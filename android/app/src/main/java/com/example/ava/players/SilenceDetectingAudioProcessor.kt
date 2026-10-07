package com.example.ava.players

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio processor that detects silence between sentences.
 *
 * TTS always pauses between sentences (usually 150–400 ms).
 * Watch amplitude in real time; when it stays under the threshold
 * long enough, that gap is a sentence boundary.
 *
 * More accurate than estimating from character weights, because it
 * measures the audio itself.
 */
@UnstableApi
class SilenceDetectingAudioProcessor : AudioProcessor {
    
    companion object {
        private const val TAG = "SilenceDetector"
        


        private const val SILENCE_THRESHOLD = 500
        

        private const val MIN_SILENCE_DURATION_MS = 150L
        
        private const val MIN_SPEECH_DURATION_MS = 100L
    }
    
    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    private var buffer = EMPTY_BUFFER
    private var inputEnded = false
    
    private var isInSilence = true
    private var silenceStartTimeMs = 0L
    private var speechStartTimeMs = 0L
    private var totalSamplesProcessed = 0L
    private var sampleRate = 44100
    
    var onSentenceBoundaryDetected: ((sentenceIndex: Int, timeMs: Long) -> Unit)? = null
    private var detectedSentenceCount = 0
    

    var onPlaybackPositionUpdate: ((currentMs: Long) -> Unit)? = null
    private var lastPositionUpdateMs = 0L
    
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioFormat.NOT_SET
        }
        
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        this.sampleRate = inputAudioFormat.sampleRate
        
        Log.d(TAG, "Configured: sampleRate=$sampleRate, channels=${inputAudioFormat.channelCount}")
        return outputAudioFormat
    }
    
    override fun isActive(): Boolean {
        return inputAudioFormat != AudioFormat.NOT_SET
    }
    
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        
        analyzeAudio(inputBuffer.duplicate())
        
        val size = inputBuffer.remaining()
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        buffer.clear()
        buffer.put(inputBuffer)
        buffer.flip()
    }
    
    private fun analyzeAudio(data: ByteBuffer) {
        data.order(ByteOrder.LITTLE_ENDIAN)
        
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val bytesPerSample = 2 * channelCount
        val sampleCount = data.remaining() / bytesPerSample
        
        if (sampleCount == 0) return
        

        var sumSquares = 0.0
        var sampleIndex = 0
        
        while (data.remaining() >= 2) {
            val sample = data.short.toInt()
            sumSquares += sample * sample
            sampleIndex++
        }
        
        val rms = if (sampleIndex > 0) kotlin.math.sqrt(sumSquares / sampleIndex) else 0.0
        val currentTimeMs = (totalSamplesProcessed * 1000L) / sampleRate
        
        totalSamplesProcessed += sampleCount
        

        if (currentTimeMs - lastPositionUpdateMs >= 100) {
            lastPositionUpdateMs = currentTimeMs
            onPlaybackPositionUpdate?.invoke(currentTimeMs)
        }
        
        val isSilent = rms < SILENCE_THRESHOLD
        
        if (isSilent) {
            if (!isInSilence) {
                val speechDuration = currentTimeMs - speechStartTimeMs
                if (speechDuration >= MIN_SPEECH_DURATION_MS) {
                    silenceStartTimeMs = currentTimeMs
                    isInSilence = true
                }
            } else {
                val silenceDuration = currentTimeMs - silenceStartTimeMs
                if (silenceDuration >= MIN_SILENCE_DURATION_MS && silenceStartTimeMs > 0) {
                    detectedSentenceCount++
                    Log.d(TAG, "Sentence boundary #$detectedSentenceCount at ${currentTimeMs}ms (silence ${silenceDuration}ms)")
                    onSentenceBoundaryDetected?.invoke(detectedSentenceCount, currentTimeMs)
                    silenceStartTimeMs = currentTimeMs + MIN_SILENCE_DURATION_MS * 2
                }
            }
        } else {
            if (isInSilence) {
                speechStartTimeMs = currentTimeMs
                isInSilence = false
            }
        }
    }
    
    override fun queueEndOfStream() {
        inputEnded = true
        val finalTimeMs = (totalSamplesProcessed * 1000L) / sampleRate
        Log.d(TAG, "End of stream at ${finalTimeMs}ms, total sentences: $detectedSentenceCount")
    }
    
    override fun getOutput(): ByteBuffer {
        val output = buffer
        buffer = EMPTY_BUFFER
        return output
    }
    
    override fun isEnded(): Boolean {
        return inputEnded && buffer === EMPTY_BUFFER
    }
    
    override fun flush() {
        buffer = EMPTY_BUFFER
        inputEnded = false
    }
    
    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        totalSamplesProcessed = 0
        detectedSentenceCount = 0
        isInSilence = true
        silenceStartTimeMs = 0
        speechStartTimeMs = 0
        lastPositionUpdateMs = 0
    }
    
    fun resetDetection() {
        totalSamplesProcessed = 0
        detectedSentenceCount = 0
        isInSilence = true
        silenceStartTimeMs = 0
        speechStartTimeMs = 0
        lastPositionUpdateMs = 0
    }
}

private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
