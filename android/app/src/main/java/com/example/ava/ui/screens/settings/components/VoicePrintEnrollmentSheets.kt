package com.example.ava.ui.screens.settings.components

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.EspHomeState
import com.example.ava.esphome.Stopped
import com.example.ava.permissions.getVoiceSatellitePermissions
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.services.WebViewPermissionCoordinator
import com.example.ava.voiceprint.VoicePrintStorage
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.VoicePrintEnrollmentMode
import com.example.ava.settings.WakeWordEngine
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.ui.services.BindToService
import com.example.ava.ui.services.rememberLaunchWithMultiplePermissions
import kotlinx.coroutines.delay
import kotlin.math.sin

private const val VOICEPRINT_LISTEN_TICKS = 24
private const val VOICEPRINT_LISTEN_TICK_MS = 80L
/**
 * Soft pre-filter on float-RMS (÷32768), same scale as [VoiceSatelliteService.currentMicrophoneLevel].
 * Native enrollment already accepts ~rms≥0.003 / peak≥0.035; the previous 0.06 gate was calibrated
 * like the Voice Stats ×5 display scale and rejected usable far-field samples before native ran.
 */
private const val VOICEPRINT_MANUAL_MIN_PEAK_LEVEL = 0.025f
private const val VOICEPRINT_PROCESSING_MIN_MS = 900L
private const val VOICEPRINT_SAVING_MIN_MS = 750L
private const val VOICEPRINT_DOT_FILL_MS = 480L
private const val VOICEPRINT_SUCCESS_HOLD_MS = 650L

private suspend fun ensureMinPhaseDuration(startedAtMs: Long, minMs: Long) {
    val elapsed = System.currentTimeMillis() - startedAtMs
    if (elapsed < minMs) delay(minMs - elapsed)
}

enum class VoicePrintEnrollmentSheet {
    None,
    ModePicker,
    AutoInfo,
    ManualEnroll,
}

enum class VoicePrintSamplePhase {
    Idle,
    Listening,
    Processing,
    Saving,
    Accepted,
    AwaitingNextUser,
    Complete,
}

private enum class VoicePrintManualEnrollmentBlocker {
    None,
    ServiceStopped,
    MicrophonePermission,
    MicrophoneMuted,
    HassDisconnected,
}

/** Resolvable from the enrollment sheet primary button (HA connect stays settings-only). */
private fun VoicePrintManualEnrollmentBlocker.isSheetResolvable(): Boolean = when (this) {
    VoicePrintManualEnrollmentBlocker.ServiceStopped,
    VoicePrintManualEnrollmentBlocker.MicrophonePermission,
    VoicePrintManualEnrollmentBlocker.MicrophoneMuted -> true
    VoicePrintManualEnrollmentBlocker.None,
    VoicePrintManualEnrollmentBlocker.HassDisconnected -> false
}

@Composable
private fun rememberVoicePrintManualEnrollmentBlocker(
    service: VoiceSatelliteService?,
    hasMicPermission: Boolean,
    microphoneMuted: Boolean,
): VoicePrintManualEnrollmentBlocker {
    val esphomeState: EspHomeState by service?.voiceSatelliteState?.collectAsStateWithLifecycle(
        initialValue = service?.getState() ?: Stopped,
    ) ?: remember { mutableStateOf(Stopped) }
    val satelliteStarted = service != null &&
        VoiceSatelliteService.isSatelliteStarted() &&
        esphomeState !is Stopped

    return remember(satelliteStarted, hasMicPermission, microphoneMuted, esphomeState) {
        when {
            !satelliteStarted || esphomeState is Stopped ->
                VoicePrintManualEnrollmentBlocker.ServiceStopped
            !hasMicPermission -> VoicePrintManualEnrollmentBlocker.MicrophonePermission
            microphoneMuted -> VoicePrintManualEnrollmentBlocker.MicrophoneMuted
            // Running but not linked to HA — do not mislabel a stopped satellite as this.
            esphomeState is Disconnected -> VoicePrintManualEnrollmentBlocker.HassDisconnected
            else -> VoicePrintManualEnrollmentBlocker.None
        }
    }
}

@Composable
private fun voicePrintManualEnrollmentBlockerMessage(
    blocker: VoicePrintManualEnrollmentBlocker,
): String? = when (blocker) {
    VoicePrintManualEnrollmentBlocker.None -> null
    VoicePrintManualEnrollmentBlocker.ServiceStopped ->
        stringResource(R.string.settings_voice_print_manual_service_required)
    VoicePrintManualEnrollmentBlocker.MicrophonePermission ->
        stringResource(R.string.settings_voice_print_manual_microphone_permission_required)
    VoicePrintManualEnrollmentBlocker.MicrophoneMuted ->
        stringResource(R.string.settings_voice_print_manual_microphone_muted_required)
    VoicePrintManualEnrollmentBlocker.HassDisconnected ->
        stringResource(R.string.settings_voice_print_manual_hass_required)
}

@Composable
fun VoicePrintEnrollmentModeRow(
    mode: VoicePrintEnrollmentMode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val modeLabel = when (mode) {
        VoicePrintEnrollmentMode.AUTO -> stringResource(R.string.settings_voice_print_mode_auto)
        VoicePrintEnrollmentMode.MANUAL -> stringResource(R.string.settings_voice_print_mode_manual)
    }
    val modifier = if (enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.alpha(0.5f)
    }
    SettingItem(
        modifier = modifier,
        name = stringResource(R.string.settings_voice_print_mode_title),
        description = stringResource(R.string.settings_voice_print_mode_desc),
        value = modeLabel,
        action = {
            SettingsChevronIcon(base = 22f)
        },
    )
}

