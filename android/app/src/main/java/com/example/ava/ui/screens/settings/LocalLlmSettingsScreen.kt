package com.example.ava.ui.screens.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.homeassistant.HaManager
import com.example.ava.localllm.LocalLlmManager
import com.example.ava.localllm.remote.RemoteAiClient
import com.example.ava.localllm.remote.RemoteAiManager
import com.example.ava.settings.LocalLlmPath
import com.example.ava.settings.REMOTE_AI_SLOT_MAX
import com.example.ava.settings.REMOTE_AI_HISTORY_TURNS_MAX
import com.example.ava.settings.REMOTE_AI_HISTORY_TURNS_MIN
import com.example.ava.settings.RemoteAiKind
import com.example.ava.settings.RemoteAiProfile
import com.example.ava.settings.defaultBaseUrl
import com.example.ava.settings.ready
import com.example.ava.settings.resolvedPath
import com.example.ava.settings.slotIndexOrNull
import com.example.ava.settings.slotList
import com.example.ava.settings.snapRemoteHistoryTurns
import com.example.ava.settings.voiceProfile
import com.example.ava.ui.AvaToast
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.components.BoxedSelectPopup
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.SettingsGuideCard
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LocalLlmSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { LocalLlmManager.getInstance(context) }
    val haManager = remember { HaManager.ensure(context) }
    val settings = manager.settingsStore
    val seed = remember { settings.getCached() }
    val haSeed = remember { haManager.settingsStore.getCached() }

    val llmSettings by settings.getFlow().collectAsStateWithLifecycle(seed)
    val path = llmSettings.resolvedPath()
    val haUrl by haManager.settingsStore.serverUrl.collectAsStateWithLifecycle(haSeed.serverUrl)
    val haToken by haManager.settingsStore.accessToken.collectAsStateWithLifecycle(haSeed.accessToken)
    val haSignedIn = haUrl.isNotBlank() && haToken.isNotBlank()
    val preferredPipelineId by haManager.preferredPipelineId.collectAsStateWithLifecycle()
    val pipelines by haManager.pipelines.collectAsStateWithLifecycle()
    val storedPreferred by haManager.settingsStore.preferredPipeline.collectAsStateWithLifecycle(haSeed.preferredPipeline)
    val guideDismissed by settings.guideDismissed.collectAsStateWithLifecycle(seed.guideDismissed)
    val overlayDismissed by settings.overlayDismissed.collectAsStateWithLifecycle(seed.overlayDismissed)
    val accent = getAccentColor()
    val needHaToken = stringResource(R.string.local_llm_ha_need_token)

    fun remindHaToken() {
        AvaToast.show(context, needHaToken, tag = "local-llm-ha-token", durationMs = AvaToast.LONG_MS)
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.local_llm_title),
    ) {
        if (!guideDismissed) {
            item(key = "llm_guide") {
                SettingsGuideCard(
                    text = stringResource(R.string.local_llm_guide),
                    onDismiss = { scope.launch { settings.guideDismissed.set(true) } },
                )
            }
        }

        item(key = "path_card") {
            CaptionCard(caption = "") {
                PathSegment(
                    path = path,
                    accent = accent,
                    overlayDismissed = overlayDismissed,
                    onOverlayDismiss = { scope.launch { settings.overlayDismissed.set(true) } },
                    onHaOpen = {
                        if (!haSignedIn) {
                            remindHaToken()
                            navController.navigate(Screen.SETTINGS_HA) { launchSingleTop = true }
                        } else {
                            val pipelineId = preferredPipelineId
                                ?: storedPreferred.takeIf { it.isNotBlank() }
                                ?: pipelines.firstOrNull()?.id
                            val route = if (!pipelineId.isNullOrBlank()) {
                                Screen.SETTINGS_HA_PIPELINE_DETAIL.replace("{pipelineId}", pipelineId)
                            } else {
                                Screen.SETTINGS_HA
                            }
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    },
                    onRemoteOpen = {
                        navController.navigate(Screen.SETTINGS_HA_LOCAL_LLM_REMOTE) {
                            launchSingleTop = true
                        }
                    },
                    onHa = {
                        if (!haSignedIn) remindHaToken()
                        scope.launch { settings.setPath(LocalLlmPath.HA) }
                    },
                    onRemote = { scope.launch { settings.setPath(LocalLlmPath.REMOTE) } },
                )
            }
        }
    }
}

