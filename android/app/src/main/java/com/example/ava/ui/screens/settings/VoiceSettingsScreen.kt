package com.example.ava.ui.screens.settings

import android.media.MediaRecorder
import androidx.annotation.DrawableRes
import com.example.ava.ui.AvaToast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.localllm.LocalLlmManager
import com.example.ava.settings.LocalLlmPath
import com.example.ava.settings.resolvedPath
import com.example.ava.audio.AmbientAutoGain
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.audio.DeviceAudioProfile
import com.example.ava.detection.AudioEventCatalog
import com.example.ava.detection.AudioEventDisplayDuration
import com.example.ava.detection.AudioEventSensitivity
import com.example.ava.ui.screens.settings.components.UsageGuideDialog
import com.example.ava.ui.screens.settings.components.HeaderTextSetting
import com.example.ava.ui.screens.settings.components.IntSetting
import com.example.ava.ui.screens.settings.components.MicPreviewCard
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.SettingItem
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.settingsClickable
import com.example.ava.ui.screens.settings.components.SwitchSetting
import com.example.ava.ui.screens.settings.components.VoicePrintEnrollmentModeRow
import com.example.ava.ui.screens.settings.components.VoicePrintEnrollmentSheet
import com.example.ava.ui.screens.settings.components.VoicePrintEnrollmentSheetsHost
import com.example.ava.ui.screens.settings.components.VoicePrintManualEnrollRow
import com.example.ava.ui.screens.settings.components.VoicePrintModeSwitchConfirmDialog
import com.example.ava.ui.screens.settings.components.SettingValueBadge
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsGuideCard
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.VoiceFeedbackAccentSettingsCard
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsHelpMarkTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.theme.SlateTertiary as SubLabelColor
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.RecordingPath
import com.example.ava.settings.SoftwareAecRoom
import com.example.ava.settings.SoftwareAecStrength
import com.example.ava.settings.SoftwareNsStrength
import com.example.ava.settings.VoicePrintEnrollmentMode
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.QuickWakeTrigger
import com.example.ava.settings.WakeMode
import com.example.ava.settings.WakeWordEngine
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.VoiceSettingsEntryRow
import com.example.ava.ui.screens.settings.components.VoiceDetailScaffold
import com.example.ava.ui.screens.settings.components.VoiceStatsFocus
import com.example.ava.voiceprint.VoicePrintStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUDIO_SOURCE_AUTO_DETECT_KEY = "auto_detect"
private const val PREFS_VOICE_REPLY_GUIDE = "voice_reply_guide"
private const val KEY_VOICE_REPLY_GUIDE_DISMISSED = "dismissed"

private data class AudioSourceOption(
    val key: String,
    val source: Int?,
    val label: String,
    val description: String,
)

private data class RecordingPathOption(
    val path: RecordingPath,
    val label: String,
    val description: String,
)

enum class VoiceSettingsDestination {
    Wake,
    Microphone,
    NoiseSuppression,
    EchoCancellation,
    VoicePrint,
    AudioEvent,
    StreamingTts,
    FeedbackAccent,
}

