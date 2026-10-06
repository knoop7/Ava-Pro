package com.example.ava.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.RingtoneManager
import com.example.ava.R
import com.example.ava.microwakeword.WakeWordCutoffPolicy
import com.example.ava.microwakeword.WakeWordWithId
import com.example.ava.openwakeword.OpenWakeWordCutoffPolicy
import com.example.ava.ui.screens.settings.components.SharedRingtonePickerDialog
import com.example.ava.ui.screens.settings.components.SystemRingtoneLoader
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.vswakeword.VsWakeWordCatalogEntry
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.settingsClickable
import com.example.ava.ui.theme.AccentBrown
import androidx.compose.foundation.layout.width

internal val HaOfficialBlue = Color(0xFF18BCF2)

/** Status pill used on the HA identity row and voice hub engine cards. Display only. */
@Composable
internal fun IdentityStatusCapsule(
    text: String,
    active: Boolean,
) {
    val isDark = isDarkModeEnabled()
    val scale = rememberSettingsTextScale()
    val textSize = settingsBodyTextSize(base = 13f)
    Box(
        modifier = Modifier
            .size(width = (68f * scale).dp, height = (28f * scale).dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) {
                    if (isDark) AccentBrown.copy(alpha = 0.28f)
                    else HaOfficialBlue.copy(alpha = 0.14f)
                } else if (isDark) {
                    Color(0xFF3A3A3A)
                } else {
                    Color(0xFFE8EBF1)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                fontSize = textSize,
                lineHeight = textSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            color = if (active) {
                if (isDark) AccentBrown else HaOfficialBlue
            } else {
                getSettingsDescriptionColor()
            },
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
    }
}

/** Compact "严格+" / "极致" tag shown beside a wake slot's chevron (level 1 / 2). */
@Composable
internal fun ExtraStrictnessTag(level: Int) {
    val extreme = level >= 2
    val tint = if (extreme) Color(0xFFFF7A59) else Color(0xFFFFB454)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = if (isDarkModeEnabled()) 0.22f else 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(
                if (extreme) R.string.wake_strictness_extra_level2 else R.string.wake_strictness_extra_level1,
            ),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
internal fun VoiceSettingsEntryRow(
    label: String,
    subLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: String? = null,
    badgeActive: Boolean = false,
) {
    SimpleCard(modifier = Modifier.then(
        if (!enabled) Modifier.alpha(0.45f) else Modifier
    )) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .settingsClickable(enabled = enabled, onClick = onClick),
        ) {
            SettingRow(
                label = label,
                subLabel = subLabel,
            ) {
                if (!badge.isNullOrBlank()) {
                    IdentityStatusCapsule(text = badge, active = badgeActive)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                SettingsChevronIcon(tint = Color(0xFF94A3B8))
            }
        }
    }
}

@Composable
internal fun WakeSoundItem(
    label: String,
    soundUri: String,
    enabled: Boolean,
    context: android.content.Context,
    onSoundSelected: (String) -> Unit,
) {
    val noneLabel = stringResource(R.string.sound_none)
    val defaultLabel = stringResource(R.string.wake_sound_default)
    val unknownLabel = stringResource(R.string.sound_unknown)

    var showRingtonePicker by remember { mutableStateOf(false) }
    var externalSoundUri by remember { mutableStateOf<String?>(null) }

    val audioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            externalSoundUri = uri.toString()
        }
    }

    val defaultUri = "asset:///sounds/wake_word_triggered.wav"
    var ringtones by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var soundName by remember(soundUri) { mutableStateOf(defaultLabel) }

    LaunchedEffect(soundUri, defaultLabel, noneLabel, unknownLabel, defaultUri) {
        soundName = SystemRingtoneLoader.resolveTitle(
            context = context,
            soundUri = soundUri,
            defaultUri = defaultUri,
            defaultLabel = defaultLabel,
            unknownLabel = unknownLabel,
        )
    }

    LaunchedEffect(showRingtonePicker, defaultLabel, noneLabel, unknownLabel, defaultUri) {
        if (!showRingtonePicker || ringtones != null) return@LaunchedEffect
        ringtones = SystemRingtoneLoader.loadRingtones(
            context = context,
            ringtoneType = RingtoneManager.TYPE_NOTIFICATION,
            prefixEntries = listOf(
                defaultLabel to defaultUri,
                noneLabel to "",
            ),
            unknownLabel = unknownLabel,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsClickable(enabled = enabled) { showRingtonePicker = true }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.wake_sound_select_prefix) + soundName,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = Color(0xFF94A3B8))
    }

    if (showRingtonePicker && ringtones != null) {
        SharedRingtonePickerDialog(
            ringtones = ringtones!!,
            currentUri = soundUri,
            context = context,
            title = stringResource(R.string.wake_sound_select),
            externalSoundUri = externalSoundUri,
            onExternalSoundUriConsumed = { externalSoundUri = null },
            onDismiss = { showRingtonePicker = false },
            onConfirm = { uri ->
                onSoundSelected(uri)
                showRingtonePicker = false
            },
            onSelectExternal = {
                audioFileLauncher.launch("audio/*")
            },
        )
    }
}

