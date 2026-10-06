package com.example.ava.ui.screens.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persistent state for the cold-start onboarding flow.
 *
 * /* @debug-onboarding — all public setters are debug-only hooks, called via
 *    ADB broadcast actions. Strip this class's debug surface (the
 *    [resetForDebug] / [setUiScale] / [UI_SCALE_KEY] members) when shipping a
 *    release build that no longer needs field-tuning. */
 */
object OnboardingPrefs {
    private const val TAG = "OnboardingPrefs"
    private const val PREFS_NAME = "ava_onboarding"

    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_COMPLETED_AT = "onboarding_completed_at"
    private const val KEY_COMPLETED_BY_FLOW = "onboarding_completed_by_flow"
    /* @debug-onboarding */ private const val KEY_DEBUG_FORCE = "onboarding_debug_force"

    /**
     * /* @debug-onboarding */ Uniform UI scale multiplier (default 1.0).
     * Tunable via:
     *   adb shell am broadcast -a com.example.ava.ACTION_ONBOARDING_SET_SCALE --ef scale 1.2
     */
    const val UI_SCALE_KEY = "onboarding_ui_scale"
    private const val UI_SCALE_DEFAULT = 1.0f

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True once the user has finished (or skipped) onboarding.
     * Also auto-marks completed for existing users upgrading from a pre-onboarding build
     * so they never see the flow.
     */
    fun isCompleted(context: Context): Boolean {
        val p = prefs(context)
        /* @debug-onboarding — debug reset sets this flag to bypass auto-skip */
        if (p.getBoolean(KEY_DEBUG_FORCE, false)) return false
        if (p.getBoolean(KEY_COMPLETED, false)) return true

        val existingUser = context.applicationContext
            .getSharedPreferences("ava_home_prefs", Context.MODE_PRIVATE)
            .all.isNotEmpty()
        if (existingUser) {
            p.edit()
                .putBoolean(KEY_COMPLETED, true)
                .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Existing user detected — onboarding auto-skipped")
            return true
        }
        return false
    }

    /**
     * True only for a brand-new install that has not finished onboarding yet,
     * or that just finished the real first-run flow. Existing / upgraded users
     * stay false so identity fields default to collapsed.
     */
    fun isBrandNewFirstUse(context: Context): Boolean {
        val p = prefs(context)
        /* @debug-onboarding */
        if (p.getBoolean(KEY_DEBUG_FORCE, false)) return true
        if (!p.getBoolean(KEY_COMPLETED, false)) return true
        return p.getBoolean(KEY_COMPLETED_BY_FLOW, false)
    }

    fun markCompleted(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_COMPLETED, true)
            .putBoolean(KEY_COMPLETED_BY_FLOW, true)
            .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
            .remove(KEY_DEBUG_FORCE) /* @debug-onboarding */
            .apply()
        Log.i(TAG, "Onboarding marked completed")
    }

    /**
     * /* @debug-onboarding */ Current scale factor for all onboarding dimensions.
     * Read once per composition root so changes take effect on next screen entry.
     */
    fun getUiScale(context: Context): Float =
        prefs(context).getFloat(UI_SCALE_KEY, UI_SCALE_DEFAULT)

    /**
     * /* @debug-onboarding */ Set the uniform scale multiplier.
     * adb shell am broadcast -a com.example.ava.ACTION_ONBOARDING_SET_SCALE --ef scale 1.15
     */
    fun setUiScale(context: Context, scale: Float) {
        val clamped = scale.coerceIn(0.5f, 3.0f)
        prefs(context).edit().putFloat(UI_SCALE_KEY, clamped).apply()
        Log.i(TAG, "UI scale set to $clamped")
    }

    /**
     * /* @debug-onboarding */ Reset onboarding so the flow shows again.
     * adb shell am broadcast -a com.example.ava.ACTION_ONBOARDING_RESET
     */
    fun resetForDebug(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_COMPLETED, false)
            .remove(KEY_COMPLETED_AT)
            .remove(KEY_COMPLETED_BY_FLOW)
            .putBoolean(KEY_DEBUG_FORCE, true)
            .apply()
        Log.i(TAG, "Onboarding reset (debug)")
    }
}
