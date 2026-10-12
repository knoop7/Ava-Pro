package com.example.ava.sendspin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Holds [AudioManager.AUDIOFOCUS_GAIN] for Sendspin PCM output for the duration of a
 * playback session.
 *
 * Acquire on the first successful [SendspinPcmAudioOutput.start]; release on full
 * stop ([SendspinPcmAudioOutput.stop] / disconnect), and (#251) after a short grace
 * once the user paused, the stream ended, or the buffer stayed empty. Still **not**
 * released on internal pauses (track transitions, stream/clear, resync) so
 * Portal-class devices keep a stable focus holder across queue advances.
 *
 * Duck/unDuck for voice/TTS is **not** driven from here — [SendspinManager.duck] /
 * [SendspinManager.unDuck] remain the single source of truth for attenuation during
 * the voice pipeline. This controller only manages focus ownership and calls
 * [onFocusRegained] so the AudioTrack can resume after a transient focus loss.
 *
 * #251: a permanent [AudioManager.AUDIOFOCUS_LOSS] while we are *not* audibly
 * playing (Music Assistant paused, buffer drained) means another app (Netflix /
 * Emby) now owns playback. Latch [lostToOtherApp] so the playout loop cannot
 * steal focus back a few seconds later; only a real play start
 * ([noteRealPlayStart]: stream/start, user play, fresh output after pause)
 * clears it. A LOSS while PCM is actively being written keeps the old
 * mid-track re-acquire (Portal / VOICE_CALL silent-device fix).
 */
internal class SendspinAudioFocusController(
    context: Context,
    private val isActivelyPlaying: () -> Boolean = { false },
    /** Voice/TTS overlay active: keep the pre-#251b behaviour, never pause music. */
    private val isVoiceOverlayActive: () -> Boolean = { false },
    /**
     * #251b: permanent LOSS outside voice overlay = another app (MA client,
     * Netflix, Emby, ...) owns media now. Called off the focus thread's lock with
     * whether PCM was audibly flowing; the client pauses output and tells MA.
     */
    private val onPermanentYield: (wasPlaying: Boolean) -> Unit = {},
    private val onFocusRegained: () -> Unit = {}
) {
    private val tag = "SendspinAudioFocus"
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    @Volatile
    private var focusHeld = false

    /** Another app took permanent focus while we were idle; do not re-steal. */
    @Volatile
    private var lostToOtherApp = false

    private var focusRequest: AudioFocusRequest? = null

    /** elapsedRealtime of the last permanent yield; 0 = none. */
    @Volatile
    private var yieldedAtMs = 0L

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
                // #251b: a permanent LOSS always means another app's turn (MA phone
                // client, Netflix, Emby). Re-acquiring turned into a ~7 s focus war,
                // so yield: latch, abandon, pause, tell MA. Only during the voice
                // overlay keep the old mid-track re-acquire (the overlay never
                // takes GAIN itself, so a LOSS there is transient noise).
                val playing = try { isActivelyPlaying() } catch (_: Exception) { false }
                val overlay = try { isVoiceOverlayActive() } catch (_: Exception) { false }
                if (overlay && playing) {
                    synchronized(lock) { focusHeld = false }
                    Log.w(tag, "AUDIOFOCUS_LOSS during voice overlay — will re-acquire from playout")
                    return@OnAudioFocusChangeListener
                }
                synchronized(lock) {
                    focusHeld = false
                    lostToOtherApp = true
                    yieldedAtMs = SystemClock.elapsedRealtime()
                    abandonLocked()
                }
                Log.w(
                    tag,
                    if (playing) "AUDIOFOCUS_LOSS while playing — yielding (pause + notify MA)"
                    else "AUDIOFOCUS_LOSS while idle — yielding to other app until real play"
                )
                try {
                    onPermanentYield(playing)
                } catch (e: Exception) {
                    Log.w(tag, "onPermanentYield failed", e)
                }
            }
        }
    }

    /** Request permanent media focus when PCM output begins. Idempotent while held. */
    fun acquire(): Boolean {
        synchronized(lock) {
            if (focusHeld) return true
            if (lostToOtherApp) return false

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
        if (lostToOtherApp) return false
        return acquire()
    }

    /**
     * A genuine play start (stream/start, user play, fresh output after pause):
     * the user wants Sendspin audible again, so the idle-loss latch is lifted.
     */
    fun noteRealPlayStart(userInitiated: Boolean = false) {
        if (!lostToOtherApp) return
        synchronized(lock) {
            // #251b: right after a yield, a server-driven restart (stream/start,
            // output cold start) is most likely the other player's echo; only an
            // explicit user play lifts the latch inside the window.
            val sinceYield = SystemClock.elapsedRealtime() - yieldedAtMs
            if (!userInitiated && yieldedAtMs != 0L && sinceYield < YIELD_HOLD_MS) {
                Log.i(tag, "play start ${sinceYield}ms after yield — staying yielded")
                return
            }
            // Smart end: a server-driven next track must not steal focus back
            // while the app that took it (Netflix / Emby / MA client) is still
            // audibly playing, however long ago the yield was.
            if (!userInitiated && isOtherAppPlaying()) {
                Log.i(tag, "play start while another app is playing — staying yielded")
                return
            }
            if (lostToOtherApp) {
                lostToOtherApp = false
                Log.i(tag, "real play start — focus may be re-acquired")
            }
        }
    }

    fun isLostToOtherApp(): Boolean = lostToOtherApp

    /**
     * Some other app is playing media right now. Only meaningful while our own
     * output is stopped (stream end / pause): we do not hold focus then, so any
     * active music stream belongs to someone else. Covers the case where focus
     * was already released after the end grace and a video started later — we
     * never got a LOSS for that, so [lostToOtherApp] alone cannot see it.
     */
    fun isOtherAppPlaying(): Boolean =
        !focusHeld && try { audioManager.isMusicActive } catch (_: Exception) { false }

    /** Abandon focus on full stop / disconnect, and after idle / pause / stream-end grace (#251). */
    fun release() {
        synchronized(lock) {
            if (!focusHeld) return
            focusHeld = false
            abandonLocked()
        }
    }

    private fun abandonLocked() {
        run {
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

    private companion object {
        /** Window after a yield in which only a user play may re-take focus. */
        const val YIELD_HOLD_MS = 10_000L
    }
}
