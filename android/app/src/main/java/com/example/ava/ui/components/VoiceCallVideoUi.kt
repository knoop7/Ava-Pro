package com.example.ava.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import android.graphics.BitmapFactory
import com.example.ava.R
import com.example.ava.camera.VoiceCallVideoEnhancer
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceVideoBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHROME_DIM_ALPHA = 0.18f
private const val CHROME_REVEAL_HOLD_MS = 4_000L

/** Overlay chrome: full opacity without video; auto-dim over video, touch to reveal. */
@Composable
fun rememberVoiceCallOverlayChrome(videoBackgroundActive: Boolean): VoiceCallOverlayChrome {
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(videoBackgroundActive) {
        revealed = false
    }

    LaunchedEffect(revealed, videoBackgroundActive) {
        if (!videoBackgroundActive || !revealed) return@LaunchedEffect
        delay(CHROME_REVEAL_HOLD_MS)
        revealed = false
    }

    val alpha by animateFloatAsState(
        targetValue = when {
            !videoBackgroundActive -> 1f
            revealed -> 1f
            else -> CHROME_DIM_ALPHA
        },
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "voiceCallChromeAlpha"
    )

    return remember(videoBackgroundActive, alpha) {
        VoiceCallOverlayChrome(
            alpha = alpha,
            reveal = { if (videoBackgroundActive) revealed = true }
        )
    }
}

class VoiceCallOverlayChrome(
    val alpha: Float,
    private val reveal: () -> Unit
) {
    fun onTouchReveal() = reveal()
}

/** Pass-through tap — reveals chrome without consuming button clicks. */
fun Modifier.voiceCallOverlayTouchReveal(
    enabled: Boolean,
    chrome: VoiceCallOverlayChrome
): Modifier {
    if (!enabled) return this
    return pointerInput(chrome) {
        detectTapGestures(onTap = { chrome.onTouchReveal() })
    }
}

@Composable
fun VoiceCallOverlayChromeHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
    }
}

private enum class PreviewFocus {
    Dim,
    Clear
}

/** Extend video/background into overlay safe-area padding so it fills the floating window. */
@Composable
fun Modifier.overlayFullBleed(contentPadding: PaddingValues): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val start = contentPadding.calculateStartPadding(layoutDirection)
    val top = contentPadding.calculateTopPadding()
    val end = contentPadding.calculateEndPadding(layoutDirection)
    val bottom = contentPadding.calculateBottomPadding()
    if (start == 0.dp && top == 0.dp && end == 0.dp && bottom == 0.dp) {
        return this
    }
    // Negative padding throws on newer Compose; expand measure/placement instead.
    return this.layout { measurable, constraints ->
        val startPx = start.roundToPx()
        val topPx = top.roundToPx()
        val endPx = end.roundToPx()
        val bottomPx = bottom.roundToPx()
        val expanded = Constraints(
            minWidth = constraints.minWidth + startPx + endPx,
            maxWidth = constraints.maxWidth + startPx + endPx,
            minHeight = constraints.minHeight + topPx + bottomPx,
            maxHeight = constraints.maxHeight + topPx + bottomPx
        )
        val placeable = measurable.measure(expanded)
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(-startPx, -topPx)
        }
    }
}

/**
 * Remote peer video — full floating-window bleed, WeChat-style dim → clear on tap.
 */
