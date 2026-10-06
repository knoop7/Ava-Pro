package com.example.ava.audio

import kotlin.math.sqrt

class SilenceDetector(
    private val silenceThreshold: Int = 200,
    private val silenceDurationMs: Long = 3500,
    private val minSpeechDurationMs: Long = 400
) {
    private var lastSoundTime: Long = 0
    private var speechStartTime: Long = 0
    var isSpeaking: Boolean = false
        private set

    fun reset() {
        lastSoundTime = 0
        speechStartTime = 0
        isSpeaking = false
    }

    fun forceStartSpeaking() {
        val now = System.currentTimeMillis()
        isSpeaking = true
        speechStartTime = now
        lastSoundTime = now
    }

    fun processAudio(audioBytes: ByteArray): Boolean {
        val currentTime = System.currentTimeMillis()
        val silence = isSilent(audioBytes)

        if (silence) {
            if (isSpeaking && lastSoundTime > 0) {
                val silenceDur = currentTime - lastSoundTime
                if (silenceDur >= silenceDurationMs) {
                    val speechDur = lastSoundTime - speechStartTime
                    if (speechDur >= minSpeechDurationMs) {
                        isSpeaking = false
                        return true
                    }
                    isSpeaking = false
                }
            }
        } else {
            lastSoundTime = currentTime
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTime = currentTime
            }
        }

        return false
    }

    fun isSilent(audioBytes: ByteArray): Boolean {
        val rms = calculateRMS(audioBytes)
        return rms < silenceThreshold
    }

    private fun calculateRMS(audioBytes: ByteArray): Double {
        if (audioBytes.size < 2) return 0.0

        var sumSquares = 0.0
        var sampleCount = 0

        var i = 0
        while (i < audioBytes.size - 1) {
            val lo = audioBytes[i].toInt() and 0xFF
            val hi = audioBytes[i + 1].toInt()
            val sample = lo or (hi shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSquares += signed * signed
            sampleCount++
            i += 2
        }

        return if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
    }
}
