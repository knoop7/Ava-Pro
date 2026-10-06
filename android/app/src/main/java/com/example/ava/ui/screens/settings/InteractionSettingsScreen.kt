package com.example.ava.ui.screens.settings

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.ui.Screen
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.ui.screens.settings.components.*
import com.example.ava.ui.components.ExpandedTapTarget
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.HEADER_SUBTITLE_MAX_LENGTH
import com.example.ava.ui.screens.home.HEADER_TITLE_MAX_LENGTH
import com.example.ava.ui.screens.home.KEY_HEADER_SUBTITLE
import com.example.ava.ui.screens.home.KEY_HEADER_TITLE
import com.example.ava.ui.screens.home.KEY_HIDE_HEADER
import com.example.ava.ui.screens.home.KEY_TRANSPARENT_SETTINGS_BUTTON
import com.example.ava.ui.prefs.rememberStringPreference
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.DreamClockFace
import com.example.ava.settings.SimpleClockPortraitStyle
import com.example.ava.voice.VoiceCallVideoQuality
import com.example.ava.ui.prefs.rememberBooleanPreference
import kotlinx.coroutines.launch
import com.example.ava.ui.theme.SlateText as TitleColor
import com.example.ava.ui.theme.SlateLabel as LabelColor
import com.example.ava.ui.theme.SlateTertiary as SubLabelColor
import com.example.ava.ui.theme.IconBackground
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon


enum class InteractionSettingsDestination {
    Root,
    Interface,
    Playback,
    PlaybackEqHa,
    PlaybackEqMa,
    PlaybackMassApi,
    Scene,
    VoiceMessage,
    QuickEntity,
    SimpleClockAppearance,
    SimpleClockStatus,
    DreamClockAppearance,
}

@Composable
fun InteractionSettingsScreen(
    navController: NavController,
    startDestination: InteractionSettingsDestination = InteractionSettingsDestination.Root,
    viewModel: SettingsViewModel = viewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val notificationState by viewModel.notificationSettingsState.collectAsStateWithLifecycle(com.example.ava.settings.NotificationSettings())
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val screenWidthPx = displayMetrics.widthPixels
    val screenHeightPx = displayMetrics.heightPixels
    // Square panes (320×320 reports w426dp×h354dp) stay on the portrait stack.
    val widePane = rememberPaneIsLandscape()
    val splitActive = LocalSettingsSplitActive.current
    // Split: stacked cards only. Header chrome stays with SettingsDetailScreen.
    val isLandscape = !splitActive && widePane
    val isSmallScreen = minOf(screenWidthPx, screenHeightPx) <= 480
    val enabled = uiState != null
    val homePrefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var hideHeader by remember { mutableStateOf(homePrefs.getBoolean(KEY_HIDE_HEADER, false)) }
    val defaultHeaderTitle = stringResource(R.string.home_header_title_default)
    val defaultHeaderSubtitle = stringResource(R.string.home_header_subtitle_default)
    val storedHeaderTitle by rememberStringPreference(homePrefs, KEY_HEADER_TITLE)
    val storedHeaderSubtitle by rememberStringPreference(homePrefs, KEY_HEADER_SUBTITLE)
    val headerTitle = storedHeaderTitle.takeIf { it.isNotBlank() } ?: defaultHeaderTitle
    val headerSubtitle = storedHeaderSubtitle.takeIf { it.isNotBlank() } ?: defaultHeaderSubtitle
    val isDarkMode by rememberBooleanPreference(homePrefs, KEY_DARK_MODE, false)
    var transparentSettingsButton by remember { mutableStateOf(homePrefs.getBoolean(KEY_TRANSPARENT_SETTINGS_BUTTON, false)) }
    var showMediaPlayerStatsSheet by remember { mutableStateOf(false) }
    
    val experimentalSettingsStore = remember { com.example.ava.settings.ExperimentalSettingsStore(context) }
    val syncDarkModeToHass by experimentalSettingsStore.syncDarkModeToHass.collectAsStateWithLifecycle(initialValue = false)

    when (startDestination) {
        InteractionSettingsDestination.Root -> {
            // Include small landscape so left-card edge fade can appear only when isSmallScreen.
            val landscapeRoot = isLandscape
            // Landscape: zero vertical contentPadding + fillParentMaxHeight => one screen, no scroll.
            val rootContentPadding = if (landscapeRoot) {
                PaddingValues(
                    horizontal = settingsListHorizontalPadding(),
                    vertical = 0.dp,
                )
            } else {
                null
            }
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_group_interaction),
                contentPadding = rootContentPadding,
                landscapeLayout = if (!splitActive && !widePane) false else null,
                content = {
                    item(key = "interaction_bento") {
                        InteractionSettingsBento(
                            isLandscape = landscapeRoot,
                            isSmallScreen = isSmallScreen,
                            modifier = if (landscapeRoot) {
                                Modifier.fillParentMaxHeight()
                            } else {
                                Modifier
                            },
                            onPlaybackClick = {
                                navController.navigate(Screen.SETTINGS_INTERACTION_PLAYBACK) {
                                    launchSingleTop = true
                                }
                            },
                            onSceneClick = {
                                navController.navigate(Screen.SETTINGS_INTERACTION_SCENE) {
                                    launchSingleTop = true
                                }
                            },
                            onVoiceMessageClick = {
                                navController.navigate(Screen.SETTINGS_INTERACTION_VOICE_MESSAGE) {
                                    launchSingleTop = true
                                }
                            },
                            onQuickEntityClick = {
                                navController.navigate(Screen.SETTINGS_INTERACTION_QUICK_ENTITY) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            )
        }

        InteractionSettingsDestination.Interface -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_home_interface)
            ) {
                interfaceSettingsSection(
                    hideHeader = hideHeader,
                    onHideHeaderChange = {
                        hideHeader = it
                        homePrefs.edit().putBoolean(KEY_HIDE_HEADER, it).apply()
                    },
                    headerTitle = headerTitle,
                    headerSubtitle = headerSubtitle,
                    onHeaderTextChange = { title, subtitle ->
                        homePrefs.edit()
                            .putString(KEY_HEADER_TITLE, title)
                            .putString(KEY_HEADER_SUBTITLE, subtitle)
                            .apply()
                    },
                    isDarkMode = isDarkMode,
                    onDarkModeChange = { DarkModeManager.getInstance(context).setDarkMode(it) },
                    syncDarkModeToHass = syncDarkModeToHass,
                    onSyncDarkModeToHassChange = { enabled ->
                        coroutineScope.launch {
                            experimentalSettingsStore.setSyncDarkModeToHass(enabled)
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    },
                    transparentSettingsButton = transparentSettingsButton,
                    onTransparentSettingsButtonChange = {
                        transparentSettingsButton = it
                        homePrefs.edit().putBoolean(KEY_TRANSPARENT_SETTINGS_BUTTON, it).apply()
                    },
                )
            }
        }

        InteractionSettingsDestination.QuickEntity -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_interaction_group_quick_entity_title),
            ) {
                item(key = "quick_entity") {
                    QuickEntitySettingsCard(
                        enabled = enabled,
                        coroutineScope = coroutineScope,
                        onEditSlot = { slotIndex ->
                            navController.navigate("quick_entity_edit/$slotIndex") { launchSingleTop = true }
                        },
                    )
                }
            }
        }

        InteractionSettingsDestination.SimpleClockStatus -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_screensaver_status_slots),
            ) {
                item(key = "simple_clock_status") {
                    SimpleClockStatusSettingsCard(
                        enabled = enabled,
                        coroutineScope = coroutineScope,
                        onEditSlot = { slotIndex ->
                            navController.navigate("simple_clock_status_edit/$slotIndex") { launchSingleTop = true }
                        },
                    )
                }
            }
        }

        InteractionSettingsDestination.Playback -> {
            val sendspinEnabled = uiState?.sendspinEnabled == true
            androidx.compose.runtime.LaunchedEffect(sendspinEnabled) {
                if (!sendspinEnabled) {
                    showMediaPlayerStatsSheet = false
                }
            }
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_interaction_group_playback_title),
                showBottomHandle = sendspinEnabled && !showMediaPlayerStatsSheet,
                onBottomHandleClick = { showMediaPlayerStatsSheet = true },
            ) {
                mediaPlayerSettingsItems(
                    viewModel = viewModel,
                    uiState = uiState,
                    playerState = playerState,
                    enabled = enabled,
                    context = context,
                    coroutineScope = coroutineScope,
                    navController = navController,
                )
            }
            if (showMediaPlayerStatsSheet) {
                MediaPlayerStatsSheet(
                    onDismiss = { showMediaPlayerStatsSheet = false },
                    isDarkMode = isDarkMode,
                )
            }
        }

        InteractionSettingsDestination.PlaybackEqHa -> {
            MusicEqualizerSettingsScreen(
                navController = navController,
                source = com.example.ava.audio.eq.MusicEqSource.HA,
                viewModel = viewModel,
            )
        }

        InteractionSettingsDestination.PlaybackEqMa -> {
            MusicEqualizerSettingsScreen(
                navController = navController,
                source = com.example.ava.audio.eq.MusicEqSource.SENDSPIN,
                viewModel = viewModel,
            )
        }

        InteractionSettingsDestination.PlaybackMassApi -> {
            MassApiSettingsScreen(navController = navController)
        }

        InteractionSettingsDestination.Scene -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_interaction_group_scene_title)
            ) {
                sceneSettingsSection(
                    navController = navController,
                    viewModel = viewModel,
                    playerState = playerState,
                    notificationState = notificationState,
                    enabled = enabled,
                    context = context,
                    coroutineScope = coroutineScope,
                    onOpenAppearanceSettings = {
                        navController.navigate(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        InteractionSettingsDestination.SimpleClockAppearance -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_simple_clock_appearance_entry_title)
            ) {
                simpleClockAppearanceSettingsSection(
                    viewModel = viewModel,
                    playerState = playerState,
                    enabled = enabled,
                    coroutineScope = coroutineScope,
                    onOpenStatusSettings = {
                        navController.navigate(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_STATUS) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        InteractionSettingsDestination.VoiceMessage -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_interaction_group_voice_message_title)
            ) {
                voiceMessageSettingsSection(
                    viewModel = viewModel,
                    playerState = playerState,
                    enabled = enabled,
                    context = context,
                    coroutineScope = coroutineScope
                )
            }
        }

        InteractionSettingsDestination.DreamClockAppearance -> {
            SettingsDetailScreen(
                navController = navController,
                title = stringResource(R.string.settings_dream_clock_appearance_title)
            ) {
                dreamClockAppearanceSettingsSection(
                    viewModel = viewModel,
                    playerState = playerState,
                    enabled = enabled,
                    coroutineScope = coroutineScope,
                )
            }
        }
    }
}

