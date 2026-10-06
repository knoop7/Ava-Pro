package com.example.ava.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.localllm.remote.AvaBrowserTools
import com.example.ava.localllm.remote.AvaMusicTools
import com.example.ava.localllm.remote.AvaPhoneTools
import com.example.ava.localllm.remote.AvaSelfTools
import com.example.ava.localllm.remote.AvaVoiceTools
import com.example.ava.localllm.remote.RemoteAiManager
import com.example.ava.localllm.remote.RemoteAiMind
import com.example.ava.localllm.remote.RemoteAiPrompt
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.settingsChevronIconSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.launch

@Composable
fun RemoteAiPromptScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RemoteAiManager.getInstance(context).settingsStore }
    val seed = remember { store.getCached() }
    val settings by store.getFlow().collectAsStateWithLifecycle(seed)
    val musicLive = remember { AvaMusicTools.ready() }
    val webLive by produceState(initialValue = false, context) {
        value = AvaBrowserTools.ready(context)
    }
    val selfLive = remember { AvaSelfTools.ready() }
    val voiceLive = remember { AvaVoiceTools.ready(context) }
    val phoneLive = remember { AvaPhoneTools.live(context) }
    val haLive = remember { RemoteAiMind.haSignedIn() }
    // Only the persona and policy block is editable. Device facts, tool routing,
    // and reply rules are appended by the host at every turn, so a saved block
    // cannot go stale when a toolset switch flips.
    val hostBody = remember { RemoteAiPrompt.operatorDefault() }
    var draft by remember { mutableStateOf(RemoteAiPrompt.editable(settings.extraPrompt)) }
    var unlocked by remember { mutableStateOf(false) }
    var promptOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(true) }
    val accent = getAccentColor()
    val muted = getSettingsDescriptionColor()

    fun persist(text: String) {
        val next = if (text.trim() == hostBody.trim() || text.isBlank()) "" else text
        scope.launch { store.extraPrompt.set(next) }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.remote_ai_instruction_set),
        onLeave = { persist(draft) },
    ) {
        item(key = "system_prompt") {
            SimpleCard {
                FoldHead(
                    title = stringResource(R.string.remote_ai_system_prompt),
                    expanded = promptOpen,
                    onToggle = { promptOpen = !promptOpen },
                    trailing = {
                        HeaderIcon(
                            icon = if (unlocked) R.drawable.mdi_lock_open else R.drawable.mdi_lock,
                            tint = if (unlocked) accent else muted,
                            label = stringResource(
                                if (unlocked) R.string.remote_ai_prompt_lock
                                else R.string.mass_api_rail_sync_delay_unlock,
                            ),
                            onClick = { unlocked = !unlocked },
                        )
                        HeaderIcon(
                            icon = R.drawable.ic_refresh_24px,
                            tint = muted,
                            label = stringResource(R.string.notif_scene_restore),
                            onClick = {
                                draft = hostBody
                                persist("")
                                unlocked = false
                            },
                        )
                    },
                )
                AnimatedVisibility(visible = promptOpen) {
                    SettingsInsetWell {
                        PromptCodeField(
                            value = draft,
                            onValueChange = { if (unlocked) draft = it },
                            readOnly = !unlocked,
                            unlocked = unlocked,
                            accent = accent,
                        )
                    }
                }
            }
        }
        item(key = "toolset") {
            SimpleCard {
                FoldHead(
                    title = stringResource(R.string.remote_ai_toolset),
                    expanded = toolsOpen,
                    onToggle = { toolsOpen = !toolsOpen },
                )
                AnimatedVisibility(visible = toolsOpen) {
                    SettingsInsetWell {
                        ToolFamilyRow(
                            label = stringResource(R.string.remote_ai_tool_phone),
                            desc = stringResource(R.string.remote_ai_tool_phone_desc),
                            live = phoneLive,
                            checked = settings.toolsPhone,
                            onChecked = { scope.launch { store.toolsPhone.set(it) } },
                        )
                        SettingsWellDivider()
                        ToolFamilyRow(
                            label = stringResource(R.string.remote_ai_tool_ha),
                            desc = stringResource(R.string.remote_ai_tool_ha_desc),
                            live = haLive,
                            checked = settings.toolsHa,
                            onChecked = { scope.launch { store.toolsHa.set(it) } },
                        )
                        SettingsWellDivider()
                        ToolFamilyRow(
                            label = stringResource(R.string.remote_ai_tool_music),
                            desc = stringResource(R.string.remote_ai_tool_music_desc),
                            live = musicLive.any,
                            checked = settings.toolsMusic,
                            onChecked = { scope.launch { store.toolsMusic.set(it) } },
                        )
                        SettingsWellDivider()
                        ToolFamilyRow(
                            label = stringResource(R.string.remote_ai_tool_web),
                            desc = stringResource(R.string.remote_ai_tool_web_desc),
                            live = webLive,
                            checked = settings.toolsWeb,
                            onChecked = { scope.launch { store.toolsWeb.set(it) } },
                        )
                        SettingsWellDivider()
                        ToolFamilyRow(
                            label = stringResource(R.string.remote_ai_tool_self),
                            desc = stringResource(R.string.remote_ai_tool_self_desc),
                            live = selfLive,
                            checked = settings.toolsSelf,
                            onChecked = { scope.launch { store.toolsSelf.set(it) } },
                        )
                        SettingsWellDivider()
                        ToolFamilyRow(
                            label = stringResource(R.string.remote_ai_tool_voice),
                            desc = stringResource(R.string.remote_ai_tool_voice_desc),
                            live = voiceLive,
                            checked = settings.toolsVoice,
                            onChecked = { scope.launch { store.toolsVoice.set(it) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    unlocked: Boolean,
    accent: androidx.compose.ui.graphics.Color,
) {
    var visualLines by remember { mutableIntStateOf(1) }
    val label = getLabelColor().copy(alpha = if (unlocked) 1f else 0.72f)
    val gutter = getSettingsDescriptionColor()
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = settingsTitleTextSize(base = 13f),
        lineHeight = settingsTitleTextSize(base = 20f),
        color = label,
    )
    val digits = visualLines.toString().length.coerceAtLeast(2)
    SettingsEdgeFadeScrollColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .padding(vertical = 12.dp),
        fadeHeight = 18.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(end = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                for (i in 1..visualLines) {
                    androidx.compose.material3.Text(
                        text = i.toString().padStart(digits, ' '),
                        style = style.copy(color = gutter),
                        textAlign = TextAlign.End,
                    )
                }
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                textStyle = style,
                cursorBrush = SolidColor(accent),
                onTextLayout = { visualLines = it.lineCount.coerceAtLeast(1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FoldHead(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 15.dp),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = getTitleColor(),
        )
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = getSlateMutedColor(),
            modifier = Modifier
                .size(settingsChevronIconSize(base = 22f))
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}

@Composable
private fun HeaderIcon(
    @DrawableRes icon: Int,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = label,
        tint = tint,
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

@Composable
private fun ToolFamilyRow(
    label: String,
    desc: String,
    live: Boolean,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    SettingRow(
        label = label,
        subLabel = if (live) desc else "$desc，${stringResource(R.string.remote_ai_tool_idle)}",
    ) {
        ModernSwitch(checked = checked, onCheckedChange = onChecked)
    }
}