/** Fallback baseline when no micro model is selected yet. */
private const val SENSITIVITY_FLOOR = 0.5f

/** Copy for the extra-strictness zone. Null when the slot has no virtual tail. */
private fun extraStrictnessHintRes(
    openEngine: Boolean,
    extraLevel: Int,
    extraZoneEnabled: Boolean,
    atCeiling: Boolean = false,
): Int? {
    if (!extraZoneEnabled) return null
    return when {
        extraLevel >= 2 -> if (openEngine) {
            R.string.wake_strictness_hint_open_l2
        } else {
            R.string.wake_strictness_hint_micro_l2
        }
        extraLevel == 1 -> if (openEngine) {
            R.string.wake_strictness_hint_open_l1
        } else {
            R.string.wake_strictness_hint_micro_l1
        }
        atCeiling -> R.string.wake_strictness_hint_at_max
        else -> null
    }
}

// Single source of truth: the detector rejects stored values outside this same
// range as another engine's leftovers, so the slider must not offer anything wider.
private fun microSensitivityRange(manifestCutoff: Float): ClosedFloatingPointRange<Float> =
    WakeWordCutoffPolicy.sliderRange(manifestCutoff)

private fun sensitivityRangeFor(
    manifestCutoff: Float,
    openEngine: Boolean,
    hasBuiltInVerifier: Boolean = false,
): ClosedFloatingPointRange<Float> =
    if (openEngine) OpenWakeWordCutoffPolicy.sliderRange(manifestCutoff, hasBuiltInVerifier)
    else microSensitivityRange(manifestCutoff)

private fun resolveSliderCutoff(
    modelId: String,
    manifestCutoff: Float,
    requestedCutoff: Float,
    openEngine: Boolean,
    hasBuiltInVerifier: Boolean = false,
): Float = if (openEngine) {
    OpenWakeWordCutoffPolicy.resolveRequestedCutoff(
        manifestCutoff,
        requestedCutoff,
        hasBuiltInVerifier,
    )
} else {
    WakeWordCutoffPolicy.resolveRequestedCutoff(
        modelId = modelId,
        manifestCutoff = WakeWordCutoffPolicy.sanitizeManifestCutoff(manifestCutoff),
        requestedCutoff = requestedCutoff,
    )
}

private fun sanitizeManifestCutoff(manifestCutoff: Float, openEngine: Boolean): Float =
    if (openEngine) {
        OpenWakeWordCutoffPolicy.resolveRequestedCutoff(manifestCutoff, -1f)
    } else {
        WakeWordCutoffPolicy.sanitizeManifestCutoff(manifestCutoff)
    }

