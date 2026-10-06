package com.example.ava.audio

/**
 * Smoothed playback RMS from ExoPlayer PCM tap (TTS player only).
 */
object PlaybackEnergyMonitor {
    @Volatile
    private var enabled = false

    @Volatile
    private var smoothedLevel = 0f

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            smoothedLevel = 0f
        }
    }

    fun isEnabled(): Boolean = enabled

    fun onLevel(level: Float) {
        if (!enabled) return
        val prev = smoothedLevel
        smoothedLevel = (prev * 0.5f + level.coerceIn(0f, 1f) * 0.5f).coerceIn(0f, 1f)
    }

    fun currentLevel(): Float = smoothedLevel

    fun reset() {
        smoothedLevel = 0f
    }
}
