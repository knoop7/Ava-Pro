package com.example.ava.ui.screens.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.mods.ModConfigItem
import com.example.ava.mods.ModManifest
import com.example.ava.mods.ModManagerBridge
import com.example.ava.mods.ModStateCallback
import com.example.ava.mods.ModStatusPanelAction
import com.example.ava.mods.ModStatusPanelItem
import com.example.ava.mods.orEmptyMod
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.settingsFocusHighlight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val modStatusMainHandler = Handler(Looper.getMainLooper())

private val DEFAULT_READY_KEYWORDS = listOf("ready", "complete", "done")
private val DEFAULT_ERROR_KEYWORDS = listOf("error", "failed", "fail")
private val DEFAULT_DOWNLOADING_KEYWORDS = listOf("downloading")
private val DEFAULT_PAUSED_KEYWORDS = listOf("paused", "pause")

private enum class DownloadStripPhase {
    Ready, Downloading, Paused, Error, Idle,
}

/**
 * Unified model manager card: [status_panel] download_strip + manifest-declared [inline_config_keys].
 * Mod authors declare compatibility in manifest; Ava renders one card — no split across config sections.
 */
@Composable
fun ModModelManagerSection(
    modId: String,
    manifest: ModManifest,
    configItems: List<ModConfigItem>,
    configValues: Map<String, String>,
    onConfigChange: (key: String, value: String) -> Unit,
) {
    val stripItems = manifest.statusPanel.filter { it.type == "download_strip" }
    if (stripItems.isEmpty()) return

    val inlineKeys = stripItems.flatMap { it.inlineConfigKeys.orEmpty() }.toSet()
    val inlineItems = configItems.filter { it.key in inlineKeys }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun invokeAction(methodName: String?) {
        if (methodName.isNullOrBlank()) return
        coroutineScope.launch {
            ModManagerBridge.invokeAction(
                modId = modId,
                managerClassName = manifest.manager,
                context = context,
                methodName = methodName,
                configValues = configValues,
            )
        }
    }

    SimpleCard {
        Column {
            stripItems.forEachIndexed { index, panelItem ->
                ModDownloadMinimalStripRow(
                    modId = modId,
                    manifest = manifest,
                    item = panelItem,
                    configValues = configValues,
                    onAction = ::invokeAction,
                )
                if (index < stripItems.lastIndex) {
                    SettingsDivider()
                }
            }

            inlineItems.forEachIndexed { index, configItem ->
                if (stripItems.isNotEmpty() || index > 0) {
                    SettingsDivider()
                }
                ModInlineConfigSelectRow(
                    item = configItem,
                    selected = configValues[configItem.key].orEmptyMod().ifBlank {
                        configItem.defaultValue.orEmptyMod()
                    },
                    onSelected = { value -> onConfigChange(configItem.key, value) },
                )
            }
        }
    }
}

/** @deprecated Use [ModModelManagerSection] from mod config screen. */
@Composable
fun ModStatusPanelSection(
    modId: String,
    manifest: ModManifest,
    configValues: Map<String, String>,
    includeCard: Boolean = true,
) {
    val items = manifest.statusPanel.filter { it.type != "download_strip" }
    if (items.isEmpty()) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun invokeAction(methodName: String?) {
        if (methodName.isNullOrBlank()) return
        coroutineScope.launch {
            ModManagerBridge.invokeAction(
                modId = modId,
                managerClassName = manifest.manager,
                context = context,
                methodName = methodName,
                configValues = configValues,
            )
        }
    }

    val panelContent: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            items.forEachIndexed { index, panelItem ->
                ModStatusPanelRow(
                    modId = modId,
                    manifest = manifest,
                    item = panelItem,
                    configValues = configValues,
                    onAction = { invokeAction(panelItem.press) },
                )
                if (index < items.lastIndex) {
                    SettingsDivider()
                }
            }
        }
    }

    if (includeCard) {
        SimpleCard { panelContent() }
    } else {
        panelContent()
    }
}

