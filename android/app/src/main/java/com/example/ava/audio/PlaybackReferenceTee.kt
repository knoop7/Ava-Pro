package com.example.ava.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps ExoPlayer's audio sink PCM into [PlaybackReferenceBus] as a far-end
 * reference for software AEC. Near-zero overhead when the bus is inactive.
 *
 * Accepts both 16-bit integer and 32-bit float PCM from the audio sink so taps
 * survive whatever encoding ExoPlayer's `DefaultAudioSink` ends up writing to
 * `AudioTrack` (notably HA media playback which may negotiate float output and
 * silently break the AEC reference if we only accepted PCM 16-bit).
 */
@UnstableApi
class PlaybackReferenceTee(
    /**
     * Player app-volume at the moment a buffer is teed. ExoPlayer applies its volume
     * via `AudioTrack.setVolume` *after* the audio-processor chain, so the tee sees
     * full-scale PCM regardless of the actual speaker level. Without compensation the
     * reference amplitude jumps relative to the real echo on every duck/unduck ramp,
     * forcing the AEC filter to re-converge exactly when TTS overlaps music. `null`
     * keeps the legacy unity behaviour.
     */
    private val volumeProvider: (() -> Float)? = null,
) : TeeAudioProcessor.AudioBufferSink {
    private var writer: PlaybackReferenceBus.Writer? = null
    private var isFloat = false

    /** Reusable scratch for volume-scaled copies; grown on demand, no steady-state GC. */
    private var scaled: ByteBuffer = ByteBuffer.allocate(0)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        writer = when {
            sampleRateHz <= 0 || channelCount <= 0 -> null
            encoding == C.ENCODING_PCM_16BIT ->
                PlaybackReferenceBus.createWriter(sampleRateHz, channelCount)
            encoding == C.ENCODING_PCM_FLOAT ->
                PlaybackReferenceBus.createFloatWriter(sampleRateHz, channelCount)
            else -> {
                Log.w(TAG, "Unsupported encoding $encoding for AEC reference, skipping stream")
                null
            }
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val writer = writer ?: return
        val gain = refGain()
        if (gain >= 0.999f) {
            writer.write(buffer)
            return
        }
        writer.write(scaledCopy(buffer, gain))
    }

    /**
     * Effective reference gain. When a [volumeProvider] is wired (ExoPlayer path), the
     * tee sees full-scale PCM while the speaker level is `player.volume` applied
     * downstream — scale by that, then shave a little headroom so the reference does
     * not sit hotter than the real echo (over-hot reference weakens cancellation).
     * No provider → unity, preserving legacy behaviour.
     */
    private fun refGain(): Float {
        val volume = volumeProvider?.invoke() ?: return 1f
        if (!volume.isFinite()) return 1f
        return (volume.coerceIn(0f, 1f) * REFERENCE_HEADROOM).coerceIn(0f, 1f)
    }

    private fun scaledCopy(buffer: ByteBuffer, gain: Float): ByteBuffer {
        // Match the byte-order conventions PlaybackReferenceBus.Writer expects:
        // native order for float PCM, little-endian for 16-bit integer PCM.
        val order = if (isFloat) ByteOrder.nativeOrder() else ByteOrder.LITTLE_ENDIAN
        val src = buffer.duplicate().order(order)
        if (scaled.capacity() < src.remaining()) {
            scaled = ByteBuffer.allocate(src.remaining())
        }
        scaled.clear()
        scaled.order(order)
        if (isFloat) {
            while (src.remaining() >= 4) {
                scaled.putFloat(src.float * gain)
            }
        } else {
            while (src.remaining() >= 2) {
                val sample = (src.short * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                scaled.putShort(sample.toShort())
            }
        }
        scaled.flip()
        return scaled
    }

    companion object {
        private const val TAG = "PlaybackReferenceTee"
        /** ~0.6 dB below unity — keeps the far-end reference slightly under actual echo level. */
        private const val REFERENCE_HEADROOM = 0.93f
    }
}
