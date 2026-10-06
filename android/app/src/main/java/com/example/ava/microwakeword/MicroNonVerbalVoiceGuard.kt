package com.example.ava.microwakeword

import kotlin.math.max
import kotlin.math.sqrt

/** Candidate-time rejection for strongly periodic non-verbal voice (humming/mm-hmm). */
internal object MicroNonVerbalVoiceGuard {
    private const val WINDOW_SAMPLES = 7_680 // 480 ms at 16 kHz
    private const val FRAME_SAMPLES = 640 // 40 ms
    private const val MIN_ACTIVE_FRAMES = 6
    private const val MIN_PERIODIC_FRACTION = 0.90
    private const val MIN_MEDIAN_CORRELATION = 0.885
    private const val PERIODIC_CORRELATION = 0.72
    private const val MIN_LAG = 20 // 800 Hz
    private const val MAX_LAG = 200 // 80 Hz

    fun isSustainedHum(pcm: ShortArray): Boolean {
        val sampleCount = minOf(pcm.size, WINDOW_SAMPLES)
        val frameCount = sampleCount / FRAME_SAMPLES
        if (frameCount < MIN_ACTIVE_FRAMES) return false

        val start = pcm.size - frameCount * FRAME_SAMPLES
        val rms = DoubleArray(frameCount)
        var maxRms = 0.0
        for (frame in 0 until frameCount) {
            val base = start + frame * FRAME_SAMPLES
            var sumSquares = 0.0
            for (i in 0 until FRAME_SAMPLES) {
                val value = pcm[base + i].toDouble()
                sumSquares += value * value
            }
            rms[frame] = sqrt(sumSquares / FRAME_SAMPLES)
            maxRms = max(maxRms, rms[frame])
        }

        val activeFloor = max(80.0, maxRms * 0.01)
        val correlations = ArrayList<Double>(frameCount)
        for (frame in 0 until frameCount) {
            if (rms[frame] < activeFloor) continue
            val base = start + frame * FRAME_SAMPLES
            var mean = 0.0
            for (i in 0 until FRAME_SAMPLES) mean += pcm[base + i]
            mean /= FRAME_SAMPLES

            var energy = 0.0
            for (i in 0 until FRAME_SAMPLES) {
                val value = pcm[base + i] - mean
                energy += value * value
            }
            if (energy <= 1.0) continue

            var best = -1.0
            for (lag in MIN_LAG..MAX_LAG) {
                var correlation = 0.0
                for (i in 0 until FRAME_SAMPLES - lag) {
                    correlation +=
                        (pcm[base + i] - mean) * (pcm[base + i + lag] - mean)
                }
                best = max(best, correlation / energy)
            }
            correlations.add(best)
        }

        if (correlations.size < MIN_ACTIVE_FRAMES) return false
        val periodicFrames = correlations.count { it >= PERIODIC_CORRELATION }
        val periodicFraction = periodicFrames.toDouble() / correlations.size
        val sorted = correlations.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
        return periodicFraction >= MIN_PERIODIC_FRACTION &&
            median >= MIN_MEDIAN_CORRELATION
    }
}
