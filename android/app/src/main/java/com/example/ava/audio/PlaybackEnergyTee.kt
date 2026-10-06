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

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!PlaybackEnergyMonitor.isEnabled()) return
        val level = when (encoding) {
            C.ENCODING_PCM_16BIT -> AudioEnergy.rmsLevelPlayback(buffer)
            C.ENCODING_PCM_FLOAT -> rmsLevelFloat(buffer)
            else -> return
        }
        PlaybackEnergyMonitor.onLevel(level)
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
