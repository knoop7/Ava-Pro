package com.example.ava.detection

/**
 * VyloEdge-aligned confidence and speech-confusion gates for audio event detection.
 *
 * Which classes are published is controlled by user-monitored labels in settings;
 * this object only applies score thresholds and the speech runner-up guard.
 */
object AudioEventAutoThresholds {
    /** VyloEdge default — 70% on dequantized classifier probability. */
    const val CONFIDENCE_THRESHOLD = 0.70f

    /**
     * Speech-confusion guard. On speech-heavy windows the model often ranks an event class
     * (e.g. glass_breaking) top-1 while keeping "speech" as a strong runner-up. Household speech
     * must never trigger alarm/siren/glass, so if speech holds at least this probability in the
     * runner-up slot we treat the window as speech and suppress the event.
     * Not applied when the top class is already speech.
     */
    const val SPEECH_RUNNERUP_SUPPRESS = 0.20f

    fun passes(
        result: AudioEventResult,
        runnerUp: AudioEventResult? = null,
        thresholds: AudioEventSensitivity.Thresholds = AudioEventSensitivity.BALANCED.thresholds(),
    ): Boolean {
        if (result.score < thresholds.confidenceMin) return false
        if (result.label != "speech" &&
            runnerUp?.label == "speech" &&
            runnerUp.score >= thresholds.speechRunnerUpSuppressMin
        ) {
            return false
        }
        return true
    }
}
