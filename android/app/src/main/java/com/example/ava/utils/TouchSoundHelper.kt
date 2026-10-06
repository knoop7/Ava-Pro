package com.example.ava.utils

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.os.SystemClock
import android.view.SoundEffectConstants
import android.view.View

object TouchSoundHelper {
    const val PREFS_NAME = "ava_prefs"
    const val KEY_TOUCH_SOUND_ENABLED = "touch_sound_enabled"

    @Volatile
    private var lastPlayAtMs: Long = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUCH_SOUND_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOUCH_SOUND_ENABLED, enabled)
            .apply()
    }

    fun playClick(context: Context) {
        if (!isEnabled(context)) return
        if (!isSystemSoundEffectsEnabled(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayAtMs < 40L) return
        lastPlayAtMs = now
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        }
    }

    fun playClick(view: View) {
        if (!isEnabled(view.context)) return
        if (!isSystemSoundEffectsEnabled(view.context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayAtMs < 40L) return
        lastPlayAtMs = now
        runCatching {
            view.isSoundEffectsEnabled = true
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }.onFailure {
            playClick(view.context)
        }
    }

    private fun isSystemSoundEffectsEnabled(context: Context): Boolean {
        return runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SOUND_EFFECTS_ENABLED,
                0
            ) == 1
        }.getOrDefault(false)
    }
}
