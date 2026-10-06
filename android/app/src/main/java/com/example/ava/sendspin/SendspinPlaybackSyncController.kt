package com.example.ava.sendspin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Playback Sync Controller
 * 
 * Monitors upstream playback state (position, duration, play/pause/stop) and
 * coordinates local playback timing for multi-room audio synchronization.
 * 
 * Key responsibilities:
 * 1. Track server-reported playback position and duration
 * 2. Calculate optimal start time for new playback
 * 3. Handle play/pause/stop state transitions
 * 4. Provide offset adjustments for multi-room sync
 */
class SendspinPlaybackSyncController {
    companion object {
        // Precision sync: tight tolerance for multi-device sync
        private const val SYNC_TOLERANCE_MS = 20L  // ±20ms tolerance
        
        // Maximum offset adjustment per update (for smooth correction)
        private const val MAX_OFFSET_ADJUSTMENT_MS = 50L
        
        // Update rate for position tracking
        private const val POSITION_UPDATE_INTERVAL_MS = 50L
    }
    
    /**
     * Playback state from server
     */
    enum class PlayState {
        STOPPED,
        PLAYING,
        PAUSED,
        BUFFERING
    }
    
    /**
     * Sync state for UI/debugging
     */
    data class SyncState(
        val playState: PlayState = PlayState.STOPPED,
        val serverPositionMs: Long = 0L,
        val serverDurationMs: Long = 0L,
        val localPositionMs: Long = 0L,
        val syncOffsetMs: Long = 0L,
        val isSynced: Boolean = false,
        val lastUpdateTimeMs: Long = 0L
    )

    data class RecoveryPlan(
        val shouldStart: Boolean,
        val minQueuedChunks: Int,
        val primeSilenceMs: Int,
        val startupLatencyMs: Long,
        val reason: String
    )
    
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    // Server-reported values
    private var serverPlayState = PlayState.STOPPED
    private var serverPositionMs = 0L
    private var serverDurationMs = 0L
    private var serverPositionUpdateTimeMs = 0L
    
    // Local tracking
    private var localPlayStartTimeMs = 0L
    private var localPlayStartPositionMs = 0L
    private var isLocalPlaying = false
    
    // Sync offset (positive = local is ahead, negative = local is behind)
    private var currentSyncOffsetMs = 0L
    
    // EMA for smooth offset tracking
    private var offsetEmaMs = 0.0
    private val offsetEmaAlpha = 0.3

    // Gentle recovery state after discontinuity/underrun.
    private var recovering = false
    private var recoveryStartedAtMs = 0L
    private var lastDisturbanceAtMs = 0L
    
    /**
     * Called when server reports playback position update
     * 
     * @param positionMs Current playback position in milliseconds
     * @param durationMs Total track duration in milliseconds
     * @param state Current play state (playing, paused, stopped)
     */
    fun onServerPositionUpdate(positionMs: Long, durationMs: Long, state: PlayState) {
        val nowMs = System.currentTimeMillis()
        
        serverPositionMs = positionMs
        serverDurationMs = durationMs
        serverPlayState = state
        serverPositionUpdateTimeMs = nowMs
        
        // Calculate expected local position
        val expectedLocalPositionMs = if (isLocalPlaying && localPlayStartTimeMs > 0) {
            localPlayStartPositionMs + (nowMs - localPlayStartTimeMs)
        } else {
            positionMs
        }
        
        // Calculate sync offset
        val rawOffsetMs = expectedLocalPositionMs - positionMs
        
        // Update EMA
        offsetEmaMs = offsetEmaMs * (1.0 - offsetEmaAlpha) + rawOffsetMs.toDouble() * offsetEmaAlpha
        currentSyncOffsetMs = offsetEmaMs.toLong()
        
        // Check if synced (within tolerance)
        val isSynced = kotlin.math.abs(currentSyncOffsetMs) <= SYNC_TOLERANCE_MS
        
        _syncState.value = SyncState(
            playState = state,
            serverPositionMs = positionMs,
            serverDurationMs = durationMs,
            localPositionMs = expectedLocalPositionMs,
            syncOffsetMs = currentSyncOffsetMs,
            isSynced = isSynced,
            lastUpdateTimeMs = nowMs
        )
    }
    