@Composable
private fun VoicePrintManualWakeWordNote(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Text(
        text = stringResource(R.string.settings_voice_print_manual_wake_word_note),
        fontSize = if (compact) 11.sp else 12.sp,
        color = getSettingsDescriptionColor(),
        lineHeight = settingsBodyLineHeight(),
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
fun VoicePrintManualEnrollRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val modifier = if (enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.alpha(0.5f)
    }
    SettingItem(
        modifier = modifier,
        name = stringResource(R.string.settings_voice_print_manual_enroll_title),
        description = stringResource(R.string.settings_voice_print_manual_enroll_desc),
        action = {
            SettingsChevronIcon(base = 22f)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePrintEnrollmentSheetsHost(
    activeSheet: VoicePrintEnrollmentSheet,
    enrollmentMode: VoicePrintEnrollmentMode,
    wakeWordEngine: WakeWordEngine,
    wakeWordPhrase: String,
    manualUser0Samples: Int,
    manualUser1Samples: Int,
    userNames: List<String>,
    microphoneMuted: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: (VoicePrintEnrollmentMode) -> Unit,
    onManualSampleRecorded: (userIndex: Int, newCount: Int) -> Unit,
    onManualUserDeleted: (userIndex: Int) -> Unit,
) {
    // Latch wake suspend for the whole sheet session (mode picker → manual enroll), even when
    // the satellite is not running yet — create-path applies it before VoiceSatellite.start().
    val enrollmentSheetVisible = activeSheet != VoicePrintEnrollmentSheet.None
    if (enrollmentSheetVisible) {
        BindToService(
            autoCreate = true,
            onConnected = { },
            onDisconnected = { },
        )
    }

    DisposableEffect(enrollmentSheetVisible) {
        if (enrollmentSheetVisible) {
            VoiceSatelliteService.requestWakeDetectionSuspended(true)
            onDispose {
                VoiceSatelliteService.requestWakeDetectionSuspended(false)
            }
        } else {
            onDispose { }
        }
    }

    when (activeSheet) {
        VoicePrintEnrollmentSheet.None -> Unit
        VoicePrintEnrollmentSheet.ModePicker -> VoicePrintModePickerSheet(
            selected = enrollmentMode,
            onDismiss = onDismiss,
            onSelect = onSelectMode,
        )
        VoicePrintEnrollmentSheet.AutoInfo -> VoicePrintAutoInfoSheet(onDismiss = onDismiss)
        VoicePrintEnrollmentSheet.ManualEnroll -> VoicePrintManualEnrollmentSheet(
            wakeWordEngine = wakeWordEngine,
            wakeWordPhrase = wakeWordPhrase,
            manualUser0Samples = manualUser0Samples,
            manualUser1Samples = manualUser1Samples,
            userNames = userNames,
            microphoneMuted = microphoneMuted,
            onDismiss = onDismiss,
            onSampleRecorded = onManualSampleRecorded,
            onUserDeleted = onManualUserDeleted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePrintSheetScaffold(
    onDismiss: () -> Unit,
    compact: Boolean = false,
    pinFooter: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val configuration = LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * 0.92f).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sectionSpacing = if (compact) 10.dp else 18.dp
    val horizontalPad = if (compact) 20.dp else 24.dp
    val sheetBackground = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC)
    val scrollState = rememberScrollState()
    val footerReserve = when {
        !pinFooter || footer == null -> 0.dp
        compact -> 108.dp
        else -> 124.dp
    }

    val fillPane = rememberHandleSheetFill()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = fillPane.modifier,
        sheetState = sheetState,
        sheetMaxWidth = fillPane.sheetMaxWidth,
        shape = fillPane.shape,
        containerColor = sheetBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillPane.fill) Modifier.fillMaxHeight() else Modifier),
        ) {
        ModalSheetDragHandle(isDarkMode = isDarkMode, onClick = onDismiss)
        if (pinFooter && footer != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fillPane.fill) {
                            Modifier.weight(1f)
                        } else {
                            Modifier
                                .widthIn(max = 520.dp)
                                .align(Alignment.CenterHorizontally)
                                .heightIn(max = maxSheetHeight)
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = horizontalPad)
                        .padding(top = 4.dp, bottom = footerReserve),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                    content = { content() },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(sheetBackground)
                        .navigationBarsPadding()
                        .padding(horizontal = horizontalPad)
                        .padding(bottom = if (compact) 12.dp else 20.dp),
                    content = { footer() },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fillPane.fill) {
                            Modifier.weight(1f)
                        } else {
                            Modifier
                                .widthIn(max = 520.dp)
                                .align(Alignment.CenterHorizontally)
                                .heightIn(max = maxSheetHeight)
                        },
                    )
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPad)
                    .padding(top = 4.dp, bottom = if (compact) 16.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                content = { content() },
            )
        }
        }
    }
}