/** Style A: portrait stacked large cards; landscape left featured + right three. */
@Composable
private fun InteractionSettingsBento(
    isLandscape: Boolean,
    isSmallScreen: Boolean,
    onPlaybackClick: () -> Unit,
    onSceneClick: () -> Unit,
    onVoiceMessageClick: () -> Unit,
    onQuickEntityClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackTitle = stringResource(R.string.settings_interaction_group_playback_title)
    val playbackDesc = stringResource(R.string.settings_interaction_group_playback_desc)
    val sceneTitle = stringResource(R.string.settings_interaction_group_scene_title)
    val sceneDesc = stringResource(R.string.settings_interaction_group_scene_desc)
    val voiceTitle = stringResource(R.string.settings_interaction_group_voice_message_title)
    val voiceDesc = stringResource(R.string.settings_interaction_group_voice_message_desc)
    val entityTitle = stringResource(R.string.settings_interaction_group_quick_entity_title)
    val entityDesc = stringResource(R.string.settings_interaction_group_quick_entity_desc)
    val accent = getAccentColor()
    val gap = 12.dp

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            // Featured: title/desc on top, icon bottom-end (original Style A left card).
            InteractionLargeEntryCard(
                title = playbackTitle,
                description = playbackDesc,
                iconResId = R.drawable.mdi_music_note,
                tone = accent,
                featured = true,
                featuredStack = true,
                fillHeight = true,
                // Landscape left card: show full description; edge fade only on tiny screens.
                fullDescription = true,
                descriptionEdgeFade = isSmallScreen,
                onClick = onPlaybackClick,
                titleSize = 30.sp,
                titleMinSize = 18.sp,
                descSize = 17.sp,
                descMinSize = 13.sp,
                descMaxLines = 2,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                InteractionLargeEntryCard(
                    title = sceneTitle,
                    description = sceneDesc,
                    iconResId = R.drawable.mdi_puzzle_heart,
                    tone = BentoToneTeal,
                    featured = false,
                    onClick = onSceneClick,
                    compact = true,
                    fillHeight = true,
                    titleSize = 16.sp,
                    titleMinSize = 11.sp,
                    descSize = 13.sp,
                    descMinSize = 11.sp,
                    descMaxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                InteractionLargeEntryCard(
                    title = voiceTitle,
                    description = voiceDesc,
                    iconResId = R.drawable.mdi_message,
                    tone = BentoToneRose,
                    featured = false,
                    onClick = onVoiceMessageClick,
                    compact = true,
                    fillHeight = true,
                    titleSize = 16.sp,
                    titleMinSize = 11.sp,
                    descSize = 13.sp,
                    descMinSize = 11.sp,
                    descMaxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                InteractionLargeEntryCard(
                    title = entityTitle,
                    description = entityDesc,
                    iconResId = R.drawable.mdi_puzzle_plus,
                    tone = BentoToneAmber,
                    featured = false,
                    onClick = onQuickEntityClick,
                    compact = true,
                    fillHeight = true,
                    titleSize = 16.sp,
                    titleMinSize = 11.sp,
                    descSize = 13.sp,
                    descMinSize = 11.sp,
                    descMaxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InteractionLargeEntryCard(
                title = playbackTitle,
                description = playbackDesc,
                iconResId = R.drawable.mdi_music_note,
                tone = accent,
                featured = true,
                // Portrait: same left-text / right-icon layout as the other cards.
                featuredStack = false,
                onClick = onPlaybackClick,
                titleSize = 20.sp,
                titleMinSize = 13.sp,
                descSize = 15.sp,
                descMinSize = 13.sp,
                descMaxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
            )
            InteractionLargeEntryCard(
                title = sceneTitle,
                description = sceneDesc,
                iconResId = R.drawable.mdi_puzzle_heart,
                tone = BentoToneTeal,
                featured = false,
                onClick = onSceneClick,
                titleSize = 20.sp,
                titleMinSize = 13.sp,
                descSize = 15.sp,
                descMinSize = 13.sp,
                descMaxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
            )
            InteractionLargeEntryCard(
                title = voiceTitle,
                description = voiceDesc,
                iconResId = R.drawable.mdi_message,
                tone = BentoToneRose,
                featured = false,
                onClick = onVoiceMessageClick,
                titleSize = 20.sp,
                titleMinSize = 13.sp,
                descSize = 15.sp,
                descMinSize = 13.sp,
                descMaxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
            )
            InteractionLargeEntryCard(
                title = entityTitle,
                description = entityDesc,
                iconResId = R.drawable.mdi_puzzle_plus,
                tone = BentoToneAmber,
                featured = false,
                onClick = onQuickEntityClick,
                titleSize = 20.sp,
                titleMinSize = 13.sp,
                descSize = 15.sp,
                descMinSize = 13.sp,
                descMaxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
            )
        }
    }
}

private val BentoToneTeal = Color(0xFF0A8F7A)
private val BentoToneRose = Color(0xFFB83D6E)
private val BentoToneAmber = Color(0xFFC45C26)

@Composable
private fun InteractionLargeEntryCard(
    title: String,
    description: String,
    iconResId: Int,
    tone: Color,
    featured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Featured left-card layout: title/desc on top, icon bottom-end. */
    featuredStack: Boolean = false,
    /** When true, content fills a bounded parent height (landscape). */
    fillHeight: Boolean = false,
    /** Landscape left card: full description (no 2-line cap) in remaining space. */
    fullDescription: Boolean = false,
    /** Soft edge fade for [fullDescription] — only enable on small screens. */
    descriptionEdgeFade: Boolean = false,
    titleSize: TextUnit = 20.sp,
    titleMinSize: TextUnit = 13.sp,
    descSize: TextUnit = 15.sp,
    descMinSize: TextUnit = 12.sp,
    descMaxLines: Int = 2,
) {
    val accent = getAccentColor()
    val corner = if (compact) 22.dp else 26.dp
    val titleColor: Color
    val descColor: Color
    val iconTint: Color
    val iconBg: Color
    val surfaceColor: Color
    val wash: Brush
    val borderColor: Color

    if (featured) {
        titleColor = Color.White
        descColor = Color.White.copy(alpha = 0.86f)
        iconTint = Color.White
        iconBg = Color.White.copy(alpha = 0.18f)
        surfaceColor = Color.Transparent
        borderColor = Color.Transparent
        wash = Brush.linearGradient(
            colors = listOf(
                lerp(accent, Color.Black, 0.28f),
                accent,
                lerp(accent, BentoToneTeal, 0.42f)
            )
        )
    } else {
        titleColor = getTitleColor()
        descColor = getSettingsDescriptionColor()
        iconTint = tone
        iconBg = tone.copy(alpha = 0.14f)
        surfaceColor = getDialogBackground()
        borderColor = tone.copy(alpha = 0.18f)
        wash = Brush.linearGradient(
            colors = listOf(
                tone.copy(alpha = 0.12f),
                Color.Transparent
            )
        )
    }

    // Landscape featured (left large card): scale chrome with larger title/desc.
    val landscapeFeatured = featuredStack && fillHeight
    val hPad = when {
        landscapeFeatured -> 28.dp
        featuredStack -> 22.dp
        // Landscape right stack only (portrait never passes compact).
        compact -> 22.dp
        else -> 22.dp
    }
    val vPad = when {
        landscapeFeatured -> 26.dp
        featuredStack -> 20.dp
        compact -> 20.dp
        else -> 20.dp
    }
    val iconBox = when {
        landscapeFeatured -> 56.dp
        featuredStack -> 44.dp
        compact -> 36.dp
        else -> 48.dp
    }
    val iconInner = when {
        landscapeFeatured -> 26.dp
        featuredStack -> 20.dp
        compact -> 18.dp
        else -> 22.dp
    }
    val iconRadius = when {
        landscapeFeatured -> 18.dp
        featuredStack -> 15.dp
        compact -> 12.dp
        else -> 16.dp
    }
    val textGap = if (compact) 12.dp else 16.dp
    val descTop = when {
        compact -> 6.dp
        landscapeFeatured -> 12.dp
        else -> 8.dp
    }
    // Tablet/large panels: description follows the shared settings text scale.
    // Phones (shortest side < 600dp) keep the hand-tuned constants untouched.
    val bentoConfiguration = LocalConfiguration.current
    val bentoShortestSideDp = minOf(bentoConfiguration.screenWidthDp, bentoConfiguration.screenHeightDp)
    val descScale = if (bentoShortestSideDp >= 600) rememberSettingsTextScale() else 1f
    val effectiveDescSize = (descSize.value * descScale).sp
    val descLineHeight = (effectiveDescSize.value * 15.5f / 13f).sp
    // Featured gradient cards fade into accent; others into the card surface.
    val descFadeTo = if (featured) accent else getDialogBackground()

    @Composable
    fun TitleText() {
        AutoResizeText(
            text = title,
            fontSize = titleSize,
            minFontSize = titleMinSize,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            maxLines = 1,
            softWrap = true,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    fun TwoLineDescription() {
        CollapsibleDescriptionText(
            text = description,
            collapsedLines = 2,
            fontSize = effectiveDescSize,
            lineHeight = descLineHeight,
            color = descColor,
            fadeToColor = descFadeTo,
            topPadding = descTop,
        )
    }

    @Composable
    fun TitleBlock(modifier: Modifier = Modifier) {
        Column(modifier = modifier) {
            TitleText()
            TwoLineDescription()
        }
    }

    @Composable
    fun IconChip() {
        Box(
            modifier = Modifier
                .size(iconBox)
                .clip(RoundedCornerShape(iconRadius))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconInner)
            )
        }
    }

    val contentFill = if (fillHeight) Modifier.fillMaxHeight() else Modifier

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .then(
                if (borderColor.alpha > 0f) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(corner))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(corner),
        color = surfaceColor,
        shadowElevation = 0.dp
    ) {
        if (featuredStack && fullDescription && fillHeight) {
            // Landscape left: title + full description (remaining height) + icon. No 2-line clamp.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(wash)
                    .padding(horizontal = hPad, vertical = vPad),
            ) {
                TitleText()
                FillHeightDescriptionText(
                    text = description,
                    fontSize = effectiveDescSize,
                    lineHeight = descLineHeight,
                    color = descColor,
                    topPadding = descTop,
                    edgeFadeEnabled = descriptionEdgeFade,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    IconChip()
                }
            }
        } else if (featuredStack) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(contentFill)
                    .then(if (!fillHeight) Modifier.heightIn(min = 120.dp) else Modifier)
                    .background(wash)
                    .padding(horizontal = hPad, vertical = vPad),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                TitleBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fillHeight) Modifier.weight(1f, fill = false) else Modifier)
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    IconChip()
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(contentFill)
                    .then(if (!fillHeight) Modifier.heightIn(min = 120.dp) else Modifier)
                    .background(wash)
                    .padding(horizontal = hPad, vertical = vPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(textGap)
            ) {
                TitleBlock(modifier = Modifier.weight(1f))
                IconChip()
            }
        }
    }
}

