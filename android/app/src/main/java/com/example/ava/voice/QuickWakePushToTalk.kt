package com.example.ava.voice

import android.os.SystemClock
import android.util.Log
import com.example.ava.services.VoiceSatelliteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Push-to-talk driver behind the Quick Wake FAB's hold gesture.
 *
 * Finger down → [begin] opens a manual voice session when idle. Finger up →
 * [release] sends HA the end-of-audio marker via [VoiceSatelliteService.finishManualSpeech]:
 * STT finalizes on what was said while holding and the pipeline goes on to intent / TTS.
 *
 * If a listen is already open (wake word won the hold-arm race), [begin] latches
 * onto that uplink instead of calling [VoiceSatelliteService.quickWake], which
 * would 闭嘴 the turn. A reply already in flight is left alone — the hold is skipped.
 *
 * Guard rails:
 * - A hold shorter than [MIN_HOLD_MS] is a brush, not speech — the session is aborted
 *   silently so a graze never produces a "sorry, I didn't catch that".
 * - A hold longer than [MAX_HOLD_MS] auto-releases (finger stuck / device on a shelf).
 * - [cancel] (service teardown) aborts without recognition. A drag during the
 *   hold does not call this — sliding only repositions the FAB.
 *
 * Thread-safe enough for the single UI thread that owns the FAB; all state lives on Main.
 */
object QuickWakePushToTalk {
    private const val TAG = "QuickWakePTT"

    /** Below this the release aborts: nobody says a command in under a quarter second. */
    const val MIN_HOLD_MS = 250L
    /** Safety cap so a stuck touch cannot stream the room indefinitely. */
    const val MAX_HOLD_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capJob: Job? = null
    private var pressStartedAt = 0L

    private val _holding = MutableStateFlow(false)
    /** True from [begin] until [release] / [cancel]; the FAB paints its recording state from this. */
    val holding: StateFlow<Boolean> = _holding.asStateFlow()

    val isHolding: Boolean get() = _holding.value

    /**
     * True from a live [begin] until the hold listen is finished or aborted.
     * Stays set after finger-up so a 15s splice cannot be mistaken for a tap renew.
     */
    var isHoldTurn: Boolean = false
        private set

    fun clearHoldTurn() {
        isHoldTurn = false
    }

    /**
     * Tap / silent quick-wake. A leftover hold latch would make
     * [FabListenRenew.shouldRenew] treat the 15s cap as "finger already up"
     * and refuse to splice.
     */
    fun noteTapWake() {
        if (_holding.value) return
        isHoldTurn = false
    }

    /**
     * How [begin] should treat the current satellite turn.
     * [WAKE]: idle — open a silent listen.
     * [LATCH]: mic already collecting — keep that uplink, do not 闭嘴.
     * [SKIP]: reply / remote seat — do not start PTT and do not cut the turn.
     */
    enum class BeginAction { WAKE, LATCH, SKIP }

    fun decideBegin(turnActive: Boolean, collectingSpeech: Boolean): BeginAction = when {
        !turnActive -> BeginAction.WAKE
        collectingSpeech -> BeginAction.LATCH
        else -> BeginAction.SKIP
    }

    /**
     * @return true if this hold is now live (wake or latch). False if skipped —
     * the FAB must drop recording chrome without touching the turn.
     */
    fun begin(): Boolean {
        if (_holding.value) return true
        val action = decideBegin(
            turnActive = VoiceSatelliteService.isAssistTurnActive(),
            collectingSpeech = VoiceSatelliteService.isCollectingSpeech(),
        )
        if (action == BeginAction.SKIP) {
            Log.d(TAG, "begin skipped: turn already in reply")
            return false
        }
        pressStartedAt = SystemClock.elapsedRealtime()
        _holding.value = true
        isHoldTurn = true
        if (action == BeginAction.WAKE) {
            VoiceSatelliteService.quickWake()
        } else {
            Log.d(TAG, "begin latched onto live listen")
        }
        capJob?.cancel()
        capJob = scope.launch {
            delay(MAX_HOLD_MS)
            if (_holding.value) {
                Log.w(TAG, "hold exceeded ${MAX_HOLD_MS}ms, auto-releasing")
                release()
            }
        }
        return true
    }

    fun release() {
        if (!_holding.value) return
        _holding.value = false
        capJob?.cancel()
        capJob = null
        val heldMs = SystemClock.elapsedRealtime() - pressStartedAt
        if (heldMs < MIN_HOLD_MS) {
            Log.d(TAG, "hold ${heldMs}ms < min, aborting as accidental")
            VoiceSatelliteService.stopVoiceSession()
            return
        }
        VoiceSatelliteService.finishManualSpeech()
    }

    fun cancel() {
        if (!_holding.value) return
        _holding.value = false
        capJob?.cancel()
        capJob = null
        isHoldTurn = false
        VoiceSatelliteService.stopVoiceSession()
    }
}
