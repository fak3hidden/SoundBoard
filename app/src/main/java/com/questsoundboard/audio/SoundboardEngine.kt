package com.questsoundboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.questsoundboard.data.Sound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Where soundboard audio ends up.
 */
enum class Route {
    /** Only you hear it, through the headset speakers. Good for testing. */
    MONITOR,

    /** Injected into the capture path — the game/voice chat hears it. Needs priv-app. */
    MIC,

    /** Both: teammates hear it and so do you. The usual choice. */
    MIC_AND_MONITOR,

    /** No root: play loud out of the headset so the real mic picks it up. */
    ACOUSTIC
}

/**
 * Polyphonic soundboard mixer. Multiple clips can fire at once; everything is
 * summed into one 48 kHz stereo stream and pushed to the active routes from a
 * single real-time-ish thread.
 */
class SoundboardEngine(
    private val context: Context,
    private val micInjector: MicInjector
) {

    companion object {
        private const val TAG = "SoundboardEngine"
        private const val MAX_VOICES = 8
        private const val CACHE_BUDGET_BYTES = 192 * 1024 * 1024 // ~192 MB of PCM
    }

    data class Playing(
        val voiceId: Long,
        val soundId: String,
        val name: String,
        val positionMs: Long,
        val durationMs: Long,
        val loop: Boolean
    )

    private class Voice(
        val id: Long,
        val soundId: String,
        val name: String,
        val clip: PcmDecoder.DecodedClip,
        @Volatile var position: Int,      // in samples (interleaved index)
        @Volatile var volume: Float,
        @Volatile var loop: Boolean,
        @Volatile var stopping: Boolean = false,
        @Volatile var fade: Float = 1.0f
    )

    private val cache = ConcurrentHashMap<String, PcmDecoder.DecodedClip>()
    private val cacheOrder = CopyOnWriteArrayList<String>()
    private val voices = CopyOnWriteArrayList<Voice>()
    private var nextVoiceId = 1L

    private val running = AtomicBoolean(false)
    private var mixThread: Thread? = null

    private var monitorTrack: AudioTrack? = null
    private var acousticTrack: AudioTrack? = null

    // Private setter: the JVM setter for a public `var route` would be
    // setRoute(Route), which collides with the switchRoute() entry point.
    @Volatile var route: Route = Route.MIC_AND_MONITOR
        private set

    @Volatile var masterVolume: Float = 0.9f
    @Volatile var monitorVolume: Float = 0.6f

    private val _nowPlaying = MutableStateFlow<List<Playing>>(emptyList())
    val nowPlaying: StateFlow<List<Playing>> = _nowPlaying

    private val _peakLevel = MutableStateFlow(0f)
    val peakLevel: StateFlow<Float> = _peakLevel

    val isRunning: Boolean get() = running.get()

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        openTracks()
        mixThread = Thread({ mixLoop() }, "soundboard-mixer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "engine started, route=$route")
    }

    @Synchronized
    fun stop() {
        running.set(false)
        mixThread?.join(1000)
        mixThread = null
        voices.clear()
        closeTracks()
        micInjector.stop()
        _nowPlaying.value = emptyList()
    }

    /** Re-opens output tracks for the new route. */
    @Synchronized
    fun switchRoute(newRoute: Route) {
        if (route == newRoute) return
        route = newRoute
        if (running.get()) {
            closeTracks()
            openTracks()
        }
    }

    private fun openTracks() {
        val bufferBytes = AudioTrack.getMinBufferSize(
            AudioFormatSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(AudioFormatSpec.FRAMES_PER_BUFFER * 4 * 4)

        if (route == Route.MIC || route == Route.MIC_AND_MONITOR) {
            if (!micInjector.start()) {
                Log.w(TAG, "mic injection unavailable (${micInjector.lastError}); using acoustic")
                route = Route.ACOUSTIC
            }
        }

        if (route == Route.MONITOR || route == Route.MIC_AND_MONITOR) {
            monitorTrack = buildTrack(AudioAttributes.USAGE_MEDIA, bufferBytes)?.apply { play() }
        }
        if (route == Route.ACOUSTIC) {
            // Route through the voice-call stream so it is loud and does not get
            // ducked by the game, maximising what the real headset mic picks up.
            acousticTrack = buildTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, bufferBytes)
                ?.apply { play() }
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
            } catch (_: Exception) {}
        }
    }

    private fun buildTrack(usage: Int, bufferBytes: Int): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioFormatSpec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "could not open AudioTrack usage=$usage", e)
        null
    }

    private fun closeTracks() {
        listOfNotNull(monitorTrack, acousticTrack).forEach {
            runCatching { it.pause(); it.flush(); it.release() }
        }
        monitorTrack = null
        acousticTrack = null
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL
        }
    }

    // ---------------------------------------------------------------- playback

    /** Fires a clip. Returns the voice id, or -1 if the file could not be decoded. */
    fun play(sound: Sound): Long {
        val clip = loadClip(sound.path) ?: return -1

        // Re-triggering the same pad restarts it rather than stacking echoes.
        voices.filter { it.soundId == sound.id }.forEach { voices.remove(it) }

        while (voices.size >= MAX_VOICES) {
            voices.removeAt(0)
        }

        val voice = Voice(
            id = nextVoiceId++,
            soundId = sound.id,
            name = sound.name,
            clip = clip,
            position = 0,
            volume = sound.volume,
            loop = sound.loop
        )
        voices.add(voice)
        if (!running.get()) start()
        publish()
        return voice.id
    }

    fun stopSound(soundId: String) {
        voices.filter { it.soundId == soundId }.forEach { it.stopping = true }
    }

    fun stopVoice(voiceId: Long) {
        voices.firstOrNull { it.id == voiceId }?.stopping = true
    }

    /** Panic button — kills everything instantly. */
    fun stopAll() {
        voices.clear()
        monitorTrack?.runCatching { flush() }
        publish()
    }

    fun isPlaying(soundId: String): Boolean = voices.any { it.soundId == soundId }

    // ---------------------------------------------------------------- caching

    fun loadClip(path: String): PcmDecoder.DecodedClip? {
        cache[path]?.let {
            cacheOrder.remove(path); cacheOrder.add(path)
            return it
        }
        val decoded = PcmDecoder.decode(path) ?: return null
        cache[path] = decoded
        cacheOrder.add(path)
        trimCache()
        return decoded
    }

    /** Decode ahead of time so the first press is not late. */
    fun preload(sounds: List<Sound>) {
        for (s in sounds) {
            if (!running.get() && cache.size > 64) break
            loadClip(s.path)
        }
    }

    private fun trimCache() {
        var total = cache.values.sumOf { it.sizeBytes.toLong() }
        while (total > CACHE_BUDGET_BYTES && cacheOrder.isNotEmpty()) {
            val oldest = cacheOrder.removeAt(0)
            if (voices.none { it.clip === cache[oldest] }) {
                total -= (cache.remove(oldest)?.sizeBytes ?: 0).toLong()
            }
        }
    }

    fun clearCache() {
        cache.clear(); cacheOrder.clear()
    }

    // ---------------------------------------------------------------- mix loop

    private fun mixLoop() {
        val frames = AudioFormatSpec.FRAMES_PER_BUFFER
        val samples = frames * 2
        val mixBuf = FloatArray(samples)
        val outBuf = ShortArray(samples)
        var idleIterations = 0

        while (running.get()) {
            java.util.Arrays.fill(mixBuf, 0f)
            val active = voices.toList()

            if (active.isEmpty()) {
                // Keep the tracks fed with silence so the injected mic stream
                // does not underrun and get torn down by the policy manager.
                java.util.Arrays.fill(outBuf, 0)
                writeOut(outBuf, samples)
                _peakLevel.value = 0f
                if (++idleIterations % 100 == 0) publish()
                continue
            }
            idleIterations = 0

            for (voice in active) {
                val clip = voice.clip.samples
                var pos = voice.position
                var i = 0
                val gain = voice.volume * masterVolume

                // A stopping voice ramps its gain to zero across this one
                // buffer (10 ms). Cutting mid-waveform clicks, and a click is
                // far more obvious once it has been through a voice codec.
                val startFade = voice.fade
                val endFade = if (voice.stopping) 0f else startFade
                val fadeStep = (endFade - startFade) / samples

                while (i < samples) {
                    if (pos >= clip.size) {
                        if (voice.loop && !voice.stopping) {
                            pos = 0
                        } else {
                            voices.remove(voice)
                            break
                        }
                    }
                    val remaining = min(samples - i, clip.size - pos)
                    for (k in 0 until remaining) {
                        val f = startFade + fadeStep * (i + k)
                        mixBuf[i + k] += clip[pos + k] * gain * f
                    }
                    i += remaining
                    pos += remaining
                }

                if (voice.stopping) {
                    voice.fade = endFade
                    voices.remove(voice)
                }
                voice.position = pos
            }

            var peak = 0f
            for (i in 0 until samples) {
                val v = mixBuf[i]
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                outBuf[i] = when {
                    v > 32767f -> 32767
                    v < -32768f -> -32768
                    else -> v.toInt().toShort()
                }
            }
            _peakLevel.value = (peak / 32768f).coerceIn(0f, 1f)

            writeOut(outBuf, samples)
            publish()
        }
    }

    private fun writeOut(buf: ShortArray, count: Int) {
        when (route) {
            Route.MIC -> micInjector.write(buf, count)
            Route.MIC_AND_MONITOR -> {
                micInjector.write(buf, count)
                writeMonitor(buf, count)
            }
            Route.MONITOR -> writeMonitor(buf, count)
            Route.ACOUSTIC -> acousticTrack?.runCatching {
                write(buf, 0, count, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    private fun writeMonitor(buf: ShortArray, count: Int) {
        val track = monitorTrack ?: return
        // Apply monitor-only attenuation without touching the injected stream.
        if (monitorVolume >= 0.999f) {
            runCatching { track.write(buf, 0, count, AudioTrack.WRITE_BLOCKING) }
            return
        }
        val scaled = ShortArray(count)
        for (i in 0 until count) scaled[i] = (buf[i] * monitorVolume).toInt().toShort()
        runCatching { track.write(scaled, 0, count, AudioTrack.WRITE_BLOCKING) }
    }

    private var lastPublish = 0L
    private fun publish() {
        val now = System.currentTimeMillis()
        if (now - lastPublish < 100) return
        lastPublish = now
        _nowPlaying.value = voices.map { v ->
            Playing(
                voiceId = v.id,
                soundId = v.soundId,
                name = v.name,
                positionMs = v.position / 2L * 1000L / AudioFormatSpec.SAMPLE_RATE,
                durationMs = v.clip.durationMs,
                loop = v.loop
            )
        }
    }
}