    /**
     * Called when server signals play start
     * 
     * @param startPositionMs Position to start playback from
     * @param serverTimeUs Server timestamp for sync reference
     */
    fun onServerPlay(startPositionMs: Long, serverTimeUs: Long) {
        val nowMs = System.currentTimeMillis()
        
        serverPlayState = PlayState.PLAYING
        serverPositionMs = startPositionMs
        serverPositionUpdateTimeMs = nowMs
        
        // Reset local tracking
        localPlayStartTimeMs = nowMs
        localPlayStartPositionMs = startPositionMs
        isLocalPlaying = true
        
        // Reset offset tracking
        offsetEmaMs = 0.0
        currentSyncOffsetMs = 0L
        recovering = false
        recoveryStartedAtMs = 0L

        updateSyncState()
    }
    
    /**
     * Called when server signals pause
     */
    fun onServerPause() {
        serverPlayState = PlayState.PAUSED
        isLocalPlaying = false
        recovering = false
        recoveryStartedAtMs = 0L

        updateSyncState()
    }
    
    /**
     * Called when server signals stop
     */
    fun onServerStop() {
        serverPlayState = PlayState.STOPPED
        serverPositionMs = 0L
        isLocalPlaying = false
        localPlayStartTimeMs = 0L
        localPlayStartPositionMs = 0L
        recovering = false
        recoveryStartedAtMs = 0L

        updateSyncState()
    }
    
    /**
     * Called when local playback actually starts (audio output begins)
     * 
     * @param actualStartTimeMs System time when audio output started
     */
    fun onLocalPlaybackStarted(actualStartTimeMs: Long) {
        localPlayStartTimeMs = actualStartTimeMs
        isLocalPlaying = true
        recovering = false
        recoveryStartedAtMs = 0L
    }
    
    /**
     * Get recommended offset adjustment for current chunk
     * 
     * Precision sync strategy:
     * - If we're ahead (positive offset), delay playback (positive adjustment)
     * - If we're behind (negative offset), advance playback (negative adjustment)
     * - Use the FULL offset for 1:1 tracking, not just a portion
     * 
     * @return Offset in microseconds to apply to playback timing
     *         Positive = delay playback, Negative = advance playback
     */
    fun getRecommendedOffsetUs(): Long {
        if (!isLocalPlaying || serverPlayState != PlayState.PLAYING) {
            return 0L
        }

        // Keep corrections soft so recovery does not oscillate.
        return currentSyncOffsetMs
            .coerceIn(-MAX_OFFSET_ADJUSTMENT_MS, MAX_OFFSET_ADJUSTMENT_MS) * 1000L
    }
    
    /**
     * Check if playback should start based on server state
     */
    fun shouldStartPlayback(): Boolean {
        return serverPlayState == PlayState.PLAYING && !isLocalPlaying
    }
    
    /**
     * Check if playback should pause based on server state
     */
    fun shouldPausePlayback(): Boolean {
        return serverPlayState == PlayState.PAUSED && isLocalPlaying
    }
    
    /**
     * Check if playback should stop based on server state
     */
    fun shouldStopPlayback(): Boolean {
        return serverPlayState == PlayState.STOPPED && isLocalPlaying
    }
    
    /**
     * Get current server position extrapolated to now
     */
    fun getExtrapolatedServerPositionMs(): Long {
        if (serverPlayState != PlayState.PLAYING || serverPositionUpdateTimeMs == 0L) {
            return serverPositionMs
        }

        val elapsedSinceUpdate = (System.currentTimeMillis() - serverPositionUpdateTimeMs)
            .coerceAtMost(POSITION_UPDATE_INTERVAL_MS * 4L)
        return serverPositionMs + elapsedSinceUpdate
    }
    
    /**
     * Calculate optimal start position for joining mid-stream
     * 
     * For precision sync, we want to start at exactly the server position
     * (or slightly ahead to account for startup latency)
     * 
     * @param startupLatencyMs Expected startup latency in milliseconds
     * @return Position in milliseconds to start playback from
     */
    fun calculateJoinPosition(startupLatencyMs: Long = 50L): Long {
        val extrapolatedPosition = getExtrapolatedServerPositionMs()
        
        // Start ahead by startup latency to compensate for AudioTrack startup time
        // This ensures we're synced when audio actually starts playing
        val targetPosition = extrapolatedPosition + startupLatencyMs
        
        return targetPosition.coerceAtLeast(0L)
    }
    
    /**
     * Calculate the precise server timestamp for a given local play time
     * 
     * This is used to determine when to start playing a chunk so that
     * it's heard at exactly the right moment across all devices.
     * 
     * @param localPlayTimeMs When we want the audio to be heard locally
     * @return Server timestamp that should be playing at that time
     */
    fun getServerTimestampForLocalTime(localPlayTimeMs: Long): Long {
        if (serverPositionUpdateTimeMs == 0L) {
            return 0L
        }
        
        // Calculate how much time has passed since last server update
        val elapsedSinceUpdate = localPlayTimeMs - serverPositionUpdateTimeMs
        
        // The server position at localPlayTimeMs
        return serverPositionMs + elapsedSinceUpdate
    }
    
