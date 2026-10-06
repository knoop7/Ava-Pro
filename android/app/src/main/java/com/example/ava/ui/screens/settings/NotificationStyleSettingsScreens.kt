package com.example.ava.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.notifications.NotificationScenes
import com.example.ava.notifications.ScenePalette
import com.example.ava.services.NotificationOverlayService
import com.example.ava.ui.Screen
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.CustomAccentColorDialog
import com.example.ava.ui.screens.settings.components.CustomColorSwatch
import com.example.ava.ui.screens.settings.components.PresetColorSwatch
import com.example.ava.ui.screens.settings.components.SettingValueBadge
import com.example.ava.ui.screens.settings.components.SettingsBottomDockCapsule
import com.example.ava.ui.screens.settings.components.SettingsBottomDockClearance
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.launch

private val PosLabels = listOf(
    R.string.notif_pos_top_left,
    R.string.notif_pos_top_center,
    R.string.notif_pos_top_right,
    R.string.notif_pos_mid_left,
    R.string.notif_pos_mid_center,
    R.string.notif_pos_mid_right,
    R.string.notif_pos_bottom_left,
    R.string.notif_pos_bottom_center,
    R.string.notif_pos_bottom_right,
)

/** Banner appearance: position + color. Style choice lives on the entry page. */
@Composable
fun NotificationBannerSettingsScreen(navController: NavController) {
    val viewModel: SettingsViewModel = viewModel()
    val notificationState by viewModel.notificationSettingsState.collectAsStateWithLifecycle(
        com.example.ava.settings.NotificationSettings()
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = getAccentColor()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDark by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val idleCellBg = if (isDark) Color(0xFF27272A) else Color(0xFFF3F4F6)
    val idleCellBorder = if (isDark) Color(0xFF3F3F46) else Color(0xFFE2E8F0)
    val idleCellFg = getSettingsDescriptionColor()

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.notif_banner_appearance_title),
    ) {
        item {
            SimpleCard {
                Text(
                    text = stringResource(R.string.notif_style_banner_page_desc),
                    color = getSettingsDescriptionColor(),
                    fontSize = settingsBodyTextSize(),
                    modifier = Modifier.padding(top = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.notif_banner_position_title),
                    color = getLabelColor(),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (col in 0 until 3) {
                                val idx = row * 3 + col
                                val isPos = notificationState.bannerPosition == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .background(
                                            if (isPos) accent.copy(alpha = if (isDark) 0.18f else 0.12f)
                                            else idleCellBg,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .border(
                                            if (isPos) 1.5.dp else 1.dp,
                                            if (isPos) accent else idleCellBorder,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable {
                                            scope.launch { viewModel.saveBannerPosition(idx) }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(PosLabels[idx]),
                                        color = if (isPos) accent else idleCellFg,
                                        fontSize = settingsBodyTextSize(),
                                    )
                                }
                            }
                        }
                    }
                }
                if (notificationState.bannerPosition == 4) {
                    Text(
                        text = stringResource(R.string.notif_banner_position_hint_center),
                        color = Color(0xFFF59E0B),
                        fontSize = settingsBodyTextSize(),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.notif_banner_color_title),
                    color = getLabelColor(),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val bannerColor = notificationState.bannerColor
                val isCustomBanner = bannerColor.isNotBlank() &&
                    !bannerColor.equals("#ffffff", ignoreCase = true) &&
                    ScenePalette.SWATCHES.none { it.hex.equals(bannerColor, ignoreCase = true) }
                var showColorDialog by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CustomColorSwatch(
                        selectedColor = if (isCustomBanner) bannerColor else "",
                        selected = isCustomBanner,
                        enabled = true,
                        onClick = { showColorDialog = true },
                    )
                    // Default slot — white banner (empty stored value).
                    PresetColorSwatch(
                        color = Color.White,
                        selected = bannerColor.isBlank() ||
                            bannerColor.equals("#ffffff", ignoreCase = true),
                        enabled = true,
                        onClick = { scope.launch { viewModel.saveBannerColor("") } },
                    )
                    ScenePalette.SWATCHES.forEach { sw ->
                        PresetColorSwatch(
                            color = Color(android.graphics.Color.parseColor(sw.hex)),
                            selected = bannerColor.equals(sw.hex, ignoreCase = true),
                            enabled = true,
                            onClick = { scope.launch { viewModel.saveBannerColor(sw.hex) } },
                        )
                    }
                }
                if (showColorDialog) {
                    CustomAccentColorDialog(
                        initialHex = if (isCustomBanner) bannerColor else ScenePalette.SWATCHES.first().hex,
                        onDismiss = { showColorDialog = false },
                        onConfirm = { hex ->
                            scope.launch { viewModel.saveBannerColor(hex) }
                            showColorDialog = false
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.notif_banner_logo_title),
                    subLabel = stringResource(R.string.notif_banner_logo_desc),
                ) {
                    ModernSwitch(
                        checked = notificationState.bannerLogoEnabled,
                        enabled = true,
                        onCheckedChange = { on ->
                            scope.launch { viewModel.saveBannerLogoEnabled(on) }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val id = NotificationScenes.ALL_SCENES.firstOrNull()?.id
                            if (id != null) NotificationOverlayService.previewScene(context, id)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.notif_scene_preview),
                            color = getLabelColor(),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.notif_style_preview_hint),
                            color = getSettingsDescriptionColor(),
                            fontSize = settingsBodyTextSize(),
                        )
                    }
                    TextButton(onClick = {
                        val id = NotificationScenes.ALL_SCENES.firstOrNull()?.id
                        if (id != null) NotificationOverlayService.previewScene(context, id)
                    }) {
                        Text(stringResource(R.string.notif_scene_preview), color = accent)
                    }
                }
            }
        }
    }
}

