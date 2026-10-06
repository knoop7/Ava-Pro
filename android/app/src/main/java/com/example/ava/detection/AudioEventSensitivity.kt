package com.example.ava.detection

/**
 * User-facing sensitivity presets. Maps to internal gates only — never shown in UI.
 *
 * Training reference (EI reportable events): feature nonzero ratio p50 ≥ 0.62;
 * quiet rooms sit around 0.15–0.30.
 */
enum class AudioEventSensitivity {
    /** Fewer false positives — stricter confidence and quality gates. */
    CONSERVATIVE,
    /** Default — aligned with VyloEdge CONFIDENCE_THRESHOLD = 0.70. */
    BALANCED,
    /** Catch more events — looser gates; may increase false positives. */
    SENSITIVE,
    ;

    data class Thresholds(
        val confidenceMin: Float,
        val speechRunnerUpSuppressMin: Float,
        val featureNonZeroMin: Float,
        /**
         * Score at/above which a single window is trusted and published immediately.
         * Below it, the same label must repeat in a second window before publishing,
         * which rejects one-off spurious spikes without slowing strong detections.
         */
        val confirmStrongMin: Float,
    )

    fun thresholds(): Thresholds = when (this) {
        CONSERVATIVE -> Thresholds(
            confidenceMin = 0.78f,
            speechRunnerUpSuppressMin = 0.15f,
            featureNonZeroMin = 0.50f,
            confirmStrongMin = 0.92f,
        )
        BALANCED -> Thresholds(
            confidenceMin = AudioEventAutoThresholds.CONFIDENCE_THRESHOLD,
            speechRunnerUpSuppressMin = AudioEventAutoThresholds.SPEECH_RUNNERUP_SUPPRESS,
            featureNonZeroMin = 0.45f,
            confirmStrongMin = 0.85f,
        )
        SENSITIVE -> Thresholds(
            confidenceMin = 0.62f,
            speechRunnerUpSuppressMin = 0.28f,
            featureNonZeroMin = 0.38f,
            confirmStrongMin = 0.80f,
        )
    }

    companion object {
        fun fromStored(value: String?): AudioEventSensitivity = try {
            valueOf(value ?: "")
        } catch (_: Exception) {
            BALANCED
        }
    }
}

data class AudioEventDetectionConfig(
    val enabled: Boolean,
    val monitoredLabels: Set<String>,
    val sensitivity: AudioEventSensitivity,
    val eventDisplayMs: Long = AudioEventDisplayDuration.toMillis(AudioEventDisplayDuration.DEFAULT_SECONDS),
    val deviceTier: AudioEventDeviceProfile.Tier = AudioEventDeviceProfile.Tier.STANDARD,
) {
    val thresholds: AudioEventSensitivity.Thresholds =
        AudioEventDeviceProfile.adjustThresholds(deviceTier, sensitivity.thresholds())

    val timing: AudioEventDeviceProfile.Timing = AudioEventDeviceProfile.timing(deviceTier)

    companion object {
        val DEFAULT = AudioEventDetectionConfig(
            enabled = false,
            monitoredLabels = AudioEventCatalog.DEFAULT_MONITORED_LABELS,
            sensitivity = AudioEventSensitivity.BALANCED,
        )
    }
}
