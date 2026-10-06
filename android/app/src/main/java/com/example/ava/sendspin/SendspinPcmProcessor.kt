package com.example.ava.sendspin

import android.media.AudioFormat
import android.media.AudioManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM post-processing before [android.media.AudioTrack].
 *
 * Primary anti-clip work is **correct sample format interpretation** (16/24/32/int/float)
 * plus a fixed headroom — not peak heuristics on mis-decoded bytes.
 */
object SendspinPcmProcessor {

    private const val TAG = "SendspinPcmProcessor"

    /** Margin for upstream float pipelines (MA internal 32-bit float path). */
    private const val OUTPUT_HEADROOM_DB = -6f
    private const val DIGITAL_CEILING_DB = -1.5f
    private const val SOFT_KNEE_RATIO = 8f

    enum class InputFormat {
        INT16_LE,
        INT24_PACKED_LE,
        INT32_LE,
        IEEE_FLOAT32_LE
    }

    data class SourceFormat(
        val bitDepth: Int = 16,
        val pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
    ) {
        fun toInputFormat(): InputFormat = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> InputFormat.IEEE_FLOAT32_LE
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> InputFormat.INT24_PACKED_LE
            AudioFormat.ENCODING_PCM_32BIT -> InputFormat.INT32_LE
            else -> when (bitDepth) {
                24 -> InputFormat.INT24_PACKED_LE
                32 -> InputFormat.INT32_LE
                else -> InputFormat.INT16_LE
            }
        }
    }

    data class PlaybackGain(
        val appVolumeLinear: Float = 1f,
        val deviceVolumeLinear: Float = 1f,
        val useDeviceVolume: Boolean = true,
        val muted: Boolean = false,
        /** Voice-session duck — attenuate, do not hard-mute (avoids unDuck burst). */
        val duckLinear: Float = 1f
    )

    class LimiterState {
        @Volatile
        var envelopeGain: Float = 1f
            private set

        fun reset() {
            envelopeGain = 1f
        }

        fun applyGain(targetGain: Float): Float {
            val target = targetGain.coerceIn(0f, 1f)
            envelopeGain = if (target < envelopeGain) target else envelopeGain * 0.9f + target * 0.1f
            return envelopeGain
        }
    }

    fun deviceVolumeLinear(audioManager: AudioManager): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
    }

    /** Sendspin spec: perceived 0–100 → linear PCM gain. */
    fun perceptualVolumePercentToLinear(percent: Int): Float {
        val normalized = (percent.coerceIn(0, 100) / 100f)
        return normalized.pow(1.5f)
    }

    fun linearToDb(linear: Float): Float {
        if (linear <= 0f) return -120f
        return (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()
    }

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    fun process(
        pcm: ByteArray,
        source: SourceFormat,
        gain: PlaybackGain,
        limiterState: LimiterState? = null,
        eqEngine: com.example.ava.audio.eq.MusicEqEngine? = null,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
    ): ByteArray {
        if (pcm.isEmpty() || gain.muted) return ByteArray(0)
        if (gain.useDeviceVolume && gain.deviceVolumeLinear <= 0f) return ByteArray(0)

        val inputFormat = source.toInputFormat()
        val floats = toFloatLe(pcm, inputFormat)
        if (floats.isEmpty()) return ByteArray(0)

        val appLinear = gain.appVolumeLinear.coerceIn(0f, 1f)
        val duckLinear = gain.duckLinear.coerceIn(0f, 1f)
        if (!gain.useDeviceVolume && appLinear <= 0f) return ByteArray(floats.size * 2)
        if (duckLinear <= 0f) return ByteArray(floats.size * 2)

        return fromFloatLe16(
            processFloatSamples(
                floats,
                appLinear,
                gain.useDeviceVolume,
                duckLinear,
                limiterState,
                eqEngine,
                sampleRateHz,
                channelCount,
            )
        )
    }

    /** Backward-compatible entry — assumes integer PCM from bit depth only. */
    fun process(
        pcm: ByteArray,
        sourceBitDepth: Int,
        gain: PlaybackGain,
        limiterState: LimiterState? = null
    ): ByteArray = process(pcm, SourceFormat(sourceBitDepth), gain, limiterState)

    private fun processFloatSamples(
        samples: FloatArray,
        appLinear: Float,
        useDeviceVolume: Boolean,
        duckLinear: Float,
        limiterState: LimiterState?,
        eqEngine: com.example.ava.audio.eq.MusicEqEngine?,
        sampleRateHz: Int,
        channelCount: Int,
    ): FloatArray {
        var peak = peakAbs(samples)
        if (peak > 1f) {
            val inv = 1f / peak
            for (i in samples.indices) samples[i] *= inv
            Log.w(TAG, "PCM float overs (peak=${"%.2f".format(peak)}) attenuated — likely upstream float > 0dBFS")
            peak = 1f
        }

        if (!useDeviceVolume) {
            for (i in samples.indices) samples[i] *= appLinear
        }

        // EQ before headroom so boost is absorbed by the existing −6 dB margin / limiter.
        eqEngine?.processInterleaved(samples, sampleRateHz, channelCount)

        val headroom = dbToLinear(OUTPUT_HEADROOM_DB)
        for (i in samples.indices) samples[i] *= headroom

        if (duckLinear < 0.999f) {
            for (i in samples.indices) samples[i] *= duckLinear
        }

        val ceiling = dbToLinear(DIGITAL_CEILING_DB)
        peak = peakAbs(samples)
        val targetGain = if (peak > ceiling && peak > 1e-8f) ceiling / peak else 1f
        val applied = limiterState?.applyGain(targetGain) ?: targetGain
        if (applied < 0.999f) {
            for (i in samples.indices) samples[i] *= applied
        }
        for (i in samples.indices) {
            samples[i] = softLimit(samples[i], ceiling)
        }
        return samples
    }

    private fun softLimit(x: Float, ceiling: Float): Float {
        val ax = abs(x)
        if (ax <= ceiling) return x
        val sign = if (x >= 0f) 1f else -1f
        val excess = ax - ceiling
        return sign * (ceiling + excess / (1f + SOFT_KNEE_RATIO * excess)).coerceAtMost(ceiling)
    }

    fun toFloatLe(pcm: ByteArray, format: InputFormat): FloatArray = when (format) {
        InputFormat.INT16_LE -> float16Le(pcm)
        InputFormat.INT24_PACKED_LE -> float24PackedLe(pcm)
        InputFormat.INT32_LE -> float32IntLe(pcm)
        InputFormat.IEEE_FLOAT32_LE -> float32IeeeLe(pcm)
    }

    private fun float16Le(pcm16: ByteArray): FloatArray {
        val count = pcm16.size / 2
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        val buffer = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out[i] = buffer.getShort(i * 2) / 32768f
        return out
    }

    private fun float24PackedLe(pcm24: ByteArray): FloatArray {
        val count = pcm24.size / 3
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        val input = ByteBuffer.wrap(pcm24).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val b0 = input.get().toInt() and 0xFF
            val b1 = input.get().toInt() and 0xFF
            val b2 = input.get().toInt() and 0xFF
            var s = b0 or (b1 shl 8) or (b2 shl 16)
            if (s and 0x800000 != 0) s = s or -0x1000000
            out[i] = s / 8388608f
        }
        return out
    }

    private fun float32IntLe(pcm32: ByteArray): FloatArray {
        val count = pcm32.size / 4
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        val input = ByteBuffer.wrap(pcm32).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out[i] = input.int / 2147483648f
        return out
    }

    private fun float32IeeeLe(pcm: ByteArray): FloatArray {
        val count = pcm.size / 4
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out[i] = input.getFloat(i * 4)
        return out
    }

    private fun fromFloatLe16(samples: FloatArray): ByteArray {
        val ceiling = dbToLinear(DIGITAL_CEILING_DB)
        val out = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val scaled = (sample.coerceIn(-ceiling, ceiling) * 32767f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(scaled.toShort())
        }
        return out
    }

    private fun peakAbs(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) {
            val v = abs(s)
            if (v > peak) peak = v
        }
        return peak
    }
}
