package com.example.ava.touchpad

/**
 * When AI taps another app, skip our own overlay windows first so the
 * click lands underneath — same idea as [TouchPadScroller.clickThrough]
 * skipping accessibility overlays.
 *
 * The pad window being closed or hidden is irrelevant. Clicks reuse the
 * cursor drawing and Accessibility, not [TouchPadOverlay.isShowing].
 */
internal object AiPhonePointerPolicy {
    /** System Accessibility list. Same action [AccessibilityBridge.openSettings] fires. */
    const val ACCESSIBILITY_SETTINGS_ACTION = "android.settings.ACCESSIBILITY_SETTINGS"

    fun pointerNeedsPadOverlay(@Suppress("UNUSED_PARAMETER") padShowing: Boolean, @Suppress("UNUSED_PARAMETER") padSidebarOn: Boolean): Boolean =
        false

    /**
     * In-app hits never need Accessibility. A miss inside Ava is just
     * not_found unless the query is another installed app (going outside).
     */
    fun needsAccessibility(inApp: Boolean, localHit: Boolean, goingOutside: Boolean): Boolean {
        if (localHit) return false
        if (goingOutside) return true
        return !inApp
    }

    fun skipHostWindow(
        windowPackage: String,
        hostPackage: String,
        preferForeign: Boolean,
    ): Boolean {
        if (!preferForeign) return false
        if (hostPackage.isBlank() || windowPackage.isBlank()) return false
        return windowPackage == hostPackage
    }
}
