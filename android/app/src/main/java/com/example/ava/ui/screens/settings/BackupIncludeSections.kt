package com.example.ava.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.backup.AvaBackupIncludeOptions
import com.example.ava.mods.ModManager
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.HaSettings
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.HomeLockSettings
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.LocalScenesSettings
import com.example.ava.settings.LocalScenesStore
import com.example.ava.settings.MassApiSettings
import com.example.ava.settings.MassApiSettingsStore
import com.example.ava.settings.NotificationSettings
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.ScreensaverSettings
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.SendspinSettings
import com.example.ava.settings.SendspinSettingsStore
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.VoiceChannelSettings
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.haSettingsStore
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.localScenesSettingsStore
import com.example.ava.settings.massApiSettingsStore
import com.example.ava.settings.notificationSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.sendspinSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.screens.home.MinimalLauncherIconsStore
import com.example.ava.ui.screens.home.MinimalLauncherWidgetsStore
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsClickable
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize

private data class BackupIncludeStoreItem(
    val key: String,
    val titleRes: Int,
    val subtitleRes: Int,
)

private val BACKUP_INCLUDE_STORES = listOf(
    BackupIncludeStoreItem("voice_satellite", R.string.settings_backup_include_store_voice_satellite, R.string.settings_backup_include_store_voice_satellite_sub),
    BackupIncludeStoreItem("microphone", R.string.settings_backup_include_store_microphone, R.string.settings_backup_include_store_microphone_sub),
    BackupIncludeStoreItem("voice_channel", R.string.settings_backup_include_store_voice_channel, R.string.settings_backup_include_store_voice_channel_sub),
    BackupIncludeStoreItem("player", R.string.settings_backup_include_store_player, R.string.settings_backup_include_store_player_sub),
    BackupIncludeStoreItem("sendspin", R.string.settings_backup_include_store_sendspin, R.string.settings_backup_include_store_sendspin_sub),
    BackupIncludeStoreItem("ha", R.string.settings_backup_include_store_ha, R.string.settings_backup_include_store_ha_sub),
    BackupIncludeStoreItem("mass_api", R.string.settings_backup_include_store_mass, R.string.settings_backup_include_store_mass_sub),
    BackupIncludeStoreItem("quick_entity", R.string.settings_backup_include_store_quick_entity, R.string.settings_backup_include_store_quick_entity_sub),
    BackupIncludeStoreItem("local_scenes", R.string.settings_backup_include_store_local_scenes, R.string.settings_backup_include_store_local_scenes_sub),
    BackupIncludeStoreItem("browser", R.string.settings_backup_include_store_browser, R.string.settings_backup_include_store_browser_sub),
    BackupIncludeStoreItem("screensaver", R.string.settings_backup_include_store_screensaver, R.string.settings_backup_include_store_screensaver_sub),
    BackupIncludeStoreItem("notification", R.string.settings_backup_include_store_notification, R.string.settings_backup_include_store_notification_sub),
    BackupIncludeStoreItem("sidebar", R.string.settings_backup_include_store_sidebar, R.string.settings_backup_include_store_sidebar_sub),
    BackupIncludeStoreItem("home_lock", R.string.settings_backup_include_store_home_lock, R.string.settings_backup_include_store_home_lock_sub),
    BackupIncludeStoreItem("bluetooth", R.string.settings_backup_include_store_bluetooth, R.string.settings_backup_include_store_bluetooth_sub),
    BackupIncludeStoreItem("settings_style", R.string.settings_backup_include_store_style, R.string.settings_backup_include_store_style_sub),
    BackupIncludeStoreItem("experimental", R.string.settings_backup_include_store_experimental, R.string.settings_backup_include_store_experimental_sub),
    BackupIncludeStoreItem("update", R.string.settings_backup_include_store_update, R.string.settings_backup_include_store_update_sub),
)

private data class BackupFeaturePresence(
    val stores: Set<String>,
    val mods: Boolean,
    val haCredentials: Boolean,
    val massCredentials: Boolean,
    val remoteUrl: Boolean,
    val desktopLayout: Boolean,
    val widgetLayout: Boolean,
    val system: Boolean,
)

