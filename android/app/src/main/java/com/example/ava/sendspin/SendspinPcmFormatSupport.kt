package com.example.ava.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

object SendspinPcmFormatSupport {
    private val playbackAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    fun buildPlaybackFormat(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): AudioFormat? {
        return try {
            val safeRate = sampleRate.coerceAtLeast(4000)
            val channelMask =
                when (channels) {
                    1 -> AudioFormat.CHANNEL_OUT_MONO
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    else -> return null
                }
            val encoding =
                when (bitDepth) {
                    16 -> AudioFormat.ENCODING_PCM_16BIT
                    24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    32 -> AudioFormat.ENCODING_PCM_32BIT
                    else -> return null
                }
            AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(safeRate)
                .setChannelMask(channelMask)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    private fun isStructurallyValid(format: AudioFormat): Boolean {
        if (format.encoding == AudioFormat.ENCODING_INVALID) return false
        if (format.sampleRate <= 0) return false
        return when (format.channelMask) {
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_OUT_STEREO -> true
            else -> false
        }
    }

    fun isPlaybackFormatSupported(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): Boolean {
        val format = buildPlaybackFormat(sampleRate, channels, bitDepth) ?: return false
        if (!isStructurallyValid(format)) return false
        return probePlaybackInitialization(format, channels, bitDepth)
    }

    private fun probePlaybackInitialization(
        format: AudioFormat,
        channels: Int,
        bitDepth: Int,
    ): Boolean {
        val bytesPerFrame = channels * (bitDepth / 8)
        if (bytesPerFrame <= 0) return false

        val safeMinProbeBytes = (format.sampleRate * 0.10 * bytesPerFrame).toInt()
        val probeBufferBytes = maxOf(safeMinProbeBytes, bytesPerFrame * 1024)

        return try {
            val probeTrack =
                AudioTrack(
                    playbackAttributes,
                    format,
                    probeBufferBytes,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
            val ok = probeTrack.state == AudioTrack.STATE_INITIALIZED
            try {
                probeTrack.release()
            } catch (_: Exception) {
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }
}
