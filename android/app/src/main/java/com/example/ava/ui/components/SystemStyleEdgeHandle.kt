package com.example.ava.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pixel-faithful port of MiSystemUI [GlobalGestureAnimationView] + drag wiring from
 * [GlobalGestureControlImpl.setDistance] / [animateReset]:
 *
 * 1. Idle present: vertical round stroke, alpha 0→0.5 (300ms), auto-hide 3s
 * 2. Drag 0…thresholdDone: height 106→60dp, still a straight bar
 * 3. Drag thresholdDone…arrowEnd: height stays 60dp, mid control bows inward → chevron/arrow
 * 4. Release: 200ms loose anim — height→max, bow retracts, alpha→0
 */
@Composable
fun SystemStyleEdgeHandle(
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    /** False when the drawer is fully open (handle not needed). */
    canShow: Boolean = true,
    /** True while the finger is pulling the edge — forces present alpha + live morph. */
    isDragging: Boolean = false,
    /**
     * Directed finger travel from the edge in **px** (SystemUI setDistance).
     * Left open → positive to the right; right open → positive to the left.
     */
    dragDistancePx: Float = 0f,
    /**
     * Drawer open progress 0…1. Handle draw alpha is multiplied by (1 − this)
     * so the arrow fades out as the sidebar appears, gone when fully open.
     */
    drawerOpenFraction: Float = 0f,
    /**
     * True while the drawer is settling/committed open but not yet fully open.
     * Skips the release loose-fade so [drawerOpenFraction] owns the hide.
     */
    fadeWithDrawerOpen: Boolean = false,
    revealTick: Long = 0L,
    onReveal: () -> Unit = {},
    metrics: SystemStyleEdgeHandleMetrics = rememberSystemStyleEdgeHandleMetrics(),
    /**
     * When false, this composable is draw-only — parent owns tap/drag (home drawer
     * fixed-handle layer). Browser View port always owns its own touches.
     */
    gesturesEnabled: Boolean = true,
    /**
     * Touch-box height. Null keeps the full-height edge strip. Home/settings pass
     * slider height + a small pad so taps away from the visible bar are ignored.
     */
    hitHeight: Dp? = null,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { metrics.strokeWidth.toPx() }
    val maxHeightPx = with(density) { metrics.pivotHeight.toPx() }
    val minHeightPx = with(density) { metrics.pivotMinHeight.toPx() }
    val edgeOffsetPx = with(density) { metrics.edgeOffset.toPx() }
    val thresholdDonePx = with(density) { metrics.thresholdDone.toPx() }
    val thresholdArrowEndPx = with(density) { metrics.thresholdArrowEnd.toPx() }

    val pivotColor = if (isDarkMode) Color.White else Color.Black
    val alpha = remember { Animatable(0f) }
    val pivotHeight = remember { Animatable(maxHeightPx) }
    val pivotControlX = remember { Animatable(edgeOffsetPx) }

    // Keep metrics-driven resting size in sync if scale changes.
    LaunchedEffect(maxHeightPx, edgeOffsetPx) {
        if (!isDragging && dragDistancePx <= 0f) {
            pivotHeight.snapTo(maxHeightPx)
            pivotControlX.snapTo(edgeOffsetPx)
        }
    }

    val onRevealState = rememberUpdatedState(onReveal)

    // —— Live drag morph (GlobalGestureAnimationView.updateDistance) ——
    LaunchedEffect(dragDistancePx, isDragging, maxHeightPx, minHeightPx, edgeOffsetPx) {
        if (!canShow) return@LaunchedEffect
        if (isDragging || dragDistancePx > 0f) {
            alpha.snapTo(PRESENT_ALPHA)
            val (h, cx) = SystemStyleEdgeHandleSpec.morphPivot(
                distancePx = dragDistancePx.coerceAtLeast(0f),
                maxHeightPx = maxHeightPx,
                minHeightPx = minHeightPx,
                edgeOffsetPx = edgeOffsetPx,
                thresholdDonePx = thresholdDonePx,
                thresholdArrowEndPx = thresholdArrowEndPx,
            )
            pivotHeight.snapTo(h)
            pivotControlX.snapTo(cx)
        }
    }

    // —— Release: animateHideInternal / animateReset ——
    // When the drawer is already opening, keep base alpha at PRESENT and let
    // drawerOpenFraction drive the fade to 0 (no hard 200ms snap-hide).
    var wasDragging by remember { mutableStateOf(false) }
    val openFade = SystemStyleEdgeHandleSpec.openFade(drawerOpenFraction)
    LaunchedEffect(isDragging, canShow, fadeWithDrawerOpen) {
        if (isDragging) {
            wasDragging = true
            return@LaunchedEffect
        }
        if (!wasDragging) return@LaunchedEffect
        wasDragging = false
        if (!canShow) {
            alpha.snapTo(0f)
            pivotHeight.snapTo(maxHeightPx)
            pivotControlX.snapTo(edgeOffsetPx)
            return@LaunchedEffect
        }
        if (fadeWithDrawerOpen || drawerOpenFraction > OPENING_FADE_HANDOFF) {
            // Opening: freeze morph, keep PRESENT alpha — openFade hides until fully open.
            alpha.snapTo(PRESENT_ALPHA)
            return@LaunchedEffect
        }
        // Closing / cancelled open: retract morph to the idle bar, keep PRESENT.
        // Parent reveals again when fully closed → show for AUTO_HIDE_MS then hide.
        coroutineScope {
            launch {
                pivotHeight.animateTo(
                    targetValue = maxHeightPx,
                    animationSpec = tween(durationMillis = HIDE_MS, easing = FastOutSlowInEasing),
                )
            }
            launch {
                pivotControlX.animateTo(
                    targetValue = edgeOffsetPx,
                    animationSpec = tween(durationMillis = HIDE_MS, easing = FastOutSlowInEasing),
                )
            }
        }
        pivotHeight.snapTo(maxHeightPx)
        pivotControlX.snapTo(edgeOffsetPx)
        alpha.snapTo(PRESENT_ALPHA)
    }

    // —— Idle present / auto-hide (showIndicator). ——
    // Only bump revealTick to present — do not auto-present when canShow flips true
    // mid-close (that would flash the bar before the drawer finishes retracting).
    // isDragging is a key: finger-down cancels this job (incl. the hide fade). On
    // release, re-arm AUTO_HIDE→fade even when revealTick is unchanged — otherwise
    // closing the drawer leaves the bar stuck at PRESENT with no fade-out.
    var lastPresentedReveal by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(canShow, revealTick, isDragging) {
        if (!canShow) {
            if (!isDragging) alpha.snapTo(0f)
            return@LaunchedEffect
        }
        if (isDragging) return@LaunchedEffect
        if (revealTick != lastPresentedReveal) {
            lastPresentedReveal = revealTick
            pivotControlX.snapTo(edgeOffsetPx)
            pivotHeight.snapTo(maxHeightPx)
            alpha.animateTo(
                targetValue = PRESENT_ALPHA,
                animationSpec = tween(durationMillis = PRESENT_MS, easing = FastOutSlowInEasing),
            )
        } else if (alpha.value <= 0.01f) {
            // Already tucked — stay dark until a new revealTick.
            return@LaunchedEffect
        }
        delay(AUTO_HIDE_MS)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = HIDE_MS, easing = FastOutSlowInEasing),
        )
    }

    val interactionSource = remember { MutableInteractionSource() }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    // Read animatable values each frame so drawBehind invalidates.
    // openFade ties visibility to sidebar progress (arrow gone when fully open).
    val drawAlpha = alpha.value * openFade
    val drawHeight = pivotHeight.value
    val drawControlX = pivotControlX.value

    Box(
        modifier = modifier
            .width(metrics.hitWidth)
            .then(
                if (hitHeight != null) Modifier.height(hitHeight) else Modifier.fillMaxHeight()
            )
            .then(
                if (gesturesEnabled) {
                    Modifier
                        .pointerInput(canShow) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (canShow && event.changes.any { it.pressed && !it.previousPressed }) {
                                        onRevealState.value()
                                    }
                                }
                            }
                        }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = canShow && !isDragging,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .drawBehind {
                if (drawAlpha <= 0.01f) return@drawBehind

                val cy = size.height / 2f
                val halfH = drawHeight / 2f
                val halfW = strokeWidthPx / 2f
                // SystemUI onDraw: base = offset + w/2, control = controlX + w/2
                val baseX = if (mirrored) {
                    size.width - edgeOffsetPx - halfW
                } else {
                    edgeOffsetPx + halfW
                }
                val bow = drawControlX - edgeOffsetPx
                val controlX = if (mirrored) baseX - bow else baseX + bow

                drawPath(
                    path = Path().apply {
                        moveTo(baseX, cy - halfH)
                        lineTo(controlX, cy)
                        lineTo(baseX, cy + halfH)
                    },
                    color = pivotColor.copy(alpha = drawAlpha),
                    style = strokeStyle,
                )
            }
    )
}