@Composable
fun RemoteAiSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteStore = remember { RemoteAiManager.getInstance(context).settingsStore }
    val remoteSeed = remember { remoteStore.getCached() }
    val remoteSettings by remoteStore.getFlow().collectAsStateWithLifecycle(remoteSeed)
    val accent = getAccentColor()
    LaunchedEffect(Unit) { remoteStore.ensureNumberedSlots() }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.remote_ai_caption),
    ) {
        if (remoteSettings.slotList().size >= 2) {
            item(key = "remote_ai_fallback") {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.remote_ai_fallback),
                        subLabel = stringResource(R.string.remote_ai_fallback_desc),
                    ) {
                        ModernSwitch(
                            checked = remoteSettings.fallbackEnabled,
                            onCheckedChange = { on ->
                                scope.launch { remoteStore.fallbackEnabled.set(on) }
                            },
                        )
                    }
                }
            }
        }
        item(key = "remote_ai_card") {
            RemoteAiCard(
                settings = remoteSettings,
                accent = accent,
                onPrompt = {
                    navController.navigate(Screen.SETTINGS_HA_LOCAL_LLM_PROMPT) {
                        launchSingleTop = true
                    }
                },
                onSave = { profile ->
                    val missing = remoteAiMissing(context, profile)
                    if (missing != null) {
                        remoteAiToast(context, context.getString(R.string.remote_ai_save_missing, missing))
                    } else {
                        scope.launch {
                            remoteStore.upsert(profile)
                            val after = remoteStore.get()
                            val slot = profile.slotIndexOrNull() ?: 1
                            val line = when {
                                after.voiceProfile()?.id == profile.id -> R.string.remote_ai_saved_live
                                after.fallbackEnabled -> R.string.remote_ai_saved_backup
                                else -> R.string.remote_ai_saved_idle
                            }
                            remoteAiToast(context, context.getString(line, slot))
                        }
                    }
                },
                onUseSlot = { profile ->
                    val missing = remoteAiMissing(context, profile)
                    if (missing != null) {
                        remoteAiToast(context, context.getString(R.string.remote_ai_save_missing, missing))
                    } else {
                        scope.launch {
                            remoteStore.upsert(profile)
                            if (remoteStore.useSlot(profile.id)) {
                                remoteAiToast(
                                    context,
                                    context.getString(R.string.remote_ai_now_in_use, profile.slotIndexOrNull() ?: 1),
                                )
                            }
                        }
                    }
                },
                // Browsing keeps an edited draft; it never moves the live model.
                onKeepDraft = { draft -> scope.launch { remoteStore.upsert(draft) } },
                onAddSlot = { draft, opened ->
                    scope.launch {
                        if (draft != null) remoteStore.upsert(draft)
                        opened(remoteStore.addSlot())
                    }
                },
                onClearSlot = { id, opened -> scope.launch { opened(remoteStore.removeSlot(id)) } },
                onHistoryTurns = { n -> scope.launch { remoteStore.historyTurns.set(n) } },
                onStreaming = { on -> scope.launch { remoteStore.streaming.set(on) } },
                onThinking = { on -> scope.launch { remoteStore.thinking.set(on) } },
            )
        }
    }
}

