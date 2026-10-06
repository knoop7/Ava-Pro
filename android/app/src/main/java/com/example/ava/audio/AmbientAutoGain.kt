package com.example.ava.audio

import com.example.ava.settings.PlayerSettings
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Additive TTS auto-gain from idle-mic ambient RMS.
 * Never lowers the user slider; output is [slider, 1].
 */
object AmbientAutoGain {
    const val QUIET_DB = -48f
    const val LOUD_DB = -22f
    /** Max boost as a fraction of full scale (55 percentage points). */
    const val MAX_ADD = 0.55f
    /** 1 = linear; higher stays quieter in mild rooms. Was 2 (t²). */
    const val CURVE = 1.35f

    enum class NoiseBand { Quiet, Normal, Noisy, Loud }

    fun dbFromRms(rms: Float): Float =
        20f * log10(rms.coerceAtLeast(1e-6f))

    /** 0 = quiet room, 1 = very loud. */
    fun noiseT(rms: Float): Float {
        val t = ((dbFromRms(rms) - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)
        return t.pow(CURVE)
    }

    fun gain(slider: Float, rms: Float): Float {
        val base = slider.coerceIn(0f, 1f)
        val remaining = (1f - base).coerceAtLeast(0f)
        val raw = (MAX_ADD * noiseT(rms)).coerceAtMost(remaining)
        return snapPercent(raw)
    }

    fun output(slider: Float, rms: Float): Float {
        val base = slider.coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f)
        return snapPercent((base + gain(base, rms)).coerceAtMost(1f))
    }

    /** Whole percentage points only, e.g. 0.12 not 0.123. */
    fun snapPercent(level: Float): Float =
        level.times(100f).roundToInt().coerceIn(0, 100) / 100f

    fun noiseBand(rms: Float): NoiseBand {
        val db = dbFromRms(rms)
        return when {
            db < -42f -> NoiseBand.Quiet
            db < -32f -> NoiseBand.Normal
            db < -22f -> NoiseBand.Noisy
            else -> NoiseBand.Loud
        }
    }

    fun rmsFromDb(db: Float): Float = 10.0.pow((db / 20.0)).toFloat()

    /** Typical RMS for drawing virtual gears on the TTS slider. */
    fun representativeRms(band: NoiseBand): Float = rmsFromDb(
        when (band) {
            NoiseBand.Quiet -> -50f
            NoiseBand.Normal -> -36f
            NoiseBand.Noisy -> -26f
            NoiseBand.Loud -> -16f
        },
    )

    fun virtualGearOutputs(slider: Float): List<Float> =
        NoiseBand.entries.map { output(slider, representativeRms(it)) }
}
