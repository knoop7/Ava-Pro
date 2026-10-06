package com.example.ava.audio

import android.util.Log
import com.example.microfeatures.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming WebRTC noise suppression for the Voice Satellite capture loop.
 *
 * Accepts arbitrary-length 16 kHz mono 16-bit LE PCM, slices into 10 ms frames
 * ([NoiseSuppressor.FRAME_SIZE]), and carries sub-frame remainders, so the returned buffer holds
 * whole frames and can be shorter (or empty) than the input. No delay line — unlike the AvaVoice
 * call path which intentionally buffers ~300 ms.
 */
class SoftwareNsProcessor(
    mode: Int = NoiseSuppressor.MODE_MILD,
) : AutoCloseable {
    private val ns: NoiseSuppressor? = runCatching {
        NoiseSuppressor(sampleRate = 16000, mode = mode)
    }
        .onFailure { Log.e(TAG, "Failed to init software NS, passing through", it) }
        .getOrNull()
        ?.let { created ->
            if (created.isReady) {
                created
            } else {
                Log.e(TAG, "Software NS native init failed, passing through")
                created.close()
                null
            }
        }

    private val frameSize = NoiseSuppressor.FRAME_SIZE
    private val inFrame = ShortArray(frameSize)
    private val outFrame = ShortArray(frameSize)
    private val carry = ShortArray(frameSize)
    private var carryLen = 0
    private var output = ByteBuffer.allocateDirect(frameSize * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val empty = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

    val isActive: Boolean get() = ns != null

    /**
     * Input must be 16 kHz mono 16-bit LE PCM. Returns whole suppressed frames only; a sub-frame
     * tail is held for the next call, so output lags input by under one frame.
     *
     * Every returned sample has been through the suppressor. Emitting the sub-frame tail raw (to
     * keep the sample count identical) spliced untreated audio between suppressed frames, which
     * was audible as a periodic click at the mic read rate.
     */
    fun process(input: ByteBuffer): ByteBuffer {
        val engine = ns ?: return rewindCopy(input)
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val inputSamples = src.remaining() / 2
        if (inputSamples <= 0) return rewindCopy(input)

        val totalSamples = carryLen + inputSamples
        val frames = totalSamples / frameSize

        if (frames == 0) {
            while (src.remaining() >= 2) {
                carry[carryLen++] = src.short
            }
            return empty
        }

        val outputSamples = frames * frameSize
        if (output.capacity() < outputSamples * 2) {
            output = ByteBuffer.allocateDirect(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        output.clear()

        repeat(frames) {
            var filled = 0
            if (carryLen > 0) {
                carry.copyInto(inFrame, 0, 0, carryLen)
                filled = carryLen
                carryLen = 0
            }
            while (filled < frameSize) {
                inFrame[filled++] = src.short
            }
            engine.process(inFrame, outFrame)
            for (i in 0 until frameSize) {
                output.putShort(outFrame[i])
            }
        }

        while (src.remaining() >= 2) {
            carry[carryLen++] = src.short
        }

        output.flip()
        return output
    }

    private fun rewindCopy(input: ByteBuffer): ByteBuffer {
        return input.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            rewind()
        }
    }

    fun setMode(mode: Int) {
        ns?.setMode(mode)
    }

    fun reset() {
        carryLen = 0
    }

    override fun close() {
        ns?.close()
    }

    companion object {
        private const val TAG = "SoftwareNsProcessor"
    }
}
