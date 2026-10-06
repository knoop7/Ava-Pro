package com.example.ava.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import com.example.ava.ui.OverlayLogoBadge

/**
 * Glass Music Player - A hand-crafted Compose UI inspired by iOS design.
 * 
 * Features:
 * - Blurred background with playing state transition
 * - Noise texture overlay
 * - Ambient glow breathing animation
 * - Live indicator
 * - Vignette effect
 * - Shimmer text effect
 * - Glass-style control buttons (Squircle)
 * - Progress bar with glow
 * - Time capsules
 */
@Composable
fun GlassMusicPlayerView(
    coverUrl: String?,
    coverBitmap: Bitmap?,
    songTitle: String,
    artistName: String,
    isPlaying: Boolean,
    volumeLevel: Float = 1.0f,
    repeatMode: String = "off",
    shuffleEnabled: Boolean = false,
    isSendspinSource: Boolean = false,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onVolumeChange: (Float) -> Unit = {},
    onRepeatClick: () -> Unit = {},
    onShuffleClick: () -> Unit = {},
    /** Touch wakes [DashboardOverlayChrome] (same as weather); back button dismisses. */
    onRevealChrome: () -> Unit,
    onInvalidData: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Helper to check if a string is null-like
    fun isNullLike(s: String?): Boolean {
        if (s == null) return true
        val lower = s.lowercase().trim()
        return lower == "null" || lower.isBlank()
    }
    
    // Sanitize display values - replace null-like strings with empty
    val displayTitle = if (isNullLike(songTitle)) "" else songTitle
    val displayArtist = if (isNullLike(artistName)) "" else artistName
    val displayCoverUrl = if (isNullLike(coverUrl)) null else coverUrl
    
    // --- UI-level protection for track switching --------------------------
    // During prev/next the server briefly emits transitional frames where the
    // title can be momentarily blank. Tearing the overlay down on that single
    // frame is what makes switching look "broken" (container disappears and
    // reappears). We keep the last valid content "sticky" so the gap renders
    // the previous track instead of going blank.
    val hasValidNow = displayTitle.isNotBlank()
    var stickyTitle by remember { mutableStateOf(displayTitle) }
    var stickyArtist by remember { mutableStateOf(displayArtist) }
    var stickyCoverBitmap by remember { mutableStateOf(coverBitmap) }
    if (hasValidNow) {
        stickyTitle = displayTitle
        stickyArtist = displayArtist
        stickyCoverBitmap = coverBitmap
    }

    // Debounced invalid-data signal: only fire onInvalidData (which hides the
    // container) if the data is STILL blank after a short grace window. A new
    // track arriving within the window flips `hasValidNow`, which restarts this
    // effect and cancels the pending teardown, so the overlay survives the gap.
    // Do not route this through chrome reveal — data gaps are silent.
    LaunchedEffect(hasValidNow) {
        if (!hasValidNow) {
            kotlinx.coroutines.delay(2000)
            onInvalidData?.invoke()
        }
    }

    // Only refuse to render on a genuine empty state - never on a track-switch
    // blip (we still have sticky content to show).
    if (stickyTitle.isBlank()) {
        return
    }

    // Effective values for the rest of the UI: sticky during a blank gap.
    // Never hand a recycled bitmap to Image — that crashes in Canvas draw.
    val effectiveTitle = if (hasValidNow) displayTitle else stickyTitle
    val effectiveArtist = if (hasValidNow) displayArtist else stickyArtist
    val effectiveCoverBitmap = (if (hasValidNow) coverBitmap else stickyCoverBitmap)
        ?.takeUnless { it.isRecycled }

    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )
    
    // Background blur/opacity transition based on playing state
    val targetBlur = if (isPlaying) 0.dp else 20.dp
    val targetOpacity = if (isPlaying) 0.7f else 0.4f
    
    val backgroundBlur by animateDpAsState(targetValue = targetBlur, animationSpec = tween(800), label = "blur")
    val backgroundOpacity by animateFloatAsState(targetValue = targetOpacity, animationSpec = tween(800), label = "opacity")
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // ~3s long-press wakes the shared top «返回» strip (auto-hide 3s).
            .revealMediaOverlayChromeOnLongPress(onRevealChrome)
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(1.2f)
                .alpha(backgroundOpacity)
                .blur(backgroundBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        ) {
            if (effectiveCoverBitmap != null) {
                val coverImage = remember(effectiveCoverBitmap) {
                    runCatching { effectiveCoverBitmap.asImageBitmap() }.getOrNull()
                }
                if (coverImage != null && !effectiveCoverBitmap.isRecycled) {
                    androidx.compose.foundation.Image(
                        bitmap = coverImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111111)))
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111111)))
            }
        }


        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .scale(glowScale)
                .alpha(glowAlpha)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    radius = size.minDimension * 0.8f
                ),
                radius = size.minDimension * 0.8f,
                center = center
            )
        }


        NoiseOverlay(modifier = Modifier.fillMaxSize().alpha(0.08f))


        Vignette(modifier = Modifier.fillMaxSize())

        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val screenMinDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        val scaleFactor = (screenMinDp / 360f).coerceIn(1f, 2f)

        val logoLayout = OverlayLogoBadge.rememberLayoutDp()

        val context = LocalContext.current
        val logoFileName = if (isSendspinSource) "sendspin_logo.png" else "ha_logo.png"
        val logoBitmap = remember(isSendspinSource) {
            try {
                context.assets.open(logoFileName).use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: Exception) {
                null
            }
        }

        logoBitmap?.let { bitmap ->
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (isSendspinSource) "Music Assistant" else "Home Assistant",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = logoLayout.edgeInsetDp, end = logoLayout.edgeInsetDp)
                    .size(logoLayout.sizeDp)
                    .alpha(0.25f)
            )
        }



        val horizontalPadding = ((if (isLandscape) 80 else 40) * scaleFactor).dp
        val topPadding = ((if (isLandscape) 60 else 80) * scaleFactor).dp
        val bottomPadding = ((if (isLandscape) 40 else 60) * scaleFactor).dp
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.Bottom
        ) {

            Column(modifier = Modifier.padding(bottom = (10 * scaleFactor).dp)) {
                ShimmerText(
                    text = effectiveTitle,
                    scaleFactor = scaleFactor,
                    modifier = Modifier.padding(bottom = (10 * scaleFactor).dp)
                )
                
                // Only show artist if not empty
                if (effectiveArtist.isNotBlank()) {
                    Column {
                        Text(
                            text = effectiveArtist,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = (18 * scaleFactor).sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = (0.5f * scaleFactor).sp
                        )
                        Spacer(modifier = Modifier.height((6 * scaleFactor).dp))
                        Box(
                            modifier = Modifier
                                .width((40 * scaleFactor).dp)
                                .height((2 * scaleFactor).dp)
                                .background(
                                    Color.White.copy(alpha = 0.3f),
                                    RoundedCornerShape((4 * scaleFactor).dp)
                                )
                        )
                    }
                }
            }
            
            
            Spacer(modifier = Modifier.height((20 * scaleFactor).dp))


            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isLandscape) Arrangement.Center else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val smallIconSize = ((if (isLandscape) 28 else 24) * scaleFactor).dp
                val mediumIconSize = ((if (isLandscape) 36 else 30) * scaleFactor).dp
                val buttonSpacing = (40 * scaleFactor).dp
                val buttonPadding = ((if (isLandscape) 12 else 8) * scaleFactor).dp

                ControlButton(onClick = onShuffleClick, buttonPadding = buttonPadding) {
                    IconWithShadow(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                        size = smallIconSize
                    )
                }
                
                if (isLandscape) Spacer(modifier = Modifier.width(buttonSpacing))
                

                ControlButton(onClick = onPreviousClick, buttonPadding = buttonPadding) {
                    IconWithShadow(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White.copy(alpha = 0.5f),
                        size = mediumIconSize
                    )
                }
                
                if (isLandscape) Spacer(modifier = Modifier.width(buttonSpacing))
                
                // Play/Pause
                GlassPlayButton(
                    isPlaying = isPlaying,
                    onClick = onPlayPauseClick,
                    scaleFactor = scaleFactor
                )
                
                if (isLandscape) Spacer(modifier = Modifier.width(buttonSpacing))
                

                ControlButton(onClick = onNextClick, buttonPadding = buttonPadding) {
                    IconWithShadow(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White.copy(alpha = 0.5f),
                        size = mediumIconSize
                    )
                }
                
                if (isLandscape) Spacer(modifier = Modifier.width(buttonSpacing))
                

                ControlButton(onClick = onRepeatClick, buttonPadding = buttonPadding) {
                    when (repeatMode) {
                        "one" -> IconWithShadow(
                            imageVector = Icons.Filled.RepeatOne,
                            contentDescription = "Repeat One",
                            tint = Color.White,
                            size = smallIconSize
                        )
                        "all" -> IconWithShadow(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = "Repeat All",
                            tint = Color.White,
                            size = smallIconSize
                        )
                        else -> IconWithShadow(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = "Repeat Off",
                            tint = Color.White.copy(alpha = 0.3f),
                            size = smallIconSize
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun IconWithShadow(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    Box {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.15f),
            modifier = Modifier
                .size(size)
                .offset(x = 0.dp, y = 3.dp)
                .blur(3.dp)
        )
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.2f),
            modifier = Modifier
                .size(size)
                .offset(x = 0.dp, y = 2.dp)
                .blur(2.dp)
        )
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun ShimmerText(text: String, scaleFactor: Float = 1f, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = (42 * scaleFactor).sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5f * scaleFactor).sp,
        lineHeight = (48 * scaleFactor).sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}


