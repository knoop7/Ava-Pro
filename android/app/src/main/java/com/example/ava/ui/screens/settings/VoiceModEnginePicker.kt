package com.example.ava.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.mods.ModManager
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.launch

@Composable
fun VoiceModEnginePickerScreen(
    navController: NavController,
    title: String,
    caption: String,
    nativeTitle: String,
    nativeDesc: String,
    modTitle: String,
    modDesc: String,
    noteIntroBody: String,
    noteIp: String,
    notePort: Int,
    modId: String,
    pipelineSelected: Boolean,
    onSelectPipeline: suspend () -> Unit,
    onSelectMod: suspend () -> Unit,
    stillModSelected: suspend () -> Boolean,
    revertToPipeline: suspend () -> Unit,
    selectOnlyAfterDownload: Boolean = false,
    footer: LazyListScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mods = remember { ModManager.getInstance(context) }
    val installedMods by mods.installedMods.collectAsStateWithLifecycle()
    val storeMods by mods.storeMods.collectAsStateWithLifecycle()
    val downloadProgress by mods.downloadProgress.collectAsStateWithLifecycle()
    val accent = getAccentColor()

    var selectingMod by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var keepPendingDownload by remember { mutableStateOf(false) }

    val installed = installedMods.any { it.id == modId }
    val storeMod = storeMods.find { it.id == modId }
    val version = installedMods.find { it.id == modId }?.version
        ?: storeMod?.version.orEmpty()
    val hasUpdate = installed && mods.hasUpdate(modId)

    LaunchedEffect(Unit) {
        mods.refreshStore()
    }

    SettingsDetailScreen(
        navController = navController,
        title = title,
    ) {
        item(key = "voice_mod_engines") {
            SimpleCard {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SettingsHelpBodyText(
                        text = caption,
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                    )
                    VoiceModEngineRow(
                        title = nativeTitle,
                        spec = nativeDesc,
                        selected = pipelineSelected,
                        accent = accent,
                        trailing = null,
                        onSelect = {
                            keepPendingDownload = false
                            scope.launch {
                                errorText = null
                                onSelectPipeline()
                                if (mods.isInstalled(modId) && mods.isEnabled(modId)) {
                                    mods.setModEnabled(modId, false)
                                }
                            }
                        },
                    )
                    VoiceModEngineDivider()
                    val showUpdate = hasUpdate && !selectingMod
                    val showConfig = !pipelineSelected && installed && !hasUpdate && !selectingMod
                    val showDownload = !installed && !selectingMod
                    VoiceModEngineRow(
                        title = modTitle,
                        spec = modDesc,
                        version = version,
                        selected = !pipelineSelected && (!selectOnlyAfterDownload || installed),
                        accent = accent,
                        trailingIcon = when {
                            showUpdate -> R.drawable.update_24px
                            showConfig -> R.drawable.settings_24px
                            else -> null
                        },
                        trailing = when {
                            showUpdate -> stringResource(R.string.mod_store_update)
                            showConfig -> stringResource(R.string.settings_voice_mod_open_config)
                            showDownload -> stringResource(R.string.local_llm_action_download)
                            else -> null
                        },
                        trailingClickable = showUpdate || showConfig || showDownload,
                        onTrailing = {
                            when {
                                showUpdate -> scope.launch {
                                    updateStoreModEngine(
                                        mods = mods,
                                        modId = modId,
                                        setBusy = { selectingMod = it },
                                        setError = { errorText = it },
                                    )
                                }
                                showConfig -> navController.navigate("${Screen.MOD_CONFIG}/$modId") {
                                    launchSingleTop = true
                                }
                                showDownload -> scope.launch {
                                    if (selectOnlyAfterDownload) {
                                        keepPendingDownload = true
                                        downloadThenSelectStoreMod(
                                            mods = mods,
                                            modId = modId,
                                            setBusy = { selectingMod = it },
                                            setError = { errorText = it },
                                            stillWanted = { keepPendingDownload },
                                            select = onSelectMod,
                                            stillSelected = stillModSelected,
                                        )
                                    } else {
                                        selectStoreModEngine(
                                            mods = mods,
                                            modId = modId,
                                            setBusy = { selectingMod = it },
                                            setError = { errorText = it },
                                            select = onSelectMod,
                                            stillSelected = stillModSelected,
                                            revert = revertToPipeline,
                                        )
                                    }
                                }
                            }
                        },
                        onSelect = {
                            if (selectOnlyAfterDownload && !installed) return@VoiceModEngineRow
                            scope.launch {
                                selectStoreModEngine(
                                    mods = mods,
                                    modId = modId,
                                    setBusy = { selectingMod = it },
                                    setError = { errorText = it },
                                    select = onSelectMod,
                                    stillSelected = stillModSelected,
                                    revert = revertToPipeline,
                                )
                            }
                        },
                    )
                    if (selectingMod) {
                        LinearProgressIndicator(
                            color = accent,
                            trackColor = getSliderInactiveColor(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 30.dp, top = 10.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        val phase = downloadProgress?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.local_llm_status_loading)
                        Text(
                            text = phase,
                            fontSize = settingsBodyTextSize(),
                            color = getSettingsDescriptionColor(),
                            modifier = Modifier.padding(start = 30.dp, top = 6.dp),
                        )
                    }
                    if (!errorText.isNullOrBlank()) {
                        Text(
                            text = errorText.orEmpty(),
                            fontSize = settingsBodyTextSize(),
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(start = 30.dp, top = 6.dp),
                        )
                    }
                    VoiceModEngineNote(
                        introBody = noteIntroBody,
                        ip = noteIp,
                        port = notePort,
                    )
                }
            }
        }
        footer()
    }
}

