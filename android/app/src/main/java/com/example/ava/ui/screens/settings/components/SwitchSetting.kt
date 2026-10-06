package com.example.ava.ui.screens.settings.components

import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor

@Composable
fun SwitchSetting(
    name: String,
    description: String,
    value: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val modifier = if (enabled) Modifier else Modifier.alpha(0.5f)
    SettingItem(
        name = name,
        description = description,
        modifier = modifier
    ) {
        val accentColor = getAccentColor()
        val inactiveColor = getSliderInactiveColor()
        Switch(
            checked = value,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor,
                checkedBorderColor = accentColor,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = inactiveColor,
                uncheckedBorderColor = inactiveColor
            )
        )
    }
}
