package com.example.ava.ui.glass

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.ava.ui.components.sidebarDrawerFocusable
import com.example.ava.ui.screens.settings.isDarkModeEnabled
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown

/**
 * Sidebar-only switch: the usual capsule, just a little more see-through so it
 * sits in the glass instead of stacking another glass material on top of it.
 * Settings-page switches stay [com.example.ava.ui.screens.settings.ModernSwitch].
 */
@Composable
fun LiquidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkModeEnabled()
    val accent = if (dark) AccentBrown else AccentBlue
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.sidebarDrawerFocusable(),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = accent.copy(alpha = if (dark) 0.42f else 0.36f),
            checkedBorderColor = Color.White.copy(alpha = if (dark) 0.16f else 0.28f),
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = if (dark) {
                Color.White.copy(alpha = 0.12f)
            } else {
                Color.Black.copy(alpha = 0.08f)
            },
            uncheckedBorderColor = Color.White.copy(alpha = if (dark) 0.14f else 0.32f),
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledCheckedTrackColor = accent.copy(alpha = 0.22f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = if (dark) {
                Color.White.copy(alpha = 0.06f)
            } else {
                Color.Black.copy(alpha = 0.05f)
            },
        ),
    )
}
