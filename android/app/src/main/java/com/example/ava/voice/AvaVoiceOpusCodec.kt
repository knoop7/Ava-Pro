package com.example.ava.voice

import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus codec wrappers used **only** by the live voice-call path.
 *
 * These are intentionally separate from [com.example.ava.sendspin.SendspinOpusDecoder] (the media
 * player pipeline): call frames are tiny fixed 20ms voice packets, so we use the pure-Java Concentus
 * codec directly without the MediaCodec / OpusHead csd machinery. One captured PCM frame maps to one
 * Opus packet and back. All operations are best-effort: any failure yields an empty byte array which
 * the caller treats as a soft dropout, never a crash.
 *
 * Each instance is single-threaded (one per outbound/inbound session, driven by one coroutine), so
 * the reused scratch buffers are safe. PCM is interleaved 16-bit little-endian, matching AudioRecord/
 * AudioTrack on Android and the rest of the voice pipeline.
 */
internal class AvaVoiceOpusEncoder(
    sampleRate: Int = AvaVoiceAudioConfig.SAMPLE_RATE,
    private val channels: Int = AvaVoiceAudioConfig.CHANNELS
) {
    private val out = ByteArray(MAX_PACKET_BYTES)
    private val encoder: OpusEncoder? = runCatching {
        OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            bitrate = TARGET_BITRATE
            complexity = COMPLEXITY
            useInbandFEC = true
            packetLossPercent = EXPECTED_PACKET_LOSS_PCT
        }
    }.onFailure { Log.w(TAG, "encoder init failed: ${it.message}") }.getOrNull()

    val isReady: Boolean get() = encoder != null

    private val frameSamplesPerChannel = sampleRate * AvaVoiceAudioConfig.FRAME_MS / 1000

    /** Encode one interleaved 16-bit LE PCM frame to an Opus packet. Empty on failure. */
    fun encode(pcm: ByteArray): ByteArray {
        val enc = encoder ?: return EMPTY
        val sampleCount = pcm.size / 2
        if (sampleCount != frameSamplesPerChannel * channels) return EMPTY
        val shorts = ShortArray(sampleCount)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val frameSamplesPerChannel = sampleCount / channels
        return try {
            val len = enc.encode(shorts, 0, frameSamplesPerChannel, out, 0, out.size)
            if (len > 0) out.copyOf(len) else EMPTY
        } catch (t: Throwable) {
            Log.w(TAG, "encode failed: ${t.message}")
            EMPTY
        }
    }

    companion object {
        private const val TAG = "AvaVoiceOpusEncoder"
        private const val TARGET_BITRATE = 32_000
        private const val COMPLEXITY = 4
        private const val EXPECTED_PACKET_LOSS_PCT = 10
        // One Opus frame never exceeds 1275 bytes; round up for safety.
        private const val MAX_PACKET_BYTES = 1_500
        private val EMPTY = ByteArray(0)
    }
}

internal class AvaVoiceOpusDecoder(
    private val sampleRate: Int = AvaVoiceAudioConfig.SAMPLE_RATE,
    private val channels: Int = AvaVoiceAudioConfig.CHANNELS
) {
    // Allow up to a 120ms frame so an oversized/erroneous packet can't overflow the output buffer.
    private val maxFrameSamples = sampleRate / 1000 * 120
    private val pcmShorts = ShortArray(maxFrameSamples * channels)
    private val decoder: OpusDecoder? = runCatching {
        OpusDecoder(sampleRate, channels)
    }.onFailure { Log.w(TAG, "decoder init failed: ${it.message}") }.getOrNull()

    val isReady: Boolean get() = decoder != null

    private val frameSamplesPerChannel = sampleRate * AvaVoiceAudioConfig.FRAME_MS / 1000

    /** Decode one Opus packet to interleaved 16-bit LE PCM. Empty on failure. */
    fun decode(opus: ByteArray): ByteArray {
        val dec = decoder ?: return EMPTY
        if (opus.isEmpty()) return EMPTY
        return try {
            // decode_fec=false: decode the primary frame only (fec=true is for PLC / FEC sideband).
            val samples = dec.decode(opus, 0, opus.size, pcmShorts, 0, maxFrameSamples, false)
            if (samples <= 0) return EMPTY
            val totalSamples = samples * channels
            val frameSamples = frameSamplesPerChannel * channels
            if (frameSamples > 0 && totalSamples % frameSamples != 0) {
                Log.w(TAG, "decode not frame-aligned samples=$totalSamples frame=$frameSamples")
            }
            val bytes = ByteArray(totalSamples * 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                buffer.putShort(pcmShorts[i])
            }
            bytes
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            EMPTY
        }
    }

    /** Split decoded PCM into fixed 20ms chunks for [AvaVoiceStreamPlayer]. */
    fun splitIntoFrames(pcm: ByteArray): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val frameBytes = AvaVoiceAudioConfig.FRAME_BYTES * channels
        if (frameBytes <= 0) return listOf(pcm)
        val frames = ArrayList<ByteArray>((pcm.size + frameBytes - 1) / frameBytes)
        var offset = 0
        while (offset + frameBytes <= pcm.size) {
            frames.add(pcm.copyOfRange(offset, offset + frameBytes))
            offset += frameBytes
        }
        if (offset < pcm.size) {
            val tail = ByteArray(frameBytes)
            System.arraycopy(pcm, offset, tail, 0, pcm.size - offset)
            frames.add(tail)
        }
        return frames
    }

    companion object {
        private const val TAG = "AvaVoiceOpusDecoder"
        private val EMPTY = ByteArray(0)
    }
}