@Composable
fun VoiceSettingsScreen(
    navController: NavController,
    destination: VoiceSettingsDestination,
    viewModel: SettingsViewModel = viewModel(),
) {
    when (destination) {
        VoiceSettingsDestination.Wake -> VoiceWakeSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.Microphone -> VoiceMicrophoneSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.NoiseSuppression -> VoiceNoiseSuppressionSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.EchoCancellation -> VoiceEchoCancellationSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.VoicePrint -> VoicePrintSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.AudioEvent -> VoiceAudioEventSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.StreamingTts -> VoiceStreamingTtsSettingsScreen(navController, viewModel)
        VoiceSettingsDestination.FeedbackAccent -> VoiceFeedbackAccentSettingsScreen(navController, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceWakeSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle()
    val wakeWordLibraryEntries by viewModel.wakeWordLibraryEntries.collectAsStateWithLifecycle()
    val vsCatalog by viewModel.vsWakeWordCatalog.collectAsStateWithLifecycle()
    val vsCatalogLoadState by viewModel.vsWakeWordCatalogLoadState.collectAsStateWithLifecycle()
    val vsDownloadingId by viewModel.vsWakeWordCatalogDownloadingId.collectAsStateWithLifecycle()
    val vsDownloadPercent by viewModel.vsWakeWordCatalogDownloadPercent.collectAsStateWithLifecycle()
    val enabled = uiState != null
    val noneText = stringResource(R.string.label_voice_satellite_wake_word_2_none)
    var showDeferredSections by remember { mutableStateOf(false) }
    val isVsEngine = microphoneState?.wakeWordEngine == WakeWordEngine.OPEN_WAKE_WORD
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val currentWakeMode = microphoneState?.wakeMode ?: WakeMode.VOICE
    val wakeEngineDisabled = currentWakeMode == WakeMode.BUTTON
    val wakeWordEngineMicroLabel = stringResource(R.string.option_wake_word_engine_micro)
    val wakeWordEngineVsLabel = stringResource(R.string.option_wake_word_engine_vs)
    var showWakeEngineHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        showDeferredSections = true
    }
    LaunchedEffect(isVsEngine) {
        if (isVsEngine) viewModel.refreshVsWakeWordCatalog()
    }

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_wake_entry_title),
        focus = VoiceStatsFocus.Wake,
    ) {
        item(key = "wake_mode_selector") {
            val currentTrigger = microphoneState?.quickWakeTrigger ?: QuickWakeTrigger.TAP
            SimpleCard {
                Text(
                    text = stringResource(R.string.wake_mode_section_title),
                    color = getLabelColor(),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                CollapsibleDescriptionText(
                    text = stringResource(
                        when (currentWakeMode) {
                            WakeMode.VOICE -> R.string.wake_mode_voice_desc
                            WakeMode.HYBRID -> R.string.wake_mode_hybrid_desc
                            WakeMode.BUTTON -> R.string.wake_mode_button_desc
                        },
                    ),
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                )
                WakeModeTileRow(
                    selected = currentWakeMode,
                    enabled = enabled,
                    modifier = Modifier.padding(
                        top = 12.dp,
                        bottom = if (currentWakeMode != WakeMode.VOICE) 0.dp else 16.dp,
                    ),
                    onSelected = { mode ->
                        coroutineScope.launch {
                            viewModel.saveWakeMode(mode)
                        }
                    },
                )
                AnimatedVisibility(visible = currentWakeMode != WakeMode.VOICE) {
                    Column {
                        SettingsDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                        QuickWakeGestureRow(
                            selected = currentTrigger,
                            enabled = enabled,
                            onSelected = { trigger ->
                                coroutineScope.launch {
                                    viewModel.saveQuickWakeTrigger(trigger)
                                }
                            },
                        )
                        CollapsibleDescriptionText(
                            text = stringResource(
                                when (currentTrigger) {
                                    QuickWakeTrigger.TAP -> R.string.quick_wake_trigger_tap_desc
                                    QuickWakeTrigger.HOLD -> R.string.quick_wake_trigger_hold_desc
                                },
                            ),
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            color = getSettingsDescriptionColor(),
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }

        item(key = "wake_word_engine") {
            SimpleCard(modifier = Modifier.then(
                if (wakeEngineDisabled) Modifier.alpha(0.45f) else Modifier
            )) {
                SelectSetting(
                    name = stringResource(R.string.label_voice_satellite_wake_word_engine),
                    selected = microphoneState?.wakeWordEngine,
                    items = microphoneState?.availableWakeWordEngines,
                    enabled = enabled && !wakeEngineDisabled,
                    key = { it.name },
                    value = {
                        when (it) {
                            WakeWordEngine.MICRO_WAKE_WORD -> wakeWordEngineMicroLabel
                            WakeWordEngine.OPEN_WAKE_WORD -> wakeWordEngineVsLabel
                            null -> ""
                        }
                    },
                    titleTrailing = {
                        Text(
                            text = "?",
                            color = getAccentColor(),
                            fontSize = settingsHelpMarkTextSize(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(enabled = !wakeEngineDisabled) { showWakeEngineHelp = true }
                                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        )
                    },
                    onConfirmRequest = { selectedEngine ->
                        if (selectedEngine != null) {
                            coroutineScope.launch {
                                viewModel.saveWakeWordEngine(selectedEngine)
                            }
                        }
                    },
                )
            }
        }

        item(key = "wake_word_selectors") {
            SimpleCard(modifier = Modifier.then(
                if (wakeEngineDisabled) Modifier.alpha(0.45f) else Modifier
            )) {
                WakeWordSelectWithSensitivity(
                    name = stringResource(R.string.label_voice_satellite_wake_word),
                    selected = microphoneState?.wakeWord,
                    items = microphoneState?.wakeWords ?: emptyList(),
                    sensitivity = microphoneState?.sensitivity1 ?: -1f,
                    enabled = enabled && !wakeEngineDisabled && microphoneState != null,
                    catalogEntries = if (isVsEngine) vsCatalog else emptyList(),
                    catalogLoadState = if (isVsEngine) {
                        vsCatalogLoadState
                    } else {
                        WakeWordCatalogLoadState.Ready
                    },
                    downloadingId = vsDownloadingId,
                    downloadPercent = vsDownloadPercent,
                    isRemovable = { id -> isVsEngine && viewModel.isVsCatalogModelRemovable(id) },
                    onDownloadCatalog = { id ->
                        coroutineScope.launch { viewModel.downloadVsCatalogModel(id) }
                    },
                    onDeleteInstalled = { id ->
                        coroutineScope.launch { viewModel.deleteVsCatalogModel(id) }
                    },
                    onConfirm = { wakeWord, sens, extra ->
                        coroutineScope.launch {
                            if (wakeWord != null) viewModel.saveWakeWord(wakeWord.id)
                            viewModel.saveWakeWordSensitivity1(sens)
                            viewModel.saveWakeWordExtraStrictness1(extra)
                        }
                    },
                    openEngine = isVsEngine,
                    extraStrictness = microphoneState?.extraStrictness1 ?: 0,
                    extraZoneEnabled = true,
                )

                SettingsDivider()

                WakeWordSelectWithSensitivity(
                    name = stringResource(R.string.label_voice_satellite_wake_word_2),
                    selected = microphoneState?.wakeWord2,
                    items = microphoneState?.wakeWords ?: emptyList(),
                    sensitivity = microphoneState?.sensitivity2 ?: -1f,
                    enabled = enabled && !wakeEngineDisabled && microphoneState != null,
                    allowNone = true,
                    noneText = noneText,
                    catalogEntries = if (isVsEngine) vsCatalog else emptyList(),
                    catalogLoadState = if (isVsEngine) {
                        vsCatalogLoadState
                    } else {
                        WakeWordCatalogLoadState.Ready
                    },
                    downloadingId = vsDownloadingId,
                    downloadPercent = vsDownloadPercent,
                    isRemovable = { id -> isVsEngine && viewModel.isVsCatalogModelRemovable(id) },
                    onDownloadCatalog = { id ->
                        coroutineScope.launch { viewModel.downloadVsCatalogModel(id) }
                    },
                    onDeleteInstalled = { id ->
                        coroutineScope.launch { viewModel.deleteVsCatalogModel(id) }
                    },
                    onConfirm = { wakeWord, sens, extra ->
                        coroutineScope.launch {
                            viewModel.saveWakeWord2(wakeWord?.id)
                            viewModel.saveWakeWordSensitivity2(sens)
                            viewModel.saveWakeWordExtraStrictness2(extra)
                        }
                    },
                    openEngine = isVsEngine,
                    extraStrictness = microphoneState?.extraStrictness2 ?: 0,
                    extraZoneEnabled = true,
                )

                SettingsDivider()

                WakeWordSelectWithSensitivity(
                    name = stringResource(R.string.label_voice_satellite_stop_word),
                    selected = microphoneState?.stopWord,
                    items = microphoneState?.stopPickerWakeWords ?: emptyList(),
                    sensitivity = microphoneState?.stopWordSensitivity ?: -1f,
                    enabled = enabled && !wakeEngineDisabled && microphoneState != null,
                    allowNone = true,
                    noneText = stringResource(R.string.label_voice_satellite_stop_word_none),
                    catalogEntries = if (isVsEngine) vsCatalog else emptyList(),
                    catalogLoadState = if (isVsEngine) {
                        vsCatalogLoadState
                    } else {
                        WakeWordCatalogLoadState.Ready
                    },
                    downloadingId = vsDownloadingId,
                    downloadPercent = vsDownloadPercent,
                    isRemovable = { id -> isVsEngine && viewModel.isVsCatalogModelRemovable(id) },
                    onDownloadCatalog = { id ->
                        coroutineScope.launch { viewModel.downloadVsCatalogModel(id) }
                    },
                    onDeleteInstalled = { id ->
                        coroutineScope.launch { viewModel.deleteVsCatalogModel(id) }
                    },
                    onConfirm = { stopWord, sens, _ ->
                        coroutineScope.launch {
                            viewModel.saveStopWord(stopWord?.id)
                            if (stopWord != null && stopWord.id != MicrophoneSettings.STOP_WORD_BUILTIN) {
                                viewModel.saveStopWordSensitivity(sens)
                            }
                        }
                    },
                    openEngine = isVsEngine,
                    hideSliderIds = setOf(MicrophoneSettings.STOP_WORD_BUILTIN),
                    allowStopClassifiers = isVsEngine,
                )

                SettingsDivider()

                // Self-learning is on by default and needs no choice from the user; this
                // row only leads to the status / advanced page.
                SettingRow(
                    label = stringResource(R.string.wake_learn_entry_title),
                    subLabel = stringResource(R.string.wake_learn_entry_desc),
                    onClick = if (wakeEngineDisabled) {
                        null
                    } else {
                        {
                            navController.navigate(Screen.SETTINGS_VOICE_WAKE_LEARN) {
                                launchSingleTop = true
                            }
                        }
                    },
                ) { SettingRowChevron() }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_chorus_wake_title),
                    subLabel = stringResource(R.string.settings_chorus_wake_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.multiDeviceArbiterEnabled ?: true,
                        enabled = enabled && !wakeEngineDisabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveChorusWakeEnabled(it)
                            }
                        },
                    )
                }
            }
        }

        if (showDeferredSections) {
            item(key = "wake_word_library_entry") {
                val importedCount = wakeWordLibraryEntries.count { it.ready }
                VoiceSettingsEntryRow(
                    label = stringResource(R.string.wake_word_library_entry_title),
                    subLabel = if (importedCount > 0) {
                        stringResource(R.string.wake_word_library_entry_desc_count, importedCount)
                    } else {
                        stringResource(R.string.wake_word_library_entry_desc)
                    },
                    enabled = !wakeEngineDisabled,
                    onClick = {
                        navController.navigate(Screen.SETTINGS_VOICE_WAKE_LIBRARY) { launchSingleTop = true }
                    },
                )
            }

            item(key = "wake_sound_settings") {
                VoiceWakeSoundSettingsCard(
                    viewModel = viewModel,
                    enabled = enabled && !wakeEngineDisabled,
                    dimmed = wakeEngineDisabled,
                    context = context,
                    coroutineScope = coroutineScope,
                )
            }
        }
    }

    if (showWakeEngineHelp) {
        BasicAlertDialog(onDismissRequest = { showWakeEngineHelp = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = getDialogBackground(),
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.settings_wake_word_engine_help),
                        color = getLabelColor(),
                        fontSize = settingsBodyTextSize(),
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { showWakeEngineHelp = false },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_usage_guide_dismiss),
                            color = getAccentColor(),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceWakeSoundSettingsCard(
    viewModel: SettingsViewModel,
    enabled: Boolean,
    dimmed: Boolean,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)

    SimpleCard(modifier = Modifier.then(
        if (dimmed) Modifier.alpha(0.45f) else Modifier
    )) {
        SettingRow(
            label = stringResource(R.string.label_voice_satellite_enable_wake_sound),
            subLabel = stringResource(R.string.description_voice_satellite_play_wake_sound),
        ) {
            ModernSwitch(
                checked = playerState?.enableWakeSound ?: true,
                enabled = enabled,
                onCheckedChange = {
                    coroutineScope.launch {
                        viewModel.saveEnableWakeSound(it)
                    }
                },
            )
        }

        val wakeSoundEnabled = playerState?.enableWakeSound ?: true
        if (wakeSoundEnabled) {
            SettingsDivider()
            WakeSoundItem(
                label = stringResource(R.string.wake_sound_1),
                soundUri = playerState?.wakeSound ?: "asset:///sounds/wake_word_triggered.wav",
                enabled = enabled,
                context = context,
                onSoundSelected = { uri ->
                    coroutineScope.launch {
                        viewModel.saveWakeSoundUri(uri)
                    }
                },
            )
            SettingsDivider()
            WakeSoundItem(
                label = stringResource(R.string.wake_sound_2),
                soundUri = playerState?.wakeSound2 ?: "asset:///sounds/wake_word_triggered.wav",
                enabled = enabled,
                context = context,
                onSoundSelected = { uri ->
                    coroutineScope.launch {
                        viewModel.saveWakeSound2Uri(uri)
                    }
                },
            )
        }
    }
}

@Composable
private fun VoiceMicrophoneSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null
    val audioSources = listOf(
        AudioSourceOption(
            AUDIO_SOURCE_AUTO_DETECT_KEY,
            null,
            stringResource(R.string.audio_source_auto_detect),
            stringResource(R.string.audio_source_auto_detect_desc),
        ),
        AudioSourceOption(
            MediaRecorder.AudioSource.MIC.toString(),
            MediaRecorder.AudioSource.MIC,
            stringResource(R.string.audio_source_mic),
            stringResource(R.string.audio_source_mic_desc),
        ),
        AudioSourceOption(
            MediaRecorder.AudioSource.VOICE_RECOGNITION.toString(),
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            stringResource(R.string.audio_source_voice_recognition),
            stringResource(R.string.audio_source_voice_recognition_desc),
        ),
        AudioSourceOption(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION.toString(),
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            stringResource(R.string.audio_source_voice_communication),
            stringResource(R.string.audio_source_voice_communication_desc),
        ),
        AudioSourceOption(
            MediaRecorder.AudioSource.UNPROCESSED.toString(),
            MediaRecorder.AudioSource.UNPROCESSED,
            stringResource(R.string.audio_source_unprocessed),
            stringResource(R.string.audio_source_unprocessed_desc),
        ),
    )
    val recordingPaths = listOf(
        RecordingPathOption(
            RecordingPath.AUTO,
            stringResource(R.string.recording_path_auto),
            stringResource(R.string.recording_path_auto_desc),
        ),
        RecordingPathOption(
            RecordingPath.BUILTIN,
            stringResource(R.string.recording_path_builtin),
            stringResource(R.string.recording_path_builtin_desc),
        ),
        RecordingPathOption(
            RecordingPath.USB,
            stringResource(R.string.recording_path_usb),
            stringResource(R.string.recording_path_usb_desc),
        ),
    )

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_microphone_entry_title),
        focus = VoiceStatsFocus.Microphone,
    ) {
        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.label_automatic_gain_control),
                    description = stringResource(R.string.description_automatic_gain_control),
                    value = microphoneState?.automaticGainControlEnabled ?: true,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveAutomaticGainControlEnabled(it)
                        }
                    },
                )

                SettingsDivider()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            navController.navigate(Screen.SETTINGS_VOICE_NOISE_SUPPRESSION) {
                                launchSingleTop = true
                            }
                        },
                ) {
                    SettingRow(
                        label = stringResource(R.string.settings_voice_noise_suppression_entry_title),
                        subLabel = stringResource(R.string.settings_voice_noise_suppression_entry_desc),
                    ) {
                        SettingsChevronIcon()
                    }
                }

                SettingsDivider()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            navController.navigate(Screen.SETTINGS_VOICE_ECHO_CANCELLATION) {
                                launchSingleTop = true
                            }
                        },
                ) {
                    SettingRow(
                        label = stringResource(R.string.settings_voice_echo_cancellation_entry_title),
                        subLabel = stringResource(R.string.settings_voice_echo_cancellation_entry_desc),
                    ) {
                        SettingsChevronIcon()
                    }
                }

                SettingsDivider()

                IntSetting(
                    name = stringResource(R.string.label_mic_gain_db),
                    description = stringResource(R.string.description_mic_gain_db),
                    dialogHint = stringResource(R.string.dialog_hint_mic_gain_db),
                    value = microphoneState?.micGainDb,
                    enabled = enabled,
                    signed = true,
                    validation = { viewModel.validateMicGainDb(it) },
                    onConfirmRequest = {
                        coroutineScope.launch {
                            viewModel.saveMicGainDb(it)
                        }
                    },
                )

                SettingsDivider()

                SelectSetting(
                    name = stringResource(R.string.label_audio_source),
                    description = stringResource(R.string.description_audio_source),
                    selected = if (microphoneState?.audioSourceAutoDetect != false) {
                        audioSources.firstOrNull { it.key == AUDIO_SOURCE_AUTO_DETECT_KEY }
                    } else {
                        audioSources.firstOrNull { it.source == microphoneState?.audioSource }
                    },
                    items = audioSources,
                    enabled = enabled,
                    key = { it.key },
                    value = { it?.label ?: "" },
                    itemDescription = { it.description },
                    onConfirmRequest = { option ->
                        if (option != null) {
                            coroutineScope.launch {
                                if (option.source == null) {
                                    viewModel.saveAudioSourceAutoDetect()
                                } else {
                                    viewModel.saveAudioSource(option.source)
                                }
                            }
                        }
                    },
                )

                SettingsDivider()

                SelectSetting(
                    name = stringResource(R.string.label_recording_path),
                    description = stringResource(R.string.description_recording_path),
                    selected = recordingPaths.firstOrNull {
                        it.path == (microphoneState?.recordingPath ?: RecordingPath.AUTO)
                    },
                    items = recordingPaths,
                    enabled = enabled,
                    key = { it.path.name },
                    value = { it?.label ?: "" },
                    itemDescription = { it.description },
                    onConfirmRequest = { option ->
                        if (option != null) {
                            coroutineScope.launch {
                                viewModel.saveRecordingPath(option.path)
                            }
                        }
                    },
                )

                SettingsDivider()

                val profileItems = DeviceAudioProfile.ALL_PROFILES.map { profile ->
                    profile.id to stringResource(profile.displayNameRes)
                }
                val currentProfileId = microphoneState?.audioProfileId ?: ""
                SelectSetting(
                    name = stringResource(R.string.label_audio_profile),
                    description = stringResource(R.string.description_audio_profile),
                    selected = profileItems.firstOrNull { it.first == currentProfileId } ?: profileItems.first(),
                    items = profileItems,
                    enabled = enabled,
                    key = { it.first },
                    value = { it?.second ?: "" },
                    onConfirmRequest = { selected ->
                        coroutineScope.launch {
                            viewModel.saveAudioProfileId(selected?.first ?: DeviceAudioProfile.DEFAULT.id)
                        }
                    },
                )
            }
        }
    }
}