private fun ExperimentalSettings.hasOptInFeature(): Boolean =
    cameraEnabled ||
        personDetectionEnabled ||
        environmentSensorEnabled ||
        proximitySensorEnabled ||
        screenBrightnessEnabled ||
        screenTouchSensorEnabled ||
        screenGestureEnabled ||
        forceOrientationEnabled ||
        displaySizeEnabled ||
        diagnosticSensorEnabled ||
        intentLauncherEnabled ||
        mediaKeyEnabled ||
        clusterManagementEnabled ||
        webConsoleEnabled ||
        audioEventDetectionEnabled ||
        deviceIncidentLogEnabled ||
        mainThreadStallWatchdogEnabled

@Composable
private fun rememberBackupFeaturePresence(): BackupFeaturePresence {
    val context = LocalContext.current
    val voiceStore = remember { VoiceChannelSettingsStore(context.voiceChannelSettingsStore) }
    val sendspinStore = remember { SendspinSettingsStore(context.sendspinSettingsStore) }
    val haStore = remember { HaSettingsStore(context.haSettingsStore) }
    val massStore = remember { MassApiSettingsStore(context.massApiSettingsStore) }
    val quickEntityStore = remember { QuickEntitySettingsStore(context.quickEntitySettingsStore) }
    val screensaverStore = remember { ScreensaverSettingsStore(context.screensaverSettingsStore) }
    val notificationStore = remember { NotificationSettingsStore(context.notificationSettingsStore) }
    val sidebarStore = remember { SidebarSettingsStore(context.sidebarSettingsStore) }
    val experimentalStore = remember { ExperimentalSettingsStore(context) }
    val localScenesStore = remember { LocalScenesStore(context.localScenesSettingsStore) }
    val playerStore = remember { PlayerSettingsStore(context.playerSettingsStore) }
    val homeLockStore = remember { HomeLockSettingsStore(context.homeLockSettingsStore) }
    val voiceChannel by voiceStore.getFlow().collectAsState(initial = voiceStore.getCached())
    val sendspin by sendspinStore.getFlow().collectAsState(initial = sendspinStore.getCached())
    val ha by haStore.getFlow().collectAsState(initial = haStore.getCached())
    val mass by massStore.getFlow().collectAsState(initial = massStore.getCached())
    val quickEntity by quickEntityStore.getFlow().collectAsState(initial = quickEntityStore.getCached())
    val browser by remember { BrowserSettingsStore(context) }
        .getFlow().collectAsState(initial = BrowserSettings())
    val screensaver by screensaverStore.getFlow().collectAsState(initial = screensaverStore.getCached())
    val notification by notificationStore.getFlow().collectAsState(initial = notificationStore.getCached())
    val sidebar by sidebarStore.getFlow().collectAsState(initial = sidebarStore.getCached())
    val experimental by experimentalStore.getFlow().collectAsState(initial = experimentalStore.getCached())
    val localScenes by localScenesStore.getFlow().collectAsState(initial = localScenesStore.getCached())
    val player by playerStore.getFlow().collectAsState(initial = playerStore.getCached())
    val homeLock by homeLockStore.getFlow().collectAsState(initial = homeLockStore.getCached())
    val installedMods by ModManager.getInstance(context).installedMods.collectAsState()
    val hasSystemGrants = remember(context) {
        context.getSharedPreferences("mod_permission_requests", Context.MODE_PRIVATE).all.isNotEmpty()
    }
    val hasBluetooth = remember(context) {
        val detectOn = context.getSharedPreferences("bluetooth_settings", Context.MODE_PRIVATE)
            .getBoolean("detect_enabled", false)
        val tracked = context.getSharedPreferences("bluetooth_presence_prefs", Context.MODE_PRIVATE)
            .getString("tracked_devices", null)
            .orEmpty()
            .trim()
        detectOn || (tracked.isNotEmpty() && tracked != "[]")
    }
    val hasDesktopLayout = remember(context, player.enableMinimalLauncher) {
        player.enableMinimalLauncher || MinimalLauncherIconsStore.hasPersistedLayout(context)
    }
    val hasWidgetLayout = remember(context, player.enableMinimalLauncher) {
        player.enableMinimalLauncher || MinimalLauncherWidgetsStore.hasPersistedLayout(context)
    }

    val haConnected = ha.serverUrl.isNotBlank() && ha.accessToken.isNotBlank()
    val massConfigured = mass.enabled ||
        mass.serverUrl.isNotBlank() ||
        mass.authToken.isNotBlank() ||
        mass.username.isNotBlank()
    // enabled defaults to true on a fresh install — only treat as present when
    // the user actually pointed this tablet at a server or paired a peer.
    val sendspinOn = sendspin.serverUrl.isNotBlank() || sendspin.pairedDevices.isNotEmpty()
    val voiceOn = voiceChannel.enabled
    val browserOn = browser.enableBrowserDisplay
    val notificationOn = notification.notificationSceneEnabled
    val remoteUrlOn = browserOn && browser.haRemoteUrlEnabled
    val experimentalOn = experimental.hasOptInFeature()
    val sidebarOn = sidebar.enableSidebar && hasDesktopLayout

    return remember(
        voiceOn, sendspinOn, haConnected, massConfigured, quickEntity.enableQuickEntity,
        browserOn, remoteUrlOn, screensaver.enabled, notificationOn, sidebarOn,
        experimentalOn, localScenes.scenes.size, installedMods.size, hasSystemGrants,
        hasDesktopLayout, hasWidgetLayout, homeLock.enabled, hasBluetooth,
    ) {
        val stores = linkedSetOf<String>()
        stores.add("settings_style")
        stores.add("update")
        stores.add("player")
        if (voiceOn) {
            stores.add("voice_channel")
            stores.add("voice_satellite")
            stores.add("microphone")
        }
        if (sendspinOn) stores.add("sendspin")
        if (haConnected) stores.add("ha")
        if (massConfigured) stores.add("mass_api")
        if (quickEntity.enableQuickEntity) stores.add("quick_entity")
        if (notificationOn || localScenes.scenes.isNotEmpty()) stores.add("local_scenes")
        if (browserOn) stores.add("browser")
        if (screensaver.enabled) stores.add("screensaver")
        if (notificationOn) stores.add("notification")
        if (sidebarOn) stores.add("sidebar")
        if (homeLock.enabled) stores.add("home_lock")
        if (hasBluetooth) stores.add("bluetooth")
        if (experimentalOn) stores.add("experimental")
        BackupFeaturePresence(
            stores = stores,
            mods = installedMods.isNotEmpty(),
            haCredentials = haConnected,
            massCredentials = massConfigured,
            remoteUrl = remoteUrlOn,
            desktopLayout = hasDesktopLayout,
            widgetLayout = hasWidgetLayout,
            system = hasSystemGrants,
        )
    }
}

