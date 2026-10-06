package com.example.ava.ui.screens.home

import android.content.Context
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.SidebarPosition
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Synchronous mirror of home-layout DataStore fields so the first frame after rotation
 * does not flash the default satellite card before async DataStore emits.
 */
data class CachedHomeLayoutSettings(
    val enableMinimalLauncher: Boolean = false,
    val enableSidebar: Boolean = true,
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val hideHomeHeader: Boolean = false,
)

object HomeLayoutSettingsMirror {
    private const val MIRROR_PREFS = "home_layout_mirror"
    private const val KEY_ENABLE_MINIMAL_LAUNCHER = "enable_minimal_launcher"
    private const val KEY_ENABLE_SIDEBAR = "enable_sidebar"
    private const val KEY_SIDEBAR_POSITION = "sidebar_position"
    private const val KEY_HIDE_HOME_HEADER = "hide_home_header"

    @Volatile
    private var watcherStarted = false

    fun readCached(context: Context): CachedHomeLayoutSettings = try {
        val prefs = context.applicationContext
            .getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
        CachedHomeLayoutSettings(
            enableMinimalLauncher = prefs.getBoolean(KEY_ENABLE_MINIMAL_LAUNCHER, false),
            enableSidebar = prefs.getBoolean(KEY_ENABLE_SIDEBAR, true),
            sidebarPosition = when (prefs.getString(KEY_SIDEBAR_POSITION, SidebarPosition.LEFT.name)) {
                SidebarPosition.RIGHT.name -> SidebarPosition.RIGHT
                else -> SidebarPosition.LEFT
            },
            hideHomeHeader = prefs.getBoolean(KEY_HIDE_HOME_HEADER, false),
        )
    } catch (_: Exception) {
        CachedHomeLayoutSettings()
    }

    fun write(context: Context, player: PlayerSettings, sidebar: SidebarSettings) {
        try {
            context.applicationContext.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLE_MINIMAL_LAUNCHER, player.enableMinimalLauncher)
                .putBoolean(KEY_ENABLE_SIDEBAR, sidebar.enableSidebar)
                .putString(KEY_SIDEBAR_POSITION, sidebar.sidebarPosition.name)
                .putBoolean(KEY_HIDE_HOME_HEADER, sidebar.hideHomeHeader)
                .apply()
        } catch (_: Exception) {
            // Mirror is best-effort; never let it crash settings updates.
        }
    }

    suspend fun syncFromDataStore(context: Context) {
        val appContext = context.applicationContext
        val player = appContext.playerSettingsStore.data.first()
        val sidebar = appContext.sidebarSettingsStore.data.first()
        write(appContext, player, sidebar)
    }

    fun startWatcher(context: Context, scope: CoroutineScope) {
        if (watcherStarted) return
        watcherStarted = true
        val appContext = context.applicationContext
        scope.launch {
            syncFromDataStore(appContext)
            combine(
                appContext.playerSettingsStore.data,
                appContext.sidebarSettingsStore.data,
            ) { player, sidebar -> player to sidebar }
                .collect { (player, sidebar) ->
                    write(appContext, player, sidebar)
                }
        }
    }
}
