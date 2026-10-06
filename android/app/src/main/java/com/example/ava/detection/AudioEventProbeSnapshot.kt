package com.example.ava.detection

/** Read-only Audio Event pipeline numbers for Voice Stats. */
data class AudioEventProbeSnapshot(
    val captureEnabled: Boolean = false,
    val windowsReceived: Long = 0L,
    val windowsProcessed: Long = 0L,
    val windowsSkipped: Long = 0L,
    val windowsThrottled: Long = 0L,
    val lastInferenceMs: Long = 0L,
    val adaptiveIntervalMs: Long = 0L,
    val lastTopLabel: String? = null,
    val lastTopScore: Float = 0f,
    val lastFeatureMean: Float = 0f,
    val lastFeatureNz: Float = 0f,
    val lastPcmRms: Float = 0f,
    val lastPasses: Boolean = false,
    val pendingLabel: String? = null,
    val pendingCount: Int = 0,
    val confirmRequired: Int = 0,
    val activeLabel: String? = null,
    val displayMs: Long = 0L,
    val monitoredCount: Int = 0,
    val deviceTier: String = "—",
)
