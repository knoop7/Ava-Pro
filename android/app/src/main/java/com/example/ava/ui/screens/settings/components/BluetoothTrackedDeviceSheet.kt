package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.example.ava.R
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.SlateTextMuted
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getTitleColor
/**
 * Device hub for a tracked presence device: alert, IRK, auto-retry, remove.
 * Reuses [ModalBottomSheet] + [ModalSheetDragHandle] like other settings sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothTrackedDeviceSheet(
    device: BluetoothPresenceManager.TrackedDevice,
    isAlertActive: Boolean,
    onDismiss: () -> Unit,
    onPresenceAlert: () -> Unit,
    onEditIrk: () -> Unit,
    onRetryAutoIrk: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fillPane = rememberHandleSheetFill()
    val maxSheetHeight = (configuration.screenHeightDp * 0.72f).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetBackground = getDialogBackground()
    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val dividerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val accent = getAccentColor()
    val titleColor = getTitleColor()
    val hasIrk = device.irk.isNotBlank()
    val mutedIconBg = SlateTextMuted.copy(alpha = 0.12f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = fillPane.modifier,
        sheetState = sheetState,
        sheetMaxWidth = fillPane.sheetMaxWidth,
        shape = fillPane.shape,
        containerColor = sheetBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillPane.fill) Modifier.fillMaxHeight() else Modifier),
        ) {
        ModalSheetDragHandle(isDarkMode = isDarkMode, onClick = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (fillPane.fill) Modifier.weight(1f) else Modifier.heightIn(max = maxSheetHeight),
                )
                .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
                .padding(bottom = if (isLandscape) 14.dp else 22.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fillPane.fill) Modifier.fillMaxHeight() else Modifier),
                shape = if (fillPane.fill) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                color = cardBackground,
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = if (isLandscape) 20.dp else 14.dp)
                        .padding(top = 6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DeviceSheetActionRow(
                        iconRes = if (isAlertActive) {
                            R.drawable.mdi_bell
                        } else {
                            R.drawable.ic_bell_linear_outline
                        },
                        iconTint = if (isAlertActive) accent else SlateTextMuted,
                        iconBackground = if (isAlertActive) accent.copy(alpha = 0.14f) else mutedIconBg,
                        title = stringResource(R.string.settings_bluetooth_device_sheet_alert),
                        titleColor = titleColor,
                        showDivider = true,
                        dividerColor = dividerColor,
                        onClick = {
                            onPresenceAlert()
                            onDismiss()
                        },
                    )
                    DeviceSheetActionRow(
                        iconRes = R.drawable.ic_refresh_24px,
                        iconTint = SlateTextMuted,
                        iconBackground = mutedIconBg,
                        title = stringResource(R.string.settings_bluetooth_device_sheet_retry_irk),
                        titleColor = titleColor,
                        showDivider = true,
                        dividerColor = dividerColor,
                        onClick = {
                            onRetryAutoIrk()
                            onDismiss()
                        },
                    )
                    DeviceSheetActionRow(
                        iconRes = R.drawable.ic_key_24px,
                        iconTint = if (hasIrk) accent else SlateTextMuted,
                        iconBackground = if (hasIrk) accent.copy(alpha = 0.14f) else mutedIconBg,
                        title = stringResource(
                            if (hasIrk) {
                                R.string.settings_bluetooth_device_sheet_manage_irk
                            } else {
                                R.string.settings_bluetooth_device_sheet_set_irk
                            },
                        ),
                        titleColor = titleColor,
                        showDivider = true,
                        dividerColor = dividerColor,
                        onClick = {
                            onEditIrk()
                            onDismiss()
                        },
                    )
                    DeviceSheetActionRow(
                        iconRes = R.drawable.ic_delete,
                        iconTint = SlateTextMuted,
                        iconBackground = mutedIconBg,
                        title = stringResource(R.string.settings_bluetooth_remove_device),
                        titleColor = titleColor,
                        showDivider = false,
                        dividerColor = dividerColor,
                        onClick = {
                            onRemove()
                            onDismiss()
                        },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun DeviceSheetActionRow(
    iconRes: Int,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    titleColor: Color,
    showDivider: Boolean,
    dividerColor: Color,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = settingsTitleTextSize(),
                color = titleColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SettingsChevronIcon(tint = SlateTextMuted, base = 18f)
        }
        if (showDivider) {
            HorizontalDivider(color = dividerColor)
        }
    }
}
