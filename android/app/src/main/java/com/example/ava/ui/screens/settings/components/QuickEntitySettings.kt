package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.preference.PreferenceManager
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.res.painterResource
import com.example.ava.R
import com.example.ava.services.QuickEntityOverlayService
import com.example.ava.ui.screens.settings.restartVoiceSatelliteServiceIfRunning
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySlot
import com.example.ava.settings.SidebarItemKey
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.ui.AvaToast
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.settings.SimpleCard
import com.example.ava.ui.screens.settings.SettingRow
import com.example.ava.ui.screens.settings.ModernSwitch
import com.example.ava.ui.screens.settings.SettingsDivider
import com.example.ava.ui.screens.settings.SettingsInsetWell
import com.example.ava.ui.screens.settings.SettingsWellDivider
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import kotlinx.coroutines.launch

private val LabelColorLight = Color(0xFF334155)
private val LabelColorDark = Color(0xFFF1F5F9)
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
fun QuickEntitySettingsCard(
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onEditSlot: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val quickEntityStore = remember { context.quickEntitySettingsStore }
    val sidebarStore = remember { SidebarSettingsStore(context.sidebarSettingsStore) }
    val isDark = isDarkMode()
    val labelColor = if (isDark) LabelColorDark else LabelColorLight
    val slotBackground = if (isDark) SlotBackgroundDark else SlotBackgroundLight
    val slotBorder = if (isDark) SlotBorderDark else SlotBorderLight
    val accentColor = getAccentColor()
    var quickEntityEnabled by remember { mutableStateOf(false) }
    var haSlotsEnabled by remember { mutableStateOf(false) }
    var slots by remember { mutableStateOf(List(6) { QuickEntitySlot() }) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingSlotIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        quickEntityStore.data.collect { settings ->
            quickEntityEnabled = settings.enableQuickEntity
            haSlotsEnabled = settings.enableHaSlots
            slots = settings.slots
        }
    }

    SimpleCard {
        val quickEntityMasterRow: @Composable () -> Unit = {
            SettingRow(
                label = stringResource(R.string.settings_quick_entity),
                subLabel = stringResource(R.string.settings_quick_entity_desc)
            ) {
                ModernSwitch(
                    checked = quickEntityEnabled,
                    enabled = enabled,
                    onCheckedChange = { newValue ->
                        quickEntityEnabled = newValue
                        coroutineScope.launch {
                            quickEntityStore.updateData { 
                                it.copy(
                                    enableQuickEntity = newValue,
                                    enableQuickEntityDisplay = false
                                ) 
                            }
                            if (newValue) {
                                sidebarStore.offerHomeEntry(SidebarItemKey.QuickEntity)
                            } else {
                                QuickEntityOverlayService.hide(context)
                            }
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    }
                )
            }
        }

        if (quickEntityEnabled) {
            SettingsInsetWell {
                quickEntityMasterRow()

                SettingsWellDivider()

                SettingRow(
                    label = stringResource(R.string.settings_quick_entity_ha_slots),
                    subLabel = stringResource(R.string.settings_quick_entity_ha_slots_desc)
                ) {
                    ModernSwitch(
                        checked = haSlotsEnabled,
                        enabled = enabled,
                        onCheckedChange = { newValue ->
                            haSlotsEnabled = newValue
                            coroutineScope.launch {
                                quickEntityStore.updateData { 
                                    it.copy(enableHaSlots = newValue) 
                                }
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                    )
                }
            }
        } else {
            quickEntityMasterRow()
        }

        if (quickEntityEnabled) {
            SettingsDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.settings_quick_entity_configure),
                fontSize = settingsBodyTextSize(),
                fontWeight = FontWeight.Medium,
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(bottom = 8.dp)
            )


            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            
            Row(
                horizontalArrangement = if (isLandscape) Arrangement.spacedBy(12.dp) else Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                slots.take(6).forEachIndexed { index, slot ->
                    QuickEntitySlotItem(
                        slot = slot,
                        index = index,
                        onClick = {
                            if (onEditSlot != null) {
                                onEditSlot(index)
                            } else {
                                editingSlotIndex = index
                                showEditDialog = true
                            }
                        },
                        slotBackground = slotBackground,
                        slotBorder = slotBorder,
                        labelColor = labelColor,
                        accentColor = accentColor,
                        isLandscape = isLandscape
                    )
                }
            }
        }
    }

    // Smart AOD only applies to the Quick Entity panel — hide when panel is off.
    if (quickEntityEnabled) {
        QuickEntitySmartAodSettingsCard(
            enabled = enabled,
            coroutineScope = coroutineScope,
        )
    }

    if (showEditDialog) {
        QuickEntityEditDialog(
            slot = slots.getOrElse(editingSlotIndex) { QuickEntitySlot() },
            slotIndex = editingSlotIndex,
            onDismiss = { showEditDialog = false },
            onConfirm = { newSlot ->
                val newSlots = slots.toMutableList()
                newSlots[editingSlotIndex] = newSlot
                slots = newSlots
                coroutineScope.launch {
                    quickEntityStore.updateData { it.copy(slots = newSlots) }
                    QuickEntityOverlayService.updateSlots(context)
                    restartVoiceSatelliteServiceIfRunning()
                }
                showEditDialog = false
            }
        )
    }
}

@Composable
fun QuickEntitySmartAodSettingsCard(
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val quickEntityStore = remember { context.quickEntitySettingsStore }
    var aodEnabled by remember { mutableStateOf(false) }
    var aodTimeout by remember { mutableIntStateOf(60) }
    var aodMaskPercent by remember { mutableIntStateOf(100) }

    LaunchedEffect(Unit) {
        quickEntityStore.data.collect { settings ->
            aodEnabled = settings.smartAodEnabled
            aodTimeout = settings.smartAodTimeoutSeconds.coerceIn(10, 3600)
            aodMaskPercent = settings.smartAodMaskPercent.coerceIn(5, 100)
        }
    }

    SimpleCard {
        SettingRow(
            label = stringResource(R.string.settings_quick_entity_smart_aod),
            subLabel = stringResource(R.string.settings_quick_entity_smart_aod_desc),
        ) {
            ModernSwitch(
                checked = aodEnabled,
                enabled = enabled,
                onCheckedChange = { newValue ->
                    aodEnabled = newValue
                    coroutineScope.launch {
                        quickEntityStore.updateData { it.copy(smartAodEnabled = newValue) }
                    }
                },
            )
        }

        if (aodEnabled) {
            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            IntSetting(
                name = stringResource(R.string.settings_quick_entity_smart_aod_timeout),
                description = stringResource(R.string.settings_quick_entity_smart_aod_timeout_desc),
                value = aodTimeout,
                enabled = enabled,
                validation = { value ->
                    if (value != null && value in 10..3600) {
                        null
                    } else {
                        context.getString(R.string.validation_range, 10, 3600)
                    }
                },
                onConfirmRequest = {
                    if (it != null) {
                        val snapped = it.coerceIn(10, 3600)
                        aodTimeout = snapped
                        coroutineScope.launch {
                            quickEntityStore.updateData {
                                it.copy(smartAodTimeoutSeconds = snapped)
                            }
                        }
                    }
                },
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            var maskSlider by remember(aodMaskPercent) { mutableFloatStateOf(aodMaskPercent.toFloat()) }
            SettingSliderLabelRow(
                title = stringResource(R.string.settings_quick_entity_smart_aod_mask),
                description = stringResource(R.string.settings_quick_entity_smart_aod_mask_desc),
                badgeText = "${maskSlider.toInt()}%",
            )
            TickSlider(
                value = maskSlider,
                onValueChange = { maskSlider = it },
                onValueChangeFinished = {
                    val snapped = maskSlider.toInt().coerceIn(5, 100)
                    maskSlider = snapped.toFloat()
                    aodMaskPercent = snapped
                    coroutineScope.launch {
                        quickEntityStore.updateData {
                            it.copy(smartAodMaskPercent = snapped)
                        }
                    }
                    val threshold = QuickEntitySettings.SMART_AOD_TAP_THROUGH_MAX_PERCENT
                    val toastRes = if (snapped <= threshold) {
                        R.string.settings_quick_entity_smart_aod_tap_through_toast
                    } else {
                        R.string.settings_quick_entity_smart_aod_wake_first_toast
                    }
                    AvaToast.show(
                        context,
                        context.getString(toastRes, threshold),
                        tag = "quick_entity_aod_mask",
                    )
                },
                enabled = enabled,
                valueRange = 5f..100f,
                steps = 94,
                colors = SliderDefaults.colors(
                    thumbColor = getAccentColor(),
                    activeTrackColor = getAccentColor(),
                    inactiveTrackColor = getSliderInactiveColor(),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun QuickEntitySlotItem(
    slot: QuickEntitySlot,
    index: Int,
    onClick: () -> Unit,
    slotBackground: Color,
    slotBorder: Color,
    labelColor: Color,
    accentColor: Color,
    isLandscape: Boolean = false
) {
    val isEmpty = slot.entityId.isEmpty()
    val iconResId = com.example.ava.ui.components.MdiIconMapper.getIconResId(slot.icon)
    
    val slotSize = if (isLandscape) 56.dp else 44.dp
    val iconSize = if (isLandscape) 24.dp else 20.dp
    val cornerRadius = if (isLandscape) 12.dp else 10.dp

    Box(
        modifier = Modifier
            .size(slotSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (isEmpty) slotBackground else accentColor.copy(alpha = 0.1f))
            .border(1.dp, if (isEmpty) slotBorder else accentColor.copy(alpha = 0.3f), RoundedCornerShape(cornerRadius))
            .clickable { onClick() },
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


@Composable
private fun QuickEntityEditDialog(
    slot: QuickEntitySlot,
    slotIndex: Int,
    onDismiss: () -> Unit,
    onConfirm: (QuickEntitySlot) -> Unit
) {
    val isDark = isDarkMode()
    val labelColor = if (isDark) LabelColorDark else LabelColorLight
    val slotBackground = if (isDark) SlotBackgroundDark else SlotBackgroundLight
    val slotBorder = if (isDark) SlotBorderDark else SlotBorderLight
    val accentColor = getAccentColor()
    
    var entityId by remember { mutableStateOf(slot.entityId) }
    var icon by remember { mutableStateOf(slot.icon.ifEmpty { "mdi:home-assistant" }) }
    var selectedColor by remember { mutableStateOf(slot.color) }
    
    val availableIcons = com.example.ava.ui.components.MdiIconMapper.iconMap.toList()
    val availableColors = com.example.ava.ui.components.MdiColorMapper.presetColors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_quick_entity_slot, slotIndex + 1),
                fontWeight = FontWeight.Bold,
                fontSize = settingsTitleTextSize()
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_quick_entity_id),
                        fontSize = settingsBodyTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = getSettingsDescriptionColor()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BasicTextField(
                        value = entityId,
                        onValueChange = { entityId = it },
                        cursorBrush = SolidColor(getAccentColor()),
                        textStyle = TextStyle(
                            fontSize = settingsTitleTextSize(),
                            color = labelColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(slotBackground, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (entityId.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_quick_entity_id_hint),
                                        color = getSettingsDescriptionColor(),
                                        fontSize = settingsTitleTextSize()
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.settings_quick_entity_icon),
                        fontSize = settingsBodyTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = getSettingsDescriptionColor()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .height(200.dp)
                            .fillMaxWidth()
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                        items(availableIcons) { (iconKey, iconResId) ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (icon == iconKey) accentColor.copy(alpha = 0.15f)
                                        else slotBackground
                                    )
                                    .border(
                                        width = if (icon == iconKey) 2.dp else 1.dp,
                                        color = if (icon == iconKey) accentColor else slotBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { icon = iconKey },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(iconResId),
                                    contentDescription = iconKey,
                                    tint = if (icon == iconKey) accentColor else labelColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        }
                    }
                }

                Column {
                    Text(
                        text = stringResource(R.string.settings_quick_entity_color),
                        fontSize = settingsBodyTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = getSettingsDescriptionColor()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableColors.forEach { (colorKey, tileColor) ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(tileColor.topColor))
                                    .border(
                                        width = if (selectedColor == colorKey) 3.dp else 0.dp,
                                        color = if (selectedColor == colorKey) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    .clickable { selectedColor = if (selectedColor == colorKey) "" else colorKey },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedColor == colorKey) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }
                    if (selectedColor.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_quick_entity_color_auto),
                            fontSize = 10.sp,
                            color = getSettingsDescriptionColor(),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val autoEntityType = when {
                        entityId.startsWith("switch.") -> "switch"
                        entityId.startsWith("light.") -> "light"
                        entityId.startsWith("button.") -> "button"
                        entityId.startsWith("sensor.") -> "sensor"
                        entityId.startsWith("binary_sensor.") -> "sensor"
                        entityId.startsWith("input_boolean.") -> "switch"
                        entityId.startsWith("fan.") -> "switch"
                        entityId.startsWith("cover.") -> "switch"
                        entityId.startsWith("script.") -> "button"
                        entityId.startsWith("scene.") -> "button"
                        entityId.startsWith("automation.") -> "switch"
                        entityId.startsWith("timer.") -> "timer"
                        entityId.startsWith("camera.") -> "camera"
                        else -> "switch"
                    }
                    onConfirm(QuickEntitySlot(
                        entityId = entityId,
                        entityType = autoEntityType,
                        icon = icon,
                        label = "",
                        size = slot.size,
                        color = selectedColor,
                        cameraPanX = if (slot.entityId == entityId) slot.cameraPanX else 0.5f,
                        cameraPanY = if (slot.entityId == entityId) slot.cameraPanY else 0.5f,
                        cameraZoom = if (slot.entityId == entityId) slot.cameraZoom else 1f,
                    ))
                }
            ) {
                Text(
                    text = stringResource(R.string.settings_quick_entity_save),
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.label_cancel),
                    color = getSettingsDescriptionColor()
                )
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
