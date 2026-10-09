package com.example.ava.ui.screens.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import com.example.ava.ui.AvaToast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.ava.settings.DisplayScale
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.mods.ModBleAdvProxyBridge
import com.example.ava.R
import com.example.ava.QrCodeDialog
import com.example.ava.services.WebViewService
import com.example.ava.ui.Screen
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.rememberAdaptiveSpec
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.safePopBackStack
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.BottomSheetHandle
import com.example.ava.ui.screens.settings.components.settingsNavBarEdgeFade
import com.example.ava.ui.screens.settings.components.ModalSheetDragHandle
import com.example.ava.ui.screens.settings.components.SettingsHandleSheet
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.SettingsHorizontalFadeText
import com.example.ava.ui.screens.settings.components.SettingsScaleTier
import com.example.ava.ui.screens.settings.components.rememberModalSheetScrollFlingGuard
import com.example.ava.ui.screens.settings.components.rememberSettingsFlingBehavior
import com.example.ava.ui.screens.settings.components.rememberSettingsScaleTier
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.settingsFocusHighlight
import com.example.ava.ui.screens.settings.components.rememberUnconsumedNavigationBarBottom
import com.example.ava.ui.screens.settings.components.settingsMainGridContentPadding
import com.example.ava.ui.screens.settings.components.settingsMainListContentPadding
import java.net.NetworkInterface


val PureWhiteBackground = Color(0xFFF9FAFB)
val DarkBackground = Color.Black

val SlateTextDark = Color(0xFF1E293B) 
val SlateTextLight = Color(0xFF64748B) 
val SlateTextMuted = Color(0xFF94A3B8)
val DarkTextPrimary = Color(0xFFF1F5F9)
val DarkTextSecondary = Color(0xFFACAEB0) 


val RoseGradient = Color(0xFFFFF1F2) 
val SlateGradient = Color(0xFFF8FAFC) 
val VioletGradient = Color(0xFFF5F3FF) 
val BlueGradient = Color(0xFFEFF6FF) 
val AmberGradient = Color(0xFFFFFBEB) 
val GrayGradient = Color(0xFFF9FAFB) 


val RoseIconColor = Color(0xFF9F1239) 
val SlateIconColor = Color(0xFF0F172A) 
val VioletIconColor = Color(0xFF4C1D95) 
val BlueIconColor = Color(0xFF1E3A8A) 
val AmberIconColor = Color(0xFF78350F) 
val GrayIconColor = Color(0xFF374151) 


val RoseBorderColor = Color(0xFFFECDD3) 
val SlateBorderColor = Color(0xFFE2E8F0) 
val VioletBorderColor = Color(0xFFDDD6FE) 
val BlueBorderColor = Color(0xFFBFDBFE) 
val AmberBorderColor = Color(0xFFFDE68A) 
val GrayBorderColor = Color(0xFFE5E7EB) 