private fun LazyListScope.interfaceSettingsSection(
    hideHeader: Boolean,
    onHideHeaderChange: (Boolean) -> Unit,
    headerTitle: String,
    headerSubtitle: String,
    onHeaderTextChange: (String, String) -> Unit,
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    syncDarkModeToHass: Boolean,
    onSyncDarkModeToHassChange: (Boolean) -> Unit,
    transparentSettingsButton: Boolean,
    onTransparentSettingsButtonChange: (Boolean) -> Unit,
) {
    item(key = "dark_mode") {
        SimpleCard {
            SettingRow(
                label = stringResource(R.string.settings_dark_mode),
                subLabel = stringResource(R.string.settings_dark_mode_desc)
            ) {
                ModernSwitch(
                    checked = isDarkMode,
                    enabled = true,
                    onCheckedChange = onDarkModeChange
                )
            }
            SettingsDivider()
            SettingRow(
                label = stringResource(R.string.settings_sync_dark_mode_to_hass),
                subLabel = stringResource(R.string.settings_sync_dark_mode_to_hass_desc)
            ) {
                ModernSwitch(
                    checked = syncDarkModeToHass,
                    enabled = true,
                    onCheckedChange = onSyncDarkModeToHassChange
                )
            }
        }
    }
    item(key = "display_settings") {
        SimpleCard {
            SettingRow(
                label = stringResource(R.string.settings_hide_header),
                subLabel = stringResource(R.string.settings_hide_header_desc)
            ) {
                ModernSwitch(
                    checked = hideHeader,
                    enabled = true,
                    onCheckedChange = onHideHeaderChange
                )
            }

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            HeaderTextSetting(
                name = stringResource(R.string.settings_header_text),
                description = stringResource(R.string.settings_header_text_desc),
                titleValue = headerTitle,
                subtitleValue = headerSubtitle,
                displayValue = stringResource(
                    R.string.settings_header_text_value_format,
                    headerTitle,
                    headerSubtitle
                ),
                titlePlaceholder = stringResource(R.string.home_header_title_default),
                subtitlePlaceholder = stringResource(R.string.home_header_subtitle_default),
                titleMaxLength = HEADER_TITLE_MAX_LENGTH,
                subtitleMaxLength = HEADER_SUBTITLE_MAX_LENGTH,
                enabled = true,
                onConfirmRequest = onHeaderTextChange
            )

            SettingsDivider()

            SettingRow(
                label = stringResource(R.string.settings_transparent_settings_button),
                subLabel = stringResource(R.string.settings_transparent_settings_button_desc)
            ) {
                ModernSwitch(
                    checked = transparentSettingsButton,
                    enabled = true,
                    onCheckedChange = onTransparentSettingsButtonChange
                )
            }

        }
    }
}