/** Full-height iOS-style bottom sheet for manual voiceprint recording (not a floating overlay). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePrintManualFullScreenScaffold(
    onDismiss: () -> Unit,
    title: String,
    footer: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val layout = rememberVoicePrintManualLayoutSpec()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetBackground = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC)
    val titleColor = getTitleColor()
    val fillPane = rememberHandleSheetFill()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = fillPane.modifier,
        sheetState = sheetState,
        sheetMaxWidth = fillPane.sheetMaxWidth,
        shape = fillPane.shape,
        containerColor = sheetBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(sheetBackground)
                .statusBarsPadding(),
        ) {
            ModalSheetDragHandle(
                isDarkMode = isDarkMode,
                onClick = onDismiss,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = layout.horizontalPad)
                    .padding(
                        top = if (layout.isLandscape) 0.dp else 2.dp,
                        bottom = if (subtitle != null) 0.dp else if (layout.isLandscape) 8.dp else 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = if (layout.isLandscape) 18.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                )
            }
            subtitle?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = layout.horizontalPad)
                        .padding(
                            top = if (layout.isLandscape) 4.dp else 6.dp,
                            bottom = if (layout.isLandscape) 8.dp else 12.dp,
                        ),
                ) {
                    it()
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = layout.horizontalPad)
                    .padding(bottom = layout.footerGap),
            ) {
                content()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sheetBackground)
                    .navigationBarsPadding()
                    .padding(horizontal = layout.horizontalPad)
                    .padding(
                        top = if (layout.isLandscape) 4.dp else 8.dp,
                        bottom = if (layout.isLandscape) 12.dp else 20.dp,
                    ),
                content = { footer() },
            )
        }
    }
}

private data class VoicePrintManualLayoutSpec(
    val isLandscape: Boolean,
    val compact: Boolean,
    val horizontalPad: Dp,
    val footerGap: Dp,
    val stagePad: Dp,
    val footerChipHeight: Dp,
    val meterHeight: Dp,
    val completeIconSize: Dp,
)

@Composable
private fun rememberVoicePrintManualLayoutSpec(): VoicePrintManualLayoutSpec {
    val configuration = LocalConfiguration.current
    val height = configuration.screenHeightDp
    val isLandscape = rememberPaneIsLandscape()
    val isShortScreen = height < 420
    val compact = isLandscape || isShortScreen
    return VoicePrintManualLayoutSpec(
        isLandscape = isLandscape,
        compact = compact,
        horizontalPad = if (isLandscape) 20.dp else 24.dp,
        footerGap = if (compact) 6.dp else 10.dp,
        stagePad = when {
            isLandscape && isShortScreen -> 14.dp
            isLandscape -> 18.dp
            else -> 24.dp
        },
        footerChipHeight = when {
            isLandscape && isShortScreen -> 40.dp
            isLandscape -> 42.dp
            else -> 46.dp
        },
        meterHeight = when {
            isLandscape && isShortScreen -> 56.dp
            isLandscape -> 64.dp
            else -> 96.dp
        },
        completeIconSize = if (isLandscape) 40.dp else 56.dp,
    )
}

@Composable
private fun VoicePrintManualPrimaryButton(
    text: String,
    enabled: Boolean,
    accentColor: Color,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 48.dp else 52.dp)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f),
        shape = shape,
        color = accentColor,
        shadowElevation = if (enabled) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 16.sp else 17.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePrintModePickerSheet(
    selected: VoicePrintEnrollmentMode,
    onDismiss: () -> Unit,
    onSelect: (VoicePrintEnrollmentMode) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val accentColor = getAccentColor()
    val titleColor = getTitleColor()
    val subtitleColor = getSettingsDescriptionColor()

    VoicePrintSheetScaffold(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.settings_voice_print_mode_sheet_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = stringResource(R.string.settings_voice_print_mode_sheet_desc),
            fontSize = settingsBodyTextSize(),
            color = subtitleColor,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = cardBackground,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                VoicePrintModeOptionRow(
                    title = stringResource(R.string.settings_voice_print_mode_auto),
                    description = stringResource(R.string.settings_voice_print_mode_auto_desc),
                    selected = selected == VoicePrintEnrollmentMode.AUTO,
                    accentColor = accentColor,
                    titleColor = titleColor,
                    subtitleColor = subtitleColor,
                    onClick = { onSelect(VoicePrintEnrollmentMode.AUTO) },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(1.dp)
                        .background(borderColor),
                )
                VoicePrintModeOptionRow(
                    title = stringResource(R.string.settings_voice_print_mode_manual),
                    description = stringResource(R.string.settings_voice_print_mode_manual_desc),
                    selected = selected == VoicePrintEnrollmentMode.MANUAL,
                    accentColor = accentColor,
                    titleColor = titleColor,
                    subtitleColor = subtitleColor,
                    onClick = { onSelect(VoicePrintEnrollmentMode.MANUAL) },
                )
            }
        }
    }
}

@Composable
fun VoicePrintModeSwitchConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = getTitleColor(),
            )
        },
        text = {
            Text(
                text = message,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmText, color = getAccentColor())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_voice_print_disable_cancel),
                    color = getSettingsDescriptionColor(),
                )
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = getDialogBackground(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePrintAutoInfoSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val accentColor = getAccentColor()
    val titleColor = getTitleColor()
    val subtitleColor = getSettingsDescriptionColor()
    val configuration = LocalConfiguration.current
    val compact = rememberPaneIsLandscape() ||
        configuration.screenHeightDp < 420

    val bulletLines = listOf(
        stringResource(R.string.settings_voice_print_auto_sheet_local),
        stringResource(R.string.settings_voice_print_auto_sheet_offline),
        stringResource(R.string.settings_voice_print_auto_sheet_sensor),
        stringResource(R.string.settings_voice_print_auto_sheet_no_verify),
    )

    VoicePrintSheetScaffold(onDismiss = onDismiss, compact = compact) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
            color = cardBackground,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 22.dp else 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 52.dp else 60.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(if (compact) 24.dp else 28.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_voice_print_auto_sheet_title),
                    fontSize = if (compact) 17.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = if (compact) 14.dp else 18.dp),
                )
                Text(
                    text = stringResource(R.string.settings_voice_print_auto_sheet_desc),
                    fontSize = if (compact) 13.sp else settingsBodyTextSize(),
                    color = subtitleColor,
                    textAlign = TextAlign.Center,
                    lineHeight = if (compact) 18.sp else 20.sp,
                    modifier = Modifier.padding(top = if (compact) 8.dp else 10.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compact) 14.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                ) {
                    bulletLines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "•",
                                color = accentColor,
                                fontSize = if (compact) 13.sp else 14.sp,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = line,
                                color = titleColor,
                                fontSize = if (compact) 13.sp else settingsBodyTextSize(),
                                lineHeight = if (compact) 18.sp else 20.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compact) 18.dp else 24.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onDismiss),
                    shape = RoundedCornerShape(14.dp),
                    color = accentColor,
                ) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_sheet_done),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (compact) 12.dp else 14.dp),
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 15.sp else settingsTitleTextSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePrintModeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    accentColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.SemiBold,
                color = if (selected) accentColor else titleColor,
            )
            CollapsibleDescriptionText(
                text = description,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = subtitleColor,
                topPadding = 3.dp,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (selected) {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = CircleShape,
                color = accentColor,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.5.dp, subtitleColor.copy(alpha = 0.55f)),
            ) {}
        }
    }
}

private fun voicePrintUserCustomName(index: Int, names: List<String>): String? =
    names.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }

@Composable
private fun voicePrintUserSlotLabel(index: Int): String = when (index) {
    0 -> stringResource(R.string.settings_voice_print_user_1_fallback)
    1 -> stringResource(R.string.settings_voice_print_user_2_fallback)
    else -> stringResource(R.string.settings_voice_print_user_1_fallback)
}

@Composable
private fun voicePrintUserDisplayName(index: Int, names: List<String>): String {
    return voicePrintUserCustomName(index, names) ?: voicePrintUserSlotLabel(index)
}

@Composable
private fun VoicePrintWakeWordCallout(
    wakeWordLabel: String,
    accentColor: Color,
    subtitleColor: Color,
    isDarkMode: Boolean,
    compact: Boolean,
) {
    val pulseTransition = rememberInfiniteTransition(label = "wake-word-pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wake-word-scale",
    )
    val pillShape = RoundedCornerShape(999.dp)
    val gradient = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = if (isDarkMode) 0.28f else 0.18f),
            accentColor.copy(alpha = if (isDarkMode) 0.14f else 0.08f),
            accentColor.copy(alpha = if (isDarkMode) 0.22f else 0.14f),
        ),
    )
    val wordSize = if (compact) 20.sp else 24.sp
    val promptSize = if (compact) 14.sp else 16.sp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_voice_print_manual_prompt),
            fontSize = promptSize,
            fontWeight = FontWeight.Medium,
            color = subtitleColor,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .scale(pulseScale)
                .clip(pillShape)
                .background(gradient)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.45f),
                            accentColor.copy(alpha = 0.18f),
                            accentColor.copy(alpha = 0.35f),
                        ),
                    ),
                    shape = pillShape,
                )
                .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 8.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = wakeWordLabel,
                fontSize = wordSize,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                letterSpacing = 0.2.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePrintManualEnrollmentSheet(
    wakeWordEngine: WakeWordEngine,
    wakeWordPhrase: String,
    manualUser0Samples: Int,
    manualUser1Samples: Int,
    userNames: List<String>,
    microphoneMuted: Boolean,
    onDismiss: () -> Unit,
    onSampleRecorded: (userIndex: Int, newCount: Int) -> Unit,
    onUserDeleted: (userIndex: Int) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val accentColor = getAccentColor()
    val titleColor = getTitleColor()
    val subtitleColor = getSettingsDescriptionColor()
    val total = MicrophoneSettingsStore.MANUAL_ENROLLMENT_SAMPLES

    val user0Name = voicePrintUserDisplayName(0, userNames)
    val user1Name = voicePrintUserDisplayName(1, userNames)

    var activeUserIndex by remember {
        mutableIntStateOf(
            when {
                manualUser0Samples < total -> 0
                manualUser1Samples < total -> 1
                else -> 0
            },
        )
    }
    var phase by remember { mutableStateOf(VoicePrintSamplePhase.Idle) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    var showFailed by remember { mutableStateOf(false) }
    var displayCompleted by remember {
        mutableIntStateOf(if (activeUserIndex == 0) manualUser0Samples else manualUser1Samples)
    }
    var animatingDotIndex by remember { mutableStateOf<Int?>(null) }
    var recordingSession by remember { mutableIntStateOf(0) }
    var pendingDeleteUserIndex by remember { mutableStateOf<Int?>(null) }
    var boundService by remember { mutableStateOf(VoiceSatelliteService.getInstance()) }
    var hasMicPermission by remember {
        mutableStateOf(WebViewPermissionCoordinator.hasRecordAudioPermission(context))
    }

    BindToService(
        autoCreate = true,
        onConnected = { boundService = it },
        onDisconnected = { boundService = VoiceSatelliteService.getInstance() },
    )

    val voiceChannelStore = remember { VoiceChannelSettingsStore(appContext.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsStateWithLifecycle(true)
    val startServicePermissions = remember(voiceChannelEnabled) {
        getVoiceSatellitePermissions(voiceChannelEnabled)
    }

    fun startVoiceServiceBestEffort() {
        val existing = VoiceSatelliteService.getInstance() ?: boundService
        if (existing != null) {
            existing.startVoiceSatellite()
            return
        }
        val intent = Intent(appContext, VoiceSatelliteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    val requestStartServicePermissions = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = {
            hasMicPermission = WebViewPermissionCoordinator.hasRecordAudioPermission(context)
            startVoiceServiceBestEffort()
        },
        onPermissionDenied = {
            hasMicPermission = WebViewPermissionCoordinator.hasRecordAudioPermission(context)
        },
    )
    val requestMicPermission = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = {
            hasMicPermission = true
        },
        onPermissionDenied = {
            hasMicPermission = WebViewPermissionCoordinator.hasRecordAudioPermission(context)
        },
    )

    val activeSamples = if (activeUserIndex == 0) manualUser0Samples else manualUser1Samples
    val bothUsersComplete = manualUser0Samples >= total && manualUser1Samples >= total
    val user1Unlocked = manualUser0Samples >= total || manualUser1Samples > 0

    LaunchedEffect(activeUserIndex, manualUser0Samples, manualUser1Samples) {
        if (animatingDotIndex == null &&
            (phase == VoicePrintSamplePhase.Idle || phase == VoicePrintSamplePhase.AwaitingNextUser)
        ) {
            displayCompleted = if (activeUserIndex == 0) manualUser0Samples else manualUser1Samples
        }
    }

    val displayWakeWord = wakeWordPhrase.ifBlank {
        stringResource(R.string.settings_voice_print_manual_wake_word_fallback)
    }

    val enrollmentBlocker = rememberVoicePrintManualEnrollmentBlocker(
        service = boundService,
        hasMicPermission = hasMicPermission,
        microphoneMuted = microphoneMuted,
    )
    val enrollmentReady = enrollmentBlocker == VoicePrintManualEnrollmentBlocker.None

    LaunchedEffect(recordingSession, enrollmentReady) {
        if (recordingSession == 0 || !enrollmentReady) return@LaunchedEffect
        val userIndex = activeUserIndex
        val baseSamples = if (userIndex == 0) manualUser0Samples else manualUser1Samples
        val otherSamples = if (userIndex == 0) manualUser1Samples else manualUser0Samples
        showFailed = false
        phase = VoicePrintSamplePhase.Listening
        val service = VoiceSatelliteService.getInstance()
        service?.markVoicePrintEnrollmentStart()
        repeat(VOICEPRINT_LISTEN_TICKS) {
            // Meter keeps the smoothed live level; the gate uses the pre-AEC frame peak
            // accumulated on the capture thread (avoids EMA under-reading short wake words).
            micLevel = service?.currentMicrophoneLevel() ?: 0f
            delay(VOICEPRINT_LISTEN_TICK_MS)
        }
        service?.stopVoicePrintEnrollmentListen()
        val peakLevel = service?.currentVoicePrintEnrollmentPeakLevel() ?: 0f
        if (peakLevel < VOICEPRINT_MANUAL_MIN_PEAK_LEVEL) {
            showFailed = true
            phase = VoicePrintSamplePhase.Idle
            return@LaunchedEffect
        }
        phase = VoicePrintSamplePhase.Processing
        val processingStarted = System.currentTimeMillis()
        val accepted = VoiceSatelliteService.getInstance()?.enrollVoicePrintSample(userIndex) ?: false
        ensureMinPhaseDuration(processingStarted, VOICEPRINT_PROCESSING_MIN_MS)
        if (!accepted) {
            showFailed = true
            phase = VoicePrintSamplePhase.Idle
            return@LaunchedEffect
        }
        val newCount = (baseSamples + 1).coerceAtMost(total)
        phase = VoicePrintSamplePhase.Saving
        delay(VOICEPRINT_SAVING_MIN_MS)
        animatingDotIndex = newCount - 1
        onSampleRecorded(userIndex, newCount)
        delay(VOICEPRINT_DOT_FILL_MS)
        displayCompleted = newCount
        animatingDotIndex = null
        phase = when {
            newCount >= total && userIndex == 0 && otherSamples < total ->
                VoicePrintSamplePhase.AwaitingNextUser
            newCount >= total ->
                VoicePrintSamplePhase.Complete
            else ->
                VoicePrintSamplePhase.Accepted
        }
    }

    LaunchedEffect(phase) {
        if (phase == VoicePrintSamplePhase.Accepted) {
            delay(VOICEPRINT_SUCCESS_HOLD_MS)
            phase = VoicePrintSamplePhase.Idle
        }
    }

    val overlayVisible = phase == VoicePrintSamplePhase.Listening ||
        phase == VoicePrintSamplePhase.Processing ||
        phase == VoicePrintSamplePhase.Saving ||
        phase == VoicePrintSamplePhase.Accepted

    val cardFill = if (isDarkMode) Color(0xFF1C1C1C) else Color.White
    val cardBorder = if (isDarkMode) Color(0xFF2E2E2E) else Color(0xFFE8ECF0)
    val meterFill = if (isDarkMode) Color(0xFF141414) else Color(0xFFF4F6F8)
    val layout = rememberVoicePrintManualLayoutSpec()
    val compact = layout.compact
    val meterHeight = layout.meterHeight
    val completeIconSize = layout.completeIconSize

    val user0Stored = manualUser0Samples >= total ||
        VoicePrintStorage.hasUserProfile(context, 0, wakeWordEngine)
    val user1Stored = manualUser1Samples >= total ||
        VoicePrintStorage.hasUserProfile(context, 1, wakeWordEngine)
    val canDeleteUser = phase == VoicePrintSamplePhase.Idle ||
        phase == VoicePrintSamplePhase.AwaitingNextUser ||
        phase == VoicePrintSamplePhase.Complete

    fun deleteUser(userIndex: Int) {
        onUserDeleted(userIndex)
        showFailed = false
        recordingSession = 0
        if (activeUserIndex == userIndex) {
            activeUserIndex = when {
                userIndex == 0 && manualUser1Samples > 0 -> 1
                else -> 0
            }
        }
        displayCompleted = if (activeUserIndex == 0) {
            if (userIndex == 0) 0 else manualUser0Samples
        } else {
            if (userIndex == 1) 0 else manualUser1Samples
        }
        phase = VoicePrintSamplePhase.Idle
        pendingDeleteUserIndex = null
    }

    fun selectUser(index: Int) {
        if (index == activeUserIndex) return
        if (index == 1 && !user1Unlocked) return
        if (phase != VoicePrintSamplePhase.Idle && phase != VoicePrintSamplePhase.AwaitingNextUser) return
        activeUserIndex = index
        displayCompleted = if (index == 0) manualUser0Samples else manualUser1Samples
        showFailed = false
        if (phase == VoicePrintSamplePhase.AwaitingNextUser && index == 1) {
            phase = VoicePrintSamplePhase.Idle
        }
    }

    VoicePrintManualFullScreenScaffold(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_voice_print_manual_sheet_title),
        subtitle = {
            VoicePrintManualWakeWordNote(
                compact = compact,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        footer = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(layout.footerGap),
            ) {
                VoicePrintManualFooterUserBar(
                    manualUser0Samples = manualUser0Samples,
                    manualUser1Samples = manualUser1Samples,
                    userNames = userNames,
                    total = total,
                    user0Stored = user0Stored,
                    user1Stored = user1Stored,
                    activeUserIndex = activeUserIndex,
                    user1Unlocked = user1Unlocked,
                    overlayVisible = overlayVisible,
                    phase = phase,
                    canDeleteUser = canDeleteUser,
                    displayCompleted = displayCompleted,
                    animatingDotIndex = animatingDotIndex,
                    accentColor = accentColor,
                    titleColor = titleColor,
                    subtitleColor = subtitleColor,
                    isDarkMode = isDarkMode,
                    compact = compact,
                    chipHeight = layout.footerChipHeight,
                    onSelectUser = ::selectUser,
                    onDeleteUser = { pendingDeleteUserIndex = it },
                )

                val recordingLocked = phase == VoicePrintSamplePhase.Listening ||
                    phase == VoicePrintSamplePhase.Processing ||
                    phase == VoicePrintSamplePhase.Saving ||
                    phase == VoicePrintSamplePhase.Accepted
                val activeUserComplete = activeSamples >= total
                val canRecord = enrollmentReady && !recordingLocked &&
                    phase != VoicePrintSamplePhase.Complete &&
                    !(activeUserComplete && phase != VoicePrintSamplePhase.AwaitingNextUser)
                val blockerActionable = phase == VoicePrintSamplePhase.Idle &&
                    !recordingLocked &&
                    enrollmentBlocker.isSheetResolvable()
                val blockerActionLabel = when (enrollmentBlocker) {
                    VoicePrintManualEnrollmentBlocker.ServiceStopped ->
                        stringResource(R.string.settings_voice_print_manual_service_required)
                    VoicePrintManualEnrollmentBlocker.MicrophonePermission ->
                        stringResource(R.string.settings_voice_print_manual_microphone_permission_required)
                    VoicePrintManualEnrollmentBlocker.MicrophoneMuted ->
                        stringResource(R.string.settings_voice_print_manual_microphone_muted_required)
                    else -> null
                }

                VoicePrintManualPrimaryButton(
                    text = when (phase) {
                        VoicePrintSamplePhase.Complete ->
                            stringResource(R.string.settings_voice_print_sheet_done)
                        VoicePrintSamplePhase.AwaitingNextUser ->
                            stringResource(R.string.settings_voice_print_manual_next_user, user1Name)
                        else -> blockerActionLabel
                            ?: stringResource(R.string.settings_voice_print_manual_start)
                    },
                    enabled = when (phase) {
                        VoicePrintSamplePhase.Complete -> true
                        VoicePrintSamplePhase.AwaitingNextUser -> true
                        else -> (canRecord && !activeUserComplete) || blockerActionable
                    },
                    accentColor = accentColor,
                    compact = compact,
                    onClick = {
                        when (phase) {
                            VoicePrintSamplePhase.Complete -> onDismiss()
                            VoicePrintSamplePhase.AwaitingNextUser -> {
                                activeUserIndex = 1
                                displayCompleted = manualUser1Samples
                                showFailed = false
                                phase = VoicePrintSamplePhase.Idle
                            }
                            else -> when {
                                canRecord && !activeUserComplete -> recordingSession++
                                enrollmentBlocker == VoicePrintManualEnrollmentBlocker.ServiceStopped ->
                                    requestStartServicePermissions(startServicePermissions)
                                enrollmentBlocker == VoicePrintManualEnrollmentBlocker.MicrophonePermission ->
                                    requestMicPermission(arrayOf(Manifest.permission.RECORD_AUDIO))
                                enrollmentBlocker == VoicePrintManualEnrollmentBlocker.MicrophoneMuted ->
                                    VoiceSatelliteService.setMicMute(false)
                            }
                        }
                    },
                )
            }
        },
    ) {
        val stageShape = RoundedCornerShape(if (layout.isLandscape) 20.dp else 24.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(stageShape)
                .background(cardFill)
                .border(1.dp, cardBorder, stageShape),
            contentAlignment = Alignment.Center,
        ) {
            VoicePrintManualStageContent(
                phase = phase,
                overlayVisible = overlayVisible,
                enrollmentBlocker = enrollmentBlocker,
                displayWakeWord = displayWakeWord,
                showFailed = showFailed,
                bothUsersComplete = bothUsersComplete,
                activeUserIndex = activeUserIndex,
                displayCompleted = displayCompleted,
                total = total,
                user0Name = user0Name,
                user1Name = user1Name,
                micLevel = micLevel,
                accentColor = accentColor,
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                isDarkMode = isDarkMode,
                meterFill = meterFill,
                compact = compact,
                completeIconSize = completeIconSize,
                meterHeight = meterHeight,
                stagePad = layout.stagePad,
            )
        }
    }

    pendingDeleteUserIndex?.let { userIndex ->
        val userName = if (userIndex == 0) user0Name else user1Name
        AlertDialog(
            onDismissRequest = { pendingDeleteUserIndex = null },
            title = {
                Text(
                    text = stringResource(R.string.settings_voice_print_manual_user_delete_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = titleColor,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.settings_voice_print_manual_user_delete_message,
                        userName,
                    ),
                    fontSize = settingsBodyTextSize(),
                    color = subtitleColor,
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteUser(userIndex) }) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_manual_user_delete_confirm),
                        color = Color(0xFFEF4444),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteUserIndex = null }) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_disable_cancel),
                        color = accentColor,
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
        )
    }
}

@Composable
private fun VoicePrintManualFooterUserBar(
    manualUser0Samples: Int,
    manualUser1Samples: Int,
    userNames: List<String>,
    total: Int,
    user0Stored: Boolean,
    user1Stored: Boolean,
    activeUserIndex: Int,
    user1Unlocked: Boolean,
    overlayVisible: Boolean,
    phase: VoicePrintSamplePhase,
    canDeleteUser: Boolean,
    displayCompleted: Int,
    animatingDotIndex: Int?,
    accentColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    isDarkMode: Boolean,
    compact: Boolean,
    chipHeight: Dp,
    onSelectUser: (Int) -> Unit,
    onDeleteUser: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        VoicePrintManualUserChipRow(
            modifier = Modifier.weight(1f),
            manualUser0Samples = manualUser0Samples,
            manualUser1Samples = manualUser1Samples,
            userNames = userNames,
            total = total,
            user0Stored = user0Stored,
            user1Stored = user1Stored,
            activeUserIndex = activeUserIndex,
            user1Unlocked = user1Unlocked,
            overlayVisible = overlayVisible,
            phase = phase,
            canDeleteUser = canDeleteUser,
            accentColor = accentColor,
            titleColor = titleColor,
            subtitleColor = subtitleColor,
            isDarkMode = isDarkMode,
            compact = compact,
            chipHeight = chipHeight,
            stackVertically = false,
            onSelectUser = onSelectUser,
            onDeleteUser = onDeleteUser,
        )
        VoicePrintEnrollmentDotProgress(
            completed = displayCompleted,
            total = total,
            accentColor = accentColor,
            inactiveColor = if (isDarkMode) Color(0xFF3A3A3A) else Color(0xFFE2E8F0),
            compact = true,
            animatingIndex = animatingDotIndex,
            large = false,
            centered = false,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun VoicePrintManualUserChipRow(
    manualUser0Samples: Int,
    manualUser1Samples: Int,
    userNames: List<String>,
    total: Int,
    user0Stored: Boolean,
    user1Stored: Boolean,
    activeUserIndex: Int,
    user1Unlocked: Boolean,
    overlayVisible: Boolean,
    phase: VoicePrintSamplePhase,
    canDeleteUser: Boolean,
    accentColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    isDarkMode: Boolean,
    compact: Boolean,
    chipHeight: Dp = if (compact) 44.dp else 48.dp,
    stackVertically: Boolean = false,
    modifier: Modifier = Modifier,
    onSelectUser: (Int) -> Unit,
    onDeleteUser: (Int) -> Unit,
) {
    val chipGap = if (compact) 10.dp else 12.dp
    val chip0 = @Composable { mod: Modifier ->
        VoicePrintUserChip(
            modifier = mod,
            slotLabel = voicePrintUserSlotLabel(0),
            customName = voicePrintUserCustomName(0, userNames),
            samples = manualUser0Samples,
            total = total,
            stored = user0Stored,
            selected = activeUserIndex == 0,
            recording = overlayVisible && activeUserIndex == 0,
            enabled = phase == VoicePrintSamplePhase.Idle ||
                phase == VoicePrintSamplePhase.AwaitingNextUser,
            accentColor = accentColor,
            titleColor = titleColor,
            subtitleColor = subtitleColor,
            isDarkMode = isDarkMode,
            compact = compact,
            chipHeight = chipHeight,
            onClick = { onSelectUser(0) },
            onDelete = if (canDeleteUser && (manualUser0Samples > 0 || user0Stored)) {
                { onDeleteUser(0) }
            } else {
                null
            },
        )
    }
    val chip1 = @Composable { mod: Modifier ->
        VoicePrintUserChip(
            modifier = mod,
            slotLabel = voicePrintUserSlotLabel(1),
            customName = voicePrintUserCustomName(1, userNames),
            samples = manualUser1Samples,
            total = total,
            stored = user1Stored,
            selected = activeUserIndex == 1,
            recording = overlayVisible && activeUserIndex == 1,
            enabled = user1Unlocked && (
                phase == VoicePrintSamplePhase.Idle ||
                    phase == VoicePrintSamplePhase.AwaitingNextUser
                ),
            locked = !user1Unlocked,
            accentColor = accentColor,
            titleColor = titleColor,
            subtitleColor = subtitleColor,
            isDarkMode = isDarkMode,
            compact = compact,
            chipHeight = chipHeight,
            onClick = { onSelectUser(1) },
            onDelete = if (canDeleteUser && (manualUser1Samples > 0 || user1Stored)) {
                { onDeleteUser(1) }
            } else {
                null
            },
        )
    }
    if (stackVertically) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(chipGap),
        ) {
            chip0(Modifier.fillMaxWidth())
            chip1(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(chipGap),
        ) {
            chip0(Modifier.weight(1f))
            chip1(Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoicePrintUserChip(
    slotLabel: String,
    customName: String?,
    samples: Int,
    total: Int,
    stored: Boolean,
    selected: Boolean,
    recording: Boolean,
    enabled: Boolean,
    accentColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    isDarkMode: Boolean,
    compact: Boolean,
    chipHeight: Dp = if (compact) 44.dp else 48.dp,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val titleText = customName ?: slotLabel
    val progressText = stringResource(
        R.string.settings_voice_print_manual_progress,
        samples.coerceIn(0, total),
        total,
    )
    val chipAlpha = when {
        locked -> 0.42f
        !enabled -> 0.68f
        else -> 1f
    }
    val shape = RoundedCornerShape(percent = 50)
    val borderColor = if (isDarkMode) Color(0xFF3A3A3A) else Color(0xFFE2E8F0)
    val showDelete = onDelete != null && enabled && !locked && !recording
    val horizontalPad = if (compact) 14.dp else 16.dp
    val deleteSlot = if (compact) 30.dp else 34.dp
    Box(modifier = modifier.alpha(chipAlpha)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chipHeight)
                .clip(shape)
                .background(
                    when {
                        recording -> accentColor.copy(alpha = 0.18f)
                        selected -> accentColor.copy(alpha = if (isDarkMode) 0.14f else 0.10f)
                        else -> if (isDarkMode) Color(0xFF1F1F1F) else Color.White
                    },
                )
                .border(
                    width = if (selected || recording) 1.5.dp else 1.dp,
                    color = when {
                        recording || selected -> accentColor
                        locked -> borderColor.copy(alpha = 0.5f)
                        else -> borderColor
                    },
                    shape = shape,
                )
                .then(if (enabled && !locked) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = horizontalPad, end = if (showDelete) 0.dp else horizontalPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = titleText,
                modifier = Modifier.weight(1f),
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected || recording) accentColor else titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            Text(
                text = progressText,
                modifier = Modifier.padding(start = 8.dp, end = if (showDelete) 2.dp else 0.dp),
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected || recording) accentColor else subtitleColor,
                maxLines = 1,
                softWrap = false,
            )
            if (showDelete) {
                Box(
                    modifier = Modifier
                        .width(deleteSlot)
                        .fillMaxHeight()
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_voice_print_manual_user_delete),
                        tint = subtitleColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePrintManualStageContent(
    phase: VoicePrintSamplePhase,
    overlayVisible: Boolean,
    enrollmentBlocker: VoicePrintManualEnrollmentBlocker,
    displayWakeWord: String,
    showFailed: Boolean,
    bothUsersComplete: Boolean,
    activeUserIndex: Int,
    displayCompleted: Int,
    total: Int,
    user0Name: String,
    user1Name: String,
    micLevel: Float,
    accentColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    isDarkMode: Boolean,
    meterFill: Color,
    compact: Boolean,
    completeIconSize: Dp,
    meterHeight: Dp,
    stagePad: Dp,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (!overlayVisible) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(stagePad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (phase) {
                    VoicePrintSamplePhase.Complete -> {
                        Box(
                            modifier = Modifier
                                .size(completeIconSize)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(completeIconSize * 0.5f),
                            )
                        }
                        Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))
                        Text(
                            text = if (bothUsersComplete || (activeUserIndex == 1 && displayCompleted >= total)) {
                                stringResource(R.string.settings_voice_print_manual_all_complete)
                            } else {
                                stringResource(R.string.settings_voice_print_manual_complete)
                            },
                            fontSize = if (compact) 15.sp else 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = titleColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                    VoicePrintSamplePhase.AwaitingNextUser -> {
                        Text(
                            text = stringResource(
                                R.string.settings_voice_print_manual_awaiting_next,
                                user0Name,
                                user1Name,
                            ),
                            fontSize = if (compact) 14.sp else 15.sp,
                            color = subtitleColor,
                            textAlign = TextAlign.Center,
                            lineHeight = if (compact) 20.sp else 22.sp,
                        )
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                        ) {
                            VoicePrintWakeWordCallout(
                                wakeWordLabel = displayWakeWord,
                                accentColor = accentColor,
                                subtitleColor = subtitleColor,
                                isDarkMode = isDarkMode,
                                compact = compact,
                            )
                            val blockerMessage = voicePrintManualEnrollmentBlockerMessage(enrollmentBlocker)
                            when {
                                blockerMessage != null -> {
                                    Text(
                                        text = blockerMessage,
                                        fontSize = if (compact) 12.sp else 13.sp,
                                        color = Color(0xFFF59E0B),
                                        textAlign = TextAlign.Center,
                                        lineHeight = if (compact) 17.sp else 19.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = if (compact) 8.dp else 12.dp),
                                    )
                                }
                                phase == VoicePrintSamplePhase.Idle && showFailed -> {
                                    Text(
                                        text = stringResource(R.string.settings_voice_print_manual_failed),
                                        fontSize = if (compact) 13.sp else 14.sp,
                                        color = Color(0xFFF59E0B),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        VoicePrintEnrollmentOverlay(
            visible = overlayVisible,
            phase = phase,
            micLevel = micLevel,
            activeUserName = "",
            accentColor = accentColor,
            subtitleColor = subtitleColor,
            titleColor = titleColor,
            meterFill = meterFill,
            compact = compact,
            cardSpacing = if (compact) 10.dp else 14.dp,
            meterHeight = meterHeight,
            showUserTitle = false,
            fillStage = true,
        )
    }
}

@Composable
private fun VoicePrintUserSelectCard(
    slotLabel: String,
    customName: String?,
    samples: Int,
    total: Int,
    stored: Boolean,
    selected: Boolean,
    recording: Boolean,
    enabled: Boolean,
    accentColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    borderColor: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val titleText = customName ?: slotLabel
    val statusText = when {
        recording -> stringResource(R.string.settings_voice_print_manual_user_in_progress)
        locked -> stringResource(R.string.settings_voice_print_manual_user_waiting)
        stored -> stringResource(R.string.settings_voice_print_manual_user_stored)
        samples > 0 -> stringResource(R.string.settings_voice_print_manual_user_in_progress)
        else -> stringResource(R.string.settings_voice_print_manual_user_waiting)
    }
    val cardAlpha = when {
        locked -> 0.42f
        !enabled -> 0.68f
        else -> 1f
    }
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    val showDelete = onDelete != null && enabled && !locked && !recording
    val progressText = stringResource(
        R.string.settings_voice_print_manual_progress,
        samples.coerceIn(0, total),
        total,
    )
    Box(
        modifier = modifier.alpha(cardAlpha),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    when {
                        recording -> accentColor.copy(alpha = if (compact) 0.16f else 0.14f)
                        selected -> accentColor.copy(alpha = if (compact) 0.12f else 0.10f)
                        else -> Color.Transparent
                    },
                )
                .border(
                    width = if (selected || recording) 2.dp else 1.dp,
                    color = when {
                        recording || selected -> accentColor
                        locked -> borderColor.copy(alpha = 0.45f)
                        else -> borderColor
                    },
                    shape = shape,
                )
                .then(if (enabled && !locked) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(
                    start = if (compact) 8.dp else 10.dp,
                    end = if (compact) 8.dp else 10.dp,
                    top = if (compact) 7.dp else 8.dp,
                    bottom = if (compact) 7.dp else 8.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = titleText,
                    modifier = Modifier.weight(1f),
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected || recording) accentColor else titleColor,
                    maxLines = 1,
                )
                Text(
                    text = progressText,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected || recording) accentColor else subtitleColor,
                    modifier = if (showDelete) {
                        Modifier.padding(end = if (compact) 14.dp else 10.dp)
                    } else {
                        Modifier
                    },
                )
            }
            if (customName != null) {
                Text(
                    text = slotLabel,
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = subtitleColor,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Text(
                text = statusText,
                fontSize = if (compact) 10.sp else 11.sp,
                color = when {
                    recording || stored -> accentColor
                    selected && samples > 0 -> accentColor
                    else -> subtitleColor
                },
                maxLines = 1,
                modifier = Modifier.padding(top = if (customName != null) 1.dp else 2.dp),
            )
        }
        if (showDelete) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.settings_voice_print_manual_user_delete),
                tint = subtitleColor.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (compact) 6.dp else 7.dp,
                        end = if (compact) 6.dp else 7.dp,
                    )
                    .size(if (compact) 12.dp else 14.dp)
                    .clickable(onClick = onDelete),
            )
        }
    }
}

@Composable
private fun BoxScope.VoicePrintEnrollmentOverlay(
    visible: Boolean,
    phase: VoicePrintSamplePhase,
    micLevel: Float,
    activeUserName: String,
    accentColor: Color,
    subtitleColor: Color,
    titleColor: Color,
    meterFill: Color,
    compact: Boolean,
    cardSpacing: Dp,
    meterHeight: Dp,
    showUserTitle: Boolean = true,
    fillStage: Boolean = false,
) {
    val overlayBackground = if (fillStage) {
        Color(0xFF0A0A0A)
    } else {
        Color.Black.copy(alpha = if (compact) 0.08f else 0.06f)
    }
    val meterWellColor = if (fillStage) {
        Color(0xFF141414)
    } else {
        meterFill.copy(alpha = 0.92f)
    }
    val statusColor = if (fillStage) {
        Color(0xFFE2E8F0)
    } else if (phase == VoicePrintSamplePhase.Accepted) {
        accentColor
    } else {
        titleColor
    }
    val statusText = when (phase) {
        VoicePrintSamplePhase.Listening ->
            stringResource(R.string.settings_voice_print_manual_listening)
        VoicePrintSamplePhase.Processing ->
            stringResource(R.string.settings_voice_print_manual_processing)
        VoicePrintSamplePhase.Saving ->
            stringResource(R.string.settings_voice_print_manual_saving)
        VoicePrintSamplePhase.Accepted ->
            stringResource(R.string.settings_voice_print_manual_sample_ok)
        else -> ""
    }
    val checkScale by animateFloatAsState(
        targetValue = if (phase == VoicePrintSamplePhase.Accepted) 1f else 0.72f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f),
        label = "overlay-check",
    )
    val meterCorner = if (fillStage) 18.dp else if (compact) 12.dp else 14.dp

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(220, easing = FastOutSlowInEasing)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(overlayBackground),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = if (fillStage) {
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 14.dp else 18.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (fillStage) {
                    Arrangement.Top
                } else {
                    Arrangement.spacedBy(if (compact) 8.dp else cardSpacing)
                },
            ) {
                if (showUserTitle && activeUserName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_voice_print_manual_recording_for, activeUserName),
                        fontSize = if (compact) 14.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        textAlign = TextAlign.Center,
                    )
                }
                Box(
                    modifier = if (fillStage) {
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .heightIn(min = meterHeight)
                            .clip(RoundedCornerShape(meterCorner))
                            .background(meterWellColor)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(meterHeight)
                            .clip(RoundedCornerShape(meterCorner))
                            .background(meterWellColor)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when (phase) {
                        VoicePrintSamplePhase.Listening -> {
                            VoicePrintThreeDotLevelMeter(
                                level = micLevel,
                                active = true,
                                accentColor = accentColor,
                                inactiveColor = subtitleColor.copy(alpha = if (fillStage) 0.45f else 0.35f),
                                compact = compact,
                                expanded = fillStage,
                            )
                        }
                        VoicePrintSamplePhase.Accepted -> {
                            Box(
                                modifier = Modifier
                                    .size(if (fillStage && compact) 40.dp else if (fillStage) 48.dp else if (compact) 36.dp else 44.dp)
                                    .scale(checkScale)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(
                                        if (fillStage && compact) 22.dp else if (fillStage) 26.dp else if (compact) 20.dp else 24.dp,
                                    ),
                                )
                            }
                        }
                        else -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(
                                    if (fillStage && compact) 32.dp else if (fillStage) 38.dp else if (compact) 28.dp else 34.dp,
                                ),
                                color = accentColor,
                                strokeWidth = 2.5.dp,
                            )
                        }
                    }
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        fontSize = if (compact) 13.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (phase == VoicePrintSamplePhase.Accepted) accentColor else statusColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            top = if (fillStage) 12.dp else 0.dp,
                            bottom = if (fillStage) 4.dp else 0.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePrintEnrollmentDotProgress(
    completed: Int,
    total: Int,
    accentColor: Color,
    inactiveColor: Color,
    compact: Boolean = false,
    animatingIndex: Int? = null,
    large: Boolean = false,
    centered: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .then(if (centered) Modifier.fillMaxWidth() else Modifier)
            .padding(vertical = if (compact) 2.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(
            when {
                large && compact -> 8.dp
                large -> 10.dp
                compact -> 6.dp
                else -> 8.dp
            },
            if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ),
    ) {
        repeat(total) { index ->
            val filled = index < completed
            val isAnimating = index == animatingIndex
            val targetScale = when {
                isAnimating -> 1.45f
                filled -> 1.08f
                else -> 1f
            }
            val dotScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = spring(
                    dampingRatio = if (isAnimating) 0.55f else 0.72f,
                    stiffness = if (isAnimating) 320f else 500f,
                ),
                label = "dot-$index",
            )
            val baseDotSize = when {
                large && compact -> if (filled || isAnimating) 11.dp else 10.dp
                large -> if (filled || isAnimating) 12.dp else 11.dp
                compact -> if (filled || isAnimating) 7.dp else 6.dp
                else -> if (filled || isAnimating) 8.dp else 7.dp
            }
            val dotColor = when {
                filled -> accentColor
                isAnimating -> accentColor.copy(alpha = 0.55f)
                else -> inactiveColor
            }
            Box(
                modifier = Modifier
                    .size(baseDotSize)
                    .scale(dotScale)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

@Composable
fun VoicePrintEnrollmentProgress(
    completed: Int,
    total: Int,
    accentColor: Color,
    inactiveColor: Color,
    showLabel: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(total) { index ->
            val filled = index < completed
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (filled) accentColor else inactiveColor),
            )
        }
    }
    if (showLabel) {
        Text(
            text = stringResource(
                R.string.settings_voice_print_manual_progress,
                completed.coerceIn(0, total),
                total,
            ),
            fontSize = 12.sp,
            color = getSettingsDescriptionColor(),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun VoicePrintThreeDotLevelMeter(
    level: Float,
    active: Boolean,
    accentColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    expanded: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "voiceprint-dots")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val clampedLevel = level.coerceIn(0f, 1f)
    val baseScale = if (active) 0.55f + clampedLevel * 0.9f else 0.45f
    val dotSize = when {
        expanded && compact -> 16.dp
        expanded -> 20.dp
        compact -> 13.dp
        else -> 16.dp
    }
    val dotSpacing = when {
        expanded && compact -> 18.dp
        expanded -> 24.dp
        compact -> 10.dp
        else -> 14.dp
    }
    val maxDotScale = if (expanded) 1.55f else 1.35f

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val phase = index * 0.55f
            val wave = if (active) {
                (sin((pulse + phase) * Math.PI * 2).toFloat() * 0.5f + 0.5f)
            } else {
                0.2f
            }
            val dotScale = baseScale + wave * 0.35f * if (active) 1f else 0.3f
            val alpha = if (active) {
                (0.45f + clampedLevel * 0.45f + wave * 0.1f).coerceIn(0.35f, 1f)
            } else {
                0.35f
            }
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .scale(dotScale.coerceIn(0.4f, maxDotScale))
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(if (active) accentColor else inactiveColor),
            )
        }
    }
}