@Composable
private fun ControlButton(
    onClick: () -> Unit,
    buttonPadding: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "controlScale"
    )
    
    Box(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    onClick()
                }
            )
            .padding(buttonPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
    
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}

@Composable
private fun GlassPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    scaleFactor: Float = 1f
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "playBtnScale"
    )
    
    val backgroundColor by animateColorAsState(
        targetValue = if (pressed) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.2f),
        animationSpec = tween(150),
        label = "playBtnBg"
    )
    
    val buttonSize = ((if (isLandscape) 88 else 72) * scaleFactor).dp
    val iconSize = ((if (isLandscape) 38 else 32) * scaleFactor).dp
    val squircleShape = RoundedCornerShape(28)
    
    Box(
        modifier = Modifier
            .size(buttonSize)
            .scale(scale)
            .background(backgroundColor, squircleShape)
            .border(
                width = (1 * scaleFactor).dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.6f),
                        Color.White.copy(alpha = 0.15f)
                    )
                ),
                shape = squircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
    
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(100)
            pressed = false
        }
    }
}

@Composable
private fun NoiseOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val random = java.util.Random(42)
        val dotCount = 500
        for (i in 0 until dotCount) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val alpha = random.nextFloat() * 0.5f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = 1.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun Vignette(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.4f),
                    Color.Black.copy(alpha = 0.95f)
                ),
                center = center,
                radius = size.maxDimension * 0.7f
            )
        )
    }
}
