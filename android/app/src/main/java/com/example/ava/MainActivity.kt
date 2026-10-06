package com.example.ava

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.services.WebViewPermissionCoordinator
import com.example.ava.services.WebViewService
import com.example.ava.ui.MainNavHost
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.Screen
import com.example.ava.ui.theme.AvaTheme
import com.example.ava.ui.screens.onboarding.OnboardingPrefs
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.settings.DisplayScale
import com.example.ava.settings.UpdateSettings
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.updateSettingsStore
import com.example.ava.update.AppUpdater
import com.example.ava.update.RemoteUpdatePhase
import com.example.ava.update.UpdateInfo
import com.example.ava.update.UpdateInstallPolicy
import com.example.ava.update.UpdatePresentation
import com.example.ava.utils.LocaleUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.TouchSoundHelper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"

        /** adb: am start -n com.example.ava/.MainActivity --ez show_update true */
        const val EXTRA_SHOW_UPDATE = "show_update"

        /** adb: am start -a com.example.ava.action.SHOW_UPDATE -n com.example.ava/.MainActivity */
        const val ACTION_SHOW_UPDATE = "com.example.ava.action.SHOW_UPDATE"
    }

    private val manualUpdatePresentationState = mutableStateOf<UpdatePresentation?>(null)
    private var pendingManualUpdate = false

    private val startupHandler = Handler(Looper.getMainLooper())
    private var deferredStartupTasksScheduled = false
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplayScale.wrap(LocaleUtils.applyLocale(newBase)))
    }
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private val webViewAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        WebViewPermissionCoordinator.onRecordAudioPermissionResult(granted)
    }
    private var shizukuPermissionPromptShown = false
    private val deviceAdminPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        ScreenControlUtils.syncScreenState(this)
    }
    
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        com.example.ava.services.ScreensaverController.onUserInteraction()
        com.example.ava.sensor.ScreenTouchSensor.onUserTouch()
        val gestureConsumed = ev?.let { event ->
            val decor = window.decorView
            val width = decor.width.coerceAtLeast(1)
            val height = decor.height.coerceAtLeast(1)
            if (com.example.ava.touchpad.TouchPadPrefs(this).cornerSummonEnabled &&
                com.example.ava.touchpad.TouchPadCornerSummon.onScreenTouch(
                    event,
                    width,
                    height,
                    resources.displayMetrics.density,
                )
            ) {
                com.example.ava.ui.screens.home.HomeSidebarActions.openTouchPad(this)
                return true
            }
            com.example.ava.sensor.ScreenGestureRecognizer.onTouchEvent(
                this,
                event,
                width,
                height,
            )
        } == true
        when (ev?.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                com.example.ava.services.VoiceSatelliteService.getInstance()?.onScreenTouch(true)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (ev.action == android.view.MotionEvent.ACTION_UP) {
                    val dx = kotlin.math.abs(ev.x - touchDownX)
                    val dy = kotlin.math.abs(ev.y - touchDownY)
                    if (dx < 40f && dy < 40f) {
                        TouchSoundHelper.playClick(this)
                    }
                }
                com.example.ava.services.VoiceSatelliteService.getInstance()?.onScreenTouch(false)
            }
        }
        if (gestureConsumed) return true
        return super.dispatchTouchEvent(ev)
    }
    
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            com.example.ava.ui.components.RemoteFocusSession.isRemoteNavKey(event.keyCode)
        ) {
            val firstRemote = com.example.ava.ui.components.RemoteFocusSession.noteRemoteNav()
            // Drop any cold-start default focus so this first key lands on
            // the first real target instead of skipping past it.
            if (firstRemote) {
                currentFocus?.clearFocus()
            }
        }
        // Floating browser penetration: while the (FLAG_NOT_FOCUSABLE) overlay is
        // visible, hardware D-pad/BACK arrive here although the user is looking at
        // the browser. Inject them into the WebView; MENU/assist keys stay local
        // (handled in onKeyDown). Both DOWN and UP must be forwarded, hence
        // dispatchKeyEvent rather than onKeyDown.
        com.example.ava.touchpad.AvaAutoKeys.note(event)
        if (WebViewService.dispatchRemoteNavKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.example.ava.mods.ModDeviceSupport.onKeyDown(this, keyCode, event)) {
            return true
        }
        // Remote controls have no edge to swipe: MENU toggles the sidebar drawer.
        // While the browser overlay is showing, its window is FLAG_NOT_FOCUSABLE and
        // keys still land here — route MENU to the browser's own edge sidebar then.
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            if (WebViewService.isBrowserOverlayVisible()) {
                WebViewService.toggleBrowserSidebar()
            } else {
                com.example.ava.ui.components.SidebarDrawerRemote.requestToggle()
            }
            return true
        }
        // Remote mic / assist / search keys: skip the wake word (manualWake).
        // Speech still comes from the panel microphone.
        if (keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST ||
            keyCode == android.view.KeyEvent.KEYCODE_ASSIST ||
            keyCode == android.view.KeyEvent.KEYCODE_SEARCH
        ) {
            if (event == null || event.repeatCount == 0) {
                VoiceSatelliteService.getInstance()?.onAssistKeyPressed()
            }
            return true
        }
        val handled = super.onKeyDown(keyCode, event)
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            com.example.ava.services.VoiceSatelliteService.notifyHardwareMusicVolumeChanged()
        }
        return handled
    }
    
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.example.ava.mods.ModDeviceSupport.onKeyUp(this, keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
    
    override fun onResume() {
        super.onResume()
        com.example.ava.ui.MainNavigationCoordinator.bindActivityResumed(true)
        com.example.ava.touchpad.AvaAutoKeys.bindActivity(this)
        com.example.ava.touchpad.AvaInAppHost.bind(this)
        com.example.ava.crash.CrashSelfHeal.activityResumed = true
        com.example.ava.crash.CrashSelfHeal.setWasForeground(this, true)
        com.example.ava.crash.CrashSelfHeal.arm(this)
        com.example.ava.crash.MainThreadStallWatchdog.onActivityResumed(this)
        com.example.ava.crash.MainThreadStallWatchdog.onActivityResumed(this)
        com.example.ava.services.ScreensaverController.onUserInteraction()
        ScreenControlUtils.syncScreenState(this)
        VoiceSatelliteService.reconcileBrowserVisibilityAfterOverlayGrant()
        com.example.ava.webcompat.GeckoEngineInstaller.pruneStaleArtifactsIfIdle(this)
        com.example.ava.update.AppUpdater.sweepUpdaterArtifacts(this)
        window.decorView.postDelayed({
            com.example.ava.webcompat.GeckoEngineRootSetup.maybeApply(this)
        }, 5000)
        window.decorView.postDelayed({
            ensureScreenControlPermission()
        }, 400)
        if (pendingManualUpdate) {
            runManualUpdateUi()
        }
    }

    override fun onPause() {
        com.example.ava.touchpad.AvaAutoKeys.unbindActivity(this)
        com.example.ava.touchpad.AvaInAppHost.unbind(this)
        com.example.ava.ui.MainNavigationCoordinator.bindActivityResumed(false)
        com.example.ava.crash.CrashSelfHeal.activityResumed = false
        com.example.ava.crash.CrashSelfHeal.setWasForeground(this, false)
        com.example.ava.crash.MainThreadStallWatchdog.onActivityPaused()
        super.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        startupHandler.removeCallbacksAndMessages(null)
        // Reconcile (not reset): if the settings UI died without a proper back-exit (task swiped
        // away, finish, etc.), restore the browser overlay + HA switch instead of dropping the
        // snapshot and leaving them stuck off. No-op when settings exited normally.
        com.example.ava.touchpad.AvaAutoKeys.unbindActivity(this)
        if (!isChangingConfigurations) {
            WebViewService.exitSettings(applicationContext)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paint window + system bars before the first frame so rotation never flashes white/gray chrome.
        AvaSystemChrome.applyEarlyWindowChrome(this)
        com.example.ava.utils.RootUtils.warmUpRootDetection()
        com.example.ava.utils.ShizukuUtils.warmUpPrivilegedShell()
        handleWebViewPermissionIntent(intent)
        scheduleManualUpdateUi(intent)

        
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)

            LaunchedEffect(isDarkMode) {
                AvaSystemChrome.applyThemedWindowChrome(this@MainActivity, isDarkMode)
            }

            AvaTheme(darkTheme = isDarkMode) {
                var showUpdateDialog by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                var hasAvailableUpdate by remember { mutableStateOf(false) }
                var updateCheckFailed by remember { mutableStateOf(false) }
                var updateInProgress by remember { mutableStateOf(false) }
                var updateProgressPercent by remember { mutableStateOf<Int?>(null) }
                var updateProgressMessage by remember { mutableStateOf("") }

                fun beginAutoUpdateInstall(settings: UpdateSettings) {
                    if (updateInProgress) return
                    updateInProgress = true
                    showUpdateDialog = false
                    updateProgressPercent = null
                    updateProgressMessage = ""
                    val appContext = context.applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppUpdater.performRemoteUpdate(
                            appContext,
                            force = false,
                            policy = UpdateInstallPolicy.fromSettings(settings),
                        )
                        withContext(Dispatchers.Main) {
                            updateInProgress = false
                        }
                    }
                }

                fun beginUpdateInstall(info: UpdateInfo, auto: Boolean) {
                    if (updateInProgress) return
                    updateInProgress = true
                    // DownloadManager already surfaces progress in the status bar —
                    // do not keep the in-app dialog pinned for the whole install.
                    showUpdateDialog = false
                    updateProgressPercent = null
                    updateProgressMessage = ""
                    val appContext = context.applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ok = if (auto) {
                            val settings = UpdateSettingsStore(appContext.updateSettingsStore).get()
                            AppUpdater.performRemoteUpdate(
                                appContext,
                                force = false,
                                policy = UpdateInstallPolicy.fromSettings(settings),
                            )
                        } else {
                            // Dialog "Update" always stays on the builtin in-app path.
                            AppUpdater.downloadAndInstallAwait(appContext, info)
                        }
                        withContext(Dispatchers.Main) {
                            updateInProgress = false
                            if (!ok) {
                                // Re-offer only when the user asked via the dialog path.
                                if (!auto) {
                                    updateInfo = info
                                    hasAvailableUpdate = true
                                    updateCheckFailed = false
                                    showUpdateDialog = true
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    scheduleDeferredStartupTasks()
                }

                var showStartServiceCountdown by remember { mutableStateOf(false) }
                var startServiceCountdownSeconds by remember {
                    mutableStateOf(com.example.ava.settings.PlayerSettings.DEFAULT_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS)
                }
                LaunchedEffect(Unit) {
                    val settings = runCatching {
                        context.applicationContext.playerSettingsStore.data.first()
                    }.getOrNull()
                    val enabled = settings?.startServiceOnAppOpen == true
                    if (enabled && !VoiceSatelliteService.isSatelliteStarted()) {
                        startServiceCountdownSeconds =
                            com.example.ava.settings.PlayerSettings.clampStartServiceOnAppOpenDelaySeconds(
                                settings?.startServiceOnAppOpenDelaySeconds
                                    ?: com.example.ava.settings.PlayerSettings.DEFAULT_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS,
                            )
                        showStartServiceCountdown = true
                    }
                }

                val manualUpdate by manualUpdatePresentationState
                LaunchedEffect(manualUpdate) {
                    val presentation = manualUpdate ?: return@LaunchedEffect
                    manualUpdatePresentationState.value = null
                    updateInfo = presentation.info
                    hasAvailableUpdate = presentation.hasUpdate
                    updateCheckFailed = presentation.checkFailed
                    updateInProgress = false
                    updateProgressPercent = null
                    updateProgressMessage = ""
                    showUpdateDialog = true
                }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2500)
                    if (updateInProgress) return@LaunchedEffect
                    val updateSettings = UpdateSettingsStore(
                        context.applicationContext.updateSettingsStore,
                    ).get()
                    if (!updateSettings.checkOnLaunch || updateSettings.ignoreUpdate) {
                        return@LaunchedEffect
                    }
                    VoiceSatelliteService.getInstance()?.refreshFirmwareUpdateEntity()
                    if (updateSettings.autoUpdate) {
                        beginAutoUpdateInstall(updateSettings)
                        return@LaunchedEffect
                    }
                    // Popup parity with the HA entity / settings page: offer the same
                    // stable candidate on launch. checkUpdate honors the "Later" skip.
                    val launchInfo = AppUpdater.checkUpdate(context.applicationContext)
                    // Never pop over an update already running elsewhere (HA entity /
                    // settings install) or a dialog the manual intent just opened —
                    // "update available" while a download runs is contradictory and
                    // its Update button would only fail with "already in progress".
                    val busyElsewhere = when (AppUpdater.liveProgress.value.phase) {
                        RemoteUpdatePhase.DOWNLOADING,
                        RemoteUpdatePhase.VERIFYING,
                        RemoteUpdatePhase.INSTALLING,
                        RemoteUpdatePhase.REBOOTING -> true
                        RemoteUpdatePhase.IDLE,
                        RemoteUpdatePhase.CHECKING,
                        RemoteUpdatePhase.DONE,
                        RemoteUpdatePhase.FAILED -> false
                    }
                    if (launchInfo != null && !updateInProgress && !showUpdateDialog && !busyElsewhere) {
                        // Mark this sha as offered the moment it is shown. Killing the
                        // app instead of tapping "Later" must not re-pop the same
                        // candidate on every launch; a new release or rebuild has a
                        // different sha/version and prompts once again. Force updates
                        // keep nagging.
                        if (!launchInfo.forceUpdate) {
                            AppUpdater.skipSoftwarePrompt(context.applicationContext, launchInfo)
                        }
                        updateInfo = launchInfo
                        hasAvailableUpdate = true
                        updateCheckFailed = false
                        showUpdateDialog = true
                    }
                }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(4000)
                    com.example.ava.webcompat.GeckoEngineInstaller.resumePendingInstall(context)
                }
                
                
                val navigateTo = intent?.getStringExtra("navigate_to")
                navigateTo?.let { route ->
                    com.example.ava.ui.MainNavigationCoordinator.requestNavigation(route)
                    intent?.removeExtra("navigate_to")
                }
                val onboardingStart = remember(navigateTo) {
                    if (navigateTo == Screen.ONBOARDING) Screen.ONBOARDING
                    else if (OnboardingPrefs.isCompleted(context)) Screen.HOME
                    else Screen.ONBOARDING
                }
                Box {
                    MainNavHost(startDestination = onboardingStart)

                    if (showStartServiceCountdown) {
                        com.example.ava.ui.components.StartServiceOnAppOpenCountdown(
                            isDarkMode = isDarkMode,
                            totalSeconds = startServiceCountdownSeconds,
                            onFinished = {
                                showStartServiceCountdown = false
                                startCoreServiceOnAppOpenNow()
                            },
                        )
                    }
                }

                if (showUpdateDialog && updateInfo != null) {
                    UpdateDialog(
                        updateInfo = updateInfo!!,
                        hasUpdate = hasAvailableUpdate,
                        checkFailed = updateCheckFailed,
                        updating = updateInProgress,
                        progressPercent = updateProgressPercent,
                        progressMessage = updateProgressMessage,
                        onDismiss = {
                            if (updateInProgress) return@UpdateDialog
                            // "Later" / dismiss while an update is offered: remember this
                            // stable candidate so auto-prompt won't nag again (entity untouched).
                            if (hasAvailableUpdate && !updateInfo!!.forceUpdate) {
                                AppUpdater.skipSoftwarePrompt(
                                    context.applicationContext,
                                    updateInfo!!,
                                )
                            }
                            showUpdateDialog = false
                        },
                        onUpdate = {
                            beginUpdateInstall(updateInfo!!, auto = false)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("navigate_to")?.let { route ->
            com.example.ava.ui.MainNavigationCoordinator.requestNavigation(route)
            intent.removeExtra("navigate_to")
        }
        handleWebViewPermissionIntent(intent)
        scheduleManualUpdateUi(intent)
    }

    private fun scheduleManualUpdateUi(intent: Intent?) {
        if (!shouldShowUpdateIntent(intent)) return
        clearShowUpdateIntent(intent)
        pendingManualUpdate = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            runManualUpdateUi()
        }
    }

    private fun runManualUpdateUi() {
        pendingManualUpdate = false
        lifecycleScope.launch {
            try {
                val presentation = AppUpdater.resolveManualUpdatePresentation(applicationContext)
                manualUpdatePresentationState.value = presentation
            } catch (e: Exception) {
                Log.e(TAG, "Manual update UI failed", e)
                manualUpdatePresentationState.value = UpdatePresentation(
                    info = UpdateInfo(
                        versionCode = 0,
                        versionName = "?",
                        downloadUrl = "",
                    ),
                    hasUpdate = false,
                    checkFailed = true,
                )
            }
        }
    }

    private fun shouldShowUpdateIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action == ACTION_SHOW_UPDATE) return true
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) return true
        return intent.getStringExtra(EXTRA_SHOW_UPDATE)?.equals("true", ignoreCase = true) == true
    }

    private fun clearShowUpdateIntent(intent: Intent?) {
        intent ?: return
        intent.removeExtra(EXTRA_SHOW_UPDATE)
        if (intent.action == ACTION_SHOW_UPDATE) {
            intent.action = null
        }
    }

    private fun handleWebViewPermissionIntent(intent: Intent?) {
        if (intent?.action != WebViewPermissionCoordinator.ACTION_REQUEST_WEBVIEW_AUDIO_PERMISSION) {
            return
        }
        intent.action = null

        if (WebViewPermissionCoordinator.hasRecordAudioPermission(this)) {
            WebViewPermissionCoordinator.onRecordAudioPermissionResult(true)
            return
        }

        webViewAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun ensureScreenControlPermission() {
        // Silent only on resume. The Device Admin dialog belongs to feature enablement
        // (ensureScreenOffPermission / PermissionManager), not every onResume.
        if (!ScreenControlUtils.isDeviceAdminActive(this)) {
            ScreenControlUtils.tryActivateDeviceAdminViaShell(this)
        }
        if (com.example.ava.utils.ShizukuUtils.isShizukuPermissionGranted()) {
            return
        }
        if (com.example.ava.utils.ShizukuUtils.isShizukuRunning() && !shizukuPermissionPromptShown) {
            shizukuPermissionPromptShown = true
            com.example.ava.utils.ShizukuUtils.requestPermission(1002)
        }
    }

    private fun scheduleDeferredStartupTasks() {
        if (deferredStartupTasksScheduled) return
        deferredStartupTasksScheduled = true
        // Open-app core-service start is driven by StartServiceOnAppOpenCountdown (Compose).
        startupHandler.postDelayed({
            AvaClarity.maybeInitialize(this)
        }, 2000)
    }

    /**
     * Best-effort start of [VoiceSatelliteService] after the open-app countdown finishes.
     * Gated upstream by PlayerSettings.startServiceOnAppOpen + countdown UI.
     * Distinct from boot auto-start (enableAutoRestart / BootReceiver).
     *
     * Important: do **not** gate on [VoiceSatelliteService.getInstance]. Soft-stop leaves the
     * Service object alive while the satellite is down. Home bind no longer AUTO_CREATEs an
     * empty shell just to paint Start/Stop — use [VoiceSatelliteService.isSatelliteStarted].
     */
    private fun startCoreServiceOnAppOpenNow() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (VoiceSatelliteService.isSatelliteStarted()) return@launch

            getSharedPreferences("ava_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_user_stopped", false)
                .apply()

            startupHandler.post {
                if (VoiceSatelliteService.isSatelliteStarted()) return@post
                try {
                    val existing = VoiceSatelliteService.getInstance()
                    if (existing != null) {
                        existing.startVoiceSatellite()
                    } else {
                        val intent = Intent(this@MainActivity, VoiceSatelliteService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "startServiceOnAppOpen failed", e)
                }
            }
        }
    }

}

