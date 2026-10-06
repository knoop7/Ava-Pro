package com.example.ava.voice

/**
 * LAN voice-call video presets. [SMOOTH] matches the original shipped defaults (480p @ 8fps).
 */
enum class VoiceCallVideoQuality(val storageKey: String) {
    /** Default — 480p @ 8fps (current production behavior). */
    SMOOTH("smooth"),
    /** 640p @ 10fps — higher clarity on good LAN. */
    HIGH("high"),
    /** 720p @ 12fps — maximum LAN quality. */
    ULTRA("ultra"),
    ;

    data class Params(
        val shortEdge: Int,
        val fps: Int,
        val jpegQuality: Int,
        val captureCapShortEdge: Int,
        val applyPolish: Boolean,
        val remoteUiMinIntervalMs: Long,
    )

    fun params(): Params = when (this) {
        SMOOTH -> Params(
            shortEdge = 480,
            fps = 8,
            jpegQuality = 75,
            captureCapShortEdge = 320,
            applyPolish = true,
            remoteUiMinIntervalMs = 125L,
        )
        HIGH -> Params(
            shortEdge = 640,
            fps = 10,
            jpegQuality = 80,
            captureCapShortEdge = 480,
            applyPolish = true,
            remoteUiMinIntervalMs = 100L,
        )
        ULTRA -> Params(
            shortEdge = 720,
            fps = 12,
            jpegQuality = 85,
            captureCapShortEdge = 640,
            applyPolish = true,
            remoteUiMinIntervalMs = 83L,
        )
    }

    companion object {
        fun fromStored(value: String?): VoiceCallVideoQuality =
            entries.find { it.storageKey == value?.trim() } ?: SMOOTH
    }
}
