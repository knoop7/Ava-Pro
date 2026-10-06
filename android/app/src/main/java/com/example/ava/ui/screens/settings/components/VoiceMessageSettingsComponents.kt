package com.example.ava.ui.screens.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ava.R
import androidx.compose.ui.Alignment
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getInputBackground
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class VoiceFlowDirection { Outbound, Inbound }

@Composable
private fun isVoiceSettingsPortrait(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
        configuration.screenWidthDp < 520
}

@Composable
fun VoiceFlowSectionHeader(
    direction: VoiceFlowDirection,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val homePrefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(homePrefs, KEY_DARK_MODE, false)
    val accent = getAccentColor()
    val inboundAccent = if (isDarkMode) Color(0xFF5EEAD4) else Color(0xFF0F766E)
    val bandColor = when (direction) {
        VoiceFlowDirection.Outbound -> accent.copy(alpha = if (isDarkMode) 0.14f else 0.08f)
        VoiceFlowDirection.Inbound -> inboundAccent.copy(alpha = if (isDarkMode) 0.10f else 0.08f)
    }
    val bandBorder = when (direction) {
        VoiceFlowDirection.Outbound -> accent.copy(alpha = if (isDarkMode) 0.35f else 0.22f)
        VoiceFlowDirection.Inbound -> inboundAccent.copy(alpha = if (isDarkMode) 0.25f else 0.22f)
    }
    val highlightColor = when (direction) {
        VoiceFlowDirection.Outbound -> accent
        VoiceFlowDirection.Inbound -> inboundAccent
    }
    val title = when (direction) {
        VoiceFlowDirection.Outbound -> stringResource(R.string.settings_voice_flow_outbound_title)
        VoiceFlowDirection.Inbound -> stringResource(R.string.settings_voice_flow_inbound_title)
    }
    val subtitle = when (direction) {
        VoiceFlowDirection.Outbound -> stringResource(R.string.settings_voice_flow_outbound_desc)
        VoiceFlowDirection.Inbound -> stringResource(R.string.settings_voice_flow_inbound_desc)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = bandColor,
        border = BorderStroke(1.dp, bandBorder)
    ) {
        val isPortrait = isVoiceSettingsPortrait()
        if (isPortrait) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    VoiceFlowDiagram(
                        direction = direction,
                        highlightColor = highlightColor
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = title,
                        color = getLabelColor(),
                        fontSize = settingsTitleTextSize(base = 15f),
                        fontWeight = FontWeight.Bold
                    )
                    CollapsibleDescriptionText(
                        text = subtitle,
                        color = getSettingsDescriptionColor(),
                        fontSize = settingsBodyTextSize(base = 12f),
                        lineHeight = settingsBodyLineHeight(),
                        topPadding = 0.dp,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VoiceFlowDiagram(
                    direction = direction,
                    highlightColor = highlightColor
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = getLabelColor(),
                        fontSize = settingsTitleTextSize(base = 15f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        color = getSettingsDescriptionColor(),
                        fontSize = settingsBodyTextSize(base = 12f),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceFlowDiagram(
    direction: VoiceFlowDirection,
    highlightColor: Color
) {
    val nodeBg = getInputBackground()
    val nodeBorder = getSliderInactiveColor()
    val muted = getSettingsDescriptionColor()
    val localLabel = stringResource(R.string.settings_voice_flow_node_local)
    val remoteLabel = stringResource(R.string.settings_voice_flow_node_remote)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (direction == VoiceFlowDirection.Inbound) {
            VoiceFlowNode(
                label = remoteLabel,
                highlighted = false,
                highlightColor = highlightColor,
                background = nodeBg,
                border = nodeBorder
            )
            Text(text = "→", color = muted, fontSize = settingsBodyTextSize(base = 13f))
            VoiceFlowNode(
                label = localLabel,
                highlighted = true,
                highlightColor = highlightColor,
                background = nodeBg,
                border = nodeBorder
            )
        } else {
            VoiceFlowNode(
                label = localLabel,
                highlighted = true,
                highlightColor = highlightColor,
                background = nodeBg,
                border = nodeBorder
            )
            Text(text = "→", color = muted, fontSize = settingsBodyTextSize(base = 13f))
            VoiceFlowNode(
                label = remoteLabel,
                highlighted = false,
                highlightColor = highlightColor,
                background = nodeBg,
                border = nodeBorder
            )
        }
    }
}

@Composable
private fun VoiceFlowNode(
    label: String,
    highlighted: Boolean,
    highlightColor: Color,
    background: Color,
    border: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = background,
        border = BorderStroke(
            width = if (highlighted) 1.5.dp else 1.dp,
            color = if (highlighted) highlightColor else border
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = if (highlighted) highlightColor else getLabelColor(),
            fontSize = settingsCaptionTextSize(base = 11f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun VoiceInboundSubsectionLabel(
    label: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        color = getSettingsDescriptionColor(),
        fontSize = settingsCaptionTextSize(base = 11f),
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )
}

@Composable
fun VoiceCapabilityPicker(
    intercomEnabled: Boolean,
    callEnabled: Boolean,
    enabled: Boolean,
    onIntercomChange: (Boolean) -> Unit,
    onCallChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_voice_outbound_capabilities),
            color = getLabelColor(),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.settings_voice_outbound_capabilities_desc),
            color = getSettingsDescriptionColor(),
            fontSize = settingsBodyTextSize(),
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        val isPortrait = isVoiceSettingsPortrait()
        if (isPortrait) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceCapabilityTile(
                    title = stringResource(R.string.settings_voice_outbound_capability_intercom),
                    description = stringResource(R.string.settings_voice_outbound_capability_intercom_desc),
                    icon = Icons.Filled.Mic,
                    selected = intercomEnabled,
                    enabled = enabled,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (intercomEnabled && !callEnabled) return@VoiceCapabilityTile
                        onIntercomChange(!intercomEnabled)
                    }
                )
                VoiceCapabilityTile(
                    title = stringResource(R.string.settings_voice_outbound_capability_call),
                    description = stringResource(R.string.settings_voice_outbound_capability_call_desc),
                    icon = Icons.Filled.Phone,
                    selected = callEnabled,
                    enabled = enabled,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (callEnabled && !intercomEnabled) return@VoiceCapabilityTile
                        onCallChange(!callEnabled)
                    }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceCapabilityTile(
                    title = stringResource(R.string.settings_voice_outbound_capability_intercom),
                    description = stringResource(R.string.settings_voice_outbound_capability_intercom_desc),
                    icon = Icons.Filled.Mic,
                    selected = intercomEnabled,
                    enabled = enabled,
                    compact = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (intercomEnabled && !callEnabled) return@VoiceCapabilityTile
                        onIntercomChange(!intercomEnabled)
                    }
                )
                VoiceCapabilityTile(
                    title = stringResource(R.string.settings_voice_outbound_capability_call),
                    description = stringResource(R.string.settings_voice_outbound_capability_call_desc),
                    icon = Icons.Filled.Phone,
                    selected = callEnabled,
                    enabled = enabled,
                    compact = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (callEnabled && !intercomEnabled) return@VoiceCapabilityTile
                        onCallChange(!callEnabled)
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun VoiceCapabilityTile(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = getAccentColor()
    val surface = getInputBackground()
    val borderDefault = getSliderInactiveColor()
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        color = if (selected) accent.copy(alpha = 0.08f) else surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accent else borderDefault
        )
    ) {
        if (compact) {
            VoiceCapabilityCompactTileContent(
                title = title,
                description = description,
                icon = icon,
                selected = selected,
                accent = accent,
                borderDefault = borderDefault
            )
        } else {
            VoiceCapabilityCardTileContent(
                title = title,
                description = description,
                icon = icon,
                selected = selected,
                accent = accent,
                borderDefault = borderDefault
            )
        }
    }
}

@Composable
private fun VoiceCapabilityCheckmark(
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .background(
                color = if (selected) accent else getInputBackground(),
                shape = RoundedCornerShape(7.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun VoiceCapabilityIconBadge(
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    borderDefault: Color,
    iconPadding: Dp = 10.dp,
    iconSize: Dp = 20.dp
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = getInputBackground(),
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.35f) else borderDefault
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent else getLabelColor(),
            modifier = Modifier
                .padding(iconPadding)
                .size(iconSize)
        )
    }
}

@Composable
private fun VoiceCapabilityCompactTileContent(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    borderDefault: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VoiceCapabilityIconBadge(
            icon = icon,
            selected = selected,
            accent = accent,
            borderDefault = borderDefault,
            iconPadding = 8.dp,
            iconSize = 18.dp
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = getLabelColor(),
                fontSize = settingsTitleTextSize(base = 15f),
                fontWeight = FontWeight.SemiBold
            )
            CollapsibleDescriptionText(
                text = description,
                color = getSettingsDescriptionColor(),
                fontSize = settingsCaptionTextSize(base = 12f),
                lineHeight = settingsBodyLineHeight(),
                topPadding = 0.dp,
            )
        }
        VoiceCapabilityCheckmark(selected = selected, accent = accent)
    }
}

@Composable
private fun VoiceCapabilityCardTileContent(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    borderDefault: Color
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
        ) {
            VoiceCapabilityCheckmark(selected = selected, accent = accent)
        }
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
            VoiceCapabilityIconBadge(
                icon = icon,
                selected = selected,
                accent = accent,
                borderDefault = borderDefault
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                color = getLabelColor(),
                fontSize = settingsTitleTextSize(base = 15f),
                fontWeight = FontWeight.SemiBold
            )
            CollapsibleDescriptionText(
                text = description,
                color = getSettingsDescriptionColor(),
                fontSize = settingsCaptionTextSize(base = 12f),
                lineHeight = settingsBodyLineHeight(),
                topPadding = settingsDescriptionTopPadding(),
            )
        }
    }
}
