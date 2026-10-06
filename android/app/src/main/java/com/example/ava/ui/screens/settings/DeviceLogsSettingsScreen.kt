package com.example.ava.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.crash.AvaIncidentLog
import com.example.ava.crash.MainThreadStallWatchdog
import com.example.ava.ui.AvaToast
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.ModalSheetDragHandle
import com.example.ava.ui.screens.settings.components.rememberHandleSheetFill
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.utils.DebugLogCollector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceLogsSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val splitActive = LocalSettingsSplitActive.current
    val deviceLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Split right pane is portrait-width: stack the cards. Off-split landscape keeps the two-column row.
    val landscape = !splitActive && deviceLandscape
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val exportOn = experimentalState?.deviceIncidentLogEnabled == true
    val watchdogOn = experimentalState?.mainThreadStallWatchdogEnabled == true
    val healOn = playerState?.enableCrashSelfHeal == true
    val fused = remember(watchdogOn, exportOn) { MainThreadStallWatchdog.isFused(context) }

    var incidents by remember { mutableStateOf<List<AvaIncidentLog.Incident>>(emptyList()) }
    var selected by remember { mutableStateOf<AvaIncidentLog.Incident?>(null) }

    LaunchedEffect(exportOn) {
        incidents = if (exportOn) {
            withContext(Dispatchers.IO) { AvaIncidentLog.snapshot(context) }
        } else {
            emptyList()
        }
    }

    val listPadH = settingsListHorizontalPadding()
    val listPadV = settingsListVerticalPadding()
    val copied = stringResource(R.string.settings_device_logs_copied)
    val offHint = stringResource(R.string.settings_device_logs_off_hint)

    val onCopy: () -> Unit = {
        if (!exportOn) {
            AvaToast.show(context, offHint)
        } else {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    buildCopyText(context, incidents)
                }
                DebugLogCollector.copyToClipboard(context, text)
                AvaToast.show(context, copied)
            }
        }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_device_logs_title),
        // Split stacks two portrait-width cards that can exceed the pane; let the page scroll.
        contentPadding = if (splitActive) {
            null
        } else {
            PaddingValues(horizontal = listPadH, vertical = 0.dp)
        },
        userScrollEnabled = splitActive,
    ) {
        item {
            val fillViewport = Modifier
                .fillParentMaxHeight()
                .fillMaxWidth()
                .padding(vertical = listPadV)
            val info = @Composable { modifier: Modifier ->
                DeviceLogsInfoCard(
                    exportOn = exportOn,
                    watchdogOn = watchdogOn,
                    healOn = healOn,
                    fused = fused,
                    latest = incidents.firstOrNull(),
                    onExportChange = { enabled ->
                        scope.launch { viewModel.saveDeviceIncidentLogEnabled(enabled) }
                    },
                    onWatchdogChange = { enabled ->
                        scope.launch { viewModel.saveMainThreadStallWatchdogEnabled(enabled) }
                    },
                    onCopy = onCopy,
                    modifier = modifier,
                )
            }
            val list = @Composable { modifier: Modifier ->
                DeviceLogsListCard(
                    exportOn = exportOn,
                    incidents = incidents,
                    onOpen = { selected = it },
                    modifier = modifier,
                    fillRemaining = !splitActive,
                )
            }
            if (landscape) {
                Row(
                    modifier = fillViewport,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    info(Modifier.weight(1f).fillMaxHeight())
                    list(Modifier.weight(1f).fillMaxHeight())
                }
            } else if (splitActive) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    info(Modifier.fillMaxWidth())
                    list(Modifier.fillMaxWidth())
                }
            } else {
                Column(
                    modifier = fillViewport,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    info(Modifier.fillMaxWidth())
                    list(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    selected?.let { incident ->
        DeviceLogsDetailSheet(
            incident = incident,
            onDismiss = { selected = null },
            onCopy = {
                DebugLogCollector.copyToClipboard(context, formatIncident(incident))
                AvaToast.show(context, copied)
            },
        )
    }
}

@Composable
private fun DeviceLogsInfoCard(
    exportOn: Boolean,
    watchdogOn: Boolean,
    healOn: Boolean,
    fused: Boolean,
    latest: AvaIncidentLog.Incident?,
    onExportChange: (Boolean) -> Unit,
    onWatchdogChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SimpleCard(modifier = modifier) {
        SettingRow(
            label = stringResource(R.string.settings_device_logs_export),
            subLabel = stringResource(R.string.settings_device_logs_export_desc),
        ) {
            ModernSwitch(checked = exportOn, onCheckedChange = onExportChange)
        }
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.settings_device_logs_watchdog),
            subLabel = stringResource(R.string.settings_device_logs_watchdog_desc),
        ) {
            ModernSwitch(
                checked = watchdogOn,
                enabled = exportOn,
                onCheckedChange = onWatchdogChange,
            )
        }
        if (watchdogOn && exportOn && !healOn) {
            SettingsDivider()
            SettingsHelpBodyText(
                text = stringResource(R.string.settings_device_logs_watchdog_needs_heal),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        if (fused && exportOn) {
            SettingsDivider()
            SettingsHelpBodyText(
                text = stringResource(R.string.settings_device_logs_watchdog_fused),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.settings_device_logs_copy),
            subLabel = latest?.let { incidentSummary(it) }
                ?: stringResource(R.string.settings_device_logs_empty),
            onClick = onCopy,
        ) {
            SettingRowHelpMark()
        }
    }
}

@Composable
private fun DeviceLogsListCard(
    exportOn: Boolean,
    incidents: List<AvaIncidentLog.Incident>,
    onOpen: (AvaIncidentLog.Incident) -> Unit,
    modifier: Modifier = Modifier,
    fillRemaining: Boolean = true,
) {
    SimpleCard(modifier = modifier) {
        if (!exportOn) {
            SettingsHelpBodyText(
                text = stringResource(R.string.settings_device_logs_off_hint),
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@SimpleCard
        }
        if (incidents.isEmpty()) {
            SettingsHelpBodyText(
                text = stringResource(R.string.settings_device_logs_empty),
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@SimpleCard
        }
        val rows: @Composable ColumnScope.() -> Unit = {
            incidents.forEach { incident ->
                SettingRow(
                    label = incidentKindLabel(incident.kind),
                    subLabel = incidentSummary(incident),
                    onClick = { onOpen(incident) },
                ) {
                    SettingRowChevron()
                }
                SettingsDivider()
            }
        }
        if (fillRemaining) {
            SettingsEdgeFadeScrollColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                fadeHeight = 18.dp,
                content = rows,
            )
        } else {
            rows()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceLogsDetailSheet(
    incident: AvaIncidentLog.Incident,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    val prefs = LocalContext.current
        .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val isDark by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fillPane = rememberHandleSheetFill()
    val preview = remember(incident.detail) {
        incident.detail.lineSequence().take(40).joinToString("\n")
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = fillPane.modifier,
        sheetState = sheetState,
        sheetMaxWidth = fillPane.sheetMaxWidth,
        shape = fillPane.shape,
        containerColor = getDialogBackground(),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(bottom = 16.dp),
        ) {
            ModalSheetDragHandle(isDarkMode = isDark, onClick = onDismiss)
            Text(
                text = incidentKindLabel(incident.kind),
                color = getTitleColor(),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = incidentSummary(incident),
                color = getSettingsDescriptionColor(),
                fontSize = settingsBodyTextSize(),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            SettingsEdgeFadeScrollColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                fadeHeight = 18.dp,
            ) {
                Text(
                    text = preview.ifBlank { incident.reason },
                    color = getTitleColor(),
                    fontSize = settingsBodyTextSize(),
                    fontFamily = FontFamily.Monospace,
                )
            }
            TextButton(
                onClick = onCopy,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(text = stringResource(R.string.settings_device_logs_copy))
            }
        }
    }
}

@Composable
private fun incidentKindLabel(kind: String): String = stringResource(
    when (kind) {
        AvaIncidentLog.KIND_CRASH_JAVA -> R.string.settings_device_logs_kind_crash
        AvaIncidentLog.KIND_CRASH_NATIVE -> R.string.settings_device_logs_kind_native
        AvaIncidentLog.KIND_STALL_RESTART -> R.string.settings_device_logs_kind_stall_restart
        AvaIncidentLog.KIND_STALL_ONLY -> R.string.settings_device_logs_kind_stall
        AvaIncidentLog.KIND_RENDERER_CRASH -> R.string.settings_device_logs_kind_renderer
        AvaIncidentLog.KIND_RESTART_USER -> R.string.settings_device_logs_kind_restart
        AvaIncidentLog.KIND_EXIT_USER -> R.string.settings_device_logs_kind_exit
        AvaIncidentLog.KIND_KILL_REMOTE -> R.string.settings_device_logs_kind_kill
        else -> R.string.settings_device_logs_kind_other
    },
)

@Composable
private fun incidentSummary(incident: AvaIncidentLog.Incident): String {
    val time = remember(incident.ts) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(incident.ts))
    }
    val stuck = if (incident.stuckMs > 0L) {
        stringResource(R.string.settings_device_logs_stuck_ms, incident.stuckMs)
    } else {
        incident.reason
    }
    return "$time · $stuck"
}

private fun formatIncident(incident: AvaIncidentLog.Incident): String = buildString {
    append(incident.kind).append(" · ").append(incident.reason).append('\n')
    append("ts=").append(incident.ts)
    append(" stuckMs=").append(incident.stuckMs)
    append(" uptimeMs=").append(incident.uptimeMs)
    append(" version=").append(incident.version).append('\n')
    if (incident.detail.isNotBlank()) append(incident.detail)
}

private fun buildCopyText(
    context: android.content.Context,
    incidents: List<AvaIncidentLog.Incident>,
): String {
    val header = DebugLogCollector.buildReport(
        listOf("Version" to (incidents.firstOrNull()?.version.orEmpty())),
    )
    val body = incidents.joinToString("\n\n") { formatIncident(it) }
    return buildString {
        append(header)
        append("\n--- incidents ---\n")
        append(body.ifBlank { "(none)" })
    }
}
