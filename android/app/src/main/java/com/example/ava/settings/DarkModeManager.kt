package com.example.ava.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.ava.services.WebViewService
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.widgets.AvaActionWidgets
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Manager for dark mode state that supports two-way sync with Home Assistant.
 * This provides a Flow-based API for reading/writing dark mode state from SharedPreferences.
 */
class DarkModeManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Flow that emits the current dark mode state and updates when it changes.
     */
    val darkModeState: Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DARK_MODE) {
                trySend(prefs.getBoolean(KEY_DARK_MODE, false))
            }
        }

        trySend(prefs.getBoolean(KEY_DARK_MODE, false))

        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    /**
     * SettingState for two-way binding with Home Assistant entities.
     */
    val darkMode: SettingState<Boolean> = SettingState(
        darkModeState,
        { value -> setDarkMode(value) }
    )

    fun isDarkMode(): Boolean = prefs.getBoolean(KEY_DARK_MODE, false)

    fun setDarkMode(enabled: Boolean) {
        if (isDarkMode() == enabled) return
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        WebViewService.refreshDarkMode(appContext)
        AvaActionWidgets.refreshAll(appContext)
    }

    companion object {
        @Volatile
        private var instance: DarkModeManager? = null

        fun getInstance(context: Context): DarkModeManager {
            return instance ?: synchronized(this) {
                instance ?: DarkModeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
