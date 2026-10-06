package com.example.ava.detection

import com.example.ava.voiceprint.VoicePrintPcmCapture
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TARGET_RATE_HZ = 16_000

/**
 * Linear PCM16 accumulator: collects 16 kHz mono samples until a 1 s inference window
 * is full, then emits a fresh direct buffer and keeps any overflow for the next window.
 */
class AudioEventWindowAccumulator(
    private val windowSamples: Int = 16_000,
) {
    private val pending = ShortArray(windowSamples)
    private var pendingCount = 0

    @Synchronized
    fun write(pcm: ByteBuffer, sourceRateHz: Int): List<ByteBuffer> {
        val input = if (sourceRateHz == TARGET_RATE_HZ) {
            pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        } else {
            VoicePrintPcmCapture.normalizeTo16kMono(pcm, sourceRateHz)
        }
        val windows = mutableListOf<ByteBuffer>()

        while (input.remaining() >= 2) {
            pending[pendingCount++] = input.short
            if (pendingCount >= windowSamples) {
                windows += copyPendingWindow()
                shiftPendingAfterEmit()
            }
        }
        return windows
    }

    @Synchronized
    fun reset() {
        pendingCount = 0
    }

    private fun copyPendingWindow(): ByteBuffer {
        val out = ByteBuffer.allocateDirect(windowSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until windowSamples) {
            out.putShort(pending[i])
        }
        out.flip()
        return out
    }

    private fun shiftPendingAfterEmit() {
        val overflow = pendingCount - windowSamples
        if (overflow > 0) {
            System.arraycopy(pending, windowSamples, pending, 0, overflow)
        }
        pendingCount = overflow
    }
}
