package com.example.ava.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Loops a call ringtone (asset or content URI) for up to [maxDurationMs], then invokes [onTimeout].
 */
internal class AvaVoiceCallRingtonePlayer(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private var mediaPlayer: MediaPlayer? = null
    private var timeoutRunnable: Runnable? = null

    fun start(
        uriString: String,
        maxDurationMs: Long = AvaVoiceProtocol.CALL_RING_TIMEOUT_MS,
        volume: Float = 1f,
        usage: Int = AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        onTimeout: () -> Unit = {}
    ) {
        stop()
        if (uriString.isBlank()) return
        val player = MediaPlayer()
        try {
            when {
                uriString.startsWith("asset://") -> {
                    val assetPath = uriString.removePrefix("asset://").trimStart('/')
                    context.assets.openFd(assetPath).use { afd ->
                        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                }
                else -> player.setDataSource(context, Uri.parse(uriString))
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true
            player.prepare()
            val level = volume.coerceIn(0f, 1f)
            player.setVolume(level, level)
            player.start()
            mediaPlayer = player
            timeoutRunnable = Runnable {
                stop()
                onTimeout()
            }
            handler.postDelayed(timeoutRunnable!!, maxDurationMs)
        } catch (e: Exception) {
            Log.w(TAG, "ringtone start failed uri=$uriString: ${e.message}")
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        val player = mediaPlayer
        mediaPlayer = null
        if (player == null) return
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AvaVoiceCallRing"
    }
}
