package com.example.ava.ui.screens.home

import android.content.Context
import android.content.SharedPreferences
import com.example.ava.utils.DirectBootHelper
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.shouldHideHomeChrome
import com.example.ava.settings.sidebarSettingsStore

const val KEY_HIDE_HOME_CHROME = "hide_home_chrome"

fun readHideHomeChrome(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(KEY_HIDE_HOME_CHROME, false)

fun syncHideHomeChromePref(
    prefs: SharedPreferences,
    sidebarSettings: SidebarSettings,
    playerSettings: PlayerSettings
): Boolean {
    val value = shouldHideHomeChrome(sidebarSettings, playerSettings)
    if (prefs.getBoolean(KEY_HIDE_HOME_CHROME, false) != value) {
        prefs.edit().putBoolean(KEY_HIDE_HOME_CHROME, value).apply()
    }
    return value
}

suspend fun syncHideHomeChromePrefFromStores(context: Context) {
    if (!DirectBootHelper.isUserUnlocked(context)) return
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val sidebarSettings = SidebarSettingsStore(context.sidebarSettingsStore).get()
    val playerSettings = PlayerSettingsStore(context.playerSettingsStore).get()
    syncHideHomeChromePref(prefs, sidebarSettings, playerSettings)
}
