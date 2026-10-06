package com.example.ava.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.min

/**
 * Persistent circular mini control (edge variant E):
 * album art + scrim + play/pause, thin dark rim, and a one-lap progress ring.
 * Cover art spins while playing; pause coasts to the next upright (0°) pose — no snap.
 *
 * Center transport toggles playback; the rest of the circle expands.
 * Cover swaps use a short crossfade so track / upstream blips do not hard-flash.
 */
@Composable
fun CircleEqMiniPlayerFab(
    coverBitmap: Bitmap?,
    isPlaying: Boolean,
    onExpandClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    currentTimeMs: Long = 0L,
    totalTimeMs: Long = 0L,
    /**
     * MA waiting-for-media: cover only (no play control / progress ring) so
     * collapsed FAB taps cannot toggle play on an empty queue.
     */
    waitingForMedia: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val screenMinDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val scaleFactor = (screenMinDp / 360f).coerceIn(1f, 2f)
    // Outer disc slightly larger + transport slightly smaller → wider expand ring.
    // Keep in sync with VinylCoverService.miniFabShellSizePx().
    val fabSize = (86f * scaleFactor).dp
    val transportIconSize = (44f * scaleFactor).dp
    val transportHitSize = (48f * scaleFactor).dp

    val rawProgress = if (!waitingForMedia && totalTimeMs > 0L) {
        (currentTimeMs.toFloat() / totalTimeMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "miniFabProgress",
    )
    val coverRotationDeg = rememberMiniFabCoverRotation(isPlaying && !waitingForMedia)

    Box(
        modifier = modifier.size(fabSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(fabSize)
                .clip(CircleShape)
                .then(
                    if (interactionEnabled) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExpandClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Disc layer only — transport + progress ring stay upright.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = coverRotationDeg },
            ) {
                var lastGoodCover by remember { mutableStateOf<Bitmap?>(null) }
                val incoming = coverBitmap?.takeUnless { it.isRecycled }
                if (incoming != null) {
                    lastGoodCover = incoming
                }
                // Waiting shell: never keep the previous track's art under the FAB.
                val coverKey = if (waitingForMedia) {
                    incoming
                } else {
                    incoming ?: lastGoodCover?.takeUnless { it.isRecycled }
                }
                Crossfade(
                    targetState = coverKey,
                    animationSpec = tween(durationMillis = COVER_CROSSFADE_MS, easing = FastOutSlowInEasing),
                    label = "miniFabCover",
                    modifier = Modifier.fillMaxSize(),
                ) { bmp ->
                    val coverImage = remember(bmp) {
                        bmp?.let { runCatching { it.asImageBitmap() }.getOrNull() }
                    }
                    if (coverImage != null && bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = coverImage,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // Waiting / no-art: zoom Ava mark to fill the disc.
                        WaitingCoverImage()
                    }
                }
                // Waiting uses the colorful placeholder — skip the heavy black scrim.
                if (!waitingForMedia) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.40f)),
                    )
                }
            }

            if (!waitingForMedia) {
                Box(
                    modifier = Modifier
                        .size(transportHitSize)
                        .clip(CircleShape)
                        .then(
                            if (interactionEnabled) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onPlayPauseClick,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = isPlaying,
                        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
                        label = "miniFabTransport",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .size(transportIconSize)
                                .then(
                                    if (!playing) Modifier.offset(x = (2f * scaleFactor).dp)
                                    else Modifier,
                                ),
                        )
                    }
                }
            }
        }

        // Thin in-bounds rim + one-lap progress (no outward white feather / elevation halo).
        Canvas(
            modifier = Modifier
                .size(fabSize)
                .align(Alignment.Center),
        ) {
            val minSide = size.minDimension
            val progressStroke = (1.8f * scaleFactor).dp.toPx()
            val rimRadius = minSide / 2f - progressStroke / 2f

            drawCircle(
                color = Color.Black.copy(alpha = if (waitingForMedia) 0.22f else 0.42f),
                radius = rimRadius,
                style = Stroke(width = (1f * scaleFactor).dp.toPx()),
            )

            if (!waitingForMedia) {
                // Progress: one full lap around the circle (kept soft so cover stays primary).
                val arcTopLeft = Offset(progressStroke / 2f, progressStroke / 2f)
                val arcSize = Size(minSide - progressStroke, minSide - progressStroke)
                drawArc(
                    color = Color.White.copy(alpha = 0.05f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = progressStroke, cap = StrokeCap.Round),
                )
                if (progress > 0.002f) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.28f),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = progressStroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * Vinyl-style cover spin for the mini FAB.
 *
 * Phases (no mid-pose snap):
 * 1. **Playing** — ramp angular speed up to a slow cruise (~26s / lap).
 * 2. **Coast** (pause) — ease speed down while the disc keeps turning.
 * 3. **Home** — ease-in toward the next 0° origin, then hold.
 */
@Composable
private fun rememberMiniFabCoverRotation(isPlaying: Boolean): Float {
    val playing by rememberUpdatedState(isPlaying)
    var angleDeg by remember { mutableFloatStateOf(0f) }
    var velocityDegPerSec by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (isActive) {
            // Parked (paused, no residual velocity, upright pose): suspend until
            // playback resumes. The FAB is an always-on overlay — a per-vsync
            // withFrameNanos loop while idle keeps the render pipeline awake.
            if (!playing && velocityDegPerSec == 0f && angleDeg == 0f) {
                snapshotFlow { playing }.first { it }
            }
            var lastFrameNs = withFrameNanos { it }
            frames@ while (isActive) {
                val frameNs = withFrameNanos { it }
                val dtSec = ((frameNs - lastFrameNs).coerceIn(0L, 50_000_000L)) / 1_000_000_000f
                lastFrameNs = frameNs
                if (dtSec <= 0f) continue

                if (playing) {
                    val ramp = min(1f, dtSec / PLAY_SPEED_RAMP_SEC)
                    velocityDegPerSec += (PLAY_SPEED_DEG_PER_SEC - velocityDegPerSec) * ramp
                    angleDeg += velocityDegPerSec * dtSec
                } else {
                    when {
                        velocityDegPerSec > COAST_VELOCITY_FLOOR_DEG_PER_SEC -> {
                            val decel = min(
                                velocityDegPerSec,
                                COAST_DECEL_DEG_PER_SEC2 * dtSec,
                            )
                            velocityDegPerSec -= decel
                            angleDeg += velocityDegPerSec * dtSec
                        }
                        else -> {
                            velocityDegPerSec = 0f
                            val norm = angleDeg % 360f
                            val forwardToOrigin = if (norm < ORIGIN_EPSILON_DEG) {
                                0f
                            } else {
                                360f - norm
                            }
                            if (forwardToOrigin > ORIGIN_EPSILON_DEG) {
                                // Ease-in home leg: a bit faster toward the upright stop.
                                val settleT = (1f - forwardToOrigin / 360f).coerceIn(0f, 1f)
                                val creepSpeed = ORIGIN_CREEP_MIN_DEG_PER_SEC +
                                    (ORIGIN_CREEP_MAX_DEG_PER_SEC - ORIGIN_CREEP_MIN_DEG_PER_SEC) *
                                    settleT * settleT
                                val step = min(forwardToOrigin, creepSpeed * dtSec)
                                angleDeg += step
                            } else {
                                // Upright and stopped — normalize (rendered value is
                                // angleDeg % 360 either way) and leave the frame loop.
                                angleDeg = 0f
                                break@frames
                            }
                        }
                    }
                }
            }
        }
    }

    return angleDeg % 360f
}

private const val COVER_CROSSFADE_MS = 180
/** ~26s per revolution — slow enough to read as vinyl, cheap on GPU. */
private const val PLAY_SPEED_DEG_PER_SEC = 360f / 26f
private const val PLAY_SPEED_RAMP_SEC = 0.75f
private const val COAST_DECEL_DEG_PER_SEC2 = 125f
private const val COAST_VELOCITY_FLOOR_DEG_PER_SEC = 2.2f
private const val ORIGIN_CREEP_MIN_DEG_PER_SEC = 58f
private const val ORIGIN_CREEP_MAX_DEG_PER_SEC = 148f
private const val ORIGIN_EPSILON_DEG = 1.2f
