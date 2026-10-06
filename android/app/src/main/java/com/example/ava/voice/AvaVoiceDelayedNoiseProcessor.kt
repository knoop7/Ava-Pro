package com.example.ava.voice

import android.util.Log
import com.example.microfeatures.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Buffers [delayMs] of 20ms PCM frames, then runs mild RNNoise-style suppression on each
 * delayed frame before encode. Extra latency is intentional — trades ~300ms for cleaner call audio.
 */
internal class AvaVoiceDelayedNoiseProcessor(
    private val delayMs: Int = AvaVoiceAudioConfig.CALL_NOISE_DELAY_MS
) : AutoCloseable {
    private val frameBytes = AvaVoiceAudioConfig.FRAME_BYTES
    private val delayFrames = (delayMs / AvaVoiceAudioConfig.FRAME_MS).coerceAtLeast(1)
    private val queue = ArrayDeque<ByteArray>(delayFrames + 2)
    private val nativeNs = runCatching {
        NoiseSuppressor(
            sampleRate = AvaVoiceAudioConfig.SAMPLE_RATE,
            mode = NoiseSuppressor.MODE_MILD
        )
    }.onFailure { Log.w(TAG, "native NS init failed: ${it.message}") }.getOrNull()
    private val subFrameSamples = NoiseSuppressor.FRAME_SIZE
    private val subFrameBytes = subFrameSamples * AvaVoiceAudioConfig.BYTES_PER_SAMPLE
    private val inShorts = ShortArray(subFrameSamples)
    private val outShorts = ShortArray(subFrameSamples)

    /**
     * @return processed frame ready to send, or null while the delay line is filling (~[delayMs]).
     */
    fun push(frame: ByteArray): ByteArray? {
        if (frame.size != frameBytes) return null
        queue.addLast(frame.copyOf())
        if (queue.size <= delayFrames) return null
        return denoiseFrame(queue.removeFirst())
    }

    /** Drain remaining delayed frames when recording stops. */
    fun flushRemaining(): List<ByteArray> {
        val out = ArrayList<ByteArray>(queue.size)
        while (queue.isNotEmpty()) {
            out.add(denoiseFrame(queue.removeFirst()))
        }
        return out
    }

    private fun denoiseFrame(frame: ByteArray): ByteArray {
        val ns = nativeNs
        if (ns == null) return frame
        val processed = frame.copyOf()
        var offset = 0
        while (offset + subFrameBytes <= processed.size) {
            ByteBuffer.wrap(processed, offset, subFrameBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(inShorts)
            ns.process(inShorts, outShorts)
            ByteBuffer.wrap(processed, offset, subFrameBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(outShorts)
            offset += subFrameBytes
        }
        return processed
    }

    override fun close() {
        runCatching { nativeNs?.close() }
        queue.clear()
    }

    companion object {
        private const val TAG = "AvaVoiceNoiseDelay"
    }
}
