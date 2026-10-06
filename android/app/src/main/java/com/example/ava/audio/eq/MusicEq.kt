package com.example.ava.audio.eq

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Persisted / runtime gains for the 5-band music output equalizer. */
data class MusicEqGains(
    val enabled: Boolean = false,
    val bassDb: Float = 0f,
    val lowMidDb: Float = 0f,
    val midDb: Float = 0f,
    val upperMidDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** Level-driven low/high contour layered on top of the bands. See [MusicEqEngine]. */
    val adaptiveEnabled: Boolean = false,
) {
    companion object {
        const val MIN_DB = -10f
        const val MAX_DB = 10f
        val FLAT = MusicEqGains()

        fun clampDb(value: Float): Float = value.coerceIn(MIN_DB, MAX_DB)
    }

    fun normalized(): MusicEqGains = copy(
        bassDb = clampDb(bassDb),
        lowMidDb = clampDb(lowMidDb),
        midDb = clampDb(midDb),
        upperMidDb = clampDb(upperMidDb),
        trebleDb = clampDb(trebleDb),
    )

    fun isBypassed(): Boolean {
        if (!enabled) return true
        // Adaptive rides on the bands, so a flat curve still has work to do.
        if (adaptiveEnabled) return false
        return bassDb == 0f &&
            lowMidDb == 0f &&
            midDb == 0f &&
            upperMidDb == 0f &&
            trebleDb == 0f
    }

    fun bandDb(index: Int): Float = when (index) {
        0 -> bassDb
        1 -> lowMidDb
        2 -> midDb
        3 -> upperMidDb
        4 -> trebleDb
        else -> 0f
    }

    fun withBandDb(index: Int, db: Float): MusicEqGains {
        val v = clampDb(db)
        return when (index) {
            0 -> copy(bassDb = v)
            1 -> copy(lowMidDb = v)
            2 -> copy(midDb = v)
            3 -> copy(upperMidDb = v)
            4 -> copy(trebleDb = v)
            else -> this
        }
    }
}

enum class MusicEqSource {
    HA,
    SENDSPIN,
}

/** Hot-swappable EQ curves read from the audio realtime path. */
object MusicEqRuntime {
    private val ha = AtomicReference(MusicEqGains.FLAT)
    private val sendspin = AtomicReference(MusicEqGains.FLAT)

    fun get(source: MusicEqSource): MusicEqGains = when (source) {
        MusicEqSource.HA -> ha.get()
        MusicEqSource.SENDSPIN -> sendspin.get()
    }

    fun set(source: MusicEqSource, gains: MusicEqGains) {
        val normalized = gains.normalized()
        when (source) {
            MusicEqSource.HA -> ha.set(normalized)
            MusicEqSource.SENDSPIN -> sendspin.set(normalized)
        }
    }
}

/**
 * Causal 5-band IIR EQ (RBJ biquads): low shelf, three peaking, high shelf.
 * Processes interleaved float samples in place. Group delay is negligible vs Sendspin sync.
 */
