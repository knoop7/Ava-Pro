package com.example.ava.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.QuickEntityOverlayRoot
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.SettingsBottomFadeOverlay
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getInputBackground
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.ui.theme.SlateTertiary
import com.example.ava.voice.AvaVoiceCallController
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDeviceType
import com.example.ava.voice.AvaVoiceHaController
import com.example.ava.voice.AvaVoiceInboundBus
import com.example.ava.voice.AvaVoiceIncomingPhase
import com.example.ava.voice.AvaVoiceMessenger
import com.example.ava.voice.AvaVoiceMicrophoneGuard
import com.example.ava.voice.AvaVoiceMode
import com.example.ava.voice.AvaVoiceNetwork
import com.example.ava.voice.AvaVoiceOverlayPrefs
import com.example.ava.voice.AvaVoiceSessionHub
import com.example.ava.voice.MicAcquireResult
import com.example.ava.voice.resolveVoiceOverlayMode
import com.example.ava.voice.showVoiceOverlayModeSwitch
import com.example.ava.voice.withPinnedDeviceFirst
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private enum class VoiceOverlayScreen {
    DeviceList,
    PushToTalk
}

private enum class PushToTalkState {
    Ready,
    Recording,
    Sending,
    Sent,
    NoAnswer,
    Declined,
    PeerHangup
}

private val VoiceDeviceListBottomFadeHeight = 60.dp
private val VoiceDeviceListBottomButtonHeight = 72.dp
private val VoiceDeviceListBottomButtonDownOffset = 20.dp
private val VoiceDeviceListBottomInset = 4.dp
private val VoiceDeviceListBottomReservedHeight =
    VoiceDeviceListBottomFadeHeight + VoiceDeviceListBottomButtonDownOffset +
        VoiceDeviceListBottomButtonHeight + VoiceDeviceListBottomInset
private val VoiceDeviceListToolbarControlHeight = 52.dp
private val VoiceModeSwitchWidth = 208.dp
private val VoiceModeSwitchHeight = 58.dp
private val VoiceModeSwitchInset = 5.dp
private val VoiceModeSwitchCorner = RoundedCornerShape(50)

