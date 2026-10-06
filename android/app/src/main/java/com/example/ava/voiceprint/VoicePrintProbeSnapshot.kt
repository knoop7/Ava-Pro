package com.example.ava.voiceprint

/** Read-only voiceprint match numbers for Voice Stats. */
data class VoicePrintProbeSnapshot(
    val statusText: String = "—",
    val identifiedIndex: Int? = null,
    val identifiedConfidence: Float = 0f,
    val lastMatchStatus: String = "—",
    val lastMatchConfidence: Float = 0f,
    val lastMatchQuality: Float = 0f,
    val lastMatchU0: Float = 0f,
    val lastMatchU1: Float = 0f,
    val lastMatchMargin: Float = 0f,
    val lastMatchAgeMs: Long = -1L,
    val lastExtractSamples: Int = 0,
    val lastExtractWaitedMs: Int = 0,
    val processGapMs: Long = -1L,
    val manualWakeVerifyRequired: Boolean = false,
)
