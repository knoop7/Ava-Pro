package com.example.ava.ui.screens.settings.components

import com.example.ava.utils.SoundUriPreview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.ui.screens.settings.SettingsDivider
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getTitleColor

@Composable
fun SharedRingtonePickerDialog(
    ringtones: List<Pair<String, String>>,
    currentUri: String,
    context: android.content.Context,
    title: String,
    externalSoundUri: String? = null,
    onExternalSoundUriConsumed: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onSelectExternal: () -> Unit,
) {
    val unknownLabel = stringResource(R.string.sound_unknown)
    var tempSelectedUri by remember(currentUri) { mutableStateOf(currentUri) }
    var customUris by remember(currentUri) {
        mutableStateOf(
            if (currentUri.isNotBlank() && ringtones.none { it.second == currentUri }) {
                listOf(currentUri)
            } else {
                emptyList()
            }
        )
    }
    var currentPreview by remember { mutableStateOf<SoundUriPreview.Handle?>(null) }

    LaunchedEffect(externalSoundUri) {
        val uri = externalSoundUri ?: return@LaunchedEffect
        if (uri.isNotBlank() && uri !in customUris) {
            customUris = customUris + uri
        }
        tempSelectedUri = uri
        currentPreview?.stop()
        currentPreview = SoundUriPreview.play(context, uri)
        onExternalSoundUriConsumed()
    }

    val displayRingtones = remember(ringtones, customUris, unknownLabel) {
        SystemRingtoneLoader.appendCustomRingtoneEntries(ringtones, customUris, context, unknownLabel)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(displayRingtones) {
        val index = displayRingtones.indexOfFirst { it.second == currentUri }
        if (index > 0) listState.scrollToItem(index)
    }

    Dialog(
        onDismissRequest = {
            currentPreview?.stop()
            onDismiss()
        },
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = getDialogBackground(),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor(),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 300.dp)
                        .fillMaxWidth(),
                ) {
                    itemsIndexed(displayRingtones) { index, (itemTitle, uri) ->
                        val isSelected = uri == tempSelectedUri
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPreview?.stop()
                                    tempSelectedUri = uri
                                    if (uri.isNotBlank()) {
                                        currentPreview = SoundUriPreview.play(context, uri)
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                modifier = Modifier.padding(end = 8.dp),
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = getAccentColor(),
                                    unselectedColor = Color(0xFF94A3B8),
                                ),
                            )
                            Text(
                                text = itemTitle,
                                fontSize = settingsTitleTextSize(),
                                color = if (isSelected) getAccentColor() else getLabelColor(),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (index < displayRingtones.lastIndex) {
                            SettingsDivider()
                        }
                    }
                    item {
                        SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPreview?.stop()
                                    onSelectExternal()
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.select_external_audio),
                                fontSize = settingsTitleTextSize(),
                                color = getAccentColor(),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        currentPreview?.stop()
                        onDismiss()
                    }) {
                        Text(
                            text = stringResource(R.string.label_cancel),
                            fontSize = settingsTitleTextSize(),
                            color = Color(0xFF94A3B8),
                        )
                    }
                    TextButton(onClick = {
                        currentPreview?.stop()
                        onConfirm(tempSelectedUri)
                    }) {
                        Text(
                            text = stringResource(R.string.label_ok),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Bold,
                            color = getAccentColor(),
                        )
                    }
                }
            }
        }
    }
}
