package com.example.ava.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayClockStatsTest {
    @Test
    fun percentileNearestRank() {
        val sorted = longArrayOf(10, 20, 30, 40, 50)
        assertEquals(10L, DisplayClockStats.percentile(sorted, 5, 0))
        assertEquals(30L, DisplayClockStats.percentile(sorted, 5, 50))
        assertEquals(50L, DisplayClockStats.percentile(sorted, 5, 100))
        assertEquals(40L, DisplayClockStats.percentile(sorted, 5, 95))
    }

    @Test
    fun mi9SizedSkewStaysClear() {
        // +0.3ms ahead — the Choreographer warning, not a miss.
        assertFalse(
            DisplayClockStats.decideFrost(
                previouslyFrost = false,
                ready = true,
                skewP95Ns = 300_000L,
                lateOver8msRatio = 0f,
            ),
        )
    }

    @Test
    fun twoMillisecondP95Frosts() {
        assertTrue(
            DisplayClockStats.decideFrost(
                previouslyFrost = false,
                ready = true,
                skewP95Ns = 2_000_001L,
                lateOver8msRatio = 0f,
            ),
        )
    }

    @Test
    fun lateFramesFrostEvenIfSkewSmall() {
        assertTrue(
            DisplayClockStats.decideFrost(
                previouslyFrost = false,
                ready = true,
                skewP95Ns = 200_000L,
                lateOver8msRatio = 0.05f,
            ),
        )
    }

    @Test
    fun notReadyKeepsPrevious() {
        assertTrue(
            DisplayClockStats.decideFrost(
                previouslyFrost = true,
                ready = false,
                skewP95Ns = 0L,
                lateOver8msRatio = 0f,
            ),
        )
        assertFalse(
            DisplayClockStats.decideFrost(
                previouslyFrost = false,
                ready = false,
                skewP95Ns = 9_000_000L,
                lateOver8msRatio = 1f,
            ),
        )
    }

    @Test
    fun hysteresisNeedsSubMillisecondToLeaveFrost() {
        assertTrue(
            DisplayClockStats.decideFrost(
                previouslyFrost = true,
                ready = true,
                skewP95Ns = 1_500_000L,
                lateOver8msRatio = 0f,
            ),
        )
        assertFalse(
            DisplayClockStats.decideFrost(
                previouslyFrost = true,
                ready = true,
                skewP95Ns = 400_000L,
                lateOver8msRatio = 0f,
            ),
        )
    }

    @Test
    fun talliesFutureAndLate() {
        val values = longArrayOf(2_000_000L, -9_000_000L, 500_000L, -100_000L)
        assertEquals(1, DisplayClockStats.futureOver1ms(values, 4))
        assertEquals(1, DisplayClockStats.lateOver8ms(values, 4))
        assertEquals(0.5f, DisplayClockStats.futureRatio(values, 4), 0.001f)
    }
}
