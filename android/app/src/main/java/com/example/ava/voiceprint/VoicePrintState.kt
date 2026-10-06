package com.example.ava.voiceprint

sealed class VoicePrintState {
    data object Disabled : VoicePrintState()
    /** Auto mode only: passively collecting templates. */
    data object Learning : VoicePrintState()
    /** Manual mode only: no enrolled templates yet (sensor reports idle). */
    data object NotEnrolled : VoicePrintState()
    /** Standby with enrolled templates (sensor reports idle). */
    data object Ready : VoicePrintState()
    data class Identified(val index: Int, val displayName: String, val confidence: Float) : VoicePrintState()
    data object Unknown : VoicePrintState()
    data object LowQuality : VoicePrintState()
}

/** Home Assistant sensor / status token (English snake_case). */
fun VoicePrintState.statusText(): String = when (this) {
    VoicePrintState.Disabled -> "disabled"
    VoicePrintState.Learning -> "learning"
    VoicePrintState.NotEnrolled,
    VoicePrintState.Ready,
    -> "idle"
    is VoicePrintState.Identified -> displayName
    VoicePrintState.Unknown -> "stranger"
    VoicePrintState.LowQuality -> "low_quality"
}

fun voicePrintDisplayName(index: Int, names: List<String>): String {
    val custom = names.getOrNull(index)?.trim().orEmpty()
    return custom.ifBlank {
        when (index) {
            0 -> "User 1"
            1 -> "User 2"
            else -> "User 1"
        }
    }
}
