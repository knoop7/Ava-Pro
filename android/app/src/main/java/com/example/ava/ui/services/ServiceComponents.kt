package com.example.ava.ui.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import com.example.ava.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.esphome.*
import com.example.ava.permissions.getVoiceSatellitePermissions
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.components.expandTouchTarget
import com.example.ava.ui.screens.home.HomeCenterLoadingRing
import com.example.ava.ui.rememberAdaptiveSpec
import com.example.ava.utils.translate
import com.example.ava.ui.theme.SlateText
import com.example.ava.ui.theme.SlateSecondary
import com.example.ava.ui.theme.SlateSecondaryDark
import com.example.ava.ui.theme.SlateTertiary
import com.example.ava.ui.theme.SlateBorder
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import com.example.ava.ui.theme.AccentGreen
import com.example.ava.ui.theme.AccentRed
import com.example.ava.webcompat.EngineCapabilities
import com.example.ava.webcompat.GeckoSatelliteStatusHolder
import kotlinx.coroutines.delay

private const val BIND_TO_SERVICE_TAG = "BindToService"
private const val PERMISSION_REQUEST_TAG = "PermissionRequest"

/** Cosmetic cold-start spin before the stopped control appears. */
private const val COLD_START_RING_HINT_MS = 900L

/**
 * Start the core voice service without Home bind auto-creating an empty shell.
 * Prefer an existing instance; otherwise cold-start via foreground service so
 * [VoiceSatelliteService.onStartCommand] runs the full satellite path.
 */
fun startCoreServiceBestEffort(context: Context) {
    val app = context.applicationContext
    app.getSharedPreferences("ava_prefs", Context.MODE_PRIVATE).edit()
        .putBoolean("service_user_stopped", false)
        .apply()
    val existing = VoiceSatelliteService.getInstance()
    if (existing != null) {
        existing.startVoiceSatellite()
        return
    }
    val intent = Intent(app, VoiceSatelliteService::class.java)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    } catch (e: Exception) {
        Log.e(BIND_TO_SERVICE_TAG, "startCoreServiceBestEffort failed", e)
    }
}

private enum class ControlScaleTier {
    TINY,
    NORMAL,
    TABLET,
    LARGE,
    XLARGE
}

private fun homeSecondaryTextColor(isDarkMode: Boolean): Color =
    if (isDarkMode) SlateSecondaryDark else SlateSecondary

/**
 * Shared metrics for the home center power circle and the satellite-state label under it.
 * Status text tracks [controlScale] / button diameter so large screens stay visually balanced.
 */
private data class HomeMainControlMetrics(
    val controlScale: Float,
    val buttonSize: androidx.compose.ui.unit.Dp,
    val iconSize: androidx.compose.ui.unit.Dp,
    val innerLabelSp: androidx.compose.ui.unit.TextUnit,
    val statusSp: androidx.compose.ui.unit.TextUnit,
    val statusGap: androidx.compose.ui.unit.Dp,
)