private fun LazyListScope.voiceMessageSettingsSection(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val hasOverlayPermission = checkOverlayPermission(context)
    val masterEnabled = (playerState?.enableVoiceMessageOverlay == true) && hasOverlayPermission

    item(key = "voice_master") {
        var showVoiceHaHelp by remember { mutableStateOf(false) }
        SimpleCard {
            SettingsInsetWell {
                SettingRow(
                    label = stringResource(R.string.settings_voice_master),
                    subLabel = stringResource(R.string.settings_voice_master_desc)
                ) {
                    ModernSwitch(
                        checked = masterEnabled,
                        enabled = enabled,
                        onCheckedChange = {
                            if (it && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveVoiceMessageOverlay(it)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            }
                        }
                    )
                }

                SettingsWellDivider()

                SettingRow(
                    label = stringResource(R.string.settings_voice_message_overlay_ha_switch),
                    subLabel = stringResource(R.string.settings_voice_message_overlay_ha_switch_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableVoiceMessageOverlayDisplay ?: false,
                        enabled = enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveVoiceMessageOverlayDisplay(it)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                    )
                }
            }

            SettingsDivider()

            SettingRow(
                label = stringResource(R.string.settings_usage_guide_title),
                onClick = { showVoiceHaHelp = true },
            ) {
                SettingRowHelpMark()
            }
        }
        if (showVoiceHaHelp) {
            val voiceHaCopyText = buildString {
                appendLine(stringResource(R.string.voice_ha_help_desc))
                appendLine()
                appendLine(stringResource(R.string.voice_ha_help_call_title))
                appendLine(stringResource(R.string.voice_ha_help_call_content))
                appendLine()
                appendLine(stringResource(R.string.voice_ha_help_automation_title))
                appendLine(stringResource(R.string.voice_ha_help_automation_content))
                appendLine()
                appendLine(stringResource(R.string.voice_ha_help_params_title))
                appendLine(stringResource(R.string.voice_ha_help_params_content))
            }
            UsageGuideDialog(
                onDismissRequest = { showVoiceHaHelp = false },
                title = stringResource(R.string.settings_usage_guide_title),
                copyText = voiceHaCopyText,
            ) {
                Text(
                    text = stringResource(R.string.voice_ha_help_desc),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    lineHeight = settingsBodyLineHeight()
                )
                Text(
                    text = stringResource(R.string.voice_ha_help_call_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.voice_ha_help_call_content),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        lineHeight = settingsBodyLineHeight(),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Text(
                    text = stringResource(R.string.voice_ha_help_automation_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.voice_ha_help_automation_content),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        lineHeight = settingsBodyLineHeight(),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Text(
                    text = stringResource(R.string.voice_ha_help_params_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.voice_ha_help_params_content),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        lineHeight = settingsBodyLineHeight(),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }

    if (!masterEnabled) return

    item(key = "voice_flow_outbound_header") {
        VoiceFlowSectionHeader(direction = VoiceFlowDirection.Outbound)
    }

    item(key = "voice_message_outbound") {
        SimpleCard {
            VoiceCapabilityPicker(
                intercomEnabled = playerState?.enableVoiceOverlayIntercom ?: true,
                callEnabled = playerState?.enableVoiceOverlayCall ?: true,
                enabled = enabled,
                onIntercomChange = { checked ->
                    coroutineScope.launch {
                        viewModel.saveVoiceOverlayIntercom(checked)
                    }
                },
                onCallChange = { checked ->
                    coroutineScope.launch {
                        viewModel.saveVoiceOverlayCall(checked)
                    }
                }
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(10.dp))

            TextSetting(
                name = stringResource(R.string.settings_voice_message_display_name),
                description = stringResource(R.string.settings_voice_message_display_name_desc),
                dialogHint = stringResource(R.string.settings_voice_message_display_name_dialog_desc),
                value = playerState?.voiceMessageDisplayName ?: "",
                placeholder = stringResource(R.string.settings_voice_message_display_name_placeholder),
                enabled = enabled,
                onConfirmRequest = {
                    coroutineScope.launch {
                        viewModel.saveVoiceMessageDisplayName(it)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                }
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            IntSetting(
                name = stringResource(R.string.settings_voice_message_delay_minutes),
                description = stringResource(R.string.settings_voice_message_delay_minutes_desc),
                value = playerState?.voiceMessageDelayMinutes ?: 0,
                enabled = enabled,
                validation = { value ->
                    when {
                        value == null -> null
                        value < 0 -> context.getString(R.string.validation_range, 0, 1440)
                        value > 1440 -> context.getString(R.string.validation_range, 0, 1440)
                        else -> null
                    }
                },
                onConfirmRequest = {
                    coroutineScope.launch {
                        viewModel.saveVoiceMessageDelayMinutes(it ?: 0)
                    }
                }
            )
        }
    }

    item(key = "voice_flow_inbound_header") {
        VoiceFlowSectionHeader(direction = VoiceFlowDirection.Inbound)
    }

    item(key = "voice_message_inbound") {
        SimpleCard {
            val intercomCapability = playerState?.enableVoiceOverlayIntercom ?: true
            val callCapability = playerState?.enableVoiceOverlayCall ?: true

            if (intercomCapability) {
                VoiceInboundSubsectionLabel(
                    label = stringResource(R.string.settings_voice_inbound_message_section)
                )

                SettingRow(
                    label = stringResource(R.string.settings_voice_message_receive),
                    subLabel = stringResource(R.string.settings_voice_message_receive_desc)
                ) {
                    ModernSwitch(
                        checked = (playerState?.enableVoiceMessageReceive == true) && hasOverlayPermission,
                        enabled = enabled,
                        onCheckedChange = {
                            if (it && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveVoiceMessageReceive(it)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            }
                        }
                    )
                }

                val receiveEnabled = (playerState?.enableVoiceMessageReceive == true) && hasOverlayPermission
                if (receiveEnabled) {
                    SettingsDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    val receiveModeOptions = listOf("auto", "board")
                    val receiveModeAuto = stringResource(R.string.settings_voice_message_receive_mode_auto)
                    val receiveModeAutoDesc = stringResource(R.string.settings_voice_message_receive_mode_auto_desc)
                    val receiveModeBoard = stringResource(R.string.settings_voice_message_receive_mode_board)
                    val receiveModeBoardDesc = stringResource(R.string.settings_voice_message_receive_mode_board_desc)
                    SelectSetting(
                        name = stringResource(R.string.settings_voice_message_receive_mode),
                        description = stringResource(R.string.settings_voice_message_receive_mode_desc),
                        selected = playerState?.voiceMessageReceiveMode ?: "auto",
                        items = receiveModeOptions,
                        enabled = enabled,
                        value = { mode ->
                            when (mode) {
                                "board" -> receiveModeBoard
                                else -> receiveModeAuto
                            }
                        },
                        itemDescription = { mode ->
                            when (mode) {
                                "board" -> receiveModeBoardDesc
                                else -> receiveModeAutoDesc
                            }
                        },
                        onConfirmRequest = {
                            coroutineScope.launch {
                                viewModel.saveVoiceMessageReceiveMode(it ?: "auto")
                            }
                        }
                    )
                }
            }

            if (intercomCapability && callCapability) {
                SettingsDivider()
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (callCapability) {
                VoiceInboundSubsectionLabel(
                    label = stringResource(R.string.settings_voice_inbound_call_section)
                )

                SettingRow(
                    label = stringResource(R.string.settings_voice_call_answer_required),
                    subLabel = stringResource(R.string.settings_voice_call_answer_required_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableVoiceCallAnswerRequired ?: true,
                        enabled = enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveVoiceCallAnswerRequired(it)
                            }
                        }
                    )
                }

                val answerRequired = playerState?.enableVoiceCallAnswerRequired ?: true
                if (answerRequired) {
                    SettingsDivider()
                    VoiceCallRingtoneRow(
                        soundUri = playerState?.voiceCallRingtone
                            ?: PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE,
                        enabled = enabled,
                        context = context,
                        onSoundSelected = { uri ->
                            coroutineScope.launch {
                                viewModel.saveVoiceCallRingtone(uri)
                            }
                        }
                    )
                }

                SettingsDivider()
                val videoQualityOptions = VoiceCallVideoQuality.entries
                val qualitySmooth = stringResource(R.string.settings_voice_call_video_quality_smooth)
                val qualitySmoothDesc = stringResource(R.string.settings_voice_call_video_quality_smooth_desc)
                val qualityHigh = stringResource(R.string.settings_voice_call_video_quality_high)
                val qualityHighDesc = stringResource(R.string.settings_voice_call_video_quality_high_desc)
                val qualityUltra = stringResource(R.string.settings_voice_call_video_quality_ultra)
                val qualityUltraDesc = stringResource(R.string.settings_voice_call_video_quality_ultra_desc)
                SelectSetting(
                    name = stringResource(R.string.settings_voice_call_video_quality),
                    description = stringResource(R.string.settings_voice_call_video_quality_desc),
                    selected = VoiceCallVideoQuality.fromStored(playerState?.voiceCallVideoQuality),
                    items = videoQualityOptions,
                    enabled = enabled,
                    key = { it.storageKey },
                    value = { quality ->
                        when (quality) {
                            VoiceCallVideoQuality.HIGH -> qualityHigh
                            VoiceCallVideoQuality.ULTRA -> qualityUltra
                            else -> qualitySmooth
                        }
                    },
                    itemDescription = { quality ->
                        when (quality) {
                            VoiceCallVideoQuality.HIGH -> qualityHighDesc
                            VoiceCallVideoQuality.ULTRA -> qualityUltraDesc
                            else -> qualitySmoothDesc
                        }
                    },
                    onConfirmRequest = { quality ->
                        if (quality != null) {
                            coroutineScope.launch {
                                viewModel.saveVoiceCallVideoQuality(quality)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceCallRingtoneRow(
    soundUri: String,
    enabled: Boolean,
    context: android.content.Context,
    onSoundSelected: (String) -> Unit
) {
    val defaultLabel = stringResource(R.string.voice_call_ringtone_default)
    val unknownLabel = stringResource(R.string.sound_unknown)

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
            } catch (_: Exception) {
            }
            externalSoundUri = uri.toString()
        }
    }

    val defaultUri = PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE
    var ringtones by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var soundName by remember(soundUri) { mutableStateOf(defaultLabel) }

    LaunchedEffect(soundUri, defaultLabel, unknownLabel, defaultUri) {
        soundName = SystemRingtoneLoader.resolveTitle(
            context = context,
            soundUri = soundUri,
            defaultUri = defaultUri,
            defaultLabel = defaultLabel,
            unknownLabel = unknownLabel
        )
    }

    LaunchedEffect(showRingtonePicker, defaultLabel, unknownLabel, defaultUri) {
        if (!showRingtonePicker || ringtones != null) return@LaunchedEffect
        ringtones = SystemRingtoneLoader.loadRingtones(
            context = context,
            ringtoneType = RingtoneManager.TYPE_RINGTONE,
            prefixEntries = listOf(defaultLabel to defaultUri),
            unknownLabel = unknownLabel
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showRingtonePicker = true }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.voice_call_ringtone),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.wake_sound_select_prefix) + soundName,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor()
            )
        }
        SettingsChevronIcon(tint = Color(0xFF94A3B8))
    }

    if (showRingtonePicker && ringtones != null) {
        SharedRingtonePickerDialog(
            ringtones = ringtones!!,
            currentUri = soundUri.ifBlank { defaultUri },
            context = context,
            title = stringResource(R.string.voice_call_ringtone_select),
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


/**
 * Settings-level face choice. FILL and FLIP are two pages of the same StandBy face
 * that the user swipes between on the overlay, so the settings radio must not split
 * them — otherwise the "chosen" option changes under the user every swipe.
 */
private enum class DreamClockFamily { MECHANICAL, STANDBY }

private fun DreamClockFace.family(): DreamClockFamily = when (this) {
    DreamClockFace.MECHANICAL -> DreamClockFamily.MECHANICAL
    DreamClockFace.FILL, DreamClockFace.FLIP -> DreamClockFamily.STANDBY
}

private fun LazyListScope.dreamClockAppearanceSettingsSection(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val currentFace = DreamClockFace.fromStored(playerState?.dreamClockFace)
    val standby = currentFace.family() == DreamClockFamily.STANDBY

    // Card 1 — face family + StandBy-only display toggles
    item(key = "dream_clock_face") {
        SimpleCard {
            val faceMechanical = stringResource(R.string.settings_dream_clock_face_mechanical)
            val faceMechanicalDesc = stringResource(R.string.settings_dream_clock_face_mechanical_desc)
            val faceStandby = stringResource(R.string.settings_dream_clock_face_standby)
            val faceStandbyDesc = stringResource(R.string.settings_dream_clock_face_standby_desc)
            SelectSetting(
                name = stringResource(R.string.settings_dream_clock_face),
                description = stringResource(R.string.settings_dream_clock_face_desc),
                selected = currentFace.family(),
                items = DreamClockFamily.entries,
                enabled = enabled,
                key = { it.name },
                value = { family ->
                    when (family) {
                        DreamClockFamily.MECHANICAL -> faceMechanical
                        DreamClockFamily.STANDBY,
                        null -> faceStandby
                    }
                },
                itemDescription = { family ->
                    when (family) {
                        DreamClockFamily.MECHANICAL -> faceMechanicalDesc
                        DreamClockFamily.STANDBY -> faceStandbyDesc
                    }
                },
                onConfirmRequest = { family ->
                    if (family != null) {
                        val next = when (family) {
                            DreamClockFamily.MECHANICAL -> DreamClockFace.MECHANICAL
                            // Keep whichever StandBy page the overlay last showed.
                            DreamClockFamily.STANDBY -> if (standby) currentFace else DreamClockFace.FILL
                        }
                        coroutineScope.launch { viewModel.saveDreamClockFace(next) }
                    }
                }
            )

            if (standby) {
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_dream_clock_show_seconds),
                    subLabel = stringResource(R.string.settings_dream_clock_show_seconds_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.dreamClockFlipShowSeconds ?: true,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.saveDreamClockFlipShowSeconds(checked)
                            }
                        }
                    )
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_dream_clock_flip_style_ha_select),
                    subLabel = stringResource(R.string.settings_dream_clock_flip_style_ha_select_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.dreamClockFlipStyleHaSelect ?: false,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.saveDreamClockFlipStyleHaSelect(checked)
                            }
                        }
                    )
                }
            }
        }
    }

    // Card 2 — flip countdown: HA entity + alarm (StandBy only)
    if (standby) {
        item(key = "dream_clock_timer") {
            SimpleCard {
                DreamClockTimerEntitySetting(
                    viewModel = viewModel,
                    playerState = playerState,
                    enabled = enabled,
                    coroutineScope = coroutineScope,
                )
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))
                val soundOn = playerState?.dreamClockTimerSoundEnabled ?: true
                SettingRow(
                    label = stringResource(R.string.settings_dream_clock_timer_sound),
                    subLabel = stringResource(R.string.settings_dream_clock_timer_sound_desc)
                ) {
                    ModernSwitch(
                        checked = soundOn,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.saveDreamClockTimerSoundEnabled(checked)
                            }
                        }
                    )
                }
                if (soundOn) {
                    SettingsDivider()
                    val ctx = LocalContext.current
                    TimerFinishedSoundItem(
                        soundUri = playerState?.dreamClockTimerSound ?: "asset:///sounds/timer_finished.wav",
                        enabled = enabled,
                        context = ctx,
                        onSoundSelected = { uri ->
                            coroutineScope.launch {
                                viewModel.saveDreamClockTimerSound(uri)
                            }
                        }
                    )
                }
            }
        }
    }

    // Card 3 — smart AOD
    item(key = "dream_clock_smart_aod") {
        SimpleCard {
            SettingRow(
                label = stringResource(R.string.settings_dream_clock_smart_aod),
                subLabel = stringResource(R.string.settings_dream_clock_smart_aod_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.dreamClockSmartAodEnabled ?: false,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveDreamClockSmartAodEnabled(it)
                        }
                    }
                )
            }

            if (playerState?.dreamClockSmartAodEnabled == true) {
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val aodContext = LocalContext.current
                IntSetting(
                    name = stringResource(R.string.settings_dream_clock_smart_aod_timeout),
                    description = stringResource(R.string.settings_dream_clock_smart_aod_timeout_desc),
                    value = playerState.dreamClockSmartAodTimeoutSeconds,
                    enabled = enabled,
                    validation = { value ->
                        if (value != null && value in 10..3600) {
                            null
                        } else {
                            aodContext.getString(R.string.validation_range, 10, 3600)
                        }
                    },
                    onConfirmRequest = {
                        coroutineScope.launch {
                            if (it != null) viewModel.saveDreamClockSmartAodTimeoutSeconds(it)
                        }
                    },
                )

                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val maskPercent = playerState.dreamClockSmartAodMaskPercent.coerceIn(5, 100)
                var maskSlider by remember(maskPercent) { mutableFloatStateOf(maskPercent.toFloat()) }
                SettingSliderLabelRow(
                    title = stringResource(R.string.settings_dream_clock_smart_aod_mask),
                    description = stringResource(R.string.settings_dream_clock_smart_aod_mask_desc),
                    badgeText = "${maskSlider.toInt()}%",
                )
                TickSlider(
                    value = maskSlider,
                    onValueChange = { maskSlider = it },
                    onValueChangeFinished = {
                        val snapped = maskSlider.toInt().coerceIn(5, 100)
                        maskSlider = snapped.toFloat()
                        coroutineScope.launch {
                            viewModel.saveDreamClockSmartAodMaskPercent(snapped)
                        }
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
}

@Composable
private fun DreamClockTimerEntitySetting(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val currentEntity = playerState?.dreamClockTimerEntityId ?: ""
    var showDialog by remember { mutableStateOf(false) }
    val haPickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_dream_clock_timer_entity),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (currentEntity.isBlank()) {
                    stringResource(R.string.settings_dream_clock_timer_entity_empty)
                } else {
                    currentEntity
                },
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor()
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }

    if (showDialog) {
        if (haPickerAvailable) {
            com.example.ava.homeassistant.ui.HaEntityPickerDialog(
                title = stringResource(R.string.settings_dream_clock_timer_entity),
                currentValue = currentEntity,
                domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.Timer,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveDreamClockTimerEntityId(newValue)
                    }
                    showDialog = false
                },
            )
        } else {
            HaWeatherEntityDialog(
                currentValue = currentEntity,
                titleRes = R.string.settings_dream_clock_timer_entity,
                descRes = R.string.settings_dream_clock_timer_entity_desc,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveDreamClockTimerEntityId(newValue)
                    }
                    showDialog = false
                }
            )
        }
    }
}

