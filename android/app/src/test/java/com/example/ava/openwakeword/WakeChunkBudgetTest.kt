package com.example.ava.openwakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeChunkBudgetTest {
    @Test
    fun schedulerBudgetMatchesFortyMillisecondFrontendTick() {
        assertEquals(40, WakeChunkBudget.BUDGET_MS)
        assertEquals(10_000, WakeChunkBudget.BUDGET_MS * WakeChunkBudget.WINDOW_CHUNKS)
        assertEquals(640, OpenFrontendManifest().chunkSamples)
    }

    @Test
    fun inBudgetWindowIsNotOver() {
        val tracker = WakeChunkBudgetTracker(windowChunks = 4, minDisplayChunks = 2)
        val twentyMs = 20_000_000L
        repeat(3) { tracker.record(twentyMs) }
        val mid = tracker.snapshot()
        assertFalse(mid.overBudget)
        assertEquals(20.0, mid.avgMs, 0.01)

        tracker.record(twentyMs)
        assertFalse(tracker.snapshot().overBudget)
    }

    @Test
    fun sustainedOverrunMarksOverBudget() {
        val tracker = WakeChunkBudgetTracker(windowChunks = 4, minDisplayChunks = 2)
        val slow = 136_000_000L
        repeat(3) { tracker.record(slow) }
        assertTrue(tracker.snapshot().overBudget)
        tracker.record(slow)
        assertTrue(tracker.snapshot().overBudget)
        assertEquals(136.0, tracker.snapshot().avgMs, 0.01)
    }

    @Test
    fun singlePeakInsideFastWindowDoesNotMarkOver() {
        val tracker = WakeChunkBudgetTracker(windowChunks = 4, minDisplayChunks = 2)
        tracker.record(20_000_000L)
        tracker.record(20_000_000L)
        tracker.record(500_000_000L)
        tracker.record(20_000_000L)
        val snap = tracker.snapshot()
        assertFalse(snap.overBudget)
        assertEquals(500.0, snap.peakMs, 0.01)
    }

    @Test
    fun resetClearsDisplay() {
        val tracker = WakeChunkBudgetTracker(windowChunks = 2, minDisplayChunks = 1)
        tracker.record(200_000_000L)
        tracker.record(200_000_000L)
        assertTrue(tracker.snapshot().overBudget)
        tracker.reset()
        assertEquals(0, tracker.snapshot().samples)
        assertFalse(tracker.snapshot().overBudget)
    }
}