/** Scene library: full list with edit / preview / delete / restore, plus new-scene. */
@Composable
fun NotificationSceneLibraryScreen(navController: NavController) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val pageBg = if (isDarkMode) Color.Black else PureWhiteBackground

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.notif_scene_library_title),
        listExtraBottom = SettingsBottomDockClearance,
        bottomOverlay = {
            SettingsBottomDockCapsule(
                pageBg = pageBg,
                isDarkMode = isDarkMode,
                primaryLabel = stringResource(R.string.notif_scene_new),
                onPrimary = {
                    navController.navigate("${Screen.SETTINGS_INTERACTION_SCENE_EDIT}/new") {
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    ) {
        item {
            SimpleCard {
                NotificationScenesSharedBlock(
                    navController = navController,
                    enabled = true,
                    coroutineScope = coroutineScope,
                    showNewSceneAction = false,
                )
            }
        }
    }
}

/** Shared duration / sound / custom JSON / help docs. */
@Composable
fun NotificationGeneralSettingsScreen(navController: NavController) {
    val viewModel: SettingsViewModel = viewModel()
    val notificationState by viewModel.notificationSettingsState.collectAsStateWithLifecycle(
        com.example.ava.settings.NotificationSettings()
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val displayDuration = (notificationState.sceneDisplayDuration) / 1000

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.notif_general_settings_title),
    ) {
        item {
            SimpleCard {
                CollapsibleDescriptionText(
                    text = stringResource(R.string.notif_general_settings_page_desc),
                    collapsedLines = 2,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    fadeToColor = getDialogBackground(),
                    topPadding = 12.dp,
                )

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(12.dp))

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
                        overflow = TextOverflow.Ellipsis,
                    )
                    SettingValueBadge(text = "${displayDuration}s")
                }
                Text(
                    text = stringResource(R.string.settings_scene_display_duration_desc),
                    color = getSettingsDescriptionColor(),
                    fontSize = settingsBodyTextSize(),
                    modifier = Modifier.padding(top = 4.dp),
                )
                TickSlider(
                    value = displayDuration.toFloat(),
                    onValueChange = { newValue ->
                        scope.launch {
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
                        inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                NotificationSoundSection(
                    viewModel = viewModel,
                    notificationState = notificationState,
                    enabled = true,
                    coroutineScope = scope,
                    context = context,
                )

                Spacer(modifier = Modifier.height(8.dp))
                SettingsDivider()

                CustomSceneSection(
                    viewModel = viewModel,
                    notificationState = notificationState,
                    enabled = true,
                    coroutineScope = scope,
                    context = context,
                )
            }
        }
    }
}
