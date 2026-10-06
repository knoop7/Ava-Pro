package com.example.ava.wakelearn

/**
 * Label for an accepted wake once the pipeline has produced a verdict.
 *
 * A blank transcript alone is not proof that nobody spoke: audio can be lost
 * between the mic and STT (earcon overlap, dropped pre-roll, recogniser failure).
 * It only becomes a negative when the device itself heard no speech either.
 */
internal enum class WakeLearningLabel {
    POSITIVE, NEGATIVE, UNKNOWN;

    companion object {
        /**
         * @param speechEvidence true when the device observed speech during this
         *   session by any local or remote means (pre-roll replay, local energy after
         *   the uplink opened, HA's own VAD). Blank text with evidence is discarded.
         */
        fun fromTranscript(text: String?, speechEvidence: Boolean): WakeLearningLabel = when {
            !text.isNullOrBlank() -> POSITIVE
            speechEvidence -> UNKNOWN
            else -> NEGATIVE
        }
    }
}

/**
 * Per-session speech evidence. Frames are only counted once [arm] has been called
 * (the uplink is open and the earcon no longer bleeds into the mic); before that the
 * pre-roll's own onset decision is the evidence for the chime period.
 */
internal class WakeSessionSpeechEvidence(
    private val rmsFloor: Float = 0.008f,
    private val sustainMs: Int = 240,
) {
    private var armed = false
    private var runMs = 0
    var observed = false
        private set

    /** Skip the per-frame RMS when the outcome can no longer change. */
    val wantsFrames: Boolean get() = armed && !observed

    fun reset() {
        armed = false
        runMs = 0
        observed = false
    }

    fun arm() {
        armed = true
    }

    /** External evidence (pre-roll replay carried speech, HA reported VAD start). */
    fun note() {
        observed = true
    }

    /** Returns true exactly once, when this frame completes the sustained run. */
    fun onFrame(rms: Float, frameMs: Int): Boolean {
        if (!armed || observed) return false
        runMs = if (rms >= rmsFloor) runMs + frameMs else 0
        if (runMs < sustainMs) return false
        observed = true
        return true
    }
}