/**
 * L3 noise suppression — order is the reverse of Echo L3:
 * 1) hardware (default on), 2) software (default off). Soft/hard are mutually exclusive.
 */
@Composable
private fun VoiceNoiseSuppressionSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null
    val softwareNsEnabled = microphoneState?.softwareNsEnabled ?: false
    val nsStrength = microphoneState?.softwareNsStrength ?: SoftwareNsStrength.LIGHT

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_noise_suppression_entry_title),
        focus = VoiceStatsFocus.Microphone,
    ) {
        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.label_noise_suppressor),
                    description = stringResource(R.string.description_noise_suppressor),
                    value = if (softwareNsEnabled) {
                        false
                    } else {
                        microphoneState?.noiseSuppressorEnabled ?: true
                    },
                    enabled = enabled && !softwareNsEnabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveNoiseSuppressorEnabled(it)
                        }
                    },
                )
            }
        }

        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.label_software_ns),
                    description = stringResource(R.string.description_software_ns),
                    value = softwareNsEnabled,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveSoftwareNsEnabled(it)
                        }
                    },
                )

                if (softwareNsEnabled) {
                    SettingsDivider()

                    AecTierSetting(
                        name = stringResource(R.string.label_software_ns_strength),
                        description = stringResource(R.string.description_software_ns_strength),
                        options = listOf(
                            stringResource(R.string.label_software_ns_strength_light),
                            stringResource(R.string.label_software_ns_strength_standard),
                            stringResource(R.string.label_software_ns_strength_strong),
                        ),
                        selectedIndex = when (nsStrength) {
                            SoftwareNsStrength.LIGHT -> 0
                            SoftwareNsStrength.STANDARD -> 1
                            SoftwareNsStrength.STRONG -> 2
                        },
                        enabled = enabled,
                        onSelect = { index ->
                            val next = when (index) {
                                0 -> SoftwareNsStrength.LIGHT
                                2 -> SoftwareNsStrength.STRONG
                                else -> SoftwareNsStrength.STANDARD
                            }
                            coroutineScope.launch {
                                viewModel.saveSoftwareNsStrength(next)
                            }
                        },
                    )
                }
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_voice_mic_preview_section)) }

        item {
            MicPreviewCard(
                enabled = enabled,
                title = stringResource(R.string.settings_voice_ns_preview_title),
                description = stringResource(R.string.settings_voice_ns_preview_desc),
            )
        }
    }
}

