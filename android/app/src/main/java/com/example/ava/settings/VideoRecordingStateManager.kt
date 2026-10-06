package com.example.ava.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Flow-based video recording toggle state for two-way sync between sidebar, HA, and Gecko pack.
 */
class VideoRecordingStateManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val enabledState: Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_RECORDING_ENABLED) {
                trySend(prefs.getBoolean(KEY_RECORDING_ENABLED, false))
            }
        }

        trySend(prefs.getBoolean(KEY_RECORDING_ENABLED, false))
        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_RECORDING_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        if (isEnabled() == enabled) return
        prefs.edit().putBoolean(KEY_RECORDING_ENABLED, enabled).apply()
    }

    fun clear() {
        setEnabled(false)
    }

    companion object {
        const val PREFS_NAME = "video_recording_prefs"
        const val KEY_RECORDING_ENABLED = "recording_enabled"

        @Volatile
        private var instance: VideoRecordingStateManager? = null

        fun getInstance(context: Context): VideoRecordingStateManager {
            return instance ?: synchronized(this) {
                instance ?: VideoRecordingStateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