private fun LazyListScope.simpleClockAppearanceSettingsSection(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onOpenStatusSettings: () -> Unit
) {
    // Card 1 — wallpaper / background appearance
    item(key = "simple_clock_wallpaper") {
        SimpleCard {
            ScreensaverWallpaperUrlSetting(
                viewModel = viewModel,
                playerState = playerState,
                enabled = enabled,
                coroutineScope = coroutineScope
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            ScreensaverWallpaperRefreshSetting(
                viewModel = viewModel,
                playerState = playerState,
                enabled = enabled,
                coroutineScope = coroutineScope
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SettingRow(
                label = stringResource(R.string.settings_screensaver_wallpaper_dual_pane),
                subLabel = stringResource(R.string.settings_screensaver_wallpaper_dual_pane_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.screensaverWallpaperDualPane ?: false,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveScreensaverWallpaperDualPane(it)
                        }
                    }
                )
            }

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SettingRow(
                label = stringResource(R.string.settings_screensaver_wallpaper_dark_overlay),
                subLabel = stringResource(R.string.settings_screensaver_wallpaper_dark_overlay_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.screensaverWallpaperDarkOverlayEnabled ?: true,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveScreensaverWallpaperDarkOverlayEnabled(it)
                        }
                    }
                )
            }

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            val portraitMagazine = stringResource(R.string.settings_simple_clock_portrait_magazine)
            val portraitMagazineDesc = stringResource(R.string.settings_simple_clock_portrait_magazine_desc)
            val portraitSimple = stringResource(R.string.settings_simple_clock_portrait_simple)
            val portraitSimpleDesc = stringResource(R.string.settings_simple_clock_portrait_simple_desc)
            SelectSetting(
                name = stringResource(R.string.settings_simple_clock_portrait_style),
                description = stringResource(R.string.settings_simple_clock_portrait_style_desc),
                selected = SimpleClockPortraitStyle.fromStored(playerState?.screensaverPortraitStyle),
                items = SimpleClockPortraitStyle.entries,
                enabled = enabled,
                key = { it.storageKey },
                value = { style ->
                    when (style) {
                        SimpleClockPortraitStyle.MAGAZINE -> portraitMagazine
                        else -> portraitSimple
                    }
                },
                itemDescription = { style ->
                    when (style) {
                        SimpleClockPortraitStyle.MAGAZINE -> portraitMagazineDesc
                        else -> portraitSimpleDesc
                    }
                },
                onConfirmRequest = { style ->
                    if (style != null) {
                        coroutineScope.launch {
                            viewModel.saveSimpleClockPortraitStyle(style)
                        }
                    }
                }
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SettingRow(
                label = stringResource(R.string.settings_simple_clock_weather),
                subLabel = stringResource(R.string.settings_simple_clock_weather_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.enableScreensaverWeather ?: true,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveSimpleClockWeatherEnabled(it)
                        }
                    }
                )
            }

            if (playerState?.enableScreensaverWeather != false) {
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))
                SimpleClockWeatherEntitySetting(
                    viewModel = viewModel,
                    playerState = playerState,
                    enabled = enabled,
                    coroutineScope = coroutineScope
                )
            }
        }
    }

    // Card 2 — on-clock display: time format + burn-in shift + smart AOD + entity status chips
    item(key = "simple_clock_status_display") {
        SimpleCard {
            SettingRow(
                label = stringResource(R.string.settings_simple_clock_12_hour),
                subLabel = stringResource(R.string.settings_simple_clock_12_hour_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.screensaver12HourEnabled ?: false,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveSimpleClock12HourEnabled(it)
                        }
                    }
                )
            }

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SettingRow(
                label = stringResource(R.string.settings_simple_clock_pixel_shift),
                subLabel = stringResource(R.string.settings_simple_clock_pixel_shift_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.screensaverPixelShiftEnabled ?: false,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveSimpleClockPixelShiftEnabled(it)
                        }
                    }
                )
            }

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            val darkOffContext = LocalContext.current
            SettingRow(
                label = stringResource(R.string.settings_screensaver_dark_off),
                subLabel = stringResource(R.string.settings_screensaver_dark_off_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.screensaverDarkOffEnabled ?: false,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            if (!it || ScreenControlUtils.ensureScreenOffPermission(darkOffContext)) {
                                viewModel.saveSimpleClockDarkOffEnabled(it)
                            }
                        }
                    }
                )
            }

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SettingRow(
                label = stringResource(R.string.settings_simple_clock_smart_aod),
                subLabel = stringResource(R.string.settings_simple_clock_smart_aod_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.smartPowerSavingAodEnabled ?: false,
                    enabled = enabled,
                    onCheckedChange = {
                        coroutineScope.launch {
                            viewModel.saveSmartPowerSavingAodEnabled(it)
                        }
                    }
                )
            }

            if (playerState?.smartPowerSavingAodEnabled == true) {
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                IntSetting(
                    name = stringResource(R.string.settings_simple_clock_smart_aod_timeout),
                    description = stringResource(R.string.settings_simple_clock_smart_aod_timeout_desc),
                    value = playerState.smartPowerSavingAodTimeoutSeconds,
                    enabled = enabled,
                    validation = { value ->
                        if (value != null && value in 10..3600) {
                            null
                        } else {
                            context.getString(R.string.validation_range, 10, 3600)
                        }
                    },
                    onConfirmRequest = {
                        coroutineScope.launch {
                            if (it != null) viewModel.saveSmartPowerSavingAodTimeoutSeconds(it)
                        }
                    },
                )

                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val maskPercent = playerState.smartPowerSavingAodMaskPercent.coerceIn(5, 100)
                var maskSlider by remember(maskPercent) { mutableFloatStateOf(maskPercent.toFloat()) }
                SettingSliderLabelRow(
                    title = stringResource(R.string.settings_simple_clock_smart_aod_mask),
                    description = stringResource(R.string.settings_simple_clock_smart_aod_mask_desc),
                    badgeText = "${maskSlider.toInt()}%",
                )
                TickSlider(
                    value = maskSlider,
                    onValueChange = { maskSlider = it },
                    onValueChangeFinished = {
                        val snapped = maskSlider.toInt().coerceIn(5, 100)
                        maskSlider = snapped.toFloat()
                        coroutineScope.launch {
                            viewModel.saveSmartPowerSavingAodMaskPercent(snapped)
                        }
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

            SettingsDivider()
            Spacer(modifier = Modifier.height(8.dp))

            SettingRow(
                label = stringResource(R.string.settings_screensaver_status_slots),
                subLabel = stringResource(R.string.settings_screensaver_status_slots_entry_desc),
                onClick = onOpenStatusSettings
            ) {
                SettingsChevronIcon(tint = SubLabelColor)
            }
        }
    }
}