/** L3: software AEC (with optional pause-while-speaking) above hardware AEC. */
@Composable
private fun VoiceEchoCancellationSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null
    val softwareAecEnabled = microphoneState?.softwareAecEnabled ?: false
    val strength = microphoneState?.softwareAecStrength ?: SoftwareAecStrength.STANDARD
    val room = microphoneState?.softwareAecRoom ?: SoftwareAecRoom.LIVING

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_echo_cancellation_entry_title),
        focus = VoiceStatsFocus.Echo,
    ) {
        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.label_software_aec),
                    description = stringResource(R.string.description_software_aec),
                    value = softwareAecEnabled,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveSoftwareAecEnabled(it)
                        }
                    },
                )

                if (softwareAecEnabled) {
                    SettingsDivider()

                    SwitchSetting(
                        name = stringResource(R.string.label_software_aec_pause_during_speech),
                        description = stringResource(R.string.description_software_aec_pause_during_speech),
                        value = microphoneState?.softwareAecPauseDuringSpeech ?: false,
                        enabled = enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveSoftwareAecPauseDuringSpeech(it)
                            }
                        },
                    )

                    SettingsDivider()

                    AecTierSetting(
                        name = stringResource(R.string.label_software_aec_strength),
                        description = stringResource(R.string.description_software_aec_strength),
                        options = listOf(
                            stringResource(R.string.label_software_aec_strength_light),
                            stringResource(R.string.label_software_aec_strength_standard),
                            stringResource(R.string.label_software_aec_strength_strong),
                        ),
                        selectedIndex = when (strength) {
                            SoftwareAecStrength.LIGHT -> 0
                            SoftwareAecStrength.STANDARD -> 1
                            SoftwareAecStrength.STRONG -> 2
                        },
                        enabled = enabled,
                        onSelect = { index ->
                            val next = when (index) {
                                0 -> SoftwareAecStrength.LIGHT
                                2 -> SoftwareAecStrength.STRONG
                                else -> SoftwareAecStrength.STANDARD
                            }
                            coroutineScope.launch {
                                viewModel.saveSoftwareAecStrength(next)
                            }
                        },
                    )

                    SettingsDivider()

                    AecTierSetting(
                        name = stringResource(R.string.label_software_aec_room),
                        description = stringResource(R.string.description_software_aec_room),
                        options = listOf(
                            stringResource(R.string.label_software_aec_room_near),
                            stringResource(R.string.label_software_aec_room_living),
                            stringResource(R.string.label_software_aec_room_open),
                        ),
                        selectedIndex = when (room) {
                            SoftwareAecRoom.NEAR -> 0
                            SoftwareAecRoom.LIVING -> 1
                            SoftwareAecRoom.OPEN -> 2
                        },
                        enabled = enabled,
                        onSelect = { index ->
                            val next = when (index) {
                                0 -> SoftwareAecRoom.NEAR
                                2 -> SoftwareAecRoom.OPEN
                                else -> SoftwareAecRoom.LIVING
                            }
                            coroutineScope.launch {
                                viewModel.saveSoftwareAecRoom(next)
                            }
                        },
                    )
                }
            }
        }

        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.label_acoustic_echo_canceler),
                    description = stringResource(R.string.description_acoustic_echo_canceler),
                    value = if (softwareAecEnabled) {
                        false
                    } else {
                        microphoneState?.acousticEchoCancelerEnabled ?: true
                    },
                    // Soft/hard AEC are mutually exclusive; hardware is forced off while software is on.
                    enabled = enabled && !softwareAecEnabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveAcousticEchoCancelerEnabled(it)
                        }
                    },
                )
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_voice_mic_preview_section)) }

        item {
            MicPreviewCard(
                enabled = enabled,
                title = stringResource(R.string.settings_voice_aec_preview_title),
                description = stringResource(R.string.settings_voice_aec_preview_desc),
                withEchoTest = true,
            )
        }
    }
}

@Composable
private fun AecTierSetting(
    name: String,
    description: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val accent = getAccentColor()
    val trackColor = getSliderInactiveColor()
    val thumbColor = getDialogBackground()
    // No extra horizontal padding — SimpleCard already insets content; keep flush with SwitchSetting.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = name,
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) getTitleColor() else getSettingsDescriptionColor(),
        )
        CollapsibleDescriptionText(
            text = description,
            modifier = Modifier.padding(top = 4.dp),
            color = getSettingsDescriptionColor(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
                .padding(3.dp),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) thumbColor else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            selected -> accent
                            else -> getSettingsDescriptionColor()
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePrintSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null
    var showVoicePrintDisableDialog by remember { mutableStateOf(false) }
    var showManualWakeVerifyBlockedDialog by remember { mutableStateOf(false) }
    var activeEnrollmentSheet by remember { mutableStateOf(VoicePrintEnrollmentSheet.None) }
    var pendingManualSwitch by remember { mutableStateOf(false) }
    var pendingAutoSwitch by remember { mutableStateOf(false) }
    val context = LocalContext.current

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_print_entry_title),
        focus = VoiceStatsFocus.VoicePrint,
    ) {
        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.settings_voice_print_enabled),
                    description = stringResource(R.string.settings_voice_print_enabled_desc),
                    value = microphoneState?.voicePrintEnabled ?: false,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            coroutineScope.launch {
                                viewModel.saveVoicePrintEnabled(true)
                            }
                        } else {
                            showVoicePrintDisableDialog = true
                        }
                    },
                )
            }
        }

        val currentMicrophoneState = microphoneState
        if (currentMicrophoneState?.voicePrintEnabled == true) {
            item {
                SimpleCard {
                    VoicePrintEnrollmentModeRow(
                        mode = currentMicrophoneState.voicePrintEnrollmentMode,
                        enabled = enabled,
                        onClick = { activeEnrollmentSheet = VoicePrintEnrollmentSheet.ModePicker },
                    )

                    if (currentMicrophoneState.voicePrintEnrollmentMode == VoicePrintEnrollmentMode.MANUAL) {
                        SettingsDivider()
                        VoicePrintManualEnrollRow(
                            enabled = enabled,
                            onClick = { activeEnrollmentSheet = VoicePrintEnrollmentSheet.ManualEnroll },
                        )
                        SettingsDivider()
                        SwitchSetting(
                            name = stringResource(R.string.settings_voice_print_manual_wake_verify_title),
                            description = stringResource(R.string.settings_voice_print_manual_wake_verify_desc),
                            value = currentMicrophoneState.voicePrintManualWakeVerifyEnabled,
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                if (!checked) {
                                    coroutineScope.launch {
                                        viewModel.saveVoicePrintManualWakeVerifyEnabled(false)
                                    }
                                    return@SwitchSetting
                                }
                                val canEnable = viewModel.canEnableManualWakeVerify(
                                    user0Samples = currentMicrophoneState.voicePrintManualUser0Samples,
                                    user1Samples = currentMicrophoneState.voicePrintManualUser1Samples,
                                )
                                if (!canEnable) {
                                    showManualWakeVerifyBlockedDialog = true
                                    return@SwitchSetting
                                }
                                coroutineScope.launch {
                                    viewModel.saveVoicePrintManualWakeVerifyEnabled(true)
                                }
                            },
                        )
                    }
                }
            }

            item {
                SimpleCard {
                    val defaultUserNames = listOf(
                        stringResource(R.string.settings_voice_print_user_1_name),
                        stringResource(R.string.settings_voice_print_user_2_name),
                    )
                    val voicePrintNames = currentMicrophoneState.voicePrintUserNames
                    val voicePrintNamesSummary = voicePrintNames
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .joinToString(" / ")
                    HeaderTextSetting(
                        name = stringResource(R.string.settings_voice_print_users),
                        description = stringResource(R.string.settings_voice_print_user_desc),
                        titleValue = voicePrintNames.getOrNull(0).orEmpty(),
                        subtitleValue = voicePrintNames.getOrNull(1).orEmpty(),
                        displayValue = voicePrintNamesSummary,
                        titlePlaceholder = defaultUserNames[0],
                        subtitlePlaceholder = defaultUserNames[1],
                        titleLabel = defaultUserNames[0],
                        subtitleLabel = defaultUserNames[1],
                        dialogTitle = stringResource(R.string.settings_voice_print_users),
                        titleMaxLength = 24,
                        subtitleMaxLength = 24,
                        allowBlank = true,
                        enabled = enabled,
                        onConfirmRequest = { firstName, secondName ->
                            coroutineScope.launch {
                                viewModel.saveVoicePrintUserNames(listOf(firstName, secondName))
                            }
                        },
                    )
                }
            }
        }
    }

    val wakeWordPhrase = microphoneState?.wakeWord?.wakeWord?.wake_word.orEmpty()

    VoicePrintEnrollmentSheetsHost(
        activeSheet = activeEnrollmentSheet,
        enrollmentMode = microphoneState?.voicePrintEnrollmentMode ?: VoicePrintEnrollmentMode.AUTO,
        wakeWordEngine = microphoneState?.wakeWordEngine ?: WakeWordEngine.MICRO_WAKE_WORD,
        wakeWordPhrase = wakeWordPhrase,
        manualUser0Samples = microphoneState?.voicePrintManualUser0Samples ?: 0,
        manualUser1Samples = microphoneState?.voicePrintManualUser1Samples ?: 0,
        userNames = microphoneState?.voicePrintUserNames.orEmpty(),
        microphoneMuted = microphoneState?.muted ?: false,
        onDismiss = { activeEnrollmentSheet = VoicePrintEnrollmentSheet.None },
        onSelectMode = { mode ->
            val currentMode = microphoneState?.voicePrintEnrollmentMode ?: VoicePrintEnrollmentMode.AUTO
            when (mode) {
                VoicePrintEnrollmentMode.AUTO -> {
                    val hasManualData = currentMode == VoicePrintEnrollmentMode.MANUAL && (
                        (microphoneState?.voicePrintManualUser0Samples ?: 0) > 0 ||
                            (microphoneState?.voicePrintManualUser1Samples ?: 0) > 0 ||
                            VoicePrintStorage.hasStoredProfilesAny(context)
                        )
                    if (hasManualData) {
                        pendingAutoSwitch = true
                        activeEnrollmentSheet = VoicePrintEnrollmentSheet.None
                    } else {
                        if (currentMode != VoicePrintEnrollmentMode.AUTO) {
                            coroutineScope.launch {
                                viewModel.saveVoicePrintEnrollmentMode(VoicePrintEnrollmentMode.AUTO)
                            }
                        }
                        activeEnrollmentSheet = VoicePrintEnrollmentSheet.AutoInfo
                    }
                }
                VoicePrintEnrollmentMode.MANUAL -> {
                    val hasAutoProfiles = currentMode == VoicePrintEnrollmentMode.AUTO &&
                        VoicePrintStorage.hasStoredProfilesAny(context)
                    if (hasAutoProfiles) {
                        pendingManualSwitch = true
                        activeEnrollmentSheet = VoicePrintEnrollmentSheet.None
                    } else {
                        if (currentMode != VoicePrintEnrollmentMode.MANUAL) {
                            coroutineScope.launch {
                                viewModel.saveVoicePrintEnrollmentMode(VoicePrintEnrollmentMode.MANUAL)
                            }
                        }
                        activeEnrollmentSheet = VoicePrintEnrollmentSheet.ManualEnroll
                    }
                }
            }
        },
        onManualSampleRecorded = { userIndex, newCount ->
            coroutineScope.launch {
                viewModel.saveVoicePrintManualProgress(userIndex, newCount)
            }
        },
        onManualUserDeleted = { userIndex ->
            coroutineScope.launch {
                viewModel.clearVoicePrintManualUser(userIndex)
            }
        },
    )

    VoicePrintModeSwitchConfirmDialog(
        visible = pendingManualSwitch,
        title = stringResource(R.string.settings_voice_print_switch_manual_title),
        message = stringResource(R.string.settings_voice_print_switch_manual_message),
        confirmText = stringResource(R.string.settings_voice_print_switch_manual_confirm),
        onConfirm = {
            pendingManualSwitch = false
            coroutineScope.launch {
                viewModel.switchVoicePrintToManualAndClearProfiles()
            }
            activeEnrollmentSheet = VoicePrintEnrollmentSheet.ManualEnroll
        },
        onDismiss = { pendingManualSwitch = false },
    )

    VoicePrintModeSwitchConfirmDialog(
        visible = pendingAutoSwitch,
        title = stringResource(R.string.settings_voice_print_switch_auto_title),
        message = stringResource(R.string.settings_voice_print_switch_auto_message),
        confirmText = stringResource(R.string.settings_voice_print_switch_auto_confirm),
        onConfirm = {
            pendingAutoSwitch = false
            coroutineScope.launch {
                viewModel.switchVoicePrintToAutoAndClearProfiles()
            }
            activeEnrollmentSheet = VoicePrintEnrollmentSheet.AutoInfo
        },
        onDismiss = { pendingAutoSwitch = false },
    )

    if (showManualWakeVerifyBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showManualWakeVerifyBlockedDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_voice_print_manual_wake_verify_blocked_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = getTitleColor(),
                )
            },
            text = {
                SettingsHelpBodyText(
                    text = stringResource(R.string.settings_voice_print_manual_wake_verify_blocked_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManualWakeVerifyBlockedDialog = false
                        activeEnrollmentSheet = VoicePrintEnrollmentSheet.ManualEnroll
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_manual_wake_verify_blocked_go_enroll),
                        color = getAccentColor(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualWakeVerifyBlockedDialog = false }) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_disable_cancel),
                        color = getAccentColor(),
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
        )
    }

    if (showVoicePrintDisableDialog) {
        AlertDialog(
            onDismissRequest = { showVoicePrintDisableDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_voice_print_disable_confirm_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = getTitleColor(),
                )
            },
            text = {
                SettingsHelpBodyText(
                    text = stringResource(R.string.settings_voice_print_disable_confirm_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showVoicePrintDisableDialog = false
                        coroutineScope.launch {
                            viewModel.disableVoicePrintAndClearProfiles()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_disable_confirm_ok),
                        color = Color(0xFFEF4444),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoicePrintDisableDialog = false }) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_disable_cancel),
                        color = getAccentColor(),
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
        )
    }
}

