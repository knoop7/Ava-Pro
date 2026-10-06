package com.example.ava.sendspin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Holds [AudioManager.AUDIOFOCUS_GAIN] for Sendspin PCM output for the duration of a
 * playback session.
 *
 * Acquire on the first successful [SendspinPcmAudioOutput.start]; release only on full
 * stop ([SendspinPcmAudioOutput.stop] / disconnect). Intentionally **not** released on
 * [SendspinPcmAudioOutput.pause] (track transitions, stream/clear) so Portal-class
 * devices keep a stable focus holder across queue advances.
 *
 * Duck/unDuck for voice/TTS is **not** driven from here — [SendspinManager.duck] /
 * [SendspinManager.unDuck] remain the single source of truth for attenuation during
 * the voice pipeline. This controller only manages focus ownership and calls
 * [onFocusRegained] so the AudioTrack can resume after a transient focus loss.
 */
internal class SendspinAudioFocusController(
    context: Context,
    private val onFocusRegained: () -> Unit = {}
) {
    private val tag = "SendspinAudioFocus"
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    @Volatile
    private var focusHeld = false

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(tag, "AUDIOFOCUS_GAIN")
                synchronized(lock) {
                    focusHeld = true
                }
                try {
                    onFocusRegained()
                } catch (e: Exception) {
                    Log.w(tag, "onFocusRegained failed", e)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Voice/TTS uses TRANSIENT_MAY_DUCK; SendspinManager.duck() already
                // attenuates PCM — do not abandon focus here.
                Log.d(tag, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (keeping focus)")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(tag, "AUDIOFOCUS_LOSS_TRANSIENT (keeping focus)")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss (e.g. another GAIN holder). Clear local latch so the
                // playout loop can re-request without waiting for the next track
                // transition — otherwise PCM keeps writing while the device stays muted
                // until process death.
                Log.w(tag, "AUDIOFOCUS_LOSS — will re-acquire from playout")
                synchronized(lock) {
                    focusHeld = false
                }
            }
        }
    }

    /** Request permanent media focus when PCM output begins. Idempotent while held. */
    fun acquire(): Boolean {
        synchronized(lock) {
            if (focusHeld) return true

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                    .also { focusRequest = it }
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) {
                Log.w(tag, "requestAudioFocus denied (result=$result)")
            }
            return focusHeld
        }
    }

    /**
     * Mid-play recovery: if focus was stolen, ask again without requiring
     * [SendspinPcmAudioOutput.start]. Safe to call every playout tick.
     */
    fun ensureHeld(): Boolean {
        if (focusHeld) return true
        return acquire()
    }

    /** Abandon focus on full playback stop / disconnect only. */
    fun release() {
        synchronized(lock) {
            if (!focusHeld) return
            focusHeld = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(focusListener)
                }
            } catch (e: Exception) {
                Log.w(tag, "abandonAudioFocus failed", e)
            }
        }
    }

    fun isHeld(): Boolean = focusHeld
}
