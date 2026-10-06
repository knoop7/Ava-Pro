package com.example.ava.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioEnergy {
    private const val MIC_NORMALIZE = 2400.0
    private const val PLAYBACK_NORMALIZE = 2200.0

    fun rmsLevelMic(audioBytes: ByteArray): Float =
        normalizeRms(computePcm16Rms(audioBytes), MIC_NORMALIZE)

    fun rmsLevel(audioBytes: ByteArray): Float = rmsLevelMic(audioBytes)

    fun rmsLevelPlayback(buffer: ByteBuffer): Float =
        normalizeRms(computeBufferRms(buffer), PLAYBACK_NORMALIZE)

    fun rmsLevel(buffer: ByteBuffer): Float = rmsLevelPlayback(buffer)

    /** RMS of PCM16 LE samples normalized to ±1 float (matches vsWakeWord detector input scale). */
    fun pcm16LeFloatRms(buffer: ByteBuffer): Float {
        val rms = computeBufferRms(buffer)
        return (rms / 32768.0).toFloat()
    }

    /** Fraction of PCM16 LE samples that are non-zero (detects dead / wrong-format capture). */
    fun pcm16LeNonZeroRatio(buffer: ByteBuffer): Float {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (dup.remaining() < 2) return 0f
        var nonZero = 0
        var total = 0
        while (dup.remaining() >= 2) {
            if (dup.short.toInt() != 0) nonZero++
            total++
        }
        return if (total > 0) nonZero.toFloat() / total.toFloat() else 0f
    }

    /** Peak absolute PCM16 LE sample normalized to ±1 float. */
    fun pcm16LeFloatPeak(buffer: ByteBuffer): Float {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (dup.remaining() < 2) return 0f
        var peak = 0
        while (dup.remaining() >= 2) {
            peak = maxOf(peak, kotlin.math.abs(dup.short.toInt()))
        }
        return (peak / 32768.0f).coerceIn(0f, 1f)
    }

    private fun computePcm16Rms(audioBytes: ByteArray): Double {
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

    private fun computeBufferRms(buffer: ByteBuffer): Double {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (dup.remaining() < 2) return 0.0
        var sumSquares = 0.0
        var sampleCount = 0
        while (dup.remaining() >= 2) {
            val sample = dup.short.toInt()
            sumSquares += sample * sample
            sampleCount++
        }
        return if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
    }

    private fun normalizeRms(rms: Double, scale: Double): Float =
        (rms / scale).toFloat().coerceIn(0f, 1f)
}
