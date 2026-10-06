package com.example.ava.services

import android.content.pm.ActivityInfo
import android.view.WindowManager

/**
 * Overlay windows default to [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED], which
 * means "whatever the focused window behind wants". Ava's [com.example.ava.MainActivity]
 * is unspecified in the other sense: rotate with the user / sensor.
 *
 * Stamp [WindowManager.LayoutParams.screenOrientation] onto every Ava overlay so a
 * landscape YouTube (or a portrait-only app) cannot flip clock / weather / FAB /
 * notifications into the other layout. [ActivityInfo.SCREEN_ORIENTATION_USER] still
 * follows the device the way the main UI does — Ava's own portrait ↔ landscape
 * layouts keep working.
 *
 * Force-orientation settings still win (portrait / landscape lock).
 */
object OverlayOrientation {
    @Volatile private var forceEnabled = false
    @Volatile private var forceMode = "auto"

    fun syncForceSettings(enabled: Boolean, mode: String) {
        forceEnabled = enabled
        forceMode = mode
    }

    fun screenOrientation(): Int {
        if (forceEnabled) {
            when (forceMode) {
                "portrait" -> return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        return ActivityInfo.SCREEN_ORIENTATION_USER
    }

    fun apply(params: WindowManager.LayoutParams) {
        params.screenOrientation = screenOrientation()
    }
}
