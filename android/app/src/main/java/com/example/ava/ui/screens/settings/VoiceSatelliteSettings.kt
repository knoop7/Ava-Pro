package com.example.ava.ui.screens.settings

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ava.R
import com.example.ava.ui.components.sidebarDrawerFocusable
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.ui.screens.settings.components.*
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.NotificationSettings
import com.example.ava.utils.BatteryOptimizationHelper
import com.example.ava.utils.SoundUriPreview
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.ava.ui.theme.SlateBorder as CardBorder
import com.example.ava.ui.theme.SlateText as TitleColor
import com.example.ava.ui.theme.SlateLabel as LabelColor
import com.example.ava.ui.theme.SlateTertiary as SubLabelColor
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import com.example.ava.ui.theme.IconBackground


private val CardBackgroundLight = Color.White
private val CardBackgroundDark = Color(0xFF1F1F1F)

@Composable
internal fun isDarkModeEnabled(): Boolean {
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
fun getDialogBackground(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) CardBackgroundDark else CardBackgroundLight
}

@Composable
fun getLabelColor(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) LabelColorDark else LabelColor
}

/** Setting-row description: light keeps slate-400; dark is a brighter, less-blue gray. */
@Composable
fun getSettingsDescriptionColor(): Color {
    return if (isDarkModeEnabled()) DescriptionColorDark else SubLabelColor
}

@Composable
fun getTitleColor(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) Color(0xFFF1F5F9) else TitleColor
}

@Composable
fun getAccentColor(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) AccentBrown else AccentBlue
}

/**
 * Mass-only chrome accent (rail / MA settings cards / Sendspin stats).
 * Does **not** replace global [getAccentColor].
 *
 * - Dark: translucent brown (theme [AccentBrown])
 * - Light: fixed muted gray — avoids harsh translucent [AccentBlue] on these pages
 */
@Composable
fun getMassChromeAccent(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) {
        // 透棕 — theme brown with alpha (soft over dark surfaces)
        AccentBrown.copy(alpha = 0.88f)
    } else {
        // Day: gray chrome for Mass surfaces only (not app-wide accent)
        MassChromeAccentLightGray
    }
}

/** Light-theme Mass chrome — muted gray (not AccentBlue). */
private val MassChromeAccentLightGray = Color(0xFF666669)

/** Ink on [getMassChromeAccent] solid fills (primary buttons / selected chips). */
@Composable
fun getMassChromeOnAccent(): Color {
    val isDarkMode = isDarkModeEnabled()
    // Brown fill → dark ink; gray fill → cream ink for contrast.
    return if (isDarkMode) Color(0xFF1A140C) else Color(0xFFF4EFE8)
}

@Composable
fun getSliderInactiveColor(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) Color(0xFF3D3D3D) else Color(0xFFE2E8F0)
}

/** Bluish-gray for secondary / unknown Bluetooth device labels (theme-aware). */
@Composable
fun getSlateMutedColor(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) Color(0xFF8D99AE) else Color(0xFF94A3B8)
}

@Composable
fun CollapsibleSettingsNote(
    title: String,
    content: String,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(bottom = 8.dp),
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getTitleColor(),
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(
                    if (expanded) {
                        android.R.drawable.arrow_up_float
                    } else {
                        android.R.drawable.arrow_down_float
                    },
                ),
                contentDescription = null,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Text(
                text = content,
                fontSize = settingsBodyTextSize(),
                color = if (contentColor == Color.Unspecified) {
                    getSettingsDescriptionColor()
                } else {
                    contentColor
                },
                lineHeight = settingsBodyLineHeight(),
            )
        }
    }
}

@Composable
fun getInputBackground(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF8FAFC)
}

/** Filled settings fields: title-ink text, description-gray placeholder, accent cursor. */
@Composable
fun settingsFilledFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = getInputBackground(),
    unfocusedContainerColor = getInputBackground(),
    disabledContainerColor = getInputBackground(),
    errorContainerColor = getInputBackground(),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = getAccentColor(),
    focusedTextColor = getLabelColor(),
    unfocusedTextColor = getLabelColor(),
    disabledTextColor = getSettingsDescriptionColor(),
    errorTextColor = getLabelColor(),
    focusedPlaceholderColor = getSettingsDescriptionColor(),
    unfocusedPlaceholderColor = getSettingsDescriptionColor(),
    disabledPlaceholderColor = getSettingsDescriptionColor(),
)

