package com.example.ava.settings

/**
 * Portrait Simple Clock layout.
 * [SIMPLE] is the original one-line clock (default). [MAGAZINE] is the stacked hero.
 */
enum class SimpleClockPortraitStyle(val storageKey: String) {
    MAGAZINE("magazine"),
    SIMPLE("simple"),
    ;

    companion object {
        fun fromStored(value: String?): SimpleClockPortraitStyle =
            entries.find { it.storageKey == value?.trim() } ?: SIMPLE
    }
}
