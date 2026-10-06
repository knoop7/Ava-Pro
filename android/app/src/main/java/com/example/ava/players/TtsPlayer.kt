package com.example.ava.players

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.ava.audio.PlaybackEnergyMonitor

@OptIn(UnstableApi::class)
class TtsPlayer
    (private val player: AudioPlayer) : AutoCloseable {

    private var _ttsPlayed: Boolean = false
    val ttsPlayed: Boolean
        get() = _ttsPlayed

    private var onCompletion: (() -> Unit)? = null

    /**
     * Optional HA URL normalizer (relative `/api/...` → reachable absolute URL).
     * Wired by [com.example.ava.esphome.voicesatellite.VoiceSatellitePlayer].
     */
    var resolveUrl: (String?) -> String? = { it }
    
    
    var onPlaybackEnded: (() -> Unit)? = null
        set(value) {
            field = value
            player.onPlaybackEnded = value
        }

    /** Resolved URL of the current/last URL TTS playback (for reachability probes). */
    var lastPlayedUrl: String? = null
        private set

    var onTtsDurationReady: ((Long) -> Unit)? = null
    var onTtsPlaybackStarted: (() -> Unit)? = null
    var onTtsProgressUpdate: ((currentMs: Long, totalMs: Long) -> Unit)? = null
    var onTtsPlaybackError: (() -> Unit)? = null
    /** Permanent audio-focus loss while URL TTS is active (Sendspin GAIN, etc.). */
    var onAudioFocusLoss: (() -> Unit)? = null
    
    private var progressHandler: android.os.Handler? = null
    private var progressRunnable: Runnable? = null
    
    private fun startProgressTracking() {
        stopProgressTracking()
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    // total may be 0 for progressive HA tts_proxy — still emit position
                    // so session watchdogs can see that playback is advancing.
                    onTtsProgressUpdate?.invoke(currentPosition, duration)
                    progressHandler?.postDelayed(this, 100)
                }
            }
        }
        progressHandler?.post(progressRunnable!!)
    }
    
    private fun stopProgressTracking() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressRunnable = null
        progressHandler = null
    }

    val isPlaying get() = player.isPlaying
    /** Buffering or paused: media is loaded but not currently outputting audio. */
    val isPaused get() = player.isPaused
    /** ExoPlayer idle/ended: playback is truly over (not a rebuffer). */
    val isStopped get() = player.isStopped
    val currentPosition: Long get() = player.currentPosition
    val duration: Long get() = player.duration

    var volume
        get() = player.volume
        set(value) {
            player.volume = value
        }

    fun runStart(onCompletion: () -> Unit) {
        // Detach any stale playback listener BEFORE installing the new completion
        // handler: streaming WAV (bogus 0x7FFFFFF duration header) never reaches
        // ENDED, so after a triggerCompletion() finish the old ExoPlayer keeps its
        // listener. init() then stops that player, and the listener's idle
        // fallback would fire the just-installed handler — hard-cutting the brand
        // new session to idle right at RUN_START.
        player.cancelPlayback()
        this.onCompletion = onCompletion
        _ttsPlayed = false
        player.init()
    }

    fun runEnd() {
        if (!_ttsPlayed) {
            fireAndRemoveCompletionHandler()
        }
        _ttsPlayed = false
        stopProgressTracking()
        disablePlaybackEnergyTap()
        onTtsDurationReady = null
        onTtsPlaybackStarted = null
        onTtsProgressUpdate = null
        onTtsPlaybackError = null
        onAudioFocusLoss = null
        player.onAudioFocusLoss = null
    }

    fun markAsPlayed() {
        _ttsPlayed = true
    }
    
    fun triggerCompletion() {
        fireAndRemoveCompletionHandler()
    }
    
    fun playTts(ttsUrl: String?) {
        val playUrl = resolveUrl(ttsUrl)
        if (playUrl != ttsUrl) {
            Log.d(TAG, "playTts called: url=$ttsUrl resolved=$playUrl")
        } else {
            Log.d(TAG, "playTts called: url=$ttsUrl")
        }
        if (!playUrl.isNullOrBlank()) {
            _ttsPlayed = true
            lastPlayedUrl = playUrl
            // Keep listening: progressive tts_proxy often reports duration only after
            // more bytes arrive (timeline refresh), not on the first READY.
            player.onDurationChanged = { durationMs ->
                Log.d(TAG, "onDurationChanged: $durationMs ms")
                onTtsDurationReady?.invoke(durationMs)
            }
            player.onPlaybackStarted = {
                Log.d(TAG, "onPlaybackStarted triggered")
                PlaybackEnergyMonitor.setEnabled(true)
                onTtsPlaybackStarted?.invoke()
                startProgressTracking()
                player.onPlaybackStarted = null
            }
            player.onPlaybackError = {
                Log.w(TAG, "TTS playback error for url=$playUrl")
                onTtsPlaybackError?.invoke()
                player.onPlaybackError = null
            }
            player.onAudioFocusLoss = {
                Log.w(TAG, "TTS lost audio focus for url=$playUrl")
                onAudioFocusLoss?.invoke()
            }
            // Completion for progressive HA tts_proxy: TTS_STREAM_END (streaming) or
            // classic URL watchdog / focus-loss (ExoPlayer often never ENDED).
            player.play(playUrl) {
                stopProgressTracking()
                player.onDurationChanged = null
                player.onPlaybackStarted = null
                player.onPlaybackError = null
                player.onAudioFocusLoss = null
                disablePlaybackEnergyTap()
                fireAndRemoveCompletionHandler()
            }
        } else {
            Log.w(TAG, "TTS URL is null or blank")
        }
    }

    fun playSound(soundUrl: String?, onCompletion: () -> Unit) {
        val resolved = resolveUrl(soundUrl)
        if (resolved.isNullOrBlank()) {
            Log.w(TAG, "Sound URL is null or blank")
            onCompletion()
            return
        }
        // Timer ring / notification sounds share this player with TTS but are NOT
        // TTS — never route them through playAnnouncement. The onTts* relays still
        // hold the previous voice session's closures (each captured that session's
        // caption text; nothing clears them at session end), so firing them here
        // resurrected the old floating subtitles + speaking style mid-ring.
        // _ttsPlayed stays untouched too: the state machine reads it to decide
        // whether the current voice session's TTS still needs playing.
        // onPlaybackEnded is the voice reply's "clip finished" signal. A timer
        // chime or media-player announcement must not fire it: that completes the
        // reply and Home Assistant sends another tts_proxy URL.
        player.onDurationChanged = null
        player.onPlaybackError = null
        onPlaybackEnded = null
        player.onPlaybackStarted = {
            PlaybackEnergyMonitor.setEnabled(true)
            player.onPlaybackStarted = null
        }
        player.play(resolved) {
            player.onPlaybackStarted = null
            disablePlaybackEnergyTap()
            onCompletion()
        }
    }

    fun playAnnouncement(mediaUrl: String?, preannounceUrl: String?, onCompletion: () -> Unit) {
        val resolvedMedia = resolveUrl(mediaUrl)
        val resolvedPreannounce = resolveUrl(preannounceUrl)
        Log.d(
            TAG,
            "playAnnouncement: mediaUrl=$mediaUrl, preannounceUrl=$preannounceUrl" +
                if (resolvedMedia != mediaUrl || resolvedPreannounce != preannounceUrl) {
                    " resolvedMedia=$resolvedMedia resolvedPreannounce=$resolvedPreannounce"
                } else {
                    ""
                },
        )
        if (resolvedMedia.isNullOrBlank()) {
            Log.w(TAG, "Media URL is null or blank")
            onCompletion()
            return
        }

        _ttsPlayed = true
        player.onDurationChanged = { durationMs ->
            onTtsDurationReady?.invoke(durationMs)
            player.onDurationChanged = null
        }

        val notifyPlaybackStarted: () -> Unit = {
            PlaybackEnergyMonitor.setEnabled(true)
            onTtsPlaybackStarted?.invoke()
            startProgressTracking()
        }

        if (resolvedPreannounce.isNullOrBlank()) {
            player.onPlaybackStarted = {
                notifyPlaybackStarted()
                player.onPlaybackStarted = null
            }
            player.onPlaybackError = {
                Log.w(TAG, "Announcement playback error for url=$resolvedMedia")
                onTtsPlaybackError?.invoke()
                player.onPlaybackError = null
            }
            player.play(resolvedMedia) {
                stopProgressTracking()
                player.onDurationChanged = null
                player.onPlaybackStarted = null
                player.onPlaybackError = null
                disablePlaybackEnergyTap()
                onCompletion()
            }
            return
        }

        // Play preannounce with a load timeout — if it doesn't start within 3s, skip it
        var preannounceTimedOut = false
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!preannounceTimedOut) {
                preannounceTimedOut = true
                Log.w(TAG, "Preannounce load timeout, skipping to main media")
                player.stop()
                player.onPlaybackStarted = {
                    notifyPlaybackStarted()
                    player.onPlaybackStarted = null
                }
                player.onPlaybackError = {
                    Log.w(TAG, "Announcement playback error for url=$resolvedMedia (after preannounce timeout)")
                    onTtsPlaybackError?.invoke()
                    player.onPlaybackError = null
                }
                player.play(resolvedMedia) {
                    stopProgressTracking()
                    player.onDurationChanged = null
                    player.onPlaybackStarted = null
                    player.onPlaybackError = null
                    disablePlaybackEnergyTap()
                    onCompletion()
                }
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 3000)

        var mainPlaybackNotified = false
        player.onPlaybackStarted = {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            if (!mainPlaybackNotified) {
                mainPlaybackNotified = true
                notifyPlaybackStarted()
            }
            player.onPlaybackStarted = null
        }
        player.onPlaybackError = {
            Log.w(
                TAG,
                "Announcement playback error for preannounce=$resolvedPreannounce, media=$resolvedMedia",
            )
            onTtsPlaybackError?.invoke()
            player.onPlaybackError = null
        }

        player.play(listOf(resolvedPreannounce, resolvedMedia)) {
            if (!preannounceTimedOut) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                stopProgressTracking()
                player.onDurationChanged = null
                player.onPlaybackStarted = null
                player.onPlaybackError = null
                disablePlaybackEnergyTap()
                onCompletion()
            }
        }
    }

    /**
     * Stops an in-flight HTTP TTS stream without clearing the session completion handler
     * from [runStart]. Used when PCM streaming takes over in streaming TTS mode.
     */
    fun cancelActivePlayback() {
        stopProgressTracking()
        player.onDurationChanged = null
        player.onPlaybackStarted = null
        player.onPlaybackError = null
        player.onAudioFocusLoss = null
        player.cancelPlayback()
        disablePlaybackEnergyTap()
    }

    fun stop() {
        onCompletion = null
        _ttsPlayed = false
        disablePlaybackEnergyTap()
        onTtsPlaybackError = null
        onAudioFocusLoss = null
        player.onAudioFocusLoss = null
        player.stop()
    }

    private fun disablePlaybackEnergyTap() {
        PlaybackEnergyMonitor.setEnabled(false)
        PlaybackEnergyMonitor.reset()
    }

    private fun fireAndRemoveCompletionHandler() {
        val completion = onCompletion
        onCompletion = null
        completion?.invoke()
    }

    override fun close() {
        player.close()
    }

    companion object {
        private const val TAG = "TtsPlayer"
    }
}