@Composable
private fun rememberHomeMainControlMetrics(): HomeMainControlMetrics {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val adaptive = rememberAdaptiveSpec()
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val shortestSide = minOf(screenWidth, screenHeight)
    val longestSide = maxOf(screenWidth, screenHeight)
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isTinySquare = shortestSide <= 340f && kotlin.math.abs(screenWidth - screenHeight) <= 40f
    val scaleTier = when {
        shortestSide <= 360f -> ControlScaleTier.TINY
        shortestSide >= 1440f || longestSide >= 2560f -> ControlScaleTier.XLARGE
        shortestSide >= 960f || longestSide >= 1920f -> ControlScaleTier.LARGE
        shortestSide >= 600f || longestSide >= 1280f -> ControlScaleTier.TABLET
        else -> ControlScaleTier.NORMAL
    }
    val widthScale = ((shortestSide - 320f) / (900f - 320f)).coerceIn(0f, 1f)
    val heightScale = ((screenHeight - 320f) / (1280f - 320f)).coerceIn(0f, 1f)
    val smallScreenScale = ((600f - shortestSide) / (600f - 320f)).coerceIn(0f, 1f)
    val tabletScale = ((shortestSide - 600f) / (1100f - 600f)).coerceIn(0f, 1f)
    val widthFactor = 0.86f + 0.28f * widthScale
    val heightFactor = 0.84f + 0.28f * heightScale
    val smallScreenBoost = 1f + 0.13f * smallScreenScale
    val tabletBoost = 1f + 0.34f * tabletScale
    val squareBoost = if (isTinySquare) 1.14f else 1f
    val tierBoost = when (scaleTier) {
        ControlScaleTier.TINY -> 1.02f
        ControlScaleTier.NORMAL -> 1f
        ControlScaleTier.TABLET -> 1.42f
        ControlScaleTier.LARGE -> 1.5f
        ControlScaleTier.XLARGE -> 1.7f
    }
    val labelBoost = when {
        scaleTier == ControlScaleTier.XLARGE -> 1.6f
        scaleTier == ControlScaleTier.LARGE -> 1.4f
        scaleTier == ControlScaleTier.TABLET -> 1.25f
        isTinySquare -> 1.08f
        shortestSide <= 360f -> 1.04f
        else -> 1f
    }
    val landscapePenalty = if (isLandscape) {
        when (scaleTier) {
            ControlScaleTier.TABLET, ControlScaleTier.LARGE, ControlScaleTier.XLARGE -> 1f
            else -> {
                val ratio = (longestSide / shortestSide).coerceIn(1f, 3f)
                (1f - ((ratio - 1.4f) / (2.6f - 1.4f)).coerceIn(0f, 1f) * 0.12f)
            }
        }
    } else {
        1f
    }
    val controlScale = (adaptive.controlScale * widthFactor * heightFactor * landscapePenalty *
        tabletBoost * squareBoost * tierBoost * smallScreenBoost)
        .coerceIn(0.84f, 2.2f)
    val buttonDp = 186f * controlScale
    // Phones: keep legacy 15sp (14.4 on tiny). Tablets+: track circle diameter (~9.5%).
    val statusSpValue = when (scaleTier) {
        ControlScaleTier.TINY -> 14.4f
        ControlScaleTier.NORMAL -> 15f
        else -> (buttonDp * 0.095f).coerceIn(16f, 38f)
    }
    val statusGap = when (scaleTier) {
        ControlScaleTier.TINY, ControlScaleTier.NORMAL -> 24.dp
        else -> (24f * controlScale.coerceIn(0.9f, 1.55f)).dp
    }
    return HomeMainControlMetrics(
        controlScale = controlScale,
        buttonSize = buttonDp.dp,
        iconSize = (54f * controlScale).dp,
        innerLabelSp = (12f * controlScale.coerceIn(0.9f, 1.8f) * labelBoost).sp,
        statusSp = statusSpValue.sp,
        statusGap = statusGap,
    )
}

@Composable
fun VoiceSatelliteCompactStatus(
    isDarkMode: Boolean = false,
    interactive: Boolean = true
) {
    VoiceSatelliteServiceControl(
        isDarkMode = isDarkMode,
        showMainButton = false,
        interactive = interactive
    )
}

/** Same start/stop path as the home power button, for a corner control that only needs the click. */
@Composable
fun rememberToggleVoiceSatellite(): () -> Unit {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    var service by remember { mutableStateOf<VoiceSatelliteService?>(null) }
    BindToService(
        onConnected = { service = it },
        onDisconnected = { service = null },
    )
    val voiceChannelStore = remember { VoiceChannelSettingsStore(appContext.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsStateWithLifecycle(true)
    val currentService = service
    val serviceStateFlow = remember(currentService) {
        currentService?.voiceSatelliteState ?: kotlinx.coroutines.flow.flowOf(Stopped)
    }
    val serviceState by serviceStateFlow.collectAsStateWithLifecycle(Stopped)
    val isStarted = currentService != null && serviceState !is Stopped
    val registerStart = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = { startCoreServiceBestEffort(appContext) },
        onPermissionDenied = { },
    )
    val registerToggle = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = { service?.startVoiceSatellite() },
        onPermissionDenied = { },
    )
    val requiredPermissions = remember(voiceChannelEnabled) {
        getVoiceSatellitePermissions(voiceChannelEnabled)
    }
    val toggle by rememberUpdatedState(newValue = {
        val bound = service
        when {
            bound == null -> registerStart(requiredPermissions)
            isStarted -> bound.stopVoiceSatellite()
            else -> registerToggle(requiredPermissions)
        }
    })
    return { toggle() }
}

@Composable
fun StartStopVoiceSatellite(
    onNavigateToSettings: () -> Unit = {},
    isDarkMode: Boolean = false
) {
    VoiceSatelliteServiceControl(
        isDarkMode = isDarkMode,
        showMainButton = true
    )
}