@Composable
fun VoiceCallRemoteVideoLayer(
    visible: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    onOverlayReveal: () -> Unit = {}
) {
    val remoteJpeg by AvaVoiceVideoBridge.remoteJpeg.collectAsState()
    var previewFocus by remember { mutableStateOf(PreviewFocus.Dim) }

    LaunchedEffect(visible, remoteJpeg) {
        if (!visible || remoteJpeg == null) {
            previewFocus = PreviewFocus.Dim
        }
    }

    // No peer video → no dim layer; with video → dim until touch.
    val dimAlpha by animateFloatAsState(
        targetValue = if (previewFocus == PreviewFocus.Dim) 0.42f else 1f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "videoDimAlpha"
    )

    AnimatedVisibility(
        visible = visible && remoteJpeg != null,
        enter = fadeIn(tween(420, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(320, easing = FastOutSlowInEasing)),
        modifier = modifier
    ) {
        val jpeg = remoteJpeg ?: return@AnimatedVisibility
        val bitmap by produceState<ImageBitmap?>(null, jpeg) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        ?.let { decoded ->
                            VoiceCallVideoEnhancer.enhanceForDisplay(decoded).asImageBitmap()
                        }
                }.getOrNull()
            }
        }
        if (bitmap == null) return@AnimatedVisibility

        Box(
            modifier = Modifier
                .fillMaxSize()
                .overlayFullBleed(contentPadding)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures {
                        onOverlayReveal()
                        previewFocus = when (previewFocus) {
                            PreviewFocus.Dim -> PreviewFocus.Clear
                            PreviewFocus.Clear -> PreviewFocus.Dim
                        }
                    }
                }
        ) {
            Image(
                bitmap = bitmap!!,
                contentDescription = stringResource(R.string.voice_call_video_remote),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(dimAlpha)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xCC000000))
                        )
                    )
            )

        }
    }
}

/**
 * Local self preview — floating inset only while local video is on.
 * Flip stays outside on the top-end (see [VoiceCallFlipCameraButton]); this layer never hosts controls.
 * Front-camera preview is mirrored in UI only; uplink JPEG is unchanged.
 *
 * Top-start, vertically near the flip button (slightly lower). Portrait 108×144, landscape 160×120.
 */
@Composable
fun VoiceCallLocalPipLayer(
    visible: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    scale: VoiceCallUiScale = rememberVoiceCallUiScale(),
) {
    val localActive by AvaVoiceVideoBridge.localVideoActive.collectAsState()
    val localJpeg by AvaVoiceVideoBridge.localJpeg.collectAsState()
    val mirrorPreview by AvaVoiceVideoBridge.localFrontCamera.collectAsState()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val pipWidth = scale.dp(if (landscape) 160f else 108f)
    val pipHeight = scale.dp(if (landscape) 120f else 144f)
    val corner = scale.dp(if (landscape) 12f else 14f)
    // Match flip button top inset (14) and sit a touch lower for visual balance.
    val topPad = scale.dp(if (landscape) 18f else 20f)
    val startPad = scale.dp(14f)

    AnimatedVisibility(
        visible = visible && localActive,
        enter = fadeIn(tween(280, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(220, easing = FastOutSlowInEasing)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .overlayFullBleed(contentPadding)
        ) {
            val jpeg = localJpeg
            val bitmap by produceState<ImageBitmap?>(null, jpeg) {
                value = if (jpeg == null || jpeg.isEmpty()) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                ?.let { decoded ->
                                    VoiceCallVideoEnhancer.enhanceForDisplay(decoded).asImageBitmap()
                                }
                        }.getOrNull()
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = topPad, start = startPad)
                    .size(width = pipWidth, height = pipHeight)
                    .shadow(scale.dp(10f), RoundedCornerShape(corner))
                    .clip(RoundedCornerShape(corner))
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(corner),
                    )
                    .background(Color(0xFF1A1D24))
                    // Consume taps so they do not dim/clear the remote layer underneath.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier
                            .fillMaxSize()
                            // Mirror only the drawn preview for front camera (selfie UX).
                            .graphicsLayer { scaleX = if (mirrorPreview) -1f else 1f },
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceCallFlipCameraButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: VoiceCallUiScale = rememberVoiceCallUiScale()
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color(0xB3121316),
            shadowElevation = scale.dp(4f)
        ) {
            Icon(
                imageVector = Icons.Default.FlipCameraAndroid,
                contentDescription = stringResource(R.string.voice_call_flip_camera),
                tint = Color.White,
                modifier = Modifier
                    .padding(scale.dp(12f))
                    .size(scale.dp(26f))
            )
        }
    }
}

