package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize

@Composable
fun <T> SelectSetting(
    name: String,
    description: String = "",
    selected: T?,
    items: List<T>?,
    enabled: Boolean = true,
    key: ((T) -> Any)? = null,
    value: (T?) -> String = { it.toString() },
    itemDescription: ((T) -> String?)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    onConfirmRequest: (T?) -> Unit = {}
) {
    DialogSettingItem(
        name = name,
        description = description,
        value = selectSettingDisplayedValue(selected, value),
        enabled = enabled,
    ) {
        SelectDialog(
            title = name,
            description = description,
            selected = selected,
            items = items,
            key = key,
            value = value,
            itemDescription = itemDescription,
            titleTrailing = titleTrailing,
            onConfirmRequest = onConfirmRequest
        )
    }
}

@Composable
fun <T> DialogScope.SelectDialog(
    title: String = "",
    description: String = "",
    selected: T?,
    items: List<T>?,
    key: ((T) -> Any)? = null,
    value: (T) -> String = { it.toString() },
    itemDescription: ((T) -> String?)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    onConfirmRequest: (T?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    
    val accentColor = getAccentColor()
    val labelColor = if (isDarkMode) androidx.compose.ui.graphics.Color(0xFFF1F5F9) else androidx.compose.ui.graphics.Color(0xFF334155)
    
    var selectedItem by remember { mutableStateOf(selected) }
    ActionDialog(
        title = title,
        description = description,
        titleTrailing = titleTrailing,
        onConfirmRequest = {
            onConfirmRequest(selectedItem)
        }
    ) {
        if (items != null) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(
                    items = items,
                    key = key
                ) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .settingsFocusHighlight(cornerRadius = 12.dp, horizontalOutset = 4.dp)
                            .selectable(
                                selected = (item == selectedItem),
                                onClick = { selectedItem = item },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            selected = item == selectedItem,
                            onClick = null,
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = accentColor,
                                unselectedColor = labelColor.copy(alpha = 0.6f)
                            )
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        )
                        {
                            Text(
                                text = value(item),
                                fontSize = settingsTitleTextSize(),
                                color = if (item == selectedItem) accentColor else labelColor
                            )
                            val optionDescription = itemDescription?.invoke(item)
                            if (!optionDescription.isNullOrBlank()) {
                                CollapsibleDescriptionText(
                                    text = optionDescription,
                                    fontSize = settingsBodyTextSize(),
                                    lineHeight = settingsBodyLineHeight(),
                                    color = getSettingsDescriptionColor(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Same row as [SelectSetting] (title left, current value + chevron right,
 * description under the title). Menu is the shared [ThemedDropdownMenu].
 */
@Composable
fun <T> DropdownSelectSetting(
    name: String,
    description: String = "",
    selected: T,
    items: List<T>,
    value: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = value(selected)
    SettingItem(
        // Plain clickable: SettingItem draws the D-pad focus ring already.
        modifier = if (enabled) {
            Modifier.clickable { expanded = true }
        } else {
            Modifier.alpha(0.5f)
        },
        name = name,
        description = description,
        value = selectedLabel,
    ) {
        Box {
            if (description.isNotBlank() && selectedLabel.isNotBlank()) {
                SettingsChevronIcon(tint = Color(0xFF94A3B8))
            }
            ThemedDropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
            ) {
                items.forEach { item ->
                    ThemedDropdownMenuItem(
                        text = value(item),
                        selected = item == selected,
                        onClick = {
                            onSelect(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Row label for [SelectSetting]. Kotlin compiles `when (enum)` to `enum.ordinal()`,
 * so a null [selected] passed into a lambda without `null ->` crashes composition.
 * Still invoke the lambda for null so callers like wake-word "none" keep their label;
 * only swallow the ordinal NPE.
 */
private fun <T> selectSettingDisplayedValue(selected: T?, value: (T?) -> String): String {
    if (selected != null) return value(selected)
    return try {
        value(null)
    } catch (_: NullPointerException) {
        ""
    }
}
