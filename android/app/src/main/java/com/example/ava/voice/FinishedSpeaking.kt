package com.example.ava.voice

/**
 * Home Assistant's per-satellite "Finished speaking detection" select.
 *
 * The ESPHome integration creates it (`{mac}-vad_sensitivity`). Assist reads
 * that state when it starts a listen and maps it to silence before STT ends:
 * aggressive 0.25s, default 0.7s, relaxed 1.25s. Ava does not time this itself.
 */
object FinishedSpeaking {
    const val DEFAULT = "default"
    const val RELAXED = "relaxed"
    const val AGGRESSIVE = "aggressive"

    /** Same order as HA `VadSensitivity` / the select's `options`. */
    val options = listOf(DEFAULT, RELAXED, AGGRESSIVE)

    fun canonical(state: String?): String? {
        val value = state?.trim()?.lowercase().orEmpty()
        return value.takeIf { it in options }
    }

    /** Registry unique_id HA assigns: the device MAC plus `-vad_sensitivity`. */
    fun matches(uniqueId: String, mac: String): Boolean {
        val node = mac.replace(":", "").lowercase()
        if (node.isBlank() || node.all { it == '0' }) return false
        val id = uniqueId.replace(":", "").lowercase()
        return id == "$node-vad_sensitivity"
    }
}
