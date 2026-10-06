package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.bluetooth.BleIrkResolver
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getTitleColor

/**
 * Paste, view, or clear a presence IRK for one tracked device.
 * Prefills [initialIrk] when one is already stored (manual or auto-read).
 */
@Composable
fun BluetoothIrkEditDialog(
    initialIrk: String = "",
    hasExistingIrk: Boolean,
    onDismiss: () -> Unit,
    onSave: (irkInput: String) -> Boolean,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialIrk) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val invalidMsg = stringResource(R.string.settings_bluetooth_irk_invalid)
    val accent = getAccentColor()
    val titleColor = getTitleColor()
    val labelColor = getLabelColor()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val fieldBg = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC)
    val fieldBorder = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = getDialogBackground(),
        title = {
            Text(
                text = stringResource(
                    if (hasExistingIrk) {
                        R.string.settings_bluetooth_irk_edit_title_manage
                    } else {
                        R.string.settings_bluetooth_irk_edit_title_set
                    },
                ),
                fontWeight = FontWeight.Bold,
                fontSize = settingsTitleTextSize(),
                color = titleColor,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(fieldBg, RoundedCornerShape(12.dp))
                        .border(1.dp, fieldBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_bluetooth_irk_edit_hint),
                            color = getSettingsDescriptionColor(),
                            fontSize = settingsBodyTextSize(),
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = {
                            draft = it.replace("\n", "")
                            errorText = null
                        },
                        singleLine = true,
                        maxLines = 1,
                        cursorBrush = SolidColor(accent),
                        textStyle = TextStyle(
                            color = titleColor,
                            fontSize = settingsBodyTextSize(),
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                errorText?.let { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFFEF4444),
                        fontSize = settingsBodyTextSize(),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasExistingIrk) {
                    TextButton(
                        onClick = {
                            onClear()
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_bluetooth_irk_clear),
                            color = Color(0xFFEF4444),
                            fontSize = settingsTitleTextSize(),
                        )
                    }
                } else {
                    Box {}
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.label_cancel),
                            color = labelColor,
                            fontSize = settingsTitleTextSize(),
                        )
                    }
                    TextButton(
                        onClick = {
                            val trimmed = draft.trim()
                            if (trimmed.isEmpty() || BleIrkResolver.parseIrk(trimmed) == null) {
                                errorText = invalidMsg
                                return@TextButton
                            }
                            if (!onSave(trimmed)) {
                                errorText = invalidMsg
                                return@TextButton
                            }
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_bluetooth_irk_save),
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = settingsTitleTextSize(),
                        )
                    }
                }
            }
        },
        dismissButton = null,
    )
}
