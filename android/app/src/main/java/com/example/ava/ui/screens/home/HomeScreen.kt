package com.example.ava.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.HomeLockSession
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.HomeLockTarget
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.avaHomeSystemBarPadding
import com.example.ava.ui.Screen
import com.example.ava.ui.components.LeftSidebarDrawerLayout
import com.example.ava.ui.components.NewFeatureBadge
import com.example.ava.ui.rememberAdaptiveSpec
import com.example.ava.ui.screens.settings.components.settingsFocusHighlight
import com.example.ava.ui.services.StartStopVoiceSatellite
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.prefs.rememberStringPreference
import kotlinx.coroutines.delay
import com.example.ava.ui.theme.SlateBackground
import com.example.ava.ui.theme.SlateText
import com.example.ava.ui.theme.SlateSecondary
import com.example.ava.ui.theme.SlateSecondaryDark
import com.example.ava.ui.theme.SlateTertiary
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentGreen

@Composable
fun SunMoonToggle(
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showNewBadge: Boolean = true
) {
    val transition = updateTransition(targetState = isDarkMode, label = "sunMoon")
    
    val sunAlpha by transition.animateFloat(
        transitionSpec = { tween(200) },
        label = "sunAlpha"
    ) { if (it) 0f else 1f }
    
    val sunScale by transition.animateFloat(
        transitionSpec = { tween(300) },
        label = "sunScale"
    ) { if (it) 0.6f else 1f }
    
    val moonAlpha by transition.animateFloat(
        transitionSpec = { tween(200) },
        label = "moonAlpha"
    ) { if (it) 1f else 0f }
    
    val moonScale by transition.animateFloat(
        transitionSpec = { tween(300) },
        label = "moonScale"
    ) { if (it) 1f else 0.3f }
    
    Box(
        modifier = modifier.size(28.dp),
        contentAlignment = Alignment.Center
    ) {
        // The visual is only ~28dp; keep that footprint but let the tap target
        // overflow to 44dp so imprecise / dropped touches still register.
        Box(
            modifier = Modifier
                .requiredSize(SUN_MOON_TOGGLE_HIT_SIZE)
                // Invisible tap pad: without a ring, remote-control focus would
                // park here with no visual anchor.
                .settingsFocusHighlight(
                    cornerRadius = SUN_MOON_TOGGLE_HIT_SIZE / 2,
                    horizontalOutset = 0.dp,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggle() }
        )
        Icon(
            painter = painterResource(R.drawable.mdi_weather_sunny),
            contentDescription = "Sun",
            tint = Color.Unspecified,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    alpha = sunAlpha
                    scaleX = sunScale
                    scaleY = sunScale
                }
        )
        Icon(
            painter = painterResource(R.drawable.mdi_weather_night),
            contentDescription = "Moon",
            tint = Color.Unspecified,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    alpha = moonAlpha
                    scaleX = moonScale
                    scaleY = moonScale
                }
        )
        NewFeatureBadge(visible = showNewBadge)
    }
}

const val PREFS_NAME = "ava_home_prefs"
const val KEY_DARK_MODE = "dark_mode"
const val KEY_DARK_MODE_BADGE_SEEN = "dark_mode_badge_seen"
const val KEY_HIDE_HEADER = "hide_header"
const val KEY_HEADER_TITLE = "header_title"
const val KEY_HEADER_SUBTITLE = "header_subtitle"
const val HEADER_TITLE_MAX_LENGTH = 12
const val HEADER_SUBTITLE_MAX_LENGTH = 20
const val KEY_TRANSPARENT_SETTINGS_BUTTON = "transparent_settings_button"

/**
 * Invisible enlargement of header touch targets. Budget panels with flaky digitizers
 * (dropped / phantom touches) miss small targets often; the extra pad is layout-neutral
 * (requiredSize overflow) so nothing on screen moves.
 */
private val SETTINGS_BUTTON_HIT_EXPANSION = 24.dp
private val SUN_MOON_TOGGLE_HIT_SIZE = 44.dp

private enum class HomeScaleTier {
    TINY,
    NORMAL,
    TABLET,
    LARGE,
    XLARGE
}

