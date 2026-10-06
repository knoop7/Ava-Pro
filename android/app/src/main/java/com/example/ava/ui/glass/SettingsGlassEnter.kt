package com.example.ava.ui.glass

/**
 * One-shot handoff: a frosted sidebar (home / browser) is about to remount into
 * Settings. [SettingsRouteEnter] consumes this so only that jump gets blur → clear,
 * not every hop between settings pages.
 */
object SettingsGlassEnter {
    @Volatile
    private var armed = false

    fun armFromSidebarFrost() {
        if (LiquidGlass.blurEnabled && LiquidGlass.viewBlurRadiusPx() > 0.5f) {
            armed = true
        }
    }

    /** @return true once, then clears. */
    fun consume(): Boolean {
        val was = armed
        armed = false
        return was
    }
}
