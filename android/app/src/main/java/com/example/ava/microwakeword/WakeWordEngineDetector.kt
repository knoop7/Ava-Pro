package com.example.ava.microwakeword

import java.nio.ByteBuffer

interface WakeWordEngineDetector : AutoCloseable {
    data class DetectionResult(
        val wakeWordId: String,
        val wakeWordPhrase: String,
        val confidence: Float = 1f,
        /**
         * The classifier input behind this fire (open: 16x96 embedding window; micro:
         * pooled trailing feature frames), for on-device verifier learning. Null when
         * the engine cannot provide it.
         */
        val verifierWindow: FloatArray? = null,
    )

    fun detect(audio: ByteBuffer): List<DetectionResult>
    fun setActiveWakeWords(wakeWordIds: List<String>)
    fun reset()
    fun updateProbabilityCutoff(wakeWordId: String, cutoff: Float)

    /** Re-read `<id>_verifier.bin` after on-device learning wrote a new head. */
    fun reloadVerifier(wakeWordId: String) {}

    /**
     * Extra strictness past the native cutoff ceiling (0 = off, 1 = offline
     * re-verify handled by the caller, 2 = level 1 + engine-side VAD/hit-gate
     * tightening). Engines apply what is theirs and ignore the rest.
     */
    fun updateExtraStrictness(wakeWordId: String, level: Int) {}
}
