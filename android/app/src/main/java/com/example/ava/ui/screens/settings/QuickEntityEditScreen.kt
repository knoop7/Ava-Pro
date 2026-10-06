package com.example.ava.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.homeassistant.entity.HaEntityDomainFilter
import com.example.ava.homeassistant.ui.HaEntityIdField
import com.example.ava.microwakeword.WakeWordProviderFactory
import com.example.ava.services.QuickEntityOverlayService
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.QuickEntitySlot
import com.example.ava.settings.WakeWordEngine
import com.example.ava.settings.activeStopWordForEngine
import com.example.ava.settings.guessDawnSlotIcon
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.ui.components.MdiColorMapper
import com.example.ava.ui.components.MdiIconMapper
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.rememberPaneIsLandscape
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SharedRingtonePickerDialog
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.prefs.rememberBooleanPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LabelColorLight = Color(0xFF334155)
private val LabelColorDark = Color(0xFFF1F5F9)
private val SubLabelColor = Color(0xFF94A3B8)
private val SlotBackgroundLight = Color(0xFFF9FAFB)
private val SlotBackgroundDark = Color(0xFF2D2D2D)
private val SlotBorderLight = Color(0xFFF1F5F9)
private val SlotBorderDark = Color(0xFF3D3D3D)
private val CardBackgroundLight = Color(0xFFFFFFFF)
private val CardBackgroundDark = Color(0xFF1F1F1F)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEntityEditScreen(
    slotIndex: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val quickEntityStore = remember { context.quickEntitySettingsStore }
    val playerSettingsStore = remember { PlayerSettingsStore(context.playerSettingsStore) }
    val coroutineScope = rememberCoroutineScope()
    val isDark = isDarkMode()
    val labelColor = if (isDark) LabelColorDark else LabelColorLight
    val slotBackground = if (isDark) SlotBackgroundDark else SlotBackgroundLight
    val cardBackground = if (isDark) CardBackgroundDark else CardBackgroundLight
    val screenBackground = if (isDark) Color.Black else Color(0xFFF9FAFB)
    val accentColor = getAccentColor()
    val pageHorizontalPadding = settingsListHorizontalPadding()
    val pageVerticalPadding = settingsListVerticalPadding()
    val isLandscape = rememberPaneIsLandscape()

    var entityId by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("mdi:home-assistant") }
    var color by remember { mutableStateOf("") }
    val timerFinishedSound by playerSettingsStore.timerFinishedSound.collectAsState(initial = "asset:///sounds/timer_finished.wav")
    val enableTimerStopButton by playerSettingsStore.enableTimerStopButton.collectAsState(initial = false)
    // The stop-button description quotes the user's actual stop word (the stop
    // slot is configurable); null = stop word off, so the text must not claim it.
    var stopPhrase by remember { mutableStateOf<String?>("Stop") }

    LaunchedEffect(slotIndex) {
        val settings = quickEntityStore.data.first()
        val slot = settings.slots.getOrElse(slotIndex) { QuickEntitySlot() }
        entityId = slot.entityId
        icon = slot.icon.ifEmpty { "mdi:home-assistant" }
        color = slot.color
    }

    LaunchedEffect(Unit) {
        val micSettings = context.microphoneSettingsStore.data.first()
        val engine = micSettings.wakeWordEngine
        stopPhrase = when (val setting = micSettings.activeStopWordForEngine(engine)) {
            MicrophoneSettings.STOP_WORD_NONE -> null
            MicrophoneSettings.STOP_WORD_BUILTIN -> "Stop"
            else -> withContext(Dispatchers.IO) { resolveStopPhrase(context, engine, setting) }
        }
    }


    fun saveSlot() {

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
        coroutineScope.launch {
            quickEntityStore.updateData { settings ->
                val existing = settings.slots.getOrElse(slotIndex) { QuickEntitySlot() }
                val newSlot = QuickEntitySlot(
                    entityId = entityId,
                    entityType = autoEntityType,
                    icon = icon,
                    label = "",
                    color = color,
                    cameraPanX = if (existing.entityId == entityId) existing.cameraPanX else 0.5f,
                    cameraPanY = if (existing.entityId == entityId) existing.cameraPanY else 0.5f,
                    cameraZoom = if (existing.entityId == entityId) existing.cameraZoom else 1f,
                )
                val newSlots = settings.slots.toMutableList()
                while (newSlots.size <= slotIndex) {
                    newSlots.add(QuickEntitySlot())
                }
                newSlots[slotIndex] = newSlot
                settings.copy(slots = newSlots)
            }
            QuickEntityOverlayService.updateSlots(context)
        }
    }

    val availableIcons = MdiIconMapper.iconMap.entries
        .filter { 
            !it.key.contains("weather") && 
            !it.key.endsWith("-off") && 
            !it.key.endsWith("-open") && 
            !it.key.endsWith("-closed") &&
            it.key != "mdi:lightbulb-off"
        }
        .map { it.key to it.value }

    ImmersiveMode(isLandscape = isLandscape)

    Scaffold(
        contentWindowInsets = avaContentWindowInsets(isLandscape),
        topBar = {
            SettingsHeaderBar(
                title = stringResource(R.string.settings_quick_entity_slot, slotIndex + 1),
                titleColor = labelColor,
                containerColor = screenBackground,
                onBack = onBack,
                isLandscape = isLandscape,
                windowInsets = avaTopBarWindowInsets(isLandscape)
            )
        },
        containerColor = screenBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageHorizontalPadding, vertical = pageVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_quick_entity_id),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = labelColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_quick_entity_supported_types),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = 0.dp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HaEntityIdField(
                        value = entityId,
                        onValueChange = {
                            val previous = entityId.trim()
                            entityId = it
                            val trimmed = it.trim()
                            if (trimmed.isEmpty()) {
                                icon = "mdi:home-assistant"
                            } else if (trimmed != previous) {
                                icon = guessDawnSlotIcon(trimmed)
                            }
                            saveSlot()
                        },
                        hint = stringResource(R.string.settings_quick_entity_id_hint),
                        pickerTitle = stringResource(R.string.settings_quick_entity_id),
                        domainFilter = HaEntityDomainFilter.QuickEntity,
                        textColor = labelColor,
                        hintColor = getSettingsDescriptionColor(),
                        background = slotBackground,
                    )

                    if (entityId.trim().startsWith("timer.")) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = if (isDark) SlotBorderDark else SlotBorderLight)
                        TimerFinishedSoundItem(
                            soundUri = timerFinishedSound,
                            enabled = true,
                            context = context,
                            onSoundSelected = { uri ->
                                coroutineScope.launch {
                                    playerSettingsStore.timerFinishedSound.set(uri)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = if (isDark) SlotBorderDark else SlotBorderLight)
                        TimerStopButtonSetting(
                            enabled = enableTimerStopButton,
                            stopPhrase = stopPhrase,
                            onToggle = { enabled ->
                                coroutineScope.launch {
                                    playerSettingsStore.enableTimerStopButton.set(enabled)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            }
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_quick_entity_icon),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = labelColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(240.dp)
                    ) {
                        items(availableIcons) { (iconKey, iconResId) ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (icon == iconKey) accentColor.copy(alpha = 0.15f)
                                        else slotBackground
                                    )
                                    .border(
                                        width = if (icon == iconKey) 2.dp else 0.dp,
                                        color = if (icon == iconKey) accentColor else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        icon = iconKey
                                        saveSlot()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(iconResId),
                                    contentDescription = iconKey,
                                    tint = if (icon == iconKey) accentColor else labelColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_quick_entity_color),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = labelColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (color.isEmpty()) accentColor.copy(alpha = 0.1f) else slotBackground)
                            .border(
                                width = if (color.isEmpty()) 2.dp else 0.dp,
                                color = if (color.isEmpty()) accentColor else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                color = ""
                                saveSlot()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFFFD60A),
                                            Color(0xFF34C759),
                                            Color(0xFF007AFF),
                                            Color(0xFFFF2D55)
                                        )
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_quick_entity_color_auto),
                            fontSize = settingsTitleTextSize(),
                            color = labelColor
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    var showCustomColorDialog by remember { mutableStateOf(false) }
                    var selectedHue by remember { mutableStateOf(0f) }
                    var selectedSaturation by remember { mutableStateOf(1f) }
                    var selectedBrightness by remember { mutableStateOf(1f) }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (color.startsWith("#")) accentColor.copy(alpha = 0.1f) else slotBackground)
                            .border(
                                width = if (color.startsWith("#")) 2.dp else 0.dp,
                                color = if (color.startsWith("#")) accentColor else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { showCustomColorDialog = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (color.startsWith("#")) {
                                        try { Color(android.graphics.Color.parseColor(color)) } 
                                        catch (e: Exception) { Color.Gray }
                                    } else {
                                        Color.Gray
                                    }
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (color.startsWith("#")) stringResource(R.string.settings_quick_entity_color_custom) + " " + color else stringResource(R.string.settings_quick_entity_color_custom),
                            fontSize = settingsTitleTextSize(),
                            color = labelColor
                        )
                    }
                    
                    if (showCustomColorDialog) {
                        val controller = com.github.skydoves.colorpicker.compose.rememberColorPickerController()
                        var hexColor by remember { mutableStateOf("#FF0000") }
                        val isLandscape = rememberPaneIsLandscape()
                        val pickerSize = if (isLandscape) 140.dp else 200.dp
                        
                        AlertDialog(
                            onDismissRequest = { showCustomColorDialog = false },
                            title = { Text(stringResource(R.string.settings_quick_entity_color_custom)) },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    com.github.skydoves.colorpicker.compose.HsvColorPicker(
                                        modifier = Modifier.size(pickerSize),
                                        controller = controller,
                                        onColorChanged = { colorEnvelope ->
                                            hexColor = "#" + colorEnvelope.hexCode.takeLast(6)
                                        }
                                    )
                                    
                                    Spacer(modifier = Modifier.height(10.dp))
                                    
                                    com.github.skydoves.colorpicker.compose.BrightnessSlider(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(26.dp),
                                        controller = controller
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        color = hexColor
                                        saveSlot()
                                        showCustomColorDialog = false
                                    }
                                ) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCustomColorDialog = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(MdiColorMapper.presetColors) { (colorName, tileColor) ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(tileColor.topColor))
                                    .border(
                                        width = if (color == colorName) 3.dp else 0.dp,
                                        color = if (color == colorName) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        color = colorName
                                        saveSlot()
                                    }
                            )
                        }
                    }
                }
            }

            if (entityId.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        entityId = ""
                        icon = "mdi:home-assistant"
                        color = ""
                        saveSlot()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF4444)
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFEF4444))
                    )
                ) {
                    Text(stringResource(R.string.settings_quick_entity_clear_slot))
                }
            }
        }
    }
}

