package com.questsoundboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Makes the soundboard audio come out of the *microphone* as far as any game
 * (or VRChat / Rec Room / Gorilla Tag voice chat) is concerned.
 *
 * Implementation: Android's hidden `android.media.audiopolicy.AudioPolicy` API
 * supports an "injector" mix — audio written into it is fed into the capture
 * path and handed to whoever is recording. The API is gated behind
 * MODIFY_AUDIO_ROUTING (signature|privileged), which is exactly what the
 * Magisk module buys us by installing the APK as a priv-app.
 *
 * All of it is reflection, because the classes are hidden. If anything is
 * missing we report it and the engine silently falls back to acoustic mode.
 */
class MicInjector(private val context: Context) {

    companion object {
        private const val TAG = "MicInjector"
    }

    enum class State { IDLE, ACTIVE, UNAVAILABLE }

    @Volatile var state: State = State.IDLE
        private set

    @Volatile var lastError: String? = null
        private set

    private var audioPolicy: Any? = null
    private var injectionTrack: AudioTrack? = null

    val isActive: Boolean get() = state == State.ACTIVE && injectionTrack != null

    /**
     * Registers the injector policy. Returns true when real mic injection is
     * live. Must be called before any write.
     */
    @Synchronized
    fun start(): Boolean {
        if (isActive) return true
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")

            // RULE_MATCH_ATTRIBUTE_USAGE = 0x1
            val ruleMatchUsage = runCatching {
                ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            }.getOrDefault(0x1)

            // createAudioTrackSource() requires a loopback-routed mix: the
            // audio we write is looped back into the capture path instead of
            // being rendered to a device. ROUTE_FLAG_LOOP_BACK = 0x2.
            val routeFlagLoopBack = runCatching {
                mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            }.getOrDefault(0x2)

            val injectedAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val rule = ruleBuilderCls.getDeclaredConstructor().newInstance().let { rb ->
                ruleBuilderCls
                    .getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(rb, ruleMatchUsage, injectedAttributes)
                ruleBuilderCls.getMethod("build").invoke(rb)
            }

            val format = AudioFormat.Builder()
                .setSampleRate(AudioFormatSpec.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val mix = mixBuilderCls.getDeclaredConstructor(ruleCls).newInstance(rule).let { mb ->
                mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mb, format)
                mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                    .invoke(mb, routeFlagLoopBack)
                // Newer AOSP vintages let the mix declare its role explicitly.
                runCatching {
                    val mixRoleInjector = mixCls.getField("MIX_ROLE_INJECTOR").getInt(null)
                    mixBuilderCls.getMethod("setMixRole", Int::class.javaPrimitiveType)
                        .invoke(mb, mixRoleInjector)
                }
                mixBuilderCls.getMethod("build").invoke(mb)
            }

            val policy = policyBuilderCls.getDeclaredConstructor(Context::class.java)
                .newInstance(context).let { pb ->
                    policyBuilderCls.getMethod("addMix", mixCls).invoke(pb, mix)
                    policyBuilderCls.getMethod("build").invoke(pb)
                }

            val registerMethod = AudioManager::class.java.getMethod(
                "registerAudioPolicy", policyCls
            )
            val result = registerMethod.invoke(audioManager, policy) as Int
            if (result != AudioManager.SUCCESS) {
                fail("registerAudioPolicy returned $result (missing MODIFY_AUDIO_ROUTING?)")
                return false
            }

            val track = policyCls.getMethod("createAudioTrackSource", mixCls)
                .invoke(policy, mix) as? AudioTrack
                ?: run {
                    policyCls.getMethod("unregisterAudioPolicy", policyCls)
                    fail("createAudioTrackSource returned null")
                    return false
                }

            track.play()
            audioPolicy = policy
            injectionTrack = track
            state = State.ACTIVE
            lastError = null
            Log.i(TAG, "mic injection active")
            return true
        } catch (e: ClassNotFoundException) {
            fail("AudioPolicy API not reachable — hidden API policy still locked. Run the root setup, then reboot.")
        } catch (e: NoSuchMethodException) {
            fail("AudioPolicy API shape differs on this build: ${e.message}")
        } catch (e: SecurityException) {
            fail("Denied MODIFY_AUDIO_ROUTING — install the Magisk module and reboot.")
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
        return false
    }

    /** Writes one interleaved stereo frame buffer into the fake microphone. */
    fun write(buffer: ShortArray, count: Int): Int {
        val track = injectionTrack ?: return 0
        return try {
            track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "injection write failed", e)
            0
        }
    }

    @Synchronized
    fun stop() {
        try {
            injectionTrack?.let { it.pause(); it.flush(); it.release() }
        } catch (_: Exception) {}
        injectionTrack = null

        try {
            val policy = audioPolicy
            if (policy != null) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                AudioManager::class.java
                    .getMethod("unregisterAudioPolicy", policy.javaClass)
                    .invoke(am, policy)
            }
        } catch (_: Exception) {}
        audioPolicy = null
        if (state == State.ACTIVE) state = State.IDLE
    }

    private fun fail(msg: String) {
        lastError = msg
        state = State.UNAVAILABLE
        Log.w(TAG, "mic injection unavailable: $msg")
    }
}
