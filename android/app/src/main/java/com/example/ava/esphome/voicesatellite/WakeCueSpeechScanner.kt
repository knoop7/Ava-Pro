package com.example.ava.esphome.voicesatellite

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.ava.audio.AudioFrameProcessor
import com.example.ava.microwakeword.WakePreRollSpeechDetector
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline check of a wake cue: decode it, downmix to 16 kHz mono and run the same
 * VAD that pre-roll rescue uses. A cue the VAD hears as speech must not enable
 * rescue, because its own echo would then be replayed to HA as the user's command.
 */
internal object WakeCueSpeechScanner {
    private const val TAG = "WakeCueSpeechScanner"
    private const val TARGET_RATE = 16_000
    /** Custom cues can be long ringtones; the earcon hold is capped at 2.5 s anyway. */
    private const val MAX_SCAN_MS = 6_000
    private const val CODEC_TIMEOUT_US = 20_000L

    /**
     * @return true when no speech was found, false when the VAD fired, null when the
     *   cue could not be decoded or the VAD is unavailable.
     */
    fun isSpeechFree(context: Context, uri: String, loader: WakePreRollSpeechDetector.Loader): Boolean? {
        val pcm = try {
            decodeMono16k(context, uri)
        } catch (error: Exception) {
            Log.w(TAG, "cannot decode wake cue $uri", error)
            return null
        } ?: return null
        val session = loader.newSession() ?: return null
        return try {
            session.push(pcm) == null
        } catch (error: Exception) {
            Log.w(TAG, "VAD failed on wake cue $uri", error)
            null
        } catch (error: LinkageError) {
            Log.w(TAG, "VAD runtime unavailable for wake cue $uri", error)
            null
        } finally {
            session.close()
        }
    }

    private fun decodeMono16k(context: Context, uri: String): ByteArray? {
        val extractor = MediaExtractor()
        try {
            if (uri.startsWith("asset:///")) {
                context.assets.openFd(uri.removePrefix("asset:///")).use { afd ->
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } else {
                extractor.setDataSource(context, Uri.parse(uri), null)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, format)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, trackFormat: MediaFormat): ByteArray? {
        var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = pcmEncoding(trackFormat)
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleAfterEos = 0
        var maxBytes = maxBytes(sampleRate, channels, encoding)
        while (out.size() < maxBytes) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: return null
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = pcmEncoding(format)
                    maxBytes = maxBytes(sampleRate, channels, encoding)
                }
                // Decoders flush their last buffers a few polls after EOS; give up only
                // once the drain has been silent for ~1 s.
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone && ++idleAfterEos > 50) break
                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        out.write(bytes)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        if (out.size() == 0) return null
        return toMono16k(out.toByteArray(), sampleRate, channels, encoding)
    }

    /** MediaFormat.KEY_PCM_ENCODING is API 24; older decoders omit the key and emit 16-bit. */
    private const val KEY_PCM_ENCODING = "pcm-encoding"

    private fun pcmEncoding(format: MediaFormat): Int =
        if (format.containsKey(KEY_PCM_ENCODING)) format.getInteger(KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT

    private fun maxBytes(sampleRate: Int, channels: Int, encoding: Int): Int {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        return sampleRate / 1000 * MAX_SCAN_MS * channels.coerceAtLeast(1) * bytesPerSample
    }

    private fun toMono16k(raw: ByteArray, sampleRate: Int, channels: Int, encoding: Int): ByteArray? {
        if (sampleRate <= 0 || channels <= 0) return null
        val source = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val frames = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> raw.size / (4 * channels)
            AudioFormat.ENCODING_PCM_8BIT -> raw.size / channels
            else -> raw.size / (2 * channels)
        }
        if (frames == 0) return null
        val mono = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            var sum = 0
            repeat(channels) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> (source.float.coerceIn(-1f, 1f) * 32767f).toInt()
                    AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xFF) - 128) shl 8
                    else -> source.short.toInt()
                }
            }
            mono.putShort((sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        mono.flip()
        val resampled = AudioFrameProcessor.resampleMono(mono, sampleRate, TARGET_RATE)
        val result = ByteArray(resampled.remaining())
        resampled.get(result)
        return result
    }
}
