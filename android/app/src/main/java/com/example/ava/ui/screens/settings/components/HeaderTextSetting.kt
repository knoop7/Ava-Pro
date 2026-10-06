package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME

@Composable
fun HeaderTextSetting(
    name: String,
    description: String = "",
    titleValue: String,
    subtitleValue: String,
    displayValue: String,
    titlePlaceholder: String = "",
    subtitlePlaceholder: String = "",
    titleLabel: String? = null,
    subtitleLabel: String? = null,
    dialogTitle: String? = null,
    titleMaxLength: Int,
    subtitleMaxLength: Int,
    allowBlank: Boolean = false,
    enabled: Boolean = true,
    onConfirmRequest: (String, String) -> Unit = { _, _ -> }
) {
    DialogSettingItem(
        name = name,
        description = description,
        value = displayValue,
        enabled = enabled
    ) {
        HeaderTextDialog(
            titleValue = titleValue,
            subtitleValue = subtitleValue,
            titlePlaceholder = titlePlaceholder,
            subtitlePlaceholder = subtitlePlaceholder,
            titleLabel = titleLabel,
            subtitleLabel = subtitleLabel,
            dialogTitle = dialogTitle,
            titleMaxLength = titleMaxLength,
            subtitleMaxLength = subtitleMaxLength,
            allowBlank = allowBlank,
            onConfirmRequest = onConfirmRequest
        )
    }
}

@Composable
fun DialogScope.HeaderTextDialog(
    titleValue: String,
    subtitleValue: String,
    titlePlaceholder: String = "",
    subtitlePlaceholder: String = "",
    titleLabel: String? = null,
    subtitleLabel: String? = null,
    dialogTitle: String? = null,
    titleMaxLength: Int,
    subtitleMaxLength: Int,
    allowBlank: Boolean = false,
    onConfirmRequest: (String, String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val fieldLabelColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)

    var titleText by remember { mutableStateOf(titleValue) }
    var subtitleText by remember { mutableStateOf(subtitleValue) }
    val emptyValidation = stringResource(R.string.validation_header_text_empty)
    val titleValidation = when {
        !allowBlank && titleText.isBlank() -> emptyValidation
        titleText.length > titleMaxLength -> stringResource(R.string.validation_max_length, titleMaxLength)
        else -> null
    }
    val subtitleValidation = when {
        !allowBlank && subtitleText.isBlank() -> emptyValidation
        subtitleText.length > subtitleMaxLength -> stringResource(R.string.validation_max_length, subtitleMaxLength)
        else -> null
    }
    val titleCounter = stringResource(R.string.settings_header_char_count, titleText.length, titleMaxLength)
    val subtitleCounter = stringResource(
        R.string.settings_header_char_count,
        subtitleText.length,
        subtitleMaxLength
    )

    ActionDialog(
        title = dialogTitle ?: stringResource(R.string.settings_header_text),
        confirmEnabled = titleValidation.isNullOrBlank() && subtitleValidation.isNullOrBlank(),
        compact = true,
        onConfirmRequest = { onConfirmRequest(titleText.trim(), subtitleText.trim()) }
    ) {
        Column {
            Text(
                text = titleLabel ?: stringResource(R.string.settings_header_title_label),
                color = fieldLabelColor,
                fontSize = settingsBodyTextSize(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ValidatedTextField(
                value = titleText,
                onValueChange = { if (it.length <= titleMaxLength) titleText = it },
                placeholder = titlePlaceholder,
                isValid = titleValidation.isNullOrBlank(),
                validationText = titleValidation ?: titleCounter
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitleLabel ?: stringResource(R.string.settings_header_subtitle_label),
                color = fieldLabelColor,
                fontSize = settingsBodyTextSize(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ValidatedTextField(
                value = subtitleText,
                onValueChange = { if (it.length <= subtitleMaxLength) subtitleText = it },
                placeholder = subtitlePlaceholder,
                isValid = subtitleValidation.isNullOrBlank(),
                validationText = subtitleValidation ?: subtitleCounter
            )
        }
    }
}