private fun LazyListScope.sceneSettingsSection(
    navController: NavController,
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    notificationState: com.example.ava.settings.NotificationSettings?,
    enabled: Boolean,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onOpenAppearanceSettings: () -> Unit
) {
    item(key = "weather") {
        SimpleCard {
            val weatherEnabled = playerState?.enableWeatherOverlay ?: false
            val weatherMasterRow: @Composable () -> Unit = {
                SettingRow(
                    label = stringResource(R.string.settings_weather_overlay),
                    subLabel = stringResource(R.string.settings_weather_overlay_realtime_desc)
                ) {
                    ModernSwitch(
                        checked = weatherEnabled,
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
            }

            if (weatherEnabled) {
                SettingsInsetWell {
                    weatherMasterRow()

                    SettingsWellDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_weather_overlay_ha_switch),
                        subLabel = stringResource(R.string.settings_weather_overlay_ha_switch_desc)
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
                }

                SettingsDivider()
                HaWeatherEntitySetting(
                    viewModel = viewModel,
                    playerState = playerState,
                    enabled = enabled,
                    coroutineScope = coroutineScope
                )
            } else {
                weatherMasterRow()
            }
        }
    }

    item(key = "simple_clock") {
        // One card: master → HA display → Appearance & Status entry
        SimpleCard {
            val screensaverEnabled = playerState?.enableScreensaver ?: false
            val simpleClockMasterRow: @Composable () -> Unit = {
                SettingRow(
                    label = stringResource(R.string.settings_screensaver),
                    subLabel = stringResource(R.string.settings_screensaver_desc)
                ) {
                    ModernSwitch(
                        checked = screensaverEnabled,
                        enabled = enabled,
                        onCheckedChange = {
                            if (it && !checkOverlayPermission(context)) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveScreensaver(it)
                                }
                            }
                        }
                    )
                }
            }

            if (screensaverEnabled) {
                SettingsInsetWell {
                    simpleClockMasterRow()

                    SettingsWellDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_ha_switch),
                        subLabel = stringResource(R.string.settings_screensaver_ha_switch_desc)
                    ) {
                        ModernSwitch(
                            checked = playerState?.enableScreensaverDisplay ?: false,
                            enabled = enabled,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveScreensaverDisplay(it)
                                }
                            }
                        )
                    }
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_simple_clock_appearance_entry_title),
                    subLabel = stringResource(R.string.settings_simple_clock_appearance_entry_desc),
                    onClick = onOpenAppearanceSettings
                ) {
                    SettingsChevronIcon(tint = SubLabelColor)
                }
            } else {
                simpleClockMasterRow()
            }
        }
    }

    item(key = "dream_clock") {
        SimpleCard {
            val dreamClockEnabled = playerState?.enableDreamClock ?: false
            val dreamClockMasterRow: @Composable () -> Unit = {
                SettingRow(
                    label = stringResource(R.string.settings_dream_clock),
                    subLabel = stringResource(R.string.settings_dream_clock_desc)
                ) {
                    ModernSwitch(
                        checked = dreamClockEnabled,
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
            }

            if (dreamClockEnabled) {
                SettingsInsetWell {
                    dreamClockMasterRow()

                    SettingsWellDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_dream_clock_ha_switch),
                        subLabel = stringResource(R.string.settings_dream_clock_ha_switch_desc)
                    ) {
                        ModernSwitch(
                            checked = playerState?.enableDreamClockDisplay ?: false,
                            enabled = enabled,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveDreamClockDisplay(it)
                                }
                            }
                        )
                    }
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_dream_clock_appearance_title),
                    subLabel = stringResource(R.string.settings_dream_clock_appearance_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE) {
                            launchSingleTop = true
                        }
                    }
                ) {
                    SettingsChevronIcon(tint = SubLabelColor)
                }
            } else {
                dreamClockMasterRow()
            }
        }
    }

    item(key = "notification_scene") {
        SimpleCard {
            val notificationSceneEnabled = notificationState?.notificationSceneEnabled ?: false
            SettingRow(
                label = stringResource(R.string.entity_notification_scene),
                subLabel = stringResource(R.string.settings_notification_scene_enabled_desc)
            ) {
                ModernSwitch(
                    checked = notificationSceneEnabled,
                    enabled = enabled,
                    onCheckedChange = {
                        if (it && !checkOverlayPermission(context)) {
                            requestOverlayPermission(context)
                        } else {
                            coroutineScope.launch {
                                viewModel.saveNotificationSceneEnabled(it)
                            }
                        }
                    }
                )
            }

            if (notificationSceneEnabled) {
                SettingsDivider()
                Spacer(modifier = Modifier.height(12.dp))
                val style = notificationState?.displayStyle
                    ?: com.example.ava.settings.NotificationDisplayStyle.FULLSCREEN
                val fullscreenSelected =
                    style != com.example.ava.settings.NotificationDisplayStyle.BANNER

                val styleLandscape = rememberPaneIsLandscape()
                val selectFullscreen: () -> Unit = {
                    coroutineScope.launch {
                        viewModel.saveNotificationDisplayStyle(
                            com.example.ava.settings.NotificationDisplayStyle.FULLSCREEN
                        )
                    }
                    Unit
                }
                val selectBanner: () -> Unit = {
                    coroutineScope.launch {
                        viewModel.saveNotificationDisplayStyle(
                            com.example.ava.settings.NotificationDisplayStyle.BANNER
                        )
                    }
                    Unit
                }
                if (styleLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NotificationStyleChoiceCard(
                            label = stringResource(R.string.notif_style_fullscreen),
                            desc = stringResource(R.string.notif_style_fullscreen_desc),
                            selected = fullscreenSelected,
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onSelect = selectFullscreen,
                        )
                        NotificationStyleChoiceCard(
                            label = stringResource(R.string.notif_style_banner),
                            desc = stringResource(R.string.notif_style_banner_desc),
                            selected = !fullscreenSelected,
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onSelect = selectBanner,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NotificationStyleChoiceCard(
                            label = stringResource(R.string.notif_style_fullscreen),
                            desc = stringResource(R.string.notif_style_fullscreen_desc),
                            selected = fullscreenSelected,
                            enabled = enabled,
                            onSelect = selectFullscreen,
                        )
                        NotificationStyleChoiceCard(
                            label = stringResource(R.string.notif_style_banner),
                            desc = stringResource(R.string.notif_style_banner_desc),
                            selected = !fullscreenSelected,
                            enabled = enabled,
                            onSelect = selectBanner,
                        )
                    }
                }

                if (!fullscreenSelected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    NotificationSceneNavRow(
                        label = stringResource(R.string.notif_banner_appearance_title),
                        subLabel = stringResource(R.string.notif_banner_appearance_desc),
                        enabled = enabled,
                    ) {
                        navController.navigate(Screen.SETTINGS_INTERACTION_SCENE_BANNER) {
                            launchSingleTop = true
                        }
                    }
                }

                NotificationSceneNavRow(
                    label = stringResource(R.string.notif_scene_library_title),
                    subLabel = stringResource(R.string.notif_scenes_list_desc),
                    enabled = enabled,
                ) {
                    navController.navigate(Screen.SETTINGS_INTERACTION_SCENE_LIBRARY) {
                        launchSingleTop = true
                    }
                }

                SettingsDivider()

                NotificationSceneNavRow(
                    label = stringResource(R.string.notif_general_settings_title),
                    subLabel = stringResource(R.string.notif_general_settings_desc),
                    enabled = enabled,
                ) {
                    navController.navigate(Screen.SETTINGS_INTERACTION_SCENE_GENERAL) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationStyleChoiceCard(
    label: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = getAccentColor()
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (selected) accent else getSliderInactiveColor()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(
                    width = 2.dp,
                    color = when {
                        !enabled -> Color(0xFF555555).copy(alpha = 0.5f)
                        selected -> accent
                        else -> Color(0xFF555555)
                    },
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (enabled) accent else accent.copy(alpha = 0.5f)),
                )
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = getLabelColor(),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.SemiBold,
            )
            CollapsibleDescriptionText(
                text = desc,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
    }
}

@Composable
private fun NotificationSceneNavRow(
    label: String,
    subLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = getLabelColor(),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
            )
            CollapsibleDescriptionText(
                text = subLabel,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }
}

@Composable
internal fun NotificationSoundSection(
    viewModel: SettingsViewModel,
    notificationState: com.example.ava.settings.NotificationSettings,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
    val soundUri = notificationState.soundUri
    val noneLabel = stringResource(R.string.sound_none)
    val unknownLabel = stringResource(R.string.sound_unknown)
    
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
    
    var ringtones by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var soundName by remember(soundUri) { mutableStateOf(noneLabel) }

    LaunchedEffect(soundUri, noneLabel, unknownLabel) {
        soundName = SystemRingtoneLoader.resolveTitle(
            context = context,
            soundUri = soundUri,
            defaultUri = "",
            defaultLabel = noneLabel,
            unknownLabel = unknownLabel
        )
    }

    LaunchedEffect(showRingtonePicker, noneLabel, unknownLabel) {
        if (!showRingtonePicker || ringtones != null) return@LaunchedEffect
        ringtones = SystemRingtoneLoader.loadRingtones(
            context = context,
            ringtoneType = RingtoneManager.TYPE_NOTIFICATION,
            prefixEntries = listOf(noneLabel to ""),
            unknownLabel = unknownLabel
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showRingtonePicker = true }
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
    
    if (showRingtonePicker && ringtones != null) {
        SharedRingtonePickerDialog(
            ringtones = ringtones!!,
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
            }
        )
    }
}

@Composable
internal fun CustomSceneSection(
    viewModel: SettingsViewModel,
    notificationState: com.example.ava.settings.NotificationSettings,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
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

    SettingsDivider()

    var showPlaceholderDialog by remember { mutableStateOf(false) }
    SettingRow(
        label = stringResource(R.string.scene_text_placeholder_title),
        onClick = { showPlaceholderDialog = true },
    ) {
        SettingRowHelpMark()
    }
    if (showPlaceholderDialog) {
        val placeholderCopyText = buildString {
            appendLine(stringResource(R.string.scene_text_placeholder_desc))
            appendLine()
            appendLine(stringResource(R.string.scene_text_placeholder_syntax_title))
            appendLine(stringResource(R.string.scene_text_placeholder_syntax_body))
            appendLine()
            appendLine(stringResource(R.string.scene_text_placeholder_example_title))
            appendLine(stringResource(R.string.scene_text_placeholder_example))
            appendLine()
            appendLine(stringResource(R.string.scene_sound_title))
            appendLine(stringResource(R.string.scene_sound_desc))
            appendLine(stringResource(R.string.scene_sound_syntax_body))
        }
        UsageGuideDialog(
            onDismissRequest = { showPlaceholderDialog = false },
            title = stringResource(R.string.scene_text_placeholder_title),
            copyText = placeholderCopyText,
        ) {
            Text(
                text = stringResource(R.string.scene_text_placeholder_desc),
                fontSize = settingsBodyTextSize(),
                color = getLabelColor(),
                lineHeight = settingsBodyLineHeight()
            )
            Text(
                text = stringResource(R.string.scene_text_placeholder_syntax_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor()
            )
            Text(
                text = stringResource(R.string.scene_text_placeholder_syntax_body),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Text(
                text = stringResource(R.string.scene_text_placeholder_example_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor()
            )
            Text(
                text = stringResource(R.string.scene_text_placeholder_example),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Text(
                text = stringResource(R.string.scene_sound_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor()
            )
            Text(
                text = stringResource(R.string.scene_sound_desc),
                fontSize = settingsBodyTextSize(),
                color = getLabelColor(),
                lineHeight = settingsBodyLineHeight()
            )
            Text(
                text = stringResource(R.string.scene_sound_syntax_body),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HaMediaPlayerSetting(
    viewModel: SettingsViewModel,
    uiState: UIState?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val currentEntity = uiState?.haMediaPlayerEntity ?: ""
    var showDialog by remember { mutableStateOf(false) }
    val haPickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_ha_media_player),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.settings_ha_media_player_desc),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }
    
    if (showDialog) {
        if (haPickerAvailable) {
            com.example.ava.homeassistant.ui.HaEntityPickerDialog(
                title = stringResource(R.string.settings_ha_media_player),
                currentValue = currentEntity,
                domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.MediaPlayer,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveHaMediaPlayerEntity(newValue)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                    showDialog = false
                },
            )
        } else {
            HaMediaPlayerDialog(
                currentValue = currentEntity,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveHaMediaPlayerEntity(newValue)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                    showDialog = false
                }
            )
        }
    }
}

@Composable
private fun HaWeatherEntitySetting(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val currentEntity = playerState?.haWeatherEntity ?: ""
    var showDialog by remember { mutableStateOf(false) }
    val haPickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_ha_weather_entity),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.settings_ha_weather_entity_short_desc),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }
    
    if (showDialog) {
        if (haPickerAvailable) {
            com.example.ava.homeassistant.ui.HaEntityPickerDialog(
                title = stringResource(R.string.settings_ha_weather_entity),
                currentValue = currentEntity,
                domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.Weather,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveHaWeatherEntity(newValue)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                    showDialog = false
                },
            )
        } else {
            HaWeatherEntityDialog(
                currentValue = currentEntity,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveHaWeatherEntity(newValue)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                    showDialog = false
                }
            )
        }
    }
}

