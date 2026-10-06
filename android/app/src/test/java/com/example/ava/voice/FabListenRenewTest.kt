package com.example.ava.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FabListenRenewTest {
    @Test fun holdEarlyVadSplices() {
        assertTrue(FabListenRenew.shouldRenew(true, false, true, 800L, holdTurn = true))
        assertTrue(FabListenRenew.shouldRenew(true, true, true, 3_000L, holdTurn = true))
    }

    @Test fun holdNearHaCapRenews() {
        assertTrue(
            FabListenRenew.shouldRenew(
                holding = true,
                fabSession = false,
                listening = true,
                msSinceRunStart = FabListenRenew.RENEW_AFTER_MS,
                holdTurn = true,
            ),
        )
    }

    @Test fun holdReleaseDoesNotTapRenew() {
        assertFalse(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = true,
                msSinceRunStart = FabListenRenew.HA_VAD_TIMEOUT_MS,
                holdTurn = true,
            ),
        )
    }

    @Test fun holdReleaseDoesNotKeepSplicing() {
        assertFalse(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = true,
                msSinceRunStart = 2_000L,
                holdTurn = true,
                alreadySpliced = true,
                msSinceSessionStart = 20_000L,
            ),
        )
    }

    @Test fun freshSpliceWindowIsTheClosingOldRun() {
        assertTrue(FabListenRenew.isFreshSpliceWindow(180L))
        assertFalse(FabListenRenew.isFreshSpliceWindow(FabListenRenew.FRESH_SPLICE_MS))
    }

    @Test fun tapEarlyVadIsAPause() {
        assertFalse(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = true,
                msSinceRunStart = 3_000L,
            ),
        )
    }

    @Test fun tapNearHaCapRenews() {
        assertTrue(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = true,
                msSinceRunStart = FabListenRenew.RENEW_AFTER_MS,
            ),
        )
    }

    @Test fun wakeWordDoesNotRenew() {
        assertFalse(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = false,
                listening = true,
                msSinceRunStart = 20_000L,
            ),
        )
    }

    @Test fun holdSplicesAfterVadFlippedToProcessing() {
        assertTrue(
            FabListenRenew.shouldRenew(
                holding = true,
                fabSession = true,
                listening = false,
                msSinceRunStart = 4_000L,
                holdTurn = true,
                processing = true,
            ),
        )
    }

    @Test fun tapAtCapWhileProcessingStillRenews() {
        assertTrue(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = false,
                msSinceRunStart = FabListenRenew.RENEW_AFTER_MS,
                processing = true,
            ),
        )
    }

    @Test fun tapEarlyProcessingIsStillAPause() {
        assertFalse(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = false,
                msSinceRunStart = 3_000L,
                processing = true,
            ),
        )
    }

    @Test fun tapAfterSpliceKeepsGoingBeforeTwoMinutes() {
        assertTrue(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = true,
                msSinceRunStart = 2_000L,
                msSinceSessionStart = 20_000L,
                alreadySpliced = true,
            ),
        )
    }

    @Test fun tapAfterSpliceStopsAtTwoMinutes() {
        assertFalse(
            FabListenRenew.shouldRenew(
                holding = false,
                fabSession = true,
                listening = true,
                msSinceRunStart = 14_000L,
                msSinceSessionStart = FabListenRenew.SESSION_RENEW_MS,
                alreadySpliced = true,
            ),
        )
    }

    @Test fun stitchJoinsChunks() {
        assertEquals("打开客厅灯 再把窗帘拉上", FabListenRenew.stitch("打开客厅灯", "再把窗帘拉上"))
        assertEquals("hello", FabListenRenew.stitch("", " hello "))
    }
}
