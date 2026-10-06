package com.example.ava.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.screens.settings.components.*
import kotlinx.coroutines.launch

@Composable
fun IntentLauncherSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    var showAdbControlDialog by remember { mutableStateOf(false) }
    var showIntentLauncherDialog by remember { mutableStateOf(false) }
    var showMediaKeyDialog by remember { mutableStateOf(false) }

    if (showAdbControlDialog) {
        AdbControlUsageGuideDialog(onDismiss = { showAdbControlDialog = false })
    }
    if (showIntentLauncherDialog) {
        IntentLauncherUsageGuideDialog(onDismiss = { showIntentLauncherDialog = false })
    }
    if (showMediaKeyDialog) {
        MediaKeyUsageGuideDialog(onDismiss = { showMediaKeyDialog = false })
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_intent_launcher)
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_adb_control),
                    subLabel = stringResource(R.string.settings_adb_control_desc)
                ) {
                    ModernSwitch(
                        checked = experimentalState?.adbControlEnabled ?: true,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                viewModel.saveAdbControlEnabled(enabled)
                            }
                        }
                    )
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showAdbControlDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }

        item {
            SimpleCard {
                val intentLauncherMasterRow: @Composable () -> Unit = {
                    SettingRow(
                        label = stringResource(R.string.settings_intent_launcher),
                        subLabel = stringResource(R.string.settings_intent_launcher_desc)
                    ) {
                        ModernSwitch(
                            checked = experimentalState?.intentLauncherEnabled ?: false,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    viewModel.saveIntentLauncherEnabled(enabled)
                                }
                            }
                        )
                    }
                }

                if (experimentalState?.intentLauncherEnabled == true) {
                    SettingsInsetWell {
                        intentLauncherMasterRow()

                        SettingsWellDivider()

                        SettingRow(
                            label = stringResource(R.string.settings_intent_launcher_ha_display),
                            subLabel = stringResource(R.string.settings_intent_launcher_ha_display_desc)
                        ) {
                            ModernSwitch(
                                checked = experimentalState?.intentLauncherHaDisplayEnabled ?: false,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        viewModel.saveIntentLauncherHaDisplayEnabled(enabled)
                                        restartVoiceSatelliteServiceIfRunning(
                                            com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE,
                                        )
                                    }
                                }
                            )
                        }
                    }
                } else {
                    intentLauncherMasterRow()
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
                val mediaKeyMasterRow: @Composable () -> Unit = {
                    SettingRow(
                        label = stringResource(R.string.settings_media_key),
                        subLabel = stringResource(R.string.settings_media_key_desc)
                    ) {
                        ModernSwitch(
                            checked = experimentalState?.mediaKeyEnabled ?: false,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    viewModel.saveMediaKeyEnabled(enabled)
                                }
                            }
                        )
                    }
                }

                if (experimentalState?.mediaKeyEnabled == true) {
                    SettingsInsetWell {
                        mediaKeyMasterRow()

                        SettingsWellDivider()

                        SettingRow(
                            label = stringResource(R.string.settings_intent_launcher_ha_display),
                            subLabel = stringResource(R.string.settings_media_key_ha_display_desc)
                        ) {
                            ModernSwitch(
                                checked = experimentalState?.mediaKeyHaDisplayEnabled ?: false,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        viewModel.saveMediaKeyHaDisplayEnabled(enabled)
                                        restartVoiceSatelliteServiceIfRunning(
                                            com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE,
                                        )
                                    }
                                }
                            )
                        }
                    }
                } else {
                    mediaKeyMasterRow()
                }

                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showMediaKeyDialog = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }
    }
}

@Composable
fun AdbControlUsageGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    UsageGuideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_usage_guide_title),
        copyText = buildAdbControlUsageCopy(context),
    ) {
        AdbControlUsageGuideBody()
    }
}

@Composable
fun IntentLauncherUsageGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    UsageGuideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_usage_guide_title),
        copyText = buildIntentLauncherUsageCopy(context),
    ) {
        IntentLauncherUsageGuideBody()
    }
}

@Composable
fun CombinedIntentControlUsageGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    UsageGuideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_usage_guide_title),
        copyText = buildCombinedIntentControlUsageCopy(context),
    ) {
        Text(
            text = stringResource(R.string.settings_adb_control),
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor()
        )
        AdbControlUsageGuideBody()
        Text(
            text = stringResource(R.string.settings_intent_launcher),
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor()
        )
        IntentLauncherUsageGuideBody()
        Text(
            text = stringResource(R.string.settings_media_key),
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor()
        )
        MediaKeyUsageGuideBody()
    }
}

