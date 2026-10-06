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
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.restartVoiceSatelliteServiceIfRunning
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.SimpleClockStatusSlot
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.components.MdiIconMapper
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.settings.ModernSwitch
import com.example.ava.ui.screens.settings.SettingRow
import com.example.ava.ui.screens.settings.SettingsDivider
import com.example.ava.ui.screens.settings.SettingsInsetWell
import com.example.ava.ui.screens.settings.SettingsWellDivider
import com.example.ava.ui.screens.settings.SimpleCard
import com.example.ava.ui.screens.settings.getAccentColor
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

/**
 * Third-level Simple Clock status page — master switch, HA slots, and 3 slot editors.
 */
@Composable
fun SimpleClockStatusSettingsCard(
    enabled: Boolean,
    coroutineScope: CoroutineScope,
    onEditSlot: (Int) -> Unit
) {
    val context = LocalContext.current
    val playerStore = remember { PlayerSettingsStore(context.playerSettingsStore) }
    val isDark = isDarkMode()
    val slotBackground = if (isDark) SlotBackgroundDark else SlotBackgroundLight
    val slotBorder = if (isDark) SlotBorderDark else SlotBorderLight
    val accentColor = getAccentColor()

    var statusEnabled by remember { mutableStateOf(false) }
    var haSlotsEnabled by remember { mutableStateOf(false) }
    var slots by remember { mutableStateOf(List(3) { SimpleClockStatusSlot() }) }

    LaunchedEffect(Unit) {
        playerStore.getFlow().collect { settings ->
            statusEnabled = settings.enableScreensaverStatusSlots
            haSlotsEnabled = settings.enableScreensaverStatusHaSlots
            slots = (0 until 3).map { index ->
                settings.screensaverStatusSlots.getOrElse(index) { SimpleClockStatusSlot() }
            }
        }
    }

    SimpleCard {
        val statusSlotsMasterRow: @Composable () -> Unit = {
            SettingRow(
                label = stringResource(R.string.settings_screensaver_status_slots),
                subLabel = stringResource(R.string.settings_screensaver_status_slots_desc)
            ) {
                ModernSwitch(
                    checked = statusEnabled,
                    enabled = enabled,
                    onCheckedChange = { newValue ->
                        statusEnabled = newValue
                        coroutineScope.launch {
                            playerStore.enableScreensaverStatusSlots.set(newValue)
                            if (!newValue) {
                                playerStore.enableScreensaverStatusHaSlots.set(false)
                                haSlotsEnabled = false
                            }
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    }
                )
            }
        }

        if (statusEnabled) {
            SettingsInsetWell {
                statusSlotsMasterRow()

                SettingsWellDivider()

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_status_ha_slots),
                    subLabel = stringResource(R.string.settings_screensaver_status_ha_slots_desc)
                ) {
                    ModernSwitch(
                        checked = haSlotsEnabled,
                        enabled = enabled,
                        onCheckedChange = { newValue ->
                            haSlotsEnabled = newValue
                            coroutineScope.launch {
                                playerStore.enableScreensaverStatusHaSlots.set(newValue)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                    )
                }
            }
        } else {
            statusSlotsMasterRow()
        }

        if (statusEnabled) {
            SettingsDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.settings_screensaver_status_configure),
                fontSize = settingsBodyTextSize(),
                fontWeight = FontWeight.Medium,
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(bottom = 8.dp)
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
                modifier = Modifier.fillMaxWidth()
            ) {
                slots.take(3).forEachIndexed { index, slot ->
                    SimpleClockStatusSlotItem(
                        slot = slot,
                        onClick = { onEditSlot(index) },
                        slotBackground = slotBackground,
                        slotBorder = slotBorder,
                        accentColor = accentColor,
                        isLandscape = isLandscape
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleClockStatusSlotItem(
    slot: SimpleClockStatusSlot,
    onClick: () -> Unit,
    slotBackground: Color,
    slotBorder: Color,
    accentColor: Color,
    isLandscape: Boolean
) {
    val isEmpty = slot.entityId.isEmpty()
    val iconResId = MdiIconMapper.getIconResId(slot.icon)
    val slotSize = if (isLandscape) 56.dp else 44.dp
    val iconSize = if (isLandscape) 24.dp else 20.dp
    val cornerRadius = if (isLandscape) 12.dp else 10.dp

    Box(
        modifier = Modifier
            .size(slotSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (isEmpty) slotBackground else accentColor.copy(alpha = 0.1f))
            .border(
                1.dp,
                if (isEmpty) slotBorder else accentColor.copy(alpha = 0.3f),
                RoundedCornerShape(cornerRadius)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isEmpty) {
            Icon(
                painter = painterResource(R.drawable.mdi_plus),
                contentDescription = null,
                tint = SubLabelColor,
                modifier = Modifier.size(iconSize)
            )
        } else {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = slot.icon,
                tint = accentColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