/** First-open checks: only what this device actually has. Secrets / layouts stay off. */
private fun BackupFeaturePresence.toSeedOptions(): AvaBackupIncludeOptions =
    AvaBackupIncludeOptions(
        includeStores = stores,
        includeMods = mods,
        includeHaCredentials = false,
        includeMassApiCredentials = false,
        includeRemoteBrowserUrl = false,
        includeDesktopLayout = false,
        includeWidgetLayout = false,
        includeSystemPermissions = false,
    )

internal object BackupIncludeMemory {
    private const val PREFS = "ava_backup_include_memory"
    private const val VERSION = 2
    private const val KEY_VERSION = "version"
    internal const val CLONE = "clone"
    internal const val FILE = "file"

    fun load(context: Context, slot: String): AvaBackupIncludeOptions? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) != VERSION) return null
        if (!prefs.contains("${slot}_stores")) return null
        val stores = prefs.getString("${slot}_stores", "")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it in AvaBackupIncludeOptions.PORTABLE_STORE_KEYS }
            .toSet()
        return AvaBackupIncludeOptions(
            includeStores = stores,
            includeMods = prefs.getBoolean("${slot}_mods", false),
            includeHaCredentials = prefs.getBoolean("${slot}_ha_cred", false),
            includeMassApiCredentials = prefs.getBoolean("${slot}_mass_cred", false),
            includeRemoteBrowserUrl = prefs.getBoolean("${slot}_remote", false),
            includeDesktopLayout = prefs.getBoolean("${slot}_desktop", false),
            includeWidgetLayout = prefs.getBoolean("${slot}_widgets", false),
            includeSystemPermissions = prefs.getBoolean("${slot}_system", false),
        )
    }

    fun save(context: Context, slot: String, options: AvaBackupIncludeOptions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, VERSION)
            .putString("${slot}_stores", options.includeStores.sorted().joinToString(","))
            .putBoolean("${slot}_mods", options.includeMods)
            .putBoolean("${slot}_ha_cred", options.includeHaCredentials)
            .putBoolean("${slot}_mass_cred", options.includeMassApiCredentials)
            .putBoolean("${slot}_remote", options.includeRemoteBrowserUrl)
            .putBoolean("${slot}_desktop", options.includeDesktopLayout)
            .putBoolean("${slot}_widgets", options.includeWidgetLayout)
            .putBoolean("${slot}_system", options.includeSystemPermissions)
            .apply()
    }
}

