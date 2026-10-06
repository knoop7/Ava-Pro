package com.example.ava.ui.screens.settings

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.ava.ui.AvaToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.Screen
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingItem
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsSimpleCardVerticalPadding
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsDescriptionTopPadding
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsClickable
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.utils.DeviceCapabilities

private data class LauncherOption(
    val packageName: String,
    val label: String,
    val isCurrent: Boolean,
)

@Composable
fun ServiceSettingsScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    var showLauncherDialog by remember { mutableStateOf(false) }
    var launcherRefreshKey by remember { mutableStateOf(0) }

    val homeIntent = remember {
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    }
    val launcherOptions = remember(launcherRefreshKey, showLauncherDialog) {
        if (showLauncherDialog) {
            buildLauncherOptions(context.packageManager, homeIntent)
        } else {
            emptyList()
        }
    }
    val currentLauncherLabel = remember(launcherRefreshKey) {
        resolveCurrentLauncherLabel(context.packageManager, homeIntent, context)
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        showLauncherDialog = false
        launcherRefreshKey++
    }

    val hasEnvironmentSensor = DeviceCapabilities.hasAnyEnvironmentSensor(context)
    val hasProximitySensor = DeviceCapabilities.hasProximitySensor(context)
    val deviceNotSupportedText = stringResource(R.string.settings_device_not_supported)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_group_service),
    ) {
        item {
            val dark = isDarkModeEnabled()
            ServiceSettingsEntryPair(
                leftTitle = stringResource(R.string.settings_device_control_title),
                leftSubLabel = stringResource(R.string.settings_service_entry_device_control_desc),
                leftIcon = rememberVectorPainter(Icons.Outlined.Refresh),
                leftBadge = serviceEntryBadge(AccentBlue, dark),
                onLeftClick = {
                    navController.navigate(Screen.SETTINGS_SERVICE_DEVICE_CONTROL) {
                        launchSingleTop = true
                    }
                },
                rightTitle = stringResource(R.string.settings_device_logs_title),
                rightSubLabel = stringResource(R.string.settings_device_logs_entry_desc),
                rightIcon = painterResource(R.drawable.rounded_bug_report_24),
                rightBadge = serviceEntryBadge(ServiceLogsBadge, dark),
                onRightClick = {
                    navController.navigate(Screen.SETTINGS_SERVICE_DEVICE_LOGS) {
                        launchSingleTop = true
                    }
                },
            )
        }
        item { SettingsSectionLabel(stringResource(R.string.settings_service_section_home_system)) }
        item {
            SimpleCard {
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_home_interface),
                    subLabel = stringResource(R.string.settings_service_entry_home_interface_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_INTERACTION_INTERFACE) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_style),
                    subLabel = stringResource(R.string.settings_service_entry_settings_style_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_STYLE) { launchSingleTop = true }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_sidebar),
                    subLabel = stringResource(R.string.settings_service_entry_sidebar_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SIDEBAR) { launchSingleTop = true }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_home_pin_lock),
                    subLabel = stringResource(R.string.settings_service_entry_home_lock_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_HOME_LOCK) { launchSingleTop = true }
                    },
                )
                SettingsDivider()
                SettingItem(
                    modifier = Modifier.clickable { showLauncherDialog = true },
                    name = stringResource(R.string.settings_system_launcher),
                    description = stringResource(R.string.settings_service_entry_system_launcher_desc),
                    value = currentLauncherLabel,
                ) {
                    SettingsChevronIcon(tint = Color(0xFF94A3B8))
                }
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_minimal_launcher),
                    subLabel = stringResource(R.string.settings_service_entry_minimal_launcher_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_service_keep_running),
                    subLabel = stringResource(R.string.settings_service_entry_auto_restart_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_AUTO_RESTART) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_software_update),
                    subLabel = stringResource(R.string.settings_service_entry_software_update_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SOFTWARE_UPDATE) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_service_section_screen)) }
        item {
            SimpleCard {
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_screen_power_control),
                    subLabel = stringResource(R.string.settings_service_entry_screen_power_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_SCREEN_POWER) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_screen_brightness),
                    subLabel = stringResource(R.string.settings_service_entry_screen_brightness_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_screen_touch),
                    subLabel = stringResource(R.string.settings_service_entry_screen_touch_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_SCREEN_TOUCH) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_force_orientation),
                    subLabel = stringResource(R.string.settings_service_entry_force_orientation_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_FORCE_ORIENTATION) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_touch_sound),
                    subLabel = stringResource(R.string.settings_service_entry_touch_sound_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_TOUCH_SOUND) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_service_section_sensors)) }
        item {
            SimpleCard {
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_sensor_enabled),
                    subLabel = if (hasEnvironmentSensor) {
                        stringResource(R.string.settings_service_entry_environment_desc)
                    } else {
                        deviceNotSupportedText
                    },
                    onClick = {
                        navController.navigate(Screen.SETTINGS_ENVIRONMENT) { launchSingleTop = true }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_proximity_sensor),
                    subLabel = if (hasProximitySensor) {
                        stringResource(R.string.settings_service_entry_proximity_desc)
                    } else {
                        deviceNotSupportedText
                    },
                    onClick = {
                        navController.navigate(Screen.SETTINGS_SERVICE_PROXIMITY) {
                            launchSingleTop = true
                        }
                    },
                )
                SettingsDivider()
                ServiceSettingsNavRow(
                    label = stringResource(R.string.settings_diagnostic_sensor),
                    subLabel = stringResource(R.string.settings_service_entry_diagnostic_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_DIAGNOSTIC) { launchSingleTop = true }
                    },
                )
            }
        }
    }

    if (showLauncherDialog) {
        AlertDialog(
            onDismissRequest = { showLauncherDialog = false },
            modifier = Modifier.widthIn(max = 350.dp),
            title = {
                Text(
                    text = stringResource(R.string.settings_system_launcher),
                    color = getTitleColor(),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_system_launcher_picker_desc),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = 0.dp,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        launcherOptions.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        handleLauncherSelection(
                                            context = context,
                                            option = option,
                                            roleLauncher = roleLauncher::launch,
                                            onLaunchedSettings = { launcherRefreshKey++ },
                                        )
                                        showLauncherDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = option.isCurrent,
                                    onClick = {
                                        handleLauncherSelection(
                                            context = context,
                                            option = option,
                                            roleLauncher = roleLauncher::launch,
                                            onLaunchedSettings = { launcherRefreshKey++ },
                                        )
                                        showLauncherDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = getAccentColor(),
                                        unselectedColor = Color(0xFF94A3B8),
                                    ),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        fontSize = settingsTitleTextSize(),
                                        color = getTitleColor(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = option.packageName,
                                        fontSize = settingsBodyTextSize(),
                                        color = getSettingsDescriptionColor(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLauncherDialog = false }) {
                    Text(
                        text = stringResource(R.string.label_ok),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            containerColor = getDialogBackground(),
            shape = RoundedCornerShape(20.dp),
        )
    }
}

/** Same slate oval as [R.drawable.widget_action_circle_slate]. */
private val ServiceLogsBadge = Color(0xFF475569)

/** Same light/dark badge lift as [DeviceControlSettingsScreen] restart tile. */
private fun serviceEntryBadge(accent: Color, dark: Boolean): Color {
    return if (dark) lerp(accent, Color.White, 0.16f) else accent
}

@Composable
private fun ServiceSettingsEntryPair(
    leftTitle: String,
    leftSubLabel: String,
    leftIcon: Painter,
    leftBadge: Color,
    onLeftClick: () -> Unit,
    rightTitle: String,
    rightSubLabel: String,
    rightIcon: Painter,
    rightBadge: Color,
    onRightClick: () -> Unit,
) {
    val isDarkMode = isDarkModeEnabled()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSimpleCardVerticalPadding),
        shape = RoundedCornerShape(32.dp),
        color = getDialogBackground(),
        shadowElevation = if (isDarkMode) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ServiceSettingsEntryTile(
                title = leftTitle,
                subLabel = leftSubLabel,
                icon = leftIcon,
                badge = leftBadge,
                onClick = onLeftClick,
                modifier = Modifier.fillMaxWidth(),
            )
            ServiceSettingsEntryTile(
                title = rightTitle,
                subLabel = rightSubLabel,
                icon = rightIcon,
                badge = rightBadge,
                onClick = onRightClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ServiceSettingsEntryTile(
    title: String,
    subLabel: String,
    icon: Painter,
    badge: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = rememberSettingsTextScale()
    val badgeSize = (38f * scale).dp
    val glyphSize = (20f * scale).dp
    Row(
        modifier = modifier
            .heightIn(min = (88f * scale).dp)
            .clip(RoundedCornerShape(16.dp))
            .background(getSettingsInsetWellColor())
            .settingsClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(badgeSize)
                .background(badge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(glyphSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = getTitleColor(),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CollapsibleDescriptionText(
                text = subLabel,
                fontSize = settingsCaptionTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
                collapsedLines = 3,
                topPadding = settingsDescriptionTopPadding(),
            )
        }
        SettingsChevronIcon(tint = Color(0xFF94A3B8), base = 18f)
    }
}

@Composable
private fun ServiceSettingsNavRow(
    label: String,
    subLabel: String,
    onClick: () -> Unit,
) {
    SettingRow(
        label = label,
        subLabel = subLabel,
        onClick = onClick,
    ) {
        SettingsChevronIcon(tint = Color(0xFF94A3B8))
    }
}

private fun buildLauncherOptions(
    packageManager: PackageManager,
    homeIntent: Intent,
): List<LauncherOption> {
    val currentHomePackage = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName

    return packageManager.queryIntentActivities(
        homeIntent,
        PackageManager.GET_META_DATA,
    ).mapNotNull { resolveInfo ->
        val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
        LauncherOption(
            packageName = activityInfo.packageName,
            label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty()
                .ifBlank { activityInfo.packageName },
            isCurrent = activityInfo.packageName == currentHomePackage,
        )
    }.distinctBy { it.packageName }.sortedWith(
        compareByDescending<LauncherOption> { it.isCurrent }.thenBy { it.label.lowercase() },
    )
}

private fun resolveCurrentLauncherLabel(
    packageManager: PackageManager,
    homeIntent: Intent,
    context: android.content.Context,
): String {
    val currentHome = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?: return context.getString(R.string.settings_system_launcher_not_set)

    return packageManager.queryIntentActivities(
        homeIntent,
        PackageManager.GET_META_DATA,
    ).firstOrNull { it.activityInfo?.packageName == currentHome.packageName }
        ?.loadLabel(packageManager)
        ?.toString()
        ?.ifBlank { currentHome.packageName }
        ?: currentHome.packageName
}

private fun handleLauncherSelection(
    context: android.content.Context,
    option: LauncherOption,
    roleLauncher: (Intent) -> Unit,
    onLaunchedSettings: () -> Unit,
) {
    if (option.packageName == context.packageName &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    ) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null &&
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        ) {
            roleLauncher(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            return
        }
    }

    val settingsIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(settingsIntent)
    }.recoverCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    onLaunchedSettings()

    AvaToast.show(
        context,
        context.getString(R.string.settings_system_launcher_switch_hint),
    )
}
