package com.example.ava.ui.screens.settings.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.bluetooth.BluetoothPresenceAlertSound
import com.example.ava.settings.BluetoothPresenceAlertTrigger
import com.example.ava.ui.screens.settings.SettingRow
import com.example.ava.ui.screens.settings.SettingsDivider
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.utils.SoundUriPreview

@Composable
fun BluetoothPresenceAlertDialog(
    currentSoundUri: String,
    currentTrigger: String,
    context: Context,
    ringtones: List<Pair<String, String>>,
    savedCustomUris: List<String>,
    externalSoundUri: String?,
    onExternalSoundUriConsumed: () -> Unit,
    onRememberCustomUri: (String) -> Unit,
    onRemoveSavedCustomUri: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (soundUri: String, trigger: String) -> Unit,
    onSelectExternal: () -> Unit,
) {
    var tempSelectedUri by remember(currentSoundUri) { mutableStateOf(currentSoundUri) }
    var tempTrigger by remember(currentTrigger) {
        mutableStateOf(BluetoothPresenceAlertSound.normalizeTrigger(currentTrigger))
    }
    val unknownLabel = stringResource(R.string.sound_unknown)
    val systemUriSet = remember(ringtones) { ringtones.map { it.second }.toSet() }
    var customUris by remember(savedCustomUris, currentSoundUri, systemUriSet) {
        mutableStateOf(
            buildList {
                savedCustomUris.forEach { uri ->
                    if (uri.isNotBlank() && !BluetoothPresenceAlertSound.isBuiltInRingtoneUri(uri, systemUriSet) && uri !in this) {
                        add(uri)
                    }
                }
                if (
                    currentSoundUri.isNotBlank() &&
                    !BluetoothPresenceAlertSound.isBuiltInRingtoneUri(currentSoundUri, systemUriSet) &&
                    currentSoundUri !in this
                ) {
                    add(currentSoundUri)
                }
            }
        )
    }
    var currentPreview by remember { mutableStateOf<SoundUriPreview.Handle?>(null) }
    var triggerExpanded by remember { mutableStateOf(false) }

    val displayRingtones = remember(ringtones, customUris, unknownLabel) {
        SystemRingtoneLoader.appendCustomRingtoneEntries(ringtones, customUris, context, unknownLabel)
    }
    val removeSavedSoundDescription = stringResource(
        R.string.settings_bluetooth_remove_saved_sound_content_description
    )

    val triggerOptions = listOf(
        BluetoothPresenceAlertTrigger.NOT_NEARBY,
        BluetoothPresenceAlertTrigger.NEARBY,
    )
    val triggerLabels = mapOf(
        BluetoothPresenceAlertTrigger.NEARBY to
            stringResource(R.string.notification_bluetooth_presence_alert_trigger_nearby),
        BluetoothPresenceAlertTrigger.NOT_NEARBY to
            stringResource(R.string.notification_bluetooth_presence_alert_trigger_not_nearby),
    )

    LaunchedEffect(externalSoundUri) {
        val uri = externalSoundUri ?: return@LaunchedEffect
        if (uri.isNotBlank() && uri !in customUris) {
            customUris = customUris + uri
        }
        if (!BluetoothPresenceAlertSound.isBuiltInRingtoneUri(uri, systemUriSet)) {
            onRememberCustomUri(uri)
        }
        tempSelectedUri = uri
        currentPreview?.stop()
        currentPreview = SoundUriPreview.play(context, uri)
        onExternalSoundUriConsumed()
    }

    fun removeCustomUri(uri: String) {
        customUris = customUris.filter { it != uri }
        onRemoveSavedCustomUri(uri)
        if (tempSelectedUri == uri) {
            tempSelectedUri = BluetoothPresenceAlertSound.NONE_URI
            currentPreview?.stop()
        }
    }

    fun previewUri(storedUri: String) {
        currentPreview?.stop()
        val resolved = BluetoothPresenceAlertSound.resolvePlayUri(storedUri)
        if (resolved.isNotBlank()) {
            currentPreview = SoundUriPreview.play(context, resolved)
        }
    }

    AlertDialog(
        onDismissRequest = {
            currentPreview?.stop()
            onDismiss()
        },
        title = {
            Text(
                text = stringResource(R.string.settings_bluetooth_presence_alert_sound_title),
                fontWeight = FontWeight.Bold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SettingRow(
                    label = stringResource(R.string.notification_bluetooth_presence_alert_trigger_when),
                    onClick = { triggerExpanded = true },
                ) {
                    Box {
                        Text(
                            text = triggerLabels[tempTrigger] ?: triggerLabels.values.first(),
                            fontSize = settingsBodyTextSize(),
                            fontWeight = FontWeight.Medium,
                            color = getAccentColor(),
                        )
                        ThemedDropdownMenu(
                            expanded = triggerExpanded,
                            onDismissRequest = { triggerExpanded = false },
                        ) {
                            triggerOptions.forEach { key ->
                                ThemedDropdownMenuItem(
                                    text = triggerLabels[key] ?: key,
                                    selected = key == tempTrigger,
                                    onClick = {
                                        tempTrigger = key
                                        triggerExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                SettingsDivider()

                Text(
                    text = stringResource(R.string.settings_bluetooth_presence_alert_sound_label),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )

                displayRingtones.forEachIndexed { index, (itemTitle, uri) ->
                    val isSelected = uri == tempSelectedUri
                    val isRemovableCustom = !BluetoothPresenceAlertSound.isBuiltInRingtoneUri(uri, systemUriSet)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tempSelectedUri = uri
                                previewUri(uri)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            modifier = Modifier.padding(end = 8.dp),
                            colors = RadioButtonDefaults.colors(
                                selectedColor = getAccentColor(),
                                unselectedColor = getLabelColor().copy(alpha = 0.6f)
                            )
                        )
                        Text(
                            text = itemTitle,
                            fontSize = settingsTitleTextSize(),
                            color = if (isSelected) getAccentColor() else getLabelColor(),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isRemovableCustom) {
                            IconButton(
                                onClick = { removeCustomUri(uri) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = removeSavedSoundDescription,
                                    tint = getLabelColor(),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (index < displayRingtones.lastIndex) {
                        SettingsDivider()
                    }
                }

                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentPreview?.stop()
                            onSelectExternal()
                        }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.select_external_audio),
                        fontSize = settingsTitleTextSize(),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                currentPreview?.stop()
                if (!BluetoothPresenceAlertSound.isBuiltInRingtoneUri(tempSelectedUri, systemUriSet)) {
                    onRememberCustomUri(tempSelectedUri)
                }
                onConfirm(tempSelectedUri, tempTrigger)
            }) {
                Text(
                    text = stringResource(R.string.label_ok),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Bold,
                    color = getAccentColor()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                currentPreview?.stop()
                onDismiss()
            }) {
                Text(
                    text = stringResource(R.string.label_cancel),
                    fontSize = settingsTitleTextSize(),
                    color = getLabelColor()
                )
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = getDialogBackground()
    )
}
