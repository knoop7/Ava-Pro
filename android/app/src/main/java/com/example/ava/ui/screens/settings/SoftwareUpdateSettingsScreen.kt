package com.example.ava.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.MainActivity
import com.example.ava.R
import com.example.ava.backup.AvaBackupIncludeOptions
import com.example.ava.backup.AvaBackupManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.AvaToast
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.Screen
import com.example.ava.ui.safePopBackStack
import com.example.ava.settings.UpdateDownloadMethod
import com.example.ava.settings.UpdateSettings
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.ActionDialog
import com.example.ava.ui.screens.settings.components.DialogScope
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.update.AppUpdater
import com.example.ava.update.GitHubReleaseNotes
import com.example.ava.update.ReleaseCoverArt
import com.example.ava.update.RemoteUpdatePhase
import com.example.ava.update.UpdateInstallPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SoftwareUpdateSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val accent = getAccentColor()
    val subColor = getSettingsDescriptionColor()
    val notesBg = if (isDarkMode) Color(0xFF111111) else Color.White
    val notesColor = getSettingsDescriptionColor()

    val installed = remember { AppUpdater.getVersionName(context) }
    var releases by remember { mutableStateOf<List<GitHubReleaseNotes.Release>>(emptyList()) }
    var selectedTag by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var phaseText by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentPhase by remember { mutableStateOf(RemoteUpdatePhase.IDLE) }
    var downloadViaSystem by remember { mutableStateOf(false) }
    val downloadPaused by AppUpdater.downloadPaused.collectAsStateWithLifecycle()
    val liveProgress by AppUpdater.liveProgress.collectAsStateWithLifecycle()
    val updateSettings by viewModel.updateSettingsState.collectAsStateWithLifecycle(UpdateSettings())
    val downgradeDialog = remember { DialogScope() }
    val downgradeOpen by downgradeDialog.isDialogOpen.collectAsStateWithLifecycle()
    var exportingJson by remember { mutableStateOf(false) }
    var sawLiveWork by remember { mutableStateOf(false) }
    val liveWorking = when (liveProgress.phase) {
        RemoteUpdatePhase.DOWNLOADING,
        RemoteUpdatePhase.VERIFYING,
        RemoteUpdatePhase.INSTALLING,
        RemoteUpdatePhase.REBOOTING -> true
        else -> false
    }
    val displayBusy = busy || liveWorking

    LaunchedEffect(reloadKey) {
        val forceRefresh = reloadKey > 0
        loadFailed = false
        val cached = withContext(Dispatchers.IO) {
            GitHubReleaseNotes.cachedInstallableReleases(context)
        }
        val previousFingerprint = settingsReleaseFingerprint(releases.ifEmpty { cached })
        if (cached.isNotEmpty()) {
            releases = cached
            selectedTag = pickSettingsTag(cached, selectedTag)
            loading = false
        } else {
            loading = true
        }

        // Freshness is decided inside fetchInstallableReleases by the cheap
        // one-release probe (publish date / tag / digest + ETag): unchanged →
        // cache returns immediately, new publish → full list refresh right now.
        val fetched = withContext(Dispatchers.IO) {
            GitHubReleaseNotes.fetchInstallableReleases(context, forceRefresh = forceRefresh)
        }
        if (fetched.isNotEmpty()) {
            releases = fetched
            selectedTag = pickSettingsTag(fetched, selectedTag)
            loadFailed = false
            val nextFingerprint = settingsReleaseFingerprint(fetched)
            if (nextFingerprint != previousFingerprint) {
                ReleaseCoverArt.prefetch(
                    context,
                    fetched.take(3).mapNotNull { ReleaseCoverArt.extractCoverUrl(it.fullBody) },
                )
            }
        } else if (releases.isEmpty()) {
            loadFailed = true
        }
        loading = false
    }

    val selected = releases.firstOrNull { it.tagName == selectedTag }
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Ambient masthead cover for the selected release. Previous art stays on
    // screen while the next one loads so switching versions never flashes.
    var mastheadArt by remember { mutableStateOf<ReleaseCoverArt.Art?>(null) }
    LaunchedEffect(selectedTag, releases) {
        val index = releases.indexOfFirst { it.tagName == selectedTag }
        val release = releases.getOrNull(index)
        val coverUrl = ReleaseCoverArt.extractCoverUrl(release?.fullBody)
        // Each selection also warms the next-older release's cover, so walking
        // down the version list stays one step ahead of the user.
        val olderUrl = releases.getOrNull(index + 1)
            ?.let { ReleaseCoverArt.extractCoverUrl(it.fullBody) }
        if (coverUrl == null) {
            mastheadArt = null
            olderUrl?.let { ReleaseCoverArt.prefetch(context, listOf(it)) }
            return@LaunchedEffect
        }
        mastheadArt = withContext(Dispatchers.IO) { ReleaseCoverArt.load(context, coverUrl) }
        olderUrl?.let { ReleaseCoverArt.prefetch(context, listOf(it)) }
    }
    val emptyNotes = stringResource(R.string.settings_update_empty_notes)

    fun actionLabel(): String {
        if (loading) return context.getString(R.string.settings_update_loading)
        if (loadFailed) return context.getString(R.string.settings_update_retry)
        val tag = selected?.tagName ?: return context.getString(R.string.settings_update_action_unavailable)
        return when {
            selected.apkAsset() == null -> context.getString(R.string.settings_update_action_unavailable)
            GitHubReleaseNotes.isClearlyNewer(tag, installed) ->
                context.getString(R.string.settings_update_action_update)
            GitHubReleaseNotes.isClearlyOlder(tag, installed) ->
                context.getString(R.string.settings_update_action_install)
            // Same version tuple: publisher SHA differs → update (same-version rebuild);
            // SHA matches / missing → reinstall current, never "install this version".
            GitHubReleaseNotes.isSameVersionTuple(tag, installed) -> {
                if (GitHubReleaseNotes.sameVersionPublisherUpdateAvailable(context, selected)) {
                    context.getString(R.string.settings_update_action_patch)
                } else {
                    context.getString(R.string.settings_update_action_reinstall)
                }
            }
            else -> context.getString(R.string.settings_update_action_install)
        }
    }

    fun actionEnabled(): Boolean = when {
        loading -> false
        loadFailed -> true
        else -> selected?.apkAsset() != null
    }

    fun downloadingPhaseText(paused: Boolean, percent: Int?, viaSystem: Boolean): String =
        updateButtonPhaseText(
            context = context,
            phase = RemoteUpdatePhase.DOWNLOADING,
            paused = paused,
            percent = percent,
            viaSystem = viaSystem,
            skipInstall = false,
            silentInstall = false,
            reopenAfterUpdate = false,
        )

    fun runInstall() {
        val release = selected ?: return
        if (displayBusy || loading || release.apkAsset() == null) return
        val forceReinstall = GitHubReleaseNotes.isSameVersionTuple(release.tagName, installed) &&
            !GitHubReleaseNotes.sameVersionPublisherUpdateAvailable(context, release)
        val skipInstall = GitHubReleaseNotes.isClearlyOlder(release.tagName, installed)
        busy = true
        currentPhase = RemoteUpdatePhase.CHECKING
        phaseText = context.getString(R.string.settings_update_phase_checking)
        progress = 0f
        scope.launch(Dispatchers.IO) {
            val policy = UpdateInstallPolicy.load(context).copy(allowDowngrade = true)
            val ok = AppUpdater.performReleaseUpdate(
                context,
                release,
                policy,
                forceReinstall = forceReinstall,
                skipInstall = skipInstall,
            ) { p ->
                currentPhase = p.phase
                if (p.phase == RemoteUpdatePhase.DOWNLOADING) {
                    downloadViaSystem = policy.downloadMethod == UpdateDownloadMethod.SYSTEM
                }
                phaseText = updateButtonPhaseText(
                    context = context,
                    phase = p.phase,
                    paused = p.paused,
                    percent = p.percent,
                    viaSystem = policy.downloadMethod == UpdateDownloadMethod.SYSTEM,
                    skipInstall = skipInstall,
                    silentInstall = p.silentInstall,
                    reopenAfterUpdate = policy.reopenAfterUpdate,
                )
                progress = (p.percent ?: 0).coerceIn(0, 100) / 100f
            }
            withContext(Dispatchers.Main) {
                if (!ok && phaseText.isBlank()) {
                    phaseText = context.getString(R.string.settings_update_phase_failed)
                }
                busy = false
                currentPhase = if (ok) RemoteUpdatePhase.DONE else RemoteUpdatePhase.FAILED
                if (ok && skipInstall) {
                    phaseText = context.getString(R.string.settings_update_phase_downloaded)
                    downgradeDialog.openDialog()
                }
            }
        }
    }

    LaunchedEffect(downloadPaused, busy, currentPhase) {
        if (!busy || currentPhase != RemoteUpdatePhase.DOWNLOADING) return@LaunchedEffect
        phaseText = downloadingPhaseText(
            paused = downloadPaused,
            percent = (progress * 100).toInt(),
            viaSystem = downloadViaSystem,
        )
    }

    LaunchedEffect(liveProgress, busy, updateSettings) {
        val viaSystem = updateSettings.resolvedDownloadMethod() == UpdateDownloadMethod.SYSTEM
        val working = when (liveProgress.phase) {
            RemoteUpdatePhase.DOWNLOADING,
            RemoteUpdatePhase.VERIFYING,
            RemoteUpdatePhase.INSTALLING,
            RemoteUpdatePhase.REBOOTING -> true
            else -> false
        }
        if (working) {
            if (!busy) sawLiveWork = true
            currentPhase = liveProgress.phase
            downloadViaSystem = viaSystem
            progress = (liveProgress.percent ?: if (liveProgress.phase == RemoteUpdatePhase.DOWNLOADING) 0 else 100)
                .coerceIn(0, 100) / 100f
            phaseText = updateButtonPhaseText(
                context = context,
                phase = liveProgress.phase,
                paused = liveProgress.paused,
                percent = liveProgress.percent,
                viaSystem = viaSystem,
                skipInstall = false,
                silentInstall = liveProgress.silentInstall,
                reopenAfterUpdate = updateSettings.reopenAfterUpdate,
            )
            return@LaunchedEffect
        }
        if (busy || !sawLiveWork) return@LaunchedEffect
        currentPhase = liveProgress.phase
        progress = if (liveProgress.phase == RemoteUpdatePhase.DONE) 1f else 0f
        phaseText = updateButtonPhaseText(
            context = context,
            phase = liveProgress.phase,
            paused = liveProgress.paused,
            percent = liveProgress.percent,
            viaSystem = viaSystem,
            skipInstall = false,
            silentInstall = liveProgress.silentInstall,
            reopenAfterUpdate = updateSettings.reopenAfterUpdate,
        )
        sawLiveWork = false
    }

    val stickyDone = !displayBusy && currentPhase == RemoteUpdatePhase.DONE && phaseText.isNotBlank()
    val stickyFail = !displayBusy && currentPhase == RemoteUpdatePhase.FAILED && phaseText.isNotBlank()
    val displayPhase = if (liveWorking) liveProgress.phase else currentPhase
    val pauseable = displayBusy && displayPhase == RemoteUpdatePhase.DOWNLOADING

    // Dedicated immersive shell. SettingsDetailScreen can't host this design:
    // its Scaffold pushes the list below the app bar (LazyColumn clips on the
    // scroll axis, so nothing can bleed up under the nav) and its horizontal
    // contentPadding leaves only ~30dp of cross-axis draw room. Here the list
    // is truly edge-to-edge and the nav bar floats transparently on the cover.
    val pageBg = if (isDarkMode) Color.Black else PureWhiteBackground
    // Nav floats on the dark masthead cover in both themes — keep back + title white.
    val headerTextColor = Color.White
    val sidePad = settingsListHorizontalPadding()
    val verticalPad = settingsListVerticalPadding()
    ImmersiveMode(isLandscape = landscape)

    SettingsSidebarDrawerWrapper(navController) {
        Box(modifier = Modifier.fillMaxSize().background(pageBg)) {
            var headerHeightPx by remember { mutableIntStateOf(0) }
            val headerHeight = with(LocalDensity.current) { headerHeightPx.toDp() }
            // No Scaffold anymore, so consume the gesture-nav inset ourselves.
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            // The three cards, shared between the portrait list and the
            // landscape split so parameter plumbing lives in one place.
            val notesCard: @Composable (Modifier, Boolean) -> Unit = { cardModifier, fillHeight ->
                SoftwareUpdateNotesCard(
                    selected = selected,
                    loading = loading,
                    loadFailed = loadFailed,
                    landscape = landscape,
                    isDarkMode = isDarkMode,
                    notesBg = notesBg,
                    notesColor = notesColor,
                    emptyNotes = emptyNotes,
                    modifier = cardModifier,
                    fillHeight = fillHeight,
                )
            }
            val installCard: @Composable (Modifier, Boolean, (@Composable () -> Unit)?) -> Unit =
                { cardModifier, fillHeight, embeddedPrefs ->
                    SoftwareUpdateInstallCard(
                        selected = selected,
                        selectedTag = selectedTag,
                        releases = releases,
                        installed = installed,
                        busy = displayBusy,
                        paused = downloadPaused,
                        pauseable = pauseable,
                        stickyDone = stickyDone,
                        stickyFail = stickyFail,
                        phaseText = phaseText,
                        progress = progress,
                        landscape = landscape,
                        isDarkMode = isDarkMode,
                        accent = accent,
                        subColor = subColor,
                        actionLabel = actionLabel(),
                        actionEnabled = actionEnabled(),
                        onSelectTag = {
                            selectedTag = it
                            if (!displayBusy) {
                                phaseText = ""
                                currentPhase = RemoteUpdatePhase.IDLE
                                progress = 0f
                            }
                        },
                        onInstall = {
                            when {
                                loadFailed -> reloadKey++
                                pauseable -> AppUpdater.toggleDownloadPause(context)
                                stickyFail -> runInstall()
                                !displayBusy && !stickyDone -> runInstall()
                            }
                        },
                        modifier = cardModifier,
                        fillHeight = fillHeight,
                        // Portrait: third-level entry. Landscape: prefs merge
                        // into this same card with an inner scroll pane.
                        onOpenPrefs = if (landscape || embeddedPrefs != null) {
                            null
                        } else {
                            {
                                navController.navigate(Screen.SETTINGS_SOFTWARE_UPDATE_PREFS) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        embeddedPrefs = embeddedPrefs,
                    )
                }
            if (landscape) {
                // Split view: cover + changelog on the left; one merged install
                // + scrollable-settings card on the right, edge-aligned.
                SoftwareUpdateLandscapeLayout(
                    hasRelease = true,
                    art = mastheadArt,
                    accent = accent,
                    topOverlap = headerHeight,
                    sidePad = sidePad,
                    verticalPad = verticalPad,
                    pageBg = pageBg,
                    notesCard = notesCard,
                    installCard = installCard,
                    prefsContent = { WiredSoftwareUpdatePrefsContent(viewModel) },
                )
            } else {
                // Portrait is a locked, non-scrolling stack: adaptive cover →
                // changelog fills the middle → install card pinned at the
                // bottom. The switches live behind the entry inside the
                // install card (third-level page).
                SoftwareUpdatePortraitLayout(
                    hasRelease = true,
                    art = mastheadArt,
                    accent = accent,
                    topOverlap = headerHeight,
                    sidePad = sidePad,
                    bottomPad = verticalPad + bottomInset,
                    pageBg = pageBg,
                    notesCard = notesCard,
                    installCard = { installCard(Modifier, false, null) },
                )
            }

            // Floating nav, always transparent: neither orientation scrolls
            // content under the bar anymore.
            SettingsHeaderBar(
                title = stringResource(R.string.settings_software_update),
                titleColor = headerTextColor,
                containerColor = Color.Transparent,
                onBack = { navController.safePopBackStack() },
                isLandscape = landscape,
                windowInsets = avaTopBarWindowInsets(landscape),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { headerHeightPx = it.height },
            )
        }

        if (downgradeOpen) {
            val targetTag = selected?.tagName?.ifBlank { selectedTag } ?: selectedTag
            downgradeDialog.ActionDialog(
                title = stringResource(R.string.settings_update_downgrade_title, targetTag),
                description = stringResource(R.string.settings_update_downgrade_message),
                confirmLabel = stringResource(R.string.settings_update_downgrade_uninstall),
                dismissLabel = stringResource(R.string.settings_update_downgrade_later),
                maxWidth = 400.dp,
                onConfirmRequest = {
                    val intent = Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    runCatching { context.startActivity(intent) }
                },
            ) {
                DowngradeInstallSteps(
                    accent = accent,
                    isDarkMode = isDarkMode,
                    exporting = exportingJson,
                    onExport = {
                        if (exportingJson) return@DowngradeInstallSteps
                        exportingJson = true
                        scope.launch {
                            try {
                                AvaBackupManager.export(context, AvaBackupIncludeOptions())
                                    .onSuccess { export ->
                                        context.startActivity(
                                            Intent.createChooser(
                                                AvaBackupManager.createShareIntent(
                                                    context,
                                                    export.file,
                                                ),
                                                context.getString(
                                                    R.string.settings_update_downgrade_export,
                                                ),
                                            ),
                                        )
                                    }
                                    .onFailure {
                                        AvaToast.show(
                                            context,
                                            context.getString(
                                                R.string.settings_backup_restore_export_failed,
                                            ),
                                        )
                                    }
                            } finally {
                                exportingJson = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DowngradeInstallSteps(
    accent: Color,
    isDarkMode: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
) {
    val labelColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)
    val steps = listOf(
        stringResource(R.string.settings_update_downgrade_step_export),
        stringResource(R.string.settings_update_downgrade_step_uninstall),
        stringResource(R.string.settings_update_downgrade_step_install),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 4.dp else 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${index + 1}",
                    color = accent,
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
                Text(
                    text = text,
                    color = labelColor,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedButton(
            onClick = onExport,
            enabled = !exporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.dp,
                if (exporting) accent.copy(alpha = 0.4f) else accent,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accent,
                disabledContentColor = accent.copy(alpha = 0.55f),
            ),
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_update_downgrade_exporting),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_update_downgrade_export),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Same installed release for picker marks / muted action — version tuple, not raw string. */
private fun isInstalledTag(tag: String, installed: String): Boolean =
    GitHubReleaseNotes.isSameVersionTuple(tag, installed)

private fun pickSettingsTag(
    list: List<GitHubReleaseNotes.Release>,
    current: String,
): String {
    if (current.isNotEmpty() && list.any { it.tagName == current }) return current
    val latestStable = list.firstOrNull { !it.prerelease && !it.isBetaChannel() }
        ?: list.firstOrNull()
    return latestStable?.tagName.orEmpty()
}

private fun settingsReleaseFingerprint(list: List<GitHubReleaseNotes.Release>): List<String> =
    list.map { release ->
        "${release.tagName}\u0000${ReleaseCoverArt.extractCoverUrl(release.fullBody).orEmpty()}"
    }

private fun updateButtonPhaseText(
    context: android.content.Context,
    phase: RemoteUpdatePhase,
    paused: Boolean,
    percent: Int?,
    viaSystem: Boolean,
    skipInstall: Boolean,
    silentInstall: Boolean,
    reopenAfterUpdate: Boolean,
): String {
    val pct = percent?.coerceIn(0, 100)
    return when (phase) {
        RemoteUpdatePhase.DOWNLOADING -> when {
            paused -> context.getString(R.string.settings_update_phase_paused)
            viaSystem && pct == null -> context.getString(R.string.settings_update_phase_system)
            viaSystem -> context.getString(R.string.settings_update_phase_system_pct, pct)
            pct == null -> context.getString(R.string.settings_update_phase_downloading)
            else -> context.getString(R.string.settings_update_phase_downloading_pct, pct)
        }
        RemoteUpdatePhase.CHECKING, RemoteUpdatePhase.IDLE ->
            context.getString(R.string.settings_update_phase_checking)
        RemoteUpdatePhase.VERIFYING ->
            context.getString(R.string.settings_update_phase_verifying)
        RemoteUpdatePhase.INSTALLING ->
            context.getString(R.string.settings_update_phase_installing)
        RemoteUpdatePhase.REBOOTING ->
            context.getString(R.string.settings_update_phase_rebooting)
        RemoteUpdatePhase.DONE -> when {
            skipInstall -> context.getString(R.string.settings_update_phase_downloaded)
            !silentInstall -> context.getString(R.string.settings_update_phase_confirm)
            reopenAfterUpdate -> context.getString(R.string.settings_update_phase_reopening)
            else -> context.getString(R.string.settings_update_phase_done)
        }
        RemoteUpdatePhase.FAILED ->
            context.getString(R.string.settings_update_phase_failed)
    }
}

/** Strip images + Markdown markers; one blank line between paragraphs. */
private fun releaseNotesPlain(body: String?): String {
    if (body.isNullOrBlank()) return ""
    val text = body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^```.*$"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^>\\s?"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        .replace(Regex("(?m)^(?:-{3,}|\\*{3,}|_{3,})\\s*$"), "")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("~~([^~]+)~~"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("(?<!\\w)\\*([^*]+)\\*(?!\\w)"), "$1")
        .replace(Regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace('\u00A0', ' ')

    // Split on blank runs → paragraphs; keep a single blank line between them
    return text
        .split(Regex("\n{2,}"))
        .map { block ->
            block.lines()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
}

/** Fallback banner aspect when a release has no cover yet (matches Ava's 2585×956). */
private const val DEFAULT_COVER_RATIO = 2585f / 956f

/**
 * Portrait: a locked, non-scrolling stack. The cover height adapts to the
 * screen (capped fraction of the height, ratio never crops), the changelog
 * absorbs whatever remains, and the install card is pinned at the bottom —
 * everything always fits without scrolling.
 */
@Composable
private fun SoftwareUpdatePortraitLayout(
    hasRelease: Boolean,
    art: ReleaseCoverArt.Art?,
    accent: Color,
    topOverlap: Dp,
    sidePad: Dp,
    bottomPad: Dp,
    pageBg: Color,
    notesCard: @Composable (Modifier, Boolean) -> Unit,
    installCard: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val ratio = (art?.ratio ?: DEFAULT_COVER_RATIO).coerceIn(0.5f, 4f)
        val topSpace = 16.dp
        // Locked-height adaptive sizing: every release uses the same content
        // width; height follows the ratio but never exceeds ~28% of the screen
        // so a tall banner cannot shove the install card off-screen.
        // No extra under-cover gap — notes Surface `.padding(vertical = 8.dp)`
        // already provides the same stack spacing as landscape.
        val maxCoverHeight = minOf(220.dp, maxHeight * 0.28f)
        val coverWidth = (maxWidth - sidePad * 2).coerceAtLeast(0.dp)
        val coverHeight = (coverWidth / ratio).coerceAtMost(maxCoverHeight)
        val blockHeight = topOverlap + topSpace + coverHeight

        if (hasRelease) {
            // Backdrop lives on its own layer, running past the cover block so
            // the dissolve keeps flowing down behind the changelog card.
            MastheadBackdrop(
                art = art,
                accent = accent,
                pageBg = pageBg,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(blockHeight + 72.dp),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (hasRelease) {
                Box(modifier = Modifier.fillMaxWidth().height(blockHeight)) {
                    MastheadCoverCard(
                        art = art,
                        coverWidth = coverWidth,
                        coverHeight = coverHeight,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topOverlap + topSpace),
                    )
                }
            } else {
                // Keep the first card clear of the floating nav bar.
                Spacer(modifier = Modifier.height(topOverlap + 8.dp))
            }
            // Changelog absorbs the leftover height; its text scrolls inside.
            notesCard(
                Modifier
                    .weight(1f)
                    .padding(horizontal = sidePad),
                true,
            )
            Box(modifier = Modifier.padding(horizontal = sidePad)) { installCard() }
            Spacer(modifier = Modifier.height(bottomPad))
        }
    }
}

/**
 * Landscape split: ambient backdrop bleeds behind both columns. Left pins the
 * cover with the changelog filling the rest; right is one merged install +
 * settings card whose switches pane scrolls, so both columns stay edge-aligned.
 */
@Composable
private fun SoftwareUpdateLandscapeLayout(
    hasRelease: Boolean,
    art: ReleaseCoverArt.Art?,
    accent: Color,
    topOverlap: Dp,
    sidePad: Dp,
    verticalPad: Dp,
    pageBg: Color,
    notesCard: @Composable (Modifier, Boolean) -> Unit,
    installCard: @Composable (Modifier, Boolean, (@Composable () -> Unit)?) -> Unit,
    prefsContent: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val colGap = 14.dp
        val colWidth = (maxWidth - sidePad * 2 - colGap) / 2
        val ratio = (art?.ratio ?: DEFAULT_COVER_RATIO).coerceIn(0.5f, 4f)
        val topSpace = 10.dp
        // Same outer gap as install/notes Surface `.padding(vertical = 8.dp)` —
        // without it the cover sits 8.dp above the right card's top edge.
        val cardOuterPad = 8.dp
        // Fixed column width for every release; height follows ratio, capped so
        // a square/tall cover cannot blow out the left column.
        val coverWidth = if (hasRelease) colWidth else 0.dp
        val coverHeight = if (hasRelease) {
            (coverWidth / ratio).coerceAtMost(160.dp)
        } else {
            0.dp
        }
        // Shared bottom inset so left changelog and right merged card end together.
        val colBottom = (verticalPad - cardOuterPad).coerceAtLeast(0.dp)

        if (hasRelease) {
            MastheadBackdrop(
                art = art,
                accent = accent,
                pageBg = pageBg,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topOverlap + topSpace + cardOuterPad + coverHeight + 24.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = sidePad, end = sidePad, top = topOverlap + topSpace),
            horizontalArrangement = Arrangement.spacedBy(colGap),
        ) {
            // Left: cover pinned on the artwork, changelog fills the remainder.
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (hasRelease) {
                    MastheadCoverCard(
                        art = art,
                        coverWidth = coverWidth,
                        coverHeight = coverHeight,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = cardOuterPad),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                notesCard(Modifier.weight(1f).padding(bottom = colBottom), true)
            }
            // Right: one full-height card — install pinned, settings scroll inside.
            installCard(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(bottom = colBottom),
                true,
                prefsContent,
            )
        }
    }
}

/** Screen-wide blurred artwork (or accent gradient) dissolving into the page. */
@Composable
private fun MastheadBackdrop(
    art: ReleaseCoverArt.Art?,
    accent: Color,
    pageBg: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Crossfade(targetState = art, label = "mastheadAmbient") { current ->
            val ambientImage = remember(current) {
                current?.ambient?.takeUnless { it.isRecycled }
                    ?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            }
            if (ambientImage != null) {
                Image(
                    bitmap = ambientImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accent.copy(alpha = 0.42f),
                                    Color(0xFF17181B),
                                    Color(0xFF2A3550).copy(alpha = 0.85f),
                                ),
                            ),
                        ),
                )
            }
        }
        // Gentle top darken for depth + bottom dissolve into the page.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.45f to Color.Transparent,
                        0.80f to pageBg.copy(alpha = 0.55f),
                        1f to pageBg,
                    ),
                ),
        )
    }
}

/**
 * Cover card at a fixed [coverWidth]. Height is chosen by the caller (ratio,
 * capped). [ContentScale.FillWidth] keeps every release the same visual width;
 * when height is capped a taller image is clipped vertically instead of
 * shrinking narrower and breaking column alignment.
 */
@Composable
private fun MastheadCoverCard(
    art: ReleaseCoverArt.Art?,
    coverWidth: Dp,
    coverHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val artShape = RoundedCornerShape(16.dp)
    Crossfade(targetState = art, label = "mastheadCover", modifier = modifier) { current ->
        val sharpImage = remember(current) {
            current?.sharp?.takeUnless { it.isRecycled }
                ?.let { runCatching { it.asImageBitmap() }.getOrNull() }
        }
        if (sharpImage != null) {
            Image(
                bitmap = sharpImage,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .width(coverWidth)
                    .height(coverHeight)
                    .shadow(14.dp, artShape)
                    .clip(artShape),
            )
        } else {
            MastheadCoverPlaceholder(
                coverWidth = coverWidth,
                coverHeight = coverHeight,
            )
        }
    }
}

/** Soft dissolve occupying the cover slot — no card chrome, just a gentle join. */
@Composable
private fun MastheadCoverPlaceholder(
    coverWidth: Dp,
    coverHeight: Dp,
) {
    Box(
        modifier = Modifier
            .width(coverWidth)
            .height(coverHeight)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.10f),
                    0.45f to Color.White.copy(alpha = 0.04f),
                    1f to Color.Transparent,
                ),
            ),
    )
}

@Composable
private fun SoftwareUpdateNotesCard(
    selected: GitHubReleaseNotes.Release?,
    loading: Boolean,
    loadFailed: Boolean,
    landscape: Boolean,
    isDarkMode: Boolean,
    notesBg: Color,
    notesColor: Color,
    emptyNotes: String,
    modifier: Modifier = Modifier,
    /** Landscape split: fill the given height and let the notes scroll inside. */
    fillHeight: Boolean = false,
) {
    val notes = releaseNotesPlain(selected?.fullBody)
    val cardBg = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF3F4F6)
    // Header (icon / version / "changelog" title) removed — body only.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(if (landscape) 18.dp else 20.dp),
        color = cardBg,
        shadowElevation = 0.dp,
        border = if (isDarkMode) BorderStroke(1.dp, Color(0xFF2A2A2A)) else null,
    ) {
        Column(
            modifier = Modifier
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .padding(
                    start = if (landscape) 12.dp else 14.dp,
                    end = if (landscape) 12.dp else 14.dp,
                    top = if (landscape) 12.dp else 14.dp,
                    bottom = if (landscape) 10.dp else 12.dp,
                ),
        ) {
            val notesCorner = RoundedCornerShape(if (landscape) 9.dp else 10.dp)
            val notesPadH = if (landscape) 9.dp else 10.dp
            val notesPadV = if (landscape) 7.dp else 8.dp
            val notesBody: @Composable () -> Unit = {
                Text(
                    text = when {
                        loading -> stringResource(R.string.settings_update_loading)
                        loadFailed -> stringResource(R.string.settings_update_load_failed)
                        notes.isBlank() -> emptyNotes
                        else -> notes
                    },
                    color = notesColor,
                    fontSize = if (landscape) 11.sp else 11.5.sp,
                    lineHeight = if (landscape) (11 * 1.42f).sp else (11.5f * 1.45f).sp,
                    letterSpacing = (-0.1).sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Soft dissolve at the scroll edges (same DstIn mask as other settings panes).
            if (fillHeight) {
                SettingsEdgeFadeScrollColumn(
                    modifier = Modifier
                        .weight(1f)
                        .clip(notesCorner)
                        .background(notesBg)
                        .padding(horizontal = notesPadH, vertical = notesPadV),
                    fadeHeight = 18.dp,
                ) {
                    notesBody()
                }
            } else {
                SettingsEdgeFadeScrollColumn(
                    maxHeight = if (landscape) 168.dp else 96.dp,
                    modifier = Modifier
                        .clip(notesCorner)
                        .background(notesBg)
                        .padding(horizontal = notesPadH, vertical = notesPadV),
                    fadeHeight = 16.dp,
                ) {
                    notesBody()
                }
            }
        }
    }
}

@Composable
private fun SoftwareUpdateInstallCard(
    selected: GitHubReleaseNotes.Release?,
    selectedTag: String,
    releases: List<GitHubReleaseNotes.Release>,
    installed: String,
    busy: Boolean,
    paused: Boolean,
    pauseable: Boolean,
    stickyDone: Boolean,
    stickyFail: Boolean,
    phaseText: String,
    progress: Float,
    landscape: Boolean,
    isDarkMode: Boolean,
    accent: Color,
    subColor: Color,
    actionLabel: String,
    actionEnabled: Boolean,
    onSelectTag: (String) -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    /** Landscape merged card: fill the right column height. */
    fillHeight: Boolean = false,
    /** Portrait: opens the third-level update-options page; null hides the entry. */
    onOpenPrefs: (() -> Unit)? = null,
    /** Landscape: switch rows embedded below a divider, scrolling inside the card. */
    embeddedPrefs: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var showPicker by remember { mutableStateOf(false) }
    var pickHeightPx by remember { mutableIntStateOf(0) }
    var pickWidthPx by remember { mutableIntStateOf(0) }
    var pickTopInWindowPx by remember { mutableIntStateOf(0) }
    var pickBottomInWindowPx by remember { mutableIntStateOf(0) }
    val date = selected?.publishedAt?.take(10).orEmpty()
    val border = if (isDarkMode) Color(0xFF333333) else Color(0xFFE2E8F0)
    // Mockup .card.install / .ver-pick / .action / .btn
    val cardBg = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val pickBg = if (isDarkMode) Color(0xFF000000) else Color(0xFFF9FAFB)
    val menuBg = cardBg
    val labelColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)
    val cardPadH = if (landscape) 16.dp else 18.dp
    val cardPadV = if (landscape) 14.dp else 16.dp
    val actionTop = if (landscape) 12.dp else 14.dp
    val btnFont = if (landscape) 14.sp else 15.sp
    val btnPadV = if (landscape) 11.dp else 13.dp
    // One picker row ≈ 12+12 padding + 14sp. Cap visible rows by orientation,
    // then shrink further to the space left below the trigger (portrait card
    // sits on the bottom — a fixed 232.dp menu was covering the action button).
    val pickerRowH = 46.dp
    val pickerMaxRows = if (landscape) 4 else 3
    val screenHpx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val pickerGapPx = with(density) { 8.dp.toPx() }
    val remainingBelow = with(density) {
        (screenHpx - pickBottomInWindowPx - navBottomPx - pickerGapPx)
            .coerceAtLeast(0f)
            .toDp()
    }
    val remainingAbove = with(density) {
        (pickTopInWindowPx - statusTopPx - pickerGapPx)
            .coerceAtLeast(0f)
            .toDp()
    }
    val openPickerUpward = !landscape &&
        remainingBelow < pickerRowH * 2 &&
        remainingAbove > remainingBelow
    val menuMaxH = minOf(
        pickerRowH * pickerMaxRows,
        if (openPickerUpward) remainingAbove else remainingBelow,
    ).coerceAtLeast(pickerRowH)
    val latest = releases.firstOrNull { !it.prerelease && !it.isBetaChannel() }?.tagName
    val versionPrefix = stringResource(R.string.settings_update_version, "\u0000")
        .substringBefore('\u0000')
        .trimEnd()
    // Mute only true reinstall (same bytes). Same-version SHA rebuild keeps accent.
    // In-flight and sticky end states keep accent so they don't look idle.
    val isSame = !busy &&
        !stickyDone &&
        !stickyFail &&
        selected != null &&
        isInstalledTag(selected.tagName, installed) &&
        !GitHubReleaseNotes.sameVersionPublisherUpdateAvailable(context, selected)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(if (landscape) 24.dp else 28.dp),
        color = cardBg,
        shadowElevation = if (isDarkMode) 0.dp else 1.dp,
        border = if (isDarkMode) BorderStroke(1.dp, Color(0xFF2A2A2A)) else null,
    ) {
        Column(
            modifier = Modifier
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = cardPadH, vertical = cardPadV),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            pickHeightPx = coords.size.height
                            pickWidthPx = coords.size.width
                            val bounds = coords.boundsInWindow()
                            pickTopInWindowPx = bounds.top.toInt()
                            pickBottomInWindowPx = bounds.bottom.toInt()
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, border, RoundedCornerShape(14.dp))
                        .background(pickBg)
                        .clickable(enabled = !busy && releases.isNotEmpty()) {
                            showPicker = !showPicker
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = versionPrefix,
                            color = subColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Text(
                            text = selectedTag.ifBlank { "—" },
                            color = subColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (date.isNotBlank()) {
                        Text(
                            text = date,
                            color = subColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    SettingsChevronIcon(tint = subColor, base = 20f)
                }
                if (showPicker && pickWidthPx > 0) {
                    val menuGapPx = with(density) { 6.dp.roundToPx() }
                    val pickerAlignment: Alignment
                    val menuOffsetY: Int
                    if (openPickerUpward) {
                        pickerAlignment = Alignment.BottomStart
                        menuOffsetY = -pickHeightPx - menuGapPx
                    } else {
                        pickerAlignment = Alignment.TopStart
                        menuOffsetY = pickHeightPx + menuGapPx
                    }
                    Popup(
                        alignment = pickerAlignment,
                        offset = IntOffset(0, menuOffsetY),
                        onDismissRequest = { showPicker = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(with(density) { pickWidthPx.toDp() })
                                .heightIn(max = menuMaxH)
                                .shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(16.dp),
                                    ambientColor = Color(0x2E0F172A),
                                    spotColor = Color(0x2E0F172A),
                                ),
                            shape = RoundedCornerShape(16.dp),
                            color = menuBg,
                            border = BorderStroke(1.dp, border),
                        ) {
                            SettingsEdgeFadeScrollColumn(
                                maxHeight = menuMaxH,
                                fadeHeight = 16.dp,
                                verticalArrangement = Arrangement.Top,
                            ) {
                                releases.forEach { release ->
                                    val mark = when {
                                        isInstalledTag(release.tagName, installed) ->
                                            context.getString(R.string.settings_update_mark_current)
                                        release.tagName == latest ->
                                            context.getString(R.string.settings_update_mark_latest)
                                        release.prerelease || release.isBetaChannel() ->
                                            context.getString(R.string.settings_update_mark_pre)
                                        else -> ""
                                    }
                                    val pub = release.publishedAt.take(10)
                                    val selectedRow = release.tagName == selectedTag
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onSelectTag(release.tagName)
                                                showPicker = false
                                            }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = release.tagName,
                                            fontSize = 14.sp,
                                            fontWeight = if (selectedRow) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                            color = if (selectedRow) accent else labelColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (pub.isNotBlank()) {
                                            Text(
                                                text = pub,
                                                fontSize = 11.sp,
                                                color = subColor,
                                                maxLines = 1,
                                            )
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = mark,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = subColor,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Option C: full-width soft accent fill + accent label
            val failColor = if (isDarkMode) Color(0xFFF87171) else Color(0xFFDC2626)
            val actionColor = when {
                stickyFail -> failColor
                isSame -> getSettingsDescriptionColor()
                else -> accent
            }
            val actionBg = when {
                stickyFail -> failColor.copy(alpha = if (isDarkMode) 0.16f else 0.10f)
                isSame -> if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFF1F5F9)
                else -> accent.copy(alpha = if (isDarkMode) 0.14f else 0.08f)
            }
            val fillFraction by animateFloatAsState(
                targetValue = when {
                    stickyDone -> 1f
                    busy -> progress.coerceIn(0f, 1f)
                    else -> 0f
                },
                animationSpec = tween(durationMillis = 220),
                label = "update-btn-fill",
            )
            val fillColor = (if (stickyFail) failColor else accent)
                .copy(alpha = if (isDarkMode) 0.38f else 0.26f)
            val buttonActive = actionEnabled || busy || stickyDone || stickyFail
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = actionTop),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (buttonActive) actionBg else actionBg.copy(alpha = 0.45f))
                        .clickable(
                            enabled = pauseable || stickyFail || (actionEnabled && !busy && !stickyDone),
                            onClick = onInstall,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (fillFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .align(Alignment.CenterStart),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fillFraction)
                                    .background(fillColor),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(vertical = btnPadV, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (busy && !paused) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = actionColor,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (phaseText.isNotBlank()) phaseText else actionLabel,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = btnFont,
                            color = if (buttonActive) {
                                actionColor
                            } else {
                                subColor.copy(alpha = 0.45f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                CollapsibleDescriptionText(
                    text = stringResource(R.string.settings_update_downgrade_tip),
                    color = subColor,
                    fontSize = settingsCaptionTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    textAlign = TextAlign.Start,
                    topPadding = 10.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Entry to the third-level update-options page (portrait only).
            if (onOpenPrefs != null) {
                Spacer(modifier = Modifier.height(10.dp))
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenPrefs)
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_update_prefs_entry),
                            color = labelColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        CollapsibleDescriptionText(
                            text = stringResource(R.string.settings_update_prefs_entry_desc),
                            color = subColor,
                            fontSize = settingsCaptionTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            topPadding = 1.dp,
                        )
                    }
                    SettingsChevronIcon(tint = subColor, base = 20f)
                }
            }

            // Landscape: merge settings into this card; only the switch list scrolls,
            // with soft dissolve edges when content overflows.
            if (embeddedPrefs != null) {
                Spacer(modifier = Modifier.height(10.dp))
                SettingsDivider()
                SettingsEdgeFadeScrollColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    fadeHeight = 22.dp,
                ) {
                    embeddedPrefs()
                }
            }
        }
    }
}

/** Switch rows only — used inside the landscape merged card or wrapped in SimpleCard. */
@Composable
private fun SoftwareUpdatePrefsContent(
    updateSettings: UpdateSettings,
    downloadBuiltin: String,
    downloadBuiltinDesc: String,
    downloadSystem: String,
    downloadSystemDesc: String,
    onAutoUpdate: (Boolean) -> Unit,
    onCheckOnLaunch: (Boolean) -> Unit,
    onHaEntity: (Boolean) -> Unit,
    onReopen: (Boolean) -> Unit,
    onStartServices: (Boolean) -> Unit,
    onIgnore: (Boolean) -> Unit,
    onDownloadMethod: (UpdateDownloadMethod) -> Unit,
) {
    SettingRow(
        label = stringResource(R.string.settings_update_auto),
        subLabel = stringResource(R.string.settings_update_auto_desc),
    ) {
        ModernSwitch(
            checked = updateSettings.autoUpdate,
            onCheckedChange = onAutoUpdate,
        )
    }
    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_update_check_on_launch),
        subLabel = stringResource(R.string.settings_update_check_on_launch_desc),
    ) {
        ModernSwitch(
            checked = updateSettings.checkOnLaunch,
            onCheckedChange = onCheckOnLaunch,
        )
    }
    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_update_ha_entity),
        subLabel = stringResource(R.string.settings_update_ha_entity_desc),
    ) {
        ModernSwitch(
            checked = updateSettings.haUpdateEntity,
            onCheckedChange = onHaEntity,
        )
    }
    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_update_reopen),
        subLabel = stringResource(R.string.settings_update_reopen_desc),
    ) {
        ModernSwitch(
            checked = updateSettings.reopenAfterUpdate,
            onCheckedChange = onReopen,
        )
    }
    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_update_start_services),
        subLabel = stringResource(R.string.settings_update_start_services_desc),
    ) {
        ModernSwitch(
            checked = updateSettings.startServicesAfterInstall,
            onCheckedChange = onStartServices,
        )
    }
    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_update_ignore),
        subLabel = stringResource(R.string.settings_update_ignore_desc),
    ) {
        ModernSwitch(
            checked = updateSettings.ignoreUpdate,
            onCheckedChange = onIgnore,
        )
    }
    SettingsDivider()
    SelectSetting(
        name = stringResource(R.string.settings_update_download_method),
        description = when (updateSettings.resolvedDownloadMethod()) {
            UpdateDownloadMethod.SYSTEM -> downloadSystemDesc
            UpdateDownloadMethod.BUILTIN -> downloadBuiltinDesc
        },
        selected = updateSettings.resolvedDownloadMethod(),
        items = UpdateDownloadMethod.entries,
        key = { it.storageKey },
        value = {
            when (it) {
                UpdateDownloadMethod.SYSTEM -> downloadSystem
                UpdateDownloadMethod.BUILTIN, null -> downloadBuiltin
            }
        },
        itemDescription = {
            when (it) {
                UpdateDownloadMethod.SYSTEM -> downloadSystemDesc
                UpdateDownloadMethod.BUILTIN -> downloadBuiltinDesc
            }
        },
        onConfirmRequest = { method ->
            if (method != null) onDownloadMethod(method)
        },
    )
}

/** Built-in path: always open Ava's update dialog and check version.json. */
private fun openBuiltinUpdateUi(context: android.content.Context) {
    val launch = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_SHOW_UPDATE
        addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
    }
    context.startActivity(launch)
}

/**
 * Prefs wiring shared by the landscape merged card (content only) and the
 * portrait third-level page (wrapped in SimpleCard).
 */
@Composable
private fun WiredSoftwareUpdatePrefsContent(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateSettings by viewModel.updateSettingsState.collectAsStateWithLifecycle(UpdateSettings())
    SoftwareUpdatePrefsContent(
        updateSettings = updateSettings,
        downloadBuiltin = stringResource(R.string.settings_update_download_builtin),
        downloadBuiltinDesc = stringResource(R.string.settings_update_download_builtin_desc),
        downloadSystem = stringResource(R.string.settings_update_download_system),
        downloadSystemDesc = stringResource(R.string.settings_update_download_system_desc),
        onAutoUpdate = { scope.launch { viewModel.saveUpdateAutoUpdate(it) } },
        onCheckOnLaunch = { scope.launch { viewModel.saveUpdateCheckOnLaunch(it) } },
        onHaEntity = {
            scope.launch {
                viewModel.saveUpdateHaEntity(it)
                // updateModule.init()/dispose() only runs during satellite start().
                restartVoiceSatelliteServiceIfRunning(
                    com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE,
                )
            }
        },
        onReopen = { scope.launch { viewModel.saveUpdateReopenAfterUpdate(it) } },
        onStartServices = { scope.launch { viewModel.saveUpdateStartServicesAfterInstall(it) } },
        onIgnore = { scope.launch { viewModel.saveUpdateIgnoreUpdate(it) } },
        onDownloadMethod = { method ->
            scope.launch {
                viewModel.saveUpdateDownloadMethod(method)
                // Selecting built-in should immediately open the update checker UI.
                if (method == UpdateDownloadMethod.BUILTIN) {
                    withContext(Dispatchers.Main) { openBuiltinUpdateUi(context) }
                }
            }
        },
    )
}

@Composable
private fun WiredSoftwareUpdatePrefsCard(viewModel: SettingsViewModel) {
    SimpleCard { WiredSoftwareUpdatePrefsContent(viewModel) }
}

/** Third-level page behind the install-card entry: the update switches. */
@Composable
fun SoftwareUpdatePrefsSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_update_prefs_entry),
    ) {
        item { WiredSoftwareUpdatePrefsCard(viewModel) }
    }
}