/** V4 bottom capsule: [视频 | 挂断] */
@Composable
fun VoiceCallV4ControlBar(
    modifier: Modifier = Modifier,
    barColor: Color,
    textColor: Color,
    videoEnabled: Boolean,
    videoActive: Boolean,
    onVideoToggle: () -> Unit,
    onHangup: () -> Unit,
    scale: VoiceCallUiScale = rememberVoiceCallUiScale()
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(scale.dp(48f)),
        color = barColor,
        shadowElevation = scale.dp(8f)
    ) {
        Row(
            modifier = Modifier.padding(scale.dp(8f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { if (videoEnabled) onVideoToggle() },
                shape = RoundedCornerShape(scale.dp(40f)),
                color = when {
                    !videoEnabled -> Color.Transparent
                    videoActive -> Color(0x2E4F8CFF)
                    else -> Color.Transparent
                }
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = scale.dp(20f),
                        vertical = scale.dp(12f)
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (videoActive) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = stringResource(
                            if (videoActive) R.string.voice_call_video_on else R.string.voice_call_video_off
                        ),
                        tint = when {
                            !videoEnabled -> textColor.copy(alpha = 0.35f)
                            videoActive -> Color(0xFF93C5FD)
                            else -> textColor
                        },
                        modifier = Modifier.size(scale.dp(22f))
                    )
                    Text(
                        text = stringResource(
                            if (videoActive) R.string.voice_call_video_on else R.string.voice_call_video_off
                        ),
                        color = when {
                            !videoEnabled -> textColor.copy(alpha = 0.35f)
                            videoActive -> Color(0xFF93C5FD)
                            else -> textColor
                        },
                        fontSize = scale.sp(15f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = scale.dp(8f))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = scale.dp(9f))
                    .width(scale.dp(1f))
                    .height(scale.dp(28f))
                    .background(Color(0xFF404550))
            )

            Surface(
                onClick = onHangup,
                shape = RoundedCornerShape(scale.dp(40f)),
                color = Color(0xFFBA1A1A)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = scale.dp(24f),
                        vertical = scale.dp(12f)
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.voice_call_hangup),
                        tint = Color.White,
                        modifier = Modifier.size(scale.dp(22f))
                    )
                    Text(
                        text = stringResource(R.string.voice_call_hangup),
                        color = Color.White,
                        fontSize = scale.sp(15f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = scale.dp(8f))
                    )
                }
            }
        }
    }
}

/** Compact mute toggle — sits immediately beside the V4 capsule bar. */
@Composable
fun VoiceCallMuteSideButton(
    muted: Boolean,
    barColor: Color,
    iconColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    scale: VoiceCallUiScale = rememberVoiceCallUiScale()
) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (muted) Color(0xFF6B7280) else barColor,
        shadowElevation = scale.dp(6f),
        modifier = modifier.size(scale.dp(52f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = stringResource(
                    if (muted) R.string.voice_call_unmute else R.string.voice_call_mute
                ),
                tint = if (muted) Color.White else iconColor,
                modifier = Modifier.size(scale.dp(24f))
            )
        }
    }
}