    /**
     * Get the offset needed to align local playback with server
     * 
     * This is the core precision sync calculation:
     * - Positive result: local is ahead, need to delay
     * - Negative result: local is behind, need to advance
     * 
     * @return Offset in milliseconds
     */
    fun getPrecisionSyncOffsetMs(): Long {
        if (!isLocalPlaying || serverPlayState != PlayState.PLAYING) {
            return 0L
        }

        return currentSyncOffsetMs.coerceIn(-MAX_OFFSET_ADJUSTMENT_MS, MAX_OFFSET_ADJUSTMENT_MS)
    }

    fun onDiscontinuityDetected() {
        val nowMs = System.currentTimeMillis()
        recovering = true
        recoveryStartedAtMs = nowMs
        lastDisturbanceAtMs = nowMs
        isLocalPlaying = false
    }

    fun onUnderrunDetected() {
        val nowMs = System.currentTimeMillis()
        if (!recovering) {
            recoveryStartedAtMs = nowMs
        }
        recovering = true
        lastDisturbanceAtMs = nowMs
        isLocalPlaying = false
    }

    fun buildRecoveryPlan(queuedChunks: Int, bufferAheadMs: Long): RecoveryPlan {
        if (serverPlayState != PlayState.PLAYING) {
            return RecoveryPlan(
                shouldStart = false,
                minQueuedChunks = 0,
                primeSilenceMs = 0,
                startupLatencyMs = 0L,
                reason = "server_not_playing"
            )
        }

        if (!recovering) {
            val minQueuedChunks = 5
            return RecoveryPlan(
                shouldStart = queuedChunks >= minQueuedChunks,
                minQueuedChunks = minQueuedChunks,
                primeSilenceMs = 0,
                startupLatencyMs = 50L,
                reason = if (queuedChunks >= minQueuedChunks) "normal_start" else "waiting_normal_buffer"
            )
        }

        val nowMs = System.currentTimeMillis()
        val recoveryAgeMs = nowMs - recoveryStartedAtMs
        val minQueuedChunks = when {
            queuedChunks >= 3 -> 3
            recoveryAgeMs >= 180L && queuedChunks >= 2 -> 2
            recoveryAgeMs >= 320L && queuedChunks >= 1 && bufferAheadMs >= -20L -> 1
            else -> 3
        }
        val shouldStart = queuedChunks >= minQueuedChunks
        val primeSilenceMs = if (shouldStart) 10 else 0
        val startupLatencyMs = when {
            recoveryAgeMs < 150L -> 45L
            recoveryAgeMs < 350L -> 35L
            else -> 25L
        }

        return RecoveryPlan(
            shouldStart = shouldStart,
            minQueuedChunks = minQueuedChunks,
            primeSilenceMs = primeSilenceMs,
            startupLatencyMs = startupLatencyMs,
            reason = if (shouldStart) "gentle_recovery_start" else "waiting_recovery_buffer"
        )
    }
    
    private fun updateSyncState() {
        val nowMs = System.currentTimeMillis()
        val expectedLocalPositionMs = if (isLocalPlaying && localPlayStartTimeMs > 0) {
            localPlayStartPositionMs + (nowMs - localPlayStartTimeMs)
        } else {
            serverPositionMs
        }
        
        _syncState.value = SyncState(
            playState = serverPlayState,
            serverPositionMs = serverPositionMs,
            serverDurationMs = serverDurationMs,
            localPositionMs = expectedLocalPositionMs,
            syncOffsetMs = currentSyncOffsetMs,
            isSynced = kotlin.math.abs(currentSyncOffsetMs) <= SYNC_TOLERANCE_MS,
            lastUpdateTimeMs = nowMs
        )
    }
    
    /**
     * Reset all state
     */
    fun reset() {
        serverPlayState = PlayState.STOPPED
        serverPositionMs = 0L
        serverDurationMs = 0L
        serverPositionUpdateTimeMs = 0L
        localPlayStartTimeMs = 0L
        localPlayStartPositionMs = 0L
        isLocalPlaying = false
        currentSyncOffsetMs = 0L
        offsetEmaMs = 0.0
        recovering = false
        recoveryStartedAtMs = 0L
        lastDisturbanceAtMs = 0L

        _syncState.value = SyncState()
    }
}