@Composable
private fun VoiceOverlayToolbarIconButton(
    onClick: () -> Unit,
    surfaceContainer: Color,
    iconTint: Color,
    contentDescription: String,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = surfaceContainer,
        modifier = Modifier.size(VoiceDeviceListToolbarControlHeight)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun VoiceOverlayBalancedToolbar(
    onBack: () -> Unit,
    surfaceContainer: Color,
    iconTint: Color,
    backContentDescription: String,
    trailing: @Composable () -> Unit = {
        Spacer(modifier = Modifier.size(VoiceDeviceListToolbarControlHeight))
    },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VoiceOverlayToolbarIconButton(
            onClick = onBack,
            surfaceContainer = surfaceContainer,
            iconTint = iconTint,
            contentDescription = backContentDescription,
        )
        Spacer(modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun VoiceMessageOverlayContent(
    devices: List<AvaVoiceDevice>,
    localDeviceId: String,
    localDeviceName: String,
    voiceMessageDelayMinutes: Int,
    enableVoiceOverlayIntercom: Boolean = true,
    enableVoiceOverlayCall: Boolean = true,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val homePrefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(homePrefs, KEY_DARK_MODE, false)
    val surfaceColor = if (isDarkMode) Color(0xFF121316) else Color(0xFFFAF9FD)
    val surfaceContainer = if (isDarkMode) Color(0xFF2A2D33) else getSliderInactiveColor()
    val accent = getAccentColor()
    val onAccent = Color.White
    val titleColor = getTitleColor()
    val bodyColor = SlateTertiary
    val errorColor = if (isDarkMode) Color(0xFFFF6B6B) else Color(0xFFBA1A1A)

    val scope = rememberCoroutineScope()
    val haSession by AvaVoiceHaController.uiSession.collectAsState()
    val isHaSession = haSession != null
    var screen by remember { mutableStateOf(VoiceOverlayScreen.DeviceList) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var activeDevices by remember { mutableStateOf<List<AvaVoiceDevice>>(emptyList()) }
    var voiceMode by remember { mutableStateOf(AvaVoiceOverlayPrefs.loadMode(appContext)) }
    var pinnedDeviceId by remember { mutableStateOf(AvaVoiceOverlayPrefs.loadPinnedDeviceId(appContext)) }

    LaunchedEffect(haSession) {
        val session = haSession
        if (session == null) {
            if (screen == VoiceOverlayScreen.PushToTalk) {
                screen = VoiceOverlayScreen.DeviceList
                activeDevices = emptyList()
                selectedIds = emptySet()
            }
            return@LaunchedEffect
        }
        voiceMode = when (session.mode) {
            "call", "video_call", "video" -> AvaVoiceMode.Call
            else -> AvaVoiceMode.Intercom
        }
        activeDevices = session.targets
        selectedIds = session.targets.map { it.id }.toSet()
        screen = VoiceOverlayScreen.PushToTalk
    }

    fun dismissOverlay() {
        if (isHaSession) {
            scope.launch { AvaVoiceHaController.hangUp(appContext) }
        } else {
            onDismiss()
        }
    }

    fun leavePushToTalk() {
        if (isHaSession) {
            scope.launch { AvaVoiceHaController.hangUp(appContext) }
        } else {
            screen = VoiceOverlayScreen.DeviceList
        }
    }
    val orderedDevices = remember(devices, pinnedDeviceId) {
        devices.withPinnedDeviceFirst(pinnedDeviceId)
    }
    val showModeSwitch = showVoiceOverlayModeSwitch(enableVoiceOverlayIntercom, enableVoiceOverlayCall)
    LaunchedEffect(enableVoiceOverlayIntercom, enableVoiceOverlayCall) {
        val resolved = resolveVoiceOverlayMode(
            voiceMode,
            enableVoiceOverlayIntercom,
            enableVoiceOverlayCall
        )
        if (resolved != voiceMode) {
            voiceMode = resolved
            AvaVoiceOverlayPrefs.saveMode(appContext, resolved)
        }
    }
    QuickEntityOverlayRoot(
        backgroundColor = surfaceColor,
        modifier = modifier
    ) { contentPadding ->
        AnimatedContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            targetState = screen,
            transitionSpec = {
                if (targetState == VoiceOverlayScreen.PushToTalk) {
                    (slideInHorizontally { it / 4 } + fadeIn()).togetherWith(
                        slideOutHorizontally { -it / 4 } + fadeOut()
                    )
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()).togetherWith(
                        slideOutHorizontally { it / 4 } + fadeOut()
                    )
                }
            },
            label = "voice_overlay_screen"
        ) { currentScreen ->
            when (currentScreen) {
                VoiceOverlayScreen.DeviceList -> VoiceDeviceListScreen(
                    devices = orderedDevices,
                    selectedIds = selectedIds,
                    isDarkMode = isDarkMode,
                    surfaceColor = surfaceColor,
                    surfaceContainer = surfaceContainer,
                    accent = accent,
                    onAccent = onAccent,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                    voiceMode = voiceMode,
                    showModeSwitch = showModeSwitch && !isHaSession,
                    showToolbarClose = isHaSession,
                    onDismiss = { dismissOverlay() },
                    onVoiceModeChange = { mode ->
                        voiceMode = mode
                        AvaVoiceOverlayPrefs.saveMode(appContext, mode)
                    },
                    onToggleDevice = { id ->
                        selectedIds = if (selectedIds.contains(id)) {
                            selectedIds - id
                        } else {
                            selectedIds + id
                        }
                    },
                    onContinue = {
                        val targets = orderedDevices.filter { selectedIds.contains(it.id) }
                        if (targets.isNotEmpty()) {
                            if (selectedIds.size == 1) {
                                val pinnedId = selectedIds.first()
                                AvaVoiceOverlayPrefs.savePinnedDeviceId(appContext, pinnedId)
                                pinnedDeviceId = pinnedId
                            }
                            activeDevices = targets
                            screen = VoiceOverlayScreen.PushToTalk
                        }
                    }
                )

                VoiceOverlayScreen.PushToTalk -> VoicePushToTalkScreen(
                    selectedDevices = activeDevices,
                    localDeviceId = localDeviceId,
                    localDeviceName = localDeviceName,
                    voiceMessageDelayMinutes = haSession?.delayMinutes ?: voiceMessageDelayMinutes,
                    accent = accent,
                    onAccent = onAccent,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                    errorColor = errorColor,
                    surfaceContainer = surfaceContainer,
                    voiceMode = voiceMode,
                    showModeSwitch = showModeSwitch && !isHaSession,
                    showToolbarClose = isHaSession,
                    contentPadding = contentPadding,
                    haAutoMessageSeconds = haSession
                        ?.takeIf { session -> session.mode == "message" || session.mode == "intercom" }
                        ?.messageSeconds,
                    autoEnableVideoOnConnect = haSession?.withVideo == true,
                    onBack = { leavePushToTalk() },
                    onDismiss = { dismissOverlay() }
                )
            }
        }
    }
}

@Composable
private fun VoiceDeviceListScreen(
    devices: List<AvaVoiceDevice>,
    selectedIds: Set<String>,
    isDarkMode: Boolean,
    surfaceColor: Color,
    surfaceContainer: Color,
    accent: Color,
    onAccent: Color,
    titleColor: Color,
    bodyColor: Color,
    voiceMode: AvaVoiceMode,
    showModeSwitch: Boolean,
    showToolbarClose: Boolean = false,
    onDismiss: () -> Unit,
    onVoiceModeChange: (AvaVoiceMode) -> Unit,
    onToggleDevice: (String) -> Unit,
    onContinue: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showModeSwitch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VoiceOverlayToolbarIconButton(
                        onClick = onDismiss,
                        surfaceContainer = surfaceContainer,
                        iconTint = titleColor,
                        contentDescription = stringResource(R.string.voice_message_close),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    VoiceModeSwitch(
                        selectedMode = voiceMode,
                        surfaceContainer = surfaceContainer,
                        accent = accent,
                        onAccent = onAccent,
                        titleColor = titleColor,
                        onModeChange = onVoiceModeChange
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else if (showToolbarClose) {
                VoiceOverlayBalancedToolbar(
                    onBack = onDismiss,
                    surfaceContainer = surfaceContainer,
                    iconTint = titleColor,
                    backContentDescription = stringResource(R.string.voice_message_close),
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.voice_message_no_devices),
                        color = bodyColor,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (selectedIds.isNotEmpty()) {
                            VoiceDeviceListBottomReservedHeight
                        } else {
                            16.dp
                        }
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(devices, key = { it.stableKey }) { device ->
                        val selected = selectedIds.contains(device.id)
                        VoiceDeviceCard(
                            device = device,
                            selected = selected,
                            surfaceContainer = surfaceContainer,
                            accent = accent,
                            onAccent = onAccent,
                            titleColor = titleColor,
                            onClick = { onToggleDevice(device.id) }
                        )
                    }
                }
            }

            VoiceDeviceListBottomCta(
                visible = selectedIds.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
                isDarkMode = isDarkMode,
                surfaceColor = surfaceColor,
                accent = accent,
                onAccent = onAccent,
                voiceMode = voiceMode,
                selectedCount = selectedIds.size,
                onContinue = onContinue
            )
            }
        }
    }
}

