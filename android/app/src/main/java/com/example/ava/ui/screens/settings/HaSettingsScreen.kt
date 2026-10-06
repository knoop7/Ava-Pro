package com.example.ava.ui.screens.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.homeassistant.HaDiscovery
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.HaPipeline
import com.example.ava.homeassistant.HaWsClient
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsGuideCard
import com.example.ava.ui.screens.settings.components.SettingsHorizontalFadeText
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown

private val HaBlue = Color(0xFF18BCF2)
private val HaDiscoverCardHeight = 56.dp
private val HaDiscoverCardGap = 8.dp
private const val HaDiscoverVisibleCount = 2

@Composable
fun HaSettingsScreen(navController: NavController) {
    val isDarkMode = isDarkModeEnabled()
    val accent = if (isDarkMode) AccentBrown else AccentBlue
    val scale = rememberSettingsTextScale()
    val context = LocalContext.current

    val haManager = remember { HaManager.ensure(context) }
    // Seed every DataStore-backed state from the hot snapshot so the first frame already
    // matches the persisted state. Hardcoded initial values used to flash the login form
    // (signedIn=false) and default toggle positions on every entry until the async read landed.
    val settingsSeed = remember { haManager.settingsStore.getCached() }
    val connectionState by haManager.connectionState.collectAsStateWithLifecycle()
    val pipelines by haManager.pipelines.collectAsStateWithLifecycle()
    val preferredId by haManager.preferredPipelineId.collectAsStateWithLifecycle()
    val entityPickerEnabled by haManager.settingsStore.entityPickerEnabled.collectAsStateWithLifecycle(settingsSeed.entityPickerEnabled)
    val scanModeSyncEnabled by haManager.settingsStore.scanModeSyncEnabled.collectAsStateWithLifecycle(settingsSeed.scanModeSyncEnabled)
    val allowServiceCalls by haManager.settingsStore.allowServiceCalls.collectAsStateWithLifecycle(settingsSeed.allowServiceCalls)
    val wsCallServiceEnabled by haManager.settingsStore.wsCallServiceEnabled.collectAsStateWithLifecycle(settingsSeed.wsCallServiceEnabled)
    val liveStateEnabled by haManager.settingsStore.liveStateEnabled.collectAsStateWithLifecycle(settingsSeed.liveStateEnabled)
    val btProxyCleanupEnabled by haManager.settingsStore.btProxyCleanupEnabled.collectAsStateWithLifecycle(settingsSeed.btProxyCleanupEnabled)
    val mediaBearerEnabled by haManager.settingsStore.mediaBearerEnabled.collectAsStateWithLifecycle(settingsSeed.mediaBearerEnabled)
    val historyBackfillEnabled by haManager.settingsStore.historyBackfillEnabled.collectAsStateWithLifecycle(settingsSeed.historyBackfillEnabled)

    val connected = connectionState is HaWsClient.ConnectionState.Connected
    val isConnecting = connectionState is HaWsClient.ConnectionState.Connecting
    val errorMessage = (connectionState as? HaWsClient.ConnectionState.Error)?.message

    val guideDismissed by haManager.settingsStore.guideDismissed.collectAsStateWithLifecycle(settingsSeed.guideDismissed)
    val savedUrl by haManager.settingsStore.serverUrl.collectAsStateWithLifecycle(settingsSeed.serverUrl)
    val savedToken by haManager.settingsStore.accessToken.collectAsStateWithLifecycle(settingsSeed.accessToken)
    var urlDraft by remember { mutableStateOf<String?>(null) }
    var tokenDraft by remember { mutableStateOf<String?>(null) }
    val url = urlDraft ?: savedUrl
    val token = tokenDraft ?: savedToken
    val signedIn = savedUrl.isNotBlank() && savedToken.isNotBlank()
    var selectedTab by remember { mutableIntStateOf(0) }

    val discovery = remember { HaDiscovery(context) }
    val discovered by discovery.instances.collectAsStateWithLifecycle()
    val scanning by discovery.scanning.collectAsStateWithLifecycle()

    DisposableEffect(discovery) {
        onDispose { discovery.stop() }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_ha_page_title),
    ) {
        item(key = "ha_guide") {
            if (!guideDismissed && !signedIn) {
                SettingsGuideCard(
                    text = stringResource(R.string.settings_ha_guide),
                    onDismiss = { haManager.dismissGuide() },
                )
            }
        }

        item(key = "ha_account") {
            if (signedIn) {
                HaConnectedCard(
                    isDarkMode = isDarkMode,
                    accent = accent,
                    host = url.ifBlank { savedUrl },
                    haVersion = (connectionState as? HaWsClient.ConnectionState.Connected)?.haVersion,
                    onDisconnect = {
                        haManager.disconnect()
                        urlDraft = ""
                        tokenDraft = ""
                    },
                )
            } else {
                HaLoginCard(
                    isDarkMode = isDarkMode,
                    scale = scale,
                    accent = accent,
                    url = url,
                    token = token,
                    isConnecting = isConnecting,
                    errorMessage = errorMessage,
                    scanning = scanning,
                    discovered = discovered,
                    onUrlChange = { urlDraft = it },
                    onTokenChange = { tokenDraft = it },
                    onFindLocal = {
                        if (scanning) discovery.stop() else discovery.start()
                    },
                    onPickInstance = { baseUrl ->
                        urlDraft = baseUrl
                    },
                    onConnect = {
                        haManager.connect(url, token)
                    },
                )
            }
        }

        item(key = "ha_tabs") {
            HaFeatureTabs(
                isDarkMode = isDarkMode,
                accent = accent,
                selectedTab = selectedTab,
                signedIn = signedIn,
                pipelines = pipelines,
                preferredId = preferredId,
                entityPickerEnabled = entityPickerEnabled,
                scanModeSyncEnabled = scanModeSyncEnabled,
                allowServiceCalls = allowServiceCalls,
                wsCallServiceEnabled = wsCallServiceEnabled,
                liveStateEnabled = liveStateEnabled,
                btProxyCleanupEnabled = btProxyCleanupEnabled,
                mediaBearerEnabled = mediaBearerEnabled,
                historyBackfillEnabled = historyBackfillEnabled,
                onTabSelected = { selectedTab = it },
                onNavigateToPipeline = { id ->
                    navController.navigate(
                        Screen.SETTINGS_HA_PIPELINE_DETAIL.replace("{pipelineId}", id)
                    )
                },
                onSelectPipeline = { id -> haManager.setPreferredPipeline(id) },
                onRefresh = { haManager.refreshPipelines() },
                onEntityPickerEnabled = { haManager.setEntityPickerEnabled(it) },
                onScanModeSyncEnabled = { haManager.setScanModeSyncEnabled(it) },
                onAllowServiceCalls = { haManager.setAllowServiceCalls(it) },
                onWsCallServiceEnabled = { haManager.setWsCallServiceEnabled(it) },
                onLiveStateEnabled = { haManager.setLiveStateEnabled(it) },
                onBtProxyCleanupEnabled = { haManager.setBtProxyCleanupEnabled(it) },
                onMediaBearerEnabled = { haManager.setMediaBearerEnabled(it) },
                onHistoryBackfillEnabled = { haManager.setHistoryBackfillEnabled(it) },
            )
        }
    }
}

