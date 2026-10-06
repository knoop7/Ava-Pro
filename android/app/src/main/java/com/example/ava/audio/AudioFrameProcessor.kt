package com.example.ava.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** How stereo capture is collapsed to mono before resampling. */
enum class StereoDownmixMode {
    /** Pick the louder channel per sample (legacy default). */
    MAX_CHANNEL,
    /** (L + R) / 2 — works when both channels carry the same mic signal. */
    AVERAGE,
    /** Use left channel only. */
    LEFT_ONLY,
    /** Use right channel only. */
    RIGHT_ONLY,
    /**
     * If one channel is clearly dominant (4× louder), use it; otherwise average.
     * Handles devices that put the mic on one channel or duplicate it on both.
     */
    DOMINANT_OR_AVERAGE,
}

object AudioFrameProcessor {
    fun process(
        input: ByteBuffer,
        profile: DeviceAudioProfile,
        downmix: StereoDownmixMode = StereoDownmixMode.DOMINANT_OR_AVERAGE,
    ): ByteBuffer {
        if (!profile.requiresProcessing) {
            return copyBuffer(input)
        }

        if (profile.captureChannelCount == 2 && profile.outputChannelCount == 1) {
            return when (profile.captureSampleRateInHz) {
                32000 if profile.outputSampleRateInHz == 16000 -> stereo32kToMono16k(input, downmix)
                48000 if profile.outputSampleRateInHz == 16000 -> stereo48kToMono16k(input, downmix)
                else -> resampleMono(
                    downmixStereoToMono(input, downmix),
                    profile.captureSampleRateInHz,
                    profile.outputSampleRateInHz,
                )
            }
        }

        if (profile.captureChannelCount == 1 && profile.outputChannelCount == 1 &&
            profile.captureSampleRateInHz != profile.outputSampleRateInHz
        ) {
            return resampleMono(
                copyBuffer(input),
                profile.captureSampleRateInHz,
                profile.outputSampleRateInHz,
            )
        }

        return copyBuffer(input)
    }

    private fun copyBuffer(input: ByteBuffer): ByteBuffer {
        val copy = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        copy.put(input.duplicate())
        copy.flip()
        return copy
    }

    internal fun mixStereoSample(left: Int, right: Int, mode: StereoDownmixMode): Int {
        val mixed = when (mode) {
            StereoDownmixMode.MAX_CHANNEL ->
                if (abs(left) >= abs(right)) left else right
            StereoDownmixMode.AVERAGE -> (left + right) / 2
            StereoDownmixMode.LEFT_ONLY -> left
            StereoDownmixMode.RIGHT_ONLY -> right
            StereoDownmixMode.DOMINANT_OR_AVERAGE -> {
                val al = abs(left)
                val ar = abs(right)
                when {
                    al >= ar * 4 -> left
                    ar >= al * 4 -> right
                    else -> (left + right) / 2
                }
            }
        }
        return mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /** Per-sample stereo downmix before generic resampling. */
    private fun downmixStereoToMono(input: ByteBuffer, mode: StereoDownmixMode): ByteBuffer {
        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val outputCapacity = (source.remaining() / 4) * 2
        val output = ByteBuffer.allocateDirect(outputCapacity).order(ByteOrder.LITTLE_ENDIAN)
        while (source.remaining() >= 4) {
            val left = source.short.toInt()
            val right = source.short.toInt()
            output.putShort(mixStereoSample(left, right, mode).toShort())
        }
        output.flip()
        return output
    }

    /** Generic mono PCM16 resampler for profile capture → output rates (e.g. 48 kHz → 16 kHz). */
    internal fun resampleMono(input: ByteBuffer, fromRate: Int, toRate: Int): ByteBuffer {
        if (fromRate == toRate) return copyBuffer(input)

        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val inputSamples = source.remaining() / 2
        if (inputSamples <= 0) {
            return ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
        }

        val outputSamples = ((inputSamples.toLong() * toRate) / fromRate).toInt().coerceAtLeast(0)
        val output = ByteBuffer.allocateDirect(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        if (outputSamples == 0) {
            output.flip()
            return output
        }

        if (fromRate > toRate && fromRate % toRate == 0) {
            val factor = fromRate / toRate
            while (source.remaining() >= factor * 2) {
                var sum = 0
                repeat(factor) {
                    sum += source.short.toInt()
                }
                val averaged = (sum / factor)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(averaged.toShort())
            }
            output.flip()
            return output
        }

        if (fromRate < toRate && toRate % fromRate == 0) {
            val factor = toRate / fromRate
            while (source.remaining() >= 2) {
                val sample = source.short
                repeat(factor) {
                    output.putShort(sample)
                }
            }
            output.flip()
            return output
        }

        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (outIndex in 0 until outputSamples) {
            val inPos = outIndex * ratio
            val inIndex = inPos.toInt().coerceIn(0, inputSamples - 1)
            val frac = (inPos - inIndex).coerceIn(0.0, 1.0)
            val s0 = readSampleAt(source, inIndex)
            val s1 = readSampleAt(source, (inIndex + 1).coerceAtMost(inputSamples - 1))
            val interpolated = (s0 + (s1 - s0) * frac)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(interpolated.toShort())
        }
        output.flip()
        return output
    }

    private fun readSampleAt(source: ByteBuffer, sampleIndex: Int): Int {
        val pos = sampleIndex * 2
        if (pos + 1 >= source.limit()) return 0
        return source.getShort(source.position() + pos).toInt()
    }

    // The S8 line-array profile behaves best when capture stays 32 kHz stereo,
    // then we collapse it back to the 16 kHz mono stream expected by Ava's wake word pipeline.
    private fun stereo32kToMono16k(input: ByteBuffer, mode: StereoDownmixMode): ByteBuffer {
        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val pairSizeBytes = 8
        val outputCapacity = (source.remaining() / pairSizeBytes) * 2
        val output = ByteBuffer.allocateDirect(outputCapacity).order(ByteOrder.LITTLE_ENDIAN)

        while (source.remaining() >= pairSizeBytes) {
            val left1 = source.short.toInt()
            val right1 = source.short.toInt()
            val left2 = source.short.toInt()
            val right2 = source.short.toInt()

            val s1 = mixStereoSample(left1, right1, mode)
            val s2 = mixStereoSample(left2, right2, mode)
            val mixed = (s1 + s2) / 2
            output.putShort(mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }

        output.flip()
        return output
    }

    // Lenovo Smart Clock 2: mic only works at 48 kHz stereo.
    // Downsample 3:1 and downmix stereo to mono for the 16 kHz pipeline.
    private fun stereo48kToMono16k(input: ByteBuffer, mode: StereoDownmixMode): ByteBuffer {
        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val groupBytes = 12
        val outputCapacity = (source.remaining() / groupBytes) * 2
        val output = ByteBuffer.allocateDirect(outputCapacity).order(ByteOrder.LITTLE_ENDIAN)

        while (source.remaining() >= groupBytes) {
            val l0 = source.short.toInt()
            val r0 = source.short.toInt()
            val l1 = source.short.toInt()
            val r1 = source.short.toInt()
            val l2 = source.short.toInt()
            val r2 = source.short.toInt()

            val s0 = mixStereoSample(l0, r0, mode)
            val s1 = mixStereoSample(l1, r1, mode)
            val s2 = mixStereoSample(l2, r2, mode)
            val mixed = (s0 + s1 + s2) / 3
            output.putShort(mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }

        output.flip()
        return output
    }
}
