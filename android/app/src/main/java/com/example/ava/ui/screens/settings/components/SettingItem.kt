package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor

private val LabelColorLight = Color(0xFF334155)
private val LabelColorDark = Color(0xFFF1F5F9)
private val SubLabelColor = Color(0xFF94A3B8)
private val ValueColor = Color(0xFF64748B)

@Composable
fun SettingItem(
    modifier: Modifier = Modifier,
    name: String,
    description: String = "",
    value: String = "",
    icon: Painter? = null,
    nameTrailing: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val labelColor = if (isDarkMode) LabelColorDark else LabelColorLight
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // Keep spacing in lockstep with typography — old path grew padding while text stayed phone-sized.
    val textScale = rememberSettingsTextScale()
    val trailingValue = description.isNotBlank() && value.isNotBlank()

    Row(
        // Focus ring ahead of the caller modifier: it lights up for row-level
        // clickables and for focused children (Switch, trailing buttons) alike.
        modifier = Modifier
            .settingsFocusHighlight()
            .then(modifier)
            .fillMaxWidth()
            .padding(vertical = (16f * textScale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = name,
                tint = SubLabelColor, 
                modifier = Modifier.size((20f * textScale).dp)
            )
            Spacer(modifier = Modifier.width((16f * textScale).dp))
        }
        
        
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = (8f * textScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    color = labelColor,
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                nameTrailing?.invoke()
            }
            if (description.isNotBlank()) {
                CollapsibleDescriptionText(
                    text = description,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                )
            }
            if (!trailingValue && value.isNotBlank()) {
                Text(
                    text = value,
                    color = getSettingsDescriptionColor(), 
                    fontSize = settingsBodyTextSize(),
                    modifier = Modifier.padding(top = (4f * textScale.coerceAtMost(1.2f)).dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width((15f * textScale).dp))

        if (trailingValue) {
            val maxTrailingTextWidth = (configuration.screenWidthDp * 0.20f)
                .coerceIn(52f, 100f * textScale.coerceAtMost(1.25f))
                .dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = maxTrailingTextWidth + 28.dp),
            ) {
                Text(
                    text = value,
                    fontSize = settingsCaptionTextSize(base = 11f),
                    color = getAccentColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.widthIn(max = maxTrailingTextWidth),
                )
                Spacer(modifier = Modifier.width(8.dp))
                action()
            }
        } else {
            action()
        }
    }
}

@Composable
fun RowScope.Details(name: String, description: String = "", value: String = "") {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val labelColor = if (isDarkMode) LabelColorDark else LabelColorLight
    val textScale = rememberSettingsTextScale()
    val trailingValue = description.isNotBlank() && value.isNotBlank()

    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = name,
            color = labelColor,
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium
        )
        if (description.isNotBlank()) {
            CollapsibleDescriptionText(
                text = description,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        if (!trailingValue && value.isNotBlank()) {
            Text(
                text = value,
                color = getSettingsDescriptionColor(),
                fontSize = settingsBodyTextSize(),
                modifier = Modifier.padding(top = (4f * textScale.coerceAtMost(1.2f)).dp)
            )
        }
    }
}

@Composable
fun ActionContainer(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
