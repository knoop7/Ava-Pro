package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.SettingsDetailScreen

/**
 * Single mount point for voice Detail pages + wake library.
 * Owns Voice Channel gate, stats sheet switch, and bottom handle wiring.
 */
@Composable
fun VoiceDetailScaffold(
    navController: NavController,
    title: String,
    focus: VoiceStatsFocus,
    content: LazyListScope.() -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val voiceChannelStore = remember { VoiceChannelSettingsStore(context.voiceChannelSettingsStore) }
    val voiceChannelOn by voiceChannelStore.enabled.collectAsStateWithLifecycle(initialValue = true)
    // Handle = switch: open starts poll/history; dismiss tears composition down.
    var showStats by remember { mutableStateOf(false) }

    LaunchedEffect(voiceChannelOn) {
        if (!voiceChannelOn) showStats = false
    }

    SettingsDetailScreen(
        navController = navController,
        title = title,
        showBottomHandle = voiceChannelOn && !showStats,
        onBottomHandleClick = { showStats = true },
        content = content,
    )
    if (showStats) {
        MicrophoneStatsSheet(
            onDismiss = { showStats = false },
            isDarkMode = isDarkMode,
            focus = focus,
        )
    }
}
