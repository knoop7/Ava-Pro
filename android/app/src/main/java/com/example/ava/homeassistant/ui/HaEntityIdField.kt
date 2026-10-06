package com.example.ava.homeassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.homeassistant.entity.HaEntityDomainFilter
import com.example.ava.ui.components.ExpandedTapTarget
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.screens.settings.getAccentColor

/**
 * Inline entity-id field. Typing always works; when HA is connected a search
 * icon opens [HaEntityPickerDialog] so the id does not have to be copied by hand.
 */
@Composable
fun HaEntityIdField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    pickerTitle: String,
    domainFilter: HaEntityDomainFilter,
    textColor: Color,
    hintColor: Color,
    background: Color,
    isInvalid: Boolean = false,
    invalidColor: Color = Color(0xFFDC2626),
) {
    val pickerAvailable = rememberIsHaPickerAvailable()
    var showPicker by remember { mutableStateOf(false) }
    val accent = getAccentColor()
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, shape)
            .then(
                if (isInvalid) {
                    Modifier.border(1.dp, invalidColor, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            cursorBrush = SolidColor(accent),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = settingsTitleTextSize(),
                color = if (isInvalid) invalidColor else textColor,
            ),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            color = hintColor,
                            fontSize = settingsTitleTextSize(),
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (pickerAvailable) {
            ExpandedTapTarget(
                onClick = { showPicker = true },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.settings_ha_entity_picker),
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showPicker) {
        HaEntityPickerDialog(
            title = pickerTitle,
            currentValue = value,
            domainFilter = domainFilter,
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                onValueChange(selected)
                showPicker = false
            },
        )
    }
}
