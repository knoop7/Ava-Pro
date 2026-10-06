package com.example.ava.ui.screens.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists whether the empty-desktop long-press hint has been shown / dismissed. */
object MinimalLauncherEmptyHintStore {
    private const val PREFS = "minimal_launcher_empty_hint"
    private const val KEY_BADGE_SEEN = "badge_seen"

    private val _badgeSeen = MutableStateFlow(false)
    val badgeSeenFlow: StateFlow<Boolean> = _badgeSeen.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _badgeSeen.value = prefs.getBoolean(KEY_BADGE_SEEN, false)
    }

    fun markSeen(context: Context) {
        if (_badgeSeen.value) return
        _badgeSeen.value = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BADGE_SEEN, true)
            .apply()
    }
}