data class SystemStyleEdgeHandleMetrics(
    val scale: Float,
    val strokeWidth: Dp,
    val pivotHeight: Dp,
    val pivotMinHeight: Dp,
    val edgeOffset: Dp,
    val hitWidth: Dp,
    val thresholdDone: Dp,
    val thresholdArrowEnd: Dp,
)

/** Compose wrapper around [SystemStyleEdgeHandleSpec] — same numbers as the View port. */
object SystemStyleEdgeHandleSizing {
    fun scaleForShortestSide(shortestSideDp: Int): Float =
        SystemStyleEdgeHandleSpec.scaleForShortestSide(shortestSideDp)

    fun metrics(shortestSideDp: Int): SystemStyleEdgeHandleMetrics {
        val scale = scaleForShortestSide(shortestSideDp)
        fun s(ref: Float): Dp = (ref * scale).dp
        return SystemStyleEdgeHandleMetrics(
            scale = scale,
            strokeWidth = s(SystemStyleEdgeHandleSpec.REF_STROKE_DP),
            pivotHeight = s(SystemStyleEdgeHandleSpec.REF_HEIGHT_DP),
            pivotMinHeight = s(SystemStyleEdgeHandleSpec.REF_MIN_HEIGHT_DP),
            edgeOffset = s(SystemStyleEdgeHandleSpec.REF_OFFSET_DP),
            hitWidth = (SystemStyleEdgeHandleSpec.REF_HIT_DP * scale).coerceAtLeast(26f).dp,
            thresholdDone = s(SystemStyleEdgeHandleSpec.REF_THRESHOLD_DONE_DP),
            thresholdArrowEnd = s(SystemStyleEdgeHandleSpec.REF_THRESHOLD_ARROW_END_DP),
        )
    }
}

@Composable
fun rememberSystemStyleEdgeHandleMetrics(): SystemStyleEdgeHandleMetrics {
    val configuration = LocalConfiguration.current
    val shortest = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    return remember(shortest) { SystemStyleEdgeHandleSizing.metrics(shortest) }
}

private const val PRESENT_ALPHA = SystemStyleEdgeHandleSpec.PRESENT_ALPHA
private val PRESENT_MS = SystemStyleEdgeHandleSpec.PRESENT_MS.toInt()
private val HIDE_MS = SystemStyleEdgeHandleSpec.HIDE_MS.toInt()
private val AUTO_HIDE_MS = SystemStyleEdgeHandleSpec.AUTO_HIDE_MS
/** Above this open fraction, release hands fade-out to drawer progress (not loose anim). */
private const val OPENING_FADE_HANDOFF = 0.08f
