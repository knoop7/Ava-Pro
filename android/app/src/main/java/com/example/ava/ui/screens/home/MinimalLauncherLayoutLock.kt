package com.example.ava.ui.screens.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop layout lock: when locked, grid cannot be edited and widget/icon taps
 * cannot launch into apps (pure display mode).
 * Default: unlocked.
 */
object MinimalLauncherLayoutLockStore {
    private const val PREFS = "minimal_launcher_layout_lock"
    private const val KEY_LOCKED = "locked"

    private val _locked = MutableStateFlow(false)
    val lockedFlow: StateFlow<Boolean> = _locked.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _locked.value = prefs.getBoolean(KEY_LOCKED, false)
    }

    fun setLocked(context: Context, locked: Boolean) {
        _locked.value = locked
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCKED, locked)
            .apply()
    }

    fun toggle(context: Context): Boolean {
        val next = !_locked.value
        setLocked(context, next)
        return next
    }
}
