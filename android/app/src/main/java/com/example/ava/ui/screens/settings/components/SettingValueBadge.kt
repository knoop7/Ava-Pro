package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor

@Composable
private fun settingValueBadgeMaxWidth(): androidx.compose.ui.unit.Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return (screenWidthDp * 0.28f).coerceIn(52f, 92f).dp
}

@Composable
fun SettingValueBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val compact = screenWidthDp < 400
    val horizontalPad = if (compact) 5.dp else 7.dp
    val verticalPad = if (compact) 2.dp else 3.dp

    Surface(
        modifier = modifier.widthIn(max = settingValueBadgeMaxWidth()),
        shape = RoundedCornerShape(6.dp),
        color = getSliderInactiveColor(),
    ) {
        Text(
            text = text,
            fontSize = settingsCaptionTextSize(base = 11f),
            color = getAccentColor(),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.padding(horizontal = horizontalPad, vertical = verticalPad),
        )
    }
}

@Composable
fun SettingSliderLabelRow(
    title: String,
    description: String = "",
    badgeText: String = "",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                CollapsibleDescriptionText(
                    text = description,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                )
            }
        }
        if (badgeText.isNotEmpty()) {
            SettingValueBadge(text = badgeText)
        }
    }
}
