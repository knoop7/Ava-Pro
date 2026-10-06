package com.example.ava.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.ava.services.ScreensaverController
import com.example.ava.settings.HomeLockSession
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.HomeLockTarget
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.SettingsStyleSettings
import com.example.ava.settings.SettingsStyleSettingsStore
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.settingsStyleSettingsStore
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.HomePinLockOverlay
import com.example.ava.ui.screens.home.HomeScreen
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.SettingsScreen
import com.example.ava.ui.screens.settings.SettingsSplitDetailHold
import com.example.ava.ui.screens.settings.SettingsRouteEnter
import com.example.ava.ui.screens.settings.LocalSettingsHostDrawer
import com.example.ava.ui.screens.settings.LocalSettingsSplitActive
import com.example.ava.ui.screens.settings.SettingsSidebarDrawerWrapper
import com.example.ava.ui.screens.settings.isSettingsSplitMasterRoute
import com.example.ava.ui.screens.settings.isStandaloneDeviceControlShortcut
import com.example.ava.ui.screens.settings.rememberSettingsSplitActive
import com.example.ava.ui.screens.settings.ConnectionSettingsScreen
import com.example.ava.ui.screens.settings.VoiceSettingsDestination
import com.example.ava.ui.screens.settings.VoiceSettingsScreen
import com.example.ava.ui.screens.settings.InteractionSettingsScreen
import com.example.ava.ui.screens.settings.InteractionSettingsDestination
import com.example.ava.ui.screens.settings.SceneEditScreen
import com.example.ava.ui.screens.settings.NotificationBannerSettingsScreen
import com.example.ava.ui.screens.settings.NotificationGeneralSettingsScreen
import com.example.ava.ui.screens.settings.NotificationSceneLibraryScreen
import com.example.ava.ui.screens.settings.ExperimentalSettingsScreen
import com.example.ava.ui.screens.settings.ServiceSettingsScreen
import com.example.ava.ui.screens.settings.DeviceControlSettingsScreen
import com.example.ava.ui.screens.settings.DeviceLogsSettingsScreen
import com.example.ava.ui.screens.settings.ServiceFeatureSettingsScreen
import com.example.ava.ui.screens.settings.ServiceFeatureDestination
import com.example.ava.ui.screens.settings.SoftwareUpdatePrefsSettingsScreen
import com.example.ava.ui.screens.settings.SoftwareUpdateSettingsScreen
import com.example.ava.ui.screens.settings.MinimalLauncherAppsPickerScreen
import com.example.ava.ui.screens.settings.SidebarSettingsScreen
import com.example.ava.ui.screens.settings.TouchPadSettingsScreen
import com.example.ava.ui.screens.settings.OverlayControlSettingsScreen
import com.example.ava.ui.screens.settings.SettingsStyleSettingsScreen
import com.example.ava.ui.screens.settings.HomeLockSettingsScreen
import com.example.ava.ui.screens.settings.BrowserSettingsScreen
import com.example.ava.ui.screens.settings.BrowserSettingsDestination
import com.example.ava.ui.screens.settings.BluetoothSettingsScreen
import com.example.ava.ui.screens.settings.ScreensaverSettingsScreen
import com.example.ava.ui.screens.settings.ScreensaverSettingsDestination
import com.example.ava.ui.screens.settings.PermissionManagerScreen
import com.example.ava.ui.screens.settings.RootSettingsScreen
import com.example.ava.ui.screens.settings.DiagnosticSettingsScreen
import com.example.ava.ui.screens.settings.EnvironmentSettingsScreen
import com.example.ava.ui.screens.settings.CameraSettingsScreen
import com.example.ava.ui.screens.settings.OccupancySettingsScreen
import com.example.ava.ui.screens.settings.IntentLauncherSettingsScreen
import com.example.ava.ui.screens.settings.BackupRestoreSettingsScreen
import com.example.ava.ui.screens.settings.CloneSendScreen
import com.example.ava.ui.screens.settings.CloneReceiveScreen
import com.example.ava.ui.screens.settings.HaPipelineDetailScreen
import com.example.ava.ui.screens.settings.LocalLlmSettingsScreen
import com.example.ava.ui.screens.settings.RemoteAiPromptScreen
import com.example.ava.ui.screens.settings.RemoteAiSettingsScreen
import com.example.ava.ui.screens.settings.VoiceSttSettingsScreen
import com.example.ava.ui.screens.settings.VoiceTtsSettingsScreen
import com.example.ava.ui.screens.settings.HaSettingsScreen
import com.example.ava.ui.screens.settings.ClusterManagementSettingsScreen
import com.example.ava.ui.screens.settings.ModConfigScreen
import com.example.ava.ui.screens.settings.QuickEntityEditScreen
import com.example.ava.ui.screens.settings.SimpleClockStatusEditScreen
import com.example.ava.ui.screens.settings.DawnEntitySlotEditScreen
import com.example.ava.ui.screens.settings.ModStoreScreen
import com.example.ava.ui.screens.settings.WakeWordLibraryScreen
import com.example.ava.ui.screens.onboarding.OnboardingScreen
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType

