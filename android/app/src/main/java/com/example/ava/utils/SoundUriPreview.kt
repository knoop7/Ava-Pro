package com.example.ava.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

object SoundUriPreview {
    class Handle(private val player: MediaPlayer) {
        fun stop() {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    fun play(context: Context, uriString: String): Handle? {
        if (uriString.isBlank()) return null
        val player = MediaPlayer()
        return try {
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
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setOnCompletionListener { completed ->
                try {
                    completed.release()
                } catch (_: Exception) {
                }
            }
            player.prepare()
            player.start()
            Handle(player)
        } catch (e: Exception) {
            Log.w(TAG, "play failed uri=$uriString: ${e.message}")
            try {
                player.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private const val TAG = "SoundUriPreview"
}
