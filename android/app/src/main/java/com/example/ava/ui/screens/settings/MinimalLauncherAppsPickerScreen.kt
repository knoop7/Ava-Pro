package com.example.ava.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.appwindow.FreeformAppWindow
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.ui.AvaToast
import com.example.ava.ui.screens.home.MinimalLauncherApp
import com.example.ava.ui.screens.home.MinimalLauncherAppsCache
import com.example.ava.ui.screens.home.distinctPackagesForPicker
import com.example.ava.ui.screens.home.isAppWindowSupported
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsDescriptionTopPadding
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.theme.SlateTertiary as SubLabelColor
import kotlinx.coroutines.launch

@Composable
fun MinimalLauncherAppsPickerScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val excludePackage = appContext.packageName
    val coroutineScope = rememberCoroutineScope()
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val selectedPackages = playerState?.minimalLauncherVisiblePackages.orEmpty()
    val selectedSet = remember(selectedPackages) { selectedPackages.toHashSet() }
    val windowedPackages = playerState?.appWindowPackages.orEmpty()
    val windowedSet = remember(windowedPackages) { windowedPackages.toHashSet() }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(excludePackage) {
        MinimalLauncherAppsCache.load(appContext, excludePackage)
    }
    val apps by MinimalLauncherAppsCache.appsFlow.collectAsStateWithLifecycle()
    val pickerApps = remember(apps) { apps.distinctPackagesForPicker() }
    val filteredApps = remember(pickerApps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            pickerApps
        } else {
            pickerApps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_minimal_launcher_apps),
    ) {
        item(key = "picker_search") {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.settings_minimal_launcher_apps_search_hint),
                        color = getSettingsDescriptionColor(),
                        fontSize = settingsTitleTextSize(),
                    )
                },
                textStyle = TextStyle(
                    fontSize = settingsTitleTextSize(),
                    color = getLabelColor(),
                ),
                shape = RoundedCornerShape(16.dp),
                colors = settingsFilledFieldColors(),
            )
        }

        item(key = "picker_list") {
            SimpleCard {
                CollapsibleDescriptionText(
                    text = stringResource(R.string.settings_minimal_launcher_apps_picker_hint),
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    topPadding = settingsDescriptionTopPadding(),
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                if (selectedPackages.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_minimal_launcher_apps_selected_count,
                                selectedPackages.size,
                            ),
                            fontSize = settingsBodyTextSize(),
                            color = getSettingsDescriptionColor(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.saveMinimalLauncherVisiblePackages(
                                        emptyList(),
                                        pickerApps.map { it.packageName },
                                    )
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.settings_minimal_launcher_apps_show_all),
                                color = getAccentColor(),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = settingsBodyTextSize(),
                            )
                        }
                    }
                }

                SettingsDivider()

                if (filteredApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_minimal_launcher_apps_empty),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    filteredApps.forEachIndexed { index, app ->
                        if (index > 0) SettingsDivider()
                        MinimalLauncherAppPickerRow(
                            app = app,
                            checked = app.packageName in selectedSet,
                            onToggle = {
                                coroutineScope.launch {
                                    val next = if (app.packageName in selectedSet) {
                                        selectedPackages.filterNot { it == app.packageName }
                                    } else {
                                        selectedPackages + app.packageName
                                    }
                                    viewModel.saveMinimalLauncherVisiblePackages(
                                        next,
                                        pickerApps.map { it.packageName },
                                    )
                                }
                            },
                            windowed = app.packageName in windowedSet,
                            onWindowToggle = {
                                val turningOn = app.packageName !in windowedSet
                                if (turningOn && !isAppWindowSupported() && !FreeformAppWindow.isSupported()) {
                                    // Hard floor: below Android 7 there is neither a
                                    // scrcpy virtual display (10+) nor system freeform
                                    // (7–9), so refuse to enable.
                                    AvaToast.show(
                                        context,
                                        R.string.app_window_requires_android,
                                        durationMs = AvaToast.LONG_MS,
                                    )
                                } else {
                                    coroutineScope.launch {
                                        val next = if (turningOn) {
                                            windowedPackages + app.packageName
                                        } else {
                                            windowedPackages.filterNot { it == app.packageName }
                                        }
                                        viewModel.saveAppWindowPackages(next)
                                    }
                                    if (turningOn) {
                                        AvaToast.show(
                                            context,
                                            R.string.settings_app_window_toast,
                                            durationMs = AvaToast.LONG_MS,
                                        )
                                        if (!PlatformCapabilities.canDrawOverlays(context)) {
                                            requestOverlayPermission(context)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalLauncherAppPickerRow(
    app: MinimalLauncherApp,
    checked: Boolean,
    onToggle: () -> Unit,
    windowed: Boolean,
    onWindowToggle: () -> Unit,
) {
    val iconSize = 40.dp
    val iconSizePx = with(LocalDensity.current) { iconSize.roundToPx().coerceAtLeast(1) }
    val bitmap = remember(app.packageName, app.activityName, iconSizePx) {
        app.icon.toBitmap(iconSizePx, iconSizePx)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = app.label.toString(),
            modifier = Modifier.size(iconSize),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label.toString(),
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = app.packageName,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onWindowToggle,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PictureInPictureAlt,
                contentDescription = stringResource(R.string.settings_app_window),
                tint = if (windowed) getAccentColor() else SubLabelColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = getAccentColor(),
                uncheckedColor = SubLabelColor,
                checkmarkColor = Color.White,
            ),
        )
    }
}