object Screen {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val SETTINGS_CONNECTION = "settings/connection"
    const val SETTINGS_VOICE_WAKE = "settings/voice/wake"
    const val SETTINGS_VOICE_WAKE_LIBRARY = "settings/voice/wake/library"
    const val SETTINGS_VOICE_WAKE_LEARN = "settings/voice/wake/learn"
    const val SETTINGS_VOICE_MICROPHONE = "settings/voice/microphone"
    const val SETTINGS_VOICE_NOISE_SUPPRESSION = "settings/voice/microphone/noise_suppression"
    const val SETTINGS_VOICE_ECHO_CANCELLATION = "settings/voice/microphone/echo_cancellation"
    const val SETTINGS_VOICE_PRINT = "settings/voice/voice_print"
    const val SETTINGS_VOICE_STT = "settings/voice/stt"
    const val SETTINGS_VOICE_TTS = "settings/voice/tts"
    const val SETTINGS_VOICE_AUDIO_EVENT = "settings/voice/audio_event"
    const val SETTINGS_VOICE_STREAMING_TTS = "settings/voice/streaming_tts"
    const val SETTINGS_VOICE_FEEDBACK_ACCENT = "settings/voice/feedback_accent"
    const val SETTINGS_INTERACTION = "settings/interaction"
    const val SETTINGS_INTERACTION_INTERFACE = "settings/interaction/interface"
    const val SETTINGS_INTERACTION_PLAYBACK = "settings/interaction/playback"
    const val SETTINGS_INTERACTION_PLAYBACK_EQ_HA = "settings/interaction/playback/eq_ha"
    const val SETTINGS_INTERACTION_PLAYBACK_EQ_MA = "settings/interaction/playback/eq_ma"
    const val SETTINGS_INTERACTION_PLAYBACK_MASS_API = "settings/interaction/playback/mass_api"
    const val SETTINGS_INTERACTION_SCENE = "settings/interaction/scene"
    const val SETTINGS_INTERACTION_SCENE_BANNER = "settings/interaction/scene/banner"
    const val SETTINGS_INTERACTION_SCENE_LIBRARY = "settings/interaction/scene/library"
    const val SETTINGS_INTERACTION_SCENE_GENERAL = "settings/interaction/scene/general"
    const val SETTINGS_INTERACTION_SCENE_EDIT = "settings/interaction/scene/edit"
    const val SETTINGS_INTERACTION_VOICE_MESSAGE = "settings/interaction/voice_message"
    const val SETTINGS_INTERACTION_QUICK_ENTITY = "settings/interaction/quick_entity"
    const val SETTINGS_INTERACTION_SIMPLE_CLOCK = "settings/interaction/simple_clock"
    const val SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE = "settings/interaction/simple_clock/appearance"
    const val SETTINGS_INTERACTION_SIMPLE_CLOCK_STATUS = "settings/interaction/simple_clock_status"
    const val SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE = "settings/interaction/dream_clock/appearance"
    const val SETTINGS_EXPERIMENTAL = "settings/experimental"
    const val SETTINGS_SERVICE = "settings/service"
    const val SETTINGS_SERVICE_DEVICE_CONTROL = "settings/service/device_control"
    const val SETTINGS_SERVICE_DEVICE_LOGS = "settings/service/device_logs"
    const val SETTINGS_SERVICE_AUTO_RESTART = "settings/service/auto_restart"
    const val SETTINGS_SOFTWARE_UPDATE = "settings/software_update"
    const val SETTINGS_SOFTWARE_UPDATE_PREFS = "settings/software_update/prefs"
    const val SETTINGS_SERVICE_MINIMAL_LAUNCHER = "settings/service/minimal_launcher"
    const val SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS = "settings/service/minimal_launcher/apps"
    const val SETTINGS_SERVICE_TOUCH_SOUND = "settings/service/touch_sound"
    const val SETTINGS_SERVICE_SCREEN_POWER = "settings/service/screen_power"
    const val SETTINGS_SERVICE_SCREEN_BRIGHTNESS = "settings/service/screen_brightness"
    const val SETTINGS_SERVICE_SCREEN_TOUCH = "settings/service/screen_touch"
    const val SETTINGS_SERVICE_SCREEN_GESTURE_SPATIAL = "settings/service/screen_touch/spatial"
    const val SETTINGS_SERVICE_SCREEN_GESTURE_DIGITS = "settings/service/screen_touch/digits"
    const val SETTINGS_SERVICE_SCREEN_GESTURE_GEOMETRY = "settings/service/screen_touch/geometry"
    const val SETTINGS_SERVICE_FORCE_ORIENTATION = "settings/service/force_orientation"
    const val SETTINGS_SERVICE_PROXIMITY = "settings/service/proximity"
    const val SETTINGS_STYLE = "settings/style"
    const val SETTINGS_STYLE_OVERLAY = "settings/style/overlay"
    const val SETTINGS_SIDEBAR = "settings/sidebar"
    const val SETTINGS_SIDEBAR_TOUCH_PAD = "settings/sidebar/touch_pad"
    const val SETTINGS_HOME_LOCK = "settings/home_lock"
    const val SETTINGS_BROWSER = "settings/browser"
    const val SETTINGS_BROWSER_HA = "settings/browser/ha"
    const val SETTINGS_BROWSER_DISPLAY = "settings/browser/display"
    const val SETTINGS_BROWSER_SPLIT = "settings/browser/split"
    const val SETTINGS_BROWSER_TOUCH = "settings/browser/touch"
    const val SETTINGS_BROWSER_COMPAT = "settings/browser/compat"
    const val SETTINGS_BROWSER_SIDEBAR = "settings/browser/sidebar"
    const val SETTINGS_BROWSER_STEWARD = "settings/browser/steward"
    const val SETTINGS_BLUETOOTH = "settings/bluetooth"
    const val SETTINGS_SCREENSAVER = "settings/screensaver"
    const val SETTINGS_SCREENSAVER_CONTENT = "settings/screensaver/content"
    const val SETTINGS_SCREENSAVER_BEHAVIOR = "settings/screensaver/behavior"
    const val SETTINGS_ROOT = "settings/root"
    const val SETTINGS_PERMISSION_MANAGER = "settings/root/permissions"
    const val SETTINGS_DIAGNOSTIC = "settings/diagnostic"
    const val SETTINGS_ENVIRONMENT = "settings/environment"
    const val SETTINGS_CAMERA = "settings/camera"
    const val SETTINGS_OCCUPANCY = "settings/occupancy"
    const val SETTINGS_INTENT_LAUNCHER = "settings/intent_launcher"
    const val SETTINGS_CLUSTER_MANAGEMENT = "settings/cluster_management"
    const val SETTINGS_BACKUP_RESTORE = "settings/backup_restore"
    const val SETTINGS_BACKUP_CLONE_SEND = "settings/backup_clone_send"
    const val SETTINGS_BACKUP_CLONE_RECEIVE = "settings/backup_clone_receive"
    const val SETTINGS_MEDIA_PLAYER = "settings/media_player"
    const val SETTINGS_HA = "settings/ha"
    const val SETTINGS_HA_PIPELINE_DETAIL = "settings/ha/pipeline/{pipelineId}"
    const val SETTINGS_HA_LOCAL_LLM = "settings/ha/local_llm"
    const val SETTINGS_HA_LOCAL_LLM_REMOTE = "settings/ha/local_llm/remote"
    const val SETTINGS_HA_LOCAL_LLM_PROMPT = "settings/ha/local_llm/prompt"
    const val MOD_STORE = "mod_store"
    const val MOD_CONFIG = "mod_config"
}

