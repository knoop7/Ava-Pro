package com.example.ava.settings

/** Full-screen music overlay layout. Fresh installs default to [DETAILED]; [MINIMAL] is legacy. */
enum class MediaOverlayStyle(val storageKey: String) {
    MINIMAL("minimal"),
    DETAILED("detailed"),
    ;

    companion object {
        fun fromStored(value: String?): MediaOverlayStyle =
            entries.find { it.storageKey == value?.trim() } ?: MINIMAL
    }
}
