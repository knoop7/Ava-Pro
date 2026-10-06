package com.example.ava.voice

import android.media.AudioAttributes
import android.util.Log

/**
 * Outbound call waiting tone (ringback) for the caller — loops quietly until the peer answers
 * or the call ends.
 */
internal object AvaVoiceCallRingback {
    // start() runs on the session-hub IO dispatcher while stop() is driven by inbound control
    // messages on the discovery thread; unsynchronized these can interleave and leave the
    // ringback tone playing through a connected call.
    private var player: AvaVoiceCallRingtonePlayer? = null

    @Synchronized
    fun start() {
        val context = AvaVoiceNetwork.applicationContext() ?: return
        stop()
        val ringbackPlayer = AvaVoiceCallRingtonePlayer(context)
        ringbackPlayer.start(
            uriString = AvaVoiceAudioConfig.DEFAULT_VOICE_CALL_RINGBACK,
            maxDurationMs = AvaVoiceProtocol.CALL_RING_TIMEOUT_MS,
            volume = AvaVoiceAudioConfig.CALL_RINGBACK_VOLUME,
            usage = AudioAttributes.USAGE_MEDIA
        )
        player = ringbackPlayer
        Log.d(TAG, "ringback started")
    }

    @Synchronized
    fun stop() {
        val active = player ?: return
        player = null
        active.stop()
        Log.d(TAG, "ringback stopped")
    }

    private const val TAG = "AvaVoiceRingback"
}
