package com.example.ava.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.components.*
import com.example.ava.utils.ShizukuUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHIZUKU_RELEASE_URL = "https://github.com/RikkaApps/Shizuku/releases"
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

private fun openShizukuDownload(context: Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASE_URL)))
}

private fun openShizukuApp(context: Context) {
    context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
        context.startActivity(it)
    }
}

private fun requestShizukuAuth(scope: CoroutineScope, context: Context, onRefresh: () -> Unit) {
    ShizukuUtils.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        onRefresh()
        scope.launch {
            withContext(Dispatchers.IO) {
                ShizukuUtils.grantBluetoothPermissions(context.packageName)
            }
        }
    }, 1000)
}

@Composable
private fun StatusIndicator(isActive: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(
                color = if (isActive) Color(0xFF22C55E) else Color(0xFFEF4444),
                shape = CircleShape
            )
    )
}

@Composable
fun RootSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasRoot = remember { com.example.ava.utils.RootUtils.isRootAvailable() }
    
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuAuthorized by remember { mutableStateOf(false) }
    
    fun refreshStatus() {
        shizukuInstalled = ShizukuUtils.isShizukuInstalled(context)
        shizukuRunning = ShizukuUtils.isShizukuRunning()
        shizukuAuthorized = ShizukuUtils.isShizukuPermissionGranted()
    }
    
    LaunchedEffect(Unit) { refreshStatus() }
    
    val shizukuStatusText = listOf(
        shizukuAuthorized to stringResource(R.string.settings_shizuku_authorized),
        shizukuRunning to stringResource(R.string.settings_shizuku_waiting),
        shizukuInstalled to stringResource(R.string.settings_shizuku_not_running),
        true to stringResource(R.string.settings_shizuku_not_installed)
    ).first { it.first }.second
    
    val shizukuAction: (() -> Unit)? = listOf(
        shizukuAuthorized to null,
        shizukuRunning to { requestShizukuAuth(scope, context) { refreshStatus() } },
        shizukuInstalled to { openShizukuApp(context) },
        true to { openShizukuDownload(context) }
    ).first { it.first }.second
    
    val shizukuButtonText = listOf(
        shizukuAuthorized to "",
        shizukuRunning to stringResource(R.string.settings_shizuku_authorize),
        shizukuInstalled to stringResource(R.string.settings_shizuku_start),
        true to stringResource(R.string.settings_shizuku_install)
    ).first { it.first }.second
    
    val rootStatusLabel = stringResource(R.string.settings_root_status)
    val rootAvailable = stringResource(R.string.settings_root_available)
    val rootUnavailable = stringResource(R.string.settings_root_unavailable)
    val refreshText = stringResource(R.string.settings_shizuku_refresh)
    val guideTitle = stringResource(R.string.settings_shizuku_guide)
    val guideText = stringResource(R.string.settings_shizuku_guide_text)
    val rootGuideText = stringResource(R.string.settings_root_guide_text)
    
    SettingsDetailScreen(navController = navController, title = stringResource(R.string.settings_group_root)) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_permission_manager_title),
                    subLabel = stringResource(R.string.settings_permission_manager_entry_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_PERMISSION_MANAGER) {
                            launchSingleTop = true
                        }
                    },
                ) {
                    SettingRowChevron()
                }
            }
        }
        item {
            SettingsSectionLabel(stringResource(R.string.settings_permission_section_elevation))
        }
        item {
            SimpleCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rootStatusLabel,
                        fontSize = rootSettingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = getTitleColor()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(hasRoot)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasRoot) rootAvailable else rootUnavailable,
                            fontSize = rootSettingsBodyTextSize(),
                            color = if (hasRoot) Color(0xFF22C55E) else getSettingsDescriptionColor()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(12.dp))

                CollapsibleDescriptionText(
                    text = rootGuideText,
                    collapsedLines = 2,
                    fontSize = rootSettingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    fadeToColor = getDialogBackground(),
                    topPadding = 0.dp,
                )
            }
        }
        // Shizuku requires at least Android 6.0 (API 23); on Android 5 (API 21/22) hide the whole card
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        item {
            SimpleCard {
                Column {
                    Text(stringResource(R.string.settings_shizuku_title), fontSize = rootSettingsTitleTextSize(), fontWeight = FontWeight.Medium, color = getTitleColor())
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_shizuku_desc),
                        fontSize = rootSettingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = settingsDescriptionTopPadding(),
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicator(shizukuAuthorized)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(shizukuStatusText, fontSize = rootSettingsBodyTextSize(), color = if (shizukuAuthorized) Color(0xFF22C55E) else getSettingsDescriptionColor())
                        }
                        TextButton(onClick = { refreshStatus() }) { Text(refreshText, color = getAccentColor()) }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { shizukuAction?.invoke() },
                        enabled = shizukuAction != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = getAccentColor(),
                            disabledContainerColor = getSliderInactiveColor()
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(shizukuButtonText.ifEmpty { stringResource(R.string.settings_shizuku_authorized) })
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(guideTitle, fontSize = rootSettingsTitleTextSize(), fontWeight = FontWeight.Medium, color = getTitleColor())
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsHelpBodyText(text = guideText)
                }
            }
        }
        }
    }
}
