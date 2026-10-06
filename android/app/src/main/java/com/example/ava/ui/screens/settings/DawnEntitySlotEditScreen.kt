package com.example.ava.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.DawnEntitySlot
import com.example.ava.homeassistant.entity.HaEntityDomainFilter
import com.example.ava.homeassistant.ui.HaEntityIdField
import com.example.ava.settings.guessDawnSlotIcon
import com.example.ava.settings.isAllowedDawnSlotEntityId
import com.example.ava.settings.isPlausibleDawnSlotEntityPrefix
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.rememberPaneIsLandscape
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.components.MdiIconMapper
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.launch

private val LabelColorLight = Color(0xFF334155)
private val LabelColorDark = Color(0xFFF1F5F9)
private val SubLabelColor = Color(0xFF94A3B8)
private val SlotBackgroundLight = Color(0xFFF9FAFB)
private val SlotBackgroundDark = Color(0xFF2D2D2D)
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
fun DawnEntitySlotEditScreen(
    slotIndex: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ScreensaverSettingsStore(context.screensaverSettingsStore) }
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
    var label by remember { mutableStateOf("") }

    LaunchedEffect(slotIndex) {
        val settings = store.get()
        val slot = settings.dawnEntitySlots.getOrElse(slotIndex) { DawnEntitySlot() }
        entityId = slot.entityId
        icon = slot.icon.ifEmpty { "mdi:home-assistant" }
        label = slot.label
    }

    fun saveSlot() {
        val trimmedId = entityId.trim()
        // Reject domains outside the allow-list so person/climate… never reach the screensaver.
        if (trimmedId.isNotEmpty() && !isAllowedDawnSlotEntityId(trimmedId)) {
            return
        }
        val newSlot = if (trimmedId.isEmpty()) {
            DawnEntitySlot()
        } else {
            DawnEntitySlot(
                entityId = trimmedId,
                icon = icon,
                label = label.trim()
            )
        }
        coroutineScope.launch {
            store.updateDawnEntitySlot(slotIndex, newSlot)
            restartVoiceSatelliteServiceIfRunning()
            com.example.ava.services.ScreensaverWebViewService.pushDawnSlotsFromSettings(context)
        }
    }

    val availableIcons = remember {
        MdiIconMapper.iconMap.entries
            .filter {
                !it.key.contains("weather") &&
                    !it.key.endsWith("-off") &&
                    !it.key.endsWith("-open") &&
                    !it.key.endsWith("-closed") &&
                    it.key != "mdi:lightbulb-off"
            }
            .map { it.key to it.value }
    }

    BackHandler(onBack = onBack)
    ImmersiveMode(isLandscape = isLandscape)

    Scaffold(
        contentWindowInsets = avaContentWindowInsets(isLandscape),
        topBar = {
            SettingsHeaderBar(
                title = stringResource(R.string.settings_dawn_entity_slot, slotIndex + 1),
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
                        text = stringResource(R.string.settings_dawn_entity_slot_entity),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = labelColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_dawn_entity_slot_entity_hint),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Red only for clearly wrong domains; typing "sen…" / "binary_…" is still OK.
                    val entityInvalid = entityId.isNotBlank() &&
                        !isAllowedDawnSlotEntityId(entityId) &&
                        !isPlausibleDawnSlotEntityPrefix(entityId)
                    HaEntityIdField(
                        value = entityId,
                        onValueChange = {
                            val previous = entityId.trim()
                            entityId = it
                            val trimmed = it.trim()
                            // Re-guess icon when the entity identity changes (not on every keystroke
                            // of an already-matched id). Manual icon picks for the same id are kept
                            // until the id itself changes.
                            if (trimmed.isEmpty()) {
                                icon = "mdi:home-assistant"
                            } else if (trimmed != previous && isAllowedDawnSlotEntityId(trimmed)) {
                                icon = guessDawnSlotIcon(trimmed)
                            }
                            saveSlot()
                        },
                        hint = stringResource(R.string.settings_dawn_entity_slot_entity_hint),
                        pickerTitle = stringResource(R.string.settings_dawn_entity_slot_entity),
                        domainFilter = HaEntityDomainFilter.DawnSlot,
                        textColor = labelColor,
                        hintColor = getSettingsDescriptionColor(),
                        background = slotBackground,
                        isInvalid = entityInvalid,
                    )
                    if (entityInvalid) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.settings_dawn_entity_slot_entity_hint),
                            fontSize = settingsBodyTextSize(),
                            color = Color(0xFFDC2626)
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
                        text = stringResource(R.string.settings_dawn_entity_slot_label),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = labelColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_dawn_entity_slot_label_hint),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BasicTextField(
                        value = label,
                        onValueChange = {
                            label = it
                            saveSlot()
                        },
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
                                if (label.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_dawn_entity_slot_label_hint),
                                        color = getSettingsDescriptionColor(),
                                        fontSize = settingsTitleTextSize()
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_dawn_entity_slot_icon),
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
        }
    }
}