@Composable
private fun HaLoginCard(
    isDarkMode: Boolean,
    scale: Float,
    accent: Color,
    url: String,
    token: String,
    isConnecting: Boolean,
    errorMessage: String?,
    scanning: Boolean,
    discovered: List<HaDiscovery.HaInstance>,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFindLocal: () -> Unit,
    onPickInstance: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val fieldAccent = if (isDarkMode) Color.White else HaBlue
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = fieldAccent,
        unfocusedBorderColor = if (isDarkMode) Color(0xFF3D3D3D) else Color(0xFFE2E8F0),
        focusedContainerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF8FAFC),
        unfocusedContainerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF8FAFC),
        cursorColor = fieldAccent,
        focusedTextColor = getLabelColor(),
        unfocusedTextColor = getLabelColor(),
        focusedLabelColor = fieldAccent,
        unfocusedLabelColor = getSettingsDescriptionColor(),
        focusedPlaceholderColor = getSettingsDescriptionColor(),
        unfocusedPlaceholderColor = getSettingsDescriptionColor(),
    )

    SimpleCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy((12f * scale).dp),
        ) {
            OutlinedButton(
                onClick = onFindLocal,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_ha_discover_stop),
                        fontSize = settingsTitleTextSize(),
                        color = getLabelColor(),
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_ha_discover),
                        fontSize = settingsTitleTextSize(),
                        color = getLabelColor(),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (discovered.isNotEmpty()) {
                val selectedUrl = url.trim().trimEnd('/')
                Column(verticalArrangement = Arrangement.spacedBy(HaDiscoverCardGap)) {
                    discovered.take(HaDiscoverVisibleCount * 2).forEach { instance ->
                        HaDiscoverRadioCard(
                            instance = instance,
                            selected = selectedUrl.equals(instance.baseUrl, ignoreCase = true),
                            accent = accent,
                            onClick = { onPickInstance(instance.baseUrl) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.settings_ha_login_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_ha_login_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.settings_ha_login_token_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Spacer(modifier = Modifier.height((4f * scale).dp))

            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((48f * scale).dp),
                enabled = url.isNotBlank() && token.isNotBlank() && !isConnecting,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_ha_login_btn),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (15f * scale).sp,
                        color = Color.White,
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = Color(0xFFEF4444),
                    fontSize = settingsBodyTextSize(),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun HaDiscoverRadioCard(
    instance: HaDiscovery.HaInstance,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (selected) accent else getSliderInactiveColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HaDiscoverCardHeight)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) accent else getSettingsDescriptionColor(),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        SettingsHorizontalFadeText(
            text = haShortHostLabel(instance),
            modifier = Modifier.weight(1f),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

private fun haShortHostLabel(instance: HaDiscovery.HaInstance): String {
    return if (instance.port == HaDiscovery.DEFAULT_PORT) {
        instance.host
    } else {
        "${instance.host}:${instance.port}"
    }
}

@Composable
private fun HaConnectedCard(
    isDarkMode: Boolean,
    accent: Color,
    host: String,
    haVersion: String?,
    onDisconnect: () -> Unit,
) {
    val scale = rememberSettingsTextScale()
    val haIconTint = if (isDarkMode) accent else HaBlue
    val disconnectShape = RoundedCornerShape((10f * scale).dp)

    SimpleCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size((44f * scale).dp)
                    .clip(RoundedCornerShape((14f * scale).dp))
                    .background(if (isDarkMode) Color.Transparent else HaBlue.copy(alpha = 0.07f))
                    .border(
                        width = 1.dp,
                        color = if (isDarkMode) accent.copy(alpha = 0.40f) else Color.Transparent,
                        shape = RoundedCornerShape((14f * scale).dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.mdi_home_assistant),
                    contentDescription = null,
                    tint = haIconTint,
                    modifier = Modifier.size((24f * scale).dp),
                )
            }

            Spacer(modifier = Modifier.width((14f * scale).dp))

            Column(modifier = Modifier.weight(1f)) {
                if (host.isNotBlank()) {
                    SettingsHorizontalFadeText(
                        text = host,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                    )
                }
                Spacer(modifier = Modifier.height((3f * scale).dp))
                val secureLabel = stringResource(R.string.settings_ha_conn_secure)
                val meta = if (haVersion.isNullOrBlank()) {
                    secureLabel
                } else {
                    "$secureLabel  ·  $haVersion"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ha_shield_check),
                        contentDescription = null,
                        tint = if (isDarkMode) Color(0xFF64748B) else Color(0xFF94A3B8),
                        modifier = Modifier.size((12f * scale).dp),
                    )
                    Spacer(modifier = Modifier.width((4f * scale).dp))
                    SettingsHorizontalFadeText(
                        text = meta,
                        modifier = Modifier.weight(1f),
                        fontSize = (11f * scale).sp,
                        color = getSettingsDescriptionColor(),
                    )
                }
            }

            Spacer(modifier = Modifier.width((10f * scale).dp))

            Box(
                modifier = Modifier
                    .clip(disconnectShape)
                    .background(accent.copy(alpha = if (isDarkMode) 0.16f else 0.10f))
                    .clickable(onClick = onDisconnect)
                    .padding(horizontal = (12f * scale).dp, vertical = (7f * scale).dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_ha_disconnect),
                    fontSize = settingsBodyTextSize(),
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HaFeatureTabs(
    isDarkMode: Boolean,
    accent: Color,
    selectedTab: Int,
    signedIn: Boolean,
    pipelines: List<HaPipeline>,
    preferredId: String?,
    entityPickerEnabled: Boolean,
    scanModeSyncEnabled: Boolean,
    allowServiceCalls: Boolean,
    wsCallServiceEnabled: Boolean,
    liveStateEnabled: Boolean,
    btProxyCleanupEnabled: Boolean,
    mediaBearerEnabled: Boolean,
    historyBackfillEnabled: Boolean,
    onTabSelected: (Int) -> Unit,
    onNavigateToPipeline: (String) -> Unit,
    onSelectPipeline: (String) -> Unit,
    onRefresh: () -> Unit,
    onEntityPickerEnabled: (Boolean) -> Unit,
    onScanModeSyncEnabled: (Boolean) -> Unit,
    onAllowServiceCalls: (Boolean) -> Unit,
    onWsCallServiceEnabled: (Boolean) -> Unit,
    onLiveStateEnabled: (Boolean) -> Unit,
    onBtProxyCleanupEnabled: (Boolean) -> Unit,
    onMediaBearerEnabled: (Boolean) -> Unit,
    onHistoryBackfillEnabled: (Boolean) -> Unit,
) {
    data class TabItem(val titleRes: Int, val iconRes: Int)

    val tabs = listOf(
        TabItem(R.string.settings_ha_tab_pipeline, R.drawable.ic_ha_tab_pipeline),
        TabItem(R.string.settings_ha_tab_wake, R.drawable.ic_ha_tab_core),
        TabItem(R.string.settings_ha_tab_check, R.drawable.ic_ha_tab_more),
    )
    val scale = rememberSettingsTextScale().coerceAtMost(1.5f)
    val tabTextSize = (14f * scale).sp
    val tabIconSize = (16f * scale).dp
    val tabGap = (5f * scale).dp
    val tabPad = (11f * scale).dp

    SimpleCard {
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
            contentColor = accent,
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accent,
                    )
                }
            },
            divider = {},
        ) {
            tabs.forEachIndexed { index, item ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) },
                    selectedContentColor = accent,
                    unselectedContentColor = getSettingsDescriptionColor(),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = tabPad),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tabGap),
                    ) {
                        Icon(
                            painter = painterResource(item.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(tabIconSize),
                        )
                        Text(
                            text = stringResource(item.titleRes),
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = tabTextSize,
                        )
                    }
                }
            }
        }

        SettingsDivider()

        if (signedIn) {
            when (selectedTab) {
                0 -> HaPipelineTabContent(
                    accent = accent,
                    pipelines = pipelines,
                    preferredId = preferredId,
                    onNavigateToPipeline = onNavigateToPipeline,
                    onSelectPipeline = onSelectPipeline,
                    onRefresh = onRefresh,
                )
                1 -> HaCoreTabContent(
                    liveStateEnabled = liveStateEnabled,
                    onLiveStateEnabled = onLiveStateEnabled,
                    entityPickerEnabled = entityPickerEnabled,
                    onEntityPickerEnabled = onEntityPickerEnabled,
                    allowServiceCalls = allowServiceCalls,
                    onAllowServiceCalls = onAllowServiceCalls,
                    mediaBearerEnabled = mediaBearerEnabled,
                    onMediaBearerEnabled = onMediaBearerEnabled,
                    historyBackfillEnabled = historyBackfillEnabled,
                    onHistoryBackfillEnabled = onHistoryBackfillEnabled,
                )
                else -> HaMoreTabContent(
                    scanModeSyncEnabled = scanModeSyncEnabled,
                    onScanModeSyncEnabled = onScanModeSyncEnabled,
                    wsCallServiceEnabled = wsCallServiceEnabled,
                    onWsCallServiceEnabled = onWsCallServiceEnabled,
                    btProxyCleanupEnabled = btProxyCleanupEnabled,
                    onBtProxyCleanupEnabled = onBtProxyCleanupEnabled,
                )
            }
        } else {
            HaTabSkeleton(isDarkMode = isDarkMode)
        }
    }
}