private val IconColor = AccentBlue
private val LabelColorDark = Color(0xFFF1F5F9)
private val DescriptionColorDark = Color(0xFFACAEB0)

@Composable
fun settingsLabelColor(): Color {
    val isDarkMode = isDarkModeEnabled()
    return if (isDarkMode) LabelColorDark else LabelColor
}


fun checkOverlayPermission(context: android.content.Context): Boolean =
    com.example.ava.permissions.OverlayPermission.isGranted(context)


/**
 * Toast + jump immediately. Privileged grant runs in the background so a slow
 * `su` / Shizuku round-trip cannot freeze the UI before settings open.
 * On ROMs where the overlay switch is dead, show the ADB command instead.
 */
fun requestOverlayPermission(
    context: android.content.Context,
    @androidx.annotation.StringRes messageRes: Int = R.string.settings_overlay_permission_required,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val appContext = context.applicationContext
    if (com.example.ava.permissions.OverlayPermission.isGranted(appContext)) return
    if (com.example.ava.permissions.OverlayPermission.isSystemToggleBlocked(appContext)) {
        val command = com.example.ava.permissions.OverlayPermission.adbGrantCommand(appContext.packageName)
        com.example.ava.ui.AvaToast.show(
            appContext,
            appContext.getString(R.string.settings_overlay_blocked_toast, command),
            durationMs = com.example.ava.ui.AvaToast.LONG_MS,
        )
    } else {
        com.example.ava.ui.AvaToast.show(
            appContext,
            messageRes,
            durationMs = com.example.ava.ui.AvaToast.LONG_MS,
        )
        com.example.ava.permissions.OverlayPermission.openSettings(appContext)
    }
    Thread {
        com.example.ava.permissions.OverlayPermission.tryPrivilegedGrant(appContext)
    }.start()
}

fun requestAccessibilityPermission(
    context: android.content.Context,
    @androidx.annotation.StringRes messageRes: Int = R.string.mod_permission_accessibility_hint,
) {
    val appContext = context.applicationContext
    if (com.example.ava.services.AccessibilityBridge.isEnabled(appContext)) return
    com.example.ava.ui.AvaToast.show(
        appContext,
        messageRes,
        durationMs = com.example.ava.ui.AvaToast.LONG_MS,
    )
    com.example.ava.services.AccessibilityBridge.openSettings(appContext)
}


@Composable
fun SectionCard(
    title: String,
    iconResId: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDarkMode = isDarkModeEnabled()
    val cardBackground = if (isDarkMode) CardBackgroundDark else CardBackgroundLight
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = cardBackground,
        shadowElevation = if (isDarkMode) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = if (isDarkMode) Color(0xFF2D2D2D) else IconBackground,
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(iconResId),
                        contentDescription = title,
                        tint = IconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = getTitleColor()
                )
            }
            
            
            content()
        }
    }
}


@Composable
fun SettingsSectionLabel(text: String, withTopSpacer: Boolean = true) {
    if (withTopSpacer) {
        Spacer(modifier = Modifier.height(16.dp))
    }
    Text(
        text = text,
        fontSize = settingsTitleTextSize(),
        fontWeight = FontWeight.Medium,
        color = getSettingsDescriptionColor(),
        modifier = Modifier.padding(
            start = SettingsCardInnerHorizontalPadding,
            end = 16.dp,
            top = 8.dp,
            bottom = 8.dp,
        ),
    )
}

/** Hairline that splits a block from the rows above. Caption in the middle is optional. */
@Composable
fun SettingsCaptionDivider(text: String = "") {
    val isDarkMode = isDarkModeEnabled()
    val line = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = line, thickness = 1.dp)
        if (text.isNotBlank()) {
            Text(
                text = text,
                fontSize = settingsBodyTextSize(),
                fontWeight = FontWeight.Medium,
                color = getSettingsDescriptionColor(),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = line, thickness = 1.dp)
        }
    }
}

@Composable
fun SimpleCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDarkMode = isDarkModeEnabled()
    val cardBackground = if (isDarkMode) CardBackgroundDark else CardBackgroundLight
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSimpleCardVerticalPadding),
        shape = RoundedCornerShape(32.dp),
        color = cardBackground,
        shadowElevation = if (isDarkMode) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(SettingsCardInnerHorizontalPadding),
            content = content
        )
    }
}

