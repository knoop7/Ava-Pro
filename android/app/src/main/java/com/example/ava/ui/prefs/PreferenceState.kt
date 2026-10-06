package com.example.ava.ui.prefs

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun rememberBooleanPreference(
    prefs: SharedPreferences,
    key: String,
    default: Boolean = false
): State<Boolean> {
    val state = remember { mutableStateOf(prefs.getBoolean(key, default)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, changedKey ->
            if (changedKey == key) {
                state.value = sharedPrefs.getBoolean(key, default)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
fun rememberStringPreference(
    prefs: SharedPreferences,
    key: String,
    default: String = ""
): State<String> {
    val state = remember { mutableStateOf(prefs.getString(key, default).orEmpty()) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, changedKey ->
            if (changedKey == key) {
                state.value = sharedPrefs.getString(key, default).orEmpty()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