data class SettingsGroup(
    val title: String,
    val enTitle: String, 
    val subtitle: String,
    val iconResId: Int,
    val gradientColor: Color,
    val iconColor: Color,
    val borderColor: Color,
    val route: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    masterPane: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    var showDebugSheet by remember { mutableStateOf(false) }
    var showCoffeeDialog by remember { mutableStateOf(false) }
    var showWechatDialog by remember { mutableStateOf(false) }
    
    val backgroundColor = if (isDarkMode) DarkBackground else PureWhiteBackground
    val textColor = if (isDarkMode) DarkTextPrimary else SlateTextDark
    val accentColor = getAccentColor()
    
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val shortestSideDp = minOf(screenWidthDp, screenHeightDp)
    val longestSideDp = maxOf(screenWidthDp, screenHeightDp)
    val isLandscape = !rememberCompactSquareScreen() &&
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val adaptive = rememberAdaptiveSpec()
    val isWideScreen = screenWidthDp > 600 && !masterPane
    val scaleTier = rememberSettingsScaleTier()
    val mainHandle by SettingsStyleSession.mainHandle.collectAsStateWithLifecycle()

    ImmersiveMode(isLandscape = isLandscape)
    val flingBehavior = rememberSettingsFlingBehavior()

    // Out-of-envelope dp width usually means the panel misreports its density
    // (UI renders physically tiny). Never auto-correct — just point the user at
    // the interface-scale slider once; tapping the toast jumps straight there.
    LaunchedEffect(Unit) {
        if (DisplayScale.shouldOfferHint(context)) {
            DisplayScale.markHintShown()
            AvaToast.show(
                context,
                context.getString(R.string.display_scale_hint_toast),
                tag = "display_scale_hint",
                onTap = {
                    navController.navigate(Screen.SETTINGS_STYLE) { launchSingleTop = true }
                },
                closable = true,
            )
        }
    }

    if (showCoffeeDialog) {
        val coffeeUrl = stringResource(R.string.buy_me_a_coffee_url)
        QrCodeDialog(
            assetName = "bmc_qr.webp",
            title = stringResource(R.string.buy_me_a_coffee_title),
            subtitle = stringResource(R.string.buy_me_a_coffee_subtitle),
            caption = stringResource(R.string.buy_me_a_coffee_qr_desc),
            footer = null,
            qrContentDescription = stringResource(R.string.buy_me_a_coffee_qr_desc),
            onDismiss = { showCoffeeDialog = false },
            onQrClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(coffeeUrl)))
            }
        )
    }

    if (showWechatDialog) {
        QrCodeDialog(
            assetName = "wechat_pay_qr.webp",
            title = stringResource(R.string.wechat_payment_title),
            subtitle = stringResource(R.string.wechat_payment_subtitle),
            caption = stringResource(R.string.wechat_payment_qr),
            footer = null,
            qrContentDescription = stringResource(R.string.wechat_payment_qr_desc),
            onDismiss = { showWechatDialog = false }
        )
    }
    
    val featureManager = com.example.ava.utils.DeviceFeatureManager

    var bleAdvStandalone by remember { mutableStateOf(ModBleAdvProxyBridge.isStandalone(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bleAdvStandalone = ModBleAdvProxyBridge.isStandalone(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    val settingsGroups = listOfNotNull(
        
        SettingsGroup(
            title = stringResource(R.string.settings_group_connection),
            enTitle = stringResource(R.string.settings_group_connection_en),
            subtitle = stringResource(R.string.settings_group_connection_desc),
            iconResId = R.drawable.esphome_24px,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_CONNECTION
        ),
        
        SettingsGroup(
            title = stringResource(R.string.settings_group_service),
            enTitle = stringResource(R.string.settings_group_service_en),
            subtitle = stringResource(R.string.settings_group_service_desc),
            iconResId = R.drawable.mdi_cog_transfer,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_SERVICE
        ),
        
        SettingsGroup(
            title = stringResource(R.string.settings_group_interaction),
            enTitle = stringResource(R.string.settings_group_interaction_en),
            subtitle = stringResource(R.string.settings_group_interaction_desc),
            iconResId = R.drawable.display_24px,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_INTERACTION
        ),
        
        if (!bleAdvStandalone) SettingsGroup(
            title = stringResource(R.string.settings_group_bluetooth),
            enTitle = stringResource(R.string.settings_group_bluetooth_en),
            subtitle = stringResource(R.string.settings_group_bluetooth_desc),
            iconResId = R.drawable.bluetooth_24px,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_BLUETOOTH
        ) else null,
        
        if (featureManager.shouldShowScreensaverSettings()) SettingsGroup(
            title = stringResource(R.string.settings_group_screensaver),
            enTitle = stringResource(R.string.settings_group_screensaver_en),
            subtitle = stringResource(R.string.settings_group_screensaver_desc),
            iconResId = R.drawable.screensaver_24px,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_SCREENSAVER
        ) else null,
        
        if (featureManager.shouldShowBrowserSettings()) SettingsGroup(
            title = stringResource(R.string.settings_group_browser),
            enTitle = stringResource(R.string.settings_group_browser_en),
            subtitle = stringResource(R.string.settings_group_browser_desc),
            iconResId = R.drawable.globe_24px,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_BROWSER
        ) else null,
        
        if (featureManager.shouldShowExperimentalSettings()) SettingsGroup(
            title = stringResource(R.string.settings_group_experimental),
            enTitle = stringResource(R.string.settings_group_experimental_en),
            subtitle = stringResource(R.string.settings_group_experimental_desc),
            iconResId = R.drawable.mdi_dots_circle,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_EXPERIMENTAL
        ) else null,
        
        SettingsGroup(
            title = stringResource(R.string.settings_group_root),
            enTitle = stringResource(R.string.settings_group_root_en),
            subtitle = stringResource(R.string.settings_group_root_desc),
            iconResId = R.drawable.root_24px,
            gradientColor = BlueGradient,
            iconColor = accentColor,
            borderColor = Color(0xFFF1F5F9),
            route = Screen.SETTINGS_ROOT
        )
    )
    
    val page: @Composable () -> Unit = {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = if (masterPane) {
            WindowInsets(0, 0, 0, 0)
        } else {
            avaContentWindowInsets(isLandscape)
        },
        topBar = {
            SettingsHeaderBar(
                title = stringResource(R.string.label_settings),
                titleColor = textColor,
                containerColor = backgroundColor,
                onBack = {
                    WebViewService.exitSettings(context)
                    navController.safePopBackStack()
                },
                isLandscape = isLandscape,
                windowInsets = avaTopBarWindowInsets(isLandscape)
            )
        }
    ) { innerPadding ->
        val unconsumedNav = rememberUnconsumedNavigationBarBottom(
            innerPadding.calculateBottomPadding(),
        )
        val extraNav = if (isLandscape) unconsumedNav else 0.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(
                    if (extraNav > 0.dp) Modifier.padding(bottom = extraNav) else Modifier
                )
                .background(backgroundColor)
                .settingsNavBarEdgeFade(backgroundColor)
        ) {
            if (isWideScreen) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = settingsMainGridContentPadding(handleClearance = mainHandle),
                    horizontalArrangement = Arrangement.spacedBy(
                        when (scaleTier) {
                            SettingsScaleTier.XLARGE -> 26.dp
                            SettingsScaleTier.LARGE -> 22.dp
                            SettingsScaleTier.TABLET -> 20.dp
                            SettingsScaleTier.PHONE -> if (adaptive.expanded) 18.dp else 20.dp
                        }
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        when (scaleTier) {
                            SettingsScaleTier.XLARGE -> 18.dp
                            SettingsScaleTier.LARGE -> 16.dp
                            SettingsScaleTier.TABLET -> 14.dp
                            SettingsScaleTier.PHONE -> if (adaptive.expanded) 12.dp else 14.dp
                        }
                    ),
                    flingBehavior = flingBehavior,
                ) {
                    items(settingsGroups.size) { index ->
                        SettingsGroupCard(
                            group = settingsGroups[index],
                            onClick = {
                        if (masterPane) {
                            navController.navigateSettingsSplitGroup(settingsGroups[index].route)
                        } else {
                            navController.navigate(settingsGroups[index].route) { launchSingleTop = true }
                        }
                    },
                            widthDp = screenWidthDp
                        )
                    }

                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = settingsMainListContentPadding(handleClearance = mainHandle),
                    verticalArrangement = Arrangement.spacedBy(
                        when (scaleTier) {
                            SettingsScaleTier.XLARGE -> 18.dp
                            SettingsScaleTier.LARGE -> 16.dp
                            SettingsScaleTier.TABLET -> 14.dp
                            SettingsScaleTier.PHONE -> if (adaptive.compact) 10.dp else 12.dp
                        }
                    ),
                    flingBehavior = flingBehavior,
                ) {
                    items(settingsGroups) { group ->
                        SettingsGroupCard(
                            group = group,
                            onClick = {
                                if (masterPane) {
                                    navController.navigateSettingsSplitGroup(group.route)
                                } else {
                                    navController.navigate(group.route) { launchSingleTop = true }
                                }
                            },
                            widthDp = screenWidthDp
                        )
                    }
                }
            }

            if (mainHandle) {
                BottomSheetHandle(
                    isDarkMode = isDarkMode,
                    onClick = { showDebugSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 10.dp)
                )
            }

        }
    }
    }
    SettingsSidebarDrawerWrapper(navController, content = page)
    if (showDebugSheet) {
        SettingsDebugBottomSheet(
            onDismiss = { showDebugSheet = false },
            onShowCoffeeDialog = { showCoffeeDialog = true },
            onShowWechatDialog = { showWechatDialog = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDebugBottomSheet(
    onDismiss: () -> Unit,
    onShowCoffeeDialog: () -> Unit,
    onShowWechatDialog: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val isLandscape = rememberSettingsHandleLandscape()
    val accentColor = getAccentColor()
    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val labelColor = if (isDarkMode) Color(0xFF6B7280) else Color(0xFF9CA3AF)
    val valueColor = if (isDarkMode) DarkTextPrimary else SlateTextDark

    val versionName = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            "v${packageInfo.versionName}"
        } catch (_: Exception) {
            "v0.0.0"
        }
    }

    val deviceIp = remember {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList()
            interfaces?.find { it.displayName.contains("wlan") || it.displayName.contains("eth") }
                ?.inetAddresses?.toList()
                ?.find { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "N/A"
        } catch (_: Exception) {
            "N/A"
        }
    }
    val webViewVersion = remember {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    WebView.getCurrentWebViewPackage()?.versionName ?: "N/A"
                }
                else -> {
                    val candidates = listOf("com.google.android.webview", "com.android.webview")
                    candidates.firstNotNullOfOrNull { packageName ->
                        runCatching {
                            context.packageManager.getPackageInfo(packageName, 0).versionName
                        }.getOrNull()
                    } ?: "N/A"
                }
            }
        } catch (_: Exception) {
            "N/A"
        }
    }
    val androidSystemVersion = "Android ${Build.VERSION.RELEASE ?: "Unknown"} (SDK ${Build.VERSION.SDK_INT})"
    val kernelVersion = remember {
        runCatching { System.getProperty("os.version") ?: "N/A" }.getOrDefault("N/A")
    }

    val sheetScrollFlingGuard = rememberModalSheetScrollFlingGuard()
    val stackSpacing = if (isLandscape) 16.dp else 14.dp

    SettingsHandleSheet(
        onDismiss = onDismiss,
        containerColor = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC),
    ) {
        val fillSheet = LocalSettingsSplitActive.current || rememberCompactSquareScreen()
        ModalSheetDragHandle(isDarkMode = isDarkMode, onClick = onDismiss)
        DebugSheetStack(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillSheet) Modifier.weight(1f) else Modifier)
                .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
                .padding(bottom = if (isLandscape) 14.dp else 24.dp),
            spacing = stackSpacing,
            content = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fillSheet) Modifier.fillMaxHeight() else Modifier),
                    shape = RoundedCornerShape(24.dp),
                    color = cardBackground,
                    shadowElevation = 0.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    val logsCopiedMessage = stringResource(R.string.debug_logs_copied)
                    val splitActive = fillSheet
                    val cardPadH = if (isLandscape) 24.dp else 16.dp
                    val cardPadV = if (isLandscape) 18.dp else 14.dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (splitActive) Modifier.fillMaxHeight() else Modifier)
                    ) {
                    SettingsEdgeFadeScrollColumn(
                        modifier = Modifier
                            .then(
                                if (splitActive) Modifier.weight(1f)
                                else Modifier.weight(1f, fill = false)
                            )
                            .padding(
                                start = cardPadH,
                                end = cardPadH,
                                top = cardPadV,
                                bottom = 8.dp,
                            )
                            .nestedScroll(sheetScrollFlingGuard),
                        fadeHeight = 18.dp,
                        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 14.dp else 10.dp),
                    ) {
                            DebugInfoRow(
                                label = "Version",
                                value = stringResource(R.string.ava_version, versionName),
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape,
                                trailing = {
                                    IconButton(
                                        onClick = {
                                            val report = com.example.ava.utils.DebugLogCollector.buildReport(
                                                listOf(
                                                    "Version" to versionName,
                                                    "WebView" to webViewVersion,
                                                    "Android" to androidSystemVersion,
                                                    "Kernel" to kernelVersion
                                                )
                                            )
                                            com.example.ava.utils.DebugLogCollector.copyToClipboard(context, report)
                                            AvaToast.show(context, logsCopiedMessage)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.content_copy_24px),
                                            contentDescription = stringResource(R.string.debug_copy_logs),
                                            tint = labelColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                            DebugInfoRow(
                                label = "Device IP",
                                value = deviceIp,
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape
                            )
                            DebugInfoRow(
                                label = "WebView",
                                value = webViewVersion,
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape
                            )
                            DebugInfoRow(
                                label = "Android",
                                value = androidSystemVersion,
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape
                            )
                            DebugInfoRow(
                                label = "Kernel",
                                value = kernelVersion,
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape
                            )
                            DebugInfoRow(
                                label = "Original",
                                value = "brownard",
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape
                            )
                            DebugInfoRow(
                                label = "Modified",
                                value = "knoop7",
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape
                            )
                    }
                    SettingsDivider()
                    ClaritySwitchRow(
                        isLandscape = isLandscape,
                        titleColor = valueColor,
                        descColor = labelColor,
                        checked = experimentalState?.clarityEnabled ?: true,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.saveClarityEnabled(enabled) }
                        },
                        modifier = Modifier.padding(
                            start = cardPadH,
                            end = cardPadH,
                            top = if (isLandscape) 8.dp else 6.dp,
                            bottom = cardPadV,
                        ),
                    )
                    }
                }
            },
            footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val supportButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/knoop7/Ava/issues/")
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = supportButtonPadding,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDarkMode) cardBackground else Color.White,
                        contentColor = if (isDarkMode) DarkTextPrimary else Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    ),
                    border = if (isDarkMode) null else androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.github_24px),
                        contentDescription = null,
                        tint = if (isDarkMode) DarkTextPrimary else Color.Black,
                        modifier = Modifier.size(if (isLandscape) 16.dp else 14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_open_github_issue),
                        fontSize = if (isLandscape) 14.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onShowCoffeeDialog,
                    modifier = Modifier.weight(1f),
                    contentPadding = supportButtonPadding,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDarkMode) cardBackground else Color.White,
                        contentColor = if (isDarkMode) DarkTextPrimary else Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    ),
                    border = if (isDarkMode) null else androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.coffee_24px),
                        contentDescription = null,
                        tint = Color(0xFFFFDD00),
                        modifier = Modifier.size(if (isLandscape) 16.dp else 14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.buy_me_a_coffee_button),
                        fontSize = if (isLandscape) 14.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onShowWechatDialog,
                    modifier = Modifier.weight(1f),
                    contentPadding = supportButtonPadding,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDarkMode) cardBackground else Color.White,
                        contentColor = if (isDarkMode) DarkTextPrimary else Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    ),
                    border = if (isDarkMode) null else androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.wechat_24px),
                        contentDescription = null,
                        tint = Color(0xFF07C160),
                        modifier = Modifier.size(if (isLandscape) 16.dp else 14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.wechat_payment_button),
                        fontSize = if (isLandscape) 14.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            }
        )
    }
}