private fun adaptiveScale(
    value: Float,
    minInput: Float,
    maxInput: Float,
    minScale: Float,
    maxScale: Float
): Float {
    if (maxInput <= minInput) return value * maxScale
    val progress = ((value - minInput) / (maxInput - minInput)).coerceIn(0f, 1f)
    return minScale + (maxScale - minScale) * progress
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    var showBadge by remember { mutableStateOf(!prefs.getBoolean(KEY_DARK_MODE_BADGE_SEEN, false)) }
    var hideHeader by remember { mutableStateOf(prefs.getBoolean(KEY_HIDE_HEADER, false)) }
    val defaultHeaderTitle = stringResource(R.string.home_header_title_default)
    val defaultHeaderSubtitle = stringResource(R.string.home_header_subtitle_default)
    val storedHeaderTitle by rememberStringPreference(prefs, KEY_HEADER_TITLE)
    val storedHeaderSubtitle by rememberStringPreference(prefs, KEY_HEADER_SUBTITLE)
    val headerTitle = storedHeaderTitle.takeIf { it.isNotBlank() } ?: defaultHeaderTitle
    val headerSubtitle = storedHeaderSubtitle.takeIf { it.isNotBlank() } ?: defaultHeaderSubtitle
    val transparentSettingsButton by rememberBooleanPreference(prefs, KEY_TRANSPARENT_SETTINGS_BUTTON, false)
    var hideHomeChrome by remember { mutableStateOf(readHideHomeChrome(prefs)) }
    val layoutCache = remember { HomeLayoutSettingsMirror.readCached(appContext) }
    val playerSettingsStore = remember { PlayerSettingsStore(appContext.playerSettingsStore) }
    val sidebarSettingsStore = remember { SidebarSettingsStore(appContext.sidebarSettingsStore) }
    val homeLockSettingsStore = remember { HomeLockSettingsStore(appContext.homeLockSettingsStore) }
    val playerSettings by playerSettingsStore.getFlow().collectAsStateWithLifecycle(
        PlayerSettings(enableMinimalLauncher = layoutCache.enableMinimalLauncher)
    )
    val sidebarSettings by sidebarSettingsStore.getFlow().collectAsStateWithLifecycle(
        SidebarSettings(
            enableSidebar = layoutCache.enableSidebar,
            sidebarPosition = layoutCache.sidebarPosition,
            hideHomeHeader = layoutCache.hideHomeHeader,
        )
    )
    val homeLockSettings by homeLockSettingsStore.getFlow().collectAsStateWithLifecycle(null)
    LaunchedEffect(homeLockSettings) {
        // Skip Compose's placeholder null — never overwrite rotation-surviving session with defaults.
        val settings = homeLockSettings ?: return@LaunchedEffect
        HomeLockSession.syncFromSettings(settings)
    }
    val homeLockEnabled by HomeLockSession.featureEnabled.collectAsStateWithLifecycle()
    val homeLockTarget by HomeLockSession.lockTarget.collectAsStateWithLifecycle()
    val homeUnlocked by HomeLockSession.unlocked.collectAsStateWithLifecycle()
    val homeLockPinHash by HomeLockSession.pinHash.collectAsStateWithLifecycle()
    val homeLockPinLength by HomeLockSession.pinLength.collectAsStateWithLifecycle()
    val homeLockShuffleKeypad by HomeLockSession.shuffleKeypad.collectAsStateWithLifecycle()
    val homeLockAntiBruteForce by HomeLockSession.antiBruteForce.collectAsStateWithLifecycle()
    val locksHome = homeLockEnabled && homeLockTarget == HomeLockTarget.HOME
    val showHomePinLock = locksHome && !homeUnlocked
    val enableMinimalLauncher = playerSettings.enableMinimalLauncher
    val showMainSidebar = sidebarSettings.enableSidebar

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, locksHome, navController) {
        if (!locksHome) {
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Remember path: Settings (or any non-Home route) pauses idle;
                // backgrounding while still on Home keeps wall-clock idle.
                Lifecycle.Event.ON_STOP -> {
                    val leftForInApp =
                        navController.currentDestination?.route != Screen.HOME
                    HomeLockSession.onHomeStopped(leftForInAppNavigation = leftForInApp)
                }
                Lifecycle.Event.ON_RESUME -> {
                    HomeLockSession.onHomeResumed()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(locksHome, homeUnlocked) {
        if (!locksHome || !homeUnlocked) return@LaunchedEffect
        while (true) {
            delay(500)
            if (HomeLockSession.idleTimedOut()) {
                HomeLockSession.lock()
                break
            }
        }
    }

    LaunchedEffect(sidebarSettings, playerSettings) {
        val synced = syncHideHomeChromePref(prefs, sidebarSettings, playerSettings)
        if (synced != hideHomeChrome) {
            hideHomeChrome = synced
        }
    }

    LaunchedEffect(enableMinimalLauncher) {
        if (enableMinimalLauncher) {
            MinimalLauncherAppsCache.load(appContext, appContext.packageName)
        }
    }

    val effectiveHideHeader = hideHeader || hideHomeChrome

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = !rememberCompactSquareScreen() &&
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val adaptive = rememberAdaptiveSpec()
    ImmersiveMode(isLandscape = isLandscape)

    val backgroundColor by animateColorAsState(
        targetValue = if (isDarkMode) Color.Black else SlateBackground,
        animationSpec = tween(300),
        label = "backgroundColor"
    )
    val textColor by animateColorAsState(
        targetValue = if (isDarkMode) Color.White else SlateText,
        animationSpec = tween(300),
        label = "textColor"
    )
    val secondaryTextColor by animateColorAsState(
        targetValue = if (isDarkMode) SlateSecondaryDark else SlateSecondary,
        animationSpec = tween(300),
        label = "secondaryTextColor"
    )
    val settingsButtonColor by animateColorAsState(
        targetValue = if (transparentSettingsButton) {
            Color.Transparent
        } else if (isDarkMode) {
            Color(0xFF1F1F1F)
        } else {
            Color.White
        },
        animationSpec = tween(300),
        label = "settingsButtonColor"
    )
    val settingsIconColor by animateColorAsState(
        targetValue = if (transparentSettingsButton) {
            Color.Transparent
        } else {
            secondaryTextColor
        },
        animationSpec = tween(300),
        label = "settingsIconColor"
    )
    
    val mainContent: @Composable () -> Unit = {
        HomeScreenMainContent(
            navController = navController,
            backgroundColor = backgroundColor,
            isDarkMode = isDarkMode,
            isLandscape = isLandscape,
            adaptive = adaptive,
            enableMinimalLauncher = enableMinimalLauncher,
            layoutPrefersMinimalLauncher = layoutCache.enableMinimalLauncher,
            minimalLauncherWallpaperUri = playerSettings.minimalLauncherWallpaperUri,
            minimalLauncherWallpaperMode = playerSettings.minimalLauncherWallpaperMode,
            minimalLauncherWallpaperScale = playerSettings.minimalLauncherWallpaperScale,
            minimalLauncherWallpaperOffsetX = playerSettings.minimalLauncherWallpaperOffsetX,
            minimalLauncherWallpaperOffsetY = playerSettings.minimalLauncherWallpaperOffsetY,
            hideHeader = effectiveHideHeader,
            hideSettingsButton = hideHomeChrome,
            headerTitle = headerTitle,
            headerSubtitle = headerSubtitle,
            transparentSettingsButton = transparentSettingsButton,
            showBadge = showBadge,
            onDismissBadge = {
                showBadge = false
                prefs.edit().putBoolean(KEY_DARK_MODE_BADGE_SEEN, true).apply()
            },
            prefs = prefs,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            settingsButtonColor = settingsButtonColor,
            settingsIconColor = settingsIconColor
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .avaHomeSystemBarPadding(isLandscape)
            .then(
                if (locksHome && homeUnlocked) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed || it.previousPressed }) {
                                    HomeLockSession.noteInteraction()
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (showMainSidebar) {
            LeftSidebarDrawerLayout(
                isDarkMode = isDarkMode,
                panelColor = if (isDarkMode) Color.Black else SlateBackground,
                position = sidebarSettings.sidebarPosition,
                drawerWidth = homeSidebarDrawerWidth(),
                drawerContent = { closeDrawer ->
                    HomeSidebarContent(
                        navController = navController,
                        isDarkMode = isDarkMode,
                        onClose = closeDrawer
                    )
                },
                content = { mainContent() }
            )
        } else {
            mainContent()
        }

        // Lock: quick fade+scale in. Unlock: faster fade+scale out so Home reappears promptly.
        AnimatedVisibility(
            visible = showHomePinLock,
            enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.98f,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ),
            exit = fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)) +
                scaleOut(
                    targetScale = 1.02f,
                    animationSpec = tween(150, easing = FastOutLinearInEasing),
                ),
        ) {
            HomePinLockOverlay(
                isDarkMode = isDarkMode,
                pinHash = homeLockPinHash,
                pinLength = homeLockPinLength,
                shuffleKeypad = homeLockShuffleKeypad,
                antiBruteForce = homeLockAntiBruteForce,
                onUnlocked = { HomeLockSession.unlock() },
            )
        }
    }
}