private const val BACKUP_INCLUDE_PREVIEW_ROWS = 5
private val BackupIncludeRowHeight = 56.dp
/** Same trailing slot as Material3 Checkbox, so 全选 sits on the checkbox column. */
private val BackupIncludeCheckSlot = 48.dp

private sealed class BackupIncludeLine {
    data class Store(val item: BackupIncludeStoreItem) : BackupIncludeLine()
    data object Mods : BackupIncludeLine()
    data object HaCreds : BackupIncludeLine()
    data object MassCreds : BackupIncludeLine()
    data object RemoteUrl : BackupIncludeLine()
    data object DesktopLayout : BackupIncludeLine()
    data object WidgetLayout : BackupIncludeLine()
    data object System : BackupIncludeLine()
}

private fun BackupIncludeLine.isChecked(options: AvaBackupIncludeOptions): Boolean = when (this) {
    is BackupIncludeLine.Store -> options.includesStore(item.key)
    BackupIncludeLine.Mods -> options.includeMods
    BackupIncludeLine.HaCreds -> options.includeHaCredentials
    BackupIncludeLine.MassCreds -> options.includeMassApiCredentials
    BackupIncludeLine.RemoteUrl -> options.includeRemoteBrowserUrl
    BackupIncludeLine.DesktopLayout -> options.includeDesktopLayout
    BackupIncludeLine.WidgetLayout -> options.includeWidgetLayout
    BackupIncludeLine.System -> options.includeSystemPermissions
}

private fun BackupIncludeLine.isRowEnabled(
    options: AvaBackupIncludeOptions,
    available: AvaBackupIncludeOptions?,
    editable: Boolean,
): Boolean {
    if (!editable) return false
    return when (this) {
        is BackupIncludeLine.Store -> available?.includesStore(item.key) ?: true
        BackupIncludeLine.Mods -> available?.includeMods ?: true
        BackupIncludeLine.HaCreds -> available?.includeHaCredentials ?: true
        BackupIncludeLine.MassCreds -> available?.includeMassApiCredentials ?: true
        BackupIncludeLine.RemoteUrl -> available?.includeRemoteBrowserUrl ?: true
        BackupIncludeLine.DesktopLayout -> available?.includeDesktopLayout ?: true
        BackupIncludeLine.WidgetLayout -> available?.includeWidgetLayout ?: true
        BackupIncludeLine.System -> available?.includeSystemPermissions ?: true
    }
}

