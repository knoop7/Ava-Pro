package com.example.ava.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Far-end signal for the echo cancellation preview: band-limited noise with a slow tremolo, so it
 * reads as a hum. Broadband on purpose — a pure tone is narrowband and adaptive echo filters
 * converge poorly on it, which would make a healthy AEC look broken.
 *
 * [CHUNK_MS] holds a whole number of tremolo cycles so looped chunks stay seamless.
 */
object EchoTestTone {
    const val CHUNK_MS = 500

    fun buildChunkPcm16Mono(sampleRateHz: Int = PlaybackReferenceBus.SAMPLE_RATE): ByteArray {
        val samples = CHUNK_MS * sampleRateHz / 1000
        val out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val random = Random(SEED)
        val lowPassCoeff = onePoleCoeff(LOW_PASS_HZ, sampleRateHz)
        val highPassCoeff = onePoleCoeff(HIGH_PASS_HZ, sampleRateHz)
        var lowPass = 0f
        var highPass = 0f
        for (i in 0 until samples) {
            val white = random.nextFloat() * 2f - 1f
            lowPass += lowPassCoeff * (white - lowPass)
            highPass += highPassCoeff * (lowPass - highPass)
            val tremolo = 1f - TREMOLO_DEPTH +
                TREMOLO_DEPTH * sin(2f * PI.toFloat() * TREMOLO_HZ * i / sampleRateHz)
            val value = ((lowPass - highPass) * tremolo * AMPLITUDE * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            out.putShort(value.toInt().toShort())
        }
        return out.array()
    }

    private fun onePoleCoeff(cutoffHz: Float, sampleRateHz: Int): Float =
        (2f * PI.toFloat() * cutoffHz / sampleRateHz).coerceIn(0.0001f, 0.99f)

    private const val HIGH_PASS_HZ = 150f
    private const val LOW_PASS_HZ = 3_000f
    private const val TREMOLO_HZ = 8f
    private const val TREMOLO_DEPTH = 0.2f
    private const val AMPLITUDE = 0.4f
    private const val SEED = 1_337
}