@Composable
private fun SimpleClockWeatherEntitySetting(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val currentEntity = playerState?.screensaverWeatherEntityId ?: ""
    var showDialog by remember { mutableStateOf(false) }
    val haPickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_simple_clock_weather_entity),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (currentEntity.isBlank()) {
                    stringResource(R.string.settings_simple_clock_weather_entity_empty)
                } else {
                    currentEntity
                },
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor()
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }

    if (showDialog) {
        if (haPickerAvailable) {
            com.example.ava.homeassistant.ui.HaEntityPickerDialog(
                title = stringResource(R.string.settings_simple_clock_weather_entity),
                currentValue = currentEntity,
                domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.Weather,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveSimpleClockWeatherEntity(newValue)
                    }
                    showDialog = false
                },
            )
        } else {
            HaWeatherEntityDialog(
                currentValue = currentEntity,
                titleRes = R.string.settings_simple_clock_weather_entity,
                descRes = R.string.settings_simple_clock_weather_entity_desc,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveSimpleClockWeatherEntity(newValue)
                    }
                    showDialog = false
                }
            )
        }
    }
}

@Composable
private fun HaWeatherEntityDialog(
    currentValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    titleRes: Int = R.string.settings_ha_weather_entity,
    descRes: Int = R.string.settings_ha_weather_entity_desc
) {
    var text by remember { mutableStateOf(currentValue) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(titleRes),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = getTitleColor()
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(descRes),
                    fontSize = 11.sp,
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                androidx.compose.material3.TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("weather.xxx", color = getSettingsDescriptionColor(), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = settingsTitleTextSize(), color = getLabelColor()),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = settingsFilledFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(android.R.string.ok), color = getAccentColor())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = getSettingsDescriptionColor())
            }
        },
        containerColor = getDialogBackground(),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun ScreensaverWallpaperUrlSetting(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val currentValue = playerState?.screensaverWallpaperUrl ?: ""
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_screensaver_wallpaper_url),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.settings_screensaver_wallpaper_url_desc),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }

    if (showDialog) {
        SimpleClockTextDialog(
            title = stringResource(R.string.settings_screensaver_wallpaper_url),
            description = stringResource(R.string.settings_screensaver_wallpaper_url_hint),
            currentValue = currentValue,
            placeholder = "https://example.com/wallpaper.jpg",
            showClearAction = true,
            clearContentDescription = stringResource(
                R.string.settings_screensaver_wallpaper_url_clear
            ),
            onDismiss = { showDialog = false },
            onConfirm = { newValue ->
                coroutineScope.launch {
                    viewModel.saveScreensaverWallpaperUrl(newValue)
                }
                showDialog = false
            }
        )
    }
}