@Composable
fun MediaKeyUsageGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    UsageGuideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_usage_guide_title),
        copyText = buildMediaKeyUsageCopy(context),
    ) {
        MediaKeyUsageGuideBody()
    }
}

@Composable
internal fun AdbControlUsageGuideBody() {
    UsageGuideSection(
        body = stringResource(R.string.settings_adb_control_usage_desc),
    )
    UsageGuideSection(
        title = stringResource(R.string.settings_adb_control_usage_call_title),
        body = stringResource(R.string.settings_adb_control_usage_call_content),
        selectable = true,
    )
    UsageGuideSection(
        title = stringResource(R.string.settings_adb_control_usage_actions_title),
        body = stringResource(R.string.settings_adb_control_usage_actions_content),
        selectable = true,
    )
}

@Composable
internal fun MediaKeyUsageGuideBody() {
    UsageGuideSection(
        body = stringResource(R.string.settings_media_key_usage_desc),
    )
    UsageGuideSection(
        title = stringResource(R.string.settings_intent_launcher_usage_call_title),
        body = stringResource(R.string.settings_media_key_usage_call_content),
        selectable = true,
    )
    UsageGuideSection(
        title = stringResource(R.string.settings_media_key_usage_format_title),
        body = stringResource(R.string.settings_media_key_usage_format_content),
        selectable = true,
    )
}

@Composable
internal fun IntentLauncherUsageGuideBody() {
    UsageGuideSection(
        body = stringResource(R.string.settings_intent_launcher_usage_desc),
    )
    UsageGuideSection(
        title = stringResource(R.string.settings_intent_launcher_usage_call_title),
        body = stringResource(R.string.settings_intent_launcher_usage_call_content),
        selectable = true,
    )
    UsageGuideSection(
        title = stringResource(R.string.settings_intent_launcher_usage_format_title),
        body = stringResource(R.string.settings_intent_launcher_usage_format_content),
        selectable = true,
    )
}

@Composable
private fun UsageGuideSection(
    title: String? = null,
    body: String,
    selectable: Boolean = false,
) {
    Column {
        if (title != null) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (selectable) {
            SelectionContainer {
                Text(
                    text = body,
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    lineHeight = settingsBodyLineHeight()
                )
            }
        } else {
            Text(
                text = body,
                fontSize = settingsBodyTextSize(),
                color = getLabelColor(),
                lineHeight = settingsBodyLineHeight()
            )
        }
    }
}

internal fun buildAdbControlUsageCopy(context: Context): String = buildString {
    appendLine(context.getString(R.string.settings_adb_control_usage_desc))
    appendLine()
    appendLine(context.getString(R.string.settings_adb_control_usage_call_title))
    appendLine(context.getString(R.string.settings_adb_control_usage_call_content))
    appendLine()
    appendLine(context.getString(R.string.settings_adb_control_usage_actions_title))
    appendLine(context.getString(R.string.settings_adb_control_usage_actions_content))
}

internal fun buildIntentLauncherUsageCopy(context: Context): String = buildString {
    appendLine(context.getString(R.string.settings_intent_launcher_usage_desc))
    appendLine()
    appendLine(context.getString(R.string.settings_intent_launcher_usage_call_title))
    appendLine(context.getString(R.string.settings_intent_launcher_usage_call_content))
    appendLine()
    appendLine(context.getString(R.string.settings_intent_launcher_usage_format_title))
    appendLine(context.getString(R.string.settings_intent_launcher_usage_format_content))
}

internal fun buildMediaKeyUsageCopy(context: Context): String = buildString {
    appendLine(context.getString(R.string.settings_media_key_usage_desc))
    appendLine()
    appendLine(context.getString(R.string.settings_intent_launcher_usage_call_title))
    appendLine(context.getString(R.string.settings_media_key_usage_call_content))
    appendLine()
    appendLine(context.getString(R.string.settings_media_key_usage_format_title))
    appendLine(context.getString(R.string.settings_media_key_usage_format_content))
}

internal fun buildCombinedIntentControlUsageCopy(context: Context): String = buildString {
    appendLine(context.getString(R.string.settings_adb_control))
    appendLine(buildAdbControlUsageCopy(context).trimEnd())
    appendLine()
    appendLine(context.getString(R.string.settings_intent_launcher))
    appendLine(buildIntentLauncherUsageCopy(context).trimEnd())
    appendLine()
    appendLine(context.getString(R.string.settings_media_key))
    appendLine(buildMediaKeyUsageCopy(context).trimEnd())
}
