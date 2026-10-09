package com.example.ava.ui.screens.settings

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.Screen
import com.example.ava.ui.isCompactSquarePixels
import com.example.ava.ui.rememberCompactSquareScreen

val LocalSettingsSplitActive = staticCompositionLocalOf { false }

/** Host already owns the edge sidebar; page-level wrappers must not nest another. */
val LocalSettingsHostDrawer = staticCompositionLocalOf { false }

private val SETTINGS_SPLIT_GROUP_ROOTS = setOf(
    Screen.SETTINGS,
    Screen.SETTINGS_CONNECTION,
    Screen.SETTINGS_INTERACTION,
    Screen.SETTINGS_SERVICE,
    Screen.SETTINGS_BLUETOOTH,
    Screen.SETTINGS_SCREENSAVER,
    Screen.SETTINGS_BROWSER,
    Screen.SETTINGS_EXPERIMENTAL,
    Screen.SETTINGS_ROOT,
)

/** Same drawables as the main-settings group cards; fills the hidden back slot. */
private val SETTINGS_SPLIT_GROUP_ICONS = mapOf(
    Screen.SETTINGS to R.drawable.esphome_24px,
    Screen.SETTINGS_CONNECTION to R.drawable.esphome_24px,
    Screen.SETTINGS_SERVICE to R.drawable.mdi_cog_transfer,
    Screen.SETTINGS_INTERACTION to R.drawable.display_24px,
    Screen.SETTINGS_BLUETOOTH to R.drawable.bluetooth_24px,
    Screen.SETTINGS_SCREENSAVER to R.drawable.screensaver_24px,
    Screen.SETTINGS_BROWSER to R.drawable.globe_24px,
    Screen.SETTINGS_EXPERIMENTAL to R.drawable.mdi_dots_circle,
    Screen.SETTINGS_ROOT to R.drawable.root_24px,
)

@Composable
fun rememberSettingsSplitActive(enabled: Boolean): Boolean {
    if (!enabled) return false
    // Square stays on the portrait page, so split stays off. Portrait orientation
    // is the default and also keeps split off. A landscape panel whose left column
    // is at its minimum width is still landscape — do not treat that column as square.
    if (rememberCompactSquareScreen()) return false
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    val height = configuration.screenHeightDp
    if (isCompactSquarePixels(maxOf(width, height), minOf(width, height))) return false
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/** Handle-sheet chrome. Split panes are portrait-width, so stay on the portrait recipe. */
@Composable
fun rememberSettingsHandleLandscape(): Boolean {
    if (LocalSettingsSplitActive.current) return false
    if (rememberCompactSquareScreen()) return false
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp
}

fun isSettingsSplitGroupRoot(route: String?): Boolean =
    route != null && SETTINGS_SPLIT_GROUP_ROOTS.contains(route.substringBefore("?"))

/**
 * Right-pane group homes hide the header back: the left index already has
 * the exit arrow. The default Voice Config overlay is composed outside
 * NavHost on [Screen.SETTINGS], so [ownerRoute] may be the page title
 * instead of a route — [currentRoute] still matches the group root.
 */
fun shouldHideSettingsSplitBack(
    splitActive: Boolean,
    ownerRoute: String?,
    currentRoute: String?,
): Boolean = splitActive &&
    (isSettingsSplitGroupRoot(ownerRoute) || isSettingsSplitGroupRoot(currentRoute))

@DrawableRes
fun settingsSplitGroupLeadingIcon(ownerRoute: String?, currentRoute: String?): Int? {
    val owner = ownerRoute?.substringBefore("?")
    val current = currentRoute?.substringBefore("?")
    return SETTINGS_SPLIT_GROUP_ICONS[owner] ?: SETTINGS_SPLIT_GROUP_ICONS[current]
}

/** Left index + right detail. Mods, entity editors, and software update stay full-width. */
fun isSettingsSplitMasterRoute(route: String?): Boolean {
    if (route.isNullOrBlank()) return false
    val path = route.substringBefore("?")
    if (path == Screen.SETTINGS_SOFTWARE_UPDATE ||
        path.startsWith("${Screen.SETTINGS_SOFTWARE_UPDATE}/")
    ) {
        return false
    }
    return path == Screen.SETTINGS || path.startsWith("settings/")
}

/**
 * Sidebar (and browser) jump straight to Restart & Exit with only Home underneath.
 * Keep that path full-width so the settings index never appears; back pops Home.
 * Opening the same page from Device Service still has a settings ancestor and stays split.
 */
fun isStandaloneDeviceControlShortcut(route: String?, previousRoute: String?): Boolean {
    val path = route?.substringBefore("?") ?: return false
    if (path != Screen.SETTINGS_SERVICE_DEVICE_CONTROL) return false
    return previousRoute == Screen.HOME || previousRoute.isNullOrBlank()
}

fun NavController.navigateSettingsSplitGroup(route: String) {
    val current = currentDestination?.route
    if (route == Screen.SETTINGS_CONNECTION) {
        if (current == Screen.SETTINGS || current == Screen.SETTINGS_CONNECTION) return
        popBackStack(Screen.SETTINGS, inclusive = false)
        return
    }
    if (current == route) return
    navigate(route) {
        popUpTo(Screen.SETTINGS) { inclusive = false }
        launchSingleTop = true
    }
}

sealed class SettingsSearchNavAction {
    data object PopToSettingsHome : SettingsSearchNavAction()
    data class GroupRoot(val route: String) : SettingsSearchNavAction()
    data class Page(val route: String) : SettingsSearchNavAction()
}

fun settingsSearchNavAction(route: String): SettingsSearchNavAction {
    val path = route.substringBefore("?")
    if (path == Screen.SETTINGS || path == Screen.SETTINGS_CONNECTION) {
        return SettingsSearchNavAction.PopToSettingsHome
    }
    if (isSettingsSplitGroupRoot(path)) {
        return SettingsSearchNavAction.GroupRoot(path)
    }
    return SettingsSearchNavAction.Page(path)
}

/** Stay inside Settings — do not pop Home the way sidebar shortcuts do. */
fun NavController.navigateSettingsSearchResult(route: String) {
    when (val action = settingsSearchNavAction(route)) {
        SettingsSearchNavAction.PopToSettingsHome -> {
            val current = currentDestination?.route
            if (current == Screen.SETTINGS || current == Screen.SETTINGS_CONNECTION) return
            popBackStack(Screen.SETTINGS, inclusive = false)
        }
        is SettingsSearchNavAction.GroupRoot -> navigateSettingsSplitGroup(action.route)
        is SettingsSearchNavAction.Page -> {
            val current = currentDestination?.route
            if (current == action.route) return
            navigate(action.route) {
                popUpTo(Screen.SETTINGS) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}
