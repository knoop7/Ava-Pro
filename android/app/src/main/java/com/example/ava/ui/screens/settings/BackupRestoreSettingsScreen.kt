package com.example.ava.ui.screens.settings

import android.content.Intent
import android.net.Uri
import com.example.ava.ui.AvaToast
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.backup.AvaBackupManager
import com.example.ava.ui.screens.settings.components.*
import kotlinx.coroutines.launch

private enum class BackupRestoreTab { Clone, File }

private enum class BackupActionPhase {
    Idle,
    Send,
    Receive,
    ReceiveConfirm,
    ;

    val isSession: Boolean
        get() = this == Send || this == Receive || this == ReceiveConfirm
}

@Composable
fun BackupRestoreSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(BackupRestoreTab.Clone) }
    var fileOptions by remember {
        mutableStateOf(
            BackupIncludeMemory.load(context, BackupIncludeMemory.FILE)
                ?: AvaBackupManager.FILE_INCLUDE_OPTIONS,
        )
    }
    var cloneOptions by remember {
        mutableStateOf(
            BackupIncludeMemory.load(context, BackupIncludeMemory.CLONE)
                ?: AvaBackupManager.CLONE_INCLUDE_OPTIONS,
        )
    }
    var fileEffective by remember { mutableStateOf(fileOptions) }
    var cloneEffective by remember { mutableStateOf(cloneOptions) }
    var cloneCatalog by remember { mutableStateOf(cloneOptions) }
    var sendPayloadOptions by remember { mutableStateOf(cloneOptions) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var sendActive by remember { mutableStateOf(false) }
    var receiveActive by remember { mutableStateOf(false) }
    val receiveSession = rememberCloneReceiveSession(active = receiveActive) {
        receiveActive = false
    }

    fun closeSession() {
        sendActive = false
        receiveActive = false
    }

    BackHandler(enabled = sendActive || receiveActive) {
        closeSession()
    }

    fun persistReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistReadPermission(uri)
        importing = true
        coroutineScope.launch {
            val detectedOptions = AvaBackupManager.peekIncludes(context, uri).getOrElse {
                importing = false
                    AvaToast.show(
                        context,
                        context.getString(R.string.settings_backup_restore_import_failed),
                    )
                return@launch
            }
            fileOptions = detectedOptions
            val result = AvaBackupManager.import(context, uri, detectedOptions)
            importing = false
            if (result.success) {
                if (result.needsSatelliteRestart) {
                    restartVoiceSatelliteServiceIfRunning(
                        com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE,
                    )
                }
                    AvaToast.show(
                        context,
                        context.getString(R.string.settings_backup_restore_import_success),
                        tag = AvaToast.HA_SYNC_TAG,
                    )
            } else {
                AvaToast.show(
                    context,
                    context.getString(R.string.settings_backup_restore_import_failed),
                )
            }
        }
    }

    val confirmStep = receiveActive && receiveSession.confirmStep
    val dockClearance by animateDpAsState(
        targetValue = if (confirmStep) SettingsBottomDockClearance else 0.dp,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "clone_apply_dock_clearance",
    )
    val listState = rememberLazyListState()
    var pageReady by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        if (pageReady) {
            listState.scrollToItem(0)
        } else {
            pageReady = true
        }
    }
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_backup_restore),
        listState = listState,
        listExtraBottom = dockClearance,
        bottomOverlay = {
            CloneReceiveApplyDock(
                session = receiveSession,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    ) {
        item {
            SimpleCard {
                val contentWidthModifier = rememberBackupActionContentWidthModifier()
                val phase = when {
                    sendActive -> BackupActionPhase.Send
                    receiveActive && receiveSession.confirmStep -> BackupActionPhase.ReceiveConfirm
                    receiveActive -> BackupActionPhase.Receive
                    else -> BackupActionPhase.Idle
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .zIndex(1f),
                    ) {
                        AnimatedVisibility(
                            visible = phase.isSession,
                            enter = fadeIn(tween(durationMillis = 220, delayMillis = 120)),
                            exit = fadeOut(tween(120)),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.voice_message_close),
                                tint = getSettingsDescriptionColor(),
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .size(22.dp)
                                    .settingsClickable(onClick = { closeSession() }),
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = phase,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        transitionSpec = { backupCardTransition() },
                        contentAlignment = Alignment.TopCenter,
                        label = "backup_action_card",
                    ) { current ->
                        Column(
                            modifier = contentWidthModifier,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (current) {
                                BackupActionPhase.Idle -> {
                                    BackupSegmentedControl(
                                        selected = selectedTab,
                                        onSelect = { tab ->
                                            if (tab != BackupRestoreTab.Clone) closeSession()
                                            selectedTab = tab
                                        },
                                    )
                                    Spacer(modifier = Modifier.height(18.dp))
                                    AnimatedContent(
                                        targetState = selectedTab,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clipToBounds(),
                                        transitionSpec = { backupTabSlide() },
                                        contentAlignment = Alignment.TopCenter,
                                        label = "backup_idle_tab",
                                    ) { tab ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            BackupIdleTabBody(
                                                tab = tab,
                                                exporting = exporting,
                                                importing = importing,
                                                onSend = {
                                                    if (!cloneEffective.hasAnyInclude()) return@BackupIdleTabBody
                                                    receiveActive = false
                                                    sendPayloadOptions = cloneEffective
                                                    AvaBackupManager.cloneSendOptions = cloneEffective
                                                    AvaBackupManager.cloneSendCatalog = cloneCatalog
                                                    sendActive = true
                                                },
                                                onReceive = {
                                                    sendActive = false
                                                    receiveActive = true
                                                },
                                                onExport = {
                                                    if (exporting || importing) return@BackupIdleTabBody
                                                    if (!fileEffective.hasAnyInclude()) return@BackupIdleTabBody
                                                    exporting = true
                                                    coroutineScope.launch {
                                                        val result = AvaBackupManager.export(context, fileEffective)
                                                        exporting = false
                                                        result.onSuccess { export ->
                                                            val shareIntent = AvaBackupManager.createShareIntent(
                                                                context,
                                                                export.file,
                                                            )
                                                            context.startActivity(
                                                                Intent.createChooser(
                                                                    shareIntent,
                                                                    context.getString(
                                                                        R.string.settings_backup_restore_export_button,
                                                                    ),
                                                                ),
                                                            )
                                                        }.onFailure {
                                                            AvaToast.show(
                                                                context,
                                                                context.getString(
                                                                    R.string.settings_backup_restore_export_failed,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                },
                                                onImport = {
                                                    if (!importing && !exporting) {
                                                        importLauncher.launch("application/json")
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                BackupActionPhase.Send -> {
                                    CloneSendPanel(
                                        options = sendPayloadOptions,
                                        catalog = cloneCatalog,
                                        onClose = { closeSession() },
                                    )
                                }
                                BackupActionPhase.Receive -> {
                                    CloneReceiveDiscoverContent(receiveSession)
                                }
                                BackupActionPhase.ReceiveConfirm -> {
                                    CloneReceiveConfirmContent(receiveSession)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            val includePanel = when {
                receiveActive && receiveSession.confirmStep -> "recv-apply"
                receiveActive -> "recv-code"
                else -> "includes"
            }
            AnimatedContent(
                targetState = includePanel,
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds(),
                contentAlignment = Alignment.TopCenter,
                transitionSpec = { backupIncludeTransition() },
                label = "backup_include_panel",
            ) { panel ->
                when (panel) {
                    "recv-apply" -> {
                        BackupIncludePicker(
                            options = receiveSession.applyOptions,
                            onChange = { receiveSession.applyOptions = it },
                            available = receiveSession.available,
                            catalog = receiveSession.catalog,
                            enabled = !receiveSession.applying,
                            titleRes = R.string.settings_backup_clone_apply_includes_title,
                        )
                    }
                    "recv-code" -> {
                        SimpleCard {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(modifier = rememberBackupActionContentWidthModifier()) {
                                    CloneReceiveCodeContent(receiveSession)
                                }
                            }
                        }
                    }
                    else -> {
                        val fileTab = selectedTab == BackupRestoreTab.File
                        BackupIncludePicker(
                            options = if (fileTab) fileOptions else cloneOptions,
                            onChange = if (sendActive) {
                                null
                            } else { options ->
                                if (fileTab) fileOptions = options else cloneOptions = options
                            },
                            enabled = !exporting && !importing && !sendActive,
                            showIdentityNote = true,
                            showTransferNote = true,
                            onEffectiveOptions = { packed ->
                                if (fileTab) fileEffective = packed else cloneEffective = packed
                            },
                            onCatalog = { shown ->
                                if (!fileTab) cloneCatalog = shown
                            },
                            memoryKey = if (fileTab) {
                                BackupIncludeMemory.FILE
                            } else {
                                BackupIncludeMemory.CLONE
                            },
                        )
                    }
                }
            }
        }
    }
    CloneReceiveDialogs(receiveSession)
}

private val BackupCardSizeSpec = tween<IntSize>(
    durationMillis = 420,
    easing = FastOutSlowInEasing,
)

private fun AnimatedContentTransitionScope<BackupActionPhase>.backupCardTransition(): ContentTransform {
    val confirm = targetState == BackupActionPhase.ReceiveConfirm ||
        initialState == BackupActionPhase.ReceiveConfirm
    val enter = if (confirm) {
        fadeIn(tween(durationMillis = 320, delayMillis = 90, easing = FastOutSlowInEasing))
    } else {
        fadeIn(tween(durationMillis = 260, delayMillis = 80))
    }
    val exit = fadeOut(tween(if (confirm) 180 else 140, easing = FastOutSlowInEasing))
    return enter.togetherWith(exit)
        .using(SizeTransform(clip = true) { _, _ -> BackupCardSizeSpec })
}

private fun AnimatedContentTransitionScope<BackupRestoreTab>.backupTabSlide(): ContentTransform {
    val forward = targetState.ordinal > initialState.ordinal
    val enterX: (Int) -> Int = { width -> if (forward) width else -width }
    val exitX: (Int) -> Int = { width -> if (forward) -width else width }
    val slide = tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)
    return (slideInHorizontally(animationSpec = slide, initialOffsetX = enterX) + fadeIn(tween(140)))
        .togetherWith(
            slideOutHorizontally(animationSpec = slide, targetOffsetX = exitX) + fadeOut(tween(140)),
        )
        .using(SizeTransform(clip = true) { _, _ -> BackupCardSizeSpec })
}

private fun AnimatedContentTransitionScope<String>.backupIncludeTransition(): ContentTransform {
    if (initialState == "recv-code" && targetState == "recv-apply") {
        return (
            slideInVertically(
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 7 },
            ) + fadeIn(tween(durationMillis = 300, delayMillis = 50, easing = FastOutSlowInEasing))
            ).togetherWith(
                slideOutVertically(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    targetOffsetY = { -it / 12 },
                ) + fadeOut(tween(160)),
            )
            .using(SizeTransform(clip = true) { _, _ -> BackupCardSizeSpec })
    }
    if (initialState == "recv-apply" && targetState == "recv-code") {
        return (
            fadeIn(tween(durationMillis = 240, delayMillis = 40))
            ).togetherWith(
                slideOutVertically(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    targetOffsetY = { it / 8 },
                ) + fadeOut(tween(160)),
            )
            .using(SizeTransform(clip = true) { _, _ -> BackupCardSizeSpec })
    }
    return fadeIn(tween(durationMillis = 260, delayMillis = 80))
        .togetherWith(fadeOut(tween(140)))
        .using(SizeTransform(clip = true) { _, _ -> BackupCardSizeSpec })
}

@Composable
private fun BackupIdleTabBody(
    tab: BackupRestoreTab,
    exporting: Boolean,
    importing: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    BackupTabDescription(
        text = stringResource(
            if (tab == BackupRestoreTab.Clone) {
                R.string.settings_backup_clone_desc
            } else {
                R.string.settings_backup_restore_file_desc
            },
        ),
    )
    Spacer(modifier = Modifier.height(16.dp))
    if (tab == BackupRestoreTab.Clone) {
        BackupActionRow(
            title = stringResource(R.string.settings_backup_clone_send_entry),
            subtitle = stringResource(R.string.settings_backup_clone_send_sub),
            icon = Icons.Outlined.Send,
            onClick = onSend,
        )
        SettingsDivider()
        BackupActionRow(
            title = stringResource(R.string.settings_backup_clone_receive_entry),
            subtitle = stringResource(R.string.settings_backup_clone_receive_sub),
            icon = Icons.Rounded.MoveToInbox,
            onClick = onReceive,
        )
    } else {
        BackupActionRow(
            title = stringResource(R.string.settings_backup_restore_export_button),
            subtitle = stringResource(R.string.settings_backup_restore_export_sub),
            icon = Icons.Rounded.IosShare,
            enabled = !exporting && !importing,
            busy = exporting,
            onClick = onExport,
        )
        SettingsDivider()
        BackupActionRow(
            title = stringResource(R.string.settings_backup_restore_import_button),
            subtitle = stringResource(R.string.settings_backup_restore_import_sub),
            icon = Icons.Rounded.FileOpen,
            enabled = !importing && !exporting,
            busy = importing,
            onClick = onImport,
        )
    }
}

@Composable
private fun BackupTabDescription(text: String) {
    CollapsibleDescriptionText(
        text = text,
        fontSize = settingsBodyTextSize(),
        lineHeight = settingsBodyLineHeight(),
        color = getSettingsDescriptionColor(),
        topPadding = 0.dp,
    )
}

@Composable
internal fun BackupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    showChevron: Boolean = true,
) {
    val titleSize = settingsTitleTextSize()
    val captionSize = settingsCaptionTextSize()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enabled && !busy) {
                    Modifier.settingsClickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(getSliderInactiveColor()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = getAccentColor(),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = titleSize,
                lineHeight = titleSize,
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = captionSize,
                lineHeight = captionSize,
                color = getSettingsDescriptionColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = getAccentColor(),
            )
        } else if (showChevron) {
            SettingsChevronIcon()
        }
    }
}

private data class BackupSegmentMetrics(
    val trackHeight: Dp,
    val padding: Dp,
    val fontSize: TextUnit,
)

@Composable
private fun rememberBackupSegmentMetrics(): BackupSegmentMetrics {
    val isLandscape = rememberPaneIsLandscape()
    return if (isLandscape) {
        BackupSegmentMetrics(
            trackHeight = 54.dp,
            padding = 4.dp,
            fontSize = 14.sp,
        )
    } else {
        BackupSegmentMetrics(
            trackHeight = 58.dp,
            padding = 5.dp,
            fontSize = 15.sp,
        )
    }
}

@Composable
internal fun rememberBackupActionContentWidthModifier(): Modifier {
    val configuration = LocalConfiguration.current
    val isLandscape = rememberPaneIsLandscape()
    val maxWidth = when {
        configuration.screenWidthDp >= 840 -> 420.dp
        isLandscape && configuration.screenWidthDp >= 600 -> 380.dp
        else -> Dp.Unspecified
    }
    return if (maxWidth == Dp.Unspecified) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .widthIn(max = maxWidth)
    }
}

@Composable
private fun BackupSegmentedControl(
    selected: BackupRestoreTab,
    onSelect: (BackupRestoreTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberBackupSegmentMetrics()
    val thumbColor = getDialogBackground()
    val trackColor = getSliderInactiveColor()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.trackHeight)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .padding(metrics.padding),
    ) {
        val segmentCount = BackupRestoreTab.entries.size
        val segmentWidth = maxWidth / segmentCount
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selected.ordinal,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = 420f,
            ),
            label = "backup_segment_thumb",
        )

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(50), clip = false)
                .clip(RoundedCornerShape(50))
                .background(thumbColor),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
        ) {
            BackupSegmentTab(
                label = stringResource(R.string.settings_backup_restore_tab_clone),
                selected = selected == BackupRestoreTab.Clone,
                onClick = { onSelect(BackupRestoreTab.Clone) },
                fontSize = metrics.fontSize,
                modifier = Modifier.weight(1f),
            )
            BackupSegmentTab(
                label = stringResource(R.string.settings_backup_restore_tab_file),
                selected = selected == BackupRestoreTab.File,
                onClick = { onSelect(BackupRestoreTab.File) },
                fontSize = metrics.fontSize,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BackupSegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) getAccentColor() else getSettingsDescriptionColor(),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