@Composable
internal fun WakeWordSelectWithSensitivity(
    name: String,
    selected: WakeWordWithId?,
    items: List<WakeWordWithId>,
    sensitivity: Float,
    enabled: Boolean,
    allowNone: Boolean = false,
    noneText: String = "",
    catalogEntries: List<VsWakeWordCatalogEntry> = emptyList(),
    catalogLoadState: WakeWordCatalogLoadState = WakeWordCatalogLoadState.Ready,
    downloadingId: String? = null,
    downloadPercent: Int? = null,
    isRemovable: (String) -> Boolean = { false },
    onDownloadCatalog: (String) -> Unit = {},
    onDeleteInstalled: (String) -> Unit = {},
    /** (selected model, resolved cutoff, extra-strictness level 0..2). */
    onConfirm: (WakeWordWithId?, Float, Int) -> Unit,
    openEngine: Boolean = false,
    /** Stored extra-strictness level for this slot (0 = off). */
    extraStrictness: Int = 0,
    /** Wake slots show the spliced virtual zone; the stop slot must not. */
    extraZoneEnabled: Boolean = false,
    /** Ids whose row never shows the sensitivity slider (builtin DSP stop entry). */
    hideSliderIds: Set<String> = emptySet(),
    /** Stop slot may download dedicated stopClassifier catalog rows. */
    allowStopClassifiers: Boolean = false,
) {
    var showDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val displayValue = selected?.wakeWord?.wake_word ?: noneText
    val installedIds = remember(items) { items.map { it.id }.toSet() }
    val downloadable = remember(catalogEntries, installedIds, allowStopClassifiers) {
        catalogEntries.filter { entry ->
            entry.id !in installedIds && (allowStopClassifiers || !entry.stopClassifier)
        }
    }
    val catalogPending = openEngine &&
        downloadable.isEmpty() &&
        catalogLoadState == WakeWordCatalogLoadState.Loading
    val catalogFailed = openEngine &&
        downloadable.isEmpty() &&
        catalogLoadState == WakeWordCatalogLoadState.Failed
    val showCatalogChrome = downloadable.isNotEmpty() || catalogPending

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsClickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                showDialog = true
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = displayValue,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
            )
        }
        // The chosen extra-strictness zone reads as a compact tag by the chevron; the
        // explanatory sentence stays inside the dialog next to the slider.
        if (extraZoneEnabled && selected != null && extraStrictness >= 1) {
            ExtraStrictnessTag(level = extraStrictness)
            Spacer(modifier = Modifier.width(6.dp))
        }
        SettingsChevronIcon(tint = Color(0xFF94A3B8))
    }

    if (showDialog) {
        var selectedItem by remember { mutableStateOf(selected) }
        // Pin the slot's current word when the dialog opens so a catalog/import
        // pick is at the top. Do not reshuffle on later taps.
        var pinnedId by remember { mutableStateOf(selected?.id) }
        var catalogQuery by remember { mutableStateOf("") }
        val visibleDownloadable = remember(downloadable, catalogQuery) {
            val q = catalogQuery.trim()
            if (q.isEmpty()) downloadable
            else downloadable.filter {
                it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            }
        }
        val (pinnedItem, restItems) = remember(items, pinnedId, selected) {
            wakeWordPickerSections(items, pinnedId, selected)
        }
        val initialSelectedItem = selectedItem
        val defaultCutoff = initialSelectedItem?.wakeWord?.micro?.probability_cutoff
            ?: if (openEngine) OpenWakeWordCutoffPolicy.DEFAULT_THRESHOLD else 0.85f
        val baselineCutoff = sanitizeManifestCutoff(defaultCutoff, openEngine)
        val effectiveValue = if (sensitivity < 0 || initialSelectedItem == null) {
            baselineCutoff
        } else {
            resolveSliderCutoff(
                modelId = initialSelectedItem.id,
                manifestCutoff = baselineCutoff,
                requestedCutoff = sensitivity,
                openEngine = openEngine,
                hasBuiltInVerifier = initialSelectedItem.hasBuiltInVerifier,
            )
        }
        var sliderValue by remember { mutableStateOf(effectiveValue) }
        // Stored level applies to the slot's saved model; picking a different model
        // starts back at the native zone.
        var extraLevelValue by remember {
            mutableStateOf(if (initialSelectedItem != null) extraStrictness.coerceIn(0, 2) else 0)
        }
        val storedSelectedId = selected?.id
        val sensitivityLabel = stringResource(R.string.label_voice_satellite_wake_word_sensitivity)
        // After catalog download, newly installed id moves into items — select it for WW1/WW2.
        var previousDownloadingId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(downloadingId, items) {
            val finishedId = previousDownloadingId
            if (downloadingId == null && !finishedId.isNullOrBlank()) {
                items.firstOrNull { it.id == finishedId }?.let { installed ->
                    selectedItem = installed
                    pinnedId = installed.id
                    val range = sensitivityRangeFor(
                        installed.wakeWord.micro.probability_cutoff,
                        openEngine,
                        installed.hasBuiltInVerifier,
                    )
                    sliderValue = installed.wakeWord.micro.probability_cutoff.coerceIn(range.start, range.endInclusive)
                    extraLevelValue = 0
                }
            }
            previousDownloadingId = downloadingId
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
            title = {
                Text(
                    text = name,
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor(),
                )
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (showCatalogChrome) {
                        androidx.compose.material3.OutlinedTextField(
                            value = catalogQuery,
                            onValueChange = { catalogQuery = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = settingsTitleTextSize(),
                                color = getLabelColor(),
                            ),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.wake_word_catalog_search),
                                    fontSize = settingsBodyTextSize(),
                                    color = getSettingsDescriptionColor(),
                                )
                            },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = getLabelColor(),
                                unfocusedTextColor = getLabelColor(),
                                cursorColor = getAccentColor(),
                                focusedPlaceholderColor = getSettingsDescriptionColor(),
                                unfocusedPlaceholderColor = getSettingsDescriptionColor(),
                                focusedBorderColor = getAccentColor(),
                                unfocusedBorderColor = getSliderInactiveColor(),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                    val pinIsNone = allowNone && pinnedItem == null
                    val hasRestSection = restItems.isNotEmpty() ||
                        (allowNone && pinnedItem != null) ||
                        showCatalogChrome ||
                        catalogFailed
                    if (pinIsNone) {
                        WakeWordPickerNoneRow(
                            text = noneText,
                            selected = selectedItem == null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedItem = null
                            },
                        )
                    } else if (pinnedItem != null) {
                        WakeWordPickerInstalledRow(
                            item = pinnedItem,
                            isSelected = pinnedItem.id == selectedItem?.id,
                            sensitivityLabel = sensitivityLabel,
                            sliderValue = sliderValue,
                            extraLevel = extraLevelValue,
                            extraZoneEnabled = extraZoneEnabled,
                            onSliderChange = { value, level ->
                                sliderValue = value
                                extraLevelValue = level
                            },
                            showSlider = pinnedItem.id !in hideSliderIds,
                            openEngine = openEngine,
                            canDelete = isRemovable(pinnedItem.id),
                            deleteEnabled = downloadingId == null,
                            onDelete = { onDeleteInstalled(pinnedItem.id) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedItem = pinnedItem
                                val range = sensitivityRangeFor(
                                    pinnedItem.wakeWord.micro.probability_cutoff,
                                    openEngine,
                                    pinnedItem.hasBuiltInVerifier,
                                )
                                sliderValue = pinnedItem.wakeWord.micro.probability_cutoff
                                    .coerceIn(range.start, range.endInclusive)
                                extraLevelValue = if (pinnedItem.id == storedSelectedId) {
                                    extraStrictness.coerceIn(0, 2)
                                } else {
                                    0
                                }
                            },
                        )
                    }
                    if ((pinIsNone || pinnedItem != null) && hasRestSection) {
                        WakeWordPickerSectionDivider()
                    }

                    if (allowNone && pinnedItem != null) {
                        WakeWordPickerNoneRow(
                            text = noneText,
                            selected = selectedItem == null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedItem = null
                            },
                        )
                        if (restItems.isNotEmpty() || showCatalogChrome || catalogFailed) {
                            SettingsDivider()
                        }
                    }

                    restItems.forEachIndexed { index, item ->
                        WakeWordPickerInstalledRow(
                            item = item,
                            isSelected = item.id == selectedItem?.id,
                            sensitivityLabel = sensitivityLabel,
                            sliderValue = sliderValue,
                            extraLevel = extraLevelValue,
                            extraZoneEnabled = extraZoneEnabled,
                            onSliderChange = { value, level ->
                                sliderValue = value
                                extraLevelValue = level
                            },
                            showSlider = item.id !in hideSliderIds,
                            openEngine = openEngine,
                            canDelete = isRemovable(item.id),
                            deleteEnabled = downloadingId == null,
                            onDelete = { onDeleteInstalled(item.id) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedItem = item
                                val range = sensitivityRangeFor(
                                    item.wakeWord.micro.probability_cutoff,
                                    openEngine,
                                    item.hasBuiltInVerifier,
                                )
                                sliderValue = item.wakeWord.micro.probability_cutoff
                                    .coerceIn(range.start, range.endInclusive)
                                extraLevelValue = if (item.id == storedSelectedId) {
                                    extraStrictness.coerceIn(0, 2)
                                } else {
                                    0
                                }
                            },
                        )
                        if (index < restItems.lastIndex || showCatalogChrome || catalogFailed) {
                            SettingsDivider()
                        }
                    }

                    if (catalogPending) {
                        OpenWakeWordPickerSkeleton()
                    } else if (catalogFailed) {
                        Text(
                            text = stringResource(R.string.wake_word_catalog_load_failed),
                            fontSize = settingsBodyTextSize(),
                            color = getSettingsDescriptionColor(),
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    } else {
                    visibleDownloadable.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.name,
                                fontSize = settingsTitleTextSize(),
                                color = getLabelColor(),
                                modifier = Modifier.weight(1f),
                            )
                            val busy = downloadingId == entry.id
                            FilledIconButton(
                                onClick = { onDownloadCatalog(entry.id) },
                                enabled = downloadingId == null,
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = getAccentColor().copy(alpha = 0.12f),
                                    contentColor = getAccentColor(),
                                ),
                            ) {
                                if (busy) {
                                    Text(
                                        text = "${(downloadPercent ?: 0).coerceIn(0, 100)}%",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = getAccentColor(),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (index < visibleDownloadable.lastIndex) {
                            SettingsDivider()
                        }
                    }
                    }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val selectedCutoff = selectedItem?.wakeWord?.micro?.probability_cutoff
                        ?.let { sanitizeManifestCutoff(it, openEngine) }
                        ?: if (openEngine) OpenWakeWordCutoffPolicy.DEFAULT_THRESHOLD else SENSITIVITY_FLOOR
                    // Save what the row displayed: it clamps to the model's range, so an
                    // out-of-range stored value must not survive an untouched slider.
                    val range = sensitivityRangeFor(
                        selectedCutoff,
                        openEngine,
                        selectedItem?.hasBuiltInVerifier == true,
                    )
                    val requestedCutoff = sliderValue.coerceIn(range.start, range.endInclusive)
                    val resolvedCutoff = selectedItem?.let { item ->
                        resolveSliderCutoff(
                            modelId = item.id,
                            manifestCutoff = selectedCutoff,
                            requestedCutoff = requestedCutoff,
                            openEngine = openEngine,
                            hasBuiltInVerifier = item.hasBuiltInVerifier,
                        )
                    } ?: requestedCutoff
                    val resolvedExtraLevel = if (extraZoneEnabled && selectedItem != null) {
                        extraLevelValue.coerceIn(0, 2)
                    } else {
                        0
                    }
                    onConfirm(selectedItem, resolvedCutoff, resolvedExtraLevel)
                    showDialog = false
                }) {
                    Text(
                        text = stringResource(R.string.label_ok),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Bold,
                        color = getAccentColor(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        fontSize = settingsTitleTextSize(),
                        color = getSettingsDescriptionColor(),
                    )
                }
            },
        )
    }
}