@Composable
private fun HaTabSkeleton(isDarkMode: Boolean) {
    val pulse by rememberInfiniteTransition(label = "ha_skel").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.52f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse),
        label = "ha_skel_alpha",
    )
    val bar = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE8EDF3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        listOf(1f, 0.72f, 0.88f).forEach { widthFrac ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFrac)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(bar.copy(alpha = pulse)),
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun HaPipelineTabContent(
    accent: Color,
    pipelines: List<HaPipeline>,
    preferredId: String?,
    onNavigateToPipeline: (String) -> Unit,
    onSelectPipeline: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        if (pipelines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.settings_ha_pipeline_empty),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_ha_discover),
                        fontSize = settingsBodyTextSize(),
                        color = getLabelColor(),
                    )
                }
            }
        } else {
            pipelines.forEach { pipeline ->
                val isPreferred = pipeline.id == preferredId
                HaPipelineRow(
                    pipeline = pipeline,
                    isPreferred = isPreferred,
                    accent = accent,
                    onSelectPreferred = { onSelectPipeline(pipeline.id) },
                    onOpen = { onNavigateToPipeline(pipeline.id) },
                )
            }
        }
    }
}

@Composable
private fun HaPipelineRow(
    pipeline: HaPipeline,
    isPreferred: Boolean,
    accent: Color,
    onSelectPreferred: () -> Unit,
    onOpen: () -> Unit,
) {
    val scale = rememberSettingsTextScale().coerceAtMost(1.4f)
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (isPreferred) accent.copy(alpha = 0.4f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (isPreferred) accent.copy(alpha = 0.06f) else Color.Transparent)
            .border(width = if (isPreferred) 1.dp else 0.dp, color = borderColor, shape = shape)
            .padding(horizontal = 12.dp, vertical = (11f * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size((28f * scale).dp)
                .clickable(onClick = onSelectPreferred),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 2.dp,
                        color = if (isPreferred) accent else getSettingsDescriptionColor(),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isPreferred) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pipeline.name,
                fontSize = settingsTitleTextSize(),
                fontWeight = if (isPreferred) FontWeight.SemiBold else FontWeight.Medium,
                color = getLabelColor(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (isPreferred) {
            Text(
                text = stringResource(R.string.settings_ha_pipeline_preferred),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier.clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            SettingsChevronIcon(
                tint = if (isDarkModeEnabled()) Color(0xFF4B5563) else Color(0xFFD1D5DB),
            )
        }
    }
}

@Composable
private fun HaCoreTabContent(
    liveStateEnabled: Boolean,
    onLiveStateEnabled: (Boolean) -> Unit,
    entityPickerEnabled: Boolean,
    onEntityPickerEnabled: (Boolean) -> Unit,
    allowServiceCalls: Boolean,
    onAllowServiceCalls: (Boolean) -> Unit,
    mediaBearerEnabled: Boolean,
    onMediaBearerEnabled: (Boolean) -> Unit,
    historyBackfillEnabled: Boolean,
    onHistoryBackfillEnabled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_live_state),
            description = stringResource(R.string.settings_ha_live_state_desc),
            enabled = liveStateEnabled,
            onEnabledChange = onLiveStateEnabled,
        )
        SettingsDivider()
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_entity_picker),
            description = stringResource(R.string.settings_ha_entity_picker_desc),
            enabled = entityPickerEnabled,
            onEnabledChange = onEntityPickerEnabled,
        )
        SettingsDivider()
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_allow_service_calls),
            description = stringResource(R.string.settings_ha_allow_service_calls_desc),
            enabled = allowServiceCalls,
            onEnabledChange = onAllowServiceCalls,
            warning = if (!allowServiceCalls) {
                stringResource(R.string.settings_ha_allow_service_calls_off)
            } else {
                null
            },
        )
        SettingsDivider()
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_media_bearer),
            description = stringResource(R.string.settings_ha_media_bearer_desc),
            enabled = mediaBearerEnabled,
            onEnabledChange = onMediaBearerEnabled,
        )
        SettingsDivider()
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_history_backfill),
            description = stringResource(R.string.settings_ha_history_backfill_desc),
            enabled = historyBackfillEnabled,
            onEnabledChange = onHistoryBackfillEnabled,
        )
    }
}

