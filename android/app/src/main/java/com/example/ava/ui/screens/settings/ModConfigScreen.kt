package com.example.ava.ui.screens.settings

import com.example.ava.ui.AvaToast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.mods.ModConfigItem
import com.example.ava.mods.ModManager
import com.example.ava.mods.ModManagerBridge
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.screens.settings.components.IntSetting
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.SwitchSetting
import com.example.ava.ui.screens.settings.components.TextSetting
import kotlinx.coroutines.launch

@Composable
fun ModConfigScreen(
    navController: NavController,
    modId: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val modManager = remember { ModManager.getInstance(context) }
    LaunchedEffect(modId) {
        modManager.reloadManifestFromDisk(modId)
    }
    val manifestCache by modManager.manifestCache.collectAsStateWithLifecycle()
    val manifest = remember(manifestCache, modId) {
        manifestCache[modId] ?: modManager.getModManifest(modId)
    }
    LaunchedEffect(modId, manifest) {
        val current = manifest ?: return@LaunchedEffect
        val resolved = modManager.getResolvedConfig(modId, current)
        ModManagerBridge.syncConfig(
            modId = modId,
            managerClassName = current.manager,
            context = context,
            configValues = resolved,
        )
    }

    val configItems = manifest?.config.orEmpty()
    val resolvedConfig = remember(manifest, modId) {
        modManager.getResolvedConfig(modId, manifest)
    }
    val values = remember(modId, manifest) {
        mutableStateMapOf<String, String>().apply { putAll(resolvedConfig) }
    }
    val hasDownloadStrip = remember(manifest) {
        manifest?.statusPanel?.any { it.type == "download_strip" } == true
    }
    val inlineKeys = remember(manifest) {
        manifest?.statusPanel
            .orEmpty()
            .filter { it.type == "download_strip" }
            .flatMap { it.inlineConfigKeys.orEmpty() }
            .toSet()
    }
    val mainConfigItems = remember(configItems, values.toMap(), inlineKeys) {
        configItems.filter { it.key !in inlineKeys && it.isVisible(values) }
    }

    SettingsDetailScreen(
        navController = navController,
        title = manifest?.name.safeText().ifBlank { modId }
    ) {
        if (!manifest?.description.safeText().isBlank()) {
            item {
                SimpleCard {
                    Column(modifier = Modifier.padding(vertical = 14.dp)) {
                        Text(
                            text = manifest?.description.safeText(),
                            fontSize = 13.sp,
                            color = getLabelColor(),
                            lineHeight = settingsBodyLineHeight()
                        )
                        val detail = if (hasDownloadStrip) "" else manifest?.detailDescription.safeText()
                        if (detail.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = detail,
                                fontSize = 12.sp,
                                color = getTitleColor().copy(alpha = 0.72f),
                                lineHeight = settingsBodyLineHeight()
                            )
                        }
                    }
                }
            }
        }

        val currentManifest = manifest
        if (currentManifest != null && currentManifest.statusPanel.any { it.type == "download_strip" }) {
            item {
                ModModelManagerSection(
                    modId = modId,
                    manifest = currentManifest,
                    configItems = configItems,
                    configValues = values.toMap(),
                    onConfigChange = { key, value ->
                        values[key] = value
                        val updated = values.toMap()
                        saveConfig(modId, updated, modManager, context, coroutineScope)
                        coroutineScope.launch {
                            ModManagerBridge.syncConfig(
                                modId = modId,
                                managerClassName = currentManifest.manager,
                                context = context,
                                configValues = updated,
                            )
                        }
                    },
                )
            }
        } else if (currentManifest != null && currentManifest.statusPanel.isNotEmpty()) {
            item {
                ModStatusPanelSection(
                    modId = modId,
                    manifest = currentManifest,
                    configValues = values.toMap(),
                )
            }
        }

        item {
            SimpleCard {
                if (mainConfigItems.isEmpty()) {
                    SettingRow(
                        label = context.getString(R.string.mod_store_manage),
                        subLabel = if (manifest?.statusPanel?.isNotEmpty() == true) {
                            context.getString(R.string.mod_store_status_only)
                        } else {
                            context.getString(R.string.mod_store_no_config)
                        }
                    ) {}
                } else {
                    mainConfigItems.forEachIndexed { index, item ->
                        when (item.type) {
                            "switch" -> {
                                SwitchSetting(
                                    name = item.label.safeText(),
                                    description = item.description.safeText(),
                                    value = values[item.key].safeText()
                                        .toBooleanOrDefault(item.defaultValue.safeText(), true)
                                ) { enabled ->
                                    values[item.key] = enabled.toString()
                                    saveConfig(modId, values.toMap(), modManager, context, coroutineScope)
                                }
                            }

                            "number" -> {
                                IntSetting(
                                    name = item.label.safeText(),
                                    description = item.description.safeText(),
                                    value = values[item.key].safeText().toIntOrNull()
                                        ?: item.defaultValue.safeText().toIntOrNull()
                                        ?: item.min?.toInt()
                                        ?: 0,
                                    validation = { input ->
                                        validateNumber(item, input)
                                    },
                                    onConfirmRequest = { input ->
                                        if (input != null) {
                                            values[item.key] = input.toString()
                                            saveConfig(modId, values.toMap(), modManager, context, coroutineScope)
                                        }
                                    }
                                )
                            }

                            "select" -> {
                                val options = item.options.orEmpty().filterNotNull()
                                SelectSetting(
                                    name = item.label.safeText(),
                                    description = item.dialogDescription(),
                                    selected = values[item.key].safeText().ifBlank {
                                        item.defaultValue.safeText().ifBlank { options.firstOrNull().orEmpty() }
                                    },
                                    items = options,
                                    key = { it },
                                    value = { it ?: "" },
                                    onConfirmRequest = { selected ->
                                        if (selected != null) {
                                            values[item.key] = selected
                                            saveConfig(modId, values.toMap(), modManager, context, coroutineScope)
                                        }
                                    }
                                )
                            }

                            "text" -> {
                                TextSetting(
                                    name = item.label.safeText(),
                                    description = item.description.safeText(),
                                    dialogHint = item.dialogDescription(),
                                    value = values[item.key].safeText().ifBlank { item.defaultValue.safeText() },
                                    placeholder = item.placeholder.safeText(),
                                    onConfirmRequest = { newValue ->
                                        values[item.key] = sanitizeTextConfigValue(newValue)
                                        saveConfig(modId, values.toMap(), modManager, context, coroutineScope)
                                    }
                                )
                            }
                        }

                        if (index < mainConfigItems.lastIndex) {
                            SettingsDivider()
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        }
    }
}

private fun saveConfig(
    modId: String,
    values: Map<String, String>,
    modManager: ModManager,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    coroutineScope.launch {
        val result = modManager.saveModConfig(modId, values)
        if (result.isSuccess) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
            AvaToast.show(context, R.string.mod_store_restart_hint)
        } else {
            AvaToast.show(context, R.string.mod_store_download_failed)
        }
    }
}

private fun String?.toBooleanOrDefault(defaultValue: String?, fallback: Boolean): Boolean {
    return this?.toBooleanStrictOrNull()
        ?: defaultValue?.toBooleanStrictOrNull()
        ?: fallback
}

private fun validateNumber(item: ModConfigItem, input: Int?): String? {
    if (input == null) return null
    val min = item.min?.toInt()
    val max = item.max?.toInt()
    return when {
        min != null && input < min -> "Min $min"
        max != null && input > max -> "Max $max"
        else -> null
    }
}

private fun ModConfigItem.isVisible(values: Map<String, String>): Boolean {
    val dependencyKey = enabledWhen ?: return true
    return values[dependencyKey]?.toBooleanStrictOrNull() ?: false
}

private fun ModConfigItem.dialogDescription(): String {
    if (dialogHint.safeText().isNotBlank()) return dialogHint.safeText()
    val rangeText = buildString {
        if (min != null || max != null) {
            append("Range")
            if (min != null) append(" $min")
            if (max != null) append(" to $max")
        }
    }
    return listOf(description.safeText(), rangeText)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun String?.safeText(): String = this.orEmpty()

private fun sanitizeTextConfigValue(value: String?): String {
    if (value == null) return ""
    val normalized = value.replace('\r', '\n')
    val lines = normalized.split('\n')
    val keepSingleLine = lines.any { line ->
        val trimmed = line.trim()
        trimmed.startsWith("Use ") || trimmed.startsWith("+")
    } || lines.size > 1

    if (!keepSingleLine) {
        return normalized.trim()
    }

    return lines.firstNotNullOfOrNull { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("Use ") -> null
            trimmed.startsWith("+") -> null
            else -> trimmed
        }
    }.orEmpty()
}