/**
 * Measure [footer] first so the three action buttons stay on-screen.
 * [content] gets the leftover height and wraps when shorter than the cap.
 */
@Composable
private fun DebugSheetStack(
    modifier: Modifier = Modifier,
    spacing: Dp,
    content: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { spacing.roundToPx() }
    SubcomposeLayout(modifier) { constraints ->
        val footerPlaceables = subcompose("footer") { footer() }.map { measurable ->
            measurable.measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth)
            )
        }
        val footerHeight = footerPlaceables.maxOfOrNull { it.height } ?: 0
        val fill = constraints.hasBoundedHeight &&
            constraints.minHeight >= constraints.maxHeight &&
            constraints.maxHeight < Int.MAX_VALUE
        val contentMaxHeight = (constraints.maxHeight - footerHeight - spacingPx).coerceAtLeast(0)
        val contentPlaceables = subcompose("content") { content() }.map { measurable ->
            measurable.measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = if (fill) contentMaxHeight else 0,
                    maxWidth = constraints.maxWidth,
                    maxHeight = contentMaxHeight,
                )
            )
        }
        val contentHeight = contentPlaceables.maxOfOrNull { it.height } ?: 0
        val gap = if (contentHeight > 0 && footerHeight > 0) spacingPx else 0
        val width = maxOf(
            contentPlaceables.maxOfOrNull { it.width } ?: 0,
            footerPlaceables.maxOfOrNull { it.width } ?: 0,
        ).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (contentHeight + gap + footerHeight)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            footerPlaceables.forEach { it.placeRelative(0, height - footerHeight) }
        }
    }
}