/**
 * Deep-link style navigation for [android.content.Intent]s delivered to an already-running
 * [com.example.ava.MainActivity] (singleTop). [androidx.navigation.compose.NavHost] only reads
 * [startDestination] once — without this, `navigate_to=settings` from the browser sidebar lands on home.
 */
object MainNavigationCoordinator {
    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    @Volatile
    private var activityResumed = false

    @Volatile
    private var currentRoute: String? = null

    fun requestNavigation(route: String) {
        _pendingRoute.value = route
    }

    fun clearPending() {
        _pendingRoute.value = null
    }

    fun bindActivityResumed(resumed: Boolean) {
        activityResumed = resumed
    }

    fun isActivityResumed(): Boolean = activityResumed

    fun bindCurrentRoute(route: String?) {
        currentRoute = route
    }

    fun currentRoute(): String? = currentRoute

    /** Pattern route plus live arguments, e.g. settings/ha/pipeline/abc. */
    fun filledRoute(entry: NavBackStackEntry?): String? {
        val pattern = entry?.destination?.route ?: return null
        val args = entry.arguments ?: return pattern
        var filled = pattern
        val keys = args.keySet().sortedByDescending { it.length }
        for (key in keys) {
            if (key.startsWith("android") || key.startsWith("androidx")) continue
            val value = args.get(key)?.toString() ?: continue
            filled = filled.replace("{$key}", value)
        }
        return filled
    }