@Composable
private fun ModDownloadMinimalStripRow(
    modId: String,
    manifest: ModManifest,
    item: ModStatusPanelItem,
    configValues: Map<String, String>,
    onAction: (String?) -> Unit,
) {
    val state = rememberDownloadStripState(modId, manifest, item, configValues)
    val accent = getAccentColor()
    val title = getTitleColor()
    val track = getSliderInactiveColor()
    val visibleActions = state.visibleActions(item.actions.orEmpty())
    val statusLabel = when (state.phase) {
        DownloadStripPhase.Ready -> stringResource(R.string.mod_store_strip_ready)
        DownloadStripPhase.Error -> stringResource(R.string.mod_store_strip_error)
        DownloadStripPhase.Downloading -> stringResource(R.string.mod_store_strip_downloading)
        DownloadStripPhase.Paused -> stringResource(R.string.mod_store_strip_paused)
        else -> stringResource(R.string.mod_store_strip_not_downloaded)
    }
    val barProgress = when (state.phase) {
        DownloadStripPhase.Ready -> 1f
        else -> (state.progress / 100f).coerceIn(0f, 1f)
    }
    val percentLabel = when (state.phase) {
        DownloadStripPhase.Ready -> 100
        else -> state.progress.toInt().coerceIn(0, 100)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.label.isNotBlank()) {
                    Text(
                        text = item.label,
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = " · ",
                        fontSize = settingsCaptionTextSize(),
                        color = getSettingsDescriptionColor(),
                    )
                }
                Text(
                    text = statusLabel,
                    fontSize = settingsCaptionTextSize(),
                    color = getSettingsDescriptionColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$percentLabel%",
                fontSize = settingsCaptionTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
            )
        }

        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                fontSize = settingsCaptionTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = settingsCaptionTextSize() * 1.3f,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { barProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accent,
                trackColor = track,
            )
            if (visibleActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    visibleActions.forEach { action ->
                        ModDownloadStripTextButton(
                            action = action,
                            onClick = { onAction(action.press) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun resolveDownloadStripActionLabel(action: ModStatusPanelAction): String {
    return when (action.press.orEmptyMod().lowercase()) {
        "downloadmodel", "resumedownload" -> stringResource(R.string.mod_store_strip_action_download)
        "pausedownload" -> stringResource(R.string.mod_store_strip_action_pause)
        "deletemodel" -> stringResource(R.string.mod_store_delete)
        else -> action.label.orEmptyMod().ifBlank { action.press.orEmptyMod() }
    }
}

@Composable
private fun ModDownloadStripTextButton(
    action: ModStatusPanelAction,
    onClick: () -> Unit,
) {
    val label = resolveDownloadStripActionLabel(action)
    val style = action.style.orEmptyMod().lowercase()
    val color = when (style) {
        "destructive" -> getTitleColor().copy(alpha = 0.55f)
        "secondary" -> getTitleColor().copy(alpha = 0.72f)
        else -> getAccentColor()
    }
    TextButton(
        onClick = onClick,
        modifier = Modifier.settingsFocusHighlight(cornerRadius = 10.dp, horizontalOutset = 0.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            text = label,
            fontSize = settingsBodyTextSize(),
            fontWeight = if (style == "primary") FontWeight.Medium else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModInlineConfigSelectRow(
    item: ModConfigItem,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val options = item.options.orEmpty().filterNotNull().filter { it != "auto" }
    SelectSetting(
        name = item.label.orEmptyMod(),
        description = "",
        selected = selected.ifBlank { item.defaultValue.orEmptyMod().ifBlank { options.firstOrNull().orEmpty() } },
        items = options,
        key = { it },
        value = { formatLanguageOptionLabel(it) },
        onConfirmRequest = { choice ->
            if (choice != null) {
                onSelected(choice)
            }
        },
    )
}

private data class DownloadStripState(
    val progress: Float,
    val statusText: String,
    val phase: DownloadStripPhase,
) {
    fun visibleActions(actions: List<ModStatusPanelAction>): List<ModStatusPanelAction> {
        val phaseName = phase.name.lowercase()
        return actions.filter { action ->
            val whenList = action.showWhen.orEmpty()
            whenList.isEmpty() || whenList.any { it.equals(phaseName, ignoreCase = true) }
        }
    }
}

@Composable
private fun rememberDownloadStripState(
    modId: String,
    manifest: ModManifest,
    item: ModStatusPanelItem,
    configValues: Map<String, String>,
): DownloadStripState {
    var progressValue by remember(item.read, item.listenerId) { mutableStateOf<Any?>(null) }
    var statusValue by remember(item.statusRead, item.statusListenerId) { mutableStateOf<Any?>(null) }

    ModStatusValueSubscription(
        modId = modId,
        manifest = manifest,
        readMethod = item.read,
        listenerId = item.listenerId,
        configValues = configValues,
        onValue = { progressValue = it },
    )
    ModStatusValueSubscription(
        modId = modId,
        manifest = manifest,
        readMethod = item.statusRead,
        listenerId = item.statusListenerId,
        configValues = configValues,
        onValue = { statusValue = it },
    )

    val progress = parseProgressPercent(progressValue)
    val statusText = formatStatusValue(statusValue)
    val readyKeywords = item.readyKeywords.orEmpty().ifEmpty { DEFAULT_READY_KEYWORDS }
    val errorKeywords = item.errorKeywords.orEmpty().ifEmpty { DEFAULT_ERROR_KEYWORDS }
    val downloadingKeywords = item.downloadingKeywords.orEmpty().ifEmpty { DEFAULT_DOWNLOADING_KEYWORDS }
    val pausedKeywords = item.pausedKeywords.orEmpty().ifEmpty { DEFAULT_PAUSED_KEYWORDS }

    val phase = resolveDownloadStripPhase(
        progress = progress,
        statusText = statusText,
        readyKeywords = readyKeywords,
        errorKeywords = errorKeywords,
        downloadingKeywords = downloadingKeywords,
        pausedKeywords = pausedKeywords,
    )

    return DownloadStripState(progress = progress, statusText = statusText, phase = phase)
}

@Composable
private fun ModStatusPanelRow(
    modId: String,
    manifest: ModManifest,
    item: ModStatusPanelItem,
    configValues: Map<String, String>,
    onAction: () -> Unit,
) {
    var displayValue by remember(item.read, item.press) { mutableStateOf<Any?>(null) }

    ModStatusValueSubscription(
        modId = modId,
        manifest = manifest,
        readMethod = item.read,
        listenerId = item.listenerId,
        configValues = configValues,
        onValue = { displayValue = it },
    )

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        if (item.type != "button") {
            Text(
                text = item.label,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
        }
        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                fontSize = 11.sp,
                color = getLabelColor(),
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        when (item.type) {
            "progress" -> {
                val percent = parseProgressPercent(displayValue)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = getAccentColor(),
                    trackColor = getSliderInactiveColor(),
                )
                Text(
                    text = "${percent.toInt()}%",
                    fontSize = 12.sp,
                    color = getLabelColor(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            "button" -> {
                Spacer(modifier = Modifier.height(6.dp))
                ModDownloadStripTextButton(
                    action = ModStatusPanelAction(label = item.label, press = item.press.orEmptyMod()),
                    onClick = onAction,
                )
            }
            else -> {
                val text = formatStatusValue(displayValue)
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        color = getLabelColor(),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModStatusValueSubscription(
    modId: String,
    manifest: ModManifest,
    readMethod: String?,
    listenerId: String?,
    configValues: Map<String, String>,
    onValue: (Any?) -> Unit,
) {
    val context = LocalContext.current
    val latestConfigValues by rememberUpdatedState(configValues)
    val configKey = remember(configValues) {
        configValues.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
    }

    LaunchedEffect(modId, readMethod, listenerId) {
        if (readMethod.isNullOrBlank()) return@LaunchedEffect

        fun readCurrentValue(): Any? {
            return ModManagerBridge.readValue(
                modId = modId,
                managerClassName = manifest.manager,
                context = context,
                methodName = readMethod,
                configValues = latestConfigValues,
            )
        }

        fun publish(value: Any?) {
            modStatusMainHandler.post { onValue(value) }
        }

        if (!listenerId.isNullOrBlank()) {
            val registered = ModManagerBridge.registerStateListener(
                modId = modId,
                managerClassName = manifest.manager,
                context = context,
                listenerId = listenerId,
                callback = object : ModStateCallback() {
                    override fun onStateChanged(value: Any?) {
                        publish(value)
                    }
                },
                configValues = latestConfigValues,
            )
            if (registered) {
                publish(readCurrentValue())
                return@LaunchedEffect
            }
        }

        while (isActive) {
            publish(readCurrentValue())
            delay(1000L)
        }
    }

    LaunchedEffect(modId, readMethod, configKey) {
        if (readMethod.isNullOrBlank()) return@LaunchedEffect
        ModManagerBridge.syncConfig(
            modId = modId,
            managerClassName = manifest.manager,
            context = context,
            configValues = latestConfigValues,
        )
        val refreshed = ModManagerBridge.readValue(
            modId = modId,
            managerClassName = manifest.manager,
            context = context,
            methodName = readMethod,
            configValues = latestConfigValues,
        )
        modStatusMainHandler.post { onValue(refreshed) }
    }
}

private fun resolveDownloadStripPhase(
    progress: Float,
    statusText: String,
    readyKeywords: List<String>,
    errorKeywords: List<String>,
    downloadingKeywords: List<String>,
    pausedKeywords: List<String>,
): DownloadStripPhase {
    if (isReadyState(progress, statusText, readyKeywords)) return DownloadStripPhase.Ready
    if (isErrorState(statusText, errorKeywords)) return DownloadStripPhase.Error
    if (isPausedState(statusText, pausedKeywords)) return DownloadStripPhase.Paused
    if (isDownloadingState(progress, statusText, downloadingKeywords)) return DownloadStripPhase.Downloading
    return DownloadStripPhase.Idle
}

private fun parseProgressPercent(value: Any?): Float {
    return when (value) {
        is Number -> value.toFloat().coerceIn(0f, 100f)
        is String -> value.toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f
        else -> 0f
    }
}

private fun isReadyState(progress: Float, statusText: String, readyKeywords: List<String>): Boolean {
    if (statusText.isBlank()) return false
    val normalized = statusText.lowercase()
    return readyKeywords.any { keyword ->
        keyword.isNotBlank() && normalized.contains(keyword.lowercase())
    }
}

private fun isErrorState(statusText: String, errorKeywords: List<String>): Boolean {
    if (statusText.isBlank()) return false
    val normalized = statusText.lowercase()
    return errorKeywords.any { keyword ->
        keyword.isNotBlank() && normalized.contains(keyword.lowercase())
    }
}

private fun isPausedState(statusText: String, pausedKeywords: List<String>): Boolean {
    if (statusText.isBlank()) return false
    val normalized = statusText.lowercase()
    return pausedKeywords.any { keyword ->
        keyword.isNotBlank() && normalized.contains(keyword.lowercase())
    }
}

private fun isDownloadingState(
    progress: Float,
    statusText: String,
    downloadingKeywords: List<String>,
): Boolean {
    if (progress in 1f..99f) return true
    if (statusText.isBlank()) return false
    val normalized = statusText.lowercase()
    return downloadingKeywords.any { keyword ->
        keyword.isNotBlank() && normalized.contains(keyword.lowercase())
    }
}

private fun formatStatusValue(value: Any?): String {
    return when (value) {
        null -> ""
        is Boolean -> if (value) "On" else "Off"
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
        }
        else -> value.toString()
    }
}

private fun formatLanguageOptionLabel(code: String?): String {
    return when (code?.lowercase()) {
        "en" -> "English"
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "yue" -> "Cantonese"
        else -> code.orEmpty()
    }
}
