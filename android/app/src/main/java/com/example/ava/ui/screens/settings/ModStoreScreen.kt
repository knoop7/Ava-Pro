package com.example.ava.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.example.ava.ui.AvaToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.mods.InstalledMod
import com.example.ava.mods.ModManager
import com.example.ava.mods.ModManifest
import com.example.ava.mods.ModPermissionCoordinator
import com.example.ava.mods.ModPermissions
import com.example.ava.mods.StoreMod
import com.example.ava.mods.orEmptyMod
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.rememberPaneIsLandscape
import com.example.ava.ui.safePopBackStack
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.AutoShrinkFontRow
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsCardInnerHorizontalPadding
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.rememberSettingsFlingBehavior
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.settingsFocusHighlight
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private enum class ModStoreTab { Store, Updates, Installed }

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModStoreScreen(navController: NavController) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val coroutineScope = rememberCoroutineScope()
    val modManager = remember { ModManager.getInstance(appContext) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val storeMods by modManager.storeMods.collectAsState()
    val installedMods by modManager.installedMods.collectAsState()
    val isLoading by modManager.isLoading.collectAsState()
    val isRefreshing by modManager.isRefreshing.collectAsState()
    val downloadProgress by modManager.downloadProgress.collectAsState()
    val manifestCache by modManager.manifestCache.collectAsState()
    // Bind card actions to download busy only — never to catalog refresh.
    val isBusy = isLoading

    var selectedTab by remember { mutableStateOf(ModStoreTab.Store) }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                modManager.refreshStore()
            }
        }
    )

    var wasStopped by remember { mutableStateOf(false) }
    val runtimePermissionBridge = remember { RuntimePermissionBridge() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        runtimePermissionBridge.resume(results)
    }

    fun requestModPermissions(
        modId: String,
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null
    ) {
        coroutineScope.launch {
            val outcome = ModPermissionCoordinator.ensurePermissions(
                context = context,
                modManager = modManager,
                modId = modId,
                requestRuntime = { permissions ->
                    val runtime = ModPermissions.runtimePermissionsToRequest(context, permissions)
                    if (runtime.isEmpty()) {
                        permissions.associateWith { true }
                    } else {
                        val results = suspendCancellableCoroutine { cont ->
                            runtimePermissionBridge.continuation = cont
                            permissionLauncher.launch(runtime.toTypedArray())
                        }
                        permissions.associateWith { permission ->
                            if (permission in runtime) results[permission] == true else true
                        }
                    }
                },
            )
            when (outcome) {
                ModPermissionCoordinator.Outcome.Granted -> {
                    onGranted()
                }
                ModPermissionCoordinator.Outcome.GrantedPartial -> {
                    AvaToast.show(
                        context,
                        context.getString(R.string.mod_permission_partial_hint),
                        durationMs = AvaToast.LONG_MS,
                    )
                    onGranted()
                }
                ModPermissionCoordinator.Outcome.DeniedTemporary -> {
                    AvaToast.show(
                        context,
                        context.getString(R.string.mod_permission_denied),
                    )
                    onDenied?.invoke()
                }
                ModPermissionCoordinator.Outcome.DeniedPermanent -> {
                    openAppPermissionSettings(context)
                        AvaToast.show(
                            context,
                            context.getString(R.string.mod_permission_settings_hint),
                            durationMs = AvaToast.LONG_MS,
                        )
                    onDenied?.invoke()
                }
                ModPermissionCoordinator.Outcome.NeedsShizuku -> {
                    AvaToast.show(
                        context,
                        context.getString(R.string.mod_permission_shizuku_hint),
                        durationMs = AvaToast.LONG_MS,
                    )
                    onDenied?.invoke()
                }
            }
        }
    }

    fun activateInstalledMod(modId: String) {
        coroutineScope.launch {
            val result = modManager.setModEnabled(modId, true)
            if (result.isSuccess) {
                VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
                AvaToast.show(context, R.string.mod_store_restart_hint)
            }
        }
    }

    fun handleDownloadedMod(modId: String) {
        coroutineScope.launch {
            modManager.refreshStore()
            if (modManager.isEnabled(modId)) {
                val status = withContext(Dispatchers.IO) {
                    modManager.reloadModSync(modId)
                }
                if (status != "ok") {
                    Log.w("ModStoreScreen", "post-download reload $modId: $status")
                }
            }
        }
        requestModPermissions(
            modId = modId,
            onGranted = {
                AvaToast.show(context, R.string.mod_store_download_success)
                VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
            },
            onDenied = {
                coroutineScope.launch {
                    modManager.setModEnabled(modId, false)
                }
            }
        )
    }

    fun handleModDownloadResult(modId: String, wasUpdateAvailable: Boolean) {
        if (!wasUpdateAvailable) {
            AvaToast.show(context, R.string.mod_store_up_to_date)
            return
        }
        handleDownloadedMod(modId)
    }

    fun handleImportedMod(modId: String) {
        coroutineScope.launch {
            if (modManager.isEnabled(modId)) {
                val status = withContext(Dispatchers.IO) {
                    modManager.reloadModSync(modId)
                }
                if (status != "ok") {
                    Log.w("ModStoreScreen", "post-import reload $modId: $status")
                }
            }
        }
        requestModPermissions(
            modId = modId,
            onGranted = {
                AvaToast.show(context, R.string.mod_store_import_success)
                VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
            },
            onDenied = {
                coroutineScope.launch {
                    modManager.setModEnabled(modId, false)
                }
            },
        )
    }

    val importModLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val result = modManager.importModFromUri(uri)
            val modId = result.getOrNull()
            if (result.isSuccess && !modId.isNullOrBlank()) {
                handleImportedMod(modId)
            } else {
                AvaToast.show(context, R.string.mod_store_import_failed, durationMs = AvaToast.LONG_MS)
                Log.w("ModStoreScreen", "import failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        modManager.ensureRegistryLoaded()
        modManager.refreshStore()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    if (wasStopped) {
                        wasStopped = false
                        coroutineScope.launch {
                            modManager.refreshStore()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val backgroundColor = if (isDarkMode) Color.Black else PureWhiteBackground
    val textColor = if (isDarkMode) DarkTextPrimary else SlateTextDark
    val isLandscape = rememberPaneIsLandscape()
    ImmersiveMode(isLandscape = isLandscape)
    val topBarColor = backgroundColor.copy(alpha = 0.85f)
    val listPadH = settingsListHorizontalPadding()
    val listPadV = settingsListVerticalPadding()
    val flingBehavior = rememberSettingsFlingBehavior()

    val availableMods = remember(storeMods, installedMods) {
        storeMods.filter { store -> installedMods.none { it.id == store.id } }
    }
    val deviceMods = remember(availableMods) { availableMods.filter { it.isDeviceCatalog() } }
    val featureMods = remember(availableMods) { availableMods.filter { !it.isDeviceCatalog() } }
    val updateMods = remember(installedMods, storeMods) {
        installedMods.filter { modManager.hasUpdate(it.id) }
    }
    val currentInstalledMods = remember(installedMods, storeMods) {
        installedMods.filter { !modManager.hasUpdate(it.id) }
    }
    val updateCount = installedMods.count { modManager.hasUpdate(it.id) }

    SettingsSidebarDrawerWrapper(navController) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = backgroundColor,
            contentWindowInsets = avaContentWindowInsets(isLandscape),
            topBar = {
                SettingsHeaderBar(
                    title = stringResource(R.string.mod_store_title),
                    titleColor = textColor,
                    containerColor = topBarColor,
                    onBack = { navController.safePopBackStack() },
                    isLandscape = isLandscape,
                    windowInsets = avaTopBarWindowInsets(isLandscape),
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ModStoreUnderlineTabs(
                        selected = selectedTab,
                        updateCount = updateCount,
                        isRefreshing = isRefreshing && !isLoading,
                        onSelect = { selectedTab = it },
                        onRefresh = {
                            coroutineScope.launch { modManager.refreshStore() }
                        },
                        modifier = Modifier.padding(horizontal = listPadH),
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pullRefresh(pullRefreshState),
                        contentPadding = PaddingValues(
                            start = listPadH,
                            end = listPadH,
                            top = listPadV,
                            bottom = listPadV + 8.dp,
                        ),
                        flingBehavior = flingBehavior,
                    ) {
                        when (selectedTab) {
                            ModStoreTab.Store -> {
                                if (deviceMods.isNotEmpty()) {
                                    item(key = "sec_devices") {
                                        ModStoreSectionLabel(stringResource(R.string.mod_store_section_devices))
                                    }
                                    items(deviceMods, key = { "avail_${it.id}" }) { mod ->
                                        AvailableModCard(
                                            mod = mod,
                                            isBusy = isBusy,
                                            onDownload = {
                                                coroutineScope.launch {
                                                    val result = modManager.downloadMod(mod.id)
                                                    if (result.isSuccess) {
                                                        handleDownloadedMod(mod.id)
                                                    } else {
                                                        AvaToast.show(
                                                            context,
                                                            R.string.mod_store_download_failed,
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                if (featureMods.isNotEmpty()) {
                                    item(key = "sec_features") {
                                        ModStoreSectionLabel(stringResource(R.string.mod_store_section_features))
                                    }
                                    items(featureMods, key = { "avail_${it.id}" }) { mod ->
                                        AvailableModCard(
                                            mod = mod,
                                            isBusy = isBusy,
                                            onDownload = {
                                                coroutineScope.launch {
                                                    val result = modManager.downloadMod(mod.id)
                                                    if (result.isSuccess) {
                                                        handleDownloadedMod(mod.id)
                                                    } else {
                                                        AvaToast.show(
                                                            context,
                                                            R.string.mod_store_download_failed,
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                if (!isRefreshing && availableMods.isEmpty()) {
                                    item(key = "store_empty") {
                                        ModStoreEmptyText(stringResource(R.string.mod_store_empty))
                                    }
                                }
                            }

                            ModStoreTab.Updates -> {
                                if (updateMods.isEmpty()) {
                                    item(key = "updates_empty") {
                                        ModStoreEmptyText(stringResource(R.string.mod_store_updates_empty))
                                    }
                                } else {
                                    items(updateMods, key = { "upd_${it.id}" }) { mod ->
                                        InstalledModCard(
                                            mod = mod,
                                            manifest = manifestCache[mod.id],
                                            hasUpdate = true,
                                            isBusy = isBusy,
                                            onManage = {
                                                openModConfig(navController, context, manifestCache[mod.id], mod.id)
                                            },
                                            onToggle = { enabled ->
                                                if (enabled) {
                                                    requestModPermissions(
                                                        modId = mod.id,
                                                        onGranted = { activateInstalledMod(mod.id) },
                                                    )
                                                } else {
                                                    coroutineScope.launch {
                                                        val result = modManager.setModEnabled(mod.id, false)
                                                        if (result.isSuccess) {
                                                            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
                                                                AvaToast.show(
                                                                    context,
                                                                    R.string.mod_store_restart_hint,
                                                                )
                                                        }
                                                    }
                                                }
                                            },
                                            onDelete = {
                                                coroutineScope.launch {
                                                    val result = modManager.deleteMod(mod.id)
                                                    if (result.isSuccess) {
                                                        VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
                                                    }
                                                }
                                            },
                                            onUpdate = {
                                                coroutineScope.launch {
                                                    val needsUpdate = modManager.hasUpdate(mod.id)
                                                    val result = modManager.downloadMod(mod.id)
                                                    if (result.isSuccess) {
                                                        handleModDownloadResult(mod.id, needsUpdate)
                                                    } else {
                                                        AvaToast.show(
                                                            context,
                                                            R.string.mod_store_download_failed,
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            ModStoreTab.Installed -> {
                                item(key = "import_card") {
                                    ModStoreImportCard(
                                        enabled = !isBusy,
                                        onClick = {
                                            importModLauncher.launch(
                                                arrayOf(
                                                    "application/zip",
                                                    "application/x-zip-compressed",
                                                    "application/octet-stream",
                                                ),
                                            )
                                        },
                                    )
                                }
                                if (currentInstalledMods.isEmpty()) {
                                    item(key = "installed_empty") {
                                        ModStoreEmptyText(stringResource(R.string.mod_store_installed_empty))
                                    }
                                } else {
                                    items(currentInstalledMods, key = { "inst_${it.id}" }) { mod ->
                                        InstalledModCard(
                                            mod = mod,
                                            manifest = manifestCache[mod.id],
                                            hasUpdate = false,
                                            isBusy = isBusy,
                                            onManage = {
                                                openModConfig(navController, context, manifestCache[mod.id], mod.id)
                                            },
                                            onToggle = { enabled ->
                                                if (enabled) {
                                                    requestModPermissions(
                                                        modId = mod.id,
                                                        onGranted = { activateInstalledMod(mod.id) },
                                                    )
                                                } else {
                                                    coroutineScope.launch {
                                                        val result = modManager.setModEnabled(mod.id, false)
                                                        if (result.isSuccess) {
                                                            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
                                                                AvaToast.show(
                                                                    context,
                                                                    R.string.mod_store_restart_hint,
                                                                )
                                                        }
                                                    }
                                                }
                                            },
                                            onDelete = {
                                                coroutineScope.launch {
                                                    val result = modManager.deleteMod(mod.id)
                                                    if (result.isSuccess) {
                                                        VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
                                                    }
                                                }
                                            },
                                            onUpdate = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                downloadProgress?.let { progress ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                            .focusProperties { canFocus = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                color = getAccentColor(),
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = progress, fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun openModConfig(
    navController: NavController,
    context: Context,
    cachedManifest: ModManifest?,
    modId: String,
) {
    val hasConfig = cachedManifest?.config?.isNotEmpty() == true
    val hasStatusPanel = cachedManifest?.statusPanel?.isNotEmpty() == true
    if (hasConfig || hasStatusPanel) {
        navController.navigate("${com.example.ava.ui.Screen.MOD_CONFIG}/$modId")
    } else {
        AvaToast.show(context, R.string.mod_store_no_config)
    }
}

@Composable
private fun ModStoreImportCard(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isDarkMode = LocalContext.current
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_DARK_MODE, false)
    val cardBg = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val accent = getAccentColor()
    val dash = accent.copy(alpha = 0.35f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .settingsFocusHighlight(cornerRadius = 18.dp, horizontalOutset = 0.dp)
            .background(cardBg, RoundedCornerShape(18.dp))
            .border(1.dp, dash, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Upload,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.mod_store_import),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.SemiBold,
                color = getTitleColor(),
            )
            Text(
                text = stringResource(R.string.mod_store_import_hint),
                fontSize = settingsBodyTextSize(base = 11f),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SettingsChevronIcon(tint = Color(0xFF94A3B8), base = 20f)
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun ModStoreUnderlineTabs(
    selected: ModStoreTab,
    updateCount: Int,
    isRefreshing: Boolean,
    onSelect: (ModStoreTab) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = getAccentColor()
    val muted = getSettingsDescriptionColor()
    val scale = rememberSettingsTextScale()
    val tabFont = (13f * scale).sp
    val tabTopPad = (6f * scale).dp
    val tabLabelPadTop = (8f * scale).dp
    val refreshHit = (28f * scale).dp
    val refreshIcon = (14f * scale).dp
    val badgeSize = (14f * scale).dp
    val badgeFont = (7.5f * scale).sp
    val underlineHeight = (2f * scale).coerceAtLeast(2f).dp
    val underlineGap = (8f * scale).dp
    val spin = rememberInfiniteTransition(label = "mod_refresh")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "mod_refresh_angle",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = tabTopPad)
    ) {
        listOf(
            ModStoreTab.Store to stringResource(R.string.mod_store_tab_store),
            ModStoreTab.Installed to stringResource(R.string.mod_store_tab_installed),
            ModStoreTab.Updates to stringResource(R.string.mod_store_tab_updates),
        ).forEach { (tab, label) ->
            val on = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .settingsFocusHighlight(cornerRadius = 12.dp, horizontalOutset = 0.dp)
                    .clickable { onSelect(tab) }
                    .padding(top = tabLabelPadTop),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        fontSize = tabFont,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) accent else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab == ModStoreTab.Store) {
                        Spacer(modifier = Modifier.width((2f * scale).dp))
                        IconButton(
                            onClick = onRefresh,
                            enabled = !isRefreshing,
                            modifier = Modifier
                                .size(refreshHit)
                                .settingsFocusHighlight(cornerRadius = 14.dp, horizontalOutset = 0.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.mod_store_refresh),
                                tint = if (isRefreshing || on) accent else muted,
                                modifier = Modifier
                                    .size(refreshIcon)
                                    .then(if (isRefreshing) Modifier.rotate(angle) else Modifier),
                            )
                        }
                    }
                    if (tab == ModStoreTab.Updates && updateCount > 0) {
                        Spacer(modifier = Modifier.width((5f * scale).dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(badgeSize)
                                .defaultMinSize(minWidth = badgeSize)
                                .background(accent, CircleShape)
                                .padding(horizontal = if (updateCount >= 10) (3.5f * scale).dp else 0.dp),
                        ) {
                            Text(
                                text = updateCount.toString(),
                                color = Color.White,
                                fontSize = badgeFont,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                style = TextStyle(
                                    fontSize = badgeFont,
                                    lineHeight = badgeFont,
                                    fontWeight = FontWeight.SemiBold,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                                ),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(underlineGap))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.64f)
                        .height(underlineHeight)
                        .background(if (on) accent else Color.Transparent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
    HorizontalDivider(
        color = Color(0xFFE5E7EB).copy(alpha = 0.55f),
        thickness = 1.dp,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModStoreSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = settingsTitleTextSize(),
        fontWeight = FontWeight.SemiBold,
        color = getAccentColor(),
        modifier = Modifier.padding(
            start = SettingsCardInnerHorizontalPadding,
            end = 16.dp,
            top = 8.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun ModStoreEmptyText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = getSettingsDescriptionColor(),
            fontSize = settingsTitleTextSize(),
        )
    }
}

private fun StoreMod.isDeviceCatalog(): Boolean =
    path.contains("/devices/", ignoreCase = true)

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private class RuntimePermissionBridge {
    var continuation: kotlin.coroutines.Continuation<Map<String, Boolean>>? = null

    fun resume(results: Map<String, Boolean>) {
        continuation?.resume(results)
        continuation = null
    }
}

@Composable
private fun InstalledModCard(
    mod: InstalledMod,
    manifest: ModManifest?,
    hasUpdate: Boolean,
    isBusy: Boolean,
    onManage: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
            title = {
                Text(
                    text = stringResource(R.string.mod_store_delete),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.mod_store_delete_confirm),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor()
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text(
                        text = stringResource(R.string.mod_store_delete),
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        color = Color(0xFF94A3B8),
                        fontSize = settingsTitleTextSize()
                    )
                }
            }
        )
    }

    SimpleCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .settingsFocusHighlight(cornerRadius = 18.dp, horizontalOutset = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = manifest?.name.orEmptyMod().ifBlank { mod.id },
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.SemiBold,
                            color = getTitleColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        (manifest?.version ?: mod.version).asDisplayVersion()?.let { versionText ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = versionText, fontSize = 10.sp, color = getSettingsDescriptionColor(), modifier = Modifier.padding(top = 2.dp))
                        }
                        if (hasUpdate) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(text = "NEW", fontSize = 9.sp, color = getAccentColor(), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    val descText = buildModCardDescription(
                        detailDescription = manifest?.detailDescription,
                        description = manifest?.description,
                        author = manifest?.author,
                    )
                    CollapsibleDescriptionText(
                        text = descText,
                        modifier = Modifier.padding(end = 8.dp),
                        collapsedLines = 3,
                    )
                }

                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    ModernSwitch(
                        checked = mod.enabled,
                        onCheckedChange = onToggle,
                        enabled = !isBusy
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFFE5E7EB).copy(alpha = 0.5f))

            val baseActionFontSize = settingsBodyTextSize()
            val updateLabel = stringResource(R.string.mod_store_update)
            val manageLabel = stringResource(R.string.mod_store_manage)
            val uninstallLabel = stringResource(R.string.mod_store_uninstall)
            val authorLabel = stringResource(R.string.mod_store_author)
            val author = manifest?.author.orEmptyMod()
            val showAuthor = author.isNotEmpty()
            val context = LocalContext.current
            val authorName = if (author.equals("Ava", ignoreCase = true)) "knoop7" else author

            AutoShrinkFontRow(
                // The old vertical padding and half of each gap now live inside the
                // links' touch padding, so the labels render exactly where they used to.
                modifier = Modifier.actionLinkTouchOverhang(),
                baseFontSize = baseActionFontSize,
                minFontSize = (baseActionFontSize.value * 0.72f).coerceAtLeast(9f).sp,
            ) { fontSize ->
                if (hasUpdate) {
                    ModStoreActionLink(
                        text = updateLabel,
                        fontSize = fontSize,
                        enabled = !isBusy,
                        onClick = onUpdate,
                    )
                    ModStoreActionGap(fontSize, baseActionFontSize)
                    ModStoreActionDivider(fontSize)
                    ModStoreActionGap(fontSize, baseActionFontSize)
                }
                ModStoreActionLink(
                    text = manageLabel,
                    fontSize = fontSize,
                    enabled = !isBusy,
                    onClick = onManage,
                )
                ModStoreActionGap(fontSize, baseActionFontSize)
                ModStoreActionDivider(fontSize)
                ModStoreActionGap(fontSize, baseActionFontSize)
                ModStoreActionLink(
                    text = uninstallLabel,
                    fontSize = fontSize,
                    enabled = !isBusy,
                    onClick = { showDeleteDialog = true },
                )
                if (showAuthor) {
                    ModStoreActionGap(fontSize, baseActionFontSize)
                    ModStoreActionDivider(fontSize)
                    ModStoreActionGap(fontSize, baseActionFontSize)
                    ModStoreActionLink(
                        text = authorLabel,
                        fontSize = fontSize,
                        enabled = true,
                        onClick = {
                            val url = "https://github.com/$authorName"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
            }
        }
    }
}

// Touch padding folded into each action link. The bare labels are tiny (13sp base,
// down to 9sp after auto-shrink), and text-only hit bounds caused dead taps on
// "Manage"/"Uninstall". The padding sits inside the clickable so the hit target is
// label + ~18dp width and ~20dp height, while the row/gap metrics compensate so the
// rendered layout is unchanged.
private val ModStoreActionTouchPadH = 9.dp
private val ModStoreActionTouchPadV = 10.dp

@Composable
private fun ModStoreActionLink(
    text: String,
    fontSize: TextUnit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        fontSize = fontSize,
        color = getAccentColor(),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .settingsFocusHighlight(cornerRadius = 10.dp, horizontalOutset = 0.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = ModStoreActionTouchPadH, vertical = ModStoreActionTouchPadV),
    )
}

@Composable
private fun ModStoreActionDivider(fontSize: TextUnit) {
    Text(
        text = "|",
        fontSize = fontSize,
        color = Color(0xFFD1D5DB),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun ModStoreActionGap(fontSize: TextUnit, baseFontSize: TextUnit) {
    val ratio = if (baseFontSize.value > 0f) fontSize.value / baseFontSize.value else 1f
    // Half of the visual 18dp gap lives inside the adjacent link's touch padding.
    Spacer(modifier = Modifier.width((9f * ratio).coerceAtLeast(4f).dp))
}

/**
 * Lets the action row overhang its slot by one touch-padding on each side (into the
 * card's 24dp inner padding, still inside the card's clip bounds) so the links'
 * enlarged touch targets neither indent the first label nor steal width from
 * [AutoShrinkFontRow]'s fit calculation.
 */
private fun Modifier.actionLinkTouchOverhang(): Modifier = layout { measurable, constraints ->
    val sidePx = ModStoreActionTouchPadH.roundToPx()
    val expanded = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = constraints.maxWidth + 2 * sidePx)
    } else {
        constraints
    }
    val placeable = measurable.measure(expanded)
    layout((placeable.width - 2 * sidePx).coerceAtLeast(0), placeable.height) {
        placeable.place(-sidePx, 0)
    }
}

@Composable
private fun AvailableModCard(
    mod: StoreMod,
    isBusy: Boolean,
    onDownload: () -> Unit
) {
    SimpleCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .settingsFocusHighlight(cornerRadius = 18.dp, horizontalOutset = 0.dp)
                .clickable(enabled = !isBusy, onClick = onDownload),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = mod.name.orEmptyMod().ifBlank { mod.id },
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.SemiBold,
                            color = getTitleColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        mod.version.asDisplayVersion()?.let { versionText ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = versionText, fontSize = 10.sp, color = getSettingsDescriptionColor(), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    val descText = buildModCardDescription(
                        detailDescription = mod.detailDescription,
                        description = mod.description,
                        author = mod.author,
                    )
                    CollapsibleDescriptionText(
                        text = descText,
                        modifier = Modifier.padding(end = 8.dp),
                        collapsedLines = 3,
                    )
                }

                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    FilledIconButton(
                        onClick = onDownload,
                        enabled = !isBusy,
                        modifier = Modifier
                            .size(36.dp)
                            .focusProperties { canFocus = false },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = getAccentColor().copy(alpha = 0.12f),
                            contentColor = getAccentColor()
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun String?.asDisplayVersion(): String? {
    val normalized = orEmptyMod().trim()
    return normalized.takeIf { it.isNotBlank() }?.let { "v$it" }
}

private fun buildModCardDescription(
    detailDescription: String?,
    description: String?,
    author: String?,
): String {
    return buildString {
        val summary = detailDescription.orEmptyMod().ifBlank { description.orEmptyMod() }
        if (summary.isNotEmpty()) append(summary)
        val authorName = author.orEmptyMod()
        if (authorName.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append("@$authorName")
        }
    }
}
