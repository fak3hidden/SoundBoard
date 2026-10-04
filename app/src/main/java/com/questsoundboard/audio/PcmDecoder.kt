package com.questsoundboard.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Decodes any container/codec the headset supports (mp3, ogg/vorbis, opus,
 * wav, flac, m4a/aac) into interleaved 16-bit PCM at [AudioFormatSpec.SAMPLE_RATE]
 * stereo, so the mixer only ever deals with one format.
 */
object PcmDecoder {

    private const val TAG = "PcmDecoder"
    private const val TIMEOUT_US = 10_000L

    /** Hard cap so a user dropping in a 2-hour podcast cannot OOM the headset. */
    const val MAX_SECONDS = 600

    class DecodedClip(
        val samples: ShortArray,   // interleaved stereo
        val sampleRate: Int,
        val channels: Int
    ) {
        val frameCount: Int get() = samples.size / channels
        val durationMs: Long get() = frameCount * 1000L / sampleRate
        val sizeBytes: Int get() = samples.size * 2
    }

    fun decode(file: File): DecodedClip? = decode(file.absolutePath)

    fun decode(path: String): DecodedClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                Log.w(TAG, "no audio track in $path")
                return null
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ArrayList<ShortArray>()
            var totalShorts = 0
            val maxShorts = MAX_SECONDS * srcRate * srcChannels
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && totalShorts < maxShorts) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* handled below */ }
                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            out.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                            pcm.add(shorts)
                            totalShorts += shorts.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            if (totalShorts == 0) return null
            val flat = ShortArray(totalShorts)
            var o = 0
            for (chunk in pcm) {
                chunk.copyInto(flat, o); o += chunk.size
            }

            val stereo = toStereo(flat, srcChannels)
            val resampled = resample(stereo, srcRate, AudioFormatSpec.SAMPLE_RATE)
            return DecodedClip(resampled, AudioFormatSpec.SAMPLE_RATE, 2)
        } catch (e: Exception) {
            Log.e(TAG, "decode failed for $path", e)
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun toStereo(pcm: ShortArray, channels: Int): ShortArray = when {
        channels == 2 -> pcm
        channels == 1 -> ShortArray(pcm.size * 2).also { out ->
            for (i in pcm.indices) {
                out[i * 2] = pcm[i]; out[i * 2 + 1] = pcm[i]
            }
        }
        else -> {
            // Downmix N channels to stereo by averaging odd/even groups.
            val frames = pcm.size / channels
            ShortArray(frames * 2).also { out ->
                for (f in 0 until frames) {
                    var l = 0; var r = 0
                    for (c in 0 until channels) {
                        val s = pcm[f * channels + c].toInt()
                        if (c % 2 == 0) l += s else r += s
                    }
                    val half = (channels + 1) / 2
                    out[f * 2] = (l / half).coerceIn(-32768, 32767).toShort()
                    out[f * 2 + 1] = (r / (channels / 2).coerceAtLeast(1))
                        .coerceIn(-32768, 32767).toShort()
                }
            }
        }
    }

    /** Linear-interpolating resampler. Good enough for voice clips / memes. */
    private fun resample(stereo: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return stereo
        val inFrames = stereo.size / 2
        val outFrames = (inFrames.toLong() * to / from).toInt()
        val out = ShortArray(outFrames * 2)
        val ratio = from.toDouble() / to
        for (f in 0 until outFrames) {
            val src = f * ratio
            val i0 = src.toInt()
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = src - i0
            for (c in 0..1) {
                val a = stereo[i0 * 2 + c].toDouble()
                val b = stereo[i1 * 2 + c].toDouble()
                out[f * 2 + c] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }
}

object AudioFormatSpec {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    /** 10 ms of stereo frames — low enough latency for a soundboard. */
    const val FRAMES_PER_BUFFER = 480
}
