package com.example.ava.voice

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates UDP discovery/receive lifecycle for voice messaging.
 *
 * **Master gate:** [sync] only starts discovery/audio when [featureEnabledFlag] is true
 * ([PlayerSettings.enableVoiceMessageOverlay]). Sub-flags (receive, duplex) never bring the
 * stack up when the master switch is off.
 */
object AvaVoiceNetwork {
    private const val TAG = "AvaVoiceNetwork"

    private val featureEnabled = AtomicBoolean(false)
    private val receiveEnabled = AtomicBoolean(false)
    private val callAnswerRequired = AtomicBoolean(true)
    private val forceReceive = AtomicBoolean(false)
    private val callDuplexRefs = AtomicInteger(0)

    @Volatile
    private var applicationContext: Context? = null

    fun applicationContext(): Context? = applicationContext

    fun isFeatureEnabled(): Boolean = featureEnabled.get()

    fun isReceiveEnabled(): Boolean =
        featureEnabled.get() && (receiveEnabled.get() || forceReceive.get())

    fun isCallDuplexActive(): Boolean = callDuplexRefs.get() > 0

    fun isCallAnswerRequired(): Boolean = callAnswerRequired.get()

    /**
     * Keep the audio listener active for live duplex calls even when receive is off.
     * No-op when the voice-message master switch is off.
     */
    fun enterCallDuplex() {
        if (!featureEnabled.get()) return
        if (callDuplexRefs.incrementAndGet() == 1) {
            forceReceive.set(true)
            ensureAudioListener()
        }
    }

    fun leaveCallDuplex() {
        val remaining = callDuplexRefs.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            forceReceive.set(false)
        }
    }

    /** Reset duplex receive override when voice-message features are turned off. */
    fun resetVoiceMessageAudioState() {
        callDuplexRefs.set(0)
        forceReceive.set(false)
    }

    private fun ensureAudioListener() {
        if (!featureEnabled.get()) return
        AvaVoiceSessionHub.start()
    }

    /**
     * Apply settings from [VoiceSatelliteService.syncVoiceMessageServices].
     *
     * Order when master turns off (callers should abort sessions *before* invoking with false):
     * 1. Sessions/mic torn down by caller
     * 2. [resetVoiceMessageAudioState]
     * 3. [AvaVoiceSessionHub.stop] then release voice discovery holder
     *    (fleet may keep UDP Ava identity via [AvaVoiceDiscovery.HOLDER_FLEET])
     */
    fun sync(
        context: Context,
        featureEnabledFlag: Boolean,
        receiveEnabledFlag: Boolean,
        displayName: String = "",
        callAnswerRequiredFlag: Boolean = true
    ) {
        featureEnabled.set(featureEnabledFlag)
        receiveEnabled.set(receiveEnabledFlag)
        callAnswerRequired.set(callAnswerRequiredFlag)
        applicationContext = context.applicationContext

        if (!featureEnabledFlag) {
            resetVoiceMessageAudioState()
            AvaVoiceSessionHub.stop()
            // Keep UDP presence if fleet still holds the socket for Ava identity.
            AvaVoiceDiscovery.release(AvaVoiceDiscovery.HOLDER_VOICE)
            // Presence holders may remain — re-advertise voiceMessaging=0 promptly.
            AvaVoiceDiscovery.refreshBeaconNow()
            Log.d(TAG, "sync: master off — voice session stopped (discovery holders may remain)")
            return
        }

        val appContext = applicationContext ?: return
        AvaVoiceDiscovery.acquire(appContext, AvaVoiceDiscovery.HOLDER_VOICE, displayName)
        AvaVoiceSessionHub.start()
        AvaVoiceTransport.resetAddressCache()
        AvaVoiceDiscovery.refreshBeaconNow()
        Log.d(TAG, "sync: master on receive=$receiveEnabledFlag")
    }
}
