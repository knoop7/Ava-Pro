package com.example.ava.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Ref-counted speakerphone helper during live voice calls.
 *
 * Voice **capture** follows the intercom/message path ([AvaVoiceAudioConfig.AUDIO_SOURCE] / MIC).
 * We only toggle speakerphone here for call **playback** — never [AudioManager.MODE_IN_COMMUNICATION],
 * which misroutes the mic input on many OEM HALs (SCO instead of built-in mic).
 */
object AvaVoiceCallAudioSession {
    private const val TAG = "AvaVoiceCallAudio"

    private val lock = Any()
    private var refCount = 0
    private var previousSpeakerphone = false

    fun enter(context: Context) {
        synchronized(lock) {
            if (refCount == 0) {
                val am = audioManager(context)
                if (am == null) {
                    Log.w(TAG, "enter skipped: no AudioManager")
                } else runCatching {
                    @Suppress("DEPRECATION")
                    previousSpeakerphone = am.isSpeakerphoneOn
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    Log.d(TAG, "enter speakerphone=true (message-style, no MODE_IN_COMMUNICATION)")
                }.onFailure { Log.w(TAG, "enter failed: ${it.message}") }
            }
            refCount++
        }
    }

    fun leave(context: Context) {
        synchronized(lock) {
            if (refCount <= 0) return
            refCount--
            if (refCount > 0) return
            restoreSpeakerphone(context)
        }
    }

    /** Drop all call-surface refs when voice message is disabled while UI did not dispose cleanly. */
    fun forceLeave(context: Context) {
        synchronized(lock) {
            if (refCount <= 0) return
            refCount = 0
            restoreSpeakerphone(context)
            Log.d(TAG, "forceLeave restored speakerphone")
        }
    }

    private fun restoreSpeakerphone(context: Context) {
        val am = audioManager(context) ?: return
        runCatching {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = previousSpeakerphone
            Log.d(TAG, "leave restored speaker=$previousSpeakerphone")
        }.onFailure { Log.w(TAG, "leave failed: ${it.message}") }
    }

    private fun audioManager(context: Context): AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}
