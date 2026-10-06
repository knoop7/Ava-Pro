package com.example.ava.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.settings.HomeLockPin
import com.example.ava.settings.HomeLockSettings
import com.example.ava.settings.HomeLockTarget
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.HomePinChangeDialog
import com.example.ava.ui.screens.settings.components.IntSetting
import com.example.ava.ui.screens.settings.components.SelectSetting
import kotlinx.coroutines.launch
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon

@Composable
fun HomeLockSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val homeLockState by viewModel.homeLockSettingsState.collectAsStateWithLifecycle(null)
    val settings = homeLockState ?: HomeLockSettings()
    val homeLockEnabled = settings.enabled
    val lockTarget = HomeLockTarget.resolved(settings)
    val pinLength = HomeLockPin.resolvedPinLength(settings)
    val defaultPin = HomeLockPin.defaultPinForLength(pinLength)
    val idleTimeoutSeconds = HomeLockPin.clampIdleTimeoutSeconds(settings.idleTimeoutSeconds)
    var showChangePinDialog by remember { mutableStateOf(false) }

    fun formatPinLength(length: Int): String =
        context.getString(R.string.settings_home_pin_length_option, length)

    val lockTargetHome = stringResource(R.string.settings_home_pin_lock_target_home)
    val lockTargetSettings = stringResource(R.string.settings_home_pin_lock_target_settings)
    val lockTargetHomeDesc = stringResource(R.string.settings_home_pin_lock_target_home_desc)
    val lockTargetSettingsDesc = stringResource(R.string.settings_home_pin_lock_target_settings_desc)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_home_pin_lock),
    ) {
        // Card 1 — master switch + protect scope
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_home_pin_lock_enable),
                    subLabel = stringResource(
                        R.string.settings_home_pin_lock_enable_desc,
                        pinLength,
                        defaultPin,
                    ),
                ) {
                    ModernSwitch(
                        checked = homeLockEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                viewModel.saveHomeLockEnabled(enabled)
                            }
                        },
                    )
                }

                if (homeLockEnabled) {
                    SettingsDivider()
                    SelectSetting(
                        name = stringResource(R.string.settings_home_pin_lock_target),
                        description = stringResource(R.string.settings_home_pin_lock_target_desc),
                        selected = lockTarget,
                        items = HomeLockTarget.entries,
                        key = { it.storageKey },
                        value = { target ->
                            when (target) {
                                HomeLockTarget.HOME -> lockTargetHome
                                HomeLockTarget.SETTINGS -> lockTargetSettings
                                null -> ""
                            }
                        },
                        itemDescription = { target ->
                            when (target) {
                                HomeLockTarget.HOME -> lockTargetHomeDesc
                                HomeLockTarget.SETTINGS -> lockTargetSettingsDesc
                            }
                        },
                        onConfirmRequest = { selected ->
                            if (selected != null) {
                                coroutineScope.launch {
                                    viewModel.saveHomeLockTarget(selected)
                                }
                            }
                        },
                    )
                }
            }
        }

        if (homeLockEnabled) {
            // Card 2 — passcode digits + change
            item {
                SimpleCard {
                    SelectSetting(
                        name = stringResource(R.string.settings_home_pin_length),
                        description = stringResource(
                            R.string.settings_home_pin_length_desc,
                            defaultPin,
                        ),
                        selected = pinLength,
                        items = HomeLockPin.PIN_LENGTH_OPTIONS,
                        key = { it },
                        value = { length ->
                            if (length == null) "" else formatPinLength(length)
                        },
                        onConfirmRequest = { selected ->
                            if (selected != null) {
                                coroutineScope.launch {
                                    viewModel.saveHomeLockPinLength(selected)
                                }
                            }
                        },
                    )
                    SettingsDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showChangePinDialog = true },
                    ) {
                        SettingRow(
                            label = stringResource(R.string.settings_home_pin_change),
                            subLabel = stringResource(
                                R.string.settings_home_pin_change_desc,
                                pinLength,
                            ),
                        ) {
                            SettingsChevronIcon(tint = Color(0xFF94A3B8))
                        }
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_home_pin_shuffle_keypad),
                        subLabel = stringResource(R.string.settings_home_pin_shuffle_keypad_desc),
                    ) {
                        ModernSwitch(
                            checked = settings.shuffleKeypad,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    viewModel.saveHomeLockShuffleKeypad(enabled)
                                }
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_home_pin_anti_brute),
                        subLabel = stringResource(R.string.settings_home_pin_anti_brute_desc),
                    ) {
                        ModernSwitch(
                            checked = settings.antiBruteForce,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    viewModel.saveHomeLockAntiBruteForce(enabled)
                                }
                            },
                        )
                    }
                }
            }

            // Idle auto-lock (Home scope only)
            if (lockTarget == HomeLockTarget.HOME) {
                item {
                    SimpleCard {
                        IntSetting(
                            name = stringResource(R.string.settings_home_pin_idle_timeout),
                            description = stringResource(R.string.settings_home_pin_idle_timeout_desc),
                            dialogHint = stringResource(R.string.settings_home_pin_idle_timeout_desc),
                            value = idleTimeoutSeconds,
                            validation = { seconds ->
                                if (seconds == null) null
                                else if (seconds in HomeLockPin.MIN_IDLE_TIMEOUT_SECONDS..HomeLockPin.MAX_IDLE_TIMEOUT_SECONDS) {
                                    null
                                } else {
                                    context.getString(
                                        R.string.validation_range,
                                        HomeLockPin.MIN_IDLE_TIMEOUT_SECONDS,
                                        HomeLockPin.MAX_IDLE_TIMEOUT_SECONDS,
                                    )
                                }
                            },
                            onConfirmRequest = { seconds ->
                                coroutineScope.launch {
                                    viewModel.saveHomeLockIdleTimeoutSeconds(
                                        seconds ?: HomeLockPin.DEFAULT_IDLE_TIMEOUT_SECONDS,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showChangePinDialog) {
        HomePinChangeDialog(
            isDarkMode = isDarkMode,
            pinHash = settings.pinHash,
            pinLength = pinLength,
            shuffleKeypad = settings.shuffleKeypad,
            antiBruteForce = settings.antiBruteForce,
            onDismiss = { showChangePinDialog = false },
            onPinChanged = { newPin ->
                coroutineScope.launch {
                    viewModel.saveHomeLockPin(newPin)
                }
            },
        )
    }
}