@Composable
private fun VoiceAudioEventSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null
    val detectionEnabled = experimentalState?.audioEventDetectionEnabled ?: false
    val monitoredLabels = experimentalState?.resolvedAudioEventMonitoredLabels()
        ?: AudioEventCatalog.DEFAULT_MONITORED_LABELS
    val sensitivity = experimentalState?.resolvedAudioEventSensitivity()
        ?: AudioEventSensitivity.BALANCED
    val displaySeconds = experimentalState?.resolvedAudioEventDisplaySeconds()
        ?: AudioEventDisplayDuration.DEFAULT_SECONDS
    val keepOneTypeMessage = stringResource(R.string.settings_audio_event_keep_one_type)
    val context = LocalContext.current

    fun toggleMonitoredLabel(label: String) {
        val checked = label in monitoredLabels
        if (checked && monitoredLabels.size == 1) {
            AvaToast.show(context, keepOneTypeMessage)
            return
        }
        coroutineScope.launch {
            viewModel.saveAudioEventMonitoredLabel(label, !checked)
        }
    }

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_audio_event_entry_title),
        focus = VoiceStatsFocus.AudioEvent,
    ) {
        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.settings_audio_event_detection_enabled),
                    description = stringResource(R.string.settings_audio_event_detection_enabled_desc),
                    value = detectionEnabled,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        coroutineScope.launch {
                            viewModel.saveAudioEventDetectionEnabled(checked)
                        }
                    },
                )

                if (detectionEnabled) {
                    SettingsDivider()

                    IntSetting(
                        name = stringResource(R.string.settings_audio_event_display_seconds),
                        description = stringResource(R.string.settings_audio_event_display_seconds_desc),
                        value = displaySeconds,
                        enabled = enabled,
                        showValueOnRow = false,
                        validation = { viewModel.validateAudioEventDisplaySeconds(it) },
                        onConfirmRequest = { seconds ->
                            coroutineScope.launch {
                                viewModel.saveAudioEventDisplaySeconds(seconds)
                            }
                        },
                    )
                }
            }
        }

        if (detectionEnabled) {
            item {
                SimpleCard {
                    AudioEventSectionLabel(stringResource(R.string.settings_audio_event_listen_types))
                    AudioEventCatalog.ENTRIES.forEachIndexed { index, entry ->
                        AudioEventTypeOption(
                            label = stringResource(entry.titleRes),
                            description = stringResource(entry.descRes),
                            checked = entry.label in monitoredLabels,
                            enabled = enabled,
                            onToggle = { toggleMonitoredLabel(entry.label) },
                        )
                        if (index < AudioEventCatalog.ENTRIES.lastIndex) {
                            SettingsDivider()
                        }
                    }
                }
            }

            item {
                SimpleCard {
                    AudioEventSectionLabel(stringResource(R.string.settings_audio_event_sensitivity))
                    AudioEventSensitivityOption(
                        label = stringResource(R.string.settings_audio_event_sensitivity_conservative),
                        description = stringResource(R.string.settings_audio_event_sensitivity_conservative_desc),
                        selected = sensitivity == AudioEventSensitivity.CONSERVATIVE,
                        enabled = enabled,
                        onSelect = {
                            coroutineScope.launch {
                                viewModel.saveAudioEventSensitivity(AudioEventSensitivity.CONSERVATIVE)
                            }
                        },
                    )
                    SettingsDivider()
                    AudioEventSensitivityOption(
                        label = stringResource(R.string.settings_audio_event_sensitivity_balanced),
                        description = stringResource(R.string.settings_audio_event_sensitivity_balanced_desc),
                        selected = sensitivity == AudioEventSensitivity.BALANCED,
                        enabled = enabled,
                        onSelect = {
                            coroutineScope.launch {
                                viewModel.saveAudioEventSensitivity(AudioEventSensitivity.BALANCED)
                            }
                        },
                    )
                    SettingsDivider()
                    AudioEventSensitivityOption(
                        label = stringResource(R.string.settings_audio_event_sensitivity_sensitive),
                        description = stringResource(R.string.settings_audio_event_sensitivity_sensitive_desc),
                        selected = sensitivity == AudioEventSensitivity.SENSITIVE,
                        enabled = enabled,
                        onSelect = {
                            coroutineScope.launch {
                                viewModel.saveAudioEventSensitivity(AudioEventSensitivity.SENSITIVE)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioEventSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = settingsBodyTextSize(),
        color = getSettingsDescriptionColor(),
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun AudioEventOptionRow(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        control()
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = getTitleColor(),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
            )
            CollapsibleDescriptionText(
                text = description,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
    }
}

@Composable
private fun AudioEventTypeOption(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    AudioEventOptionRow(
        label = label,
        description = description,
        enabled = enabled,
        onClick = onToggle,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = getAccentColor(),
                uncheckedColor = Color(0xFF94A3B8),
                checkmarkColor = Color.White,
            ),
        )
    }
}

@Composable
private fun AudioEventSensitivityOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    AudioEventOptionRow(
        label = label,
        description = description,
        enabled = enabled,
        onClick = onSelect,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = getAccentColor(),
                unselectedColor = Color(0xFF94A3B8),
            ),
        )
    }
}