class MusicEqEngine(
    private val source: MusicEqSource,
) {
    private val bands = Array(BAND_COUNT) { Biquad() }
    private var sampleRate = 0
    private var channelCount = 0
    private var applied = MusicEqGains.FLAT

    /** False while the EQ is bypassed, so the next active buffer starts from clean history. */
    private var streaming = false

    /** Smoothed 0..1 weight for "the programme is quiet right now". */
    private var contour = 0.0

    fun reset() {
        for (band in bands) band.reset()
        applied = MusicEqGains.FLAT
        sampleRate = 0
        channelCount = 0
        streaming = false
        contour = 0.0
    }

    fun processInterleaved(samples: FloatArray, sampleRateHz: Int, channels: Int) {
        if (samples.isEmpty()) return
        val gains = MusicEqRuntime.get(source)
        if (gains.isBypassed()) {
            streaming = false
            contour = 0.0
            return
        }
        val rate = sampleRateHz.coerceAtLeast(1)
        val ch = channels.coerceAtLeast(1)
        val frames = samples.size / ch
        if (frames <= 0) return
        val effective = if (gains.adaptiveEnabled) {
            adaptiveGains(gains, samples, frames, rate)
        } else {
            contour = 0.0
            gains
        }
        ensureConfigured(rate, ch, effective)
        for (frame in 0 until frames) {
            val base = frame * ch
            for (c in 0 until ch) {
                var x = samples[base + c]
                for (band in bands) {
                    x = band.process(c, x)
                }
                samples[base + c] = x
            }
        }
    }

    /**
     * Equal-loudness style contour: quiet material gets a gentle low/high lift that fades out as
     * the mix gets loud. Being level-driven, the lift can only appear where there is headroom for
     * it, and it falls back faster than it rises so a loud entry does not clip.
     */
    private fun adaptiveGains(
        gains: MusicEqGains,
        samples: FloatArray,
        frames: Int,
        rate: Int,
    ): MusicEqGains {
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        val meanSquare = sum / samples.size
        val levelDb = if (meanSquare > 1e-12) 10.0 * log10(meanSquare) else -120.0
        val target = ((LOUD_REF_DB - levelDb) / CONTOUR_RANGE_DB).coerceIn(0.0, 1.0)
        val seconds = frames.toDouble() / rate
        val tau = if (target > contour) CONTOUR_RISE_SECONDS else CONTOUR_FALL_SECONDS
        contour += (target - contour) * (1.0 - exp(-seconds / tau))
        // Quantised so level wobble does not rebuild coefficients on every buffer.
        val bass = quantise(ADAPTIVE_BASS_DB * contour)
        val treble = quantise(ADAPTIVE_TREBLE_DB * contour)
        if (bass == 0f && treble == 0f) return gains
        return gains.copy(
            bassDb = MusicEqGains.clampDb(gains.bassDb + bass),
            trebleDb = MusicEqGains.clampDb(gains.trebleDb + treble),
        )
    }

    private fun quantise(db: Double): Float =
        (round(db / ADAPTIVE_STEP_DB) * ADAPTIVE_STEP_DB).toFloat()

    private fun ensureConfigured(rate: Int, channels: Int, gains: MusicEqGains) {
        val discontinuity = !streaming || rate != sampleRate || channels != channelCount
        if (!discontinuity && gains == applied) return
        sampleRate = rate
        channelCount = channels
        applied = gains
        streaming = true
        bands[0].configureLowShelf(rate, BASS_HZ, gains.bassDb, channels)
        bands[1].configurePeaking(rate, LOW_MID_HZ, gains.lowMidDb, PEAK_Q, channels)
        bands[2].configurePeaking(rate, MID_HZ, gains.midDb, PEAK_Q, channels)
        bands[3].configurePeaking(rate, UPPER_MID_HZ, gains.upperMidDb, PEAK_Q, channels)
        bands[4].configureHighShelf(rate, TREBLE_HZ, gains.trebleDb, channels)
        // Filter history stays valid across gain-only edits; clearing it clicks mid-track.
        if (discontinuity) {
            for (band in bands) band.reset()
        }
    }

    companion object {
        const val BAND_COUNT = 5
        private const val BASS_HZ = 80.0
        private const val LOW_MID_HZ = 250.0
        private const val MID_HZ = 1000.0
        private const val UPPER_MID_HZ = 4000.0
        private const val TREBLE_HZ = 10000.0
        private const val PEAK_Q = 0.7

        /** Programme RMS at or above which the adaptive contour is fully out of the way. */
        private const val LOUD_REF_DB = -14.0
        /** dB below [LOUD_REF_DB] at which the contour reaches full strength. */
        private const val CONTOUR_RANGE_DB = 20.0
        private const val CONTOUR_RISE_SECONDS = 1.5
        private const val CONTOUR_FALL_SECONDS = 0.4
        private const val ADAPTIVE_BASS_DB = 3.0
        private const val ADAPTIVE_TREBLE_DB = 1.5
        private const val ADAPTIVE_STEP_DB = 0.25
    }
}

/**
 * At 0 dB the RBJ numerator equals the denominator, so a band is then an exact passthrough.
 * That is why there is no bypass branch: keeping the real coefficients lets a band cross
 * through 0 dB without a state discontinuity, at identical cost per sample.
 */
private class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    fun configureLowShelf(sampleRate: Int, freq: Double, gainDb: Float, channels: Int) {
        ensureState(channels)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val b0n = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
        val b1n = 2.0 * a * ((a - 1) - (a + 1) * cosW)
        val b2n = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
        val a0n = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
        val a1n = -2.0 * ((a - 1) + (a + 1) * cosW)
        val a2n = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
        normalize(b0n, b1n, b2n, a0n, a1n, a2n)
    }

    fun configureHighShelf(sampleRate: Int, freq: Double, gainDb: Float, channels: Int) {
        ensureState(channels)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val b0n = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
        val b1n = -2.0 * a * ((a - 1) + (a + 1) * cosW)
        val b2n = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
        val a0n = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
        val a1n = 2.0 * ((a - 1) - (a + 1) * cosW)
        val a2n = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
        normalize(b0n, b1n, b2n, a0n, a1n, a2n)
    }

    fun configurePeaking(sampleRate: Int, freq: Double, gainDb: Float, q: Double, channels: Int) {
        ensureState(channels)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)
        val b0n = 1.0 + alpha * a
        val b1n = -2.0 * cosW
        val b2n = 1.0 - alpha * a
        val a0n = 1.0 + alpha / a
        val a1n = -2.0 * cosW
        val a2n = 1.0 - alpha / a
        normalize(b0n, b1n, b2n, a0n, a1n, a2n)
    }

    fun process(channel: Int, input: Float): Float {
        if (channel !in z1.indices) return input
        val x = input.toDouble()
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y.toFloat()
    }

    private fun ensureState(channels: Int) {
        if (z1.size != channels) {
            z1 = DoubleArray(channels)
            z2 = DoubleArray(channels)
        }
    }

    private fun normalize(
        b0n: Double,
        b1n: Double,
        b2n: Double,
        a0n: Double,
        a1n: Double,
        a2n: Double,
    ) {
        val inv = 1.0 / a0n
        b0 = b0n * inv
        b1 = b1n * inv
        b2 = b2n * inv
        a1 = a1n * inv
        a2 = a2n * inv
    }
}
