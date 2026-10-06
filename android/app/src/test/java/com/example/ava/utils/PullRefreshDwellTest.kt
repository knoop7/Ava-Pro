package com.example.ava.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PullRefreshDwellTest {

    @Test
    fun flickBeforePauseIsRejected() {
        assertEquals(PullRefreshDwell.WAITING, dwell(elapsedMs = 0L, dy = 0f))
        assertEquals(PullRefreshDwell.WAITING, dwell(elapsedMs = 200L, dy = 6f))
        assertEquals(PullRefreshDwell.REJECTED, dwell(elapsedMs = 40L, dy = 30f))
        assertEquals(PullRefreshDwell.REJECTED, dwell(elapsedMs = 319L, dx = 20f, dy = 0f))
    }

    @Test
    fun restThenPullMayArm() {
        assertEquals(PullRefreshDwell.RESTED, dwell(elapsedMs = 320L, dy = 4f))
        assertEquals(PullRefreshDwell.PULL, dwell(elapsedMs = 320L, dy = 24f))
        assertEquals(PullRefreshDwell.PULL, dwell(elapsedMs = 800L, dy = -24f))
    }

    private fun dwell(
        elapsedMs: Long,
        dx: Float = 0f,
        dy: Float = 0f,
    ): PullRefreshDwell = pullRefreshDwell(
        elapsedMs = elapsedMs,
        dx = dx,
        dy = dy,
        slopPx = 8f,
        dwellMs = 320L,
    )
}
