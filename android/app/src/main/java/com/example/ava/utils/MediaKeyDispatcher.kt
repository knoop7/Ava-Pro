package com.example.ava.utils

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Sends one media key to Android's session stack via [AudioManager.dispatchMediaKeyEvent].
 *
 * That is the same path a headset button uses, so the app that currently owns the
 * session (Netflix, Emby, and similar) receives it. Ava does not register a
 * MediaSession, so this does not pause Ava's own player.
 *
 * [COMMAND_PAUSE] is the script command. Play/pause toggles and can start playback
 * when an "everything off" automation runs again.
 */
object MediaKeyDispatcher {
    private const val TAG = "MediaKeyDispatcher"

    const val COMMAND_PAUSE = "pause"

    fun keyCodeFor(command: String): Int? = when (command.trim().lowercase()) {
        COMMAND_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> null
    }

    fun dispatchCommand(context: Context, command: String): Boolean {
        val keyCode = keyCodeFor(command)
        if (keyCode == null) {
            Log.w(TAG, "Ignored media key command: ${command.trim().ifEmpty { "(empty)" }}")
            return false
        }
        return dispatch(context, keyCode)
    }

    fun dispatch(context: Context, keyCode: Int): Boolean {
        if (!KeyEvent.isMediaSessionKey(keyCode)) {
            Log.w(TAG, "Refusing non-media key $keyCode")
            return false
        }
        val audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.w(TAG, "AudioManager unavailable")
            return false
        }
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0)
        return try {
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            Log.i(TAG, "Dispatched media key $keyCode")
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "dispatchMediaKeyEvent failed keyCode=$keyCode", e)
            false
        }
    }
}