@Composable
private fun VoiceDeviceListBottomCta(
    visible: Boolean,
    isDarkMode: Boolean,
    surfaceColor: Color,
    accent: Color,
    onAccent: Color,
    voiceMode: AvaVoiceMode,
    selectedCount: Int,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        SettingsBottomFadeOverlay(
            isDarkMode = isDarkMode,
            fadeToColor = surfaceColor,
            fadeHeight = VoiceDeviceListBottomFadeHeight,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(VoiceDeviceListBottomButtonDownOffset))
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(VoiceDeviceListBottomButtonHeight)
                        .voiceAccentButtonShadow(
                            color = accent,
                            shape = RoundedCornerShape(36.dp)
                        ),
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = onAccent
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (voiceMode == AvaVoiceMode.Call) {
                                R.string.voice_call_start_to_count
                            } else {
                                R.string.voice_message_send_to_count
                            },
                            selectedCount
                        ),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(VoiceDeviceListBottomInset))
            }
        }
    }
}

@Composable
private fun VoiceModeSwitch(
    selectedMode: AvaVoiceMode,
    surfaceContainer: Color,
    accent: Color,
    onAccent: Color,
    titleColor: Color,
    onModeChange: (AvaVoiceMode) -> Unit
) {
    val density = LocalDensity.current
    val segmentPx = with(density) {
        ((VoiceModeSwitchWidth - VoiceModeSwitchInset * 2) / 2).toPx()
    }
    val settledProgress = if (selectedMode == AvaVoiceMode.Call) 1f else 0f
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(settledProgress) }
    val progress by animateFloatAsState(
        targetValue = if (dragging) dragProgress else settledProgress,
        animationSpec = if (dragging) {
            snap()
        } else {
            tween(durationMillis = 130, easing = FastOutSlowInEasing)
        },
        label = "voice_mode_thumb",
    )
    val progressRef = rememberUpdatedState(progress)
    val onModeChangeRef = rememberUpdatedState(onModeChange)
    BoxWithConstraints(
        modifier = Modifier
            .width(VoiceModeSwitchWidth)
            .height(VoiceModeSwitchHeight)
            .clip(VoiceModeSwitchCorner)
            .background(surfaceContainer)
            .pointerInput(segmentPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragProgress = progressRef.value
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragProgress = (dragProgress + dragAmount / segmentPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val next = if (dragProgress >= 0.5f) {
                            AvaVoiceMode.Call
                        } else {
                            AvaVoiceMode.Intercom
                        }
                        dragging = false
                        onModeChangeRef.value(next)
                    },
                    onDragCancel = { dragging = false }
                )
            }
            .padding(VoiceModeSwitchInset)
    ) {
        val segmentWidth = maxWidth / 2
        Box(
            modifier = Modifier
                .offset(x = segmentWidth * progress)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(VoiceModeSwitchCorner)
                .background(accent)
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
        ) {
            VoiceModeSwitchLabel(
                text = stringResource(R.string.voice_message_mode_intercom),
                color = lerp(onAccent, titleColor, progress),
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(AvaVoiceMode.Intercom) }
            )
            VoiceModeSwitchLabel(
                text = stringResource(R.string.voice_message_mode_call),
                color = lerp(titleColor, onAccent, progress),
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(AvaVoiceMode.Call) }
            )
        }
    }
}

