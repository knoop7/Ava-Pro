package com.example.ava.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] that applies [MusicEqEngine] for HA music ([MusicEqSource.HA]).
 * Place before AEC tee so the reference matches the speaker tone.
 */
@UnstableApi
class EqualizerAudioProcessor(
    private val source: MusicEqSource = MusicEqSource.HA,
) : AudioProcessor {
    private val engine = MusicEqEngine(source)
    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        engine.reset()
        return outputAudioFormat
    }

    override fun isActive(): Boolean = inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val gains = MusicEqRuntime.get(source)
        if (gains.isBypassed()) {
            copyThrough(inputBuffer)
            return
        }
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer)
            else -> processPcm16(inputBuffer)
        }
    }

    private fun copyThrough(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        ensureBuffer(size)
        buffer.clear()
        buffer.put(inputBuffer)
        buffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val sampleCount = size / 2
        if (sampleCount <= 0) return
        val ordered = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(sampleCount)
        var i = 0
        while (ordered.remaining() >= 2) {
            floats[i++] = ordered.short / 32768f
        }
        engine.processInterleaved(
            floats,
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount.coerceAtLeast(1),
        )
        ensureBuffer(size)
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in floats) {
            val clipped = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(clipped.toShort())
        }
        buffer.flip()
    }

    private fun processFloat(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val sampleCount = size / 4
        if (sampleCount <= 0) return
        val ordered = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(sampleCount)
        var i = 0
        while (ordered.remaining() >= 4) {
            floats[i++] = ordered.float
        }
        engine.processInterleaved(
            floats,
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount.coerceAtLeast(1),
        )
        ensureBuffer(size)
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in floats) {
            buffer.putFloat(sample)
        }
        buffer.flip()
    }

    private fun ensureBuffer(size: Int) {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = buffer
        buffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && buffer === EMPTY_BUFFER

    override fun flush() {
        buffer = EMPTY_BUFFER
        inputEnded = false
        engine.reset()
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
