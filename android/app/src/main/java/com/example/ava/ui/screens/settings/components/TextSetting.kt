package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.settingsFilledFieldColors

@Composable
fun TextSetting(
    name: String,
    description: String = "",
    dialogHint: String = "",
    value: String,
    rowValue: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    validation: ((String) -> String?)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onConfirmRequest: (String) -> Unit = {}
) {
    DialogSettingItem(
        name = name.safeText(),
        description = description.safeText(),
        value = (rowValue ?: value).safeText(),
        enabled = enabled
    ) {
        TextDialog(
            title = name.safeText(),
            description = dialogHint.safeText(),
            value = value.safeText(),
            placeholder = placeholder.safeText(),
            onConfirmRequest = onConfirmRequest,
            validation = validation,
            keyboardOptions = keyboardOptions,
        )
    }
}

@Composable
fun DialogScope.TextDialog(
    title: String = "",
    description: String = "",
    value: String = "",
    placeholder: String = "",
    onConfirmRequest: (String) -> Unit,
    validation: ((String) -> String?)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var textValue by remember { mutableStateOf(value.safeText()) }
    val validationState = remember(textValue) { validation?.invoke(textValue) }

    ActionDialog(
        title = title,
        description = description.safeText(),
        confirmEnabled = validationState.isNullOrBlank(),
        onConfirmRequest = {
            onConfirmRequest(textValue)
        }
    ) {
        ValidatedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            placeholder = placeholder.safeText(),
            isValid = validationState.isNullOrBlank(),
            validationText = validationState ?: "",
            keyboardOptions = keyboardOptions,
        )
    }
}

@Composable
fun ValidatedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "",
    placeholder: String = "",
    isValid: Boolean = true,
    validationText: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)

    val labelColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)

    TextField(
        modifier = modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder.safeText(),
                color = getSettingsDescriptionColor(),
                fontSize = settingsTitleTextSize()
            )
        },
        isError = !isValid,
        supportingText = if (validationText.isNotEmpty()) {
             @Composable { Text(text = validationText, fontSize = settingsBodyTextSize()) }
        } else null,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        textStyle = TextStyle(
            fontSize = settingsTitleTextSize(),
            color = labelColor
        ),
        shape = RoundedCornerShape(12.dp),
        colors = settingsFilledFieldColors()
    )
}

private fun String?.safeText(): String = this.orEmpty()
