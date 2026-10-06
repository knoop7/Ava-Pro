package com.example.ava.players

import android.media.AudioManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class AudioPlayerState {
    PLAYING, PAUSED, IDLE
}

@UnstableApi
class AudioPlayer(
    private val audioManager: AudioManager,
    val focusGain: Int,
    private val playerBuilder: () -> Player
) : AutoCloseable {
    private var _player: Player? = null
    private var isPlayerInit = false
    private var currentListener: Player.Listener? = null
    private val isClosed = AtomicBoolean(false)
    private val isClosing = AtomicBoolean(false)

    private val _state = MutableStateFlow(AudioPlayerState.IDLE)
    val state = _state.asStateFlow()

    val isPlaying: Boolean get() = try { _player?.isPlaying ?: false } catch (e: Exception) { false }
    val isPaused: Boolean
        get() = try {
            _player?.let {
                !it.isPlaying && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED
            } ?: false
        } catch (e: Exception) { false }
    val isStopped
        get() = try {
            _player?.let { it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED } ?: true
        } catch (e: Exception) { true }

    val currentPosition: Long get() = try { _player?.currentPosition ?: 0L } catch (e: Exception) { 0L }
    /**
     * Music keeps the loaded item after it ends, so a later read still sees the
     * song length. URL TTS does not: that player is released when the clip ends.
     */
    var retainTimeline: Boolean = false
    private var retainedDurationMs: Long = 0L
    val duration: Long get() = try {
        val live = _player?.duration
        if (live != null && live > 0 && live != C.TIME_UNSET) live
        else if (retainTimeline) retainedDurationMs else 0L
    } catch (e: Exception) { if (retainTimeline) retainedDurationMs else 0L }

    fun seekTo(positionMs: Long) {
        try { _player?.seekTo(positionMs) } catch (e: Exception) { }
    }

    /**
     * Same item is already the active song. Opening it again is a new HTTP GET,
     * and Home Assistant's ffmpeg proxy restarts that GET at byte 0.
     */
    fun isCurrentPlayback(uri: String): Boolean {
        val current = try {
            _player?.currentMediaItem?.localConfiguration?.uri?.toString()
        } catch (_: Exception) {
            null
        } ?: return false
        if (current != uri) return false
        return _state.value == AudioPlayerState.PLAYING || _state.value == AudioPlayerState.PAUSED
    }

    /** Playhead reached a known length. An earlier end is a dead pipe, not this song. */
    private fun trackReachedEnd(): Boolean {
        val durationMs = duration
        if (durationMs <= 0L) return false
        return currentPosition >= durationMs - 1_500L
    }

    private var _volume: Float = 1.0f
    var volume
        get() = _volume
        set(value) {
            _volume = value
            try { _player?.volume = value } catch (e: Exception) { }
        }

    fun init() {
        if (isClosed.get()) return

        val oldPlayer = _player
        _player = null
        oldPlayer?.let { player ->
            // Detach the listener first: stop() below transitions the old player to
            // IDLE, and a still-attached listener would run its idle-completion
            // fallback against the NEW run's callbacks.
            currentListener?.let { listener ->
                try { player.removeListener(listener) } catch (e: Exception) { }
            }
            currentListener = null
            try {
                player.stop()
                player.clearMediaItems()
            } catch (e: Exception) { }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { player.release() } catch (e: Exception) { }
            }
        }

        try {
            _player = playerBuilder().apply {
                volume = _volume
            }

            currentListener?.let { listener ->
                _player?.addListener(listener)
            }
            isPlayerInit = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init player", e)
        }
    }

    /**
     * Build the underlying player ahead of [play], so a cold start does not pay
     * ExoPlayer construction at play time. Strictly a no-op while any player
     * instance exists (initialized, preparing or playing) — [init] would release it
     * mid-flight otherwise.
     */
    fun prewarm() {
        if (isClosed.get() || isPlayerInit || _player != null) return
        init()
    }

    fun play(mediaUri: String, onCompletion: () -> Unit = {}) {
        play(listOf(mediaUri), onCompletion)
    }

    fun play(mediaUris: Iterable<String>, onCompletion: () -> Unit = {}) {
        if (isClosed.get()) {
            onCompletion()
            return
        }

        if (!isPlayerInit) init()
        if (_player == null) {
            Log.e(TAG, "Player is null, cannot play")
            onCompletion()
            return
        }

        isPlayerInit = false
        if (retainTimeline) retainedDurationMs = 0L
        val player = _player ?: run {
            onCompletion()
            return
        }

        try {
            // Detach first so stop()/clear cannot complete the previous play callback.
            currentListener?.let {
                try { player.removeListener(it) } catch (e: Exception) { }
            }
            currentListener = null
            player.stop()
            player.clearMediaItems()

            val listener = getPlayerListener(onCompletion)
            currentListener = listener
            player.addListener(listener)

            for (mediaUri in mediaUris) {
                player.addMediaItem(MediaItem.fromUri(mediaUri))
            }
            // prepare() while playWhenReady is still false. Setting play first
            // reports "not playing" against the IDLE left by stop(), and that
            // idle used to be published as the item ending.
            player.prepare()
            player.playWhenReady = true

        } catch (e: Exception) {
            Log.e(TAG, "Error playing media $mediaUris", e)
            onCompletion()
            safeClose()
        }
    }

    fun pause() {
        try {
            if (isPlaying) {
                _player?.pause()
            }
        } catch (e: Exception) { }
    }

    fun unpause() {
        try {
            if (isPaused) {
                _player?.play()
            }
        } catch (e: Exception) { }
    }

    fun stop() {
        safeClose()
    }

    /** Stops current media without invoking the [play] completion callback. */
    fun cancelPlayback() {
        if (isClosed.get()) return
        val player = _player ?: return
        try {
            currentListener?.let {
                try {
                    player.removeListener(it)
                } catch (_: Exception) {
                }
            }
            currentListener = null
            player.stop()
            player.clearMediaItems()
            isPlayerInit = false
            _state.value = AudioPlayerState.IDLE
        } catch (e: Exception) {
            Log.w(TAG, "cancelPlayback failed", e)
        }
    }

    var onDurationChanged: ((Long) -> Unit)? = null
    var onMediaMetadataChanged: ((artworkUri: String?) -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackError: (() -> Unit)? = null
    /** Permanent audio-focus loss (e.g. another GAIN holder). Not fired for rebuffer/pause. */
    var onAudioFocusLoss: (() -> Unit)? = null

    private fun getPlayerListener(onCompletion: () -> Unit) = object : Player.Listener {
        private val completionCalled = AtomicBoolean(false)
        /** First real sample. Idle before this is prepare(), not the end. */
        private var heardPlayback = false

        private fun safeComplete() {
            if (completionCalled.compareAndSet(false, true)) {
                onCompletion()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                try {
                    onPlaybackEnded?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking onPlaybackEnded", e)
                }
                safeComplete()
                if (retainTimeline) {
                    reportDurationIfKnown()
                    // Only a real end of a known-length song is idle. A proxy pipe
                    // that dies mid-track has no duration; reporting idle makes
                    // Home Assistant send the URL again from the start.
                    if (trackReachedEnd()) {
                        _state.value = AudioPlayerState.IDLE
                    }
                } else {
                    safeClose()
                }
            } else if (playbackState == Player.STATE_READY) {
                try {
                    reportDurationIfKnown()
                    val metadata = _player?.mediaMetadata
                    val artworkUri = metadata?.artworkUri?.toString()
                    onMediaMetadataChanged?.invoke(artworkUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting metadata", e)
                }
            }
        }

        override fun onTimelineChanged(
            timeline: androidx.media3.common.Timeline,
            reason: Int,
        ) {
            // Progressive / HA tts_proxy often learns duration after the first READY.
            try {
                reportDurationIfKnown()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading duration on timeline change", e)
            }
        }

        private fun reportDurationIfKnown() {
            val duration = _player?.duration ?: 0L
            if (duration > 0 && duration != C.TIME_UNSET) {
                if (retainTimeline) retainedDurationMs = duration
                onDurationChanged?.invoke(duration)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            try { onPlaybackError?.invoke() } catch (e: Exception) { Log.e(TAG, "Error invoking onPlaybackError", e) }
            onPlaybackError = null
            safeComplete()
            safeClose()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) return
            Log.w(TAG, "Playback stopped due to permanent audio focus loss")
            try {
                onAudioFocusLoss?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking onAudioFocusLoss", e)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            try {
                if (isPlaying) {
                    heardPlayback = true
                    _state.value = AudioPlayerState.PLAYING
                    try {
                        onPlaybackStarted?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking onPlaybackStarted", e)
                    }
                    return
                }
                // stop() then prepare() is still IDLE and not playing. That
                // transition is faster than the first sample. Treating it as
                // the end publishes IDLE, and Home Assistant sends the URL again.
                if (!heardPlayback) return

                if (isPaused) {
                    val userPaused = try {
                        _player?.playWhenReady == false
                    } catch (_: Exception) {
                        true
                    }
                    // Rebuffer is not a pause. Publishing PAUSED makes the
                    // controller send the URL again, which restarts the song.
                    if (retainTimeline && !userPaused) {
                        _state.value = AudioPlayerState.PLAYING
                        return
                    }
                    _state.value = AudioPlayerState.PAUSED
                    return
                }
                // URL TTS often never reaches STATE_ENDED, so an idle after
                // audio really started still finishes that session. The media
                // player stays on this item unless the playhead reached its end.
                if (retainTimeline && !trackReachedEnd()) return

                _state.value = AudioPlayerState.IDLE
                if (!retainTimeline) {
                    try {
                        onPlaybackEnded?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking onPlaybackEnded fallback", e)
                    }
                    safeComplete()
                    safeClose()
                }
            } catch (e: Exception) { }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            try {
                val metadata = _player?.mediaMetadata
                val artworkUri = metadata?.artworkUri?.toString()

                onMediaMetadataChanged?.invoke(artworkUri)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting metadata on transition", e)

                onMediaMetadataChanged?.invoke(null)
            }
        }
    }

    private fun safeClose() {
        if (isClosing.compareAndSet(false, true)) {
            try {
                close()
            } finally {
                isClosing.set(false)
            }
        }
    }

    override fun close() {
        isPlayerInit = false
        retainedDurationMs = 0L
        val playerToRelease = _player
        _player = null
        currentListener = null
        _state.value = AudioPlayerState.IDLE

        if (playerToRelease != null) {
            try {
                playerToRelease.stop()
                playerToRelease.clearMediaItems()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping player", e)
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    playerToRelease.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing player", e)
                }
            }
        }
    }

    fun destroy() {
        isClosed.set(true)
        close()
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
