package com.example.ava.players

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * Home Assistant TTS is served from `/api/tts_proxy/{token}.mp3` no matter what
 * the body is. Piper and others send `audio/wav` under that name. Trusting the
 * suffix picks an MP3 extractor, the sniff fails, and the player goes idle
 * before any sample. The response Content-Type is the container.
 */
@UnstableApi
class ContainerExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> {
        val hinted = uriForContentType(responseHeaders) ?: uri
        return delegate.createExtractors(hinted, responseHeaders)
    }

    private fun uriForContentType(headers: Map<String, List<String>>): Uri? {
        val raw = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return null
        val extension = when (raw) {
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/mpeg", "audio/mp3", "audio/mpeg3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/webm" -> "webm"
            "audio/amr", "audio/amr-wb" -> "amr"
            else -> return null
        }
        return Uri.parse("https://media.local/stream.$extension")
    }
}

/**
 * A retry of `/api/esphome/ffmpeg_proxy/` is a new GET. That view kills the
 * running ffmpeg and starts the source again at byte 0, so the song loops.
 *
 * `/api/tts_proxy/` streams have no length and no duration, so ExoPlayer treats
 * them as live and restarts a retried load at byte 0. When HA leaves the stream
 * open after the last chunk, the read timeout retries and the whole reply plays
 * again (every timeout, until the token 404s). Once bytes have arrived the clip
 * is over: fail instead of retrying. A failure before any byte still retries.
 */
@UnstableApi
class FfmpegProxyLoadPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val uri = loadErrorInfo.loadEventInfo.uri.toString()
        if (uri.contains("/api/esphome/ffmpeg_proxy/")) return C.TIME_UNSET
        if (uri.contains(TTS_PROXY_PATH) && loadErrorInfo.loadEventInfo.bytesLoaded > 0L) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private companion object {
        const val TTS_PROXY_PATH = "/api/tts_proxy/"
    }
}
