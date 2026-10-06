package com.example.ava.settings

/**
 * StandBy Season Effects overlay: particle art drawn on top of any Dream Clock face.
 */
enum class DreamClockSeason(val storageKey: String) {
    NONE("none"),
    SPRING("spring"),
    SUMMER("summer"),
    AUTUMN("autumn"),
    WINTER("winter"),
    ;

    companion object {
        fun fromStored(value: String?): DreamClockSeason =
            entries.find { it.storageKey == value?.trim() } ?: NONE
    }
}
