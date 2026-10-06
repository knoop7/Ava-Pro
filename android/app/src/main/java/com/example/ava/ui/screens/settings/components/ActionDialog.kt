package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.ava.R
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val DialogBackgroundLight = androidx.compose.ui.graphics.Color.White
private val DialogBackgroundDark = androidx.compose.ui.graphics.Color(0xFF1F1F1F)
private val TitleColorLight = androidx.compose.ui.graphics.Color(0xFF1E293B)
private val TitleColorDark = androidx.compose.ui.graphics.Color(0xFFF1F5F9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogScope.ActionDialog(
    title: String = "",
    description: String = "",
    confirmEnabled: Boolean = true,
    compact: Boolean = false,
    /**
     * Optional width cap for dialogs that opt out of the (often narrow, especially in
     * landscape) platform default width via [properties]. Keeps 16dp screen-edge margins.
     */
    maxWidth: Dp? = null,
    contentPadding: Dp? = null,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    confirmColor: androidx.compose.ui.graphics.Color? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    onDismissRequest: () -> Unit = {},
    onConfirmRequest: () -> Unit = {},
    content: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    
    val accentColor = getAccentColor()
    val dialogBackground = if (isDarkMode) DialogBackgroundDark else DialogBackgroundLight
    val titleColor = if (isDarkMode) TitleColorDark else TitleColorLight
    val labelColor = if (isDarkMode) TitleColorDark else androidx.compose.ui.graphics.Color(0xFF334155)
    val subLabelColor = androidx.compose.ui.graphics.Color(0xFF94A3B8)
    val dialogPadding = contentPadding ?: if (compact) 14.dp else 20.dp
    val titleSpacing = if (compact) 4.dp else 8.dp
    val descriptionSpacing = if (compact) 4.dp else 8.dp
    val buttonSpacing = if (compact) 6.dp else 12.dp
    
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Surface(
            modifier = Modifier
                .then(
                    when {
                        compact -> Modifier.widthIn(max = 300.dp).wrapContentWidth()
                        maxWidth != null ->
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .widthIn(max = maxWidth)
                        else -> Modifier.wrapContentWidth()
                    }
                )
                .wrapContentHeight(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = dialogBackground,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(dialogPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = titleColor,
                        fontSize = settingsTitleTextSize(),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    if (titleTrailing != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        titleTrailing.invoke()
                    }
                }
                Spacer(modifier = Modifier.height(titleSpacing))
                if (description.isNotBlank()) {
                    CollapsibleDescriptionText(
                        text = description,
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = 0.dp,
                    )
                    Spacer(modifier = Modifier.height(descriptionSpacing))
                }
                if (compact) {
                    content()
                } else {
                    Box(modifier = Modifier.weight(weight = 1f, fill = false)) {
                        content()
                    }
                }
                Spacer(modifier = Modifier.height(buttonSpacing))
                Row(
                    modifier = Modifier.align(Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            closeDialog()
                        }
                    ) {
                        Text(
                            text = dismissLabel ?: stringResource(R.string.label_cancel),
                            color = subLabelColor,
                            fontSize = settingsTitleTextSize()
                        )
                    }
                    val confirmTint = confirmColor ?: accentColor
                    TextButton(
                        enabled = confirmEnabled,
                        onClick = {
                            onConfirmRequest()
                            closeDialog()
                        }
                    ) {
                        Text(
                            text = confirmLabel ?: stringResource(R.string.label_ok),
                            color = if (confirmEnabled) confirmTint else subLabelColor,
                            fontSize = settingsTitleTextSize(),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Stable
class DialogScope {
    private val _isDialogOpen = MutableStateFlow(false)
    val isDialogOpen get() = _isDialogOpen.asStateFlow()

    fun openDialog() {
        _isDialogOpen.value = true
    }

    fun closeDialog() {
        _isDialogOpen.value = false
    }
}
