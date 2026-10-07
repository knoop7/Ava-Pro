package com.example.ava.ui

import android.graphics.Color

object VoiceAccentColors {
    const val DEFAULT_WAKE_WORD_1_HEX = "#00FF88"
    const val DEFAULT_WAKE_WORD_2_HEX = "#00D4FF"

    val WAKE_WORD_1 = Color.parseColor(DEFAULT_WAKE_WORD_1_HEX)
    val WAKE_WORD_2 = Color.parseColor(DEFAULT_WAKE_WORD_2_HEX)

    /**
     * Seven rainbow presets in spectrum order (red→orange→yellow→green→cyan→blue→purple).
     * Keys are stored in [PlayerSettings.voiceWakeWord1AccentColor] / word 2.
     */
    val RAINBOW_PRESETS: List<Pair<String, Int>> = listOf(
        "voice_rainbow_red" to Color.parseColor("#FF4757"),
        "voice_rainbow_orange" to Color.parseColor("#FF9500"),
        "voice_rainbow_yellow" to Color.parseColor("#FFD60A"),
        "voice_rainbow_green" to Color.parseColor("#34C759"),
        "voice_rainbow_cyan" to Color.parseColor("#00D4FF"),
        "voice_rainbow_blue" to Color.parseColor("#007AFF"),
        "voice_rainbow_purple" to Color.parseColor("#AF52DE"),
    )

    private val rainbowColorMap = RAINBOW_PRESETS.toMap()

    /** Preset key, #RRGGBB, or empty for [defaultColor]. */
    fun resolve(colorSpec: String, defaultColor: Int): Int {
        val trimmed = colorSpec.trim()
        if (trimmed.isEmpty()) return defaultColor
        if (trimmed.startsWith("#")) {
            return try {
                Color.parseColor(trimmed)
            } catch (_: Exception) {
                defaultColor
            }
        }
        return rainbowColorMap[trimmed] ?: defaultColor
    }

    fun forWakeWordIndex(
        wakeWordIndex: Int,
        wakeWord1ColorSpec: String = "",
        wakeWord2ColorSpec: String = ""
    ): Int = if (wakeWordIndex == 0) {
        resolve(wakeWord1ColorSpec, WAKE_WORD_1)
    } else {
        resolve(wakeWord2ColorSpec, WAKE_WORD_2)
    }
}