@Composable
private fun VoiceSatelliteServiceControl(
    isDarkMode: Boolean,
    showMainButton: Boolean,
    interactive: Boolean = true
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val shortestSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val longestSide = maxOf(configuration.screenWidthDp, configuration.screenHeightDp)
    // Always remember (Compose rules); only the main-button path consumes these metrics.
    val mainMetrics = rememberHomeMainControlMetrics()
    // Sidebar compact chip — mild scale only (not the center power circle).
    val compactStatusScale = when {
        shortestSide >= 1440 || longestSide >= 2560 -> 1.1f
        shortestSide >= 960 || longestSide >= 1920 -> 1f
        shortestSide <= 360 -> 0.92f
        else -> 0.96f
    }
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    if (EngineCapabilities.GECKO_BUNDLED) {
        val geckoStarted by GeckoSatelliteStatusHolder.started.collectAsStateWithLifecycle(false)
        val geckoStatusText by GeckoSatelliteStatusHolder.statusText.collectAsStateWithLifecycle("")
        val statusColor = homeSecondaryTextColor(isDarkMode)
        if (!showMainButton && !interactive) {
            if (geckoStatusText.isNotEmpty()) {
                AnimatedServiceStatusText(
                    text = geckoStatusText,
                    color = statusColor,
                    fontSize = (13f * compactStatusScale).sp,
                )
            } else if (!geckoStarted) {
                HomeCenterLoadingRing(
                    isDarkMode = isDarkMode,
                    ringSize = 14.dp,
                    strokeWidth = 1.5.dp,
                )
            }
            return
        }
        if (showMainButton) {
            if (!geckoStarted) {
                HomeCenterLoadingRing(
                    isDarkMode = isDarkMode,
                )
            } else {
                AnimatedServiceStatusText(
                    text = geckoStatusText,
                    color = statusColor,
                    fontSize = mainMetrics.statusSp,
                )
            }
            return
        }
    }
    var service by remember { mutableStateOf<VoiceSatelliteService?>(null) }
    var awaitingService by remember { mutableStateOf(false) }
    // Cosmetic only: hold the ring briefly so cold start reads as "loading" instead of
    // snapping straight to a stopped button. Nothing is bound or created while it spins.
    var ringHintElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(COLD_START_RING_HINT_MS)
        ringHintElapsed = true
    }
    val voiceChannelStore = remember { VoiceChannelSettingsStore(appContext.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsStateWithLifecycle(true)
    // Do not AUTO_CREATE on Home paint — that empties a shell and floods deferred work.
    BindToService(
        onConnected = {
            service = it
            awaitingService = false
        },
        onDisconnected = { service = null }
    )

    val currentService = service
    val resources = LocalContext.current.resources
    val statusColor = homeSecondaryTextColor(isDarkMode)
    val serviceStateFlow = remember(currentService) {
        currentService?.voiceSatelliteState ?: kotlinx.coroutines.flow.flowOf(Stopped)
    }
    val serviceState by serviceStateFlow.collectAsStateWithLifecycle(Stopped)
    val isStarted = currentService != null && serviceState !is Stopped
    val statusText = remember(currentService, serviceState, voiceChannelEnabled, resources) {
        if (currentService == null) {
            // Service not bound yet — user sees "Stopped", not "Disconnected".
            // "Disconnected" implies HA link loss; here the satellite simply hasn't started.
            resources.getString(R.string.status_stopped)
        } else if (voiceChannelEnabled) {
            serviceState.translate(resources)
        } else {
            when (serviceState) {
                is Connected -> resources.getString(R.string.status_connected)
                else -> resources.getString(R.string.status_stopped)
            }
        }
    }
    // Always remember these (Compose slot rules). Overlay windows have no Activity, so
    // the helper itself must be host-safe — do not skip the call on the loading /
    // display-only paths or a later bind/interactive flip skips a remember.
    val registerStartPermissions = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = {
            awaitingService = true
            startCoreServiceBestEffort(appContext)
        },
        onPermissionDenied = { awaitingService = false }
    )
    val registerTogglePermissions = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = { service?.startVoiceSatellite() },
        onPermissionDenied = { }
    )
    val requiredPermissions = remember(voiceChannelEnabled) {
        getVoiceSatellitePermissions(voiceChannelEnabled)
    }

    if (currentService == null && (awaitingService || !ringHintElapsed)) {
        if (showMainButton) {
            HomeCenterLoadingRing(isDarkMode = isDarkMode)
        } else {
            HomeCenterLoadingRing(
                isDarkMode = isDarkMode,
                ringSize = 14.dp,
                strokeWidth = 1.5.dp,
            )
        }
        return
    }

    if (!showMainButton && !interactive) {
        AnimatedServiceStatusText(
            text = statusText,
            color = statusColor,
            fontSize = (13f * compactStatusScale).sp,
        )
        return
    }
    val requestStart = {
        registerStartPermissions(requiredPermissions)
    }
    val toggleService = {
        val bound = service
        if (bound == null) {
            requestStart()
        } else if (isStarted) {
            bound.stopVoiceSatellite()
        } else {
            registerTogglePermissions(requiredPermissions)
        }
    }

    if (showMainButton) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(mainMetrics.statusGap)
        ) {
            MainControlButton(
                isStarted = isStarted,
                onStart = {
                    if (currentService != null) {
                        currentService.startVoiceSatellite()
                    } else {
                        // MainControlButton already gated permissions.
                        awaitingService = true
                        startCoreServiceBestEffort(appContext)
                    }
                },
                onStop = { currentService?.stopVoiceSatellite() },
                isDarkMode = isDarkMode,
                metrics = mainMetrics,
            )
            AnimatedServiceStatusText(
                text = statusText,
                color = statusColor,
                fontSize = mainMetrics.statusSp,
            )
        }
    } else {
        AnimatedServiceStatusText(
            text = statusText,
            color = statusColor,
            fontSize = (13f * compactStatusScale).sp,
            modifier = Modifier
                .expandTouchTarget(horizontal = 10.dp, vertical = 6.dp)
                .clickable(onClick = toggleService)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}


@Composable
private fun MainControlButton(
    isStarted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    isDarkMode: Boolean = false,
    metrics: HomeMainControlMetrics = rememberHomeMainControlMetrics(),
) {
    val registerPermissionsResult = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = onStart,
        onPermissionDenied = { }
    )
    val context = LocalContext.current
    val voiceChannelStore = remember { VoiceChannelSettingsStore(context.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsState(initial = true)
    val requiredPermissions = remember(voiceChannelEnabled) {
        getVoiceSatellitePermissions(voiceChannelEnabled)
    }
    val buttonSize = metrics.buttonSize
    val iconSize = metrics.iconSize
    val labelFontSize = metrics.innerLabelSp

    val buttonColor by animateColorAsState(
        targetValue = when {
            isDarkMode && isStarted -> AccentBrown.copy(alpha = 0.23f)
            isDarkMode -> Color.Transparent
            isStarted -> AccentBlue
            else -> Color.White
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )

    val borderColor = when {
        isDarkMode && isStarted -> AccentBrown.copy(alpha = 0.35f)
        isStarted -> Color.Transparent
        isDarkMode -> Color(0xFF333333).copy(alpha = 0.5f)
        else -> SlateBorder
    }

    val iconTint = when {
        isStarted -> Color.White
        isDarkMode -> Color(0xFF6B7280)
        else -> SlateTertiary
    }

    val textColor = when {
        isStarted -> Color.White.copy(alpha = 0.8f)
        isDarkMode -> Color(0xFF6B7280)
        else -> SlateTertiary
    }

    Box(
        modifier = Modifier
            .size(buttonSize)
            .then(
                if (!isDarkMode) {
                    Modifier.shadow(
                        elevation = if (isStarted) 20.dp else 4.dp,
                        shape = CircleShape,
                        ambientColor = if (isStarted) {
                            AccentBlue.copy(alpha = 0.3f)
                        } else {
                            Color.Black.copy(alpha = 0.1f)
                        },
                        spotColor = if (isStarted) {
                            AccentBlue.copy(alpha = 0.3f)
                        } else {
                            Color.Black.copy(alpha = 0.1f)
                        }
                    )
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .background(buttonColor)
            .border(2.dp, borderColor, CircleShape)
            .clickable {
                if (isStarted) {
                    onStop()
                } else {
                    registerPermissionsResult(requiredPermissions)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            
            Icon(
                painter = painterResource(R.drawable.power_24px),
                contentDescription = if (isStarted) "Stop" else "Start",
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
            
            
            Text(
                text = if (isStarted) "STOP SERVICE" else "START SERVICE",
                color = textColor,
                fontSize = labelFontSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun AnimatedServiceStatusText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    // First paint is the cold-start handoff off the loading ring. A two-text
    // slide there measures twice while the service is still binding, so hold
    // still until that window passes, then only fade the one line.
    var allowFade by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(COLD_START_RING_HINT_MS)
        allowFade = true
    }
    var shown by remember { mutableStateOf(text) }
    var faded by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (faded) 0f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "serviceStatusAlpha",
    )
    LaunchedEffect(text, allowFade) {
        if (!allowFade || text == shown) {
            shown = text
            faded = false
            return@LaunchedEffect
        }
        faded = true
        delay(120)
        shown = text
        faded = false
    }
    Text(
        text = shown,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

@Composable
fun BindToService(
    autoCreate: Boolean = false,
    onConnected: (VoiceSatelliteService) -> Unit,
    onDisconnected: () -> Unit,
) {
    val context = LocalContext.current
    val isRunning by VoiceSatelliteService.isRunning.collectAsStateWithLifecycle(false)
    DisposableEffect(autoCreate, isRunning) {
        var bound = false
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                (binder as? VoiceSatelliteService.VoiceSatelliteBinder)?.let {
                    onConnected(it.service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onDisconnected()
            }
        }
        val serviceIntent = Intent(context, VoiceSatelliteService::class.java)
        // If the FGS already exists (boot / open-app countdown / Start), attach without
        // waiting for a bind round-trip.
        if (isRunning) {
            VoiceSatelliteService.getInstance()?.let(onConnected)
        }
        val shouldBind = autoCreate || isRunning
        if (shouldBind) {
            try {
                val flags = if (autoCreate) Context.BIND_AUTO_CREATE else 0
                bound = context.bindService(serviceIntent, serviceConnection, flags)
                if (!bound) {
                    Log.e(BIND_TO_SERVICE_TAG, "Cannot bind to VoiceSatelliteService")
                }
            } catch (e: Exception) {
                Log.e(BIND_TO_SERVICE_TAG, "Error binding to service", e)
            }
        }

        onDispose {
            if (bound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (e: Exception) {
                    Log.e(BIND_TO_SERVICE_TAG, "Error unbinding service", e)
                }
            }
        }
    }
}

/**
 * Returns a callback that asks for the given permissions.
 *
 * Inside an Activity this is a plain `RequestMultiplePermissions` launcher. Several of
 * these components are also composed inside Service overlay windows (browser sidebar,
 * screensaver, quick-entity), which have no [LocalActivityResultRegistryOwner] — asking
 * for a launcher there throws, and such a window cannot host a permission dialog anyway.
 * On that path already-granted permissions invoke [onPermissionGranted] directly and
 * anything missing brings up the app, mirroring
 * [com.example.ava.services.WebViewPermissionCoordinator].
 */
@Composable
fun rememberLaunchWithMultiplePermissions(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: (deniedPermissions: Array<String>) -> Unit = { }
): (Array<String>) -> Unit {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val grantedCallback = rememberUpdatedState(onPermissionGranted)
    val deniedCallback = rememberUpdatedState(onPermissionDenied)
    // Read once: rememberLauncherForActivityResult throws when this is null, so the
    // overlay branch must not call it. The owner is stable per composition site
    // (Activity vs Service overlay), so the remember slots stay aligned.
    if (LocalActivityResultRegistryOwner.current == null) {
        return remember(appContext) {
            { permissions: Array<String> ->
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(appContext, it) !=
                        PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    grantedCallback.value()
                } else {
                    Log.i(
                        PERMISSION_REQUEST_TAG,
                        "No Activity host for ${missing.size} permission(s); opening app",
                    )
                    runCatching {
                        appContext.startActivity(
                            Intent(appContext, com.example.ava.MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ),
                        )
                    }.onFailure {
                        Log.w(PERMISSION_REQUEST_TAG, "Could not open app for permissions", it)
                    }
                    deniedCallback.value(missing.toTypedArray())
                }
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val deniedPermissions = granted.filter { !it.value }.keys.toTypedArray()
        if (deniedPermissions.isEmpty()) {
            grantedCallback.value()
        } else {
            deniedCallback.value(deniedPermissions)
        }
    }
    return remember(launcher) { { permissions -> launcher.launch(permissions) } }
}
