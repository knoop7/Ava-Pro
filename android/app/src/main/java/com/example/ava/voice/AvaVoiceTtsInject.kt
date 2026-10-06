package com.example.ava.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.example.ava.audio.AudioFrameProcessor
import com.example.ava.homeassistant.HaManager
import com.example.ava.utils.HaMediaUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speak [text] with the house TTS engine and inject the PCM into the existing
 * LAN voice-message send path. No microphone, no overlay.
 *
 * House TTS ends on the last phoneme. The receive path then drops about a
 * second — UDP tail, decoder EOS, or AudioTrack.stop(). Hold-to-talk already
 * has that air from the button; inject appends one second of silence so the
 * cut lands on quiet, not the last word.
 */
object AvaVoiceTtsInject {
    private const val TAG = "AvaVoiceTts"
    private const val TARGET_RATE = AvaVoiceAudioConfig.SAMPLE_RATE
    private const val MAX_MS = AvaVoiceProtocol.MAX_VOICE_MS.toInt()
    private const val CODEC_TIMEOUT_US = 20_000L
    private const val KEY_PCM_ENCODING = "pcm-encoding"
    internal const val TRAILING_SILENCE_MS = 1_000

    data class Outcome(
        val ok: Boolean,
        val error: String? = null,
        val bytes: Int = 0,
        val durationMs: Long = 0,
        val reached: List<AvaVoiceDevice> = emptyList(),
    )

    suspend fun send(
        context: Context,
        text: String,
        targets: List<AvaVoiceDevice>,
        delayMinutes: Int,
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            if (targets.isEmpty()) return@withContext Outcome(false, "no targets")
            val url = HaManager.get()?.synthesizeWithPipelineTts(text)
                ?: return@withContext Outcome(false, "tts did not return a clip")
            val resolved = HaMediaUrl.resolvePreferringSignedIn(url, null)
                ?: return@withContext Outcome(false, "tts url could not be resolved")
            val decoded = decodeUrl(context.applicationContext, resolved)
                ?: return@withContext Outcome(false, "tts clip could not be decoded")
            if (decoded.size < AvaVoiceAudioConfig.FRAME_BYTES) {
                return@withContext Outcome(false, "tts clip was empty")
            }
            val pcm = padTrailingSilence(decoded)
            val fromId = AvaVoiceDiscovery.localId().ifBlank {
                AvaVoiceDiscovery.resolveLocalDeviceId(context)
            }
            val fromName = AvaVoiceDiscovery.localName()
            val sent = AvaVoiceSessionHub.sendBufferedPcm(
                fromDeviceId = fromId,
                fromName = fromName,
                targets = targets,
                pcm = pcm,
                delayMinutes = delayMinutes,
            ) ?: return@withContext Outcome(false, "clip did not leave this device")
            Log.d(TAG, "injected ${sent.bytes} bytes to ${sent.reached.joinToString { it.name }}")
            Outcome(
                ok = true,
                bytes = sent.bytes,
                durationMs = sent.durationMs,
                reached = sent.reached,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "inject failed: ${error.message}")
            Outcome(false, error.message ?: "send failed")
        }
    }

    private fun decodeUrl(context: Context, url: String): ByteArray? {
        val scratch = File(context.cacheDir, "ava_voice_tts_${System.nanoTime()}.bin")
        return try {
            download(url, scratch)
            decodeFile(scratch)
        } catch (error: Exception) {
            Log.w(TAG, "download/decode failed: ${error.message}")
            null
        } finally {
            scratch.delete()
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        HaMediaUrl.applyBearer(conn, url)
        conn.getInputStream().use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        if (dest.length() <= 0L) throw IllegalStateException("empty tts download")
    }

    private fun decodeFile(file: File): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
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

    private fun pcmEncoding(format: MediaFormat): Int =
        if (format.containsKey(KEY_PCM_ENCODING)) format.getInteger(KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT

    private fun maxBytes(sampleRate: Int, channels: Int, encoding: Int): Int {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        return sampleRate / 1000 * MAX_MS * channels.coerceAtLeast(1) * bytesPerSample
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

    /** 16 kHz mono 16-bit zeros, frame-aligned at [TRAILING_SILENCE_MS]. */
    internal fun padTrailingSilence(pcm: ByteArray, silenceMs: Int = TRAILING_SILENCE_MS): ByteArray {
        val frames = silenceMs / AvaVoiceAudioConfig.FRAME_MS
        val padBytes = frames * AvaVoiceAudioConfig.FRAME_BYTES
        if (pcm.isEmpty() || padBytes <= 0) return pcm
        return pcm + ByteArray(padBytes)
    }
}
