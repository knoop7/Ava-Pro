package com.example.ava.ui.screens.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.permissions.PermissionUiStatus
import com.example.ava.ui.AvaToast
import com.example.ava.ui.screens.settings.components.rootSettingsBodyTextSize
import com.example.ava.ui.screens.settings.components.rootSettingsTitleTextSize
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.utils.AvaProcessControl
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RemoveRed = Color(0xFFEF4444)

/**
 * Secondary page under Permission manager (issue #249). Numbered steps, top to bottom:
 *  1. Status: one short line (not set / set).
 *  2. Not owner -> Set up: adb command + copy, plus a one-tap Root / Shizuku button when available.
 *     Owner     -> Remove: red button behind a short confirm.
 * Silent install itself already honours device owner (SilentApkInstaller).
 */
@Composable
fun DeviceOwnerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var isOwner by remember { mutableStateOf(ScreenControlUtils.isDeviceOwner(context)) }
    var canElevate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        isOwner = ScreenControlUtils.isDeviceOwner(context)
        // RootUtils probes `su` on first use; keep it off the main thread.
        canElevate = withContext(Dispatchers.IO) {
            RootUtils.isRootAvailable() || ShizukuUtils.isShizukuPermissionGranted()
        }
    }

    val adbCommand = remember { AvaProcessControl.deviceOwnerAdbCommand(context) }
    val accent = getAccentColor()
    val dark = isDarkModeEnabled()

    fun copyCommand() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("device_owner_adb", adbCommand))
        AvaToast.show(context, R.string.copied_to_clipboard, tag = "clipboard")
    }

    fun setViaPrivileged() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AvaProcessControl.tryBecomeDeviceOwner(context) }
            AvaToast.show(
                context,
                if (ok) R.string.settings_device_owner_set_ok else R.string.settings_device_owner_set_failed,
                durationMs = if (ok) 0L else AvaToast.LONG_MS,
            )
            busy = false
            refreshTick++
        }
    }

    fun removeOwner() {
        if (busy) return
        busy = true
        val ok = AvaProcessControl.clearDeviceOwner(context, context.findHostActivity())
        AvaToast.show(
            context,
            if (ok) R.string.settings_device_owner_remove_ok else R.string.settings_device_owner_remove_failed,
        )
        busy = false
        refreshTick++
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_device_owner_title),
    ) {
        // Step 1: status
        item {
            StepCard(
                number = 1,
                title = stringResource(R.string.settings_device_owner_status_label),
                accent = accent,
                trailing = {
                    StatusBadge(
                        status = if (isOwner) PermissionUiStatus.GRANTED else PermissionUiStatus.UNUSED,
                    )
                },
            ) {
                StepBody(
                    stringResource(
                        if (isOwner) R.string.settings_device_owner_desc_active
                        else R.string.settings_device_owner_desc,
                    ),
                )
            }
        }

        if (isOwner) {
            // Step 2 (owner): remove
            item {
                StepCard(
                    number = 2,
                    title = stringResource(R.string.settings_device_owner_remove_confirm),
                    accent = accent,
                ) {
                    ActionButton(
                        label = stringResource(R.string.settings_device_owner_remove_title),
                        color = RemoveRed,
                        enabled = !busy,
                        onClick = { showRemoveConfirm = true },
                    )
                }
            }
        } else {
            // Step 2 (not owner): set up
            item {
                StepCard(
                    number = 2,
                    title = stringResource(R.string.settings_device_owner_adb_title),
                    accent = accent,
                ) {
                    StepBody(stringResource(R.string.settings_device_owner_adb_desc))
                    SelectionContainer {
                        Text(
                            text = adbCommand,
                            fontFamily = FontFamily.Monospace,
                            fontSize = rootSettingsBodyTextSize(12f),
                            lineHeight = settingsBodyLineHeight(),
                            color = getTitleColor(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (dark) Color(0xFF2A2A2A) else Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    ActionButton(
                        label = stringResource(R.string.settings_device_owner_copy),
                        color = accent,
                        enabled = true,
                        onClick = { copyCommand() },
                    )
                    if (canElevate) {
                        ActionButton(
                            label = stringResource(R.string.settings_device_owner_set_privileged),
                            color = accent,
                            enabled = !busy,
                            onClick = { setViaPrivileged() },
                        )
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            modifier = Modifier.widthIn(max = 380.dp),
            title = {
                Text(
                    text = stringResource(R.string.settings_device_owner_remove_confirm_title),
                    color = getTitleColor(),
                    fontWeight = FontWeight.Bold,
                    fontSize = rootSettingsTitleTextSize(),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_device_owner_remove_confirm_msg),
                    fontSize = rootSettingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirm = false
                        removeOwner()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_device_owner_remove_confirm),
                        color = RemoveRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        color = getSettingsDescriptionColor(),
                    )
                }
            },
        )
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    accent: Color,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    SimpleCard {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(color = accent, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = title,
                    fontSize = rootSettingsTitleTextSize(16f),
                    fontWeight = FontWeight.SemiBold,
                    color = getTitleColor(),
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun StepBody(text: String) {
    Text(
        text = text,
        fontSize = rootSettingsBodyTextSize(),
        lineHeight = settingsBodyLineHeight(),
        color = getSettingsDescriptionColor(),
    )
}

@Composable
private fun ActionButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        shape = shape,
        border = BorderStroke(1.5.dp, if (enabled) color else Color(0xFF94A3B8)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            disabledContentColor = Color(0xFF94A3B8),
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
    ) {
        Text(
            text = label,
            fontSize = rootSettingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
