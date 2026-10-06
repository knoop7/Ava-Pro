package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.DawnEntitySlot
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.ui.components.MdiIconMapper
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.settings.ModernSwitch
import com.example.ava.ui.screens.settings.SettingRow
import com.example.ava.ui.screens.settings.SettingsDivider
import com.example.ava.ui.screens.settings.SettingsInsetWell
import com.example.ava.ui.screens.settings.SettingsWellDivider
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.restartVoiceSatelliteServiceIfRunning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val SubLabelColor = Color(0xFF94A3B8)
private val SlotBackgroundLight = Color(0xFFF9FAFB)
private val SlotBackgroundDark = Color(0xFF2D2D2D)
private val SlotBorderLight = Color(0xFFF1F5F9)
private val SlotBorderDark = Color(0xFF3D3D3D)

@Composable
private fun isDarkMode(): Boolean {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            com.example.ava.ui.screens.home.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
    }
    val isDarkMode by rememberBooleanPreference(
        prefs,
        com.example.ava.ui.screens.home.KEY_DARK_MODE,
        false
    )
    return isDarkMode
}

@Composable
fun DawnEntitySlotsSettingsSection(
    enabled: Boolean,
    coroutineScope: CoroutineScope,
    onEditSlot: (Int) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ScreensaverSettingsStore(context.screensaverSettingsStore) }
    val isDark = isDarkMode()
    val slotBackground = if (isDark) SlotBackgroundDark else SlotBackgroundLight
    val slotBorder = if (isDark) SlotBorderDark else SlotBorderLight
    val accentColor = getAccentColor()

    var slotsEnabled by remember { mutableStateOf(false) }
    var haSlotsEnabled by remember { mutableStateOf(false) }
    var slots by remember { mutableStateOf(List(4) { DawnEntitySlot() }) }

    LaunchedEffect(Unit) {
        store.getFlow().collect { settings ->
            slotsEnabled = settings.enableDawnEntitySlots
            haSlotsEnabled = settings.enableDawnEntityHaSlots
            slots = (0 until 4).map { index ->
                settings.dawnEntitySlots.getOrElse(index) { DawnEntitySlot() }
            }
        }
    }

    val dawnSlotsMasterRow: @Composable () -> Unit = {
        SettingRow(
            label = stringResource(R.string.settings_dawn_entity_slots),
            subLabel = stringResource(R.string.settings_dawn_entity_slots_desc),
        ) {
            ModernSwitch(
                checked = slotsEnabled,
                enabled = enabled,
                onCheckedChange = { newValue ->
                    slotsEnabled = newValue
                    if (!newValue) haSlotsEnabled = false
                    coroutineScope.launch {
                        store.enableDawnEntitySlots.set(newValue)
                        // Capsules / HA text entities are built from a startup snapshot —
                        // restart so HA entity list matches (entities do not hot-appear).
                        kotlinx.coroutines.delay(100)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                },
            )
        }
    }

    if (slotsEnabled) {
        SettingsInsetWell {
            dawnSlotsMasterRow()

            SettingsWellDivider()

            SettingRow(
                label = stringResource(R.string.settings_dawn_entity_ha_slots),
                subLabel = stringResource(R.string.settings_dawn_entity_ha_slots_desc),
            ) {
                ModernSwitch(
                    checked = haSlotsEnabled,
                    enabled = enabled,
                    onCheckedChange = { newValue ->
                        haSlotsEnabled = newValue
                        coroutineScope.launch {
                            store.enableDawnEntityHaSlots.set(newValue)
                            kotlinx.coroutines.delay(100)
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    },
                )
            }
        }
    } else {
        SettingsDivider()
        dawnSlotsMasterRow()
    }

    if (slotsEnabled) {
        SettingsDivider()
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.settings_dawn_entity_slots_configure),
            fontSize = settingsBodyTextSize(),
            fontWeight = FontWeight.Medium,
            color = getSettingsDescriptionColor(),
            modifier = Modifier.padding(bottom = 14.dp),
        )

        val configuration = LocalConfiguration.current
        val isLandscape =
            configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        Row(
            horizontalArrangement = if (isLandscape) {
                Arrangement.spacedBy(12.dp)
            } else {
                Arrangement.SpaceEvenly
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            slots.take(4).forEachIndexed { index, slot ->
                DawnEntitySlotChip(
                    slot = slot,
                    onClick = { onEditSlot(index) },
                    slotBackground = slotBackground,
                    slotBorder = slotBorder,
                    accentColor = accentColor,
                    isLandscape = isLandscape,
                )
            }
        }

        // Breathing room before the next SettingsDivider (screensaver URL row).
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun DawnEntitySlotChip(
    slot: DawnEntitySlot,
    onClick: () -> Unit,
    slotBackground: Color,
    slotBorder: Color,
    accentColor: Color,
    isLandscape: Boolean,
) {
    val isEmpty = slot.entityId.isEmpty()
    val iconResId = MdiIconMapper.getIconResId(slot.icon)
    val slotSize = if (isLandscape) 52.dp else 42.dp
    val iconSize = if (isLandscape) 22.dp else 18.dp
    val cornerRadius = if (isLandscape) 12.dp else 10.dp

    Box(
        modifier = Modifier
            .size(slotSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (isEmpty) slotBackground else accentColor.copy(alpha = 0.1f))
            .border(
                1.dp,
                if (isEmpty) slotBorder else accentColor.copy(alpha = 0.3f),
                RoundedCornerShape(cornerRadius),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isEmpty) {
            Icon(
                painter = painterResource(R.drawable.mdi_plus),
                contentDescription = null,
                tint = SubLabelColor,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = slot.icon,
                tint = accentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
