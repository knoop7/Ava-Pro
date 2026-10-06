package com.example.ava.voiceprint

import com.example.ava.audio.DeviceAudioProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ensures wake-window PCM is always 16 kHz mono before entering the voiceprint ring buffer. */
object VoicePrintPcmCapture {
    private const val TARGET_RATE_HZ = 16_000

    fun writeToRingBuffer(ringBuffer: VoicePrintRingBuffer, pcm: ByteBuffer, sourceRateHz: Int) {
        ringBuffer.write(normalizeTo16kMono(pcm, sourceRateHz))
    }

    fun normalizeTo16kMono(pcm: ByteBuffer, sourceRateHz: Int): ByteBuffer {
        if (sourceRateHz == TARGET_RATE_HZ) {
            return pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        }
        return resampleMono16Le(pcm, sourceRateHz, TARGET_RATE_HZ)
    }

    /** Effective mono PCM rate after [DeviceAudioProfile] capture processing (wake-word path). */
    fun pcmRateAfterCaptureProcessing(profile: DeviceAudioProfile): Int {
        if (!profile.requiresProcessing) return profile.captureSampleRateInHz
        if (profile.captureChannelCount == 2 &&
            profile.captureSampleRateInHz == 32_000 &&
            profile.outputSampleRateInHz == TARGET_RATE_HZ
        ) {
            return TARGET_RATE_HZ
        }
        if (profile.captureChannelCount == 2 &&
            profile.captureSampleRateInHz == 48_000 &&
            profile.outputSampleRateInHz == TARGET_RATE_HZ
        ) {
            return TARGET_RATE_HZ
        }
        return profile.captureSampleRateInHz
    }

    private fun resampleMono16Le(input: ByteBuffer, sourceRateHz: Int, targetRateHz: Int): ByteBuffer {
        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = source.remaining() / 2
        if (sampleCount <= 0 || sourceRateHz <= 0 || targetRateHz <= 0) {
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
        }

        val outCount = (sampleCount.toLong() * targetRateHz / sourceRateHz).toInt().coerceAtLeast(1)
        val output = ByteBuffer.allocateDirect(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        val ratio = sourceRateHz.toDouble() / targetRateHz.toDouble()

        for (i in 0 until outCount) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceIn(0, sampleCount - 1)
            val frac = (srcPos - idx).toFloat()
            val s0 = readSample(source, idx)
            val s1 = readSample(source, (idx + 1).coerceAtMost(sampleCount - 1))
            val mixed = (s0 + (s1 - s0) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(mixed.toShort())
        }
        output.flip()
        return output
    }

    private fun readSample(buffer: ByteBuffer, index: Int): Int {
        return buffer.getShort(index * 2).toInt()
    }
}
