package com.example.ava.lyrics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Overlay-state lyric display: HUD offset + five-step follow tightness.
 * Follow step 2 is the shipped clock (not an interpolation of neighbors).
 */
object LyricDisplayRuntime {
    const val OFFSET_MIN_MS = -500L
    const val OFFSET_MAX_MS = 500L
    const val OFFSET_STEP_MS = 50L

    const val FOLLOW_MIN = 0
    const val FOLLOW_MAX = 4
    /** Center stop — exact current tuned gates. */
    const val FOLLOW_DEFAULT = 2

    private const val PREFS = "ava_lyric_display"
    private const val KEY_LEAD_MS = "lead_ms"
    private const val KEY_FOLLOW_STEP = "follow_step"
    private const val DEFAULT_LEAD_MS = 0L

    private val leadMsState = MutableStateFlow(DEFAULT_LEAD_MS)
    private val followStepState = MutableStateFlow(FOLLOW_DEFAULT)

    @Volatile
    private var hydrated = false

    val leadMs: StateFlow<Long> = leadMsState.asStateFlow()
    val followStep: StateFlow<Int> = followStepState.asStateFlow()

    fun currentLeadMs(): Long = leadMsState.value

    fun currentFollowStep(): Int = followStepState.value

    fun currentPreset(): LyricFollowPreset = presetAt(followStepState.value)

    fun presetAt(step: Int): LyricFollowPreset =
        FOLLOW_PRESETS[step.coerceIn(FOLLOW_MIN, FOLLOW_MAX)]

    fun ensureLoaded(context: Context) {
        if (hydrated) return
        synchronized(this) {
            if (hydrated) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            leadMsState.value = prefs
                .getLong(KEY_LEAD_MS, DEFAULT_LEAD_MS)
                .coerceIn(OFFSET_MIN_MS, OFFSET_MAX_MS)
            followStepState.value = prefs
                .getInt(KEY_FOLLOW_STEP, FOLLOW_DEFAULT)
                .coerceIn(FOLLOW_MIN, FOLLOW_MAX)
            hydrated = true
        }
    }

    fun setLeadMs(context: Context, leadMs: Long) {
        val coerced = leadMs.coerceIn(OFFSET_MIN_MS, OFFSET_MAX_MS)
        leadMsState.value = coerced
        persist(context) { putLong(KEY_LEAD_MS, coerced) }
    }

    fun setFollowStep(context: Context, step: Int) {
        val coerced = step.coerceIn(FOLLOW_MIN, FOLLOW_MAX)
        followStepState.value = coerced
        persist(context) { putInt(KEY_FOLLOW_STEP, coerced) }
    }

    private fun persist(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        hydrated = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply(edit)
            .apply()
    }
}

/**
 * One follow-tightness stop. Index 2 ([LyricDisplayRuntime.FOLLOW_DEFAULT]) must
 * match the previously tuned compile constants.
 */
data class LyricFollowPreset(
    val seekForwardAlignMs: Long,
    val seekBackAlignMs: Long,
    val catchdownMinAheadMs: Long,
    val catchdownPerSampleMs: Long,
    val comfortableAheadMs: Long,
    val openGateMs: Long,
    val behindForceAlignMs: Long,
    val stampBiasMs: Long,
    val karaokeStepSettleMs: Long,
) {
    val openIntroMs: Long get() = openGateMs

    val karaokeJumpSettleMs: Long
        get() = if (karaokeStepSettleMs <= 0L) {
            0L
        } else {
            (karaokeStepSettleMs * 80L / 200L).coerceAtLeast(0L)
        }
}

private val FOLLOW_PRESETS: Array<LyricFollowPreset> = arrayOf(
    // 0 稳
    LyricFollowPreset(3500, 5000, 280, 60, 160, 1800, 600, 200, 280),
    // 1 稍稳
    LyricFollowPreset(3000, 4500, 250, 80, 140, 1500, 500, 175, 240),
    // 2 默认 — shipped clock
    LyricFollowPreset(2500, 4000, 220, 100, 120, 1200, 400, 150, 200),
    // 3 稍跟
    LyricFollowPreset(1800, 3000, 190, 130, 100, 900, 320, 75, 100),
    // 4 跟
    LyricFollowPreset(1200, 2000, 160, 160, 80, 600, 250, 0, 0),
)
