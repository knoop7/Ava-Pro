package com.example.ava.esphome.voicesatellite

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.ava.players.AudioPlayer
import com.example.ava.players.TtsPlayer
import com.example.ava.settings.SettingState
import com.example.ava.utils.HaMediaUrl
import com.example.ava.notifications.SceneReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class VoiceSatellitePlayer(
    val ttsPlayer: TtsPlayer,
    val mediaPlayer: AudioPlayer,
    val wakeSoundPlayer: AudioPlayer,  
    volume: Float = 1.0f,
    muted: Boolean = false,
    val enableWakeSound: SettingState<Boolean>,
    val enableScreenOff: SettingState<Boolean>,
    val wakeSound: SettingState<String>,
    val wakeSound2: SettingState<String>,
    val timerFinishedSound: SettingState<String>,
    val stopSound: SettingState<String>,
    val enableStopSound: SettingState<Boolean>,
    val continuousPromptSound: SettingState<String>,
    val enableContinuousConversation: SettingState<Boolean>,
    val enableQuestionMarkContinue: SettingState<Boolean>,
    val enableExitKeywordStop: SettingState<Boolean>,
    val enableSmartContinue: SettingState<Boolean>,
    val enableStreamingTtsSubtitles: SettingState<Boolean>,
    val preserveTtsHttps: SettingState<Boolean>,
    private val duckMultiplier: Float = 0.0f
) : AutoCloseable {
    
    
    private val _haRemoteUrl = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val haRemoteUrl: kotlinx.coroutines.flow.Flow<String> = _haRemoteUrl
    private val _haRemoteUrlRight = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val haRemoteUrlRight: kotlinx.coroutines.flow.Flow<String> = _haRemoteUrlRight
    private val haRemoteUrlScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Volume fades must outlive neither the player nor a later duck/unDuck. */
    private val fadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Live HA address from the ESPHome TCP peer (same source as cover-art joining).
     * Set by [VoiceSatellite] once the API server exists.
     */
    var haHostProvider: (() -> String?)? = null

    // Hot-path snapshot: resolvePlayUrl runs on player threads where
    // runBlocking { DataStore } causes jank. Collector dies with haRemoteUrlScope in close().
    @Volatile
    private var preserveTtsHttpsSnapshot = false

    /** Resolve HA TTS / media URLs before ExoPlayer fetch. */
    fun resolvePlayUrl(url: String?): String? {
        return HaMediaUrl.resolve(url, haHostProvider?.invoke(), preserveTtsHttpsSnapshot)
    }
    
    init {
        _haRemoteUrl.tryEmit("")
        _haRemoteUrlRight.tryEmit("")
        ttsPlayer.resolveUrl = { resolvePlayUrl(it) }
        haRemoteUrlScope.launch {
            preserveTtsHttps.collect { preserveTtsHttpsSnapshot = it }
        }
    }
    
    
    private val _notificationScene = MutableStateFlow(SceneReset.IDLE)
    val notificationScene = _notificationScene.asStateFlow()
    // Display trigger stream: StateFlow dedupes, so a second HA write of the same scene does not emit,
    // the notification cannot pop again, and the countdown does not reset.
    private val _notificationSceneRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val notificationSceneRequests: kotlinx.coroutines.flow.Flow<String> = _notificationSceneRequests
    
    var onHaRemoteUrlChanged: ((String) -> Unit)? = null
    var onHaRemoteUrlRightChanged: ((String) -> Unit)? = null
    var onMediaPlay: ((String) -> Unit)? = null
    /** Non-null level writes STREAM_MUSIC for this reply; null restores the snapshot. */
    var onVoiceReplyStreamVolume: ((Float?) -> Unit)? = null
    var onMediaPause: (() -> Unit)? = null
    var onMediaResume: (() -> Unit)? = null
    var onMediaStop: (() -> Unit)? = null
    var onMediaDuration: ((Long) -> Unit)? = null
        set(value) {
            field = value
            mediaPlayer.onDurationChanged = value
        }
    var onMediaCover: ((String?) -> Unit)? = null
        set(value) {
            field = value
            mediaPlayer.onMediaMetadataChanged = value
        }
    var onHaCoverUrl: ((String) -> Unit)? = null
    var onPlaybackStarted: (() -> Unit)? = null
        set(value) {
            field = value
            mediaPlayer.onPlaybackStarted = value
        }
    var onPlaybackEnded: (() -> Unit)? = null
        set(value) {
            field = value
            mediaPlayer.onPlaybackEnded = value
        }
    
    
    val currentPosition: Long get() = mediaPlayer.currentPosition
    
    
    fun seekTo(positionMs: Long) = mediaPlayer.seekTo(positionMs)

    private var _isDucked = false
    /** This announcement called [duck]; do not lift a duck the voice session already holds. */
    private var announcementDuckOwned = false
    private val _volume = MutableStateFlow(volume)
    private val _muted = MutableStateFlow(muted)
    private var voiceOverlayActive = false
    private var savedTtsVolumeBeforeOverlay: Float? = null
    private var overlayTtsVolume: Float? = null
    private var savedWakeSoundVolumeBeforeOverlay: Float? = null

    val isWhisperPlaybackActive: Boolean get() = voiceOverlayActive

    val volume get() = _volume.asStateFlow()
    fun setVolume(value: Float) {
        _volume.value = value
        if (_muted.value) return
        mediaPlayer.volume = if (_isDucked) value * duckMultiplier else value
        if (!voiceOverlayActive) {
            ttsPlayer.volume = value
        }
    }

    /** Device STREAM_MUSIC changed. Voice overlay keeps its TTS gain until the reply ends. */
    fun onSystemVolumeChanged(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (voiceOverlayActive) {
            _volume.value = clamped
            if (!_muted.value) {
                mediaPlayer.volume = if (_isDucked) clamped * duckMultiplier else clamped
            }
            return
        }
        setVolume(clamped)
    }

    val muted get() = _muted.asStateFlow()
    fun setMuted(value: Boolean) {
        _muted.value = value
        if (value) {
            mediaPlayer.volume = 0.0f
            ttsPlayer.volume = 0.0f
        } else {
            mediaPlayer.volume = if (_isDucked) _volume.value * duckMultiplier else _volume.value
            ttsPlayer.volume = if (voiceOverlayActive) {
                overlayTtsVolume ?: _volume.value
            } else {
                _volume.value
            }
        }
    }

    fun applyWhisperWakeSoundOnly(whisperVolume: Float) {
        applyWhisperPlayback(whisperVolume)
    }

    /**
     * Voice-reply overlay. [onVoiceReplyStreamVolume] writes STREAM_MUSIC to the
     * nearest device step for [whisperVolume]; [setOverlaySoftwareGain] applies
     * the leftover so 1% slider/HA steps change loudness while speech is playing.
     */
    fun applyWhisperPlayback(whisperVolume: Float) {
        val streamLevel = whisperVolume.coerceIn(0f, 1f)
        if (!voiceOverlayActive) {
            voiceOverlayActive = true
            savedTtsVolumeBeforeOverlay = ttsPlayer.volume
            if (savedWakeSoundVolumeBeforeOverlay == null) {
                savedWakeSoundVolumeBeforeOverlay = wakeSoundPlayer.volume
            }
        }
        if (!_muted.value) {
            val callback = onVoiceReplyStreamVolume
            if (callback != null) {
                callback.invoke(streamLevel)
            } else {
                setOverlaySoftwareGain(streamLevel)
            }
        } else {
            setOverlaySoftwareGain(0f)
        }
    }

    /** Software gain for URL TTS / wake while the STREAM_MUSIC overlay is active. */
    fun setOverlaySoftwareGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        overlayTtsVolume = clamped
        if (!_muted.value) {
            ttsPlayer.volume = clamped
        }
        wakeSoundPlayer.volume = clamped
    }

    fun clearWhisperPlayback() {
        savedWakeSoundVolumeBeforeOverlay?.let { wakeSoundPlayer.volume = it }
            ?: run { wakeSoundPlayer.volume = 1f }
        savedWakeSoundVolumeBeforeOverlay = null

        if (!voiceOverlayActive) return
        voiceOverlayActive = false
        val restore = savedTtsVolumeBeforeOverlay ?: _volume.value
        savedTtsVolumeBeforeOverlay = null
        overlayTtsVolume = null
        if (!_muted.value) {
            ttsPlayer.volume = restore
        }
        onVoiceReplyStreamVolume?.invoke(null)
    }

    fun currentPlaybackVolume(): Float = _volume.value

    /** Gain currently applied to TTS output (overlay or media-linked). */
    fun ttsOutputVolume(): Float = if (_muted.value) 0f else ttsPlayer.volume

    /**
     * Warm the earcon player while the wake gates run, so a cold start does not pay
     * ExoPlayer construction between wake acceptance and the chime. Safe no-op when
     * the player already exists (e.g. a stop sound is playing).
     */
    suspend fun prewarmWakeSound() {
        if (!enableWakeSound.get()) return
        wakeSoundPlayer.prewarm()
    }

    suspend fun playWakeSound(
        wakeWordIndex: Int = 0,
        onStarting: (String?) -> Boolean = { true },
        onCompletion: () -> Unit = {},
    ) {
        val enabled = enableWakeSound.get()
        val sound = if (wakeWordIndex == 1) wakeSound2.get() else wakeSound.get()
        Log.d(TAG, "playWakeSound: enabled=$enabled, wakeWordIndex=$wakeWordIndex, sound=$sound")
        if (!onStarting(if (enabled) sound else null)) return
        if (enabled) {
            wakeSoundPlayer.play(sound, onCompletion)
        } else {
            onCompletion()
        }
    }
    
    init {
        // Song duration and position belong to this player. TTS clips must not
        // release it: that reports 0 and Home Assistant restarts the URL.
        mediaPlayer.retainTimeline = true
    }
    
    companion object {
        private const val TAG = "VoiceSatellitePlayer"
        /** Follow every HA position push for this long after play / first update. */
        private const val HA_PROGRESS_FOLLOW_WINDOW_MS = 5_000L
        /** After the follow window, re-check against HA about every 10s. */
        private const val HA_PROGRESS_RESYNC_MS = 10_000L
        /** Larger jumps are treated as seeks and accepted immediately. */
        private const val HA_PROGRESS_SEEK_THRESHOLD_MS = 2_500L
        /** HA select cannot re-fire the current option; clear after the scene is shown. */
        private const val NOTIFICATION_SCENE_SELECT_CLEAR_MS = 300L
    }

    suspend fun playTimerFinishedSound(onCompletion: () -> Unit = {}) {
        ttsPlayer.playSound(timerFinishedSound.get(), onCompletion)
    }

    /** Same ring path with an explicit sound (Dream Clock keeps its own alarm). */
    suspend fun playTimerFinishedSound(soundUri: String, onCompletion: () -> Unit = {}) {
        ttsPlayer.playSound(soundUri, onCompletion)
    }

    /**
     * HA announcement sent to the ESPHome media_player entity. Not [AudioPlayer.play]
     * on the music player: that swaps out the song, and the idle report when the
     * clip ends makes HA send the song again from the start.
     * These URLs are the clip to hear (tts_proxy included); play them even when a
     * voice reply has already marked the TTS player used.
     */
    fun playEsphomeAnnouncement(url: String) {
        val ownDuck = !_isDucked && (mediaPlayer.isPlaying || mediaPlayer.isPaused)
        if (ownDuck) {
            announcementDuckOwned = true
            duck()
        }
        ttsPlayer.playSound(url) {
            if (!announcementDuckOwned) return@playSound
            announcementDuckOwned = false
            unDuck()
        }
    }
    
    suspend fun playStopSound(onCompletion: () -> Unit = {}) {
        val enabled = enableStopSound.get()
        val sound = stopSound.get()
        Log.d(TAG, "playStopSound: enabled=$enabled, sound=$sound")
        if (enabled) {
            wakeSoundPlayer.play(sound, onCompletion)
        } else {
            onCompletion()
        }
    }
    
    fun setHaRemoteUrl(url: String, notifyCallback: Boolean = true) {
        val real = com.example.ava.webcompat.SecureContextProxy.instance.unmapUrl(url)
        if (com.example.ava.webcompat.SecureContextProxy.isLoopbackHttpUrl(real)) {
            Log.w(TAG, "Ignoring loopback ha_remote_url: $real")
            return
        }
        // Learn HA's direct port (explicit ports only) for self-built URLs.
        com.example.ava.utils.HaMediaUrl.noteHaUrl(real)
        if (!_haRemoteUrl.tryEmit(real)) {
            haRemoteUrlScope.launch {
                _haRemoteUrl.emit(real)
            }
        }
        if (notifyCallback) {
            onHaRemoteUrlChanged?.invoke(real)
        }
    }

    fun setHaRemoteUrlRight(url: String, notifyCallback: Boolean = true) {
        val real = com.example.ava.webcompat.SecureContextProxy.instance.unmapUrl(url)
        if (com.example.ava.webcompat.SecureContextProxy.isLoopbackHttpUrl(real)) {
            Log.w(TAG, "Ignoring loopback ha_remote_url_right: $real")
            return
        }
        com.example.ava.utils.HaMediaUrl.noteHaUrl(real)
        if (!_haRemoteUrlRight.tryEmit(real)) {
            haRemoteUrlScope.launch {
                _haRemoteUrlRight.emit(real)
            }
        }
        if (notifyCallback) {
            onHaRemoteUrlRightChanged?.invoke(real)
        }
    }
    
    var onHaMediaTitle: ((String) -> Unit)? = null
    var onHaMediaArtist: ((String) -> Unit)? = null
    var onHaMediaAlbum: ((String) -> Unit)? = null
    var onHaMediaDuration: ((Long) -> Unit)? = null
    var onHaMediaPosition: ((Long) -> Unit)? = null
    var onHaMediaPositionUpdatedAt: ((Long) -> Unit)? = null
    
    var onHaMediaPlayPause: (() -> Unit)? = null
    var onHaMediaPrevious: (() -> Unit)? = null
    var onHaMediaNext: (() -> Unit)? = null
    
    fun haMediaPlayPause() {
        onHaMediaPlayPause?.invoke()
    }
    
    fun haMediaPrevious() {
        onHaMediaPrevious?.invoke()
    }
    
    fun haMediaNext() {
        onHaMediaNext?.invoke()
    }
    
    var onHaVolumeLevel: ((Float) -> Unit)? = null
    var onHaRepeatMode: ((String) -> Unit)? = null
    var onHaShuffle: ((Boolean) -> Unit)? = null
    
    private val _haVolumeLevel = MutableStateFlow(1.0f)
    val haVolumeLevel = _haVolumeLevel.asStateFlow()
    
    private val _haRepeatMode = MutableStateFlow("off")
    val haRepeatMode = _haRepeatMode.asStateFlow()
    
    private val _haShuffle = MutableStateFlow(false)
    val haShuffle = _haShuffle.asStateFlow()
    
    fun setHaVolumeLevel(volume: Float) {
        _haVolumeLevel.value = volume
        onHaVolumeLevel?.invoke(volume)
    }
    
    fun setHaRepeatMode(mode: String) {
        _haRepeatMode.value = mode
        onHaRepeatMode?.invoke(mode)
    }
    
    fun setHaShuffle(shuffle: Boolean) {
        _haShuffle.value = shuffle
        onHaShuffle?.invoke(shuffle)
    }
    
    var onHaSetVolume: ((Float) -> Unit)? = null
    var onHaSetRepeat: ((String) -> Unit)? = null
    var onHaSetShuffle: ((Boolean) -> Unit)? = null
    
    fun haSetVolume(volume: Float) {
        onHaSetVolume?.invoke(volume)
    }
    
    fun haSetRepeat(mode: String) {
        onHaSetRepeat?.invoke(mode)
    }
    
    fun haSetShuffle(shuffle: Boolean) {
        onHaSetShuffle?.invoke(shuffle)
    }
    
    var onHaPlaybackStateWithMetadata: ((Boolean, Boolean) -> Unit)? = null
    
    private val _haPlaybackState = MutableStateFlow(false)
    val haPlaybackState = _haPlaybackState.asStateFlow()
    
    private var _haMediaTitleCache: String = ""
    val haMediaTitleCache: String get() = _haMediaTitleCache
    
    private var _haMediaArtistCache: String = ""
    val haMediaArtistCache: String get() = _haMediaArtistCache

    private var _haMediaAlbumCache: String = ""
    val haMediaAlbumCache: String get() = _haMediaAlbumCache

    private var _haMediaDurationMs: Long = 0L
    val haMediaDurationMs: Long get() = _haMediaDurationMs

    private var _haMediaPositionMs: Long = 0L
    val haMediaPositionMs: Long get() = _haMediaPositionMs

    private var _haMediaPositionUpdatedAtEpochMs: Long = 0L

    /**
     * Local HA progress clock. HA often pushes media_position for only a few
     * seconds then stops; we follow those early updates, then extrapolate and
     * only re-sync from HA about every [HA_PROGRESS_RESYNC_MS] (or on seek).
     */
    private var haProgressAnchorMs: Long = 0L
    private var haProgressAnchorElapsedRealtime: Long = 0L
    private var lastHaProgressResyncElapsedRealtime: Long = 0L
    /** While playing, follow every HA position push until this elapsedRealtime. */
    private var haProgressFollowUntilElapsedRealtime: Long = 0L

    private var _haMediaCoverCache: String = ""
    val haMediaCoverCache: String get() = _haMediaCoverCache
    
    fun setHaPlaybackState(isPlaying: Boolean) {
        if (_haPlaybackState.value && !isPlaying) {
            val frozen = interpolatedHaPositionMs()
            reanchorHaProgress(frozen)
            _haMediaPositionMs = frozen
        } else if (!_haPlaybackState.value && isPlaying) {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            reanchorHaProgress(_haMediaPositionMs.coerceAtLeast(0L), nowElapsed)
            lastHaProgressResyncElapsedRealtime = nowElapsed
            haProgressFollowUntilElapsedRealtime = nowElapsed + HA_PROGRESS_FOLLOW_WINDOW_MS
        }
        _haPlaybackState.value = isPlaying
        val hasMetadata = _haMediaTitleCache.isNotEmpty() ||
            _haMediaArtistCache.isNotEmpty() ||
            _haMediaAlbumCache.isNotEmpty() ||
            _haMediaCoverCache.isNotEmpty()
        onHaPlaybackStateWithMetadata?.invoke(isPlaying, hasMetadata)
    }
    
    fun clearHaMediaCache() {
        _haMediaTitleCache = ""
        _haMediaArtistCache = ""
        _haMediaAlbumCache = ""
        _haMediaCoverCache = ""
        _haMediaDurationMs = 0L
        _haMediaPositionMs = 0L
        _haMediaPositionUpdatedAtEpochMs = 0L
        haProgressAnchorMs = 0L
        haProgressAnchorElapsedRealtime = 0L
        lastHaProgressResyncElapsedRealtime = 0L
        haProgressFollowUntilElapsedRealtime = 0L
    }
    
    fun setHaMediaTitle(title: String) {
        val titleChanged = title != _haMediaTitleCache
        _haMediaTitleCache = title
        // New track identity: drop sticky credits + duration so show()/lyrics
        // cannot pair Song B with Artist A or Song A's length.
        if (titleChanged && title.isNotEmpty()) {
            _haMediaArtistCache = ""
            _haMediaAlbumCache = ""
            _haMediaDurationMs = 0L
        }
        onHaMediaTitle?.invoke(title)
        if (title.isNotEmpty() && _haPlaybackState.value) {
            onHaPlaybackStateWithMetadata?.invoke(true, true)
        }
    }
    
    fun setHaMediaArtist(artist: String) {
        _haMediaArtistCache = artist
        onHaMediaArtist?.invoke(artist)
        if (artist.isNotEmpty() && _haPlaybackState.value) {
            onHaPlaybackStateWithMetadata?.invoke(true, true)
        }
    }

    fun setHaMediaAlbum(album: String) {
        _haMediaAlbumCache = album
        onHaMediaAlbum?.invoke(album)
        if (album.isNotEmpty() && _haPlaybackState.value) {
            onHaPlaybackStateWithMetadata?.invoke(true, true)
        }
    }

    fun setHaMediaDuration(durationMs: Long) {
        if (durationMs > 0L) {
            _haMediaDurationMs = durationMs
            onHaMediaDuration?.invoke(durationMs)
        }
    }

    fun setHaMediaPosition(positionMs: Long) {
        if (positionMs < 0L) return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val predicted = if (_haPlaybackState.value && haProgressAnchorElapsedRealtime > 0L) {
            haProgressAnchorMs + (nowElapsed - haProgressAnchorElapsedRealtime)
        } else {
            _haMediaPositionMs
        }
        val sinceResync = nowElapsed - lastHaProgressResyncElapsedRealtime
        if (lastHaProgressResyncElapsedRealtime == 0L) {
            // First position of this play session: follow HA for a short window.
            haProgressFollowUntilElapsedRealtime = nowElapsed + HA_PROGRESS_FOLLOW_WINDOW_MS
        }
        val inFollowWindow = nowElapsed <= haProgressFollowUntilElapsedRealtime
        val acceptHa =
            inFollowWindow ||
                haProgressAnchorElapsedRealtime == 0L ||
                !_haPlaybackState.value ||
                sinceResync >= HA_PROGRESS_RESYNC_MS ||
                kotlin.math.abs(positionMs - predicted) >= HA_PROGRESS_SEEK_THRESHOLD_MS

        // Early HA pushes (follow window) + ~10s validation / seek: re-anchor.
        // In between, ignore spam and let interpolatedHaPositionMs() run.
        if (!acceptHa) return

        _haMediaPositionMs = positionMs
        reanchorHaProgress(positionMs, nowElapsed)
        lastHaProgressResyncElapsedRealtime = nowElapsed
        // Local receive time as updated_at fallback when HA omits / stops sending it.
        _haMediaPositionUpdatedAtEpochMs = System.currentTimeMillis()
        onHaMediaPosition?.invoke(positionMs)
    }

    fun setHaMediaPositionUpdatedAt(raw: String) {
        val epochMs = parseHaTimestampEpochMs(raw)
        if (epochMs <= 0L) return
        _haMediaPositionUpdatedAtEpochMs = epochMs
        // Align monotonic clock to HA's age when we already have a position.
        if (haProgressAnchorElapsedRealtime > 0L || _haMediaPositionMs > 0L) {
            val ageMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
            haProgressAnchorMs = _haMediaPositionMs.coerceAtLeast(0L)
            haProgressAnchorElapsedRealtime =
                android.os.SystemClock.elapsedRealtime() - ageMs
        }
        onHaMediaPositionUpdatedAt?.invoke(epochMs)
    }

    /**
     * Playing: local clock from last accepted HA position (or updated_at).
     * Paused: frozen base. HA often stops pushing after a few seconds.
     */
    fun interpolatedHaPositionMs(): Long {
        if (!_haPlaybackState.value) {
            return cappedHaPosition(_haMediaPositionMs)
        }
        if (haProgressAnchorElapsedRealtime > 0L) {
            val elapsed =
                android.os.SystemClock.elapsedRealtime() - haProgressAnchorElapsedRealtime
            return cappedHaPosition(haProgressAnchorMs + elapsed.coerceAtLeast(0L))
        }
        val base = _haMediaPositionMs.coerceAtLeast(0L)
        if (_haMediaPositionUpdatedAtEpochMs <= 0L) {
            return cappedHaPosition(base)
        }
        val elapsed = System.currentTimeMillis() - _haMediaPositionUpdatedAtEpochMs
        return cappedHaPosition(base + elapsed.coerceAtLeast(0L))
    }

    private fun reanchorHaProgress(
        positionMs: Long,
        elapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        haProgressAnchorMs = positionMs.coerceAtLeast(0L)
        haProgressAnchorElapsedRealtime = elapsedRealtime
        _haMediaPositionMs = haProgressAnchorMs
    }

    private fun cappedHaPosition(positionMs: Long): Long {
        val pos = positionMs.coerceAtLeast(0L)
        return if (_haMediaDurationMs > 0L) pos.coerceAtMost(_haMediaDurationMs) else pos
    }

    private fun parseHaTimestampEpochMs(raw: String): Long {
        raw.trim().toDoubleOrNull()?.let { numeric ->
            return if (numeric < 1_000_000_000_000.0) {
                (numeric * 1000.0).toLong()
            } else {
                numeric.toLong()
            }
        }
        return try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun setHaCoverUrl(coverUrl: String) {
        _haMediaCoverCache = coverUrl
        onHaCoverUrl?.invoke(coverUrl)
        if (coverUrl.isNotEmpty() && _haPlaybackState.value) {
            onHaPlaybackStateWithMetadata?.invoke(true, true)
        }
    }
    
    var onNotificationSceneChanged: ((String) -> Unit)? = null
    private var clearNotificationSceneJob: Job? = null

    fun setNotificationScene(sceneId: String) {
        val token = sceneId.trim()
        clearNotificationSceneJob?.cancel()
        if (SceneReset.isHideCommand(token)) {
            _notificationScene.value = SceneReset.IDLE
            onNotificationSceneChanged?.invoke(SceneReset.IDLE)
            _notificationSceneRequests.tryEmit("")
            return
        }
        _notificationScene.value = token
        onNotificationSceneChanged?.invoke(token)
        _notificationSceneRequests.tryEmit(token)
        clearNotificationSceneJob = fadeScope.launch {
            delay(NOTIFICATION_SCENE_SELECT_CLEAR_MS)
            _notificationScene.value = SceneReset.IDLE
        }
    }

    private var fadeJob: Job? = null
    private var continuousPromptFadeJob: Job? = null

    suspend fun playContinuousPromptSound(onCompletion: () -> Unit = {}) {
        val sound = continuousPromptSound.get()
        Log.d(TAG, "playContinuousPromptSound: sound=$sound")
        continuousPromptFadeJob?.cancel()
        val peak = wakeSoundPlayer.volume.let { v ->
            if (v <= 0.01f) 1f else v.coerceIn(0f, 1f)
        }
        wakeSoundPlayer.volume = 0f
        wakeSoundPlayer.play(sound) {
            continuousPromptFadeJob?.cancel()
            continuousPromptFadeJob = null
            wakeSoundPlayer.volume = peak
            onCompletion()
        }
        // Light fade only — avoid click from cold Exo start / hard cut at end.
        continuousPromptFadeJob = fadeScope.launch {
            val fadeInMs = 70L
            val fadeOutMs = 80L
            val fadeOutAtMs = 420L
            val startedAt = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= fadeInMs) break
                wakeSoundPlayer.volume = peak * (elapsed.toFloat() / fadeInMs)
                delay(16)
            }
            wakeSoundPlayer.volume = peak
            val untilFadeOut = (fadeOutAtMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
            delay(untilFadeOut)
            val fadeOutStarted = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - fadeOutStarted
                if (elapsed >= fadeOutMs) break
                wakeSoundPlayer.volume = peak * (1f - elapsed.toFloat() / fadeOutMs)
                delay(16)
            }
            wakeSoundPlayer.volume = 0f
        }
    }

    fun duck() {
        _isDucked = true
        if (!_muted.value) {
            fadeJob?.cancel()
            fadeJob = fadeScope.launch {
                val startVolume = mediaPlayer.volume
                val targetVolume = _volume.value * duckMultiplier
                val steps = 10
                val stepDelay = 30L
                for (i in 1..steps) {
                    val progress = i.toFloat() / steps
                    mediaPlayer.volume = startVolume + (targetVolume - startVolume) * progress
                    delay(stepDelay)
                }
            }
        }
    }

    fun unDuck() {
        _isDucked = false
        if (!_muted.value) {
            fadeJob?.cancel()
            fadeJob = fadeScope.launch {
                val startVolume = mediaPlayer.volume
                val targetVolume = _volume.value
                val steps = 15
                val stepDelay = 40L
                for (i in 1..steps) {
                    val progress = i.toFloat() / steps
                    mediaPlayer.volume = startVolume + (targetVolume - startVolume) * progress
                    delay(stepDelay)
                }
            }
        }
    }

    override fun close() {
        continuousPromptFadeJob?.cancel()
        continuousPromptFadeJob = null
        fadeJob?.cancel()
        fadeJob = null
        clearNotificationSceneJob?.cancel()
        clearNotificationSceneJob = null
        fadeScope.cancel()
        haRemoteUrlScope.cancel()
        ttsPlayer.close()
        mediaPlayer.close()
        wakeSoundPlayer.close()
    }
}
