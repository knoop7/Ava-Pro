package com.example.ava.webcompat

import android.content.Context
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.ava.settings.DarkModeManager

/** Theme tokens for the floating browser overlay (non-Compose surfaces). */
object BrowserOverlayTheme {
    // Match ui.theme.AppColors + getAccentColor() in settings screens.
    private const val ACCENT_LIGHT = 0xFF0417E0.toInt()
    private const val ACCENT_DARK = 0xFFA78B73.toInt()
    private const val SURFACE_LIGHT = 0xFFFFFFFF.toInt()
    private const val SURFACE_DARK = 0xFF1F1F1F.toInt()

    fun isDarkMode(context: Context): Boolean =
        DarkModeManager.getInstance(context).isDarkMode()

    fun accentColor(context: Context): Int =
        if (isDarkMode(context)) ACCENT_DARK else ACCENT_LIGHT

    fun surfaceColor(context: Context): Int =
        if (isDarkMode(context)) SURFACE_DARK else SURFACE_LIGHT

    fun applySwipeRefreshTheme(layout: SwipeRefreshLayout, context: Context) {
        layout.setColorSchemeColors(accentColor(context))
        layout.setProgressBackgroundColorSchemeColor(surfaceColor(context))
    }
}