@Composable
fun VoiceCallConnectedControls(
    modifier: Modifier = Modifier,
    barColor: Color,
    textColor: Color,
    videoEnabled: Boolean,
    videoActive: Boolean,
    muted: Boolean,
    onVideoToggle: () -> Unit,
    onHangup: () -> Unit,
    onMuteToggle: () -> Unit,
    onFlipCamera: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 28.dp,
    scale: VoiceCallUiScale = rememberVoiceCallUiScale()
) {
    BoxWithConstraints(modifier = modifier) {
        val rowFitted = rememberFittedVoiceScale(
            paneWidthDp = maxWidth.value,
            paneHeightDp = maxHeight.value,
            designWidthDp = VoiceCallConnectedDesignWidthDp * scale.control,
            scale = scale,
        )
        val barFitted = rememberFittedVoiceScale(
            paneWidthDp = maxWidth.value,
            paneHeightDp = maxHeight.value,
            designWidthDp = VoiceCallBarDesignWidthDp * scale.control,
            scale = scale,
        )
        val stackMicAbove = maxWidth < rowFitted.dp(VoiceCallConnectedDesignWidthDp)
        val fitted = if (stackMicAbove) barFitted else rowFitted
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            VoiceCallFlipCameraButton(
                visible = videoActive,
                onClick = onFlipCamera,
                scale = fitted,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = fitted.dp(14f), end = fitted.dp(14f))
            )
            if (stackMicAbove) {
                Column(
                    modifier = Modifier.padding(bottom = fitted.dp(bottomPadding)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    VoiceCallMuteSideButton(
                        muted = muted,
                        barColor = barColor,
                        iconColor = textColor,
                        onToggle = onMuteToggle,
                        scale = fitted
                    )
                    Spacer(modifier = Modifier.height(fitted.dp(10f)))
                    VoiceCallV4ControlBar(
                        barColor = barColor,
                        textColor = textColor,
                        videoEnabled = videoEnabled,
                        videoActive = videoActive,
                        onVideoToggle = onVideoToggle,
                        onHangup = onHangup,
                        scale = fitted
                    )
                }
            } else {
                Row(
                    modifier = Modifier.padding(bottom = fitted.dp(bottomPadding)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    VoiceCallV4ControlBar(
                        barColor = barColor,
                        textColor = textColor,
                        videoEnabled = videoEnabled,
                        videoActive = videoActive,
                        onVideoToggle = onVideoToggle,
                        onHangup = onHangup,
                        scale = fitted
                    )
                    Spacer(modifier = Modifier.width(fitted.dp(12f)))
                    VoiceCallMuteSideButton(
                        muted = muted,
                        barColor = barColor,
                        iconColor = textColor,
                        onToggle = onMuteToggle,
                        scale = fitted
                    )
                }
            }
        }
    }
}

@Composable
fun rememberVoiceCallVideoState(
    isCallConnected: Boolean,
    sessionId: Int?,
    localDeviceId: String,
    peers: List<AvaVoiceDevice>
): VoiceCallVideoState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localVideoActive by AvaVoiceVideoBridge.localVideoActive.collectAsState()
    val peerVideoActive by AvaVoiceVideoBridge.peerVideoActive.collectAsState()
    val remoteJpeg by AvaVoiceVideoBridge.remoteJpeg.collectAsState()

    DisposableEffect(isCallConnected, sessionId, localDeviceId, peers) {
        onDispose {
            if (sessionId != null && sessionId != 0) {
                scope.launch(Dispatchers.IO) {
                    AvaVoiceVideoBridge.setLocalVideoEnabled(
                        context = context,
                        enabled = false,
                        sessionId = sessionId,
                        deviceId = localDeviceId,
                        peers = peers
                    )
                }
            }
        }
    }

    return remember(isCallConnected, sessionId, peers, localVideoActive, peerVideoActive, remoteJpeg) {
        VoiceCallVideoState(
            localVideoActive = localVideoActive,
            peerVideoActive = peerVideoActive,
            showRemotePreview = peerVideoActive && remoteJpeg != null,
            toggleVideo = {
                sessionId?.let { id ->
                    scope.launch(Dispatchers.IO) {
                        AvaVoiceVideoBridge.setLocalVideoEnabled(
                            context = context,
                            enabled = !localVideoActive,
                            sessionId = id,
                            deviceId = localDeviceId,
                            peers = peers
                        )
                    }
                }
            },
            flipCamera = {
                AvaVoiceVideoBridge.flipLocalCamera(context)
            }
        )
    }
}

data class VoiceCallVideoState(
    val localVideoActive: Boolean,
    val peerVideoActive: Boolean,
    val showRemotePreview: Boolean,
    val toggleVideo: () -> Unit,
    val flipCamera: () -> Unit
)