/** Gray well inset used to wrap a feature switch with its Home Assistant twin. */
@Composable
fun getSettingsInsetWellColor(): Color {
    return if (isDarkModeEnabled()) Color(0xFF2A2A2A) else Color(0xFFF1F5F9)
}

@Composable
fun SettingsInsetWell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Same width as the card's dividers (the content column) so the well's
    // left/right edges line up with them; even air above and below so the
    // well never touches neighbors.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(getSettingsInsetWellColor())
            .padding(horizontal = 14.dp, vertical = 2.dp),
        content = content,
    )
}

@Composable
fun SettingsWellDivider(
    modifier: Modifier = Modifier,
) {
    val dividerColor = if (isDarkModeEnabled()) Color(0xFF3A3A3A) else Color(0xFFE2E8F0)
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        color = dividerColor,
        thickness = 1.dp,
    )
}


@Composable
fun SettingRow(
    label: String,
    subLabel: String = "",
    iconResId: Int? = null,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit
) {
    val isDarkMode = isDarkModeEnabled()
    val labelColor = if (isDarkMode) LabelColorDark else LabelColor
    
    val rowScale = rememberSettingsTextScale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.settingsClickable(onClick = onClick) else Modifier)
            .padding(vertical = (16f * rowScale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconResId != null) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = label,
                tint = SubLabelColor,
                modifier = Modifier.size((20f * rowScale).dp)
            )
            Spacer(modifier = Modifier.width((16f * rowScale).dp))
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = labelColor,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium
            )
            if (subLabel.isNotEmpty()) {
                CollapsibleDescriptionText(
                    text = subLabel,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                )
            }
        }
        
        Spacer(modifier = Modifier.width((15f * rowScale).dp))
        
        action()
    }
}

/** Shared trailing slot so chevron and ? line up in the same column. */
@Composable
private fun settingTrailingSlotSize() = (28f * rememberSettingsTextScale()).dp

@Composable
fun SettingRowChevron() {
    val isDarkMode = isDarkModeEnabled()
    val slot = settingTrailingSlotSize()
    Box(
        modifier = Modifier.size(slot),
        contentAlignment = Alignment.Center
    ) {
        SettingsChevronIcon(
            tint = if (isDarkMode) Color(0xFF4B5563) else Color(0xFFD1D5DB),
        )
    }
}

@Composable
fun SettingRowHelpMark() {
    val slot = settingTrailingSlotSize()
    Box(
        modifier = Modifier.size(slot),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "?",
            fontSize = settingsHelpMarkTextSize(),
            fontWeight = FontWeight.Bold,
            color = getAccentColor()
        )
    }
}


@Composable
fun SettingsDivider(
    modifier: Modifier = Modifier,
) {
    val isDarkMode = isDarkModeEnabled()
    val dividerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF1F5F9)

    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        color = dividerColor,
        thickness = 1.dp,
    )
}

@Composable
fun ModernSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val isDarkMode = isDarkModeEnabled()
    
    androidx.compose.runtime.key(checked) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.sidebarDrawerFocusable(),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (isDarkMode) AccentBrown else AccentBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (isDarkMode) Color(0xFF3D3D3D) else Color(0xFFE2E8F0),
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                disabledCheckedTrackColor = if (isDarkMode) AccentBrown.copy(alpha = 0.4f) else AccentBlue.copy(alpha = 0.4f),
                disabledUncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                disabledUncheckedTrackColor = if (isDarkMode) Color(0xFF3D3D3D).copy(alpha = 0.4f) else Color(0xFFE2E8F0).copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