private fun AvaBackupIncludeOptions.withLine(
    line: BackupIncludeLine,
    included: Boolean,
): AvaBackupIncludeOptions = when (line) {
    is BackupIncludeLine.Store -> withStore(line.item.key, included)
    BackupIncludeLine.Mods -> copy(includeMods = included)
    BackupIncludeLine.HaCreds ->
        copy(includeHaCredentials = included).let { if (included) it.withStore("ha", true) else it }
    BackupIncludeLine.MassCreds ->
        copy(includeMassApiCredentials = included).let { if (included) it.withStore("mass_api", true) else it }
    BackupIncludeLine.RemoteUrl ->
        copy(includeRemoteBrowserUrl = included).let { if (included) it.withStore("voice_satellite", true) else it }
    BackupIncludeLine.DesktopLayout ->
        copy(includeDesktopLayout = included).let { if (included) it.withStore("player", true) else it }
    BackupIncludeLine.WidgetLayout ->
        copy(includeWidgetLayout = included).let { if (included) it.withStore("player", true) else it }
    BackupIncludeLine.System -> copy(includeSystemPermissions = included)
}

private fun AvaBackupIncludeOptions.visibleCheckedCount(lines: List<BackupIncludeLine>): Int =
    lines.count { it.isChecked(this) }

private fun AvaBackupIncludeOptions.withLineGuarded(
    line: BackupIncludeLine,
    included: Boolean,
    lines: List<BackupIncludeLine>,
): AvaBackupIncludeOptions {
    val next = withLine(line, included)
    if (!included && next.visibleCheckedCount(lines) < 1) return this
    return next
}

private fun AvaBackupIncludeOptions.withAllLines(
    lines: List<BackupIncludeLine>,
    storeKeys: Set<String>,
    included: Boolean,
): AvaBackupIncludeOptions {
    var next = withStores(storeKeys, included)
    lines.forEach { line ->
        next = next.withLine(line, included)
    }
    if (!included && next.visibleCheckedCount(lines) < 1) {
        lines.firstOrNull()?.let { next = next.withLine(it, true) }
    }
    return next
}

