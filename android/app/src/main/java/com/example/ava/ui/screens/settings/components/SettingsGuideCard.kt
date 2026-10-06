package com.example.ava.ui.screens.settings.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.SimpleCard

private const val SettingsGuideCardLines = 5

/**
 * Page-header help card: 5-line viewport, top/bottom dissolve scroll, dismiss on the top end.
 */
@Composable
fun SettingsGuideCard(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissContentDescription: String = stringResource(R.string.settings_ha_guide_dismiss),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val scale = rememberSettingsTextScale()

    SimpleCard(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CollapsibleDescriptionText(
                text = text,
                collapsedLines = SettingsGuideCardLines,
                pinViewport = true,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                fontWeight = FontWeight.Medium,
                topPadding = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = (24f * scale).dp),
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = dismissContentDescription,
                tint = if (isDarkMode) Color(0xFF4B5563) else Color(0xFFCBD5E1),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size((16f * scale).dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
