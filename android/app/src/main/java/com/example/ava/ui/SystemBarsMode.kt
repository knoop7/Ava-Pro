package com.example.ava.ui

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.ava.ui.prefs.rememberStringPreference
import com.example.ava.ui.screens.home.PREFS_NAME

/**
 * Activity and overlay chrome for the status and navigation bars.
 *
 * [HIDE_NAV] is the historical default: portrait hides the navigation bar,
 * landscape hides both. Other modes are explicit and apply in both orientations,
 * including whichever fullscreen overlay is currently showing.
 */
enum class SystemBarsMode(val storage: String) {
    HIDE_NAV("hide_nav"),
    SHOW("show"),
    HIDE_STATUS("hide_status"),
    HIDE_BOTH("hide_both");

    val hidesNavigation: Boolean
        get() = this == HIDE_NAV || this == HIDE_BOTH

    fun hidesStatus(isLandscape: Boolean): Boolean = when (this) {
        SHOW -> false
        HIDE_STATUS, HIDE_BOTH -> true
        HIDE_NAV -> isLandscape
    }

    companion object {
        const val PREF_KEY = "system_bars_mode"

        fun fromStorage(raw: String?): SystemBarsMode = when (raw) {
            SHOW.storage -> SHOW
            HIDE_STATUS.storage -> HIDE_STATUS
            HIDE_BOTH.storage -> HIDE_BOTH
            else -> HIDE_NAV
        }

        fun read(context: Context): SystemBarsMode {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY, HIDE_NAV.storage)
            return fromStorage(raw)
        }
    }
}

@Composable
fun rememberSystemBarsMode(): SystemBarsMode {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val raw by rememberStringPreference(
        prefs,
        SystemBarsMode.PREF_KEY,
        SystemBarsMode.HIDE_NAV.storage,
    )
    return SystemBarsMode.fromStorage(raw)
}

/** Landscape content insets stay cleared only while the navigation bar is hidden. */
@Composable
fun avaContentWindowInsets(isLandscape: Boolean): WindowInsets {
    val hideNav = rememberSystemBarsMode().hidesNavigation
    return if (isLandscape && hideNav) {
        WindowInsets(0, 0, 0, 0)
    } else {
        ScaffoldDefaults.contentWindowInsets
    }
}

/** Landscape top-bar insets stay cleared only while the status bar is hidden. */
@Composable
fun avaTopBarWindowInsets(isLandscape: Boolean): WindowInsets {
    val hideStatus = rememberSystemBarsMode().hidesStatus(isLandscape)
    return if (isLandscape && hideStatus) {
        WindowInsets(0, 0, 0, 0)
    } else {
        TopAppBarDefaults.windowInsets
    }
}

/**
 * Home draws edge to edge. Pad only the bars this mode newly shows:
 * portrait already clears the status bar with its own header padding.
 */
@Composable
fun Modifier.avaHomeSystemBarPadding(isLandscape: Boolean): Modifier {
    val mode = rememberSystemBarsMode()
    return when {
        mode == SystemBarsMode.SHOW && isLandscape ->
            windowInsetsPadding(WindowInsets.systemBars)
        !mode.hidesNavigation ->
            windowInsetsPadding(WindowInsets.navigationBars)
        else -> this
    }
}
