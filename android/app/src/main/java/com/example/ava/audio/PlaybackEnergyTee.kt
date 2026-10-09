package com.example.ava.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Taps ExoPlayer PCM for visual energy (TTS player only).
 */
@UnstableApi
class PlaybackEnergyTee : TeeAudioProcessor.AudioBufferSink {
    private var encoding = C.ENCODING_INVALID
    private var sampleRateHz = 0
    private var channelCount = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!PlaybackEnergyMonitor.isEnabled()) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        // Buffer length keeps a prefill burst back to back on the audible timeline.
        val bytesPerSecond = sampleRateHz.toLong() * channelCount * bytesPerSample
        val durationMs = if (bytesPerSecond > 0L) buffer.remaining() * 1000L / bytesPerSecond else 0L
        val level = when (encoding) {
            C.ENCODING_PCM_16BIT -> AudioEnergy.rmsLevelPlayback(buffer)
            else -> rmsLevelFloat(buffer)
        }
        PlaybackEnergyMonitor.onLevel(
            level,
            PlaybackEnergyMonitor.URL_SINK_DELAY_MS,
            durationMs,
            PlaybackEnergyMonitor.generation(),
        )
    }

    private fun rmsLevelFloat(buffer: ByteBuffer): Float {
        val dup = buffer.duplicate().order(ByteOrder.nativeOrder())
        if (dup.remaining() < 4) return 0f
        var sumSquares = 0.0
        var sampleCount = 0
        while (dup.remaining() >= 4) {
            val sample = dup.float
            sumSquares += sample * sample
            sampleCount++
        }
        val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
        return (rms / 0.22).toFloat().coerceIn(0f, 1f)
    }
}