@Composable
private fun HomeScreenMainContent(
    navController: NavController,
    backgroundColor: Color,
    isDarkMode: Boolean,
    isLandscape: Boolean,
    adaptive: com.example.ava.ui.AdaptiveSpec,
    enableMinimalLauncher: Boolean,
    layoutPrefersMinimalLauncher: Boolean,
    minimalLauncherWallpaperUri: String,
    minimalLauncherWallpaperMode: String,
    minimalLauncherWallpaperScale: Float,
    minimalLauncherWallpaperOffsetX: Float,
    minimalLauncherWallpaperOffsetY: Float,
    hideHeader: Boolean,
    hideSettingsButton: Boolean,
    headerTitle: String,
    headerSubtitle: String,
    transparentSettingsButton: Boolean,
    showBadge: Boolean,
    onDismissBadge: () -> Unit,
    prefs: android.content.SharedPreferences,
    textColor: Color,
    secondaryTextColor: Color,
    settingsButtonColor: Color,
    settingsIconColor: Color
) {
    val androidContext = LocalContext.current
    val showLauncherChrome = enableMinimalLauncher || layoutPrefersMinimalLauncher
    val wallpaperMode = when {
        minimalLauncherWallpaperMode == "image" -> "image"
        minimalLauncherWallpaperMode.isBlank() &&
            minimalLauncherWallpaperUri.isNotBlank() -> "image"
        else -> "none"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        if (
            showLauncherChrome &&
            wallpaperMode == "image" &&
            minimalLauncherWallpaperUri.isNotBlank()
        ) {
            MinimalLauncherWallpaper(
                uriString = minimalLauncherWallpaperUri,
                scale = minimalLauncherWallpaperScale,
                offsetX = minimalLauncherWallpaperOffsetX,
                offsetY = minimalLauncherWallpaperOffsetY,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val screenWidth = configuration.screenWidthDp
            val screenHeight = configuration.screenHeightDp
            val aspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
            val shortestSide = minOf(screenWidth, screenHeight).toFloat()
            val longestSide = maxOf(screenWidth, screenHeight).toFloat()
            
            val isSmallScreen = aspectRatio >= 0.7f && aspectRatio <= 1.3f
            val isPhoneLandscape = isLandscape && shortestSide < 600f
            val scaleTier = when {
                shortestSide <= 360f -> HomeScaleTier.TINY
                shortestSide >= 1440f || longestSide >= 2560f -> HomeScaleTier.XLARGE
                shortestSide >= 960f || longestSide >= 1920f -> HomeScaleTier.LARGE
                shortestSide >= 600f || longestSide >= 1280f -> HomeScaleTier.TABLET
                else -> HomeScaleTier.NORMAL
            }
            val widthScale = adaptiveScale(shortestSide, 320f, 900f, 0.86f, 1.22f)
            val heightScale = adaptiveScale(screenHeight.toFloat(), 320f, 1280f, 0.84f, 1.18f)
            val landscapePenalty = if (isLandscape) {
                when (scaleTier) {
                    HomeScaleTier.TABLET, HomeScaleTier.LARGE, HomeScaleTier.XLARGE -> 1f
                    else -> adaptiveScale(longestSide / shortestSide, 1.4f, 2.6f, 1f, 0.9f)
                }
            } else {
                1f
            }
            val tabletBoost = adaptiveScale(shortestSide, 600f, 1100f, 1f, 1.18f)
            val tierBoost = when (scaleTier) {
                HomeScaleTier.TINY -> 0.98f
                HomeScaleTier.NORMAL -> 1f
                HomeScaleTier.TABLET -> 1.14f
                HomeScaleTier.LARGE -> 1.28f
                HomeScaleTier.XLARGE -> 1.42f
            }
            val homeUiScale = (widthScale * heightScale * landscapePenalty * tabletBoost * tierBoost).coerceIn(0.82f, 1.6f)
            val phoneLandscapeHeaderBoost = if (isPhoneLandscape) 1.22f else 1f
            val phoneLandscapeSettingsBoost = if (isPhoneLandscape) 1.18f else 1f
            val headerScale = (homeUiScale * (if (adaptive.expanded) 1.12f else 1.08f) * phoneLandscapeHeaderBoost).coerceIn(0.96f, 1.82f)
            val settingsScale = (homeUiScale * 1.14f * phoneLandscapeSettingsBoost).coerceIn(1f, 1.62f)

            val horizontalPadding = when {
                isPhoneLandscape -> 26.dp
                homeUiScale < 0.9f -> 26.dp
                scaleTier == HomeScaleTier.XLARGE -> 64.dp
                scaleTier == HomeScaleTier.LARGE -> 56.dp
                homeUiScale > 1.14f -> 48.dp
                adaptive.expanded -> 40.dp
                adaptive.compact -> 28.dp
                else -> 34.dp
            }
            val verticalPadding = if (isSmallScreen) {
                if (homeUiScale < 0.9f) 14.dp else if (adaptive.compact) 16.dp else 20.dp
            } else {
                if (homeUiScale < 0.9f) 18.dp else if (scaleTier == HomeScaleTier.XLARGE) 42.dp else if (scaleTier == HomeScaleTier.LARGE) 38.dp else if (homeUiScale > 1.14f) 34.dp else if (adaptive.expanded) 28.dp else 32.dp
            }
            val settingsButtonSize = ((if (isPhoneLandscape) 46f else 44f) * settingsScale.coerceAtMost(1.1f)).dp
            val settingsIconSize = ((if (isPhoneLandscape) 20f else 19f) * settingsScale.coerceAtMost(1.12f)).dp
            val settingsButtonOffset = when {
                isPhoneLandscape -> 1.dp
                homeUiScale < 0.9f -> 1.dp
                scaleTier == HomeScaleTier.XLARGE -> 3.dp
                scaleTier == HomeScaleTier.LARGE -> 2.dp
                homeUiScale > 1.14f -> 2.dp
                adaptive.compact -> 1.dp
                else -> 3.dp
            }
            val controlOffsetY = when {
                homeUiScale < 0.88f -> (-2).dp
                scaleTier == HomeScaleTier.XLARGE -> (-54).dp
                scaleTier == HomeScaleTier.LARGE -> (-48).dp
                scaleTier == HomeScaleTier.TABLET -> (-38).dp
                adaptive.compact -> (-6).dp
                isSmallScreen -> (-10).dp
                adaptive.expanded -> (-24).dp
                else -> (-40).dp
            } - 20.dp
            
            // Launcher3 phone left/right (12dp) in both orientations. Do not inflate for
            // landscape display cutout / notch — same as settings landscape zeroing
            // horizontal WindowInsets.
            val launcherLayout = if (showLauncherChrome) {
                computeMinimalLauncherHorizontalLayout(
                    screenWidthDp = screenWidth.toFloat(),
                    gridHorizontalPaddingDp =
                        MinimalLauncherGridSpec.WORKSPACE_LEFT_RIGHT_MARGIN_DP,
                    columns = androidContext.minimalLauncherGrid().columns,
                )
            } else {
                null
            }
            // Desktop icons mode (enableMinimalLauncher) is its own shell: never compose the
            // top bar here. Unrelated to sidebar hideHomeHeader / Interaction hide_header.
            if (!showLauncherChrome) {
                val headerStartPadding = horizontalPadding
                val headerEndPadding = horizontalPadding
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = headerStartPadding,
                            end = headerEndPadding,
                            top = verticalPadding,
                            bottom = verticalPadding
                        ),
                    horizontalArrangement = if (isSmallScreen || hideHeader) {
                        Arrangement.End
                    } else {
                        Arrangement.SpaceBetween
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isSmallScreen && !hideHeader) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy((-4).dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = headerTitle,
                                    fontSize = (34f * headerScale).sp,
                                    fontWeight = FontWeight.Black,
                                    color = textColor,
                                    letterSpacing = (-1).sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                SunMoonToggle(
                                    isDarkMode = isDarkMode,
                                    onToggle = {
                                        DarkModeManager.getInstance(androidContext)
                                            .setDarkMode(!isDarkMode)
                                        if (showBadge) {
                                            onDismissBadge()
                                        }
                                    },
                                    modifier = Modifier
                                        .offset(y = 2.dp)
                                        .size((28f * headerScale.coerceIn(0.88f, 1.05f)).dp),
                                    showNewBadge = showBadge
                                )
                            }
                            Text(
                                text = headerSubtitle,
                                fontSize = (15f * headerScale).sp,
                                color = secondaryTextColor
                            )
                        }
                    }

                    if (!hideSettingsButton) {
                        val openSettings = {
                            navController.navigate(Screen.SETTINGS) { launchSingleTop = true }
                        }
                        Box(
                                modifier = Modifier
                                    .offset(x = settingsButtonOffset)
                                    .size(settingsButtonSize),
                                contentAlignment = Alignment.Center
                            ) {
                                // Invisible oversized hit pad behind the circle: flaky digitizers
                                // drop taps that land barely outside the visual bounds, so give
                                // them a ring to land in. requiredSize overflows the layout slot
                                // without moving the header; taps on the circle itself still hit
                                // the Surface on top and keep its ripple. Hidden from a11y — the
                                // Surface stays the announced button.
                                Box(
                                    modifier = Modifier
                                        .requiredSize(settingsButtonSize + SETTINGS_BUTTON_HIT_EXPANSION)
                                        .clearAndSetSemantics {}
                                        // D-pad focus belongs on the visible Surface, not this pad.
                                        .focusProperties { canFocus = false }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = openSettings
                                        )
                                )
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .settingsFocusHighlight(
                                            cornerRadius = settingsButtonSize / 2,
                                            horizontalOutset = 0.dp,
                                        ),
                                    shape = CircleShape,
                                    color = settingsButtonColor,
                                    shadowElevation = if (transparentSettingsButton || isDarkMode) 0.dp else 2.dp,
                                    onClick = openSettings
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.settings_24px),
                                            contentDescription = stringResource(R.string.label_settings),
                                            tint = settingsIconColor,
                                            modifier = Modifier.size(settingsIconSize)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            if (enableMinimalLauncher) {
                val layout = requireNotNull(launcherLayout)
                // Same Launcher3 12dp edge on all sides. Do not reuse header
                // verticalPadding — that was sized for the old top chrome and
                // stacked as a second margin on the desktop grid.
                val edge = layout.gridHorizontalPadding
                MinimalLauncherContent(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    isDarkMode = isDarkMode,
                    contentPadding = PaddingValues(
                        start = edge,
                        end = edge,
                        top = edge,
                        bottom = edge,
                    ),
                    onOpenSettings = {
                        navController.navigate(Screen.SETTINGS) { launchSingleTop = true }
                    }
                )
            } else if (layoutPrefersMinimalLauncher) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HomeCenterLoadingRing(
                        isDarkMode = isDarkMode,
                        modifier = Modifier.offset(y = controlOffsetY)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.offset(y = controlOffsetY),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        StartStopVoiceSatellite(
                            onNavigateToSettings = { navController.navigate(Screen.SETTINGS) { launchSingleTop = true } },
                            isDarkMode = isDarkMode
                        )
                    }
                }
            }
            
        }
    }
}
