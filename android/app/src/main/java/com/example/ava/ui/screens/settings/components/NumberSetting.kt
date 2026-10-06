package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun IntSetting(
    name: String,
    description: String = "",
    dialogHint: String = "",
    value: Int?,
    enabled: Boolean = true,
    showValueOnRow: Boolean = true,
    /** When true, allows typing a leading minus (e.g. mic gain attenuation). */
    signed: Boolean = false,
    validation: ((Int?) -> String?)? = null,
    onConfirmRequest: (Int?) -> Unit = {}
) {
    TextSetting(
        name = name,
        description = description,
        dialogHint = dialogHint,
        value = value?.toString() ?: "",
        rowValue = if (showValueOnRow) null else "",
        enabled = enabled,
        validation = { input ->
            when {
                input.isEmpty() -> validation?.invoke(null)
                // Allow an in-progress signed prefix so Confirm stays disabled until a number exists.
                signed && (input == "-" || input == "+") -> "Invalid number"
                input.toIntOrNull() == null -> "Invalid number"
                else -> validation?.invoke(input.toIntOrNull())
            }
        },
        // Number pad often omits '-'; Ascii keeps digits + minus available for signed fields.
        keyboardOptions = KeyboardOptions(
            keyboardType = if (signed) KeyboardType.Ascii else KeyboardType.Number,
        ),
        onConfirmRequest = { onConfirmRequest(it.toIntOrNull()) }
    )
}