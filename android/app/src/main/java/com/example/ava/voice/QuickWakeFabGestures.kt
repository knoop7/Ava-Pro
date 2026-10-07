package com.example.ava.voice

/**
 * Pure tap/hold decisions for the Quick Wake FAB. Kept off the service so
 * the live-turn wander rule can be unit-tested without a WindowManager.
 */
object QuickWakeFabGestures {
    /**
     * Whether UP should fire [onTap] (start listen, or hush on a live turn).
     *
     * Idle: a settling thumb may wander past slop and still be a tap.
     * A swipe past the wander slops starts a drag instead — including
     * idle after a hush. Still-release after the drag-arm window is still
     * a tap; pickup does not kill open/close.
     * Live turn: any move past slop is a reposition — lifting must not hush.
     */
    fun shouldCommitTap(
        liveTurn: Boolean,
        maxMovedPx: Float,
        touchSlopPx: Float,
        heldMs: Long,
        minTapMs: Long,
        insideHit: Boolean,
    ): Boolean {
        if (!insideHit) return false
        if (!liveTurn && heldMs < minTapMs) return false
        if (liveTurn && maxMovedPx > touchSlopPx) return false
        return true
    }

    /**
     * TAP swipe before pickup. Always drag — idle, live, or just-closed.
     * Abandoning an idle swipe used to drop the rest of the finger stream,
     * so the disc felt stuck after a hush.
     */
    fun shouldDragOnTapWander(
        pickedUp: Boolean,
        maxMovedPx: Float,
        touchSlopPx: Float,
        wanderMul: Float,
    ): Boolean = !pickedUp && maxMovedPx > touchSlopPx * wanderMul

    /**
     * Second tap while a listen is bouncing / orbiting, or any tap on a
     * live turn, is a hush. Wait-mic with nothing in flight stays a no-op.
     */
    fun shouldCloseOnTap(
        awaitingListen: Boolean,
        assistTurnActive: Boolean,
    ): Boolean = awaitingListen || assistTurnActive

    /**
     * HA uplink is still coming up (wait-mic dots), or a tap is already in
     * flight waiting for Listening (orbit). Starting a new listen / hold
     * must wait; drag-to-reposition and tap-to-close stay available.
     */
    fun shouldBlockVoiceTrigger(
        uplinkReady: Boolean,
        awaitingListen: Boolean,
    ): Boolean = !uplinkReady || awaitingListen
}
