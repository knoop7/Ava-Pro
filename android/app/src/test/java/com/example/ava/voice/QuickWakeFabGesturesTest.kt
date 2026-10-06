package com.example.ava.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickWakeFabGesturesTest {
    @Test fun idleStillTapCommits() {
        assertTrue(
            QuickWakeFabGestures.shouldCommitTap(
                liveTurn = false,
                maxMovedPx = 4f,
                touchSlopPx = 16f,
                heldMs = 160L,
                minTapMs = 120L,
                insideHit = true,
            ),
        )
    }

    @Test fun idleBrushTooShortIsIgnored() {
        assertFalse(
            QuickWakeFabGestures.shouldCommitTap(
                liveTurn = false,
                maxMovedPx = 0f,
                touchSlopPx = 16f,
                heldMs = 80L,
                minTapMs = 120L,
                insideHit = true,
            ),
        )
    }

    @Test fun liveTurnStillTapAborts() {
        assertTrue(
            QuickWakeFabGestures.shouldCommitTap(
                liveTurn = true,
                maxMovedPx = 4f,
                touchSlopPx = 16f,
                heldMs = 40L,
                minTapMs = 120L,
                insideHit = true,
            ),
        )
    }

    @Test fun liveTurnWanderDoesNotAbort() {
        assertFalse(
            QuickWakeFabGestures.shouldCommitTap(
                liveTurn = true,
                maxMovedPx = 20f,
                touchSlopPx = 16f,
                heldMs = 200L,
                minTapMs = 120L,
                insideHit = true,
            ),
        )
    }

    @Test fun liftOutsideHitIsIgnored() {
        assertFalse(
            QuickWakeFabGestures.shouldCommitTap(
                liveTurn = true,
                maxMovedPx = 0f,
                touchSlopPx = 16f,
                heldMs = 200L,
                minTapMs = 120L,
                insideHit = false,
            ),
        )
    }

    @Test fun connectingUplinkBlocksVoice() {
        assertTrue(
            QuickWakeFabGestures.shouldBlockVoiceTrigger(
                uplinkReady = false,
                awaitingListen = false,
            ),
        )
    }

    @Test fun awaitingListenBlocksVoice() {
        assertTrue(
            QuickWakeFabGestures.shouldBlockVoiceTrigger(
                uplinkReady = true,
                awaitingListen = true,
            ),
        )
    }

    @Test fun linkedIdleAllowsVoice() {
        assertFalse(
            QuickWakeFabGestures.shouldBlockVoiceTrigger(
                uplinkReady = true,
                awaitingListen = false,
            ),
        )
    }

    @Test fun idleLongStillPressStillTaps() {
        assertTrue(
            QuickWakeFabGestures.shouldCommitTap(
                liveTurn = false,
                maxMovedPx = 0f,
                touchSlopPx = 16f,
                heldMs = 400L,
                minTapMs = 120L,
                insideHit = true,
            ),
        )
    }

    @Test fun idleSwipeAfterCloseIsDrag() {
        assertTrue(
            QuickWakeFabGestures.shouldDragOnTapWander(
                pickedUp = false,
                maxMovedPx = 48f,
                touchSlopPx = 16f,
                wanderMul = 2.5f,
            ),
        )
    }

    @Test fun settleUnderWanderIsNotDrag() {
        assertFalse(
            QuickWakeFabGestures.shouldDragOnTapWander(
                pickedUp = false,
                maxMovedPx = 20f,
                touchSlopPx = 16f,
                wanderMul = 2.5f,
            ),
        )
    }

    @Test fun pickedUpUsesSlopNotWander() {
        assertFalse(
            QuickWakeFabGestures.shouldDragOnTapWander(
                pickedUp = true,
                maxMovedPx = 48f,
                touchSlopPx = 16f,
                wanderMul = 2.5f,
            ),
        )
    }

    @Test fun secondTapWhileAwaitingCloses() {
        assertTrue(
            QuickWakeFabGestures.shouldCloseOnTap(
                awaitingListen = true,
                assistTurnActive = false,
            ),
        )
    }

    @Test fun tapOnLiveTurnCloses() {
        assertTrue(
            QuickWakeFabGestures.shouldCloseOnTap(
                awaitingListen = false,
                assistTurnActive = true,
            ),
        )
    }

    @Test fun idleLinkedTapDoesNotClose() {
        assertFalse(
            QuickWakeFabGestures.shouldCloseOnTap(
                awaitingListen = false,
                assistTurnActive = false,
            ),
        )
    }
}
