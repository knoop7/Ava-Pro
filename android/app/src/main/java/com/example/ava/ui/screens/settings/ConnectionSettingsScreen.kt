package com.example.ava.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.localllm.LocalLlmManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.LocalLlmPath
import com.example.ava.settings.resolvedPath
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.VoiceSttEngine
import com.example.ava.settings.VoiceSttSettingsStore
import com.example.ava.settings.VoiceTtsEngine
import com.example.ava.settings.VoiceTtsSettingsStore
import androidx.compose.ui.draw.alpha
import com.example.ava.settings.WakeMode
import com.example.ava.settings.haSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.settings.voiceSttSettingsStore
import com.example.ava.settings.voiceTtsSettingsStore
import com.example.ava.ui.AvaToast
import com.example.ava.ui.theme.AccentBrown
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.onboarding.OnboardingPrefs
import com.example.ava.ui.screens.settings.components.ActionDialog
import com.example.ava.ui.screens.settings.components.DialogSettingItem
import com.example.ava.ui.screens.settings.components.IntSetting
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsHorizontalFadeText
import com.example.ava.ui.screens.settings.components.EncryptionKeySetting
import com.example.ava.ui.screens.settings.components.TextSetting
import com.example.ava.ui.screens.settings.components.VoiceDetailScaffold
import com.example.ava.ui.screens.settings.components.SettingsSimpleCardVerticalPadding
import com.example.ava.ui.screens.settings.components.VoiceStatsFocus
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsChevronIconSize
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val voiceChannelStore = remember { VoiceChannelSettingsStore(context.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsStateWithLifecycle(true)
    val haSettings = remember { HaSettingsStore(context.haSettingsStore) }
    // uiState is a cold combine flow with a null initial value, so the identity card's
    // fields render blank + dimmed for the first frame(s) of every visit. Seed them from
    // the hot snapshot so the expanded card paints its real values immediately.
    val satelliteSeed = remember {
        VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).getCached()
    }
    var showVoiceChannelOffConfirm by remember { mutableStateOf(false) }

    // Hub overview stats — summary only; Detail pages keep their deep focus sections.
    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_group_connection),
        focus = VoiceStatsFocus.VoiceConfig,
    ) {
        item(key = "connection_identity") {
            ConnectionIdentityPairCard(
                serverName = uiState?.serverName ?: satelliteSeed.name,
                serverPort = uiState?.serverPort ?: satelliteSeed.serverPort,
                encryptionKey = uiState?.encryptionKey ?: satelliteSeed.encryptionKey,
                macAddress = uiState?.macAddress ?: satelliteSeed.macAddress,
                // Values are seeded synchronously and edits confirm through dialogs,
                // so the rows never need the "still loading" dim state.
                enabled = true,
                haSettings = haSettings,
                onSaveName = { name ->
                    coroutineScope.launch { viewModel.saveServerName(name) }
                },
                onSavePort = { port ->
                    coroutineScope.launch { viewModel.saveServerPort(port) }
                },
                onSaveEncryptionKey = { key ->
                    coroutineScope.launch { viewModel.saveEncryptionKey(key) }
                },
                validateName = { viewModel.validateName(it) },
                validatePort = { viewModel.validatePort(it) },
                validateEncryptionKey = { viewModel.validateEncryptionKey(it) },
                onGenerateEncryptionKey = { viewModel.generateEncryptionKey() },
                onRegenerateIdentity = {
                    coroutineScope.launch { viewModel.regenerateEspHomeIdentity() }
                },
                onHaClick = {
                    navController.navigate(Screen.SETTINGS_HA) { launchSingleTop = true }
                },
            )
        }

        item(key = "voice_channel") {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_voice_channel_enabled),
                    subLabel = stringResource(R.string.settings_voice_channel_enabled_desc),
                ) {
                    ModernSwitch(
                        checked = voiceChannelEnabled,
                        enabled = true,
                        onCheckedChange = { value ->
                            if (!value) {
                                showVoiceChannelOffConfirm = true
                            } else {
                                coroutineScope.launch {
                                    voiceChannelStore.enabled.set(true)
                                    VoiceSatelliteService.getInstance()?.applyVoiceChannelChange(true)
                                }
                            }
                        },
                    )
                }

                if (voiceChannelEnabled) {
                    SettingsDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(Screen.SETTINGS_VOICE_WAKE) { launchSingleTop = true }
                            },
                    ) {
                        SettingRow(
                            label = stringResource(R.string.settings_voice_wake_entry_title),
                            subLabel = stringResource(R.string.settings_voice_wake_entry_desc),
                        ) {
                            SettingsChevronIcon(tint = Color(0xFF94A3B8))
                        }
                    }

                    SettingsDivider()
                    // Button-only wake never shows the ripple / captions the accent colors,
                    // so the page has nothing to configure — grey it out rather than let the
                    // user tune colors that never appear.
                    val accentUnused = microphoneState?.wakeMode == WakeMode.BUTTON
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (accentUnused) 0.45f else 1f)
                            .clickable(enabled = !accentUnused) {
                                navController.navigate(Screen.SETTINGS_VOICE_FEEDBACK_ACCENT) {
                                    launchSingleTop = true
                                }
                            },
                    ) {
                        SettingRow(
                            label = stringResource(R.string.settings_voice_feedback_accent_title),
                            subLabel = stringResource(
                                if (accentUnused) {
                                    R.string.settings_voice_feedback_accent_unused_button_mode
                                } else {
                                    R.string.settings_voice_feedback_accent_entry_desc
                                },
                            ),
                        ) {
                            SettingsChevronIcon(tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }

        if (voiceChannelEnabled) {
            item(key = "voice_section_stt") {
                SettingsSectionLabel(stringResource(R.string.settings_voice_section_stt))
            }
            item(key = "voice_microphone_entry") {
                VoiceSettingsEntryRow(
                    label = stringResource(R.string.settings_voice_microphone_entry_title),
                    subLabel = stringResource(R.string.settings_voice_microphone_entry_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_VOICE_MICROPHONE) { launchSingleTop = true }
                    },
                )
            }
            item(key = "voice_print_entry") {
                VoiceSettingsEntryRow(
                    label = stringResource(R.string.settings_voice_print_entry_title),
                    subLabel = stringResource(R.string.settings_voice_print_entry_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_VOICE_PRINT) { launchSingleTop = true }
                    },
                )
            }
            item(key = "voice_section_speech_to_text") {
                SettingsSectionLabel(stringResource(R.string.settings_ha_pipeline_stt))
            }
            item(key = "voice_stt_engine_entry") {
                val sttStore = remember { VoiceSttSettingsStore(context.voiceSttSettingsStore) }
                val sttSeed = remember { sttStore.getCached() }
                val sttEngine by sttStore.engine.collectAsStateWithLifecycle(sttSeed.engine)
                val sttMod = sttEngine != VoiceSttEngine.PIPELINE
                VoiceSettingsEntryRow(
                    label = stringResource(R.string.settings_voice_stt_entry_title),
                    subLabel = stringResource(R.string.settings_voice_stt_entry_desc),
                    badge = stringResource(
                        if (sttMod) R.string.voice_engine_hub_badge_mod
                        else R.string.voice_engine_hub_badge_ha,
                    ),
                    badgeActive = true,
                    onClick = {
                        navController.navigate(Screen.SETTINGS_VOICE_STT) { launchSingleTop = true }
                    },
                )
            }
            item(key = "voice_section_conversation") {
                SettingsSectionLabel(stringResource(R.string.settings_voice_section_conversation))
            }
            item(key = "voice_llm_engine_entry") {
                val llm = remember { LocalLlmManager.getInstance(context) }
                val llmSeed = remember { llm.settingsStore.getCached() }
                val llmSettings by llm.settingsStore.getFlow().collectAsStateWithLifecycle(llmSeed)
                val remotePath = llmSettings.resolvedPath() == LocalLlmPath.REMOTE
                VoiceSettingsEntryRow(
                    label = stringResource(R.string.local_llm_title),
                    subLabel = stringResource(R.string.local_llm_entry_desc),
                    badge = stringResource(
                        if (remotePath) R.string.local_llm_hub_badge_cloud
                        else R.string.voice_engine_hub_badge_ha,
                    ),
                    badgeActive = true,
                    onClick = {
                        navController.navigate(Screen.SETTINGS_HA_LOCAL_LLM) { launchSingleTop = true }
                    },
                )
            }
            item(key = "voice_section_tts") {
                SettingsSectionLabel(stringResource(R.string.settings_voice_section_tts))
            }
            item(key = "voice_tts_card") {
                val ttsStore = remember { VoiceTtsSettingsStore(context.voiceTtsSettingsStore) }
                val ttsSeed = remember { ttsStore.getCached() }
                val ttsEngine by ttsStore.engine.collectAsStateWithLifecycle(ttsSeed.engine)
                val ttsMod = ttsEngine != VoiceTtsEngine.PIPELINE
                SimpleCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(Screen.SETTINGS_VOICE_TTS) { launchSingleTop = true }
                            },
                    ) {
                        SettingRow(
                            label = stringResource(R.string.settings_voice_tts_entry_title),
                            subLabel = stringResource(R.string.settings_voice_tts_entry_desc),
                        ) {
                            IdentityStatusCapsule(
                                text = stringResource(
                                    if (ttsMod) R.string.voice_engine_hub_badge_mod
                                    else R.string.voice_engine_hub_badge_ha,
                                ),
                                active = true,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            SettingsChevronIcon(tint = Color(0xFF94A3B8))
                        }
                    }
                    SettingsDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(Screen.SETTINGS_VOICE_STREAMING_TTS) { launchSingleTop = true }
                            },
                    ) {
                        SettingRow(
                            label = stringResource(R.string.settings_voice_streaming_tts_entry_title),
                            subLabel = stringResource(R.string.settings_voice_streaming_tts_entry_desc),
                        ) {
                            SettingsChevronIcon(tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
            item(key = "voice_section_ambient") {
                SettingsCaptionDivider(stringResource(R.string.settings_voice_section_ambient))
            }
            item(key = "voice_audio_event_entry") {
                VoiceSettingsEntryRow(
                    label = stringResource(R.string.settings_voice_audio_event_entry_title),
                    subLabel = stringResource(R.string.settings_voice_audio_event_entry_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_VOICE_AUDIO_EVENT) { launchSingleTop = true }
                    },
                )
            }
        }
    }

    if (showVoiceChannelOffConfirm) {
        BasicAlertDialog(onDismissRequest = { showVoiceChannelOffConfirm = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = getDialogBackground(),
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.settings_voice_channel_off_confirm_title),
                        color = getLabelColor(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_voice_channel_off_confirm_message),
                        color = getLabelColor(),
                        fontSize = settingsBodyTextSize(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showVoiceChannelOffConfirm = false }) {
                            Text(
                                text = stringResource(R.string.settings_voice_channel_off_cancel),
                                color = getAccentColor(),
                                fontSize = settingsTitleTextSize(),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        TextButton(onClick = {
                            showVoiceChannelOffConfirm = false
                            coroutineScope.launch {
                                voiceChannelStore.enabled.set(false)
                                VoiceSatelliteService.getInstance()?.applyVoiceChannelChange(false)
                                AvaToast.show(
                                    context,
                                    R.string.settings_voice_channel_off_syncing,
                                    tag = AvaToast.HA_SYNC_TAG,
                                )
                            }
                        }) {
                            Text(
                                text = stringResource(R.string.settings_voice_channel_off_confirm),
                                color = Color(0xFFEF4444),
                                fontSize = settingsTitleTextSize(),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionIdentityPairCard(
    serverName: String,
    serverPort: Int?,
    encryptionKey: String,
    macAddress: String,
    enabled: Boolean,
    haSettings: HaSettingsStore,
    onSaveName: (String) -> Unit,
    onSavePort: (Int?) -> Unit,
    onSaveEncryptionKey: (String) -> Unit,
    validateName: (String) -> String?,
    validatePort: (Int?) -> String?,
    validateEncryptionKey: (String) -> String?,
    onGenerateEncryptionKey: () -> String,
    onRegenerateIdentity: () -> Unit,
    onHaClick: () -> Unit,
) {
    val isDark = isDarkModeEnabled()
    val scale = rememberSettingsTextScale()
    val context = LocalContext.current
    val foldScope = rememberCoroutineScope()
    val innerBgDark = Color(0xFF2A2A2A)
    val espInnerBg = if (isDark) innerBgDark else HaOfficialBlue.copy(alpha = 0.028f)
    val haInnerBg = if (isDark) innerBgDark else HaOfficialBlue.copy(alpha = 0.038f)
    val innerShape = RoundedCornerShape(18.dp)
    // First frame must already match the persisted state: an async read here used to paint
    // a skeleton (plus collapsed/signed-out defaults) on every re-entry before swapping in
    // the real card one frame later.
    val initialSnap = remember { haSettings.getCached() }
    var identityExpanded by remember {
        mutableStateOf(
            initialSnap.esphomeIdentityExpanded ?: OnboardingPrefs.isBrandNewFirstUse(context),
        )
    }
    var haSignedIn by remember {
        mutableStateOf(initialSnap.serverUrl.isNotBlank() && initialSnap.accessToken.isNotBlank())
    }

    LaunchedEffect(haSettings) {
        // Persist the first-use fold decision so later visits read a stable value.
        if (haSettings.get().esphomeIdentityExpanded == null) {
            haSettings.esphomeIdentityExpanded.set(identityExpanded)
        }
        combine(haSettings.serverUrl, haSettings.accessToken) { url, token ->
            url.isNotBlank() && token.isNotBlank()
        }.collect { haSignedIn = it }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSimpleCardVerticalPadding),
        shape = RoundedCornerShape(32.dp),
        color = getDialogBackground(),
        shadowElevation = if (isDark) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(innerShape)
                    .background(espInnerBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                IdentityBrandHead(
                    iconRes = R.drawable.mdi_esphome,
                    title = stringResource(R.string.settings_esphome_entry_title),
                    subtitle = stringResource(R.string.settings_esphome_entry_desc),
                    scale = scale,
                    expanded = identityExpanded,
                    onToggle = {
                        val next = !identityExpanded
                        identityExpanded = next
                        foldScope.launch {
                            haSettings.esphomeIdentityExpanded.set(next)
                        }
                    },
                )
                if (identityExpanded) {
                    TextSetting(
                        name = stringResource(R.string.label_voice_satellite_name),
                        value = serverName,
                        enabled = enabled,
                        validation = validateName,
                        onConfirmRequest = onSaveName,
                    )
                    SettingsDivider()
                    IntSetting(
                        name = stringResource(R.string.label_voice_satellite_port),
                        dialogHint = stringResource(R.string.settings_port_description),
                        value = serverPort,
                        enabled = enabled,
                        validation = validatePort,
                        onConfirmRequest = onSavePort,
                    )
                    SettingsDivider()
                    EncryptionKeySetting(
                        name = stringResource(R.string.label_esphome_encryption_key),
                        dialogHint = stringResource(R.string.settings_esphome_encryption_key_hint),
                        value = encryptionKey,
                        enabled = enabled,
                        validation = validateEncryptionKey,
                        onGenerate = onGenerateEncryptionKey,
                        onConfirmRequest = onSaveEncryptionKey,
                    )
                    SettingsDivider()
                    // Shows the MAC HA keys this node on; nothing else in the UI does.
                    DialogSettingItem(
                        name = stringResource(R.string.label_esphome_identity),
                        description = stringResource(R.string.settings_esphome_identity_desc),
                        value = macAddress.lowercase(),
                        enabled = enabled,
                    ) {
                        ActionDialog(
                            title = stringResource(R.string.label_esphome_identity),
                            description = stringResource(R.string.settings_esphome_identity_regenerate_hint),
                            confirmLabel = stringResource(R.string.settings_esphome_identity_regenerate),
                            onConfirmRequest = onRegenerateIdentity,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(innerShape)
                    .background(haInnerBg)
                    .clickable(onClick = onHaClick)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdentityBrandTile(
                    iconRes = R.drawable.mdi_home_assistant,
                    scale = scale,
                )
                Spacer(modifier = Modifier.width((12f * scale).dp))
                Column(modifier = Modifier.weight(1f)) {
                    SettingsHorizontalFadeText(
                        text = stringResource(R.string.settings_ha_entry_title),
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                    )
                    Spacer(modifier = Modifier.height((1f * scale).dp))
                    SettingsHorizontalFadeText(
                        text = if (haSignedIn) {
                            stringResource(R.string.settings_ha_entry_desc_connected)
                        } else {
                            stringResource(R.string.settings_ha_entry_desc)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                    )
                }
                Spacer(modifier = Modifier.width((8f * scale).dp))
                IdentityStatusCapsule(
                    text = stringResource(
                        if (haSignedIn) R.string.settings_ha_entry_on
                        else R.string.settings_ha_entry_optional,
                    ),
                    active = haSignedIn,
                )
                Spacer(modifier = Modifier.width((6f * scale).dp))
                SettingsChevronIcon(tint = if (isDark) Color(0xFF4B5563) else Color(0xFFD1D5DB))
            }
        }
    }
}

@Composable
private fun IdentityBrandHead(
    iconRes: Int,
    title: String,
    subtitle: String,
    scale: Float,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val isDark = isDarkModeEnabled()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdentityBrandTile(iconRes = iconRes, scale = scale)
        Spacer(modifier = Modifier.width((12f * scale).dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
            )
            Spacer(modifier = Modifier.height((1f * scale).dp))
            SettingsHorizontalFadeText(
                text = subtitle,
                modifier = Modifier.fillMaxWidth(),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        if (onToggle != null) {
            Spacer(modifier = Modifier.width((8f * scale).dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (isDark) Color(0xFF4B5563) else Color(0xFFD1D5DB),
                modifier = Modifier
                    .size(settingsChevronIconSize())
                    .graphicsLayer {
                        rotationZ = if (expanded) 180f else 0f
                    },
            )
        }
    }
}

@Composable
private fun IdentityBrandTile(
    iconRes: Int,
    scale: Float,
) {
    val isDark = isDarkModeEnabled()
    val shape = RoundedCornerShape((13f * scale).dp)
    val iconTint = if (isDark) AccentBrown else HaOfficialBlue
    Box(
        modifier = Modifier
            .size((40f * scale).dp)
            .clip(shape)
            .background(if (isDark) Color.Transparent else HaOfficialBlue.copy(alpha = 0.10f))
            .border(
                width = 1.dp,
                color = if (isDark) AccentBrown.copy(alpha = 0.40f) else Color.Transparent,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size((22f * scale).dp),
        )
    }
}

