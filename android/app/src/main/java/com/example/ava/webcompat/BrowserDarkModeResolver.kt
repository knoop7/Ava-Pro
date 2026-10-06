package com.example.ava.webcompat

import android.content.Context
import com.example.ava.settings.DarkModeManager

/** Resolves whether the browser should render in dark mode. */
object BrowserDarkModeResolver {
    private const val GECKO_DARK_MODE_PREFS = "gecko_browser_dark_mode"
    private const val KEY_HAS_HOST_STATE = "has_host_state"
    private const val KEY_FOLLOW_ENABLED = "follow_enabled"
    private const val KEY_DARK_MODE = "dark_mode"

    /**
     * When [followEnabled] is true, use the Ava app dark-mode preference as the source of truth.
     * System UI_MODE_NIGHT is unreliable on many kiosk devices (e.g. Android 7.x Allwinner boards).
     */
    fun shouldUseDarkMode(context: Context, followEnabled: Boolean): Boolean {
        readHostStateOverride(context)?.let { return it }
        return DarkModeManager.getInstance(context).isDarkMode()
    }

    /** True when Ava should push/pull HA frontend theme (browser setting). */
    fun shouldSyncHaTheme(followEnabled: Boolean): Boolean = followEnabled

    fun writeHostStateOverride(context: Context, followEnabled: Boolean, darkMode: Boolean) {
        try {
            context.getSharedPreferences(GECKO_DARK_MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HAS_HOST_STATE, true)
                .putBoolean(KEY_FOLLOW_ENABLED, followEnabled)
                .putBoolean(KEY_DARK_MODE, darkMode)
                .apply()
        } catch (e: Exception) {
            // Best-effort bridge state; fall back to the local DarkModeManager on failure.
        }
    }

    fun readHostFollowOverride(context: Context): Boolean? {
        return try {
            val prefs = context.getSharedPreferences(GECKO_DARK_MODE_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_HOST_STATE, false)) null
            else prefs.getBoolean(KEY_FOLLOW_ENABLED, true)
        } catch (e: Exception) {
            null
        }
    }

    private fun readHostStateOverride(context: Context): Boolean? {
        return try {
            val prefs = context.getSharedPreferences(GECKO_DARK_MODE_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_HOST_STATE, false)) return null
            if (!prefs.getBoolean(KEY_FOLLOW_ENABLED, true)) return null
            prefs.getBoolean(KEY_DARK_MODE, false)
        } catch (e: Exception) {
            null
        }
    }
}