@Composable
internal fun BackupIncludePicker(
    options: AvaBackupIncludeOptions,
    onChange: ((AvaBackupIncludeOptions) -> Unit)?,
    available: AvaBackupIncludeOptions? = null,
    catalog: AvaBackupIncludeOptions? = null,
    enabled: Boolean = true,
    showIdentityNote: Boolean = false,
    showTransferNote: Boolean = false,
    onEffectiveOptions: ((AvaBackupIncludeOptions) -> Unit)? = null,
    onCatalog: ((AvaBackupIncludeOptions) -> Unit)? = null,
    titleRes: Int = R.string.settings_backup_clone_includes_title,
    subtitle: String? = null,
    memoryKey: String? = null,
) {
    val editable = onChange != null && enabled
    val context = LocalContext.current
    val presence = if (catalog == null && available == null) {
        rememberBackupFeaturePresence()
    } else {
        null
    }
    var seeded by remember(memoryKey) {
        mutableStateOf(
            options.hasAnyInclude() &&
                !options.includeStores.containsAll(AvaBackupIncludeOptions.PORTABLE_STORE_KEYS),
        )
    }
    val commitChange: ((AvaBackupIncludeOptions) -> Unit)? =
        if (onChange == null) {
            null
        } else {
            { next ->
                onChange(next)
                if (memoryKey != null) BackupIncludeMemory.save(context, memoryKey, next)
            }
        }
    LaunchedEffect(presence, available, catalog, memoryKey) {
        if (seeded || available != null || catalog != null || commitChange == null || presence == null) {
            return@LaunchedEffect
        }
        val remembered = memoryKey?.let { BackupIncludeMemory.load(context, it) }
        val next = remembered?.restrictToVisible(
            storeKeys = presence.stores,
            mods = presence.mods,
            haCredentials = presence.haCredentials,
            massCredentials = presence.massCredentials,
            remoteUrl = presence.remoteUrl,
            desktopLayout = presence.desktopLayout,
            widgetLayout = presence.widgetLayout,
            system = presence.system,
        )?.takeIf { it.hasAnyInclude() } ?: presence.toSeedOptions()
        commitChange(next)
        seeded = true
    }
    val storeKeys = when {
        catalog != null ->
            catalog.includeStores.intersect(AvaBackupIncludeOptions.PORTABLE_STORE_KEYS)
        available != null ->
            available.includeStores.intersect(AvaBackupIncludeOptions.PORTABLE_STORE_KEYS)
        presence != null -> presence.stores
        else -> AvaBackupIncludeOptions.PORTABLE_STORE_KEYS
    }
    val visibleStores = BACKUP_INCLUDE_STORES.filter { it.key in storeKeys }

    val showMods = catalog?.includeMods ?: available?.includeMods ?: presence?.mods ?: true
    val showHaCreds = catalog?.includeHaCredentials
        ?: available?.includeHaCredentials
        ?: presence?.haCredentials
        ?: true
    val showMassCreds = catalog?.includeMassApiCredentials
        ?: available?.includeMassApiCredentials
        ?: presence?.massCredentials
        ?: true
    val showRemoteUrl = catalog?.includeRemoteBrowserUrl
        ?: available?.includeRemoteBrowserUrl
        ?: presence?.remoteUrl
        ?: true
    val showDesktopLayout = catalog?.includeDesktopLayout
        ?: available?.includeDesktopLayout
        ?: presence?.desktopLayout
        ?: true
    val showWidgetLayout = catalog?.includeWidgetLayout
        ?: available?.includeWidgetLayout
        ?: presence?.widgetLayout
        ?: true
    val showSystem = catalog?.includeSystemPermissions
        ?: available?.includeSystemPermissions
        ?: presence?.system
        ?: true
    val showNotes = showTransferNote || showIdentityNote

    val lines = buildList {
        visibleStores.forEach { add(BackupIncludeLine.Store(it)) }
        if (showMods) add(BackupIncludeLine.Mods)
        if (showDesktopLayout) add(BackupIncludeLine.DesktopLayout)
        if (showWidgetLayout) add(BackupIncludeLine.WidgetLayout)
        if (showHaCreds) add(BackupIncludeLine.HaCreds)
        if (showMassCreds) add(BackupIncludeLine.MassCreds)
        if (showRemoteUrl) add(BackupIncludeLine.RemoteUrl)
        if (showSystem) add(BackupIncludeLine.System)
    }
    val allOn = lines.isNotEmpty() && lines.all { it.isChecked(options) }
    val packed = options.restrictToVisible(
        storeKeys = storeKeys,
        mods = showMods,
        haCredentials = showHaCreds,
        massCredentials = showMassCreds,
        remoteUrl = showRemoteUrl,
        desktopLayout = showDesktopLayout,
        widgetLayout = showWidgetLayout,
        system = showSystem,
    )
    val visibleCatalog = AvaBackupIncludeOptions(
        includeStores = storeKeys,
        includeMods = showMods,
        includeHaCredentials = showHaCreds,
        includeMassApiCredentials = showMassCreds,
        includeRemoteBrowserUrl = showRemoteUrl,
        includeDesktopLayout = showDesktopLayout,
        includeWidgetLayout = showWidgetLayout,
        includeSystemPermissions = showSystem,
    )
    SideEffect {
        onEffectiveOptions?.invoke(packed)
        onCatalog?.invoke(visibleCatalog)
    }
    val previewRows = lines.size.coerceAtMost(BACKUP_INCLUDE_PREVIEW_ROWS)
    val includeScroll = remember(memoryKey) { ScrollState(0) }

    if (lines.isEmpty() && !showNotes) return

    SimpleCard {
        if (lines.isNotEmpty()) {
            BackupIncludeSectionHeader(
                title = stringResource(titleRes),
                subtitle = subtitle,
                actionLabel = if (editable) {
                    if (allOn) {
                        stringResource(R.string.settings_backup_include_select_none)
                    } else {
                        stringResource(R.string.settings_backup_include_select_all)
                    }
                } else {
                    null
                },
                onAction = if (editable) {
                    { commitChange?.invoke(options.withAllLines(lines, storeKeys, !allOn)) }
                } else {
                    null
                },
            )
            SettingsEdgeFadeScrollColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BackupIncludeRowHeight * previewRows),
                scrollState = includeScroll,
                fadeHeight = 24.dp,
                handoffOverscrollToParent = false,
            ) {
                lines.forEachIndexed { index, line ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BackupIncludeRowHeight),
                    ) {
                        if (index > 0) {
                            SettingsDivider()
                        }
                        BackupIncludeLineRow(
                            line = line,
                            options = options,
                            available = available,
                            editable = editable,
                            lines = lines,
                            onChange = commitChange,
                        )
                    }
                }
            }
        }
        if (showNotes) {
            if (lines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (showTransferNote) {
                Text(
                    text = stringResource(R.string.settings_backup_clone_includes_desc),
                    fontSize = settingsCaptionTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(top = if (lines.isEmpty()) 16.dp else 0.dp),
                )
            }
            if (showIdentityNote) {
                Text(
                    text = stringResource(R.string.settings_backup_clone_identity_note),
                    fontSize = settingsCaptionTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(
                        top = if (showTransferNote || lines.isEmpty()) 6.dp else 0.dp,
                        bottom = 10.dp,
                    ),
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BackupIncludeLineRow(
    line: BackupIncludeLine,
    options: AvaBackupIncludeOptions,
    available: AvaBackupIncludeOptions?,
    editable: Boolean,
    lines: List<BackupIncludeLine>,
    onChange: ((AvaBackupIncludeOptions) -> Unit)?,
) {
    val titleRes: Int
    val subtitleRes: Int
    when (line) {
        is BackupIncludeLine.Store -> {
            titleRes = line.item.titleRes
            subtitleRes = line.item.subtitleRes
        }
        BackupIncludeLine.Mods -> {
            titleRes = R.string.settings_backup_restore_include_mods
            subtitleRes = R.string.settings_backup_include_mods_sub
        }
        BackupIncludeLine.HaCreds -> {
            titleRes = R.string.settings_backup_restore_include_ha_credentials
            subtitleRes = R.string.settings_backup_include_ha_credentials_sub
        }
        BackupIncludeLine.MassCreds -> {
            titleRes = R.string.settings_backup_restore_include_mass_api_credentials
            subtitleRes = R.string.settings_backup_include_mass_credentials_sub
        }
        BackupIncludeLine.RemoteUrl -> {
            titleRes = R.string.settings_backup_restore_include_remote_browser_url
            subtitleRes = R.string.settings_backup_include_remote_url_sub
        }
        BackupIncludeLine.DesktopLayout -> {
            titleRes = R.string.settings_backup_restore_include_desktop_layout
            subtitleRes = R.string.settings_backup_include_desktop_layout_sub
        }
        BackupIncludeLine.WidgetLayout -> {
            titleRes = R.string.settings_backup_restore_include_widget_layout
            subtitleRes = R.string.settings_backup_include_widget_layout_sub
        }
        BackupIncludeLine.System -> {
            titleRes = R.string.settings_backup_restore_include_system
            subtitleRes = R.string.settings_backup_include_system_sub
        }
    }
    BackupIncludeCheckRow(
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleRes),
        checked = line.isChecked(options),
        enabled = line.isRowEnabled(options, available, editable),
        onCheckedChange = { onChange?.invoke(options.withLineGuarded(line, it, lines)) },
        modifier = Modifier.fillMaxWidth().height(BackupIncludeRowHeight),
    )
}

@Composable
private fun BackupIncludeSectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = getSettingsDescriptionColor(),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = getSettingsDescriptionColor().copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Box(
                modifier = Modifier
                    .width(BackupIncludeCheckSlot)
                    .settingsClickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = getAccentColor(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BackupIncludeCheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggle = { if (enabled) onCheckedChange(!checked) }
    Row(
        modifier = modifier
            .then(if (enabled) Modifier.settingsClickable(onClick = toggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = if (enabled) getLabelColor() else getSettingsDescriptionColor(),
            )
            Text(
                text = subtitle,
                fontSize = settingsCaptionTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier.width(BackupIncludeCheckSlot),
            contentAlignment = Alignment.Center,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = getAccentColor(),
                    uncheckedColor = Color(0xFF94A3B8),
                    checkmarkColor = Color.White,
                    disabledCheckedColor = getAccentColor().copy(alpha = 0.45f),
                    disabledUncheckedColor = Color(0xFF94A3B8).copy(alpha = 0.45f),
                ),
            )
        }
    }
}
