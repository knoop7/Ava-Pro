package com.example.ava.voice

/**
 * Ordered insert of the closed HA STT window's text into the next Assist
 * pipe. Do not fire on STT_START alone — I1 may still be Processing.
 *
 * Queue: old RUN_END settled → I2 STT open → UI not Processing → settle →
 * spacer → conversation/process.
 */
object FabHaInject {
    /** Let I1 INTENT / RUN_END finish before we touch I2. */
    const val SETTLE_MS = 250L
    /** After the 15s cap, re-check whether conversation/process actually landed. */
    const val RETRY_MS = 1_000L

    fun canInsert(
        oldWindowSettled: Boolean,
        sttOpen: Boolean,
        processing: Boolean,
        pastHaCap: Boolean = false,
    ): Boolean {
        // Past 15s the text punch must not wait on Processing — that is the
        // stuck state when the previous insert never landed.
        if (pastHaCap) return true
        return oldWindowSettled && sttOpen && !processing
    }

    fun shouldRetry(
        pastHaCap: Boolean,
        confirmed: Boolean,
        hasText: Boolean,
    ): Boolean = pastHaCap && hasText && !confirmed

    /**
     * I2 (inject queued or just inserted) must not treat a short VAD pause
     * as "send". I3 clears these flags and uses normal tap/hold rules.
     */
    fun shouldHoldWindow(
        injectArmed: Boolean,
        injectDone: Boolean,
        listening: Boolean,
        processing: Boolean,
        confirmed: Boolean = true,
    ): Boolean {
        // confirmed=false is the idle default ("never punched"), not a miss.
        // Only I2 — inject queued or just inserted — must ignore a short pause.
        if (!injectArmed && !injectDone) return false
        return listening || processing
    }
}
