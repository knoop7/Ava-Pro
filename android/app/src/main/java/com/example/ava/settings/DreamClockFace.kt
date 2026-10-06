package com.example.ava.settings

/**
 * Dream Clock overlay face.
 * [MECHANICAL] is the original circular analog — selectable in settings UI only.
 * [FILL] and [FLIP] are the StandBy-style faces — swipeable in the overlay picker.
 */
enum class DreamClockFace(val storageKey: String) {
    MECHANICAL("mechanical"),
    FILL("fill"),
    FLIP("flip"),
    ;

    /** Overlay picker only cycles FILL ↔ FLIP (MECHANICAL is settings-only). */
    fun nextSwitchable(): DreamClockFace = when (this) {
        FLIP -> FILL
        MECHANICAL, FILL -> FLIP
    }

    fun previousSwitchable(): DreamClockFace = when (this) {
        FILL, MECHANICAL -> FLIP
        FLIP -> FILL
    }

    companion object {
        /** Overlay picker: FILL + FLIP only — no MECHANICAL. */
        val switchable: List<DreamClockFace> = listOf(FILL, FLIP)

        fun fromStored(value: String?): DreamClockFace =
            entries.find { it.storageKey == value?.trim() } ?: FILL
    }
}
