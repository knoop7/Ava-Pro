package com.example.ava.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.ui.components.LeftSidebarDrawerLayout
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.home.HomeSidebarContent
import com.example.ava.ui.screens.home.homeSidebarDrawerWidth
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.ProvideSettingsLightRipple
import com.example.ava.ui.theme.SlateBackground

/**
 * Wraps settings UI with the same home-screen sidebar drawer when enabled, so sidebar
 * changes can be previewed from any settings page without returning to home.
 *
 * Page enter motion is applied once in [com.example.ava.ui.MainNavHost] via [SettingsRouteEnter]
 * so scaffolds here do not double-animate.
 */
@Composable
fun SettingsSidebarDrawerWrapper(
    navController: NavController,
    enabled: Boolean = true,
    applyPageTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val sidebarStore = remember { SidebarSettingsStore(context.sidebarSettingsStore) }
    val sidebarSettings by sidebarStore.getFlow().collectAsStateWithLifecycle(SidebarSettings())
    val hostOwnsDrawer = LocalSettingsHostDrawer.current

    val page: @Composable () -> Unit = {
        if (!applyPageTheme || isDarkMode) {
            content()
        } else {
            ProvideSettingsLightRipple(content)
        }
    }

    if (hostOwnsDrawer) {
        page()
        return
    }

    LeftSidebarDrawerLayout(
        isDarkMode = isDarkMode,
        panelColor = if (isDarkMode) Color.Black else SlateBackground,
        position = sidebarSettings.sidebarPosition,
        drawerWidth = homeSidebarDrawerWidth(),
        enabled = enabled && sidebarSettings.enableSidebar,
        drawerContent = { closeDrawer ->
            HomeSidebarContent(
                isDarkMode = isDarkMode,
                onClose = closeDrawer,
                onSettingsClick = { route ->
                    navController.navigate(route) {
                        popUpTo(Screen.HOME)
                        launchSingleTop = true
                    }
                },
                includeSettingsSearch = true,
                onSettingsSearchNavigate = { route ->
                    navController.navigateSettingsSearchResult(route)
                },
            )
        },
        content = { page() }
    )
}
