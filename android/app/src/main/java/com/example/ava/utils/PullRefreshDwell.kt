package com.example.ava.utils

import kotlin.math.abs

/**
 * Whether a top-of-page touch has rested long enough to become a pull-to-refresh.
 * A move past the slop before [dwellMs] is a flick and must stay with the page.
 */
internal enum class PullRefreshDwell {
    /** Still inside the pause, finger within the slop. */
    WAITING,
    /** Finger left the slop before the pause finished. */
    REJECTED,
    /** Pause finished while the finger stayed within the slop. */
    RESTED,
    /**
     * Pause already elapsed on the sample that first leaves the slop.
     * The finger was still until this event, so this sample may start the pull.
     */
    PULL,
}

internal fun pullRefreshDwell(
    elapsedMs: Long,
    dx: Float,
    dy: Float,
    slopPx: Float,
    dwellMs: Long,
): PullRefreshDwell {
    val moved = abs(dx) > slopPx || abs(dy) > slopPx
    if (elapsedMs < dwellMs) {
        return if (moved) PullRefreshDwell.REJECTED else PullRefreshDwell.WAITING
    }
    return if (moved) PullRefreshDwell.PULL else PullRefreshDwell.RESTED
}
