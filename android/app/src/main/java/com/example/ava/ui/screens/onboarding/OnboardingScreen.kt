package com.example.ava.ui.screens.onboarding

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.permissions.OverlayPermission
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.ui.AvaToast
import com.example.ava.ui.services.StartStopVoiceSatellite
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils
import com.example.ava.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_COUNT = 5

/**
 * /* @debug-onboarding — scale dimensions by [OnboardingPrefs.UI_SCALE_KEY].
 *    Use the ADB tuning command to change at runtime:
 *    adb shell am broadcast -a com.example.ava.ACTION_ONBOARDING_SET_SCALE --ef scale 1.2
 *    To revert: --ef scale 1.0   */
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    /* @debug-onboarding */ val uiScale = remember { OnboardingPrefs.getUiScale(context) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }

    val bg = Color(0xFFFAF8FF)
    val ink = Color(0xFF191B22)
    val muted = Color(0xFF191B22).copy(alpha = 0.58f)
    val faint = Color(0xFF191B22).copy(alpha = 0.4f)
    val lineColor = Color(0xFF434652).copy(alpha = 0.12f)
    val accentBlue = AccentBlue
    val themeRgb = Color(0x240417E0) // 14% accent

    fun goNext() {
        scope.launch {
            if (currentPage < PAGE_COUNT - 1) {
                pagerState.animateScrollToPage(currentPage + 1)
            }
        }
    }

    fun goFinish() {
        OnboardingPrefs.markCompleted(context)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> PermissionsPage(
                    uiScale = uiScale,
                    isLandscape = isLandscape,
                    ink = ink, muted = muted, faint = faint,
                    lineColor = lineColor, accentBlue = accentBlue,
                    themeRgb = themeRgb,
                    onSkip = { scope.launch { pagerState.animateScrollToPage(PAGE_COUNT - 1) } },
                    onContinue = ::goNext,
                )
                1 -> StartServicePage(
                    uiScale = uiScale,
                    isLandscape = isLandscape,
                    ink = ink, muted = muted, faint = faint,
                    lineColor = lineColor, accentBlue = accentBlue,
                    onStart = ::goNext,
                )
                2 -> AddDevicePage(
                    uiScale = uiScale,
                    isLandscape = isLandscape,
                    ink = ink, muted = muted, faint = faint,
                    lineColor = lineColor, accentBlue = accentBlue,
                    onNext = ::goNext,
                )
                3 -> VoiceAssistantPage(
                    uiScale = uiScale,
                    isLandscape = isLandscape,
                    ink = ink, muted = muted, faint = faint,
                    lineColor = lineColor, accentBlue = accentBlue,
                    onBack = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onDone = ::goNext,
                )
                4 -> FinalePage(
                    uiScale = uiScale,
                    isLandscape = isLandscape,
                    ink = ink, faint = faint, accentBlue = accentBlue,
                    bg = bg,
                    active = currentPage == 4,
                    onFinish = ::goFinish,
                )
            }
        }
    }
}

// ─── Page dots ──────────────────────────────────────────────────────────────

@Composable
private fun PageDots(
    currentPage: Int,
    totalPages: Int = 4,
    accent: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalPages) { i ->
            val isActive = i == currentPage
            val w by animateFloatAsState(
                targetValue = if (isActive) 16f else 6f,
                animationSpec = tween(200),
                label = "dot-w-$i",
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(w.dp)
                    .clip(CircleShape)
                    .background(if (isActive) accent else accent.copy(alpha = 0.22f)),
            )
        }
    }
}

// ─── Outline button ─────────────────────────────────────────────────────────