@Composable
private fun VoiceModeSwitchLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(VoiceModeSwitchCorner)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun VoiceDeviceCard(
    device: AvaVoiceDevice,
    selected: Boolean,
    surfaceContainer: Color,
    accent: Color,
    onAccent: Color,
    titleColor: Color,
    onClick: () -> Unit
) {
    val cardColor = if (selected) accent else surfaceContainer
    val contentColor = if (selected) onAccent else titleColor
    val iconBg = if (selected) onAccent else getInputBackground()
    val iconTint = if (selected) accent else accent

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp),
        shape = RoundedCornerShape(28.dp),
        color = cardColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = deviceTypeIcon(device.type),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp)
                )
            }
            // User-facing name only. Type and model stay off this card.
            VoiceDeviceCardName(
                name = device.name,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = onAccent,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/**
 * Device-card name — keep the label one continuous sentence:
 *
 * - Fits in ≤2 lines → static wrap, no mask (same as NP: fitting text stays clear).
 * - Longer than 2 lines → still a **2-line block** with one shared dissolve
 *   over the **full block height** + ellipsis (mask grows with the block).
 *   No single-line marquee here: loop gaps read as a mid-name break on CJK.
 */
@Composable
private fun VoiceDeviceCardName(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (name.isBlank()) return
    val fontSize = 22.sp
    val fontWeight = FontWeight.SemiBold
    val style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = color)
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val fitsInTwoLines = remember(name, maxWidthPx, style) {
            if (maxWidthPx <= 0) {
                true
            } else {
                val layout = measurer.measure(
                    text = name,
                    style = style,
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = maxWidthPx),
                )
                !layout.hasVisualOverflow
            }
        }

        if (fitsInTwoLines) {
            Text(
                text = name,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                textAlign = TextAlign.Start,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // One mask around the whole 2-line block — shadow expands with height.
            // Right-weighted dissolve (start-aligned): left stay readable; soft
            // clip only the ellipsis edge — same idea as NP end-pad, not a
            // permanent left vignette on a static label.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White,
                                    0.72f to Color.White,
                                    0.88f to Color.White.copy(alpha = 0.38f),
                                    1.0f to Color.Transparent,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                Text(
                    text = name,
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun VoicePushToTalkScreen(
    selectedDevices: List<AvaVoiceDevice>,
    localDeviceId: String,
    localDeviceName: String,
    voiceMessageDelayMinutes: Int,
    accent: Color,
    onAccent: Color,
    titleColor: Color,
    bodyColor: Color,
    errorColor: Color,
    surfaceContainer: Color,
    voiceMode: AvaVoiceMode,
    showModeSwitch: Boolean,
    showToolbarClose: Boolean = false,
    contentPadding: PaddingValues,
    haAutoMessageSeconds: Int? = null,
    autoEnableVideoOnConnect: Boolean = false,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pttState by remember { mutableStateOf(PushToTalkState.Ready) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var activeSessionId by remember { mutableIntStateOf(0) }
    var startSessionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var micBlocked by remember { mutableStateOf(false) }
    var isHolding by remember { mutableStateOf(false) }
    var callHungUp by remember { mutableStateOf(false) }
    var connectedPeerIds by remember { mutableStateOf(setOf<String>()) }
    var disconnectedPeerIds by remember { mutableStateOf(setOf<String>()) }
    val pttStateRef = rememberUpdatedState(pttState)
    val callHungUpRef = rememberUpdatedState(callHungUp)
    val activeSessionRef = rememberUpdatedState(activeSessionId)
    val startSessionJobRef = rememberUpdatedState(startSessionJob)
    val selectedDeviceKey = remember(selectedDevices) {
        selectedDevices.joinToString("|") { device -> "${device.id}@${device.host}" }
    }
    val callTargets = remember(selectedDeviceKey) {
        selectedDevices.map { device -> device.copy(lastSeenMs = 0L) }
    }
    val callController = remember(selectedDeviceKey, localDeviceId, localDeviceName) {
        AvaVoiceCallController(
            scope = scope,
            localDeviceId = localDeviceId,
            localDeviceName = localDeviceName,
            targets = callTargets,
            onMicBlocked = {
                micBlocked = true
                pttState = PushToTalkState.Ready
            },
            onMicReady = {
                micBlocked = false
                pttState = PushToTalkState.Recording
            }
        )
    }

    LaunchedEffect(voiceMode, selectedDeviceKey) {
        if (voiceMode != AvaVoiceMode.Call) return@LaunchedEffect
        combine(
            AvaVoiceSessionHub.outboundCallConnectedPeerIds,
            AvaVoiceSessionHub.outboundCallDisconnectedPeerIds
        ) { connected, disconnected -> connected to disconnected }
            .collect { (connected, disconnected) ->
                connectedPeerIds = connected
                disconnectedPeerIds = disconnected
            }
    }

    LaunchedEffect(voiceMode, selectedDeviceKey, disconnectedPeerIds) {
        if (voiceMode != AvaVoiceMode.Call || callHungUpRef.value) return@LaunchedEffect
        val targetIds = callTargets.map { device -> device.id }.toSet()
        if (targetIds.isEmpty()) return@LaunchedEffect
        if (!disconnectedPeerIds.containsAll(targetIds)) return@LaunchedEffect
        callHungUp = true
        callController.stopMicrophone()
        pttState = PushToTalkState.PeerHangup
        delay(1_800)
        onBack()
    }

    DisposableEffect(voiceMode, selectedDeviceKey, localDeviceId) {
        if (voiceMode != AvaVoiceMode.Call) {
            return@DisposableEffect onDispose { }
        }
        val targetIds = callTargets.map { device -> device.id }.toSet()
        val job = scope.launch {
            AvaVoiceInboundBus.messages.collect { message ->
                if (message.toDeviceId != localDeviceId || message.fromDeviceId !in targetIds) return@collect
                when (message.phase) {
                    AvaVoiceIncomingPhase.Hangup,
                    AvaVoiceIncomingPhase.Declined,
                    AvaVoiceIncomingPhase.NoAnswer -> {
                        if (callHungUpRef.value) return@collect
                        val left = AvaVoiceSessionHub.outboundCallDisconnectedPeerIds.value
                        if (!left.containsAll(targetIds)) return@collect
                        callHungUp = true
                        callController.stopMicrophone()
                        pttState = when (message.phase) {
                            AvaVoiceIncomingPhase.Hangup -> PushToTalkState.PeerHangup
                            AvaVoiceIncomingPhase.NoAnswer -> PushToTalkState.NoAnswer
                            AvaVoiceIncomingPhase.Declined -> PushToTalkState.Declined
                            else -> PushToTalkState.PeerHangup
                        }
                        delay(1_800)
                        onBack()
                    }
                    else -> Unit
                }
            }
        }
        onDispose { job.cancel() }
    }

    DisposableEffect(voiceMode, selectedDeviceKey) {
        if (voiceMode == AvaVoiceMode.Call) {
            AvaVoiceSessionHub.resetOutboundCallPeerState()
            callHungUp = false
            connectedPeerIds = emptySet()
            disconnectedPeerIds = emptySet()
            pttState = PushToTalkState.Recording
            AvaVoiceNetwork.enterCallDuplex()
            callController.startMicrophone()
        }
        onDispose {
            if (voiceMode == AvaVoiceMode.Call) {
                AvaVoiceNetwork.leaveCallDuplex()
            }
            scope.launch {
                if (voiceMode == AvaVoiceMode.Call) {
                    if (!callHungUp) {
                        callController.hangup()
                    }
                } else {
                    startSessionJobRef.value?.join()
                    AvaVoiceMicrophoneGuard.finishVoiceMessageMic(activeSessionRef.value)
                }
            }
        }
    }

    fun startHold() {
        if (voiceMode == AvaVoiceMode.Call) return
        if (isHolding) return
        if (pttStateRef.value != PushToTalkState.Ready && pttStateRef.value != PushToTalkState.Sent) return
        when (AvaVoiceMicrophoneGuard.tryAcquire()) {
            MicAcquireResult.Granted -> {
                micBlocked = false
                isHolding = true
                pttState = PushToTalkState.Recording
                recordingStartedAt = System.currentTimeMillis()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                startSessionJob = scope.launch {
                    val sessionId = AvaVoiceMessenger.startVoiceStream(
                        fromDeviceId = localDeviceId,
                        fromName = localDeviceName,
                        targets = selectedDevices,
                        mode = voiceMode,
                        delayMinutes = if (voiceMode == AvaVoiceMode.Call) 0 else voiceMessageDelayMinutes
                    )
                    if (sessionId == null) {
                        isHolding = false
                        pttState = PushToTalkState.Ready
                        micBlocked = true
                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    } else {
                        activeSessionId = sessionId
                    }
                }
            }
            MicAcquireResult.AssistantActive,
            MicAcquireResult.Busy,
            MicAcquireResult.ServiceUnavailable -> {
                micBlocked = true
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
            }
        }
    }

    fun endHold() {
        if (voiceMode == AvaVoiceMode.Call) return
        if (!isHolding && pttStateRef.value != PushToTalkState.Recording) return
        if (pttStateRef.value != PushToTalkState.Recording) {
            isHolding = false
            return
        }
        isHolding = false
        pttState = PushToTalkState.Sending
        scope.launch {
            startSessionJob?.join()
            startSessionJob = null
            delay(350)
            val sessionId = activeSessionId
            activeSessionId = 0
            AvaVoiceMicrophoneGuard.finishVoiceMessageMic(sessionId)
            delay(400)
            pttState = PushToTalkState.Sent
            micBlocked = false
        }
    }

    var haAutoStarted by remember(haAutoMessageSeconds, selectedDeviceKey) { mutableStateOf(false) }
    LaunchedEffect(haAutoMessageSeconds, voiceMode, selectedDeviceKey) {
        val seconds = haAutoMessageSeconds ?: return@LaunchedEffect
        if (voiceMode != AvaVoiceMode.Intercom || haAutoStarted) return@LaunchedEffect
        if (!AvaVoiceHaController.tryConsumeMessageAutoRecord()) return@LaunchedEffect
        haAutoStarted = true
        startHold()
        delay(seconds * 1_000L)
        if (!AvaVoiceHaController.hasActiveSession()) return@LaunchedEffect
        endHold()
    }

    LaunchedEffect(haAutoMessageSeconds, pttState) {
        if (haAutoMessageSeconds == null || pttState != PushToTalkState.Sent) return@LaunchedEffect
        delay(1_500)
        if (AvaVoiceHaController.hasActiveSession()) {
            onDismiss()
        }
    }

    fun handleMotionEvent(event: MotionEvent, onDown: () -> Unit, onUp: () -> Unit): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDown()
                true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                onUp()
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 1) onUp()
                true
            }
            else -> false
        }
    }

    val isCallMode = voiceMode == AvaVoiceMode.Call
    val isHaAutoMessage = haAutoMessageSeconds != null
    val ui = rememberVoiceCallUiScale()
    val messageUi = rememberVoiceMessageUiScale()
    val mainButtonSize = messageUi.dp(104f)
    val messageTouchSize = messageUi.dp(142f)
    val messageIconSize = messageUi.dp(48f)
    val activeCallTargets = remember(callTargets, disconnectedPeerIds) {
        callTargets.filter { device -> device.id !in disconnectedPeerIds }
    }
    val connectedNames = remember(activeCallTargets, connectedPeerIds) {
        activeCallTargets
            .filter { device -> device.id in connectedPeerIds }
            .joinToString(", ") { it.name }
    }
    val ringingNames = remember(activeCallTargets, connectedPeerIds) {
        activeCallTargets
            .filter { device -> device.id !in connectedPeerIds }
            .joinToString(", ") { it.name }
    }
    val hasConnectedPeers = connectedNames.isNotBlank()
    val hasRingingPeers = ringingNames.isNotBlank()

    val statusTitle = when {
        micBlocked -> stringResource(R.string.voice_message_mic_busy_title)
        else -> when (pttState) {
            PushToTalkState.Ready -> if (voiceMode == AvaVoiceMode.Call) {
                stringResource(R.string.voice_call_status_ready)
            } else {
                stringResource(R.string.voice_message_status_ready)
            }
            PushToTalkState.Recording -> if (voiceMode == AvaVoiceMode.Call) {
                when {
                    hasConnectedPeers && hasRingingPeers ->
                        stringResource(
                            R.string.voice_call_outbound_mixed_status,
                            connectedNames,
                            ringingNames
                        )
                    hasConnectedPeers ->
                        stringResource(R.string.voice_call_outbound_connected_to, connectedNames)
                    hasRingingPeers ->
                        stringResource(R.string.voice_call_outbound_ringing_to, ringingNames)
                    else -> stringResource(R.string.voice_call_status_recording)
                }
            } else {
                stringResource(R.string.voice_message_status_recording)
            }
            PushToTalkState.Sending -> if (voiceMode == AvaVoiceMode.Call) {
                stringResource(R.string.voice_call_status_ending)
            } else {
                stringResource(R.string.voice_message_status_sending)
            }
            PushToTalkState.Sent -> if (voiceMode == AvaVoiceMode.Call) {
                stringResource(R.string.voice_call_status_ended)
            } else {
                stringResource(R.string.voice_message_status_sent)
            }
            PushToTalkState.NoAnswer -> stringResource(R.string.voice_call_status_no_answer)
            PushToTalkState.Declined -> stringResource(R.string.voice_call_status_declined)
            PushToTalkState.PeerHangup -> stringResource(R.string.voice_call_status_peer_hung_up)
        }
    }
    val statusDesc = when {
        micBlocked -> stringResource(R.string.voice_message_mic_busy_desc)
        else -> when (pttState) {
            PushToTalkState.Ready -> if (voiceMode == AvaVoiceMode.Call) {
                stringResource(R.string.voice_call_status_ready_desc)
            } else {
                stringResource(R.string.voice_message_status_ready_desc)
            }
            PushToTalkState.Recording -> if (voiceMode == AvaVoiceMode.Call) {
                when {
                    hasConnectedPeers -> stringResource(R.string.voice_call_outbound_connected_desc)
                    hasRingingPeers -> stringResource(R.string.voice_call_outbound_ringing_desc)
                    else -> stringResource(R.string.voice_call_status_recording_desc)
                }
            } else {
                stringResource(R.string.voice_message_status_recording_desc)
            }
            PushToTalkState.Sending -> if (voiceMode == AvaVoiceMode.Call) {
                stringResource(R.string.voice_call_status_ending_desc)
            } else {
                stringResource(R.string.voice_message_status_sending_desc)
            }
            PushToTalkState.Sent -> if (voiceMode == AvaVoiceMode.Call) {
                stringResource(R.string.voice_call_status_ended_desc)
            } else {
                stringResource(R.string.voice_message_status_sent_desc)
            }
            PushToTalkState.NoAnswer -> stringResource(R.string.voice_call_status_no_answer_desc)
            PushToTalkState.Declined -> stringResource(R.string.voice_call_status_declined_desc)
            PushToTalkState.PeerHangup -> stringResource(R.string.voice_call_status_peer_hung_up_desc)
        }
    }
    val callEnded = pttState == PushToTalkState.NoAnswer ||
        pttState == PushToTalkState.Declined ||
        pttState == PushToTalkState.PeerHangup
    val micColor = if (pttState == PushToTalkState.Recording) errorColor else accent

    val showBackButton = !isCallMode || callEnded
    val showInlineBack = showBackButton && (showModeSwitch || showToolbarClose)

    val isCallConnected = isCallMode && hasConnectedPeers && !callEnded
    val videoPeers = remember(activeCallTargets, connectedPeerIds) {
        activeCallTargets.filter { device -> device.id in connectedPeerIds && device.host.isNotBlank() }
    }
    val callSessionId = remember(isCallConnected, localDeviceId, videoPeers) {
        if (!isCallConnected) null
        else AvaVoiceSessionHub.findCallSessionId(localDeviceId, videoPeers.map { it.id }.toSet())
    }
    val videoState = rememberVoiceCallVideoState(
        isCallConnected = isCallConnected,
        sessionId = callSessionId,
        localDeviceId = localDeviceId,
        peers = videoPeers
    )
    var autoVideoStarted by remember(autoEnableVideoOnConnect, selectedDeviceKey) { mutableStateOf(false) }
    LaunchedEffect(
        autoEnableVideoOnConnect,
        isCallConnected,
        callSessionId,
        videoPeers,
        videoState.localVideoActive,
    ) {
        if (!autoEnableVideoOnConnect || autoVideoStarted) return@LaunchedEffect
        if (!isCallConnected || callSessionId == null || videoPeers.isEmpty()) return@LaunchedEffect
        if (videoState.localVideoActive) {
            autoVideoStarted = true
            return@LaunchedEffect
        }
        videoState.toggleVideo()
        autoVideoStarted = true
    }
    val callMicMuted by AvaVoiceSessionHub.callMicMuted.collectAsState()
    val showVideoBg = isCallConnected && videoState.showRemotePreview
    val overlayChrome = rememberVoiceCallOverlayChrome(showVideoBg)
    val overlayTitleColor = if (showVideoBg) Color.White else titleColor
    val overlayBodyColor = if (showVideoBg) Color.White.copy(alpha = 0.78f) else bodyColor
    val controlBarColor = if (showVideoBg) Color(0xE62A2D33) else surfaceContainer
    val controlTextColor = if (showVideoBg) Color.White else titleColor

    Box(
        modifier = Modifier
            .fillMaxSize()
            .voiceCallOverlayTouchReveal(showVideoBg, overlayChrome)
    ) {
        if (showVideoBg) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .overlayFullBleed(contentPadding)
                    .background(Color.Black)
            )
        }
        VoiceCallRemoteVideoLayer(
            visible = showVideoBg,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            onOverlayReveal = overlayChrome::onTouchReveal
        )
        VoiceCallLocalPipLayer(
            visible = isCallConnected && videoState.localVideoActive,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            scale = ui,
        )

        VoiceCallOverlayChromeHost(
            modifier = Modifier
                .fillMaxSize()
                .alpha(overlayChrome.alpha)
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showInlineBack) {
                VoiceOverlayBalancedToolbar(
                    onBack = onBack,
                    surfaceContainer = if (showVideoBg) Color(0x66000000) else surfaceContainer,
                    iconTint = overlayTitleColor,
                    backContentDescription = stringResource(R.string.voice_message_back),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isCallMode && !callEnded) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = ui.dp(24f)),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = statusTitle,
                            fontSize = ui.sp(if (showVideoBg) 28f else 34f),
                            fontWeight = FontWeight.Bold,
                            color = when {
                                micBlocked -> errorColor
                                pttState == PushToTalkState.Recording && !showVideoBg -> errorColor
                                else -> overlayTitleColor
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = statusDesc,
                            fontSize = ui.sp(18f),
                            fontWeight = FontWeight.Medium,
                            color = overlayBodyColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = ui.dp(12f))
                        )
                    }

                    if (isCallConnected) {
                        VoiceCallConnectedControls(
                            modifier = Modifier.fillMaxSize(),
                            barColor = controlBarColor,
                            textColor = controlTextColor,
                            videoEnabled = callSessionId != null && videoPeers.isNotEmpty(),
                            videoActive = videoState.localVideoActive,
                            muted = callMicMuted,
                            onVideoToggle = videoState.toggleVideo,
                            onHangup = {
                                callHungUp = true
                                if (!callEnded) {
                                    callController.hangup()
                                }
                                onBack()
                            },
                            onMuteToggle = {
                                AvaVoiceSessionHub.setCallMicMuted(!callMicMuted)
                            },
                            onFlipCamera = videoState.flipCamera,
                            scale = ui
                        )
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val fitted = rememberFittedVoiceScale(
                                paneWidthDp = maxWidth.value,
                                paneHeightDp = maxHeight.value,
                                designWidthDp = VoiceCallBarDesignWidthDp * ui.control,
                                scale = ui,
                            )
                            VoiceCallV4ControlBar(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = fitted.dp(28f)),
                                barColor = controlBarColor,
                                textColor = controlTextColor,
                                videoEnabled = false,
                                videoActive = false,
                                onVideoToggle = {},
                                onHangup = {
                                    callHungUp = true
                                    callController.hangup()
                                    onBack()
                                },
                                scale = fitted
                            )
                        }
                    }
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val micFit = rememberFittedVoiceScale(
                        paneWidthDp = maxWidth.value,
                        paneHeightDp = maxHeight.value,
                        designWidthDp = messageTouchSize.value,
                        scale = messageUi,
                    ).control / messageUi.control.coerceAtLeast(0.01f)
                    val fittedButton = mainButtonSize * micFit
                    val fittedTouch = messageTouchSize * micFit
                    val fittedIcon = messageIconSize * micFit
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = messageUi.dp(24f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = statusTitle,
                            fontSize = messageUi.sp(26f),
                            fontWeight = FontWeight.Bold,
                            color = when {
                                micBlocked -> errorColor
                                pttState == PushToTalkState.Recording -> errorColor
                                else -> overlayTitleColor
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = statusDesc,
                            fontSize = messageUi.sp(16f),
                            fontWeight = FontWeight.Medium,
                            color = overlayBodyColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = messageUi.dp(10f))
                        )
                        if (!isHaAutoMessage) {
                            Spacer(modifier = Modifier.height(messageUi.dp(28f)))
                            Box(
                                modifier = Modifier
                                    .size(fittedTouch)
                                    .pointerInteropFilter { event ->
                                        handleMotionEvent(
                                            event = event,
                                            onDown = { startHold() },
                                            onUp = { endHold() }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                VoiceRadialColorGlow(
                                    color = micColor,
                                    modifier = Modifier.size(fittedButton * 1.25f),
                                    enabled = pttState == PushToTalkState.Recording
                                )
                                Surface(
                                    modifier = Modifier
                                        .size(fittedButton)
                                        .voiceAccentButtonShadow(color = micColor),
                                    shape = CircleShape,
                                    color = micColor,
                                    shadowElevation = 0.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = when (pttState) {
                                                PushToTalkState.Recording -> Icons.Default.Stop
                                                PushToTalkState.Sending, PushToTalkState.Sent -> Icons.Default.Check
                                                else -> Icons.Default.Mic
                                            },
                                            contentDescription = stringResource(R.string.voice_message_mic),
                                            tint = onAccent,
                                            modifier = Modifier.size(fittedIcon)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        }
    }
}

private fun deviceTypeIcon(type: AvaVoiceDeviceType): ImageVector = when (type) {
    AvaVoiceDeviceType.PHONE -> Icons.Default.PhoneAndroid
    AvaVoiceDeviceType.TABLET -> Icons.Default.Tablet
    AvaVoiceDeviceType.SPEAKER -> Icons.Default.Speaker
    AvaVoiceDeviceType.TV -> Icons.Default.Tv
    AvaVoiceDeviceType.UNKNOWN -> Icons.Default.PhoneAndroid
}
