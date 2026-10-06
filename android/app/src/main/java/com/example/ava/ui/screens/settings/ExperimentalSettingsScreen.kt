package com.example.ava.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.mods.ModCameraStreamBridge
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.components.*


@Composable
fun ExperimentalSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    var showIntentLauncherDialog by remember { mutableStateOf(false) }
    var showClusterManagementDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var showModStoreDialog by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var showOccupancyDialog by remember { mutableStateOf(false) }
    
    if (showIntentLauncherDialog) {
        CombinedIntentControlUsageGuideDialog(
            onDismiss = { showIntentLauncherDialog = false },
        )
    }

    if (showModStoreDialog) {
        val modStoreCopyText = buildString {
            appendLine(stringResource(R.string.settings_mod_store_usage_desc))
            appendLine()
            appendLine(stringResource(R.string.settings_mod_store_usage_repo_title))
            appendLine(stringResource(R.string.settings_mod_store_usage_repo_content))
            appendLine()
            appendLine(stringResource(R.string.settings_mod_store_usage_capability_title))
            appendLine(stringResource(R.string.settings_mod_store_usage_capability_content))
            appendLine()
            appendLine(stringResource(R.string.settings_mod_store_usage_import_title))
            appendLine(stringResource(R.string.settings_mod_store_usage_import_content))
        }
        UsageGuideDialog(
            onDismissRequest = { showModStoreDialog = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = modStoreCopyText,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_desc),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_repo_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_repo_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_capability_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_capability_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_import_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_mod_store_usage_import_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
        }
    }

    if (showCameraDialog) {
        val cameraCopyText = buildString {
            appendLine(stringResource(R.string.settings_camera_usage_desc))
            appendLine()
            appendLine(stringResource(R.string.settings_camera_usage_vision_title))
            appendLine(stringResource(R.string.settings_camera_usage_vision_content))
            appendLine()
            appendLine(stringResource(R.string.settings_camera_usage_modes_title))
            appendLine(stringResource(R.string.settings_camera_usage_modes_content))
            appendLine()
            appendLine(stringResource(R.string.settings_camera_usage_stream_title))
            appendLine(stringResource(R.string.settings_camera_usage_stream_content))
            appendLine()
            appendLine(stringResource(R.string.settings_camera_usage_presence_title))
            appendLine(stringResource(R.string.settings_camera_usage_presence_content))
            appendLine()
            appendLine(stringResource(R.string.settings_camera_usage_automation_title))
            appendLine(stringResource(R.string.settings_camera_usage_automation_content))
            appendLine()
            appendLine(stringResource(R.string.settings_camera_usage_permission_title))
            appendLine(stringResource(R.string.settings_camera_usage_permission_content))
        }
        UsageGuideDialog(
            onDismissRequest = { showCameraDialog = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = cameraCopyText,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_desc),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_vision_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_camera_usage_vision_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_modes_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_camera_usage_modes_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_stream_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_camera_usage_stream_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_presence_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_camera_usage_presence_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_automation_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_camera_usage_automation_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_usage_permission_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = stringResource(R.string.settings_camera_usage_permission_content),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        lineHeight = settingsBodyLineHeight()
                    )
                }
            }
        }
    }

    if (showOccupancyDialog) {
        val occupancyCopyText = buildString {
            appendLine(stringResource(R.string.settings_occupancy_usage_desc))
            appendLine()
            appendLine(stringResource(R.string.settings_occupancy_usage_sources_title))
            appendLine(stringResource(R.string.settings_occupancy_usage_sources_content))
            appendLine()
            appendLine(stringResource(R.string.settings_occupancy_usage_params_title))
            appendLine(stringResource(R.string.settings_occupancy_usage_params_content))
            appendLine()
            appendLine(stringResource(R.string.settings_occupancy_usage_principle_title))
            appendLine(stringResource(R.string.settings_occupancy_usage_principle_content))
            appendLine()
            appendLine(stringResource(R.string.settings_occupancy_usage_ha_title))
            appendLine(stringResource(R.string.settings_occupancy_usage_ha_content))
        }
        UsageGuideDialog(
            onDismissRequest = { showOccupancyDialog = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = occupancyCopyText,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_desc),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_sources_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_sources_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_params_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_params_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_principle_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_principle_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_ha_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_occupancy_usage_ha_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
        }
    }

    if (showClusterManagementDialog) {
        val clusterCopyText = buildString {
            appendLine(stringResource(R.string.settings_cluster_management_usage_desc))
            appendLine()
            appendLine(stringResource(R.string.settings_cluster_management_usage_how_title))
            appendLine(stringResource(R.string.settings_cluster_management_usage_how_content))
            appendLine()
            appendLine(stringResource(R.string.settings_cluster_management_usage_network_title))
            appendLine(stringResource(R.string.settings_cluster_management_usage_network_content))
        }
        UsageGuideDialog(
            onDismissRequest = { showClusterManagementDialog = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = clusterCopyText,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_cluster_management_usage_desc),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_cluster_management_usage_how_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_cluster_management_usage_how_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_cluster_management_usage_network_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_cluster_management_usage_network_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
        }
    }

    if (showBackupRestoreDialog) {
        val backupCopyText = buildString {
            appendLine(stringResource(R.string.settings_backup_restore_usage_desc))
            appendLine()
            appendLine(stringResource(R.string.settings_backup_restore_usage_format_title))
            appendLine(stringResource(R.string.settings_backup_restore_usage_format_content))
            appendLine()
            appendLine(stringResource(R.string.settings_backup_restore_usage_export_title))
            appendLine(stringResource(R.string.settings_backup_restore_usage_export_content))
            appendLine()
            appendLine(stringResource(R.string.settings_backup_restore_usage_import_title))
            appendLine(stringResource(R.string.settings_backup_restore_usage_import_content))
            appendLine()
            appendLine(stringResource(R.string.settings_backup_restore_usage_esphome_title))
            appendLine(stringResource(R.string.settings_backup_restore_usage_esphome_content))
        }
        UsageGuideDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = backupCopyText,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_desc),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_format_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_format_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_export_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_export_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_import_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_import_content),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_esphome_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(),
                    color = getAccentColor()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_backup_restore_usage_esphome_content),
                    fontSize = settingsBodyTextSize(),
                    color = getAccentColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
        }
    }
    
    val context = LocalContext.current
    val hasCamera = viewModel.hasCamera()
    val noCameraText = stringResource(R.string.settings_no_camera)
    val snapshotModeLabel = stringResource(R.string.settings_camera_mode_snapshot)
    val videoModeLabel = stringResource(R.string.settings_camera_mode_video)

    var cameraStreamModActive by remember {
        mutableStateOf(ModCameraStreamBridge.isActive(context))
    }
    var cameraOwnerName by remember {
        mutableStateOf(ModCameraStreamBridge.activeOwnerName(context))
    }
    val modOwnedHint =
        stringResource(R.string.settings_camera_mod_owned_hint, cameraOwnerName ?: "")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraStreamModActive = ModCameraStreamBridge.isActive(context)
                cameraOwnerName = ModCameraStreamBridge.activeOwnerName(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(cameraStreamModActive) {
        if (cameraStreamModActive) {
            ModCameraStreamBridge.applyHostPolicySuspend(context)
        }
    }
    
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_group_experimental)
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_mod_store),
                    subLabel = stringResource(R.string.settings_mod_store_desc),
                    onClick = { navController.navigate(com.example.ava.ui.Screen.MOD_STORE) },
                ) {
                    SettingRowChevron()
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showModStoreDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }

        if (com.example.ava.utils.DeviceFeatureManager.shouldShowCameraSettings()) {
            item {
                SettingsDisabledOverlay(
                    disabled = cameraStreamModActive,
                    hint = modOwnedHint,
                ) {
                    SimpleCard {
                        val cameraEnabled =
                            !cameraStreamModActive && experimentalState?.cameraEnabled == true
                        val cameraMode = try {
                            com.example.ava.settings.CameraMode.valueOf(
                                experimentalState?.cameraMode ?: "SNAPSHOT"
                            )
                        } catch (e: Exception) {
                            com.example.ava.settings.CameraMode.SNAPSHOT
                        }
                        val cameraSubLabel = when {
                            cameraStreamModActive -> modOwnedHint
                            !hasCamera -> noCameraText
                            cameraEnabled -> {
                                val modeLabel = when (cameraMode) {
                                    com.example.ava.settings.CameraMode.SNAPSHOT -> snapshotModeLabel
                                    com.example.ava.settings.CameraMode.VIDEO -> videoModeLabel
                                    else -> ""
                                }
                                stringResource(R.string.settings_camera_enabled_desc) + " · $modeLabel"
                            }
                            else -> stringResource(R.string.settings_camera_enabled_desc)
                        }

                        SettingRow(
                            label = stringResource(R.string.settings_camera_enabled),
                            subLabel = cameraSubLabel,
                            onClick = {
                                if (!interactionsEnabled) return@SettingRow
                                navController.navigate(Screen.SETTINGS_CAMERA) {
                                    launchSingleTop = true
                                }
                            },
                        ) {
                            SettingRowChevron()
                        }
                        SettingsDivider()
                        SettingRow(
                            label = stringResource(R.string.settings_usage_guide_title),
                            onClick = {
                                if (!interactionsEnabled) return@SettingRow
                                showCameraDialog = true
                            },
                        ) {
                            SettingRowHelpMark()
                        }
                    }
                }
            }
        }

        item {
            SimpleCard {
                val occupancyOn = experimentalState?.occupancyEnabled == true
                SettingRow(
                    label = stringResource(R.string.settings_occupancy),
                    subLabel = if (occupancyOn) {
                        stringResource(R.string.settings_occupancy_entry_on)
                    } else {
                        stringResource(R.string.settings_occupancy_entry_desc)
                    },
                    onClick = {
                        navController.navigate(Screen.SETTINGS_OCCUPANCY) {
                            launchSingleTop = true
                        }
                    },
                ) {
                    SettingRowChevron()
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showOccupancyDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }
        
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_intent_launcher),
                    subLabel = stringResource(R.string.settings_intent_launcher_entry_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_INTENT_LAUNCHER) { launchSingleTop = true }
                    },
                ) {
                    SettingRowChevron()
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showIntentLauncherDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }

        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_cluster_management),
                    subLabel = stringResource(R.string.settings_cluster_management_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_CLUSTER_MANAGEMENT) { launchSingleTop = true }
                    },
                ) {
                    SettingRowChevron()
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showClusterManagementDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }

        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_backup_restore),
                    subLabel = stringResource(R.string.settings_backup_restore_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_BACKUP_RESTORE) { launchSingleTop = true }
                    },
                ) {
                    SettingRowChevron()
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showBackupRestoreDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }
        
    }
}
