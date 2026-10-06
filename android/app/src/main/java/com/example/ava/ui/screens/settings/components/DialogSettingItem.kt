package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DialogSettingItem(
    name: String,
    description: String = "",
    value: String,
    enabled: Boolean = true,
    nameTrailing: (@Composable () -> Unit)? = null,
    content: @Composable DialogScope.() -> Unit
) {
    val dialogScope = remember { DialogScope() }
    val isDialogOpen by dialogScope.isDialogOpen.collectAsStateWithLifecycle()
    // Plain clickable: SettingItem already draws the D-pad focus ring, so the
    // settingsClickable variant would stack a second one on the same bounds.
    val modifier =
        if (enabled) Modifier.clickable { dialogScope.openDialog() } else Modifier.alpha(0.5f)
    val trailingValue = description.isNotBlank() && value.isNotBlank()
    SettingItem(
        modifier = modifier,
        name = name,
        description = description,
        value = value,
        nameTrailing = nameTrailing,
    ) {
        if (trailingValue) {
            SettingsChevronIcon(tint = Color(0xFF94A3B8))
        }
    }
    if (isDialogOpen) {
        content(dialogScope)
    }
}