@Composable
private fun ScreensaverWallpaperRefreshSetting(
    viewModel: SettingsViewModel,
    playerState: PlayerSettings?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    val currentValue = (playerState?.screensaverWallpaperRefreshSeconds ?: 30).toString()
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_screensaver_wallpaper_refresh),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.settings_screensaver_wallpaper_refresh_desc),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = SubLabelColor)
    }

    if (showDialog) {
        SimpleClockTextDialog(
            title = stringResource(R.string.settings_screensaver_wallpaper_refresh),
            description = stringResource(R.string.settings_screensaver_wallpaper_refresh_hint),
            currentValue = currentValue,
            placeholder = "30",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onDismiss = { showDialog = false },
            onConfirm = { newValue ->
                coroutineScope.launch {
                    viewModel.saveScreensaverWallpaperRefreshSeconds(newValue.toIntOrNull() ?: 30)
                }
                showDialog = false
            }
        )
    }
}

@Composable
private fun SimpleClockTextDialog(
    title: String,
    description: String,
    currentValue: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    validation: ((String) -> String?)? = null,
    showClearAction: Boolean = false,
    clearContentDescription: String? = null
) {
    var text by remember { mutableStateOf(currentValue) }
    val validationText = validation?.invoke(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = getTitleColor(),
                    modifier = Modifier.weight(1f)
                )
                if (showClearAction && text.isNotBlank()) {
                    ExpandedTapTarget(
                        onClick = { text = "" },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = clearContentDescription,
                            tint = SubLabelColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column {
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                androidx.compose.material3.TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            text = placeholder,
                            color = getSettingsDescriptionColor(),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    supportingText = if (!validationText.isNullOrBlank()) {
                        { Text(validationText, fontSize = settingsBodyTextSize()) }
                    } else {
                        null
                    },
                    isError = !validationText.isNullOrBlank(),
                    singleLine = true,
                    keyboardOptions = keyboardOptions,
                    // Keep a single-line control after clear; M3 TextField otherwise reserves tall padding.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = settingsTitleTextSize(),
                        color = getLabelColor()
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = settingsFilledFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = validationText.isNullOrBlank()
            ) {
                Text(stringResource(android.R.string.ok), color = getAccentColor())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = getSettingsDescriptionColor())
            }
        },
        containerColor = getDialogBackground(),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun HaMediaPlayerDialog(
    currentValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentValue) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_ha_media_player),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = getTitleColor()
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_ha_media_player_dialog_desc),
                    fontSize = 11.sp,
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                androidx.compose.material3.TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("media_player.xxx", color = getSettingsDescriptionColor(), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = settingsTitleTextSize(), color = getLabelColor()),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = settingsFilledFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(android.R.string.ok), color = getAccentColor())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = getSettingsDescriptionColor())
            }
        },
        containerColor = getDialogBackground(),
        shape = RoundedCornerShape(16.dp)
    )
}
