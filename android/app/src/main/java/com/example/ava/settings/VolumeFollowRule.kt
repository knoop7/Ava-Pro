package com.example.ava.settings

/**
 * One-way volume follow between the device and the configured Home Assistant media player.
 * Bidirectional follow is intentionally omitted — both directions at once fight each other (#106).
 */
enum class VolumeFollowRule(val storageKey: String) {
    /** Device and HA volumes stay independent (default for new installs). */
    INDEPENDENT("independent"),

    /** Device volume keys → Home Assistant (legacy [SendspinSettings.syncDeviceVolumeWithHa]). */
    FOLLOW_DEVICE("follow_device"),

    /** Home Assistant volume → device STREAM_MUSIC. */
    FOLLOW_HA("follow_ha"),
    ;

    val mirrorsDeviceToHa: Boolean get() = this == FOLLOW_DEVICE
    val mirrorsHaToDevice: Boolean get() = this == FOLLOW_HA

    companion object {
        fun fromStored(value: String?): VolumeFollowRule =
            entries.find { it.storageKey == value?.trim() } ?: INDEPENDENT

        /**
         * Resolve effective rule. Blank [SendspinSettings.volumeFollowRule] means "not migrated yet"
         * and falls back to the legacy boolean.
         */
        fun fromSettings(settings: SendspinSettings): VolumeFollowRule {
            val raw = settings.volumeFollowRule.trim()
            if (raw.isNotEmpty()) return fromStored(raw)
            return if (settings.syncDeviceVolumeWithHa) FOLLOW_DEVICE else INDEPENDENT
        }
    }
}
