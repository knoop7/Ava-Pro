package com.example.ava.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.homeassistant.HaEngineOption
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.HaPipeline
import com.example.ava.homeassistant.HaPipelineCatalog
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingsGuideCard
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import java.util.Locale

@Composable
fun HaPipelineDetailScreen(
    navController: NavController,
    pipelineId: String,
) {
    val context = LocalContext.current
    val haManager = remember { HaManager.ensure(context) }
    val pipelines by haManager.pipelines.collectAsState()
    val preferredId by haManager.preferredPipelineId.collectAsState()
    val catalog by haManager.catalog.collectAsState()
    val settingsSeed = remember { haManager.settingsStore.getCached() }
    val pipelineGuideDismissed by haManager.settingsStore.pipelineGuideDismissed
        .collectAsStateWithLifecycle(settingsSeed.pipelineGuideDismissed)
    val pipeline = pipelines.find { it.id == pipelineId }
    val accent = getAccentColor()
    val isDark = isDarkModeEnabled()
    val isPreferred = pipelineId == preferredId
    var ttsVoices by remember { mutableStateOf<List<HaEngineOption>>(emptyList()) }

    LaunchedEffect(pipelineId) {
        if (catalog.conversationAgents.isEmpty() && catalog.sttEngines.isEmpty()) {
            haManager.refreshCatalog()
        }
    }

    LaunchedEffect(pipeline?.ttsEngine, pipeline?.ttsLanguage, pipeline?.language) {
        val engine = pipeline?.ttsEngine
        val language = pipeline?.ttsLanguage ?: pipeline?.language.orEmpty()
        ttsVoices = if (engine.isNullOrBlank() || language.isBlank()) {
            emptyList()
        } else {
            haManager.fetchTtsVoices(engine, language)
        }
    }

    SettingsDetailScreen(
        navController = navController,
        title = pipeline?.name ?: stringResource(R.string.settings_ha_pipeline_detail_title),
    ) {
        if (pipeline == null) {
            item {
                Text(
                    text = stringResource(R.string.settings_ha_pipeline_empty),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            return@SettingsDetailScreen
        }

        if (!pipelineGuideDismissed) {
            item(key = "pipeline_guide") {
                SettingsGuideCard(
                    text = stringResource(R.string.settings_ha_pipeline_guide),
                    onDismiss = { haManager.dismissPipelineGuide() },
                )
            }
        }

        item {
            SimpleCard {
                PipelinePreferredHeader(
                    name = pipeline.name,
                    isPreferred = isPreferred,
                    accent = accent,
                    onSelectPreferred = {
                        if (!isPreferred) haManager.setPreferredPipeline(pipelineId)
                    },
                )
                PipelineFieldDivider()
                PipelineSelectRow(
                    label = stringResource(R.string.settings_ha_pipeline_lang),
                    value = displayLanguage(pipeline.language),
                    accent = accent,
                    isDark = isDark,
                    options = languageOptions(catalog, pipeline),
                    selectedId = pipeline.language,
                    onSelect = { selected ->
                        applyLanguage(haManager, pipeline, catalog, selected.id)
                    },
                )
                PipelineFieldDivider()
                PipelineSelectRow(
                    label = stringResource(R.string.settings_ha_pipeline_conversation),
                    value = catalog.labelFor(pipeline.conversationEngine, catalog.conversationAgents),
                    accent = accent,
                    isDark = isDark,
                    options = catalog.conversationAgents,
                    selectedId = pipeline.conversationEngine.orEmpty(),
                    onSelect = { selected ->
                        haManager.updatePipeline(
                            pipelineId = pipelineId,
                            conversationEngine = selected.id,
                            conversationLanguage = selected.matchLanguage(pipeline.language)
                                ?: pipeline.language,
                        )
                    },
                )
                PipelineFieldDivider()
                PipelineSelectRow(
                    label = stringResource(R.string.settings_ha_pipeline_stt),
                    value = catalog.labelFor(pipeline.sttEngine, catalog.sttEngines),
                    accent = accent,
                    isDark = isDark,
                    options = catalog.sttEngines,
                    selectedId = pipeline.sttEngine.orEmpty(),
                    onSelect = { selected ->
                        haManager.updatePipeline(
                            pipelineId = pipelineId,
                            sttEngine = selected.id,
                            sttLanguage = selected.matchLanguage(pipeline.language)
                                ?: pipeline.sttLanguage
                                ?: pipeline.language,
                        )
                    },
                )
                PipelineFieldDivider()
                PipelineSelectRow(
                    label = stringResource(R.string.settings_ha_pipeline_tts),
                    value = catalog.labelFor(pipeline.ttsEngine, catalog.ttsEngines),
                    accent = accent,
                    isDark = isDark,
                    options = catalog.ttsEngines,
                    selectedId = pipeline.ttsEngine.orEmpty(),
                    onSelect = { selected ->
                        val ttsLanguage = selected.matchLanguage(pipeline.language)
                            ?: pipeline.ttsLanguage
                            ?: pipeline.language
                        haManager.updatePipeline(
                            pipelineId = pipelineId,
                            ttsEngine = selected.id,
                            ttsLanguage = ttsLanguage,
                        )
                    },
                )
                if (ttsVoices.isNotEmpty()) {
                    PipelineFieldDivider()
                    PipelineSelectRow(
                        label = stringResource(R.string.settings_ha_pipeline_tts_voice),
                        value = ttsVoices.find { it.id == pipeline.ttsVoice }?.name
                            ?: pipeline.ttsVoice
                            ?: "—",
                        accent = accent,
                        isDark = isDark,
                        options = ttsVoices,
                        selectedId = pipeline.ttsVoice.orEmpty(),
                        onSelect = { selected ->
                            haManager.updatePipeline(
                                pipelineId = pipelineId,
                                ttsVoice = selected.id,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelinePreferredHeader(
    name: String,
    isPreferred: Boolean,
    accent: Color,
    onSelectPreferred: () -> Unit,
) {
    val scale = rememberSettingsTextScale().coerceAtMost(1.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectPreferred)
            .padding(vertical = (12f * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PipelineRadio(selected = isPreferred, accent = accent)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = getLabelColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isPreferred) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_ha_pipeline_preferred),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PipelineSelectRow(
    label: String,
    value: String,
    accent: Color,
    isDark: Boolean,
    options: List<HaEngineOption>,
    selectedId: String,
    onSelect: (HaEngineOption) -> Unit,
) {
    val scale = rememberSettingsTextScale().coerceAtMost(1.4f)
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = options.isNotEmpty()) { showPicker = true }
            .padding(vertical = (12f * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = settingsTitleTextSize(base = 17f),
            fontWeight = FontWeight.Medium,
            color = getLabelColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = settingsBodyTextSize(),
            color = getSettingsDescriptionColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (options.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            SettingsChevronIcon(tint = if (isDark) Color(0xFF4B5563) else Color(0xFFD1D5DB))
        }
    }

    if (showPicker) {
        PipelineOptionPicker(
            title = label,
            options = options,
            selectedId = selectedId,
            accent = accent,
            onDismiss = { showPicker = false },
            onSelect = { option ->
                onSelect(option)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelineOptionPicker(
    title: String,
    options: List<HaEngineOption>,
    selectedId: String,
    accent: Color,
    onDismiss: () -> Unit,
    onSelect: (HaEngineOption) -> Unit,
) {
    val scale = rememberSettingsTextScale().coerceAtMost(1.5f)
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.46f).dp.coerceIn(180.dp, 420.dp)
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = getDialogBackground(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = settingsTitleTextSize(base = 18f),
                    color = getLabelColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsEdgeFadeScrollColumn(
                    maxHeight = listMaxHeight,
                    fadeHeight = 18.dp,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.forEach { option ->
                        val selected = option.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(option) }
                                .padding(horizontal = 8.dp, vertical = (10f * scale).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PipelineRadio(selected = selected, accent = accent)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = option.name,
                                fontSize = settingsTitleTextSize(base = 16f),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = getLabelColor(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineRadio(selected: Boolean, accent: Color) {
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
private fun PipelineFieldDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                if (isDarkModeEnabled()) Color(0xFF2A2A2A) else Color(0xFFF1F5F9)
            ),
    )
}

private fun languageOptions(
    catalog: HaPipelineCatalog,
    pipeline: HaPipeline,
): List<HaEngineOption> {
    val tags = linkedSetOf<String>()
    catalog.languages.forEach { tags.add(it) }
    if (pipeline.language.isNotBlank()) tags.add(pipeline.language)
    pipeline.conversationLanguage?.let { tags.add(it) }
    pipeline.sttLanguage?.let { tags.add(it) }
    pipeline.ttsLanguage?.let { tags.add(it) }
    return tags.map { HaEngineOption(id = it, name = displayLanguage(it)) }
}

private fun HaPipelineCatalog.labelFor(id: String?, options: List<HaEngineOption>): String {
    if (id.isNullOrBlank()) return "—"
    return options.find { it.id == id }?.name ?: id.substringAfterLast('.')
}

private fun applyLanguage(
    haManager: HaManager,
    pipeline: HaPipeline,
    catalog: HaPipelineCatalog,
    language: String,
) {
    val conversation = catalog.conversationAgents.find { it.id == pipeline.conversationEngine }
    val stt = catalog.sttEngines.find { it.id == pipeline.sttEngine }
    val tts = catalog.ttsEngines.find { it.id == pipeline.ttsEngine }
    haManager.updatePipeline(
        pipelineId = pipeline.id,
        language = language,
        conversationLanguage = conversation?.matchLanguage(language) ?: language,
        sttLanguage = stt?.matchLanguage(language) ?: pipeline.sttLanguage,
        ttsLanguage = tts?.matchLanguage(language) ?: pipeline.ttsLanguage,
    )
}

private fun displayLanguage(tag: String): String {
    if (tag.isBlank()) return "—"
    return try {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }.ifBlank { tag }
    } catch (_: Exception) {
        tag
    }
}
