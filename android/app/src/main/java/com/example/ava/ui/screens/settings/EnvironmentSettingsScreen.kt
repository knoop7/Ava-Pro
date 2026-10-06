package com.example.ava.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.services.SatelliteRestartReason
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.utils.DeviceCapabilities
import kotlinx.coroutines.launch

@Composable
fun EnvironmentSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val hasEnvironmentSensor = DeviceCapabilities.hasAnyEnvironmentSensor(context)
    val deviceNotSupportedText = stringResource(R.string.settings_device_not_supported)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_environment_sensor)
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_sensor_enabled),
                    subLabel = if (hasEnvironmentSensor) {
                        stringResource(R.string.settings_sensor_enabled_desc)
                    } else {
                        deviceNotSupportedText
                    },
                ) {
                    ModernSwitch(
                        checked = if (hasEnvironmentSensor) {
                            experimentalState?.environmentSensorEnabled ?: false
                        } else {
                            false
                        },
                        enabled = hasEnvironmentSensor,
                        onCheckedChange = {
                            if (hasEnvironmentSensor) {
                                coroutineScope.launch {
                                    viewModel.saveEnvironmentSensorEnabled(it)
                                    // initEnvironmentSensors() only runs during satellite start().
                                    restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                }
                            }
                        },
                    )
                }

                if (hasEnvironmentSensor && experimentalState?.environmentSensorEnabled == true) {
                    val hasLightSensor = DeviceCapabilities.hasLightSensor(context)
                    val hasMagneticSensor = DeviceCapabilities.hasMagneticSensor(context)

                    data class EnvironmentToggleItem(
                        val available: Boolean,
                        val label: String,
                        val subLabel: String,
                        val checked: Boolean,
                        val onCheckedChange: (Boolean) -> Unit
                    )

                    val environmentItems = listOf(
                        EnvironmentToggleItem(
                            available = hasLightSensor,
                            label = stringResource(R.string.settings_environment_light_sensor),
                            subLabel = if (hasLightSensor) {
                                stringResource(R.string.settings_environment_light_sensor_desc)
                            } else {
                                deviceNotSupportedText
                            },
                            checked = hasLightSensor && (experimentalState?.environmentLightSensorEnabled != false),
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveEnvironmentLightSensorEnabled(it)
                                    restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                }
                            }
                        ),
                        EnvironmentToggleItem(
                            available = hasMagneticSensor,
                            label = stringResource(R.string.settings_environment_magnetic_sensor),
                            subLabel = if (hasMagneticSensor) {
                                stringResource(R.string.settings_environment_magnetic_sensor_desc)
                            } else {
                                deviceNotSupportedText
                            },
                            checked = hasMagneticSensor && (experimentalState?.environmentMagneticSensorEnabled == true),
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveEnvironmentMagneticSensorEnabled(it)
                                    restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                }
                            }
                        )
                    ).sortedBy { !it.available }

                    environmentItems.forEach { item ->
                        SettingsDivider()
                        SettingRow(
                            label = item.label,
                            subLabel = item.subLabel
                        ) {
                            ModernSwitch(
                                checked = item.checked,
                                enabled = item.available,
                                onCheckedChange = item.onCheckedChange
                            )
                        }
                    }

                    SettingsDivider()
                    val sensorInterval = experimentalState?.sensorUpdateInterval ?: 35
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_sensor_update_interval),
                            description = stringResource(R.string.settings_sensor_update_interval_desc),
                            badgeText = "${sensorInterval}s",
                        )
                        TickSlider(
                            value = sensorInterval.toFloat(),
                            onValueChange = {
                                coroutineScope.launch {
                                    viewModel.saveSensorUpdateInterval(it.toInt())
                                }
                            },
                            valueRange = 5f..60f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = getAccentColor(),
                                activeTrackColor = getAccentColor(),
                                inactiveTrackColor = getSliderInactiveColor(),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
