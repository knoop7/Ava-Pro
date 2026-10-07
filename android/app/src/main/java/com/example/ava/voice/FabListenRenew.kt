package com.example.ava.voice

/**
 * HA's VoiceCommandSegmenter hard-stops a listen at 15s, and also ends the
 * window on a short pause. FAB hold must not treat that pause as "send":
 * splice a new STT window until the finger comes up. Tap still
 * commits on an early pause in the first window; after the first splice
 * it keeps chaining until [SESSION_RENEW_MS].
 */
object FabListenRenew {
    /** HA default [timeout_seconds]. A VAD_END this late is the cap, not a pause. */
    const val HA_VAD_TIMEOUT_MS = 15_000L
    /** Renew a bit before the cap so a slow event still counts as timeout. */
    const val RENEW_AFTER_MS = 13_000L
    /** After the first splice, keep chaining windows up to the hold safety cap. */
    const val SESSION_RENEW_MS = QuickWakePushToTalk.MAX_HOLD_MS
    /**
     * A spliced HA window this young is still the old run closing. An end
     * marker here becomes junk STT and a third listen.
     */
    const val FRESH_SPLICE_MS = 1_000L

    fun isFreshSpliceWindow(msSinceRunStart: Long): Boolean =
        msSinceRunStart in 0 until FRESH_SPLICE_MS

    fun shouldRenew(
        holding: Boolean,
        fabSession: Boolean,
        listening: Boolean,
        msSinceRunStart: Long,
        holdTurn: Boolean = false,
        processing: Boolean = false,
        msSinceSessionStart: Long = 0L,
        alreadySpliced: Boolean = false,
    ): Boolean {
        // Finger already up on a hold: finish this window. Do not treat a
        // sentence pause — or the 15s cap after release — as a tap renew.
        if (holdTurn && !holding) return false
        // Still holding: every closed window is a splice, even if a late
        // VAD_END already flipped us to Processing. Sending on a pause is
        // what made the disc "give up" and fire the command before UP.
        if (holding) return true
        if (!fabSession) return false
        if (!listening && !processing) return false
        // I2+ clocks reset on every RUN_START. A tap pause at 2s of I3 would
        // look "early" and kill the turn — keep splicing until 2 minutes.
        if (alreadySpliced) return msSinceSessionStart < SESSION_RENEW_MS
        // First window: only splice at HA's cap. An early VAD_END is the user done.
        return msSinceRunStart >= RENEW_AFTER_MS
    }

    fun stitch(previous: String, chunk: String?): String {
        val next = chunk?.trim().orEmpty()
        if (next.isEmpty()) return previous
        if (previous.isBlank()) return next
        return "$previous $next"
    }
}