@Composable
private fun DebugInfoRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    isLandscape: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val fontSize = if (isLandscape) 14.sp else 12.sp
    val lineHeight = if (isLandscape) 20.sp else 18.sp
    val labelWidth = if (isLandscape) 100.dp else 82.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(labelWidth)
        )
        Spacer(modifier = Modifier.width(12.dp))
        SettingsHorizontalFadeText(
            text = value,
            color = valueColor,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(6.dp))
            trailing()
        }
    }
}

@Composable
private fun ClaritySwitchRow(
    isLandscape: Boolean,
    titleColor: Color,
    descColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleSize = if (isLandscape) 13.sp else 12.sp
    val descSize = if (isLandscape) 11.sp else 10.sp
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_clarity),
                color = titleColor,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.settings_clarity_desc),
                color = descColor,
                fontSize = descSize,
                lineHeight = if (isLandscape) 14.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .requiredWidth(if (isLandscape) 42.dp else 38.dp)
                .requiredHeight(if (isLandscape) 24.dp else 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.scale(if (isLandscape) 0.72f else 0.66f)) {
                ModernSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    group: SettingsGroup,
    onClick: () -> Unit,
    widthDp: Int
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)

    val adaptive = rememberAdaptiveSpec()
    val scaleTier = rememberSettingsScaleTier()
    val panelScale = adaptive.panelScale
    val tierScale = when (scaleTier) {
        SettingsScaleTier.PHONE -> 1f
        SettingsScaleTier.TABLET -> 1.14f
        SettingsScaleTier.LARGE -> 1.26f
        SettingsScaleTier.XLARGE -> 1.4f
    }
    // Typography follows shared vmin scale; chrome padding still uses tiered panelScale.
    val textScale = rememberSettingsTextScale()
    val compositeScale = panelScale * tierScale
    val horizontalPadding = (22f * compositeScale).dp
    val verticalPadding = (18f * compositeScale).dp
    val iconBoxSize = (50f * compositeScale).dp
    val iconSize = (22f * compositeScale).dp
    val iconCorner = (18f * panelScale).dp
    val contentSpacing = (16f * compositeScale).dp
    val titleFont = (16f * textScale).sp
    val enTitleFont = (12f * textScale.coerceAtMost(1.35f)).sp
    // Phone baseline stays 12sp (not 12.5); tablets grow via textScale only.
    val subtitleFont = (12f * textScale).sp
    val titleSpacer = 2.dp
    val minCardHeight = when {
        scaleTier == SettingsScaleTier.XLARGE -> 156.dp
        scaleTier == SettingsScaleTier.LARGE -> 138.dp
        scaleTier == SettingsScaleTier.TABLET -> 120.dp
        adaptive.compact -> 92.dp
        adaptive.expanded -> 104.dp
        else -> 98.dp
    }
    
    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val iconBoxBackground = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF9FAFB)
    val titleColor = if (isDarkMode) DarkTextPrimary else SlateTextDark
    val iconTint = if (isDarkMode) Color(0xFF9CA3AF) else group.iconColor
    val subtitleColor = if (isDarkMode) DarkTextSecondary else SlateTextLight
    val enTitleColor = if (isDarkMode) DarkTextSecondary else SlateTextMuted
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minCardHeight)
            // Before clip: the remote-control focus ring must not be cut off.
            .settingsFocusHighlight(cornerRadius = 22.dp, horizontalOutset = 0.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = cardBackground,
        shadowElevation = if (isDarkMode) 0.dp else 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF1F5F9))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            
            Box(
                modifier = Modifier
                    .size(iconBoxSize)
                    .clip(RoundedCornerShape(iconCorner))
                    .background(iconBoxBackground)
                    .border(
                        width = 1.dp,
                        color = if (isDarkMode) Color(0xFF3D3D3D) else Color.White,
                        shape = RoundedCornerShape(iconCorner)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(group.iconResId),
                    contentDescription = null,
                    tint = if (isDarkMode) group.iconColor else iconTint,
                    modifier = Modifier.size(iconSize)
                )
            }
            
            Spacer(modifier = Modifier.width(contentSpacing))
            
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsHorizontalFadeText(
                        text = group.title,
                        color = titleColor,
                        fontSize = titleFont,
                        lineHeight = titleFont,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SettingsHorizontalFadeText(
                        text = group.enTitle.uppercase(),
                        color = enTitleColor,
                        fontSize = enTitleFont,
                        lineHeight = enTitleFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                    )
                }
                Spacer(modifier = Modifier.height(titleSpacer))

                SettingsHorizontalFadeText(
                    text = group.subtitle,
                    color = subtitleColor,
                    fontSize = subtitleFont,
                    lineHeight = subtitleFont,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.15.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            
            
            SettingsChevronIcon(
                tint = if (isDarkMode) Color(0xFF4B5563) else Color(0xFFD1D5DB),
                base = 22f,
            )
        }
    }
}