@Composable
private fun HaMoreTabContent(
    scanModeSyncEnabled: Boolean,
    onScanModeSyncEnabled: (Boolean) -> Unit,
    wsCallServiceEnabled: Boolean,
    onWsCallServiceEnabled: (Boolean) -> Unit,
    btProxyCleanupEnabled: Boolean,
    onBtProxyCleanupEnabled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_scan_mode_sync),
            description = stringResource(R.string.settings_ha_scan_mode_sync_desc),
            enabled = scanModeSyncEnabled,
            onEnabledChange = onScanModeSyncEnabled,
        )
        SettingsDivider()
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_bt_cleanup),
            description = stringResource(R.string.settings_ha_bt_cleanup_desc),
            enabled = btProxyCleanupEnabled,
            onEnabledChange = onBtProxyCleanupEnabled,
        )
        SettingsDivider()
        HaFeatureSwitchRow(
            title = stringResource(R.string.settings_ha_ws_call_service),
            description = stringResource(R.string.settings_ha_ws_call_service_desc),
            enabled = wsCallServiceEnabled,
            onEnabledChange = onWsCallServiceEnabled,
        )
    }
}

@Composable
private fun HaFeatureSwitchRow(
    title: String,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    warning: String? = null,
) {
    val scale = rememberSettingsTextScale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (14f * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            CollapsibleDescriptionText(
                text = description,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
            if (!warning.isNullOrBlank()) {
                Text(
                    text = warning,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.padding(top = (6f * scale).dp),
                )
            }
        }
        Spacer(modifier = Modifier.width((15f * scale).dp))
        ModernSwitch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}
