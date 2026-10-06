package com.example.ava.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.sensors.DiagnosticSensorManager
import com.example.ava.ui.screens.settings.components.*
import kotlinx.coroutines.launch

@Composable
fun DiagnosticSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_diagnostic_sensor),
        onLeave = { viewModel.applyPendingDiagnosticRestart() },
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_sensor),
                    subLabel = stringResource(R.string.settings_diagnostic_sensor_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticSensorEnabled ?: false,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                viewModel.saveDiagnosticSensorEnabled(enabled)
                            }
                        },
                    )
                }

                if (experimentalState?.diagnosticSensorEnabled == true) {
                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_wifi),
                    subLabel = stringResource(R.string.settings_diagnostic_wifi_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticWifiEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticWifiEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_ip),
                    subLabel = stringResource(R.string.settings_diagnostic_ip_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticIpEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticIpEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_storage),
                    subLabel = stringResource(R.string.settings_diagnostic_storage_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticStorageEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticStorageEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_memory),
                    subLabel = stringResource(R.string.settings_diagnostic_memory_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticMemoryEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticMemoryEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_uptime),
                    subLabel = stringResource(R.string.settings_diagnostic_uptime_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticUptimeEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticUptimeEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_kill_app),
                    subLabel = stringResource(R.string.settings_diagnostic_kill_app_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticKillAppEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticKillAppEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_reboot),
                    subLabel = stringResource(R.string.settings_diagnostic_reboot_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticRebootEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticRebootEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_battery_level),
                    subLabel = stringResource(R.string.settings_diagnostic_battery_level_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticBatteryLevelEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticBatteryLevelEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_battery_voltage),
                    subLabel = stringResource(R.string.settings_diagnostic_battery_voltage_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticBatteryVoltageEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticBatteryVoltageEnabled(it)
                            }
                        }
                    )
                }
                
                SettingsDivider()
                
                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_charging_status),
                    subLabel = stringResource(R.string.settings_diagnostic_charging_status_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticChargingStatusEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticChargingStatusEnabled(it)
                            }
                        }
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_music_active),
                    subLabel = stringResource(R.string.settings_diagnostic_music_active_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticMusicActiveEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticMusicActiveEnabled(it)
                            }
                        }
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_last_used_app),
                    subLabel = stringResource(R.string.settings_diagnostic_last_used_app_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticLastUsedAppEnabled ?: false,
                        onCheckedChange = { enabled ->
                            if (enabled && !DiagnosticSensorManager.hasUsageStatsPermission(context)) {
                                DiagnosticSensorManager.openUsageAccessSettings(context)
                            }
                            coroutineScope.launch {
                                viewModel.saveDiagnosticLastUsedAppEnabled(enabled)
                            }
                        }
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_bluetooth),
                    subLabel = stringResource(R.string.settings_diagnostic_bluetooth_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticBluetoothEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticBluetoothEnabled(it)
                            }
                        }
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_diagnostic_network_type),
                    subLabel = stringResource(R.string.settings_diagnostic_network_type_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.diagnosticNetworkTypeEnabled ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDiagnosticNetworkTypeEnabled(it)
                            }
                        }
                    )
                }
                }
            }
        }
    }
}