    /** Ava's own home dashboard is in front — two-finger Back must not leave it. */
    fun isProgramHome(): Boolean =
        activityResumed && (currentRoute.isNullOrBlank() || currentRoute == Screen.HOME)

    /** Settings / mod / entity-edit — not the idle home dashboard. */
    fun isSettingsLikeRoute(route: String?): Boolean {
        if (route.isNullOrBlank() || route == Screen.HOME) return false
        return route == Screen.SETTINGS ||
            route.startsWith("settings/") ||
            route == Screen.MOD_STORE ||
            route.startsWith("${Screen.MOD_CONFIG}/") ||
            route.startsWith("quick_entity_edit/") ||
            route.startsWith("simple_clock_status_edit/") ||
            route.startsWith("dawn_entity_slot_edit/") ||
            route.startsWith("${Screen.SETTINGS_INTERACTION_SCENE_EDIT}/")
    }

    /** Filled NavHost routes the pad can reopen. Patterns and onboarding stay out. */
    fun isRestorableRoute(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        if (route.contains('{') || route.contains('}')) return false
        if (route == Screen.ONBOARDING) return false
        return true
    }
}

/**
 * Every settings-like destination enters through [SettingsRouteEnter] exactly once.
 * Home stays on the plain Crossfade path.
 */
private fun NavGraphBuilder.settingsDestination(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        SettingsRouteEnter {
            content(entry)
        }
    }
}

fun androidx.navigation.NavController.safePopBackStack() {
    if (!popBackStack()) {
        navigate(Screen.HOME) {
            popUpTo(Screen.HOME) { inclusive = true }
            launchSingleTop = true
        }
    }
}

