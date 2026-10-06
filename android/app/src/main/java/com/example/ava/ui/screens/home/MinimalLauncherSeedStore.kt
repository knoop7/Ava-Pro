package com.example.ava.ui.screens.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot record of the first-run desktop decision.
 *
 * Seeding a default page must never disturb an install that already has a desktop,
 * so the decision is taken exactly once and persisted: either the default page was
 * written, or this install was recognised as an existing one and left alone.
 */
object MinimalLauncherSeedStore {
    private const val PREFS = "minimal_launcher_seed"
    private const val KEY_DECIDED = "decided"

    private val _decided = MutableStateFlow(false)
    val decidedFlow: StateFlow<Boolean> = _decided.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoadedFlow: StateFlow<Boolean> = _isLoaded.asStateFlow()

    fun load(context: Context) {
        _decided.value = prefs(context).getBoolean(KEY_DECIDED, false)
        _isLoaded.value = true
    }

    fun markDecided(context: Context) {
        if (_decided.value) return
        _decided.value = true
        prefs(context).edit().putBoolean(KEY_DECIDED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