@Composable
private fun OutlineButton(
    text: String,
    accent: Color,
    uiScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = accent,
                    style = Stroke(width = 2.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(999.dp.toPx()),
                )
            }
            .padding(horizontal = (26 * uiScale).dp, vertical = (13 * uiScale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = (15.5f * uiScale).sp,
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── Styled CTA button matching the outline design ──────────────────────────

@Composable
private fun OnboardingButton(
    text: String,
    accent: Color,
    uiScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    land: Boolean = false,
    minWidth: Dp? = null,
) {
    val font = if (land) 15f else 15.5f
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .then(if (minWidth != null) Modifier.widthIn(min = minWidth) else Modifier)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = accent,
                    style = Stroke(width = 2.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(999.dp.toPx()),
                )
            }
            .padding(
                horizontal = ((if (land) 34f else 28f) * uiScale).dp,
                vertical = ((if (land) 15f else 13f) * uiScale).dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = (font * uiScale).sp,
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── Divider line ───────────────────────────────────────────────────────────

@Composable
private fun DividerLine(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

@Composable
private fun ColLabel(uiScale: Float, text: String, faint: Color) {
    Text(
        text = text,
        color = faint,
        fontSize = (13f * uiScale).sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.08f.sp * uiScale,
        modifier = Modifier.padding(bottom = (16 * uiScale).dp),
    )
}

@Composable
private fun SplitGuide(
    uiScale: Float,
    lineColor: Color,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = (40 * uiScale).dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = left,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(lineColor),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = (40 * uiScale).dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = right,
            )
        }
    }
}

// ─── S1: Permissions ────────────────────────────────────────────────────────

@Composable
private fun PermissionsPage(
    uiScale: Float,
    isLandscape: Boolean,
    ink: Color, muted: Color, faint: Color,
    lineColor: Color, accentBlue: Color, themeRgb: Color,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var overlayGranted by remember {
        mutableStateOf(OverlayPermission.isGranted(context))
    }
    // Echo Show Lineage 18.1 (ro.config.low_ram) and Fire OS disable the system switch;
    // the only way in is appops via root / Shizuku / ADB (issue #208).
    val overlayToggleBlocked = remember { OverlayPermission.isSystemToggleBlocked(context) }
    var showOverlayAdbDialog by remember { mutableStateOf(false) }
    var overlayGrantInFlight by remember { mutableStateOf(false) }
    var batteryIgnored by remember {
        mutableStateOf(PlatformCapabilities.isIgnoringBatteryOptimizations(context))
    }
    val permScope = rememberCoroutineScope()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { overlayGranted = OverlayPermission.isGranted(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = OverlayPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The service-side root grant never runs on a fresh install because this page
    // gates service start. Grant here, before the user has to tap anything.
    LaunchedEffect(Unit) {
        if (overlayGranted) return@LaunchedEffect
        overlayGrantInFlight = true
        overlayGranted = withContext(Dispatchers.IO) {
            OverlayPermission.tryPrivilegedGrant(context)
        }
        overlayGrantInFlight = false
    }

    // ADB grants happen off-device; poll so the row flips to Allowed by itself.
    LaunchedEffect(overlayGranted, overlayToggleBlocked) {
        if (overlayGranted || !overlayToggleBlocked) return@LaunchedEffect
        while (!overlayGranted) {
            delay(1500)
            overlayGranted = OverlayPermission.isGranted(context)
        }
        showOverlayAdbDialog = false
    }

    val overlayRequiredMsg = stringResource(R.string.ob_s1_overlay_required)
    fun requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            overlayGranted = true
            return
        }
        if (overlayGrantInFlight) return
        permScope.launch {
            overlayGrantInFlight = true
            overlayGranted = withContext(Dispatchers.IO) {
                OverlayPermission.tryPrivilegedGrant(context)
            }
            overlayGrantInFlight = false
            if (overlayGranted) return@launch
            if (overlayToggleBlocked) {
                showOverlayAdbDialog = true
            } else {
                overlayLauncher.launch(OverlayPermission.settingsIntent(context))
            }
        }
    }
    fun requireOverlayThen(proceed: () -> Unit) {
        overlayGranted = OverlayPermission.isGranted(context)
        if (overlayGranted) {
            proceed()
            return
        }
        if (!overlayToggleBlocked) {
            AvaToast.show(context, overlayRequiredMsg, durationMs = AvaToast.LONG_MS)
        }
        requestOverlay()
    }

    if (showOverlayAdbDialog) {
        OverlayAdbDialog(
            uiScale = uiScale,
            ink = ink, muted = muted, accentBlue = accentBlue,
            command = OverlayPermission.adbGrantCommand(context.packageName),
            hasPrivilegedShell = hasRootOrShizuku(),
            onDismiss = { showOverlayAdbDialog = false },
            onRetry = { requestOverlay() },
        )
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryIgnored = PlatformCapabilities.isIgnoringBatteryOptimizations(context)
    }

    val allowLabel = stringResource(R.string.ob_perm_allow)
    val openLabel = stringResource(R.string.ob_perm_open)
    val activateLabel = stringResource(R.string.ob_perm_activate)
    val allowedLabel = stringResource(R.string.ob_perm_allowed)

    // Polymorphic admin row: Root > Shizuku > Device Admin
    val hasRoot = remember { RootUtils.isRootAvailable() }
    val hasShizuku = remember { ShizukuUtils.isShizukuPermissionGranted() }
    var adminActive by remember { mutableStateOf(ScreenControlUtils.isDeviceAdminActive(context)) }

    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { adminActive = ScreenControlUtils.isDeviceAdminActive(context) }

    val permMic = stringResource(R.string.ob_perm_mic)
    val permMicDesc = stringResource(R.string.ob_perm_mic_desc)
    val permNotif = stringResource(R.string.ob_perm_notif)
    val permNotifDesc = stringResource(R.string.ob_perm_notif_desc)
    val permOverlay = stringResource(R.string.ob_perm_overlay)
    val permOverlayDesc = stringResource(
        if (overlayToggleBlocked && !overlayGranted) {
            R.string.ob_perm_overlay_desc_blocked
        } else {
            R.string.ob_perm_overlay_desc
        }
    )
    val permBattery = stringResource(R.string.ob_perm_battery)
    val permBatteryDesc = stringResource(R.string.ob_perm_battery_desc)

    val adminTitle: String
    val adminDesc: String
    val adminGranted: Boolean
    val adminAction: String
    if (hasRoot) {
        adminTitle = stringResource(R.string.ob_perm_admin_root)
        adminDesc = stringResource(R.string.ob_perm_admin_root_desc)
        adminGranted = true
        adminAction = allowedLabel
    } else if (hasShizuku) {
        adminTitle = stringResource(R.string.ob_perm_admin_shizuku)
        adminDesc = stringResource(R.string.ob_perm_admin_shizuku_desc)
        adminGranted = true
        adminAction = allowedLabel
    } else {
        adminTitle = stringResource(R.string.ob_perm_admin_device)
        adminDesc = stringResource(R.string.ob_perm_admin_device_desc)
        adminGranted = adminActive
        adminAction = activateLabel
    }

    PageScaffold(
        uiScale = uiScale,
        isLandscape = isLandscape,
        heroText = stringResource(R.string.ob_s1_hero),
        subText = stringResource(R.string.ob_s1_sub),
        ink = ink, muted = muted, faint = faint, lineColor = lineColor,
        accentBlue = accentBlue,
        pageIndex = 0,
        buttonText = stringResource(R.string.ob_s1_btn),
        onButton = { requireOverlayThen(onContinue) },
        topAction = stringResource(R.string.ob_later) to { requireOverlayThen(onSkip) },
        showBrandPin = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            PermissionRow(uiScale, R.drawable.ic_ob_mic, permMic, permMicDesc,
                micGranted, allowLabel, allowedLabel, ink, muted, faint, accentBlue, themeRgb,
            ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

            DividerLine(lineColor)
            PermissionRow(uiScale, R.drawable.ic_ob_bell, permNotif, permNotifDesc,
                notifGranted, allowLabel, allowedLabel, ink, muted, faint, accentBlue, themeRgb,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            DividerLine(lineColor)
            PermissionRow(uiScale, R.drawable.ic_ob_overlay, permOverlay, permOverlayDesc,
                overlayGranted, allowLabel, allowedLabel, ink, muted, faint, accentBlue, themeRgb,
            ) { requestOverlay() }

            DividerLine(lineColor)
            PermissionRow(uiScale, R.drawable.ic_ob_battery, permBattery, permBatteryDesc,
                batteryIgnored, openLabel, allowedLabel, ink, muted, faint, accentBlue, themeRgb,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    batteryIgnored = true
                    return@PermissionRow
                }
                batteryLauncher.launch(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply { data = Uri.parse("package:${context.packageName}") })
            }

            DividerLine(lineColor)
            PermissionRow(uiScale, R.drawable.ic_ob_admin, adminTitle, adminDesc,
                adminGranted, adminAction, allowedLabel, ink, muted, faint, accentBlue, themeRgb,
            ) {
                if (!hasRoot && !hasShizuku && ScreenControlUtils.canShowDeviceAdminUi(context)) {
                    adminLauncher.launch(ScreenControlUtils.buildDeviceAdminIntent(context))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    uiScale: Float,
    iconRes: Int,
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    allowedLabel: String = "Allowed",
    ink: Color, muted: Color, faint: Color,
    accentBlue: Color, themeRgb: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!granted) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = (14 * uiScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((14 * uiScale).dp),
    ) {
        Box(
            modifier = Modifier
                .size((40 * uiScale).dp)
                .clip(RoundedCornerShape((11 * uiScale).dp))
                .background(accentBlue.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(accentBlue),
                modifier = Modifier.size((20 * uiScale).dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = ink,
                fontSize = (16f * uiScale).sp,
                fontWeight = FontWeight.W600,
                lineHeight = (20.8f * uiScale).sp,
            )
            Text(
                text = description,
                color = muted,
                fontSize = (13f * uiScale).sp,
                fontWeight = FontWeight.W500,
                lineHeight = (18.2f * uiScale).sp,
            )
        }
        Text(
            text = if (granted) allowedLabel else actionLabel,
            color = if (granted) faint else accentBlue,
            fontSize = (13f * uiScale).sp,
            fontWeight = if (granted) FontWeight.W600 else FontWeight.W700,
        )
    }
}

private fun hasRootOrShizuku(): Boolean =
    RootUtils.isRootBinaryPresent() || ShizukuUtils.isShizukuPermissionGranted()

/**
 * Shown when the ROM disables the "Display over other apps" switch (Android 10+ with
 * `ro.config.low_ram`, e.g. Echo Show LineageOS 18.1). Root/Shizuku have already been
 * tried by the time this appears; the remaining path is one ADB command.
 */
@Composable
private fun OverlayAdbDialog(
    uiScale: Float,
    ink: Color, muted: Color, accentBlue: Color,
    command: String,
    hasPrivilegedShell: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.ob_s3_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = ink,
        textContentColor = muted,
        title = {
            Text(
                text = stringResource(R.string.ob_overlay_blocked_title),
                fontSize = (17f * uiScale).sp,
                fontWeight = FontWeight.W700,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (hasPrivilegedShell) R.string.ob_overlay_blocked_body_root_failed
                        else R.string.ob_overlay_blocked_body
                    ),
                    fontSize = (13.5f * uiScale).sp,
                    fontWeight = FontWeight.W500,
                    lineHeight = (19f * uiScale).sp,
                )
                Spacer(Modifier.height((12 * uiScale).dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape((10 * uiScale).dp))
                        .background(accentBlue.copy(alpha = 0.08f))
                        .clickable {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("adb", command))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = (12 * uiScale).dp, vertical = (10 * uiScale).dp),
                ) {
                    Text(
                        text = command,
                        color = ink,
                        fontSize = (12.5f * uiScale).sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        lineHeight = (17f * uiScale).sp,
                    )
                }
                Spacer(Modifier.height((8 * uiScale).dp))
                Text(
                    text = stringResource(R.string.ob_overlay_blocked_hint),
                    fontSize = (12f * uiScale).sp,
                    fontWeight = FontWeight.W500,
                    lineHeight = (17f * uiScale).sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.ob_overlay_recheck),
                    color = accentBlue,
                    fontWeight = FontWeight.W700,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.ob_overlay_close),
                    color = muted,
                    fontWeight = FontWeight.W600,
                )
            }
        },
    )
}