@Composable
fun QrCodeDialog(
    assetName: String,
    title: String,
    subtitle: String,
    caption: String?,
    footer: String?,
    qrContentDescription: String,
    onDismiss: () -> Unit,
    onCaptionClick: (() -> Unit)? = null,
    onQrClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val qrBitmap = remember(assetName) {
        try {
            context.assets.open(assetName).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                
                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = qrContentDescription,
                        modifier = if (onQrClick != null) {
                            Modifier.size(200.dp).clickable { onQrClick() }
                        } else {
                            Modifier.size(200.dp)
                        }
                    )
                }

                caption?.let {
                    Text(
                        text = it,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (onCaptionClick != null) Color(0xFF2563EB) else Color.Black,
                        modifier = if (onCaptionClick != null) {
                            Modifier.clickable { onCaptionClick() }
                        } else {
                            Modifier
                        }
                    )
                }

                footer?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = if (onCaptionClick != null) {
                            Modifier.clickable { onCaptionClick() }
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    hasUpdate: Boolean = true,
    checkFailed: Boolean = false,
    updating: Boolean = false,
    progressPercent: Int? = null,
    progressMessage: String = "",
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val accentColor = getAccentColor()
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val labelColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)
    val subLabelColor = Color(0xFF94A3B8)
    val bgColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF8FAFC)
    val dialogBgColor = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val trackColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFE2E8F0)
    
    Dialog(
        onDismissRequest = { },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .background(dialogBgColor, RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    when {
                        updating -> R.string.update_available
                        checkFailed -> R.string.update_check_failed
                        hasUpdate -> R.string.update_available
                        else -> R.string.update_up_to_date
                    }
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            
            val currentVersion = remember {
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    packageInfo.versionName ?: "?"
                } catch (e: Exception) { "?" }
            }
            
            Text(
                text = if (hasUpdate) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = subLabelColor)) {
                            append("v$currentVersion")
                        }
                        withStyle(SpanStyle(color = subLabelColor)) {
                            append(" → ")
                        }
                        withStyle(SpanStyle(color = accentColor)) {
                            append("v${updateInfo.versionName}")
                        }
                    }
                } else {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = accentColor)) {
                            append("v$currentVersion")
                        }
                    }
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            if (updateInfo.changelog.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.update_changelog),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = subLabelColor
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                val changelogScrollState = rememberScrollState()
                val density = androidx.compose.ui.platform.LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 120.dp)
                        .background(bgColor, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .drawWithContent {
                            drawContent()
                            if (changelogScrollState.maxValue > 0) {
                                val trackHeight = size.height
                                val contentHeight = trackHeight + changelogScrollState.maxValue
                                val thumbHeight = (trackHeight / contentHeight * trackHeight).coerceAtLeast(with(density) { 16.dp.toPx() })
                                val scrollProgress = changelogScrollState.value.toFloat() / changelogScrollState.maxValue
                                val thumbOffset = scrollProgress * (trackHeight - thumbHeight)
                                drawRoundRect(
                                    color = subLabelColor,
                                    topLeft = androidx.compose.ui.geometry.Offset(size.width - with(density) { 3.dp.toPx() }, thumbOffset),
                                    size = androidx.compose.ui.geometry.Size(with(density) { 3.dp.toPx() }, thumbHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 2.dp.toPx() })
                                )
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = if (changelogScrollState.maxValue > 0) 8.dp else 0.dp)
                            .verticalScroll(changelogScrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MarkdownChangelog(
                            text = updateInfo.changelog,
                            color = labelColor
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            if (updating) {
                if (progressMessage.isNotBlank()) {
                    Text(
                        text = progressMessage,
                        fontSize = 12.sp,
                        color = subLabelColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val pct = progressPercent?.coerceIn(0, 100)
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = accentColor,
                        trackColor = trackColor,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = accentColor,
                        trackColor = trackColor,
                    )
                }
            } else if (!hasUpdate) {
                Button(
                    onClick = onDismiss,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UpdateDialogButtonText(
                        text = stringResource(R.string.label_ok),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (updateInfo.forceUpdate) {
                Button(
                    onClick = onUpdate,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UpdateDialogButtonText(
                        text = stringResource(R.string.update_now),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        UpdateDialogButtonText(
                            text = stringResource(R.string.update_later),
                            color = subLabelColor
                        )
                    }
                    
                    Button(
                        onClick = onUpdate,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        UpdateDialogButtonText(
                            text = stringResource(R.string.update_now),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** Single-line button label that shrinks instead of wrapping on narrow dialogs. */
@Composable
private fun UpdateDialogButtonText(
    text: String,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxFontSize: TextUnit = 13.sp,
    minFontSize: TextUnit = 10.sp,
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minFontSize) {
                val next = (fontSize.value - 0.5f).coerceAtLeast(minFontSize.value)
                fontSize = next.sp
            }
        }
    )
}

@Composable
fun MarkdownChangelog(text: String, color: Color) {
    val lines = text.split("\n")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = trimmed.removePrefix("### "),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = trimmed.removePrefix("## "),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "•",
                            fontSize = 13.sp,
                            color = color
                        )
                        Text(
                            text = parseBoldText(trimmed.substring(2)),
                            fontSize = 13.sp,
                            color = color,
                            lineHeight = 18.sp
                        )
                    }
                }
                trimmed.isNotEmpty() -> {
                    Text(
                        text = parseBoldText(trimmed),
                        fontSize = 13.sp,
                        color = color,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun parseBoldText(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        while (remaining.contains("**")) {
            val startIndex = remaining.indexOf("**")
            if (startIndex > 0) {
                append(remaining.substring(0, startIndex))
            }
            remaining = remaining.substring(startIndex + 2)
            val endIndex = remaining.indexOf("**")
            if (endIndex >= 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(remaining.substring(0, endIndex))
                }
                remaining = remaining.substring(endIndex + 2)
            } else {
                append("**")
                break
            }
        }
        append(remaining)
    }
}
