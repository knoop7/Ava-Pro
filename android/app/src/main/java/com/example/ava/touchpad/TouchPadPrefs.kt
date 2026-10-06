package com.example.ava.touchpad

import android.content.Context
import android.content.SharedPreferences

class TouchPadPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sizeDp: Int
        get() = TouchPadMath.clamp(
            prefs.getInt(KEY_SIZE_DP, TouchPadMath.DEFAULT_SIZE_DP),
            TouchPadMath.MIN_SIZE_DP,
            TouchPadMath.MAX_SIZE_DP,
        )
        set(value) {
            prefs.edit().putInt(KEY_SIZE_DP, TouchPadMath.sizeDp(value)).apply()
        }

    var padWidthDp: Int
        get() = prefs.getInt(KEY_WIDTH_DP, sizeDp).coerceAtLeast(TouchPadMath.MIN_SIZE_DP)
        set(value) {
            prefs.edit().putInt(KEY_WIDTH_DP, value.coerceAtLeast(TouchPadMath.MIN_SIZE_DP)).apply()
        }

    var padHeightDp: Int
        get() = prefs.getInt(KEY_HEIGHT_DP, sizeDp).coerceAtLeast(TouchPadMath.MIN_SIZE_DP)
        set(value) {
            prefs.edit().putInt(KEY_HEIGHT_DP, value.coerceAtLeast(TouchPadMath.MIN_SIZE_DP)).apply()
        }

    var pinned: Boolean
        get() = prefs.getBoolean(KEY_PINNED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PINNED, value).apply()
        }

    var opacity: Int
        get() = TouchPadMath.clamp(
            prefs.getInt(KEY_OPACITY, TouchPadMath.DEFAULT_OPACITY),
            TouchPadMath.MIN_OPACITY,
            TouchPadMath.MAX_OPACITY,
        )
        set(value) {
            prefs.edit().putInt(
                KEY_OPACITY,
                TouchPadMath.clamp(value, TouchPadMath.MIN_OPACITY, TouchPadMath.MAX_OPACITY),
            ).apply()
        }

    var doubleTapTimeoutMs: Long
        get() = prefs.getLong(KEY_DOUBLE_TAP_MS, TouchPadMath.DEFAULT_DOUBLE_TAP_MS)
            .coerceIn(120L, 450L)
        set(value) {
            prefs.edit().putLong(KEY_DOUBLE_TAP_MS, value.coerceIn(120L, 450L)).apply()
        }

    var holdDelayMs: Long
        get() = prefs.getLong(KEY_HOLD_DELAY_MS, TouchPadMath.DEFAULT_HOLD_DELAY_MS)
            .coerceIn(250L, 900L)
        set(value) {
            prefs.edit().putLong(KEY_HOLD_DELAY_MS, value.coerceIn(250L, 900L)).apply()
        }

    var slideDurationMs: Long
        get() = prefs.getLong(KEY_SLIDE_MS, TouchPadMath.DEFAULT_SLIDE_MS)
            .coerceIn(80L, 500L)
        set(value) {
            prefs.edit().putLong(KEY_SLIDE_MS, value.coerceIn(80L, 500L)).apply()
        }

    var cursorSensitivity: Int
        get() = TouchPadMath.clamp(
            prefs.getInt(KEY_SENSITIVITY, TouchPadMath.DEFAULT_SENSITIVITY),
            TouchPadMath.MIN_SENSITIVITY,
            TouchPadMath.MAX_SENSITIVITY,
        )
        set(value) {
            prefs.edit().putInt(
                KEY_SENSITIVITY,
                TouchPadMath.clamp(value, TouchPadMath.MIN_SENSITIVITY, TouchPadMath.MAX_SENSITIVITY),
            ).apply()
        }

    var cursorColorRgb: Int
        get() = prefs.getInt(KEY_CURSOR_RGB, DEFAULT_CURSOR_RGB) and 0xFFFFFF
        set(value) {
            prefs.edit().putInt(KEY_CURSOR_RGB, value and 0xFFFFFF).apply()
        }

    var idleCloseMs: Long
        get() = prefs.getLong(KEY_IDLE_CLOSE_MS, TouchPadMath.DEFAULT_IDLE_CLOSE_MS)
            .coerceAtLeast(0L)
        set(value) {
            prefs.edit().putLong(KEY_IDLE_CLOSE_MS, value.coerceAtLeast(0L)).apply()
        }

    var cornerSummonEnabled: Boolean
        get() = prefs.getBoolean(KEY_CORNER_SUMMON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CORNER_SUMMON, value).apply()
        }

    var tipSeenOnce: Boolean
        get() = prefs.getBoolean(KEY_TIP_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TIP_SEEN, value).apply()
        }

    var autoLibrary: String
        get() = prefs.getString(KEY_AUTO_LIBRARY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_AUTO_LIBRARY, value).apply()
        }

    var autoLoop: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOOP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_LOOP, value).apply()
        }

    var haSelectEnabled: Boolean
        get() = prefs.getBoolean(KEY_HA_SELECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HA_SELECT, value).apply()
        }

    fun savedNormOrNull(): Pair<Float, Float>? {
        if (!prefs.contains(KEY_X_NORM) || !prefs.contains(KEY_Y_NORM)) return null
        val x = prefs.getFloat(KEY_X_NORM, Float.NaN)
        val y = prefs.getFloat(KEY_Y_NORM, Float.NaN)
        if (!x.isFinite() || !y.isFinite()) return null
        return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    fun saveNorm(x: Float, y: Float) {
        prefs.edit()
            .putFloat(KEY_X_NORM, x.coerceIn(0f, 1f))
            .putFloat(KEY_Y_NORM, y.coerceIn(0f, 1f))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ava_touch_pad"
        private const val KEY_SIZE_DP = "touch_pad_size_dp"
        private const val KEY_WIDTH_DP = "touch_pad_width_dp"
        private const val KEY_HEIGHT_DP = "touch_pad_height_dp"
        private const val KEY_PINNED = "touch_pad_pinned"
        private const val KEY_OPACITY = "touch_pad_opacity"
        private const val KEY_DOUBLE_TAP_MS = "touch_pad_double_tap_timeout_ms"
        private const val KEY_HOLD_DELAY_MS = "touch_pad_hold_delay_ms"
        private const val KEY_SLIDE_MS = "touch_pad_slide_duration_ms"
        private const val KEY_SENSITIVITY = "touch_pad_cursor_sensitivity"
        private const val KEY_CURSOR_RGB = "touch_pad_cursor_color_rgb"
        private const val KEY_IDLE_CLOSE_MS = "touch_pad_idle_close_ms"
        private const val KEY_CORNER_SUMMON = "touch_pad_corner_summon"
        private const val KEY_TIP_SEEN = "touch_pad_tip_seen_once"
        private const val KEY_AUTO_LIBRARY = "touch_pad_auto_library"
        private const val KEY_AUTO_LOOP = "touch_pad_auto_loop"
        private const val KEY_HA_SELECT = "touch_pad_ha_select"
        private const val KEY_X_NORM = "touch_pad_x"
        private const val KEY_Y_NORM = "touch_pad_y"
        const val DEFAULT_CURSOR_RGB = 0xFFFFFF
    }
}