@Composable
private fun RemoteAiCard(
    settings: com.example.ava.settings.RemoteAiSettings,
    accent: Color,
    onPrompt: () -> Unit,
    onSave: (RemoteAiProfile) -> Unit,
    onUseSlot: (RemoteAiProfile) -> Unit,
    onKeepDraft: (RemoteAiProfile) -> Unit,
    /** Draft to keep (or null), then the new slot id to open (null when full). */
    onAddSlot: (RemoteAiProfile?, (String?) -> Unit) -> Unit,
    /** Slot to drop, then the slot id to open next. */
    onClearSlot: (String, (String?) -> Unit) -> Unit,
    onHistoryTurns: (Int) -> Unit,
    onStreaming: (Boolean) -> Unit,
    onThinking: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val slots = remember(settings.profiles, settings.selectedId) { settings.slotList() }
    // Slot open in the editor. UI-only: viewing or adding a slot never changes the live model.
    var draftId by remember {
        mutableStateOf(slots.firstOrNull { it.id == settings.selectedId }?.id ?: slots.first().id)
    }
    val stored = slots.firstOrNull { it.id == draftId } ?: slots.first()
    val liveId = settings.voiceProfile()?.id
    var draftKind by remember(stored.id, stored.kind) { mutableStateOf(stored.kind) }
    var draftUrl by remember(stored.id, stored.baseUrl) { mutableStateOf(stored.baseUrl) }
    var draftModel by remember(stored.id, stored.model) { mutableStateOf(stored.model) }
    var draftToken by remember(stored.id, stored.token) { mutableStateOf(stored.token) }
    var draftTurns by remember(settings.historyTurns) {
        mutableStateOf(snapRemoteHistoryTurns(settings.historyTurns))
    }
    var fetchedModels by remember(stored.id) { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var modelMenu by remember(stored.id) { mutableStateOf(false) }
    val profile = stored.copy(
        kind = draftKind,
        baseUrl = draftUrl,
        model = draftModel,
        token = draftToken,
    )
    /**
     * Edited draft worth keeping when the user leaves this slot. An unfinished
     * edit to the live slot is not written: that would take voice off the air.
     */
    fun draftToKeep(): RemoteAiProfile? =
        profile.copy(enabled = true).takeIf { profile != stored && (profile.ready() || stored.id != liveId) }
    val kinds = remember { RemoteAiKind.entries.toList() }
    val anthropic = stringResource(R.string.remote_ai_kind_anthropic)
    val openai = stringResource(R.string.remote_ai_kind_openai)
    val openaiResponses = stringResource(R.string.remote_ai_kind_openai_responses)
    val ollama = stringResource(R.string.remote_ai_kind_ollama)
    val historyItems = remember {
        listOf(0) + (REMOTE_AI_HISTORY_TURNS_MIN..REMOTE_AI_HISTORY_TURNS_MAX).toList()
    }
    val turnsChipLabel = stringResource(R.string.remote_ai_history_n, draftTurns)
    val thinkChipLabel = stringResource(R.string.remote_ai_thinking)
    CaptionCard(caption = "") {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        val slotItems = slots.size + if (slots.size < REMOTE_AI_SLOT_MAX) 1 else 0
        val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val dotted = booleanArrayOf(false, true, true)
        val chipText = remember(turnsChipLabel, thinkChipLabel, textMeasurer, density) {
            fun widthOf(text: String): Float = with(density) {
                textMeasurer.measure(text = text, style = labelStyle, maxLines = 1).size.width.toDp().value
            }
            floatArrayOf(widthOf(turnsChipLabel), widthOf("SSE"), widthOf(thinkChipLabel)).sum()
        }
        val scales = remember(maxWidth, slotItems, chipText) {
            RemoteAiToolbarFit.scales(
                maxWidth.value,
                slotItems,
                chipText,
                RemoteAiToolbarFit.chipChrome(dotted),
            )
        }
        val chrome = remoteAiBarChrome(scales.slot, scales.text, scales.pad)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteAiSlotStrip(
                count = slots.size,
                selected = stored.slotIndexOrNull() ?: 1,
                accent = accent,
                chrome = chrome,
                modifier = Modifier.padding(start = chrome.slotInset),
                onSelect = { index ->
                    val next = slots.firstOrNull { it.slotIndexOrNull() == index } ?: return@RemoteAiSlotStrip
                    if (next.id == stored.id) return@RemoteAiSlotStrip
                    draftToKeep()?.let(onKeepDraft)
                    draftId = next.id
                    fetchedModels = emptyList()
                    modelMenu = false
                },
                onAdd = {
                    if (slots.size >= REMOTE_AI_SLOT_MAX) return@RemoteAiSlotStrip
                    onAddSlot(draftToKeep()) { id -> if (id != null) draftId = id }
                    fetchedModels = emptyList()
                    modelMenu = false
                },
            )
            Row(
                modifier = Modifier
                    .padding(start = chrome.groupGap)
                    .wrapContentWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(chrome.chipRowGap),
            ) {
                RemoteAiTurnsChip(
                    label = turnsChipLabel,
                    items = historyItems,
                    selected = draftTurns,
                    chrome = chrome,
                    onSelect = {
                        draftTurns = it
                        onHistoryTurns(it)
                    },
                )
                RemoteAiSseChip(
                    on = settings.streaming,
                    chrome = chrome,
                    onClick = { onStreaming(!settings.streaming) },
                )
                RemoteAiThinkChip(
                    on = settings.thinking,
                    chrome = chrome,
                    onClick = { onThinking(!settings.thinking) },
                )
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RemoteFadeField(
            value = draftUrl,
            label = stringResource(R.string.remote_ai_base_url),
            floatLabel = false,
            leading = {
                RemoteAiKindLead(
                    selected = draftKind,
                    items = kinds,
                    name = { kind ->
                        when (kind) {
                            RemoteAiKind.CLAUDE -> anthropic
                            RemoteAiKind.OPENAI -> openai
                            RemoteAiKind.OPENAI_RESPONSES -> openaiResponses
                            RemoteAiKind.OLLAMA -> ollama
                        }
                    },
                    onSelect = { kind ->
                        if (draftUrl.isBlank() || draftUrl == draftKind.defaultBaseUrl()) {
                            draftUrl = kind.defaultBaseUrl()
                        }
                        draftKind = kind
                        fetchedModels = emptyList()
                        modelMenu = false
                    },
                )
            },
        ) { draftUrl = it }
        RemoteFadeField(
            value = draftToken,
            label = stringResource(R.string.remote_ai_token),
            secret = true,
        ) { draftToken = it }
        RemoteFadeField(
            value = draftModel,
            label = stringResource(R.string.remote_ai_model),
            menuItems = fetchedModels,
            menuExpanded = modelMenu,
            onMenuDismiss = { modelMenu = false },
            onMenuSelect = {
                draftModel = it
                modelMenu = false
            },
            trailing = {
                Text(
                    text = stringResource(R.string.remote_ai_fetch_models),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (fetching) getSettingsDescriptionColor() else accent,
                    modifier = Modifier
                        .clickable(enabled = !fetching && draftUrl.isNotBlank()) {
                            fetching = true
                            modelMenu = false
                            scope.launch {
                                // Also the connection check: say what happened instead of failing quietly.
                                val result = runCatching { RemoteAiClient.listModels(profile) }
                                fetching = false
                                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                                fetchedModels = result.getOrDefault(emptyList())
                                modelMenu = fetchedModels.isNotEmpty()
                                val line = result.fold(
                                    onSuccess = { models ->
                                        if (models.isEmpty()) context.getString(R.string.remote_ai_fetch_empty)
                                        else context.getString(R.string.remote_ai_fetch_ok, models.size)
                                    },
                                    onFailure = { e -> context.getString(R.string.remote_ai_fetch_error, remoteAiErrorText(e)) },
                                )
                                remoteAiToast(context, line)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            },
        ) { draftModel = it }
    }
    LocalLlmDivider(vertical = 15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                accent.copy(alpha = if (isDarkModeEnabled()) 0.09f else 0.045f),
            )
            .border(1.dp, getSliderInactiveColor(), RoundedCornerShape(14.dp))
            .clickable(onClick = onPrompt)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_ai_instruction_set),
            modifier = Modifier.weight(1f),
            color = getTitleColor(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SettingsChevronIcon(tint = getSlateMutedColor(), base = 20f)
    }
    val editingLive = stored.id == liveId
    // Continuation on: the first ready slot is live and the rest are backups, so
    // only the live slot shows the (disabled) Current button; backups show none.
    val showUse = editingLive || !settings.fallbackEnabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (showUse) {
            val useTint = if (editingLive) getSlateMutedColor() else accent
            Row(
                modifier = Modifier
                    .clickable(enabled = !editingLive) { onUseSlot(profile.copy(enabled = true)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    // One live slot among many: filled radio = this slot is live.
                    imageVector = if (editingLive) {
                        Icons.Filled.RadioButtonChecked
                    } else {
                        Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = useTint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (editingLive) R.string.remote_ai_current else R.string.remote_ai_use_slot,
                    ),
                    fontSize = settingsTitleTextSize(base = 18f),
                    fontWeight = FontWeight.SemiBold,
                    color = useTint,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        val slot = stored.slotIndexOrNull() ?: 1
        if (slot > 1) {
            Row(
                modifier = Modifier
                    .clickable { onClearSlot(stored.id) { id -> if (id != null) draftId = id } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.remote_ai_clear),
                    fontSize = settingsTitleTextSize(base = 18f),
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Row(
            modifier = Modifier
                .clickable { onSave(profile.copy(enabled = true)) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.mdi_content_save),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.remote_ai_save),
                fontSize = settingsTitleTextSize(base = 18f),
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
    }
}

/** Same rule as [ready]: what this slot still needs, joined for a toast; null when complete. */
private fun remoteAiMissing(context: Context, profile: RemoteAiProfile): String? {
    if (profile.ready()) return null
    val missing = buildList {
        if (profile.baseUrl.isBlank()) add(context.getString(R.string.remote_ai_base_url))
        if (profile.model.isBlank()) add(context.getString(R.string.remote_ai_model))
        if (profile.kind != RemoteAiKind.OLLAMA && profile.token.isBlank()) add(context.getString(R.string.remote_ai_token))
    }
    return missing.joinToString(context.getString(R.string.remote_ai_list_separator))
}

/** Provider reason for a failed check; the HTTP body hint never carries the token. */
private fun remoteAiErrorText(e: Throwable): String {
    val msg = e.message.orEmpty().removePrefix("remote AI ").replace('\n', ' ').trim()
    return when {
        msg.isNotEmpty() -> msg.take(160).trimEnd('.', '。', '!', '！')
        else -> RemoteAiClient.httpStatus(e)?.let { "HTTP $it" } ?: e.javaClass.simpleName
    }
}

private fun remoteAiToast(context: Context, text: String) {
    AvaToast.show(context, text, tag = "remote-ai-settings", durationMs = AvaToast.LONG_MS)
}

@Composable
private fun RemoteFadeField(
    value: String,
    label: String,
    floatLabel: Boolean = true,
    /** Masked with a show / hide toggle, password keyboard, no autocorrect or suggestions. */
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    menuItems: List<String> = emptyList(),
    menuExpanded: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    onMenuSelect: (String) -> Unit = {},
    onValue: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val fieldClicks = remember { MutableInteractionSource() }
    val labelClicks = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    var triggerHeightPx by remember { mutableIntStateOf(0) }
    var triggerTopInWindowPx by remember { mutableIntStateOf(0) }
    var triggerBottomInWindowPx by remember { mutableIntStateOf(0) }
    val accent = getAccentColor()
    val fieldShape = RoundedCornerShape(14.dp)
    val labelColor = getTitleColor()
    val hintColor = getSettingsDescriptionColor()
    val floated = focused || value.isNotBlank()
    val floatFrac by animateFloatAsState(
        targetValue = if (floated) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "remoteFieldLabel",
    )
    val fieldHeight = 56.dp
    val restLabelSize = 13.sp
    val restY = with(LocalDensity.current) {
        ((fieldHeight.toPx() - restLabelSize.toPx()) / 2f).toDp()
    }
    val labelY = lerp(restY, (-12.5).dp, floatFrac)
    val labelSize = lerp(restLabelSize, 11.sp, floatFrac)
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .onGloballyPositioned { coords ->
                    triggerWidthPx = coords.size.width
                    triggerHeightPx = coords.size.height
                    val bounds = coords.boundsInWindow()
                    triggerTopInWindowPx = bounds.top.toInt()
                    triggerBottomInWindowPx = bounds.bottom.toInt()
                }
                .clip(fieldShape)
                .border(
                    1.dp,
                    if (focused) accent else getSliderInactiveColor(),
                    fieldShape,
                )
                .clickable(
                    interactionSource = fieldClicks,
                    indication = null,
                ) { focus.requestFocus() }
                .padding(start = if (leading != null) 4.dp else 12.dp, end = if (trailing != null || secret) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .wrapContentWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    leading()
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(1.dp)
                        .height(28.dp)
                        .background(getSliderInactiveColor()),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = if (leading != null) 8.dp else 0.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = getTitleColor(),
                    ),
                    cursorBrush = SolidColor(accent),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (secret) {
                        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                    } else {
                        KeyboardOptions.Default
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier.fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (!floatLabel && value.isEmpty() && label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    color = hintColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (secret) {
                Icon(
                    imageVector = if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (reveal) R.string.remote_ai_token_hide else R.string.remote_ai_token_show,
                    ),
                    tint = hintColor,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { reveal = !reveal }
                        .padding(10.dp)
                        .size(20.dp),
                )
            }
            if (trailing != null) trailing()
        }
        if (floatLabel) {
            Text(
                text = label,
                color = if (focused) accent else hintColor,
                style = TextStyle(
                    fontSize = labelSize,
                    lineHeight = labelSize,
                    fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
                modifier = Modifier
                    .padding(start = if (leading != null) 108.dp else 12.dp)
                    .offset(y = labelY)
                    .background(getDialogBackground().copy(alpha = floatFrac))
                    .clickable(
                        interactionSource = labelClicks,
                        indication = null,
                    ) { focus.requestFocus() }
                    .padding(
                        horizontal = (5f * floatFrac).dp,
                        vertical = (2f * floatFrac).dp,
                    ),
            )
        }
        BoxedSelectPopup(
            expanded = menuExpanded && menuItems.isNotEmpty(),
            onDismiss = onMenuDismiss,
            triggerWidthPx = triggerWidthPx,
            triggerHeightPx = triggerHeightPx,
            triggerTopInWindowPx = triggerTopInWindowPx,
            triggerBottomInWindowPx = triggerBottomInWindowPx,
        ) {
            menuItems.forEach { item ->
                val isCurrent = item == value
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMenuSelect(item) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item,
                        fontSize = 14.sp,
                        fontWeight = if (isCurrent) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (isCurrent) accent else labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteAiKindLead(
    selected: RemoteAiKind,
    items: List<RemoteAiKind>,
    name: (RemoteAiKind) -> String,
    onSelect: (RemoteAiKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    var triggerHeightPx by remember { mutableIntStateOf(0) }
    var triggerTopInWindowPx by remember { mutableIntStateOf(0) }
    var triggerBottomInWindowPx by remember { mutableIntStateOf(0) }
    val accent = getAccentColor()
    val labelColor = getTitleColor()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = items.map(name)
    val menuPadPx = with(density) { 20.dp.roundToPx() }
    val menuWidthPx = remember(labels, measurer, density, menuPadPx) {
        val style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        labels.maxOf { label ->
            measurer.measure(
                text = label,
                style = style,
                maxLines = 1,
                softWrap = false,
            ).size.width
        } + menuPadPx
    }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .wrapContentWidth()
            .onGloballyPositioned { coords ->
                triggerWidthPx = coords.size.width
                triggerHeightPx = coords.size.height
                val bounds = coords.boundsInWindow()
                triggerTopInWindowPx = bounds.top.toInt()
                triggerBottomInWindowPx = bounds.bottom.toInt()
            }
            .clickable { expanded = !expanded },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name(selected),
                color = labelColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            SettingsChevronIcon(tint = getSlateMutedColor(), base = 16f)
        }
        BoxedSelectPopup(
            expanded = expanded,
            onDismiss = { expanded = false },
            triggerWidthPx = maxOf(triggerWidthPx, menuWidthPx),
            triggerHeightPx = triggerHeightPx,
            triggerTopInWindowPx = triggerTopInWindowPx,
            triggerBottomInWindowPx = triggerBottomInWindowPx,
        ) {
            items.forEach { kind ->
                val on = kind == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = false
                            onSelect(kind)
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name(kind),
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) accent else labelColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private data class RemoteAiBarChrome(
    val slot: Dp,
    val slotGap: Dp,
    val slotInset: Dp,
    val chipH: Dp,
    val chipPad: Dp,
    val chipRowGap: Dp,
    val dot: Dp,
    val dotGap: Dp,
    val plus: Dp,
    val font: TextUnit,
    val slotFont: TextUnit,
    val groupGap: Dp,
)

private fun remoteAiBarChrome(slotScale: Float, textScale: Float, padScale: Float) = RemoteAiBarChrome(
    slot = (RemoteAiToolbarFit.SLOT * slotScale).dp,
    slotGap = (RemoteAiToolbarFit.SLOT_GAP * slotScale).dp,
    slotInset = RemoteAiToolbarFit.SLOT_INSET.dp,
    chipH = (26f * padScale).dp,
    chipPad = (RemoteAiToolbarFit.CHIP_PAD * padScale).dp,
    chipRowGap = (RemoteAiToolbarFit.CHIP_ROW_GAP * padScale).dp,
    dot = (RemoteAiToolbarFit.CHIP_DOT * padScale).dp,
    dotGap = (RemoteAiToolbarFit.CHIP_DOT_GAP * padScale).dp,
    plus = (16f * slotScale).dp,
    font = (12f * textScale).sp,
    slotFont = (12f * slotScale).sp,
    groupGap = RemoteAiToolbarFit.GROUP_GAP.dp,
)

@Composable
private fun RemoteAiTurnsChip(
    label: String,
    items: List<Int>,
    selected: Int,
    chrome: RemoteAiBarChrome,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    var triggerHeightPx by remember { mutableIntStateOf(0) }
    var triggerTopInWindowPx by remember { mutableIntStateOf(0) }
    var triggerBottomInWindowPx by remember { mutableIntStateOf(0) }
    val accent = getAccentColor()
    val labelColor = getTitleColor()
    Box {
        RemoteAiBarChip(
            text = label,
            on = selected > 0,
            showDot = false,
            chrome = chrome,
            modifier = Modifier.onGloballyPositioned { coords ->
                triggerWidthPx = coords.size.width
                triggerHeightPx = coords.size.height
                val bounds = coords.boundsInWindow()
                triggerTopInWindowPx = bounds.top.toInt()
                triggerBottomInWindowPx = bounds.bottom.toInt()
            },
            onClick = { expanded = !expanded },
        )
        BoxedSelectPopup(
            expanded = expanded,
            onDismiss = { expanded = false },
            triggerWidthPx = triggerWidthPx,
            triggerHeightPx = triggerHeightPx,
            triggerTopInWindowPx = triggerTopInWindowPx,
            triggerBottomInWindowPx = triggerBottomInWindowPx,
        ) {
            items.forEach { n ->
                val on = n == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = false
                            onSelect(n)
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = n.toString(),
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) accent else labelColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteAiSseChip(
    on: Boolean,
    chrome: RemoteAiBarChrome,
    onClick: () -> Unit,
) {
    RemoteAiBarChip(text = "SSE", on = on, showDot = true, chrome = chrome, onClick = onClick)
}

@Composable
private fun RemoteAiThinkChip(
    on: Boolean,
    chrome: RemoteAiBarChrome,
    onClick: () -> Unit,
) {
    RemoteAiBarChip(
        text = stringResource(R.string.remote_ai_thinking),
        on = on,
        showDot = true,
        chrome = chrome,
        onClick = onClick,
    )
}

@Composable
private fun RemoteAiBarChip(
    text: String,
    on: Boolean,
    chrome: RemoteAiBarChrome,
    modifier: Modifier = Modifier,
    showDot: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = getAccentColor()
    Row(
        modifier = modifier
            .height(chrome.chipH)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) accent.copy(alpha = if (isDarkModeEnabled()) 0.18f else 0.10f) else getInputBackground())
            .border(
                1.dp,
                if (on) accent.copy(alpha = 0.55f) else getSliderInactiveColor(),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = chrome.chipPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (showDot) chrome.dotGap else 0.dp),
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(chrome.dot)
                    .clip(CircleShape)
                    .background(if (on) accent else getSlateMutedColor()),
            )
        }
        Text(
            text = text,
            color = if (on) accent else getTitleColor(),
            fontSize = chrome.font,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun RemoteAiSlotStrip(
    count: Int,
    selected: Int,
    accent: Color,
    chrome: RemoteAiBarChrome,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(chrome.slotGap),
    ) {
        (1..count).forEach { index ->
            val on = index == selected
            val pop by animateFloatAsState(
                targetValue = if (on) 1.12f else 1f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "remoteSlotPop",
            )
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = pop
                        scaleY = pop
                    }
                    .size(chrome.slot)
                    .clip(CircleShape)
                    .background(if (on) accent else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    color = if (on) Color.White else getSlateMutedColor(),
                    style = TextStyle(
                        fontSize = chrome.slotFont,
                        lineHeight = chrome.slotFont,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        if (count < REMOTE_AI_SLOT_MAX) {
            Box(
                modifier = Modifier
                    .size(chrome.slot)
                    .clip(CircleShape)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.mdi_plus),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(chrome.plus),
                )
            }
        }
    }
}

@Composable
private fun CaptionCard(
    caption: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    SimpleCard {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (caption.isNotBlank() || trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (caption.isNotBlank()) {
                        SettingsHelpBodyText(
                            text = caption,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 8.dp, bottom = 10.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
private fun PathSegment(
    path: LocalLlmPath,
    accent: Color,
    overlayDismissed: Boolean,
    onOverlayDismiss: () -> Unit,
    onHaOpen: () -> Unit,
    onRemoteOpen: () -> Unit,
    onHa: () -> Unit,
    onRemote: () -> Unit,
) {
    Column {
        AnimatedVisibility(
            visible = !overlayDismissed,
            enter = fadeIn(tween(220)) + expandVertically(tween(280, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(280, easing = FastOutSlowInEasing)),
        ) {
            PathOverlayDemo(path = path, accent = accent, onDismiss = onOverlayDismiss)
        }
        PathOptionRow(
            title = stringResource(R.string.settings_ha_entry_title),
            description = stringResource(R.string.local_llm_path_ha_desc),
            selected = path == LocalLlmPath.HA,
            accent = accent,
            onOpen = onHaOpen,
            onSelect = onHa,
        )
        LocalLlmDivider(vertical = 25.dp)
        PathOptionRow(
            title = stringResource(R.string.remote_ai_caption),
            description = stringResource(R.string.local_llm_path_remote_desc),
            selected = path == LocalLlmPath.REMOTE,
            accent = accent,
            onOpen = onRemoteOpen,
            onSelect = onRemote,
        )
    }
}

@Composable
private fun PathOverlayDemo(
    path: LocalLlmPath,
    accent: Color,
    onDismiss: () -> Unit,
) {
    val prefix = stringResource(R.string.local_llm_overlay_said_prefix)
    val said = stringResource(R.string.local_llm_overlay_said)
    val cue = stringResource(R.string.local_llm_overlay_then_says)
    val who = stringResource(
        if (path == LocalLlmPath.REMOTE) {
            R.string.remote_ai_caption
        } else {
            R.string.settings_ha_entry_title
        },
    )
    val quote = stringResource(
        if (path == LocalLlmPath.REMOTE) {
            R.string.local_llm_overlay_remote_reply
        } else {
            R.string.local_llm_overlay_ha_reply
        },
    )
    val haLines = stringArrayResource(R.array.local_llm_overlay_ha_lines)
    val remoteLines = stringArrayResource(R.array.local_llm_overlay_remote_lines)
    val lessons = remember(path, haLines, remoteLines) {
        if (path == LocalLlmPath.REMOTE) remoteLines.toList() else haLines.toList()
    }
    val scroll = remember(path) { ScrollState(0) }
    val lessonHeights = remember(lessons) { mutableStateListOf(*Array(lessons.size) { 0 }) }
    val heldByUser = remember(path) { mutableStateOf(false) }
    val autoLoopJob = remember(path) { mutableStateOf<Job?>(null) }
    val spacingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val stopAutoOnUser = remember(path) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val fromUser =
                    source == NestedScrollSource.Drag || source == NestedScrollSource.Fling
                if (!heldByUser.value && fromUser && available.y != 0f) {
                    heldByUser.value = true
                    autoLoopJob.value?.cancel()
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(path, lessons) {
        autoLoopJob.value = coroutineContext[Job]
        // Full list, step top → bottom, then loop. A drag/fling cancels this job.
        if (heldByUser.value || lessons.isEmpty()) return@LaunchedEffect
        snapshotFlow { lessonHeights.toList() }.first { heights ->
            heights.size == lessons.size && heights.all { it > 0 }
        }
        if (scroll.maxValue <= 0) return@LaunchedEffect
        var index = 0
        scroll.scrollTo(0)
        while (true) {
            delay(overlayLessonDwellMs(lessons[index]))
            val next = (index + 1) % lessons.size
            val target = overlayLessonScrollY(lessonHeights, next, spacingPx)
                .coerceAtMost(scroll.maxValue)
            if (next == 0) {
                scroll.scrollTo(0)
            } else if (target > scroll.value) {
                scroll.animateScrollTo(
                    target,
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                )
            } else {
                delay(OVERLAY_LESSON_LOOP_TAIL_MS)
                scroll.scrollTo(0)
                index = 0
                continue
            }
            index = next
        }
    }
    val card = getDialogBackground()
    val well = getSettingsInsetWellColor()
    val dark = isDarkModeEnabled()
    val spoken = buildAnnotatedString {
        append(prefix)
        append(" ")
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(said)
        }
    }
    val wrap = overlayWrapStyle()
    val wellHeight = overlayWellHeight()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp)
            .height(wellHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(card.copy(alpha = if (dark) 0.94f else 0.96f))
            .border(1.dp, getSliderInactiveColor(), RoundedCornerShape(18.dp)),
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) {
                Text(
                    text = spoken,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 30.dp),
                    fontSize = settingsBodyTextSize(base = 13f),
                    lineHeight = settingsBodyLineHeight(base = 18f),
                    fontWeight = FontWeight.Medium,
                    color = getSettingsDescriptionColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = wrap,
                )
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(max = 108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(well)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "$who · $cue",
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = settingsBodyTextSize(base = 12f),
                        fontWeight = FontWeight.Medium,
                        color = getSettingsDescriptionColor(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = wrap,
                    )
                    Text(
                        text = quote,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        fontSize = settingsTitleTextSize(base = 15f),
                        lineHeight = settingsBodyLineHeight(base = 21f),
                        fontWeight = FontWeight.SemiBold,
                        color = getLabelColor(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = wrap,
                    )
                }
                SettingsEdgeFadeScrollColumn(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .fillMaxWidth()
                        .nestedScroll(stopAutoOnUser),
                    scrollState = scroll,
                    fadeHeight = 20.dp,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    handoffOverscrollToParent = false,
                ) {
                    lessons.forEachIndexed { index, lesson ->
                        Text(
                            text = lesson,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { size ->
                                    if (index in lessonHeights.indices &&
                                        lessonHeights[index] != size.height
                                    ) {
                                        lessonHeights[index] = size.height
                                    }
                                },
                            fontSize = settingsBodyTextSize(base = 13f),
                            lineHeight = settingsBodyLineHeight(base = 19f),
                            fontWeight = FontWeight.Normal,
                            color = getSettingsDescriptionColor(),
                            style = wrap,
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.settings_ha_guide_dismiss),
                tint = if (dark) Color(0xFF4B5563) else Color(0xFFCBD5E1),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(onClick = onDismiss)
                    .padding(14.dp)
                    .size(16.dp),
            )
    }
}

@Composable
private fun overlayWrapStyle(): TextStyle = TextStyle(
    lineBreak = LineBreak.Paragraph,
    hyphens = Hyphens.Auto,
)

@Composable
private fun overlayWellHeight(): Dp {
    val scale = rememberSettingsTextScale().coerceIn(1f, 1.35f)
    val locales = LocalConfiguration.current.locales
    val lang = if (locales.size() > 0) locales[0].language else Locale.getDefault().language
    val compact = lang.startsWith("zh") || lang == "ja" || lang == "ko"
    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()
    val base = if (compact) 228f else 268f
    return (base * scale).coerceAtMost(screenH * 0.38f).coerceAtLeast(200f).dp
}

private const val OVERLAY_LESSON_LOOP_TAIL_MS = 1600L

private fun overlayLessonDwellMs(text: String): Long =
    (3200L + text.length * 32L).coerceIn(4800L, 10_000L)

internal fun overlayLessonScrollY(heights: List<Int>, index: Int, spacingPx: Int): Int {
    if (index <= 0 || heights.isEmpty()) return 0
    var y = 0
    val last = minOf(index, heights.size)
    for (i in 0 until last) {
        y += heights[i] + spacingPx
    }
    return y
}

@Composable
private fun PathOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    accent: Color,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = settingsTitleTextSize(base = 17f),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = getLabelColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SettingsChevronIcon(
                    tint = getSlateMutedColor(),
                    base = 20f,
                )
            }
            if (description.isNotBlank()) {
                CollapsibleDescriptionText(
                    text = description,
                    collapsedLines = 3,
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    topPadding = 4.dp,
                    onClick = onOpen,
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(40.dp)
                .clickable(
                    role = Role.RadioButton,
                    onClick = onSelect,
                ),
            contentAlignment = Alignment.Center,
        ) {
            LocalLlmRadio(selected = selected, accent = accent)
        }
    }
}

@Composable
private fun LocalLlmRadio(selected: Boolean, accent: Color) {
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
private fun LocalLlmDivider(vertical: Dp = 0.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = vertical)
            .height(1.dp)
            .background(if (isDarkModeEnabled()) Color(0xFF2A2A2A) else Color(0xFFF1F5F9)),
    )
}