internal fun wakeWordPickerSections(
    items: List<WakeWordWithId>,
    pinnedId: String?,
    fallback: WakeWordWithId? = null,
): Pair<WakeWordWithId?, List<WakeWordWithId>> {
    if (pinnedId.isNullOrBlank()) return null to items
    val pinned = items.firstOrNull { it.id == pinnedId }
        ?: fallback?.takeIf { it.id == pinnedId }
    if (pinned == null) return null to items
    return pinned to items.filter { it.id != pinned.id }
}

@Composable
private fun WakeWordPickerSectionDivider() {
    Spacer(modifier = Modifier.height(4.dp))
    SettingsDivider()
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun WakeWordPickerNoneRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.padding(end = 8.dp),
            colors = RadioButtonDefaults.colors(
                selectedColor = getAccentColor(),
                unselectedColor = Color(0xFF94A3B8),
            ),
        )
        Text(
            text = text,
            fontSize = settingsTitleTextSize(),
            color = if (selected) getAccentColor() else getLabelColor(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WakeWordPickerInstalledRow(
    item: WakeWordWithId,
    isSelected: Boolean,
    sensitivityLabel: String,
    sliderValue: Float,
    extraLevel: Int,
    extraZoneEnabled: Boolean,
    onSliderChange: (Float, Int) -> Unit,
    openEngine: Boolean,
    showSlider: Boolean = true,
    canDelete: Boolean,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null,
                modifier = Modifier.padding(end = 8.dp),
                colors = RadioButtonDefaults.colors(
                    selectedColor = getAccentColor(),
                    unselectedColor = Color(0xFF94A3B8),
                ),
            )
            Text(
                text = item.wakeWord.wake_word,
                fontSize = settingsTitleTextSize(),
                color = if (isSelected) getAccentColor() else getLabelColor(),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (canDelete) {
                FilledIconButton(
                    onClick = onDelete,
                    enabled = deleteEnabled,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF94A3B8).copy(alpha = 0.18f),
                        contentColor = Color(0xFF94A3B8),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (isSelected && showSlider) {
            val itemCutoff = item.wakeWord.micro.probability_cutoff
            val range = sensitivityRangeFor(
                itemCutoff,
                openEngine,
                item.hasBuiltInVerifier,
            )
            val minVal = range.start
            val maxVal = range.endInclusive
            if (minVal < maxVal) {
                val clampedValue = sliderValue.coerceIn(minVal, maxVal)
                val atCeiling = extraLevel <= 0 && clampedValue >= maxVal - 0.005f
                val hintRes = extraStrictnessHintRes(
                    openEngine = openEngine,
                    extraLevel = extraLevel,
                    extraZoneEnabled = extraZoneEnabled,
                    atCeiling = atCeiling,
                )
                val hint = hintRes?.let { stringResource(it) }
                StrictnessSpliceSlider(
                    label = sensitivityLabel,
                    value = clampedValue,
                    valueRange = range,
                    extraLevel = extraLevel,
                    extraZoneEnabled = extraZoneEnabled,
                    level1Label = stringResource(R.string.wake_strictness_extra_level1),
                    level2Label = stringResource(R.string.wake_strictness_extra_level2),
                    hint = hint,
                    onChange = onSliderChange,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun OpenWakeWordPickerSkeleton(rows: Int = 3) {
    val isDark = isDarkModeEnabled()
    val pulse = rememberInfiniteTransition(label = "oww-picker-skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "oww-picker-skeleton-alpha",
    )
    val bar = if (isDark) Color(0xFF3F3F3F) else Color(0xFFE2E8F0)
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.58f else 0.4f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bar.copy(alpha = alpha)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(bar.copy(alpha = alpha)),
                )
            }
            if (index < rows - 1) {
                SettingsDivider()
            }
        }
        Text(
            text = stringResource(R.string.wake_word_catalog_loading),
            fontSize = settingsBodyTextSize(),
            color = getSettingsDescriptionColor(),
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 4.dp),
        )
    }
}