/** Display phrase for a model-based stop slot; falls back to the humanized id. */
private fun resolveStopPhrase(
    context: android.content.Context,
    engine: WakeWordEngine,
    modelId: String,
): String {
    val displayName = runCatching {
        when (engine) {
            WakeWordEngine.MICRO_WAKE_WORD ->
                WakeWordProviderFactory.microWakeWordProvider(context)
                    .getWakeWords().firstOrNull { it.id == modelId }?.wakeWord?.wake_word
            WakeWordEngine.OPEN_WAKE_WORD ->
                WakeWordProviderFactory.vsWakeWordProvider(context)
                    .listModels().firstOrNull { it.id == modelId }?.displayName
        }
    }.getOrNull()
    return displayName?.takeIf { it.isNotBlank() }
        ?: modelId.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun TimerStopButtonSetting(
    enabled: Boolean,
    stopPhrase: String?,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_timer_stop_button),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = if (stopPhrase != null) {
                    stringResource(R.string.settings_timer_stop_button_desc, stopPhrase)
                } else {
                    stringResource(R.string.settings_timer_stop_button_desc_no_stop)
                },
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        ModernSwitch(
            checked = enabled,
            enabled = true,
            onCheckedChange = onToggle
        )
    }
}

@Composable
internal fun TimerFinishedSoundItem(
    soundUri: String,
    enabled: Boolean,
    context: android.content.Context,
    onSoundSelected: (String) -> Unit
) {
    val defaultLabel = stringResource(R.string.timer_sound_default)
    val unknownLabel = stringResource(R.string.sound_unknown)
    val defaultUri = "asset:///sounds/timer_finished.wav"

    var showRingtonePicker by remember { mutableStateOf(false) }
    var externalSoundUri by remember { mutableStateOf<String?>(null) }

    val audioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            externalSoundUri = uri.toString()
        }
    }

    // RingtoneManager hits MediaStore — never on the composition thread. The list is
    // only needed once the picker opens, so load it lazily and off-main.
    var ringtones by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(showRingtonePicker) {
        if (!showRingtonePicker || ringtones != null) return@LaunchedEffect
        ringtones = withContext(Dispatchers.IO) {
            val list = mutableListOf<Pair<String, String>>()
            val seenUris = mutableSetOf<String>()
            list.add(defaultLabel to defaultUri)
            seenUris.add(defaultUri)
            try {
                val manager = android.media.RingtoneManager(context)
                manager.setType(
                    android.media.RingtoneManager.TYPE_NOTIFICATION or
                        android.media.RingtoneManager.TYPE_ALARM
                )
                manager.cursor.use { cursor ->
                    while (cursor.moveToNext()) {
                        val uriStr = manager.getRingtoneUri(cursor.position).toString()
                        if (seenUris.add(uriStr)) {
                            val title = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX)
                                ?: unknownLabel
                            list.add(title to uriStr)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            list
        }
    }

    val soundName by produceState(
        initialValue = when {
            soundUri.isEmpty() || soundUri.startsWith("asset://") -> defaultLabel
            else -> ""
        },
        soundUri, defaultLabel, unknownLabel,
    ) {
        value = when {
            soundUri.isEmpty() || soundUri.startsWith("asset://") -> defaultLabel
            else -> withContext(Dispatchers.IO) {
                try {
                    val uri = android.net.Uri.parse(soundUri)
                    android.media.RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: unknownLabel
                } catch (_: Exception) {
                    unknownLabel
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showRingtonePicker = true }
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.timer_sound),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.timer_sound_desc),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
            Text(
                text = stringResource(R.string.wake_sound_select_prefix) + soundName,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }

    val loadedRingtones = ringtones
    if (showRingtonePicker && loadedRingtones != null) {
        SharedRingtonePickerDialog(
            ringtones = loadedRingtones,
            currentUri = soundUri.ifBlank { defaultUri },
            context = context,
            title = stringResource(R.string.timer_sound_select),
            externalSoundUri = externalSoundUri,
            onExternalSoundUriConsumed = { externalSoundUri = null },
            onDismiss = { showRingtonePicker = false },
            onConfirm = { uri ->
                onSoundSelected(uri.ifBlank { defaultUri })
                showRingtonePicker = false
            },
            onSelectExternal = {
                audioFileLauncher.launch("audio/*")
            }
        )
    }
}
