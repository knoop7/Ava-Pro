package com.example.ava.esphome.entities

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.ava.esphome.voicesatellite.VoiceSatellitePlayer
import com.example.ava.players.AudioPlayerState
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.MediaPlayerCommand
import com.example.esphomeproto.api.MediaPlayerCommandRequest
import com.example.esphomeproto.api.MediaPlayerState
import com.example.esphomeproto.api.listEntitiesMediaPlayerResponse
import com.example.esphomeproto.api.mediaPlayerStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

@OptIn(UnstableApi::class)
class MediaPlayerEntity(
    override val key: Int,
    val name: String,
    val objectId: String,
    val player: VoiceSatellitePlayer
) : Entity {

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesMediaPlayerResponse {
                key = this@MediaPlayerEntity.key
                name = this@MediaPlayerEntity.name
                objectId = this@MediaPlayerEntity.objectId
                supportsPause = true
                featureFlags = 1201677
                // No supported_formats. Listing any format makes HA rewrite every URL
                // through its one-shot ffmpeg proxy (no duration, restarts at byte 0).
                // The original response is decoded from its Content-Type, so a
                // tts_proxy URL named .mp3 still plays when the body is WAV.
            })

            is MediaPlayerCommandRequest -> {
                if (message.key == key) {
                    if (message.hasMediaUrl) {
                        val playUrl = player.resolvePlayUrl(message.mediaUrl) ?: message.mediaUrl
                        Log.d(TAG, "media_url announcement=${message.announcement} url=$playUrl")
                        if (message.announcement) {
                            player.playEsphomeAnnouncement(playUrl)
                        } else if (!player.mediaPlayer.isCurrentPlayback(playUrl)) {
                            player.mediaPlayer.play(playUrl)
                            player.onMediaPlay?.invoke(playUrl)
                        }
                    } else if (message.hasCommand) {
                        when (message.command) {
                            MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PAUSE -> {
                                player.mediaPlayer.pause()
                                player.onMediaPause?.invoke()
                            }
                            MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PLAY -> {
                                player.mediaPlayer.unpause()
                                player.onMediaResume?.invoke()
                            }
                            MediaPlayerCommand.MEDIA_PLAYER_COMMAND_STOP -> {
                                player.mediaPlayer.stop()
                                player.onMediaStop?.invoke()
                            }
                            MediaPlayerCommand.MEDIA_PLAYER_COMMAND_MUTE -> player.setMuted(true)
                            MediaPlayerCommand.MEDIA_PLAYER_COMMAND_UNMUTE -> player.setMuted(false)
                            else -> {}
                        }
                    } else if (message.hasVolume) {
                        player.setVolume(message.volume)
                    }
                }
            }
        }
    }

    override fun subscribe() = combine(
        player.mediaPlayer.state,
        player.volume,
        player.muted,
    ) { state, volume, muted ->
        mediaPlayerStateResponse {
            key = this@MediaPlayerEntity.key
            this.state = getState(state)
            this.volume = volume
            this.muted = muted
        }
    }

    private fun getState(state: AudioPlayerState) = when (state) {
        AudioPlayerState.PLAYING -> MediaPlayerState.MEDIA_PLAYER_STATE_PLAYING
        AudioPlayerState.PAUSED -> MediaPlayerState.MEDIA_PLAYER_STATE_PAUSED
        AudioPlayerState.IDLE -> MediaPlayerState.MEDIA_PLAYER_STATE_IDLE
    }
    
    companion object {
        private const val TAG = "MediaPlayerEntity"
        private const val FEATURE_PAUSE = 1 shl 0      // 1
        private const val FEATURE_SEEK = 1 shl 1       // 2
        private const val FEATURE_VOLUME_SET = 1 shl 2 // 4
        private const val FEATURE_VOLUME_MUTE = 1 shl 3 // 8
        private const val FEATURE_PREVIOUS_TRACK = 1 shl 4 // 16
        private const val FEATURE_NEXT_TRACK = 1 shl 5     // 32
        private const val FEATURE_TURN_ON = 1 shl 7    // 128
        private const val FEATURE_TURN_OFF = 1 shl 8   // 256
        private const val FEATURE_PLAY_MEDIA = 1 shl 9 // 512
        private const val FEATURE_VOLUME_STEP = 1 shl 10 // 1024
        private const val FEATURE_STOP = 1 shl 12      // 4096
        private const val FEATURE_PLAY = 1 shl 14      // 16384
        private const val FEATURE_MEDIA_ANNOUNCE = 1 shl 24 // 16777216
    }
}