@Composable
private fun VoiceFeedbackAccentSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_feedback_accent_title),
        focus = VoiceStatsFocus.FeedbackAccent,
    ) {
        item {
            VoiceFeedbackAccentSettingsCard(
                playerState = playerState,
                enabled = enabled,
                showHeader = false,
                onRippleEffectChange = { checked ->
                    coroutineScope.launch { viewModel.saveVoiceRippleEffect(checked) }
                },
                onEdgeGlowChange = { checked ->
                    coroutineScope.launch { viewModel.saveVoiceEdgeGlow(checked) }
                },
                onWakeWord1LevelGainChange = { gain ->
                    coroutineScope.launch { viewModel.saveVoiceWakeWord1EdgeGlowLevelGain(gain) }
                },
                onWakeWord2LevelGainChange = { gain ->
                    coroutineScope.launch { viewModel.saveVoiceWakeWord2EdgeGlowLevelGain(gain) }
                },
                onWakeWord1SheerChange = { sheer ->
                    coroutineScope.launch { viewModel.saveVoiceWakeWord1EdgeGlowSheer(sheer) }
                },
                onWakeWord2SheerChange = { sheer ->
                    coroutineScope.launch { viewModel.saveVoiceWakeWord2EdgeGlowSheer(sheer) }
                },
                onWakeWord1ColorChange = { color ->
                    coroutineScope.launch { viewModel.saveVoiceWakeWord1AccentColor(color) }
                },
                onWakeWord2ColorChange = { color ->
                    coroutineScope.launch { viewModel.saveVoiceWakeWord2AccentColor(color) }
                },
            )
        }
    }
}

@Composable
private fun VoiceStreamingTtsSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val enabled = uiState != null
    val streamingEnabled = playerState?.enableStreamingTtsSubtitles ?: false
    val context = LocalContext.current
    val hasOverlayPermission = checkOverlayPermission(context)
    var showWhisperVolumeGuide by remember { mutableStateOf(false) }

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.settings_voice_streaming_tts_entry_title),
        focus = VoiceStatsFocus.StreamingTts,
    ) {
        item(key = "voice_reply_guide") {
            val guidePrefs = remember {
                context.getSharedPreferences(PREFS_VOICE_REPLY_GUIDE, android.content.Context.MODE_PRIVATE)
            }
            var guideDismissed by remember {
                mutableStateOf(guidePrefs.getBoolean(KEY_VOICE_REPLY_GUIDE_DISMISSED, false))
            }
            if (!guideDismissed) {
                SettingsGuideCard(
                    text = stringResource(R.string.settings_streaming_tts_intro),
                    onDismiss = {
                        guideDismissed = true
                        guidePrefs.edit().putBoolean(KEY_VOICE_REPLY_GUIDE_DISMISSED, true).apply()
                    },
                )
            }
        }

        // —— Audio ——
        item {
            SettingsSectionLabel(stringResource(R.string.settings_voice_reply_section_audio))
        }
        item {
            SimpleCard {
                Text(
                    text = stringResource(R.string.settings_streaming_tts_mode_label),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                StreamingTtsModeOption(
                    label = stringResource(R.string.settings_streaming_tts_mode_standard),
                    description = stringResource(R.string.settings_streaming_tts_mode_standard_desc),
                    selected = !streamingEnabled,
                    enabled = enabled,
                    onSelect = {
                        coroutineScope.launch {
                            viewModel.saveStreamingTtsSubtitles(false)
                        }
                    },
                )
                SettingsDivider()
                StreamingTtsModeOption(
                    label = stringResource(R.string.settings_streaming_tts_mode_streaming),
                    description = stringResource(R.string.settings_streaming_tts_mode_streaming_desc),
                    selected = streamingEnabled,
                    enabled = enabled,
                    onSelect = {
                        coroutineScope.launch {
                            viewModel.saveStreamingTtsSubtitles(true)
                        }
                    },
                )
            }
        }
        item {
            val whisperVolume = playerState?.whisperResponseVolume
                ?: PlayerSettings.DEFAULT_WHISPER_RESPONSE_VOLUME
            val exposeHa = playerState?.exposeWhisperResponseEntity == true
            val autoGain = playerState?.enableAmbientAutoGain == true
            var liveSlider by remember(whisperVolume) { mutableFloatStateOf(whisperVolume) }
            var previewAmbient by remember { mutableFloatStateOf(0f) }
            val showGainUi = autoGain && enabled
            LaunchedEffect(showGainUi) {
                if (!showGainUi) {
                    previewAmbient = 0f
                    return@LaunchedEffect
                }
                while (true) {
                    previewAmbient = VoiceSatelliteService.getInstance()
                        ?.previewAmbientMicrophoneLevel() ?: 0f
                    delay(50)
                }
            }
            SimpleCard {
                MediaVolumeSlider(enabled = enabled)
                SettingsDivider()
                SettingsInsetWell {
                    WhisperResponseVolumeSlider(
                        volume = whisperVolume,
                        enabled = enabled,
                        autoGainEnabled = showGainUi,
                        ambient = previewAmbient,
                        onVolumeChange = { volume ->
                            liveSlider = volume
                            VoiceSatelliteService.applyVoiceReplyVolumeLive(volume)
                            coroutineScope.launch {
                                viewModel.saveWhisperResponseVolume(volume)
                            }
                        },
                        onVolumeChanging = { volume ->
                            liveSlider = volume
                            VoiceSatelliteService.applyVoiceReplyVolumeLive(volume)
                        },
                    )
                    SettingsWellDivider(
                        modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
                    )
                    SettingRow(
                        label = stringResource(R.string.settings_ambient_auto_gain),
                        subLabel = stringResource(R.string.settings_ambient_auto_gain_desc),
                    ) {
                        ModernSwitch(
                            checked = autoGain,
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                coroutineScope.launch {
                                    viewModel.saveEnableAmbientAutoGain(checked)
                                }
                            },
                        )
                    }
                    AnimatedVisibility(visible = showGainUi) {
                        Column {
                            AmbientAutoGainCards(slider = liveSlider, ambient = previewAmbient)
                            SettingsWellDivider(
                                modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
                            )
                        }
                    }
                    if (!showGainUi) {
                        SettingsWellDivider()
                    }
                    SettingRow(
                        label = stringResource(R.string.settings_whisper_response),
                        subLabel = stringResource(R.string.settings_whisper_response_desc),
                    ) {
                        ModernSwitch(
                            checked = exposeHa,
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                coroutineScope.launch {
                                    viewModel.saveExposeWhisperResponseEntity(checked)
                                }
                            },
                        )
                    }
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showWhisperVolumeGuide = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }
        item {
            ContinuousConversationSettingsCard(
                playerState = playerState,
                enabled = enabled,
                viewModel = viewModel,
                coroutineScope = coroutineScope,
            )
        }

        // —— Interface ——
        item {
            SettingsSectionLabel(stringResource(R.string.settings_voice_reply_section_ui))
        }
        if (!streamingEnabled) {
            item {
                SimpleCard {
                    SwitchSetting(
                        name = stringResource(R.string.settings_streaming_tts_floating_window),
                        description = stringResource(R.string.settings_streaming_tts_floating_window_desc),
                        value = (playerState?.enableFloatingWindow == true) && hasOverlayPermission,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            if (checked && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveFloatingWindow(checked)
                                }
                            }
                        },
                    )
                }
            }
        }
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_ha_switch_overlay),
                    subLabel = stringResource(R.string.settings_ha_switch_overlay_desc),
                ) {
                    ModernSwitch(
                        checked = (playerState?.enableHaSwitchOverlay == true) && hasOverlayPermission,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            if (checked && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveHaSwitchOverlay(checked)
                                }
                            }
                        },
                    )
                }
            }
        }

        // —— Maintenance ——
        item {
            SettingsSectionLabel(stringResource(R.string.settings_voice_reply_section_maintain))
        }
        item {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.settings_preserve_tts_https),
                    description = stringResource(R.string.settings_preserve_tts_https_desc),
                    value = playerState?.preserveTtsHttps == true,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        coroutineScope.launch {
                            viewModel.savePreserveTtsHttps(checked)
                        }
                    },
                )
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_manual_dismiss_button),
                    subLabel = stringResource(R.string.settings_manual_dismiss_button_desc),
                ) {
                    ModernSwitch(
                        checked = playerState?.enableManualDismissButton == true,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.saveManualDismissButton(checked)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showWhisperVolumeGuide) {
        val copyText = buildString {
            appendLine(stringResource(R.string.settings_whisper_response_usage_limit_title))
            appendLine(stringResource(R.string.settings_whisper_response_usage_limit))
            appendLine()
            appendLine(stringResource(R.string.settings_whisper_response_usage_compare_title))
            appendLine(stringResource(R.string.settings_whisper_response_usage_compare))
            appendLine()
            appendLine(stringResource(R.string.settings_whisper_response_usage_live_title))
            appendLine(stringResource(R.string.settings_whisper_response_usage_live))
            appendLine()
            appendLine(stringResource(R.string.settings_whisper_response_usage_ha_title))
            appendLine(stringResource(R.string.settings_whisper_response_usage_ha))
            appendLine()
            appendLine(stringResource(R.string.settings_whisper_response_usage_note_title))
            appendLine(stringResource(R.string.settings_whisper_response_usage_note))
        }
        UsageGuideDialog(
            onDismissRequest = { showWhisperVolumeGuide = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = copyText,
        ) {
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_limit_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_limit),
                fontSize = settingsBodyTextSize(),
                color = getLabelColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_compare_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_compare),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_live_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_live),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_ha_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_ha),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_note_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_whisper_response_usage_note),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
        }
    }
}

