package com.example.ava.ui

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ava.ui.prefs.rememberStringPreference
import com.example.ava.ui.screens.home.PREFS_NAME

@Composable
fun ImmersiveMode(
    isLandscape: Boolean = false
) {
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val mode by rememberStringPreference(
        prefs,
        SystemBarsMode.PREF_KEY,
        SystemBarsMode.HIDE_NAV.storage,
    )

    DisposableEffect(lifecycleOwner, isLandscape, mode) {
        val activity = view.context as? Activity ?: return@DisposableEffect onDispose {}

        AvaSystemChrome.applyImmersiveMode(activity, isLandscape)
        AvaSystemChrome.installImmersiveModeListener(activity, isLandscape)

        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                AvaSystemChrome.applyImmersiveMode(activity, isLandscape)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AvaSystemChrome.clearImmersiveModeListener(activity)
        }
    }
}
