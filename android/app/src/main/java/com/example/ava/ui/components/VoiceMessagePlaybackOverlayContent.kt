package com.example.ava.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.OverlayFloatingCloseButton
import com.example.ava.ui.QuickEntityOverlayRoot
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.voice.AvaVoiceIncomingMessage
import com.example.ava.voice.AvaVoiceIncomingPhase
import com.example.ava.voice.AvaVoiceMessageBoard
import com.example.ava.voice.AvaVoiceMode
import com.example.ava.voice.AvaVoiceCallController
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDeviceType
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceMessenger
import com.example.ava.voice.AvaVoiceNetwork
import com.example.ava.voice.AvaVoiceSessionHub
import com.example.ava.voice.AvaVoiceVideoBridge

@Composable
fun VoiceMessagePlaybackOverlayContent(
    message: AvaVoiceIncomingMessage,
    progress: Float,
    messageBoardMode: Boolean,
    onDismiss: () -> Unit,
    onAnswer: () -> Unit = {},
    onDecline: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val homePrefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(homePrefs, KEY_DARK_MODE, false)
    val surfaceColor = if (isDarkMode) Color(0xFF121316) else Color(0xFFFAF9FD)
    val surfaceContainer = if (isDarkMode) Color(0xFF2A2D33) else getSliderInactiveColor()
    val accent = getAccentColor()
    val titleColor = getTitleColor()
    // Neutral overlay tints, not the slider palette: the slider-inactive gray is designed
    // for light surfaces and all but disappears on the dark overlay background.
    val ringTrack = if (isDarkMode) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)
    val pulseDisc = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
    val scope = rememberCoroutineScope()
    val isRinging = message.mode == AvaVoiceMode.Call && message.phase == AvaVoiceIncomingPhase.Ringing
    val isCallConnected = message.mode == AvaVoiceMode.Call && !isRinging
    val callTarget = remember(message.fromDeviceId, message.fromName, message.sourceHost) {
        if (message.mode == AvaVoiceMode.Call && message.sourceHost.isNotBlank()) {
            listOf(
                AvaVoiceDevice(
                    id = message.fromDeviceId,
                    name = message.fromName,
                    host = message.sourceHost,
                    type = AvaVoiceDeviceType.UNKNOWN
                )
            )
        } else {
            emptyList()
        }
    }
    val callController = remember(callTarget) {
        AvaVoiceCallController(
            scope = scope,
            localDeviceId = AvaVoiceDiscovery.localId(),
            localDeviceName = AvaVoiceDiscovery.localName(),
            targets = callTarget
        )
    }
    val peerDeviceId = message.fromDeviceId
    val peerHost = message.sourceHost
    val localDeviceId = AvaVoiceDiscovery.localId()
    val videoPeers = remember(isCallConnected, callTarget) {
        if (isCallConnected) callTarget.filter { it.host.isNotBlank() } else emptyList()
    }
    val callSessionId = if (isCallConnected && message.sessionId != 0) message.sessionId else null
    val videoState = rememberVoiceCallVideoState(
        isCallConnected = isCallConnected,
        sessionId = callSessionId,
        localDeviceId = localDeviceId,
        peers = videoPeers
    )
    val callMicMuted by AvaVoiceSessionHub.callMicMuted.collectAsState()
    val peerVideoActive by AvaVoiceVideoBridge.peerVideoActive.collectAsState()
    val remoteJpeg by AvaVoiceVideoBridge.remoteJpeg.collectAsState()
    val showVideoBg = isCallConnected && peerVideoActive && remoteJpeg != null
    val overlayChrome = rememberVoiceCallOverlayChrome(showVideoBg)
    val ui = rememberVoiceCallUiScale()
    DisposableEffect(isCallConnected, peerDeviceId, peerHost) {
        if (!isCallConnected || peerDeviceId.isBlank() || peerHost.isBlank()) {
            return@DisposableEffect onDispose { }
        }
        val peerIds = setOf(peerDeviceId)
        val startMic = !AvaVoiceMessenger.hasActiveCallOutboundTo(
            AvaVoiceDiscovery.localId(),
            peerIds
        )
        AvaVoiceNetwork.enterCallDuplex()
        if (startMic) {
            callController.startMicrophone()
        }
        onDispose {
            if (startMic) {
                callController.stopMicrophone()
            }
            AvaVoiceNetwork.leaveCallDuplex()
        }
    }
    QuickEntityOverlayRoot(
        backgroundColor = if (showVideoBg) Color.Black else surfaceColor,
        modifier = modifier
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            VoiceCallRemoteVideoLayer(
                visible = showVideoBg,
                contentPadding = PaddingValues(),
                modifier = Modifier.fillMaxSize(),
                onOverlayReveal = overlayChrome::onTouchReveal
            )
            VoiceCallLocalPipLayer(
                visible = isCallConnected && videoState.localVideoActive,
                contentPadding = PaddingValues(),
                modifier = Modifier.fillMaxSize(),
                scale = ui,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .voiceCallOverlayTouchReveal(showVideoBg, overlayChrome)
            ) {
            if (message.mode != AvaVoiceMode.Call) {
                OverlayFloatingCloseButton(
                    modifier = Modifier.align(Alignment.TopEnd),
                    onClick = onDismiss,
                    containerColor = surfaceContainer,
                    iconTint = titleColor,
                    contentDescription = stringResource(R.string.voice_message_close),
                )
            }

            VoiceCallOverlayChromeHost(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(overlayChrome.alpha)
            ) {
            when {
                message.mode == AvaVoiceMode.Call && isCallConnected -> {
                    val overlayTitle = if (showVideoBg) Color.White else titleColor
                    val overlayBody = if (showVideoBg) Color.White.copy(alpha = 0.78f) else titleColor.copy(alpha = 0.68f)
                    val controlBarColor = if (showVideoBg) Color(0xE62A2D33) else surfaceContainer
                    val controlTextColor = if (showVideoBg) Color.White else titleColor

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = ui.dp(24f))
                        ) {
                            Text(
                                text = stringResource(R.string.voice_call_incoming_title),
                                color = overlayTitle,
                                fontSize = ui.sp(24f),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(R.string.voice_call_connected_with, message.fromName),
                                color = overlayBody,
                                fontSize = ui.sp(18f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = ui.dp(12f))
                            )
                        }
                    }

                    VoiceCallConnectedControls(
                        modifier = Modifier.fillMaxSize(),
                        barColor = controlBarColor,
                        textColor = controlTextColor,
                        videoEnabled = callSessionId != null && videoPeers.isNotEmpty(),
                        videoActive = videoState.localVideoActive,
                        muted = callMicMuted,
                        onVideoToggle = videoState.toggleVideo,
                        onHangup = {
                            AvaVoiceSessionHub.stopInboundCallStreams(setOf(message.fromDeviceId))
                            callController.hangup()
                            onDismiss()
                        },
                        onMuteToggle = {
                            AvaVoiceSessionHub.setCallMicMuted(!callMicMuted)
                        },
                        onFlipCamera = videoState.flipCamera,
                        scale = ui
                    )
                }
                message.mode == AvaVoiceMode.Call && isRinging -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.voice_message_incoming_from, message.fromName),
                                color = titleColor,
                                fontSize = ui.sp(24f),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = ui.dp(24f),
                                        vertical = ui.dp(12f)
                                    )
                            )
                            Spacer(modifier = Modifier.height(ui.dp(24f)))
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ui.dp(24f))
                            ) {
                                val ringDiameter = voiceCallActionButtonDiameter(
                                    slotWidth = maxWidth / 2f,
                                    uiScale = ui.control
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    VoiceCallCircleButton(
                                        color = Color(0xFFBA1A1A),
                                        icon = Icons.Default.CallEnd,
                                        label = stringResource(R.string.voice_call_decline),
                                        onClick = onDecline,
                                        diameter = ringDiameter
                                    )
                                    VoiceCallCircleButton(
                                        color = Color(0xFF1B8A4A),
                                        icon = Icons.Default.Call,
                                        label = stringResource(R.string.voice_call_answer),
                                        onClick = onAnswer,
                                        diameter = ringDiameter
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Weather-overlay sizing pattern: everything derives from the shorter
                    // screen side (vmin) so the layout scales phone → tablet → TV and can
                    // never overflow in either orientation.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val vmin = if (maxWidth < maxHeight) maxWidth else maxHeight
                        val pulseSize = (vmin * 0.44f).coerceIn(ui.dp(150f), ui.dp(340f))
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val canReplay = messageBoardMode && message.phase == AvaVoiceIncomingPhase.Ended
                            IncomingSpeakerPulse(
                                accent = accent,
                                track = ringTrack,
                                disc = pulseDisc,
                                progress = progress,
                                fromName = message.fromName,
                                replayAvailable = canReplay,
                                onReplay = if (canReplay) {
                                    { AvaVoiceMessageBoard.replay(message.sessionId) }
                                } else {
                                    null
                                },
                                modifier = Modifier.size(pulseSize)
                            )
                            Text(
                                text = stringResource(R.string.voice_message_incoming_title),
                                color = titleColor,
                                fontSize = ui.sp(26f),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ui.dp(24f))
                                    .padding(top = ui.dp(28f), bottom = ui.dp(6f))
                            )
                            Text(
                                text = stringResource(R.string.voice_message_incoming_from, message.fromName),
                                color = titleColor.copy(alpha = 0.68f),
                                fontSize = ui.sp(18f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ui.dp(24f))
                            )
                            if (message.durationMs > 0L) {
                                Text(
                                    text = stringResource(
                                        R.string.voice_message_incoming_duration,
                                        (message.durationMs / 1000L).coerceAtLeast(1L)
                                    ),
                                    color = titleColor.copy(alpha = 0.52f),
                                    fontSize = ui.sp(16f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = ui.dp(24f),
                                            vertical = ui.dp(8f)
                                        )
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

/** Ring diameter as a fraction of the pulse box — shared by playing and replay states. */
private const val PULSE_RING_FRACTION = 0.78f

/** Icon size as a fraction of the pulse box (~45% of the ring diameter — never collides). */
private const val PULSE_ICON_FRACTION = 0.34f

/**
 * Progress ring + icon for an incoming voice message. All geometry is proportional to
 * the box the caller sizes (which itself follows the screen's shorter side), so the
 * icon always sits comfortably inside the ring and nothing overflows on any screen.
 * Playing and replay states share the same ring so the circle doesn't jump between them.
 */
@Composable
private fun IncomingSpeakerPulse(
    accent: Color,
    track: Color,
    disc: Color,
    progress: Float,
    fromName: String,
    replayAvailable: Boolean,
    onReplay: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "speaker_pulse")
    val breath by transition.animateFloat(
        initialValue = if (replayAvailable) 0.99f else 0.96f,
        targetValue = if (replayAvailable) 1.03f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (replayAvailable) 1600 else 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_breath"
    )
    Box(
        modifier = if (replayAvailable && onReplay != null) {
            modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onReplay
            )
        } else {
            modifier
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringRadius = size.minDimension * (PULSE_RING_FRACTION / 2f)
            val stroke = (size.minDimension * 0.030f)
                .coerceIn(3.dp.toPx(), 8.dp.toPx())
            if (!replayAvailable) {
                // Soft halo breathing outside the ring while audio is playing.
                drawCircle(
                    color = accent.copy(alpha = 0.08f),
                    radius = (ringRadius + stroke) * breath * 1.10f,
                    center = center
                )
            }
            drawCircle(
                color = disc,
                radius = ringRadius - stroke * 1.5f,
                center = center
            )
            drawCircle(
                color = track,
                radius = ringRadius,
                center = center,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        val contentDescription = if (replayAvailable) {
            stringResource(R.string.voice_message_replay)
        } else {
            stringResource(R.string.voice_message_incoming_title) + ": $fromName"
        }
        Icon(
            imageVector = if (replayAvailable) Icons.Default.PlayArrow else Icons.Default.VolumeUp,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier
                .fillMaxSize(PULSE_ICON_FRACTION)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                }
        )
    }
}