internal suspend fun selectStoreModEngine(
    mods: ModManager,
    modId: String,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    select: suspend () -> Unit,
    stillSelected: suspend () -> Boolean,
    revert: suspend () -> Unit,
) {
    setError(null)
    select()
    if (!mods.isInstalled(modId)) {
        setBusy(true)
        val refreshed = mods.refreshStore()
        val result = if (refreshed.isFailure) {
            refreshed.map { }
        } else {
            mods.downloadMod(modId)
        }
        setBusy(false)
        if (!stillSelected()) {
            if (result.isSuccess && mods.isInstalled(modId)) {
                mods.setModEnabled(modId, false)
            }
            return
        }
        if (result.isFailure) {
            setError(result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() })
            revert()
            return
        }
    }
    if (stillSelected() && mods.isInstalled(modId) && !mods.isEnabled(modId)) {
        val enabled = mods.setModEnabled(modId, true)
        if (enabled.isFailure) {
            setError(enabled.exceptionOrNull()?.message?.takeIf { it.isNotBlank() })
        }
    }
}

internal suspend fun downloadThenSelectStoreMod(
    mods: ModManager,
    modId: String,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    stillWanted: () -> Boolean,
    select: suspend () -> Unit,
    stillSelected: suspend () -> Boolean,
) {
    setError(null)
    setBusy(true)
    val refreshed = mods.refreshStore()
    val result = if (refreshed.isFailure) {
        refreshed.map { }
    } else {
        mods.downloadMod(modId)
    }
    setBusy(false)
    if (!stillWanted()) {
        if (result.isSuccess && mods.isInstalled(modId)) {
            mods.setModEnabled(modId, false)
        }
        return
    }
    if (result.isFailure || !mods.isInstalled(modId)) {
        setError(result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() })
        return
    }
    select()
    if (stillSelected() && !mods.isEnabled(modId)) {
        val enabled = mods.setModEnabled(modId, true)
        if (enabled.isFailure) {
            setError(enabled.exceptionOrNull()?.message?.takeIf { it.isNotBlank() })
        }
    }
}

internal suspend fun updateStoreModEngine(
    mods: ModManager,
    modId: String,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
) {
    setError(null)
    setBusy(true)
    val refreshed = mods.refreshStore()
    val result = if (refreshed.isFailure) {
        refreshed.map { }
    } else {
        mods.downloadMod(modId)
    }
    setBusy(false)
    if (result.isFailure) {
        setError(result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() })
    }
}

@Composable
private fun VoiceModEngineRow(
    title: String,
    spec: String,
    selected: Boolean,
    accent: Color,
    version: String? = null,
    @DrawableRes trailingIcon: Int? = null,
    trailing: String?,
    trailingClickable: Boolean = false,
    onTrailing: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceModEngineRadio(selected = selected, accent = accent)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = settingsTitleTextSize(),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = getLabelColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!version.isNullOrBlank()) {
                    Text(
                        text = version,
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                text = spec,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                modifier = if (trailingClickable && onTrailing != null) {
                    Modifier.clickable(onClick = onTrailing)
                } else {
                    Modifier
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (trailingIcon != null) {
                    Icon(
                        painter = painterResource(trailingIcon),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = trailing,
                    fontSize = if (trailingIcon != null) settingsTitleTextSize() else settingsBodyTextSize(),
                    fontWeight = if (trailingClickable) FontWeight.Medium else FontWeight.Normal,
                    color = if (trailingClickable || trailingIcon != null) {
                        accent
                    } else {
                        getSettingsDescriptionColor()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceModEngineNote(
    introBody: String,
    ip: String,
    port: Int,
) {
    val accent = getAccentColor()
    Column(modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)) {
        SettingsHelpBodyText(
            text = introBody,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        VoiceModNoteStep(1, stringResource(R.string.settings_voice_mod_note_step_service), accent)
        VoiceModNoteStep(2, stringResource(R.string.settings_voice_mod_note_step_wyoming), accent)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "3.",
                fontSize = settingsBodyTextSize(),
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.padding(end = 8.dp),
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_voice_mod_note_fill),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
                Text(
                    text = stringResource(R.string.settings_voice_mod_note_ip),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
                VoiceModNoteCode(ip)
                Text(
                    text = stringResource(R.string.settings_voice_mod_note_port),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
                VoiceModNoteCode(port.toString())
            }
        }
        SettingsHelpBodyText(
            text = stringResource(R.string.settings_voice_mod_note_footer),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun VoiceModNoteStep(index: Int, text: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$index.",
            fontSize = settingsBodyTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = text,
            fontSize = settingsBodyTextSize(),
            color = getSettingsDescriptionColor(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VoiceModNoteCode(text: String) {
    val isDark = isDarkModeEnabled()
    Text(
        text = text,
        fontSize = settingsCaptionTextSize(),
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        color = getLabelColor(),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isDark) Color(0xFF2A2A2A) else Color(0xFFEEF2FF))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun VoiceModEngineRadio(selected: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(
                width = 2.dp,
                color = if (selected) accent else getSettingsDescriptionColor(),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

@Composable
private fun VoiceModEngineDivider(vertical: Dp = 0.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = vertical)
            .height(1.dp)
            .background(if (isDarkModeEnabled()) Color(0xFF2A2A2A) else Color(0xFFF1F5F9)),
    )
}