fun VoiceSatelliteSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val notificationState by viewModel.notificationSettingsState.collectAsStateWithLifecycle(NotificationSettings())
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    
    val enabled = uiState != null
    
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        
        item {
            SectionCard(
                title = stringResource(R.string.section_core_connection),
                iconResId = R.drawable.wifi_24px
            ) {
                
                SelectSetting(
                    name = stringResource(R.string.label_voice_satellite_wake_word),
                    selected = microphoneState?.wakeWord,
                    items = microphoneState?.wakeWords,
                    enabled = enabled,
                    key = { it.id },
                    value = { it?.wakeWord?.wake_word ?: "" },
                    onConfirmRequest = {
                        if (it != null) {
                            coroutineScope.launch {
                                viewModel.saveWakeWord(it.id)
                            }
                        }
                    }
                )
                
                SettingsDivider()
                
                val noneText = stringResource(R.string.label_voice_satellite_wake_word_2_none)
                SelectSetting(
                    name = stringResource(R.string.label_voice_satellite_wake_word_2),
                    selected = microphoneState?.wakeWord2,
                    items = microphoneState?.wakeWords,
                    enabled = enabled,
                    key = { it?.id ?: "" },
                    value = { it?.wakeWord?.wake_word ?: noneText },
                    onConfirmRequest = {
                        coroutineScope.launch {
                            viewModel.saveWakeWord2(it?.id)
                        }
                    }
                )
                
                SettingsDivider()
                
                
                TextSetting(
                    name = stringResource(R.string.label_voice_satellite_name),
                    value = uiState?.serverName ?: "",
                    enabled = enabled,
                    validation = { viewModel.validateName(it) },
                    onConfirmRequest = {
                        coroutineScope.launch {
                            viewModel.saveServerName(it)
                        }
                    }
                )
                
                SettingsDivider()
                
                
                IntSetting(
                    name = stringResource(R.string.label_voice_satellite_port),
                    dialogHint = stringResource(R.string.settings_port_description),
                    value = uiState?.serverPort,
                    enabled = enabled,
                    validation = { viewModel.validatePort(it) },
                    onConfirmRequest = {
                        coroutineScope.launch {
                            viewModel.saveServerPort(it)
                        }
                    }
                )
                
                SettingsDivider()
                
                
                SettingRow(
                    label = stringResource(R.string.settings_auto_restart),
                    subLabel = stringResource(R.string.settings_auto_restart_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableAutoRestart ?: false,
                        enabled = enabled,
                        onCheckedChange = {
                            if (it && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                                val intent = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                                context.startActivity(intent)
                            }
                            coroutineScope.launch {
                                viewModel.saveAutoRestart(it)
                            }
                        }
                    )
                }
            }
        }
        
        item {
            SectionCard(
                title = stringResource(R.string.section_interaction_visual),
                iconResId = R.drawable.star_24px
            ) {
                SettingRow(
                    label = stringResource(R.string.settings_dream_clock),
                    subLabel = stringResource(R.string.settings_dream_clock_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableDreamClock ?: false,
                        enabled = enabled,
                        onCheckedChange = {
                            if (it && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveDreamClock(it)
                                }
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                
                SettingRow(
                    label = stringResource(R.string.label_voice_satellite_enable_wake_sound),
                    subLabel = stringResource(R.string.description_voice_satellite_play_wake_sound)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableWakeSound ?: true,
                        enabled = enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveEnableWakeSound(it)
                            }
                        }
                    )
                }
                
                
                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(16.dp))
                
                val displayDuration = notificationState.sceneDisplayDuration / 1000
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_scene_display_duration),
                        color = getLabelColor(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    SettingValueBadge(
                        text = "${displayDuration}s",
                    )
                }
                
                TickSlider(
                    value = displayDuration.toFloat(),
                    onValueChange = { newValue ->
                        coroutineScope.launch {
                            viewModel.saveSceneDisplayDuration(newValue.toInt() * 1000)
                        }
                    },
                    valueRange = 5f..60f,
                    steps = 10,
                    colors = SliderDefaults.colors(
                        thumbColor = getAccentColor(),
                        activeTrackColor = getAccentColor(),
                        inactiveTrackColor = getSliderInactiveColor(),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                
                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(16.dp))
                
                val soundEnabled = notificationState.soundEnabled
                val soundUri = notificationState.soundUri
                
                
                var showRingtonePicker by remember { mutableStateOf(false) }
                var externalSoundUri by remember { mutableStateOf<String?>(null) }
                
                val audioFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {}
                        externalSoundUri = uri.toString()
                    }
                }
                
                
                val ringtones = remember {
                    val list = mutableListOf<Pair<String, String>>() 
                    list.add(context.getString(R.string.sound_none) to "")
                    val manager = RingtoneManager(context)
                    manager.setType(RingtoneManager.TYPE_NOTIFICATION)
                    manager.cursor.use { cursor ->
                        while (cursor.moveToNext()) {
                            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                                ?: context.getString(R.string.sound_unknown)
                            val uri = manager.getRingtoneUri(cursor.position).toString()
                            list.add(title to uri)
                        }
                    }
                    list
                }
                
                
                val soundName = remember(soundUri) {
                    if (soundUri.isEmpty()) {
                        context.getString(R.string.sound_none)
                    } else {
                        try {
                            val uri = Uri.parse(soundUri)
                            val ringtone = RingtoneManager.getRingtone(context, uri)
                            ringtone?.getTitle(context) ?: context.getString(R.string.sound_unknown)
                        } catch (e: Exception) {
                            context.getString(R.string.sound_unknown)
                        }
                    }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsClickable(enabled = enabled) { showRingtonePicker = true }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.notification_sound),
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor(),
                            fontWeight = FontWeight.Medium
                        )
                        CollapsibleDescriptionText(
                            text = stringResource(R.string.notification_sound_desc),
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            color = getSettingsDescriptionColor(),
                        )
                    }
                    SettingsChevronIcon(tint = SubLabelColor)
                }
                
                
                if (showRingtonePicker) {
                    SharedRingtonePickerDialog(
                        ringtones = ringtones,
                        currentUri = soundUri,
                        context = context,
                        title = stringResource(R.string.select_sound),
                        externalSoundUri = externalSoundUri,
                        onExternalSoundUriConsumed = { externalSoundUri = null },
                        onDismiss = { showRingtonePicker = false },
                        onConfirm = { uri ->
                            coroutineScope.launch {
                                viewModel.saveSoundUri(uri)
                                viewModel.saveSoundEnabled(uri.isNotEmpty())
                            }
                            showRingtonePicker = false
                        },
                        onSelectExternal = {
                            audioFileLauncher.launch("audio/*")
                        },
                    )
                }
                
                
                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                
                val currentUrl = notificationState.customSceneUrl
                val _refreshSignal = com.example.ava.notifications.NotificationScenes.refreshCount.value
                val customSceneCount = com.example.ava.notifications.NotificationScenes.customSceneCount
                val loadState = com.example.ava.notifications.NotificationScenes.loadState
                
                val statusText = when (loadState) {
                    is com.example.ava.notifications.NotificationScenes.SceneLoadState.Idle -> {
                        if (currentUrl.isEmpty()) stringResource(R.string.settings_custom_scene_not_configured)
                        else stringResource(R.string.settings_custom_scene_loading)
                    }
                    is com.example.ava.notifications.NotificationScenes.SceneLoadState.Loading -> stringResource(R.string.settings_custom_scene_loading)
                    is com.example.ava.notifications.NotificationScenes.SceneLoadState.Success -> stringResource(R.string.settings_custom_scene_loaded, customSceneCount)
                    is com.example.ava.notifications.NotificationScenes.SceneLoadState.Error -> {
                        val errorDetail = loadState.detail
                        if (errorDetail != null) stringResource(loadState.resId, errorDetail)
                        else stringResource(loadState.resId)
                    }
                }
                
                
                var showTutorialDialog by remember { mutableStateOf(false) }
                val prefs = context.getSharedPreferences("ava_prefs", android.content.Context.MODE_PRIVATE)
                val hasSeenTutorial = prefs.getBoolean("custom_scene_tutorial_seen", false)
                
                
                CustomSceneUrlSetting(
                    currentUrl = currentUrl,
                    statusText = statusText,
                    enabled = enabled,
                    onClickWithTutorialCheck = {
                        if (!hasSeenTutorial && currentUrl.isEmpty()) {
                            showTutorialDialog = true
                            true 
                        } else {
                            false 
                        }
                    },
                    onConfirmRequest = { url ->
                        coroutineScope.launch {
                            viewModel.saveCustomSceneUrl(url)
                        }
                    }
                )
                
                
                if (showTutorialDialog) {
                    CustomSceneTutorialDialog(
                        onDismiss = { showTutorialDialog = false },
                        onConfirm = {
                            
                            prefs.edit().putBoolean("custom_scene_tutorial_seen", true).apply()
                            showTutorialDialog = false
                            
                            coroutineScope.launch {
                                viewModel.saveCustomSceneUrl("https://raw.githubusercontent.com/knoop7/Ava/refs/heads/master/custom_scenes.json")
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.custom_scene_config),
                    onClick = { showTutorialDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }
        
        
        item {
            val hasCamera = viewModel.hasCamera()
            val hasBackCamera = viewModel.hasBackCamera()
            val hasFrontCamera = viewModel.hasFrontCamera()
            
            SectionCard(
                title = stringResource(R.string.settings_experimental),
                iconResId = R.drawable.experiment_24px
            ) {
                
                SettingRow(
                    label = stringResource(R.string.settings_camera_enabled),
                    subLabel = if (hasCamera) 
                        stringResource(R.string.settings_camera_enabled_desc)
                    else 
                        stringResource(R.string.settings_no_camera)
                ) {
                    ModernSwitch(
                        checked = experimentalState?.cameraEnabled ?: false,
                        enabled = hasCamera && enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveCameraEnabled(it)
                            }
                        }
                    )
                }
                
                
                if (experimentalState?.cameraEnabled == true && hasCamera) {
                    SettingsDivider()
                    
                    val currentPosition = try {
                        com.example.ava.settings.CameraPosition.valueOf(
                            experimentalState?.cameraPosition ?: "FRONT"
                        )
                    } catch (e: Exception) {
                        com.example.ava.settings.CameraPosition.FRONT
                    }
                    
                    
                    val cameraOptions = buildList {
                        if (hasFrontCamera) add(com.example.ava.settings.CameraPosition.FRONT)
                        if (hasBackCamera) add(com.example.ava.settings.CameraPosition.BACK)
                    }
                    
                    // The stored position defaults to FRONT even on back-only hardware, where the
                    // picker stays disabled — show the lens that will actually be bound
                    // (reported by @gilcu2, knoop7/Ava#163).
                    val availablePosition = when {
                        cameraOptions.isEmpty() || currentPosition in cameraOptions -> currentPosition
                        else -> cameraOptions.first()
                    }
                    
                    val backCameraLabel = stringResource(R.string.settings_camera_back)
                    val frontCameraLabel = stringResource(R.string.settings_camera_front)
                    
                    SelectSetting(
                        name = stringResource(R.string.settings_camera_position),
                        selected = availablePosition,
                        items = cameraOptions,
                        enabled = enabled && cameraOptions.size > 1,
                        key = { it.name },
                        value = {
                            when (it) {
                                com.example.ava.settings.CameraPosition.BACK -> backCameraLabel
                                com.example.ava.settings.CameraPosition.FRONT -> frontCameraLabel
                                null -> ""
                            }
                        },
                        onConfirmRequest = {
                            if (it != null) {
                                coroutineScope.launch {
                                    viewModel.saveCameraPosition(it)
                                }
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                
                SettingRow(
                    label = stringResource(R.string.settings_environment_sensor),
                    subLabel = stringResource(R.string.settings_environment_sensor_desc)
                ) {
                    ModernSwitch(
                        checked = experimentalState?.environmentSensorEnabled ?: false,
                        enabled = enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveEnvironmentSensorEnabled(it)
                            }
                        }
                    )
                }
                
                
                if (experimentalState?.environmentSensorEnabled == true) {
                    SettingsDivider()
                    
                    val sensorInterval = experimentalState?.sensorUpdateInterval ?: 35
                    
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_sensor_update_interval),
                            description = stringResource(R.string.settings_sensor_update_interval_desc),
                            badgeText = "${sensorInterval}s",
                        )
                        TickSlider(
                            value = sensorInterval.toFloat(),
                            onValueChange = { 
                                coroutineScope.launch {
                                    viewModel.saveSensorUpdateInterval(it.toInt())
                                }
                            },
                            valueRange = 5f..60f,
                            steps = 10,
                            enabled = enabled,
                            colors = SliderDefaults.colors(
                                thumbColor = getAccentColor(),
                                activeTrackColor = getAccentColor(),
                                inactiveTrackColor = getSliderInactiveColor(),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
        
        
        item {
            SectionCard(
                title = stringResource(R.string.section_local_service),
                iconResId = R.drawable.cloud_24px
            ) {
                SettingRow(
                    label = stringResource(R.string.settings_weather_overlay),
                    subLabel = ""
                ) {
                    ModernSwitch(
                        checked = playerState?.enableWeatherOverlay ?: false,
                        enabled = enabled,
                        onCheckedChange = {
                            if (it && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveWeatherOverlay(it)
                                }
                            }
                        }
                    )
                }
                
                val weatherEnabled = playerState?.enableWeatherOverlay ?: false
                if (weatherEnabled) {
                    SettingsDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    

                    SettingItem(
                        name = stringResource(R.string.settings_weather_overlay_ha_switch),
                        description = stringResource(R.string.settings_weather_overlay_ha_switch_desc)
                    ) {
                        ModernSwitch(
                            checked = playerState?.enableWeatherOverlayDisplay ?: false,
                            enabled = enabled,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveWeatherOverlayDisplay(it)
                                }
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    SatelliteWeatherEntitySetting(
                        currentEntity = playerState?.haWeatherEntity ?: "",
                        enabled = enabled,
                        onSave = { entity ->
                            coroutineScope.launch {
                                viewModel.saveHaWeatherEntity(entity)
                            }
                        },
                    )
                }
            }
        }
        
        
        item {
            AboutSection()
        }
    }
}


@Composable
private fun SatelliteWeatherEntitySetting(
    currentEntity: String,
    enabled: Boolean,
    onSave: (String) -> Unit,
) {
    val notConfiguredText = stringResource(R.string.settings_custom_scene_not_configured)
    val pickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()
    var showPicker by remember { mutableStateOf(false) }

    if (!pickerAvailable) {
        TextSetting(
            name = stringResource(R.string.settings_ha_weather_entity),
            description = stringResource(R.string.settings_ha_weather_entity_desc),
            value = currentEntity,
            rowValue = if (currentEntity.isEmpty()) notConfiguredText else currentEntity,
            placeholder = "weather.xxx",
            enabled = enabled,
            onConfirmRequest = onSave,
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showPicker = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_ha_weather_entity),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (currentEntity.isEmpty()) notConfiguredText else currentEntity,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }

    if (showPicker) {
        com.example.ava.homeassistant.ui.HaEntityPickerDialog(
            title = stringResource(R.string.settings_ha_weather_entity),
            currentValue = currentEntity,
            domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.Weather,
            onDismiss = { showPicker = false },
            onConfirm = { newValue ->
                onSave(newValue)
                showPicker = false
            },
        )
    }
}

@Composable
fun CustomSceneUrlSetting(
    currentUrl: String,
    statusText: String,
    enabled: Boolean,
    onClickWithTutorialCheck: () -> Boolean, 
    onConfirmRequest: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    
    
    Row(
        modifier = (if (enabled) {
            Modifier.settingsClickable {
                val handled = onClickWithTutorialCheck()
                if (!handled) {
                    showDialog = true
                }
            }
        } else {
            Modifier.alpha(0.5f)
        })
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_custom_scene_url),
                    color = getLabelColor(),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium
                )
            Text(
                text = statusText,
                color = getSettingsDescriptionColor(),
                fontSize = settingsBodyTextSize(),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(15.dp))
        SettingsChevronIcon(tint = SubLabelColor)
    }
    
    
    if (showDialog) {
        var textValue by remember { mutableStateOf(currentUrl) }
        
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_custom_scene_url),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
            },
            text = {
                TextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    placeholder = { 
                        Text(
                            text = stringResource(R.string.settings_custom_scene_url_placeholder),
                            fontSize = settingsBodyTextSize(),
                            color = getSettingsDescriptionColor(),
                        ) 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = settingsTitleTextSize(), color = getLabelColor()),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFilledFieldColors(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmRequest(textValue)
                        showDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.label_ok), 
                        color = getAccentColor(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(
                        text = stringResource(R.string.label_cancel), 
                        color = SubLabelColor,
                        fontSize = settingsTitleTextSize()
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground()
        )
    }
}


@Composable
fun CustomSceneTutorialDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    UsageGuideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.custom_scene_config),
        confirmLabel = stringResource(R.string.custom_scene_use_example),
        onConfirm = onConfirm,
    ) {
        SettingsHelpBodyText(text = stringResource(R.string.custom_scene_desc))
        Text(
            text = stringResource(R.string.custom_scene_json_format),
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor()
        )
        SettingsHelpBodyText(text = stringResource(R.string.custom_scene_json_requirements))
        SettingsHelpBodyText(text = stringResource(R.string.custom_scene_example_hint))
    }
}
