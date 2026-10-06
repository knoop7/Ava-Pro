package com.example.ava.ui.screens.settings.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor

/**
 * Reusable disabled-state chrome for settings cards (Bluetooth tracked-devices pattern):
 * blur the content and show a frosted hint overlay when [disabled] is true.
 *
 * Use [SettingsDisabledOverlayScope.interactionsEnabled] to gate clicks inside [content].
 */
class SettingsDisabledOverlayScope internal constructor(
    val interactionsEnabled: Boolean,
)

@Composable
fun SettingsDisabledOverlay(
    disabled: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 6.dp,
    content: @Composable SettingsDisabledOverlayScope.() -> Unit,
) {
    val scope = remember(disabled) { SettingsDisabledOverlayScope(interactionsEnabled = !disabled) }
    Box(modifier = modifier.fillMaxWidth()) {
        Box(modifier = if (disabled) Modifier.blur(blurRadius) else Modifier) {
            scope.content()
        }
        if (disabled) {
            val context = LocalContext.current
            val prefs = remember {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
            val overlayBackground =
                if (isDarkMode) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(overlayBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hint,
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium,
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}
