package com.example.ava.homeassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.homeassistant.entity.HaEntityDomainFilter
import com.example.ava.homeassistant.entity.HaEntitySearchLimit
import com.example.ava.homeassistant.entity.HaEntitySummary
import com.example.ava.homeassistant.entity.search
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.SettingsHorizontalFadeText
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.isDarkModeEnabled

/**
 * Compact inline entity search. Lives in-page (not a nested dialog) so it
 * does not fight existing AlertDialogs. Max [HaEntitySearchLimit] rows.
 */
@Composable
fun HaEntitySearchBox(
    entities: List<HaEntitySummary>,
    query: String,
    loading: Boolean,
    accent: Color,
    filter: HaEntityDomainFilter = HaEntityDomainFilter.All,
    selectedId: String = "",
    listMaxHeight: Dp = 180.dp,
    limit: Int = HaEntitySearchLimit,
    onQueryChange: (String) -> Unit,
    onSelect: (HaEntitySummary) -> Unit,
) {
    val isDark = isDarkModeEnabled()
    val scale = rememberSettingsTextScale().coerceAtMost(1.5f)
    val matches = remember(entities, query, filter, limit) { entities.search(query, filter, limit) }
    val fieldShape = RoundedCornerShape((12f * scale).dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = if (isDark) Color.White else accent,
        unfocusedBorderColor = if (isDark) Color(0xFF3D3D3D) else Color(0xFFE2E8F0),
        focusedContainerColor = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF8FAFC),
        unfocusedContainerColor = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF8FAFC),
        cursorColor = if (isDark) Color.White else accent,
        focusedTextColor = getLabelColor(),
        unfocusedTextColor = getLabelColor(),
        focusedPlaceholderColor = getSettingsDescriptionColor(),
        unfocusedPlaceholderColor = getSettingsDescriptionColor(),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = fieldShape,
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            placeholder = {
                Text(
                    text = stringResource(R.string.settings_ha_entity_search_hint),
                    fontSize = settingsBodyTextSize(),
                )
            },
            leadingIcon = {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size((16f * scale).dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size((16f * scale).dp),
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height((8f * scale).dp))

        if (!loading && matches.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_ha_entity_empty),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(vertical = (6f * scale).dp),
            )
        } else {
            SettingsEdgeFadeScrollColumn(
                maxHeight = listMaxHeight,
                fadeHeight = 18.dp,
                verticalArrangement = Arrangement.spacedBy((4f * scale).dp),
            ) {
                matches.forEach { entity ->
                    val selected = entity.entityId == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape((10f * scale).dp))
                            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (selected) accent.copy(alpha = 0.35f) else Color.Transparent,
                                shape = RoundedCornerShape((10f * scale).dp),
                            )
                            .clickable { onSelect(entity) }
                            .padding(horizontal = (10f * scale).dp, vertical = (7f * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsHorizontalFadeText(
                            text = entity.compactId,
                            modifier = Modifier.weight(1f),
                            fontSize = (12f * scale).sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = getLabelColor(),
                        )
                        if (entity.name.isNotBlank() && !entity.name.equals(entity.entityId, ignoreCase = true)) {
                            Spacer(modifier = Modifier.width((8f * scale).dp))
                            SettingsHorizontalFadeText(
                                text = entity.name,
                                modifier = Modifier.weight(0.7f),
                                fontSize = (11f * scale).sp,
                                color = getSettingsDescriptionColor(),
                            )
                        }
                    }
                }
            }
        }
    }
}