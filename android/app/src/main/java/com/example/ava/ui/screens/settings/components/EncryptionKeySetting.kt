package com.example.ava.ui.screens.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.example.ava.R
import com.example.ava.ui.AvaToast
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getInputBackground
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor

@Composable
fun EncryptionKeySetting(
    name: String,
    dialogHint: String,
    value: String,
    enabled: Boolean = true,
    validation: (String) -> String?,
    onGenerate: () -> String,
    onConfirmRequest: (String) -> Unit,
) {
    val onLabel = stringResource(R.string.settings_esphome_encryption_key_on)
    val offLabel = stringResource(R.string.settings_esphome_encryption_key_off)
    DialogSettingItem(
        name = name,
        value = if (value.isBlank()) offLabel else onLabel,
        enabled = enabled,
    ) {
        EncryptionKeyDialog(
            title = name,
            description = dialogHint,
            value = value,
            onLabel = onLabel,
            offLabel = offLabel,
            validation = validation,
            onGenerate = onGenerate,
            onConfirmRequest = onConfirmRequest,
        )
    }
}

@Composable
private fun DialogScope.EncryptionKeyDialog(
    title: String,
    description: String,
    value: String,
    onLabel: String,
    offLabel: String,
    validation: (String) -> String?,
    onGenerate: () -> String,
    onConfirmRequest: (String) -> Unit,
) {
    var encryptionOn by remember { mutableStateOf(value.isNotBlank()) }
    var textValue by remember { mutableStateOf(value) }
    val invalidKey = stringResource(R.string.validation_esphome_encryption_key)
    val validationState = remember(textValue, encryptionOn, invalidKey) {
        when {
            !encryptionOn -> null
            textValue.isBlank() -> invalidKey
            else -> validation(textValue)
        }
    }
    val context = LocalContext.current
    val hasValue = textValue.isNotBlank()
    val muted = Color(0xFF94A3B8)

    ActionDialog(
        title = title,
        description = description,
        confirmEnabled = !encryptionOn || validationState.isNullOrBlank(),
        maxWidth = 420.dp,
        contentPadding = 24.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onConfirmRequest = {
            onConfirmRequest(if (encryptionOn) textValue else "")
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            EncryptionOnOffSegment(
                encryptionOn = encryptionOn,
                onLabel = onLabel,
                offLabel = offLabel,
                onSelectOn = {
                    encryptionOn = true
                    if (textValue.isBlank()) {
                        textValue = onGenerate()
                    }
                },
                onSelectOff = { encryptionOn = false },
            )
            if (encryptionOn) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        EncryptionKeyField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            isValid = validationState.isNullOrBlank(),
                            copyEnabled = hasValue,
                            copyColor = if (hasValue) getAccentColor() else muted,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("encryption_key", textValue),
                                )
                                AvaToast.show(
                                    context,
                                    R.string.copied_to_clipboard,
                                    tag = "clipboard",
                                )
                            },
                        )
                        if (!validationState.isNullOrBlank()) {
                            Text(
                                text = validationState.orEmpty(),
                                color = Color(0xFFB91C1C),
                                fontSize = settingsBodyTextSize(),
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                            )
                        }
                    }
                    TextButton(
                        onClick = { textValue = onGenerate() },
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_esphome_encryption_key_generate),
                            color = getAccentColor(),
                            fontSize = settingsBodyTextSize(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptionKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    isValid: Boolean,
    copyEnabled: Boolean,
    copyColor: Color,
    onCopy: () -> Unit,
) {
    val fieldBg = getInputBackground()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        ValidatedTextField(
            value = value,
            onValueChange = onValueChange,
            isValid = isValid,
            validationText = "",
        )
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, fieldBg),
                        ),
                    ),
            )
            IconButton(
                onClick = onCopy,
                enabled = copyEnabled,
                modifier = Modifier
                    .background(fieldBg)
                    .size(36.dp)
                    .padding(end = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.content_copy_24px),
                    contentDescription = stringResource(R.string.settings_esphome_encryption_key_copy),
                    tint = copyColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private val SegmentTrackShape = RoundedCornerShape(12.dp)
private val SegmentThumbShape = RoundedCornerShape(10.dp)

@Composable
private fun EncryptionOnOffSegment(
    encryptionOn: Boolean,
    onLabel: String,
    offLabel: String,
    onSelectOn: () -> Unit,
    onSelectOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbColor = getDialogBackground()
    val trackColor = getSliderInactiveColor()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(SegmentTrackShape)
            .background(trackColor)
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / 2
        val thumbOffset by animateDpAsState(
            targetValue = if (encryptionOn) segmentWidth else 0.dp,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = 420f,
            ),
            label = "encryption_segment_thumb",
        )

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(1.dp, SegmentThumbShape, clip = false)
                .clip(SegmentThumbShape)
                .background(thumbColor),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
        ) {
            EncryptionSegmentTab(
                label = offLabel,
                iconRes = R.drawable.mdi_lock_open,
                selected = !encryptionOn,
                onClick = onSelectOff,
                modifier = Modifier.weight(1f),
            )
            EncryptionSegmentTab(
                label = onLabel,
                iconRes = R.drawable.mdi_lock,
                selected = encryptionOn,
                onClick = onSelectOn,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EncryptionSegmentTab(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) getAccentColor() else getSettingsDescriptionColor()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(SegmentThumbShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = settingsBodyTextSize(),
                fontWeight = FontWeight.SemiBold,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