// ─── S2: Start Service ──────────────────────────────────────────────────────

@Composable
private fun StartServicePage(
    uiScale: Float,
    isLandscape: Boolean,
    ink: Color, muted: Color, faint: Color,
    lineColor: Color, accentBlue: Color,
    onStart: () -> Unit,
) {
    val context = LocalContext.current

    PageScaffold(
        uiScale = uiScale,
        isLandscape = isLandscape,
        heroText = stringResource(R.string.ob_s2_hero),
        subText = stringResource(R.string.ob_s2_sub),
        ink = ink, muted = muted, faint = faint, lineColor = lineColor,
        accentBlue = accentBlue,
        pageIndex = 1,
        buttonText = stringResource(R.string.ob_s2_btn),
        onButton = onStart,
        showBrandPin = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StartStopVoiceSatellite(isDarkMode = false)
        }
    }
}

// ─── S3: Add Device ─────────────────────────────────────────────────────────

@Composable
private fun AddDevicePage(
    uiScale: Float,
    isLandscape: Boolean,
    ink: Color, muted: Color, faint: Color,
    lineColor: Color, accentBlue: Color,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val ipAddress = remember { getDeviceIpAddress(context) }

    // Show the port the ESPHome server actually binds, not the factory default 6053
    // (real ports are device-derived, e.g. 6054..7052). Identity assignment is idempotent,
    // so finalize it here in case the service has not been started yet (issue #200).
    val settingsStore = remember { VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore) }
    LaunchedEffect(settingsStore) {
        withContext(Dispatchers.IO) { settingsStore.ensureMacAddressIsSet(context.applicationContext) }
    }
    val serverPort by remember(settingsStore) { settingsStore.getFlow().map { it.serverPort } }
        .collectAsStateWithLifecycle(initialValue = null)
    val portText = serverPort?.toString() ?: ""

    val copiedMsg = stringResource(R.string.ob_s3_copied)

    PageScaffold(
        uiScale = uiScale,
        isLandscape = isLandscape,
        heroText = stringResource(R.string.ob_s3_hero),
        subText = stringResource(R.string.ob_s3_sub),
        ink = ink, muted = muted, faint = faint, lineColor = lineColor,
        accentBlue = accentBlue,
        pageIndex = 2,
        buttonText = stringResource(R.string.ob_s3_btn),
        onButton = onNext,
        showBrandPin = true,
    ) {
        val discover = listOf(
            stringResource(R.string.ob_s3_step1),
            stringResource(R.string.ob_s3_step2),
            stringResource(R.string.ob_s3_step3),
        )
        val manual = listOf(
            stringResource(R.string.ob_s3_step4),
            stringResource(R.string.ob_s3_step5),
            stringResource(R.string.ob_s3_step6),
        )
        if (isLandscape) {
            SplitGuide(uiScale, lineColor,
                left = {
                    ColLabel(uiScale, stringResource(R.string.ob_s3_section_discovery), faint)
                    discover.forEachIndexed { idx, text ->
                        if (idx > 0) DividerLine(lineColor)
                        StepRow(uiScale, idx + 1, text, ink, muted, accentBlue, land = true)
                    }
                },
                right = {
                    ColLabel(uiScale, stringResource(R.string.ob_s3_section_manual), faint)
                    manual.forEachIndexed { idx, text ->
                        if (idx > 0) DividerLine(lineColor)
                        StepRow(uiScale, idx + 1, text, ink, muted, accentBlue, land = true)
                    }
                    DividerLine(lineColor)
                    AddressRow(
                        uiScale = uiScale,
                        ip = ipAddress,
                        port = portText,
                        copyLabel = stringResource(R.string.ob_s3_copy),
                        copiedMsg = copiedMsg,
                        accentBlue = accentBlue, muted = muted, faint = faint,
                        context = context,
                        land = true,
                    )
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                discover.forEachIndexed { idx, text ->
                    if (idx > 0) DividerLine(lineColor)
                    StepRow(uiScale, idx + 1, text, ink, muted, accentBlue)
                }
                DividerLine(lineColor)
                BreakRow(uiScale, stringResource(R.string.ob_s3_break), muted)
                manual.forEachIndexed { idx, text ->
                    DividerLine(lineColor)
                    StepRow(uiScale, idx + 4, text, ink, muted, accentBlue)
                }
                DividerLine(lineColor)
                AddressRow(
                    uiScale = uiScale,
                    ip = ipAddress,
                    port = portText,
                    copyLabel = stringResource(R.string.ob_s3_copy),
                    copiedMsg = copiedMsg,
                    accentBlue = accentBlue, muted = muted, faint = faint,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun StepRow(
    uiScale: Float,
    number: Int,
    text: String,
    ink: Color, muted: Color, accentBlue: Color,
    land: Boolean = false,
) {
    val circle = if (land) 28f else 22f
    val digit = if (land) 13f else 11.5f
    val body = if (land) 16.5f else 14.5f
    val vPad = if (land) 16f else 12f
    val gap = if (land) 16f else 14f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (vPad * uiScale).dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy((gap * uiScale).dp),
    ) {
        Box(
            modifier = Modifier
                .size((circle * uiScale).dp)
                .clip(CircleShape)
                .background(accentBlue),
            contentAlignment = Alignment.Center,
        ) {
            val digitSize = (digit * uiScale).sp
            Text(
                text = number.toString(),
                color = Color.White,
                fontSize = digitSize,
                fontWeight = FontWeight.W700,
                lineHeight = digitSize,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = digitSize,
                    lineHeight = digitSize,
                    fontWeight = FontWeight.W700,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
        Text(
            text = text,
            color = ink,
            fontSize = (body * uiScale).sp,
            fontWeight = FontWeight.W500,
            lineHeight = (body * 1.4f * uiScale).sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BreakRow(uiScale: Float, text: String, muted: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (12 * uiScale).dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy((14 * uiScale).dp),
    ) {
        Box(
            modifier = Modifier.size((22 * uiScale).dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("·", color = muted, fontSize = (14f * uiScale).sp, fontWeight = FontWeight.W700)
        }
        Text(
            text = text,
            color = muted,
            fontSize = (14.5f * uiScale).sp,
            fontWeight = FontWeight.W600,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun AddressRow(
    uiScale: Float,
    ip: String, port: String,
    copyLabel: String = "Copy",
    copiedMsg: String = "Copied",
    accentBlue: Color, muted: Color, faint: Color,
    context: Context,
    land: Boolean = false,
) {
    val body = if (land) 16f else 15f
    val vPad = if (land) 16f else 12f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (vPad * uiScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((14 * uiScale).dp),
    ) {
        Box(
            modifier = Modifier.size((22 * uiScale).dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("·", color = muted, fontSize = (14f * uiScale).sp, fontWeight = FontWeight.W700)
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ip,
                color = muted,
                fontSize = (body * uiScale).sp,
                fontWeight = FontWeight.W500,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Text(
                text = " : ",
                color = faint,
                fontSize = (body * uiScale).sp,
            )
            Text(
                text = port,
                color = muted,
                fontSize = (body * uiScale).sp,
                fontWeight = FontWeight.W500,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Text(
            text = copyLabel,
            color = accentBlue,
            fontSize = (13f * uiScale).sp,
            fontWeight = FontWeight.W600,
            modifier = Modifier.clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("address", "$ip:$port"))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

// ─── S4: Voice Assistant ────────────────────────────────────────────────────

@Composable
private fun VoiceAssistantPage(
    uiScale: Float,
    isLandscape: Boolean,
    ink: Color, muted: Color, faint: Color,
    lineColor: Color, accentBlue: Color,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    PageScaffold(
        uiScale = uiScale,
        isLandscape = isLandscape,
        heroText = stringResource(R.string.ob_s4_hero),
        subText = stringResource(R.string.ob_s4_sub),
        ink = ink, muted = muted, faint = faint, lineColor = lineColor,
        accentBlue = accentBlue,
        pageIndex = 3,
        buttonText = stringResource(R.string.ob_s4_btn),
        onButton = onDone,
        topAction = stringResource(R.string.ob_back) to onBack,
        showBrandPin = true,
    ) {
        val setupSteps = listOf(
            stringResource(R.string.ob_s4_setup1),
            stringResource(R.string.ob_s4_setup2),
            stringResource(if (isLandscape) R.string.ob_s4_setup3_land else R.string.ob_s4_setup3),
        )
        val picks = listOf(
            Triple(
                stringResource(R.string.ob_s4_pick_stt),
                stringResource(R.string.ob_s4_pick_stt_rec),
                stringResource(R.string.ob_s4_pick_stt_desc),
            ),
            Triple(
                stringResource(R.string.ob_s4_pick_tts),
                stringResource(R.string.ob_s4_pick_tts_rec),
                stringResource(R.string.ob_s4_pick_tts_desc),
            ),
            Triple(
                stringResource(R.string.ob_s4_pick_agent),
                stringResource(R.string.ob_s4_pick_agent_rec),
                stringResource(R.string.ob_s4_pick_agent_desc),
            ),
        )
        if (isLandscape) {
            SplitGuide(uiScale, lineColor,
                left = {
                    ColLabel(uiScale, stringResource(R.string.ob_s4_section_setup), faint)
                    setupSteps.forEachIndexed { idx, text ->
                        if (idx > 0) DividerLine(lineColor)
                        StepRow(uiScale, idx + 1, text, ink, muted, accentBlue, land = true)
                    }
                    Spacer(Modifier.height((18 * uiScale).dp))
                    Text(
                        text = stringResource(R.string.ob_s4_note),
                        color = muted,
                        fontSize = (13f * uiScale).sp,
                        fontWeight = FontWeight.W500,
                        lineHeight = (18.2f * uiScale).sp,
                    )
                },
                right = {
                    ColLabel(uiScale, stringResource(R.string.ob_s4_section_picks), faint)
                    picks.forEachIndexed { idx, (title, pick, desc) ->
                        if (idx > 0) DividerLine(lineColor)
                        PickRow(uiScale, title, pick, desc, ink, muted, land = true)
                    }
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.ob_s4_section_setup),
                    color = faint,
                    fontSize = (12f * uiScale).sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 0.06f.sp * uiScale,
                )
                Spacer(Modifier.height((8 * uiScale).dp))
                setupSteps.forEachIndexed { idx, text ->
                    if (idx > 0) DividerLine(lineColor)
                    StepRow(uiScale, idx + 1, text, ink, muted, accentBlue)
                }
                Spacer(Modifier.height((14 * uiScale).dp))
                DividerLine(lineColor)
                Spacer(Modifier.height((12 * uiScale).dp))
                Text(
                    text = stringResource(R.string.ob_s4_section_picks),
                    color = faint,
                    fontSize = (12f * uiScale).sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 0.06f.sp * uiScale,
                )
                Spacer(Modifier.height((8 * uiScale).dp))
                picks.forEachIndexed { idx, (title, pick, desc) ->
                    if (idx > 0) DividerLine(lineColor)
                    PickRow(uiScale, title, pick, desc, ink, muted)
                }
                Spacer(Modifier.height((10 * uiScale).dp))
                DividerLine(lineColor)
                Spacer(Modifier.height((10 * uiScale).dp))
                Text(
                    text = stringResource(R.string.ob_s4_note),
                    color = muted,
                    fontSize = (12.5f * uiScale).sp,
                    fontWeight = FontWeight.W500,
                    lineHeight = (18.75f * uiScale).sp,
                )
            }
        }
    }
}

@Composable
private fun PickRow(
    uiScale: Float,
    title: String,
    pick: String,
    description: String,
    ink: Color, muted: Color,
    land: Boolean = false,
) {
    val titleSize = if (land) 16.5f else 15f
    val body = if (land) 16.5f else 13f
    val vPad = if (land) 16f else 12f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (vPad * uiScale).dp),
    ) {
        Text(
            text = title,
            color = ink,
            fontSize = (titleSize * uiScale).sp,
            fontWeight = FontWeight.W600,
            lineHeight = (titleSize * 1.4f * uiScale).sp,
        )
        Spacer(Modifier.height(((if (land) 6f else 4f) * uiScale).dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.W600, color = ink)) {
                    append(pick)
                }
                withStyle(SpanStyle(color = muted)) {
                    append(description)
                }
            },
            fontSize = (body * uiScale).sp,
            fontWeight = FontWeight.W500,
            lineHeight = (body * 1.4f * uiScale).sp,
        )
    }
}

// ─── S5: Finale ─────────────────────────────────────────────────────────────

@Composable
private fun FinalePage(
    uiScale: Float,
    isLandscape: Boolean,
    ink: Color, faint: Color, accentBlue: Color,
    bg: Color,
    active: Boolean,
    onFinish: () -> Unit,
) {
    val markAlpha = remember { Animatable(0f) }
    val markScale = remember { Animatable(0.26f) }
    val markTransX = remember { Animatable(0.56f) }
    val markTransY = remember { Animatable(-0.72f) }
    val markBlur = remember { Animatable(2f) }
    val sloganAlpha = remember { Animatable(0f) }
    val sloganTransY = remember { Animatable(14f) }
    val pageAlpha = remember { Animatable(1f) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Phase 1: mark flies in
        launch { markAlpha.animateTo(1f, tween(1050, easing = FastOutSlowInEasing)) }
        launch { markScale.animateTo(1f, tween(1150, easing = FastOutSlowInEasing)) }
        launch { markTransX.animateTo(0f, tween(1150, easing = FastOutSlowInEasing)) }
        launch { markTransY.animateTo(0f, tween(1150, easing = FastOutSlowInEasing)) }
        launch { markBlur.animateTo(0f, tween(900, easing = LinearEasing)) }

        delay(1200)

        // Phase 2: slogan
        launch { sloganAlpha.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        launch { sloganTransY.animateTo(0f, tween(650, easing = FastOutSlowInEasing)) }

        delay(2400)

        // Phase 3: fade out → finish
        pageAlpha.animateTo(0f, tween(800))
        onFinish()
    }

    val paperColor = Color(0xFFEEF2FF)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(pageAlpha.value)
            .background(
                Brush.verticalGradient(listOf(paperColor, bg))
            ),
        contentAlignment = Alignment.Center,
    ) {
        val markBox = if (isLandscape) 188f else 148f
        val markIcon = if (isLandscape) 102f else 86f
        val welcomeSize = if (isLandscape) 38f else 28f
        val sloganSize = if (isLandscape) 18f else 16f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-15).dp),
        ) {
            Box(
                modifier = Modifier
                    .size((markBox * uiScale).dp)
                    .scale(markScale.value)
                    .alpha(markAlpha.value)
                    .blur((markBlur.value * uiScale).dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ava_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(accentBlue),
                    modifier = Modifier.size((markIcon * uiScale).dp),
                )
            }
            Spacer(Modifier.height((28 * uiScale).dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(sloganAlpha.value)
                    .padding(bottom = sloganTransY.value.dp),
            ) {
                val welcomeText = stringResource(R.string.ob_s5_welcome)
                val brandText = stringResource(R.string.ob_s5_brand)
                Text(
                    text = buildAnnotatedString {
                        append(welcomeText.trimEnd())
                        append("\u2002")
                        withStyle(SpanStyle(color = accentBlue)) {
                            append(brandText)
                        }
                    },
                    color = ink,
                    fontSize = (welcomeSize * uiScale).sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = (-0.02f).sp * uiScale,
                    lineHeight = (welcomeSize * 1.25f * uiScale).sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height((12 * uiScale).dp))
                Text(
                    text = stringResource(R.string.ob_s5_slogan),
                    color = faint,
                    fontSize = (sloganSize * uiScale).sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 0.05f.sp * uiScale,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ─── Page scaffold (shared layout) ──────────────────────────────────────────

@Composable
private fun PageScaffold(
    uiScale: Float,
    isLandscape: Boolean,
    heroText: String,
    subText: String,
    ink: Color, muted: Color, faint: Color,
    lineColor: Color, accentBlue: Color,
    pageIndex: Int,
    buttonText: String,
    onButton: () -> Unit,
    topAction: Pair<String, () -> Unit>? = null,
    showBrandPin: Boolean = false,
    content: @Composable () -> Unit,
) {
    val pinSize = (24 * uiScale).dp
    val pinRight = (20 * uiScale).dp
    val pinTop = (16 * uiScale).dp
    val footerBg = Color.White
    val pageBg = Color(0xFFFAF8FF)
    val screenW = LocalConfiguration.current.screenWidthDp
    val screenH = LocalConfiguration.current.screenHeightDp
    val dockCompact = isLandscape && screenH < 500
    val dockHero = if (dockCompact) 19.5f else 21f
    val dockSub = if (dockCompact) 13.5f else 14.5f
    val dockPadV = if (dockCompact) 12f else 16f
    val dockPadH = if (screenW < 720) 28f else 36f
    val dockGap = if (screenW < 720) 20f else 28f
    val dockBtnMin = ((screenW * 0.24f).coerceIn(176f, 228f) * uiScale).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isLandscape) {
                val headerEndPad =
                    if (showBrandPin && topAction != null) (120 * uiScale).dp
                    else if (showBrandPin || topAction != null) (72 * uiScale).dp
                    else (36 * uiScale).dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = (36 * uiScale).dp,
                            end = headerEndPad,
                            top = (45 * uiScale).dp,
                            bottom = (8 * uiScale).dp,
                        ),
                ) {
                    Text(
                        text = heroText,
                        color = ink,
                        fontSize = (21f * uiScale).sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = (-0.015f).sp * uiScale,
                        lineHeight = (26.88f * uiScale).sp,
                    )
                    Spacer(Modifier.height((6 * uiScale).dp))
                    Text(
                        text = subText,
                        color = muted,
                        fontSize = (14f * uiScale).sp,
                        fontWeight = FontWeight.W500,
                        lineHeight = (20.3f * uiScale).sp,
                    )
                }
            }

            // Body — landscape uses the full stage so S3/S4 can split
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        start = if (isLandscape) (52 * uiScale).dp else (36 * uiScale).dp,
                        end = if (isLandscape) (52 * uiScale).dp else (36 * uiScale).dp,
                        top = if (isLandscape) (20 * uiScale).dp else (8 * uiScale).dp,
                        bottom = 0.dp,
                    )
                    .drawBehind {
                        val fade = (28 * uiScale).dp.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(pageBg.copy(alpha = 0f), pageBg),
                                startY = size.height - fade,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - fade),
                            size = Size(size.width, fade),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                content()
            }

            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(footerBg)
                    .padding(
                        start = if (isLandscape) (dockPadH * uiScale).dp else (36 * uiScale).dp,
                        end = if (isLandscape) (dockPadH * uiScale).dp else (36 * uiScale).dp,
                        top = if (isLandscape) (dockPadV * uiScale).dp else (10 * uiScale).dp,
                        bottom = if (isLandscape) (dockPadV * uiScale).dp else (10 * uiScale).dp,
                    ),
            ) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((dockGap * uiScale).dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = heroText,
                                color = ink,
                                fontSize = (dockHero * uiScale).sp,
                                fontWeight = FontWeight.W700,
                                letterSpacing = (-0.015f).sp * uiScale,
                                lineHeight = (dockHero * 1.25f * uiScale).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height((5 * uiScale).dp))
                            Text(
                                text = subText,
                                color = muted,
                                fontSize = (dockSub * uiScale).sp,
                                fontWeight = FontWeight.W500,
                                lineHeight = (dockSub * 1.4f * uiScale).sp,
                                maxLines = if (dockCompact) 1 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height((10 * uiScale).dp))
                            PageDots(currentPage = pageIndex, totalPages = 4, accent = accentBlue)
                        }
                        OnboardingButton(
                            text = buttonText,
                            accent = accentBlue,
                            uiScale = uiScale,
                            onClick = onButton,
                            fillWidth = false,
                            land = true,
                            minWidth = dockBtnMin,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy((8 * uiScale).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PageDots(currentPage = pageIndex, totalPages = 4, accent = accentBlue)
                        OnboardingButton(
                            text = buttonText,
                            accent = accentBlue,
                            uiScale = uiScale,
                            onClick = onButton,
                        )
                    }
                }
            }
        }

        // Top-right: brand pin + top-act (Later / Back)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = pinTop, end = pinRight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((10 * uiScale).dp),
        ) {
            if (topAction != null) {
                Text(
                    text = topAction.first,
                    color = faint,
                    fontSize = (13f * uiScale).sp,
                    fontWeight = FontWeight.W600,
                    modifier = Modifier
                        .clickable(onClick = topAction.second)
                        .padding(vertical = (8 * uiScale).dp, horizontal = (10 * uiScale).dp),
                )
            }
            if (showBrandPin) {
                Image(
                    painter = painterResource(R.drawable.ic_ava_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(accentBlue),
                    modifier = Modifier.size(pinSize),
                )
            }
        }
    }
}

// ─── Utility ────────────────────────────────────────────────────────────────

private fun getDeviceIpAddress(context: Context): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
        val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) "0.0.0.0"
        else "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    } catch (_: Exception) {
        "0.0.0.0"
    }
}
