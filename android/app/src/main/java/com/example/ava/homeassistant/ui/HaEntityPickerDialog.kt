package com.example.ava.homeassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.ava.R
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.entity.HaEntityDomainFilter
import com.example.ava.homeassistant.entity.HaEntityPickerLimit
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor

/**
 * Entity picker dialog. Search + edge-fade list stay in the middle;
 * confirm / cancel stay pinned at the bottom so the keyboard cannot cover them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaEntityPickerDialog(
    title: String,
    currentValue: String,
    domainFilter: HaEntityDomainFilter = HaEntityDomainFilter.All,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    val haManager = remember { HaManager.ensure(context) }
    val entities by haManager.entities.collectAsState()
    val loading by haManager.entityLoading.collectAsState()
    val accent = getAccentColor()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val availableHeight = (screenHeight - imeBottom - 32.dp).coerceAtLeast(280.dp)
    val dialogMaxHeight = (availableHeight * 0.92f).coerceAtMost(560.dp)
    val listMaxHeight = (dialogMaxHeight - 220.dp).coerceIn(96.dp, 260.dp)

    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf(currentValue) }

    LaunchedEffect(Unit) {
        if (entities.isEmpty()) haManager.refreshEntities()
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .heightIn(max = dialogMaxHeight)
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = getDialogBackground(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getLabelColor(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                HaEntitySearchBox(
                    entities = entities,
                    query = query,
                    loading = loading,
                    accent = accent,
                    filter = domainFilter,
                    selectedId = selectedId,
                    listMaxHeight = listMaxHeight,
                    limit = HaEntityPickerLimit,
                    onQueryChange = { query = it },
                    onSelect = { selectedId = it.entityId },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(android.R.string.cancel),
                            color = getSettingsDescriptionColor(),
                        )
                    }
                    TextButton(
                        onClick = { onConfirm(selectedId) },
                        enabled = selectedId.isNotBlank(),
                    ) {
                        Text(
                            text = stringResource(android.R.string.ok),
                            color = if (selectedId.isNotBlank()) accent else accent.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

fun isHaPickerAvailable(): Boolean {
    val mgr = HaManager.get() ?: return false
    val state = mgr.connectionState.value
    return state is com.example.ava.homeassistant.HaWsClient.ConnectionState.Connected
}

/** Starts HA if credentials exist, then gates on connection + the settings toggle. */
@Composable
fun rememberIsHaPickerAvailable(): Boolean {
    val context = LocalContext.current
    val haManager = remember { HaManager.ensure(context) }
    val connection by haManager.connectionState.collectAsState()
    val enabled by haManager.settingsStore.entityPickerEnabled.collectAsState(true)
    return enabled && connection is com.example.ava.homeassistant.HaWsClient.ConnectionState.Connected
}