@Composable
fun MainNavHost(startDestination: String = Screen.HOME) {
    val navController = rememberNavController()
    val pendingRoute by MainNavigationCoordinator.pendingRoute.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val homeLockStore = remember { HomeLockSettingsStore(context.homeLockSettingsStore) }
    val homeLockSettings by homeLockStore.getFlow().collectAsStateWithLifecycle(null)
    val homeLockEnabled by HomeLockSession.featureEnabled.collectAsStateWithLifecycle()
    val homeLockTarget by HomeLockSession.lockTarget.collectAsStateWithLifecycle()
    val homeUnlocked by HomeLockSession.unlocked.collectAsStateWithLifecycle()
    val homeLockPinHash by HomeLockSession.pinHash.collectAsStateWithLifecycle()
    val homeLockPinLength by HomeLockSession.pinLength.collectAsStateWithLifecycle()
    val homeLockShuffleKeypad by HomeLockSession.shuffleKeypad.collectAsStateWithLifecycle()
    val homeLockAntiBruteForce by HomeLockSession.antiBruteForce.collectAsStateWithLifecycle()
    val settingsLike = MainNavigationCoordinator.isSettingsLikeRoute(currentRoute)
    val locksSettings = homeLockEnabled && homeLockTarget == HomeLockTarget.SETTINGS
    val showSettingsPinLock = locksSettings && !homeUnlocked && settingsLike
    val styleStore = remember { SettingsStyleSettingsStore(context.settingsStyleSettingsStore) }
    val landscapeSplit by SettingsStyleSession.landscapeSplit.collectAsStateWithLifecycle()
    val splitActive = rememberSettingsSplitActive(landscapeSplit)
    val previousRoute = navController.previousBackStackEntry?.destination?.route
    val showMaster = splitActive &&
        isSettingsSplitMasterRoute(currentRoute) &&
        !isStandaloneDeviceControlShortcut(currentRoute, previousRoute)
    // 40% of width. Floor 320 only when the screen can still leave a usable
    // detail pane; narrower landscape (phones / high-density 8") stays
    // proportional. Cap binds past 1300dp, where honest hardware runs out
    // and misreported-density panels take over (those self-heal via DisplayScale).
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val proportionalMaster = (screenWidthDp * 0.40f).toInt()
    val masterFloor = minOf(320, (screenWidthDp * 0.38f).toInt().coerceAtLeast(1))
    val masterWidthDp = proportionalMaster.coerceIn(masterFloor, 520)

    LaunchedEffect(homeLockSettings) {
        val settings = homeLockSettings ?: return@LaunchedEffect
        HomeLockSession.syncFromSettings(settings)
    }
    LaunchedEffect(styleStore) {
        styleStore.getFlow().collect { settings ->
            SettingsStyleSession.syncFromSettings(settings)
            SettingsStyleSession.retainOverlaySplitFromDisk()
        }
    }

    val filledRoute = MainNavigationCoordinator.filledRoute(navBackStackEntry)
    LaunchedEffect(filledRoute, settingsLike) {
        MainNavigationCoordinator.bindCurrentRoute(filledRoute)
        ScreensaverController.setSettingsUiForeground(settingsLike)
    }
    DisposableEffect(Unit) {
        MainNavigationCoordinator.bindCurrentRoute(filledRoute)
        onDispose { MainNavigationCoordinator.bindCurrentRoute(null) }
    }
    // Settings-scope: leaving the settings tree re-locks for the next entry.
    LaunchedEffect(currentRoute, locksSettings) {
        if (locksSettings && !MainNavigationCoordinator.isSettingsLikeRoute(currentRoute)) {
            HomeLockSession.lock()
        }
    }
    DisposableEffect(Unit) {
        onDispose { ScreensaverController.setSettingsUiForeground(false) }
    }

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        MainNavigationCoordinator.clearPending()
        if (!MainNavigationCoordinator.isRestorableRoute(route)) return@LaunchedEffect
        if (MainNavigationCoordinator.filledRoute(navController.currentBackStackEntry) == route ||
            navController.currentDestination?.route == route
        ) {
            return@LaunchedEffect
        }
        runCatching {
            if (route == Screen.HOME) {
                val atHome = MainNavigationCoordinator.filledRoute(
                    navController.currentBackStackEntry,
                ) == Screen.HOME ||
                    navController.currentDestination?.route == Screen.HOME
                if (atHome) return@runCatching
                if (!navController.popBackStack(Screen.HOME, false)) {
                    navController.navigate(Screen.HOME) {
                        popUpTo(Screen.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            } else {
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsSidebarDrawerWrapper(
            navController = navController,
            enabled = settingsLike,
            applyPageTheme = false,
        ) {
            CompositionLocalProvider(
                LocalSettingsSplitActive provides showMaster,
                LocalSettingsHostDrawer provides true,
            ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(if (showMaster) masterWidthDp.dp else 0.dp)
                        .clipToBounds(),
                ) {
                    if (showMaster) {
                        SettingsScreen(
                            navController = navController,
                            modifier = Modifier.fillMaxSize(),
                            masterPane = true,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clipToBounds(),
                ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                ) {
        composable(route = Screen.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Screen.HOME) {
                        popUpTo(Screen.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(route = Screen.HOME) {
            // Safety net: reaching home via system back (bypassing the settings header back
            // button) must still reconcile the browser overlay + HA switch. No-op otherwise.
            val homeContext = LocalContext.current
            LaunchedEffect(Unit) {
                com.example.ava.services.WebViewService.exitSettings(homeContext.applicationContext)
            }
            HomeScreen(navController)
        }
        settingsDestination(Screen.SETTINGS) {
            if (LocalSettingsSplitActive.current) {
                SettingsSplitDetailHold()
            } else {
                SettingsScreen(navController)
            }
        }
        settingsDestination(Screen.SETTINGS_CONNECTION) {
            ConnectionSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_VOICE_WAKE) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.Wake)
        }
        settingsDestination(Screen.SETTINGS_VOICE_WAKE_LIBRARY) {
            WakeWordLibraryScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_VOICE_WAKE_LEARN) {
            com.example.ava.ui.screens.settings.WakeLearnSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_VOICE_MICROPHONE) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.Microphone)
        }
        settingsDestination(Screen.SETTINGS_VOICE_NOISE_SUPPRESSION) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.NoiseSuppression)
        }
        settingsDestination(Screen.SETTINGS_VOICE_ECHO_CANCELLATION) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.EchoCancellation)
        }
        settingsDestination(Screen.SETTINGS_VOICE_PRINT) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.VoicePrint)
        }
        settingsDestination(Screen.SETTINGS_VOICE_STT) {
            VoiceSttSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_VOICE_TTS) {
            VoiceTtsSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_VOICE_AUDIO_EVENT) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.AudioEvent)
        }
        settingsDestination(Screen.SETTINGS_VOICE_STREAMING_TTS) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.StreamingTts)
        }
        settingsDestination(Screen.SETTINGS_VOICE_FEEDBACK_ACCENT) {
            VoiceSettingsScreen(navController, destination = VoiceSettingsDestination.FeedbackAccent)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION) {
            InteractionSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_INTERFACE) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.Interface)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_PLAYBACK) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.Playback)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_HA) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.PlaybackEqHa)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_MA) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.PlaybackEqMa)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_PLAYBACK_MASS_API) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.PlaybackMassApi)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SCENE) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.Scene)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SCENE_BANNER) {
            NotificationBannerSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SCENE_LIBRARY) {
            NotificationSceneLibraryScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SCENE_GENERAL) {
            NotificationGeneralSettingsScreen(navController)
        }
        settingsDestination(
            route = "${Screen.SETTINGS_INTERACTION_SCENE_EDIT}/{sceneId}",
            arguments = listOf(
                navArgument("sceneId") { type = NavType.StringType }
            ),
        ) { entry ->
            val sceneId = entry.arguments?.getString("sceneId") ?: "new"
            SceneEditScreen(navController = navController, sceneIdArg = sceneId)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_VOICE_MESSAGE) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.VoiceMessage)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_QUICK_ENTITY) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.QuickEntity)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK) {
            // Legacy path: Simple Clock controls live on Scene; this opens Appearance & Status.
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.SimpleClockAppearance)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.SimpleClockAppearance)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_STATUS) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.SimpleClockStatus)
        }
        settingsDestination(Screen.SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE) {
            InteractionSettingsScreen(navController, startDestination = InteractionSettingsDestination.DreamClockAppearance)
        }
        settingsDestination(Screen.SETTINGS_EXPERIMENTAL) {
            ExperimentalSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SERVICE) {
            ServiceSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_DEVICE_CONTROL) {
            DeviceControlSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_DEVICE_LOGS) {
            DeviceLogsSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SOFTWARE_UPDATE) {
            SoftwareUpdateSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SOFTWARE_UPDATE_PREFS) {
            SoftwareUpdatePrefsSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_AUTO_RESTART) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.AutoRestart)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.MinimalLauncher)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS) {
            MinimalLauncherAppsPickerScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_TOUCH_SOUND) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.TouchSound)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_SCREEN_POWER) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ScreenPower)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ScreenBrightness)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_SCREEN_TOUCH) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ScreenTouch)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_SCREEN_GESTURE_SPATIAL) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ScreenGestureSpatial)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_SCREEN_GESTURE_DIGITS) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ScreenGestureDigits)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_SCREEN_GESTURE_GEOMETRY) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ScreenGestureGeometry)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_FORCE_ORIENTATION) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.ForceOrientation)
        }
        settingsDestination(Screen.SETTINGS_SERVICE_PROXIMITY) {
            ServiceFeatureSettingsScreen(navController, ServiceFeatureDestination.Proximity)
        }
        settingsDestination(Screen.SETTINGS_STYLE) {
            SettingsStyleSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_STYLE_OVERLAY) {
            OverlayControlSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SIDEBAR) {
            SidebarSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SIDEBAR_TOUCH_PAD) {
            TouchPadSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_HOME_LOCK) {
            HomeLockSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_BROWSER) {
            BrowserSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_HA) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Ha)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_DISPLAY) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Display)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_SPLIT) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Split)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_TOUCH) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Touch)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_COMPAT) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Compat)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_SIDEBAR) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Sidebar)
        }
        settingsDestination(Screen.SETTINGS_BROWSER_STEWARD) {
            BrowserSettingsScreen(navController, startDestination = BrowserSettingsDestination.Steward)
        }
        settingsDestination(Screen.SETTINGS_BLUETOOTH) {
            BluetoothSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SCREENSAVER) {
            ScreensaverSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_SCREENSAVER_CONTENT) {
            ScreensaverSettingsScreen(
                navController,
                startDestination = ScreensaverSettingsDestination.Content,
            )
        }
        settingsDestination(Screen.SETTINGS_SCREENSAVER_BEHAVIOR) {
            ScreensaverSettingsScreen(
                navController,
                startDestination = ScreensaverSettingsDestination.Behavior,
            )
        }
        settingsDestination(Screen.SETTINGS_ROOT) {
            RootSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_PERMISSION_MANAGER) {
            PermissionManagerScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_DIAGNOSTIC) {
            DiagnosticSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_ENVIRONMENT) {
            EnvironmentSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_CAMERA) {
            CameraSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_OCCUPANCY) {
            OccupancySettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_INTENT_LAUNCHER) {
            IntentLauncherSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_CLUSTER_MANAGEMENT) {
            ClusterManagementSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_BACKUP_RESTORE) {
            BackupRestoreSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_BACKUP_CLONE_SEND) {
            CloneSendScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_BACKUP_CLONE_RECEIVE) {
            CloneReceiveScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_HA) {
            HaSettingsScreen(navController)
        }
        settingsDestination(Screen.SETTINGS_HA_PIPELINE_DETAIL) { backStackEntry ->
            val pipelineId = backStackEntry.arguments?.getString("pipelineId") ?: ""
            HaPipelineDetailScreen(navController = navController, pipelineId = pipelineId)
        }
        settingsDestination(Screen.SETTINGS_HA_LOCAL_LLM) {
            LocalLlmSettingsScreen(navController = navController)
        }
        settingsDestination(Screen.SETTINGS_HA_LOCAL_LLM_REMOTE) {
            RemoteAiSettingsScreen(navController = navController)
        }
        settingsDestination(Screen.SETTINGS_HA_LOCAL_LLM_PROMPT) {
            RemoteAiPromptScreen(navController = navController)
        }
        settingsDestination(Screen.SETTINGS_MEDIA_PLAYER) {
            InteractionSettingsScreen(
                navController = navController,
                startDestination = InteractionSettingsDestination.Playback,
            )
        }
        settingsDestination(Screen.MOD_STORE) {
            ModStoreScreen(navController)
        }
        settingsDestination(
            route = "${Screen.MOD_CONFIG}/{modId}",
            arguments = listOf(navArgument("modId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val modId = backStackEntry.arguments?.getString("modId").orEmpty()
            ModConfigScreen(navController = navController, modId = modId)
        }
        settingsDestination(
            route = "quick_entity_edit/{slotIndex}",
            arguments = listOf(navArgument("slotIndex") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slotIndex = backStackEntry.arguments?.getString("slotIndex")?.toIntOrNull() ?: 0
            QuickEntityEditScreen(
                slotIndex = slotIndex,
                onBack = { navController.safePopBackStack() }
            )
        }
        settingsDestination(
            route = "simple_clock_status_edit/{slotIndex}",
            arguments = listOf(navArgument("slotIndex") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slotIndex = backStackEntry.arguments?.getString("slotIndex")?.toIntOrNull() ?: 0
            SimpleClockStatusEditScreen(
                slotIndex = slotIndex,
                onBack = { navController.safePopBackStack() }
            )
        }
        settingsDestination(
            route = "dawn_entity_slot_edit/{slotIndex}",
            arguments = listOf(navArgument("slotIndex") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slotIndex = backStackEntry.arguments?.getString("slotIndex")?.toIntOrNull() ?: 0
            DawnEntitySlotEditScreen(
                slotIndex = slotIndex,
                onBack = { navController.safePopBackStack() }
            )
        }
                }
                    if (showMaster && currentRoute == Screen.SETTINGS) {
                        ConnectionSettingsScreen(navController)
                    }
                }
            }
            }
        }

        AnimatedVisibility(
            visible = showSettingsPinLock,
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
                opaqueBackground = true,
                onUnlocked = { HomeLockSession.unlock() },
            )
        }
    }
}