@Composable
private fun MediaVolumeSlider(enabled: Boolean) {
    val context = LocalContext.current
    var sliderValue by remember {
        mutableFloatStateOf(VoiceSatelliteService.displayedUserMediaVolume(context) * 100f)
    }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                sliderValue = VoiceSatelliteService.displayedUserMediaVolume(context) * 100f
            }
            delay(250)
        }
    }
    Column {
        SettingItem(
            name = stringResource(R.string.settings_whisper_response_media_volume),
            action = {
                SettingValueBadge(text = "${sliderValue.toInt().coerceIn(0, 100)}%")
            },
        )
        TickSlider(
            value = sliderValue.coerceIn(0f, 100f),
            onValueChange = { value ->
                dragging = true
                sliderValue = value
                VoiceSatelliteService.setUserMediaVolume(context, value / 100f)
            },
            onValueChangeFinished = {
                val snapped = sliderValue.toInt().coerceIn(0, 100)
                sliderValue = snapped.toFloat()
                VoiceSatelliteService.setUserMediaVolume(context, snapped / 100f)
                dragging = false
            },
            enabled = enabled,
            valueRange = 0f..100f,
            steps = 99,
            colors = SliderDefaults.colors(
                thumbColor = getAccentColor(),
                activeTrackColor = getAccentColor(),
                inactiveTrackColor = getSliderInactiveColor(),
                disabledThumbColor = getSliderInactiveColor(),
                disabledActiveTrackColor = getSliderInactiveColor(),
                disabledInactiveTrackColor = getSliderInactiveColor(),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun WhisperResponseVolumeSlider(
    volume: Float,
    enabled: Boolean,
    autoGainEnabled: Boolean,
    ambient: Float,
    onVolumeChange: (Float) -> Unit,
    onVolumeChanging: (Float) -> Unit,
) {
    val minPercent = (PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME * 100f).toInt()
    val percent = (volume * 100f).toInt().coerceIn(minPercent, 100)
    var sliderValue by remember(volume) {
        mutableFloatStateOf(percent.toFloat())
    }
    val sliderNorm = (sliderValue / 100f).coerceIn(0f, 1f)
    val gain = if (autoGainEnabled) AmbientAutoGain.gain(sliderNorm, ambient) else 0f
    val outputPercent = if (autoGainEnabled) {
        AmbientAutoGain.output(sliderNorm, ambient) * 100f
    } else {
        sliderValue
    }
    val marker by animateFloatAsState(
        targetValue = outputPercent,
        animationSpec = tween(120),
        label = "ttsGainMarker",
    )
    val accent = getAccentColor()
    val hatchColor = if (isDarkModeEnabled()) {
        Color.White.copy(alpha = 0.11f)
    } else {
        Color.Black.copy(alpha = 0.09f)
    }
    val pearlIdle = getLabelColor().copy(alpha = 0.28f)
    val gainPercent = (gain * 100f).roundToInt()
    val noiseBand = AmbientAutoGain.noiseBand(ambient)
    val gearPercents = remember(sliderNorm) {
        AmbientAutoGain.virtualGearOutputs(sliderNorm).map { it * 100f }
    }
    val inf = rememberInfiniteTransition(label = "ttsGainPulse")
    val pulse by inf.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(640),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ttsGainPulseA",
    )
    Column {
        SettingItem(
            name = stringResource(R.string.settings_whisper_response_volume),
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (autoGainEnabled && gainPercent > 0) {
                        Text(
                            text = stringResource(R.string.settings_ambient_auto_gain_delta, gainPercent),
                            color = accent.copy(alpha = 0.72f),
                            fontSize = settingsCaptionTextSize(base = 11f),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    SettingValueBadge(
                        text = "${(if (autoGainEnabled) marker else sliderValue).roundToInt()}%",
                    )
                }
            },
        )
        TickSlider(
            value = sliderValue,
            onValueChange = { value ->
                sliderValue = value
                val snapped = value.toInt().coerceIn(minPercent, 100)
                onVolumeChanging(snapped / 100f)
            },
            onValueChangeFinished = {
                val snapped = sliderValue.toInt().coerceIn(minPercent, 100)
                sliderValue = snapped.toFloat()
                onVolumeChange(snapped / 100f)
            },
            enabled = enabled,
            valueRange = minPercent.toFloat()..100f,
            steps = 100 - minPercent - 1,
            colors = SliderDefaults.colors(
                thumbColor = getAccentColor(),
                activeTrackColor = getAccentColor(),
                inactiveTrackColor = getSliderInactiveColor(),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    if (!autoGainEnabled) return@drawWithContent
                    val span = 100f - minPercent
                    if (span <= 0f) return@drawWithContent
                    val thumbR = 10.dp.toPx()
                    fun xOf(p: Float): Float {
                        val t = ((p - minPercent) / span).coerceIn(0f, 1f)
                        return thumbR + t * (size.width - thumbR * 2f)
                    }
                    val x0 = xOf(sliderValue)
                    val x1 = xOf(marker)
                    val xEnd = xOf(100f)
                    val cy = size.height / 2f
                    val hatchH = 8.dp.toPx()
                    val hatchTop = cy - hatchH / 2f
                    val hatchBottom = cy + hatchH / 2f
                    val hatchStart = if (x1 > x0 + 1.5f) x1 else x0
                    if (xEnd > hatchStart + 2f) {
                        clipRect(hatchStart, hatchTop, xEnd, hatchBottom) {
                            val step = 6.5.dp.toPx()
                            var x = hatchStart - hatchH
                            while (x < xEnd) {
                                drawLine(
                                    color = hatchColor,
                                    start = Offset(x, hatchBottom),
                                    end = Offset(x + hatchH, hatchTop),
                                    strokeWidth = 1.35.dp.toPx(),
                                )
                                x += step
                            }
                        }
                    }
                    val bands = AmbientAutoGain.NoiseBand.entries
                    val minPearlGap = 8.dp.toPx()
                    var lastPearlX = x0
                    for (i in gearPercents.indices) {
                        val x = xOf(gearPercents[i])
                        if (x - lastPearlX < minPearlGap) continue
                        val current = bands[i] == noiseBand
                        val r = if (current) 3.2.dp.toPx() else 1.7.dp.toPx()
                        drawCircle(
                            color = if (current) accent.copy(alpha = pulse) else pearlIdle,
                            radius = r,
                            center = Offset(x, cy),
                        )
                        if (current && gainPercent > 0) {
                            drawCircle(
                                color = accent.copy(alpha = pulse * 0.35f),
                                radius = r + 4.dp.toPx(),
                                center = Offset(x, cy),
                            )
                        }
                        lastPearlX = x
                    }
                    if (x1 > x0 + 1.5f) {
                        val bandH = 7.dp.toPx()
                        drawRoundRect(
                            color = accent.copy(alpha = 0.22f + pulse * 0.28f),
                            topLeft = Offset(x0, cy - bandH / 2f - 2.dp.toPx()),
                            size = Size(x1 - x0, bandH + 4.dp.toPx()),
                            cornerRadius = CornerRadius((bandH + 4.dp.toPx()) / 2f),
                        )
                        drawRoundRect(
                            color = accent.copy(alpha = 0.50f),
                            topLeft = Offset(x0, cy - bandH / 2f),
                            size = Size(x1 - x0, bandH),
                            cornerRadius = CornerRadius(bandH / 2f, bandH / 2f),
                        )
                        drawCircle(
                            color = accent.copy(alpha = 0.16f + pulse * 0.18f),
                            radius = 12.dp.toPx(),
                            center = Offset(x1, cy),
                        )
                        drawCircle(
                            color = accent.copy(alpha = 0.45f + pulse * 0.35f),
                            radius = 8.dp.toPx(),
                            center = Offset(x1, cy),
                            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                },
        )
    }
}

@Composable
private fun AmbientAutoGainCards(
    slider: Float,
    ambient: Float,
) {
    val gain = AmbientAutoGain.gain(slider, ambient)
    val noiseText = stringResource(
        when (AmbientAutoGain.noiseBand(ambient)) {
            AmbientAutoGain.NoiseBand.Quiet -> R.string.settings_ambient_noise_quiet
            AmbientAutoGain.NoiseBand.Normal -> R.string.settings_ambient_noise_normal
            AmbientAutoGain.NoiseBand.Noisy -> R.string.settings_ambient_noise_noisy
            AmbientAutoGain.NoiseBand.Loud -> R.string.settings_ambient_noise_loud
        },
    )
    val gainText = stringResource(
        R.string.settings_ambient_auto_gain_delta,
        (gain * 100f).roundToInt(),
    )
    val cardColor = if (isDarkModeEnabled()) {
        Color(0xFF1F1F1F)
    } else {
        Color.White.copy(alpha = 0.48f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AmbientAutoGainStat(
            caption = stringResource(R.string.settings_ambient_auto_gain_noise),
            value = noiseText,
            accent = false,
            background = cardColor,
            modifier = Modifier.weight(1f),
        )
        AmbientAutoGainStat(
            caption = stringResource(R.string.settings_ambient_auto_gain_gain),
            value = gainText,
            accent = gain > 0f,
            background = cardColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AmbientAutoGainStat(
    caption: String,
    value: String,
    accent: Boolean,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = caption,
            fontSize = settingsCaptionTextSize(base = 10f),
            color = getSettingsDescriptionColor(),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            fontSize = settingsTitleTextSize(),
            color = if (accent) getAccentColor() else getLabelColor(),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ContinuousConversationSettingsCard(
    playerState: PlayerSettings?,
    enabled: Boolean,
    viewModel: SettingsViewModel,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val llmSettingsStore = remember { LocalLlmManager.getInstance(context).settingsStore }
    val llmSettings by llmSettingsStore.getFlow().collectAsStateWithLifecycle(
        remember { llmSettingsStore.getCached() },
    )
    val remoteLlm = llmSettings.resolvedPath() == LocalLlmPath.REMOTE
    val continuousEnabled = playerState?.enableContinuousConversation ?: false
    val smartSelected = remoteLlm && playerState?.enableSmartContinue == true
    val questionMarkSelected = !smartSelected && playerState?.enableQuestionMarkContinue == true
    val exitKeywordSelected = !smartSelected && !questionMarkSelected
    LaunchedEffect(continuousEnabled, remoteLlm, playerState) {
        val state = playerState ?: return@LaunchedEffect
        if (continuousEnabled && !remoteLlm && state.enableSmartContinue) {
            viewModel.saveExitKeywordStop(true)
            return@LaunchedEffect
        }
        if (continuousEnabled &&
            !state.enableQuestionMarkContinue &&
            !state.enableExitKeywordStop &&
            !state.enableSmartContinue
        ) {
            viewModel.saveExitKeywordStop(true)
        }
    }
    SimpleCard {
        SettingRow(
            label = stringResource(R.string.settings_continuous_conversation),
            subLabel = stringResource(R.string.settings_continuous_conversation_desc),
        ) {
            ModernSwitch(
                checked = continuousEnabled,
                enabled = enabled,
                onCheckedChange = {
                    coroutineScope.launch {
                        viewModel.saveContinuousConversation(it)
                    }
                },
            )
        }
        if (continuousEnabled) {
            SettingsDivider()
            Text(
                text = stringResource(R.string.settings_continuous_conversation_submode),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            if (remoteLlm) {
                StreamingTtsModeOption(
                    label = stringResource(R.string.settings_smart_continue),
                    description = stringResource(R.string.settings_smart_continue_desc),
                    selected = smartSelected,
                    enabled = enabled,
                    onSelect = {
                        coroutineScope.launch {
                            viewModel.saveSmartContinue(true)
                        }
                    },
                )
                SettingsDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        coroutineScope.launch {
                            viewModel.saveExitKeywordStop(true)
                        }
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = exitKeywordSelected,
                    onClick = {
                        coroutineScope.launch {
                            viewModel.saveExitKeywordStop(true)
                        }
                    },
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = getAccentColor(),
                        unselectedColor = Color(0xFF94A3B8),
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_exit_keyword_stop),
                        color = getTitleColor(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                    )
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_exit_keyword_stop_desc),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                    )
                }
            }
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        coroutineScope.launch {
                            viewModel.saveQuestionMarkContinue(true)
                        }
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = questionMarkSelected,
                    onClick = {
                        coroutineScope.launch {
                            viewModel.saveQuestionMarkContinue(true)
                        }
                    },
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = getAccentColor(),
                        unselectedColor = Color(0xFF94A3B8),
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_question_mark_continue),
                        color = getTitleColor(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                    )
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_question_mark_continue_desc),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingTtsModeOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = getAccentColor(),
                unselectedColor = Color(0xFF94A3B8),
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = getTitleColor(),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
            )
            CollapsibleDescriptionText(
                text = description,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
    }
}

@Composable
private fun WakeModeTileRow(
    selected: WakeMode,
    enabled: Boolean,
    onSelected: (WakeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WakeModeTile(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            iconRes = R.drawable.ic_mic_24,
            label = stringResource(R.string.wake_mode_voice),
            selected = selected == WakeMode.VOICE,
            enabled = enabled,
            onClick = { onSelected(WakeMode.VOICE) },
        )
        WakeModeTile(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            iconRes = R.drawable.ic_mic_24,
            secondaryIconRes = R.drawable.mdi_gesture_tap_button,
            label = stringResource(R.string.wake_mode_hybrid),
            selected = selected == WakeMode.HYBRID,
            enabled = enabled,
            onClick = { onSelected(WakeMode.HYBRID) },
        )
        WakeModeTile(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            iconRes = R.drawable.mdi_gesture_tap_button,
            label = stringResource(R.string.wake_mode_button),
            selected = selected == WakeMode.BUTTON,
            enabled = enabled,
            onClick = { onSelected(WakeMode.BUTTON) },
        )
    }
}

@Composable
private fun WakeModeTile(
    @DrawableRes iconRes: Int,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes secondaryIconRes: Int? = null,
) {
    val accent = getAccentColor()
    val line = if (isDarkModeEnabled()) Color(0xFF3A3A3A) else Color(0xFFE2E8F0)
    val shape = RoundedCornerShape(14.dp)
    val glyph = if (selected) accent else getSettingsDescriptionColor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.5.dp, if (selected) accent else line, shape)
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .settingsClickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(top = 10.dp, bottom = 8.dp, start = 6.dp, end = 6.dp)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (secondaryIconRes != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = glyph,
                        modifier = Modifier.size(16.dp),
                    )
                    Icon(
                        painter = painterResource(secondaryIconRes),
                        contentDescription = null,
                        tint = glyph,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = glyph,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = if (selected) accent else getLabelColor(),
            fontSize = settingsCaptionTextSize(base = 12.5f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QuickWakeGestureRow(
    selected: QuickWakeTrigger,
    enabled: Boolean,
    onSelected: (QuickWakeTrigger) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quick_wake_gesture_label),
            color = getLabelColor(),
            fontSize = settingsCaptionTextSize(base = 13f),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(getSettingsInsetWellColor())
                .padding(2.dp),
        ) {
            QuickWakeGestureChip(
                label = stringResource(R.string.quick_wake_trigger_tap_short),
                selected = selected == QuickWakeTrigger.TAP,
                enabled = enabled,
                onClick = { onSelected(QuickWakeTrigger.TAP) },
            )
            QuickWakeGestureChip(
                label = stringResource(R.string.quick_wake_trigger_hold_short),
                selected = selected == QuickWakeTrigger.HOLD,
                enabled = enabled,
                onClick = { onSelected(QuickWakeTrigger.HOLD) },
            )
        }
    }
}

@Composable
private fun QuickWakeGestureChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = label,
        color = if (selected) getLabelColor() else getSettingsDescriptionColor(),
        fontSize = settingsCaptionTextSize(base = 12f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    !selected -> Color.Transparent
                    isDarkModeEnabled() -> Color(0xFF3A3A3A)
                    else -> Color.White
                },
            )
            .settingsClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
