package com.example.ava.ui.screens.home

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.components.SystemStyleEdgeHandleSpec
import com.example.ava.ui.components.rememberSystemStyleEdgeHandleMetrics
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Overlay height for the bottom pull-handle. Grid content padding matches this so the
 * last row rests above the pill, same idea as settings lists vs the bottom handle.
 */
internal val MinimalLauncherDrawerHandleHeight = 52.dp

/** Viewport edge dissolve — about half an icon, not a fraction of the whole panel. */
internal val MinimalLauncherDrawerEdgeFade = 36.dp

/**
 * Offscreen + DstIn is dropped on software render (vinyl overlay taught us this).
 * Same cut as the lyric wall: Android 10+ keeps the mask; 9 and below paint a
 * panel-color scrim so the dissolve still draws on Android 8 and earlier.
 */
private val LegacyDrawerEdgeDissolve = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** Android 7.x and below — slightly longer scrim so the band is not a visible step. */
private val SoftLegacyDrawerEdgeDissolve = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

/** Same length/thickness ratios as the settings bottom-sheet pill. */
private const val DRAWER_HANDLE_LENGTH_SCALE = 0.80f
private const val DRAWER_HANDLE_THICKNESS_SCALE = 5f / 8f

internal fun LazyGridState.drawerShowTopFade(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 2

internal fun LazyGridState.drawerShowBottomFade(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    return last.index < info.totalItemsCount - 1 ||
        last.offset.y + last.size.height >
        info.viewportEndOffset - info.afterContentPadding - 2
}

internal fun LazyListState.drawerShowTopFade(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 2

internal fun LazyListState.drawerShowBottomFade(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    return last.index < info.totalItemsCount - 1 ||
        last.offset + last.size >
        info.viewportEndOffset - info.afterContentPadding - 2
}

/**
 * Top/bottom edge dissolve on a scrolling drawer surface.
 *
 * The mask sits on the viewport, so icons fade as they pass through the edge
 * (including during fling). Toggle [showTop]/[showBottom] from scroll position
 * so the first/last row is not washed out while it is fully in view.
 *
 * [fadeToColor] is the panel behind the grid — only used on the legacy scrim path.
 */
internal fun Modifier.drawerVerticalEdgeDissolve(
    showTop: Boolean,
    showBottom: Boolean,
    fadeToColor: Color,
    fadeHeight: Dp = MinimalLauncherDrawerEdgeFade,
    preferScrim: Boolean = false,
    /** When true the dissolve is deeper (1.5×) and always uses DstIn so the
     *  translucent glass panel truly shows through the edge gradient. */
    glassMode: Boolean = false,
): Modifier {
    if (!showTop && !showBottom) return this
    val effectiveFadeHeight = if (glassMode) fadeHeight * 1.5f else fadeHeight
    // Glass mode always uses DstIn so the translucent panel is visible through the edge.
    val useScrim = !glassMode && (LegacyDrawerEdgeDissolve || preferScrim)
    val dissolve = if (useScrim) {
        Modifier.drawWithContent {
            drawContent()
            drawLegacyDrawerEdgeScrim(
                showTop = showTop,
                showBottom = showBottom,
                fadeHeightPx = effectiveFadeHeight.toPx(),
                fadeToColor = fadeToColor,
            )
        }
    } else {
        Modifier
            .graphicsLayer(
                compositingStrategy = CompositingStrategy.Offscreen,
                // alpha < 1 forces an RGBA offscreen buffer. An opaque FBO
                // clears to the light-theme window colour (white) and shows
                // up as a sheet behind scrolling content.
                alpha = if (glassMode) 0.99f else 1f,
            )
            .drawWithContent {
                drawContent()
                drawDrawerDstInEdgeMask(
                    showTop = showTop,
                    showBottom = showBottom,
                    fadeHeightPx = effectiveFadeHeight.toPx(),
                )
            }
    }
    // Glass already clips to the rounded slab. clipToBounds() is another
    // graphicsLayer FBO and was the light-mode white sheet on this path.
    return if (glassMode) dissolve else clipToBounds().then(dissolve)
}

/**
 * Pull-handle overlay — pill + transparent→panel fade, same language as the
 * settings bottom handle, without a second nav-bar inset (the panel is already padded).
 */
@Composable
fun MinimalLauncherDrawerCloseBar(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val edgeMetrics = rememberSystemStyleEdgeHandleMetrics()
    val pillWidth = edgeMetrics.pivotHeight * DRAWER_HANDLE_LENGTH_SCALE
    val pillHeight = edgeMetrics.strokeWidth * DRAWER_HANDLE_THICKNESS_SCALE
    val handleColor = if (isDarkMode) {
        Color.White.copy(alpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
    } else {
        Color.Black.copy(alpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
    }
    val panelColor = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val closeLabel = stringResource(R.string.minimal_launcher_drawer_close)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MinimalLauncherDrawerHandleHeight)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, panelColor),
                ),
            )
            .semantics { contentDescription = closeLabel }
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(pillWidth)
                .height(pillHeight)
                .clip(RoundedCornerShape(pillHeight / 2))
                .background(handleColor),
        )
    }
}

/**
 * Matches the panel's 20.dp corner plus the grid's top inset so A and Z sit
 * inside the rounded rect instead of being pinched off by the arc.
 */
internal val FastScrollerEdgeInset = 22.dp

/**
 * Emil Kowalski UI curves — strong custom easings, not Material's weaker
 * built-ins. Enter/exit use the drawer curve; press and on-screen hops use
 * ease-out so motion starts in the frame the user is watching.
 */
internal val AvaEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
internal val AvaEaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** Niagara `scrollbar_animation_time_press`. */
private const val FAST_SCROLLER_PRESS_MS = 150

/**
 * Extra hit width toward the grid. Idle letters stay in the 22.dp lane;
 * Niagara also accepts a wider press than the painted column
 * (`scrollbar_gesture_deadzone_x` is 36.dp).
 */
internal val FastScrollerTouchExtra = 16.dp

/** Landscape thumbs come from the short side — a little more slop than portrait. */
internal val FastScrollerTouchExtraLandscape = 24.dp

/** Niagara `scrollbar_pressed_offset` — rest pull when the finger stays on the strip. */
private val FastScrollerPressedOffset = 64.dp

/** Extra rope when the finger yanks the strip inward. */
private val FastScrollerPressedOffsetMax = 96.dp

/** Floor on gaussian width so nearby letters bend with the finger, like a rope. */
internal const val FAST_SCROLLER_ROPE_MIN_NORM = 0.28f

/** Niagara `scrollbar_handle_diameter`. */
private val FastScrollerHandleDiameter = 44.sp

/** Niagara `scrollbar_handle_margin`. */
private val FastScrollerHandleMargin = 12.sp

/** Niagara `scrollbar_handle_text_size`. */
private val FastScrollerHandleText = 28.sp

/**
 * Portrait slot floor — letters sit close (about 14sp in a 16sp cell) instead of
 * stretching one 10sp glyph across a quarter of the screen.
 */
internal val FastScrollerCompactSlot = 16.sp

/** Portrait slot ceiling. Taller phones grow toward this so the column lengthens a little. */
internal val FastScrollerRoomySlot = 20.sp

/** How much of each slot the glyph fills. Tight enough that the run reads as one bar. */
internal const val FAST_SCROLLER_LETTER_FILL = 0.90f

/**
 * A–Z strip down the trailing edge of the drawer.
 *
 * Niagara draws letters in equal-height slots on a Canvas, not a Column of Text.
 * Pressing fans nearby letters inward and shows a circular handle that follows
 * the finger; Y maps evenly across every slot. Empty letters stay gray and
 * do not jump. Portrait packs a compact column that lengthens on taller
 * screens; landscape fills the inset track so A/Z are not clipped.
 */
@Composable
fun MinimalLauncherFastScroller(
    sections: List<MinimalLauncherAppSection>,
    isDarkMode: Boolean,
    width: Dp,
    scrollSectionIndex: Int = -1,
    modifier: Modifier = Modifier,
    onJump: (itemIndex: Int) -> Unit,
) {
    if (sections.isEmpty()) {
        Box(modifier = modifier.fillMaxHeight().width(width))
        return
    }
    val haptic = LocalHapticFeedback.current
    val isLandscape = rememberPaneIsLandscape()
    val touchExtra = if (isLandscape) FastScrollerTouchExtraLandscape else FastScrollerTouchExtra
    val layoutDirection = LocalLayoutDirection.current
    val towardStart = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val activeColor = if (isDarkMode) Color(0xFF9FA4AC) else Color(0xFF7D838D)
    val idleColor = if (isDarkMode) Color(0xFF4F565F) else Color(0xFFC7CBD1)
    val scrollHighlightColor = if (isDarkMode) Color(0xFFE6E7EA) else Color(0xFF222A37)
    val handleTextColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFF3C3F45)
    val sectionsState = rememberUpdatedState(sections)
    val onJumpState = rememberUpdatedState(onJump)
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    // Smooth glide for the scroll-position highlight (EMA equivalent).
    val highlightFloat by animateFloatAsState(
        targetValue = scrollSectionIndex.toFloat(),
        animationSpec = tween(durationMillis = 120, easing = AvaEaseOut),
        label = "scrollHighlight",
    )
    var fingerY by remember { mutableFloatStateOf(0f) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableIntStateOf(-1) }
    val panelWidthState = rememberUpdatedState(panelWidthPx)

    // Niagara-style 64ms trailing debounce for section jumps.
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var jumpCount by remember { mutableIntStateOf(0) }
    val letterPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val handlePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    fun applyFinger(x: Float, y: Float, trackTop: Float, trackHeight: Float) {
        fingerX = x
        fingerY = y
        val list = sectionsState.value
        val index = niagaraLetterIndex(y, trackTop, trackHeight, list.size)
        if (index < 0 || index == active) return
        active = index
        val section = list[index]
        if (!section.enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // Leading dispatch: first section change fires immediately.
        // Subsequent rapid changes are debounced (trailing 64ms).
        jumpJob?.cancel()
        jumpCount++
        if (jumpCount <= 1) {
            onJumpState.value(section.firstIndex)
        } else {
            val target = section.firstIndex
            jumpJob = scope.launch {
                delay(64L)
                onJumpState.value(target)
            }
        }
    }

    fun setPressed(on: Boolean) {
        if (pressed == on) return
        pressed = on
        if (!on) {
            active = -1
            jumpCount = 0
            jumpJob?.cancel()
            jumpJob = null
        }
        scope.launch {
            press.animateTo(
                targetValue = if (on) 1f else 0f,
                animationSpec = tween(FAST_SCROLLER_PRESS_MS, easing = AvaEaseOut),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { panelWidthPx = it.width.toFloat() },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val count = sections.size
            val stripWidthPx = width.toPx()
            val track = fastScrollerTrackMetrics(
                viewportHeight = size.height,
                count = count,
                insetPx = FastScrollerEdgeInset.toPx(),
                compactSlotPx = FastScrollerCompactSlot.toPx(),
                roomySlotPx = FastScrollerRoomySlot.toPx(),
                stretchToFill = isLandscape,
            )
            val trackTop = track.top
            val trackHeight = track.height
            val slotHeight = track.slot
            val letterPx = fastScrollerLetterPx(slotHeight)
            val stripCenterX = if (layoutDirection == LayoutDirection.Rtl) {
                stripWidthPx / 2f
            } else {
                size.width - stripWidthPx / 2f
            }
            val inward = ((stripCenterX - fingerX) * towardStart).coerceAtLeast(0f)
            val fanAmount = niagaraRopePull(
                basePx = FastScrollerPressedOffset.toPx(),
                maxPx = FastScrollerPressedOffsetMax.toPx(),
                inwardPx = inward,
            ) * press.value
            val fanNorm = niagaraRopeFanNorm(fanAmount, trackHeight)
            letterPaint.textSize = letterPx
            val fontHeight = letterPaint.descent() - letterPaint.ascent()
            val slotPad = (slotHeight - fontHeight) / 2f

            // Niagara "strip follows handle": when the finger goes past the
            // first/last letter, stretch the strip like a rubber band —
            // letters near the finger move more, the far end stays anchored.
            val trackShift = if (pressed && active >= 0 && count > 0) {
                val activeCenterY = trackTop + (active + 0.5f) * slotHeight
                val handleDia = FastScrollerHandleDiameter.toPx()
                val handleRad = handleDia / 2f
                val handleTarget = niagaraHandleCenterY(fingerY, activeCenterY, slotHeight)
                    .coerceIn(handleRad, size.height - handleRad)
                (handleTarget - activeCenterY)
            } else 0f

            sections.forEachIndexed { i, section ->
                val letterProgress = (i + 1f) / count
                val fingerProgress =
                    ((slotHeight / 2f) + (fingerY - trackTop)) / trackHeight
                val fan = niagaraFanWeight(letterProgress - fingerProgress, fanNorm)
                val x = stripCenterX - towardStart * fan * fanAmount
                // Rubber-band stretch: anchor the far end, pull the near end.
                val stretchFrac = if (count <= 1) 1f else when {
                    trackShift < 0f -> (count - 1 - i).toFloat() / (count - 1)
                    trackShift > 0f -> i.toFloat() / (count - 1)
                    else -> 0f
                }
                val letterShiftY = trackShift * stretchFrac
                val baseline = trackTop + letterShiftY + i * slotHeight + slotPad - letterPaint.ascent()
                letterPaint.color = when {
                    !pressed && section.enabled && highlightFloat >= 0f -> {
                        val proximity = 1f - abs(i - highlightFloat).coerceAtMost(1f)
                        if (proximity > 0f) {
                            lerpColorArgb(activeColor.toArgb(), scrollHighlightColor.toArgb(), proximity)
                        } else {
                            activeColor.toArgb()
                        }
                    }
                    section.enabled -> activeColor.toArgb()
                    else -> idleColor.toArgb()
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(section.label, x, baseline, letterPaint)
                }
            }

            if (pressed && active in sections.indices) {
                val handleDiameter = FastScrollerHandleDiameter.toPx()
                val handleRadius = handleDiameter / 2f
                val handleMargin = FastScrollerHandleMargin.toPx()
                val letterCenterY = trackTop + (active + 0.5f) * slotHeight
                val handleCy = niagaraHandleCenterY(fingerY, letterCenterY, slotHeight)
                    .coerceIn(handleRadius, size.height - handleRadius)
                // Follow the rope — always on the icon side of the fanned letter.
                val handleCx = stripCenterX -
                    towardStart * (fanAmount + handleMargin + handleRadius)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.16f),
                    radius = handleRadius,
                    center = Offset(handleCx, handleCy + 1.5f),
                )
                drawCircle(
                    color = Color.White,
                    radius = handleRadius,
                    center = Offset(handleCx, handleCy),
                )
                val label = sections[active].label
                handlePaint.textSize = FastScrollerHandleText.toPx()
                handlePaint.color = handleTextColor.toArgb()
                val handleBaseline =
                    handleCy - (handlePaint.ascent() + handlePaint.descent()) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(label, handleCx, handleBaseline, handlePaint)
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(width + touchExtra)
                .fillMaxHeight()
                .pointerInput(sections.size, isLandscape, touchExtra) {
                    val inset = FastScrollerEdgeInset.toPx()
                    val compact = FastScrollerCompactSlot.toPx()
                    val roomy = FastScrollerRoomySlot.toPx()
                    fun toPanelX(localX: Float): Float {
                        val panelW = panelWidthState.value
                        return if (layoutDirection == LayoutDirection.Rtl) {
                            localX
                        } else {
                            (panelW - size.width) + localX
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val track = fastScrollerTrackMetrics(
                            viewportHeight = size.height.toFloat(),
                            count = sectionsState.value.size,
                            insetPx = inset,
                            compactSlotPx = compact,
                            roomySlotPx = roomy,
                            stretchToFill = isLandscape,
                        )
                        setPressed(true)
                        applyFinger(
                            toPanelX(down.position.x),
                            down.position.y,
                            track.top,
                            track.height,
                        )
                        drag(down.id) { change ->
                            applyFinger(
                                toPanelX(change.position.x),
                                change.position.y,
                                track.top,
                                track.height,
                            )
                            change.consume()
                        }
                        setPressed(false)
                    }
                },
        )
    }
}

/** Niagara: `index = (y - top) / (height / N)`, clamped to the first/last slot. */
internal fun niagaraLetterIndex(
    y: Float,
    trackTop: Float,
    trackHeight: Float,
    count: Int,
): Int {
    if (count <= 0 || trackHeight <= 0f) return -1
    return when {
        y < trackTop -> 0
        y >= trackTop + trackHeight -> count - 1
        else -> ((y - trackTop) / (trackHeight / count)).toInt().coerceIn(0, count - 1)
    }
}

/** ARGB channel lerp for smooth color transitions in the strip highlight. */
private fun lerpColorArgb(from: Int, to: Int, fraction: Float): Int {
    val a = ((from shr 24) and 0xFF) + (fraction * (((to shr 24) and 0xFF) - ((from shr 24) and 0xFF))).toInt()
    val r = ((from shr 16) and 0xFF) + (fraction * (((to shr 16) and 0xFF) - ((from shr 16) and 0xFF))).toInt()
    val g = ((from shr 8) and 0xFF) + (fraction * (((to shr 8) and 0xFF) - ((from shr 8) and 0xFF))).toInt()
    val b = (from and 0xFF) + (fraction * ((to and 0xFF) - (from and 0xFF))).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Niagara handle Y: cubic ease while the finger is inside the active slot,
 * otherwise the raw finger Y.
 */
internal fun niagaraHandleCenterY(
    fingerY: Float,
    letterCenterY: Float,
    slotHeight: Float,
): Float {
    if (slotHeight <= 0f) return fingerY
    val t = (fingerY - letterCenterY) / slotHeight
    return if (t in -0.5f..0.5f) {
        letterCenterY + (t * 2f).pow(3) / 2f * slotHeight
    } else {
        fingerY
    }
}

/** Niagara `pow(2, (1 / fanNorm) * -4 * delta²)`. */
internal fun niagaraFanWeight(delta: Float, fanNorm: Float): Float {
    if (fanNorm <= 0f) return 0f
    return 2.0.pow((1.0 / fanNorm) * -4.0 * delta * delta).toFloat()
}

/** Niagara sets pull from finger X, then clamps to the pressed-offset window. */
internal fun niagaraRopePull(basePx: Float, maxPx: Float, inwardPx: Float): Float {
    if (basePx <= 0f) return 0f
    return (basePx + inwardPx.coerceAtLeast(0f)).coerceAtMost(maxPx.coerceAtLeast(basePx))
}

/**
 * Wider than `fanAmount / track` alone so the bulge is a rope, not a single
 * letter jumping inward.
 */
internal fun niagaraRopeFanNorm(
    fanAmount: Float,
    trackHeight: Float,
    minNorm: Float = FAST_SCROLLER_ROPE_MIN_NORM,
): Float {
    if (fanAmount <= 0f || trackHeight <= 0f) return 0f
    return (fanAmount / trackHeight).coerceAtLeast(minNorm)
}

internal data class FastScrollerTrackMetrics(
    val top: Float,
    val height: Float,
    val slot: Float,
)

/**
 * Portrait packs toward [compactSlotPx] and grows to [roomySlotPx] when the
 * panel is tall, so A–Z lengthens a little on larger phones instead of
 * leaving a 10sp letter in a huge empty cell. Landscape always fills the
 * inset track so every letter stays on screen.
 */
internal fun fastScrollerTrackMetrics(
    viewportHeight: Float,
    count: Int,
    insetPx: Float,
    compactSlotPx: Float,
    roomySlotPx: Float,
    stretchToFill: Boolean,
): FastScrollerTrackMetrics {
    val available = (viewportHeight - insetPx * 2f).coerceAtLeast(1f)
    val slot = fastScrollerSlotHeight(
        available = available,
        count = count,
        compactSlotPx = compactSlotPx,
        roomySlotPx = roomySlotPx,
        stretchToFill = stretchToFill,
    )
    val height = (slot * count.coerceAtLeast(0)).coerceAtLeast(1f)
    val top = insetPx + (available - height).coerceAtLeast(0f) / 2f
    return FastScrollerTrackMetrics(top = top, height = height, slot = slot)
}

internal fun fastScrollerSlotHeight(
    available: Float,
    count: Int,
    compactSlotPx: Float,
    roomySlotPx: Float,
    stretchToFill: Boolean,
): Float {
    if (count <= 0) return compactSlotPx.coerceAtLeast(1f)
    val fill = available / count
    if (stretchToFill || fill <= compactSlotPx) return fill
    val grow = ((available - compactSlotPx * count) / count).coerceAtLeast(0f)
    return (compactSlotPx + grow).coerceAtMost(roomySlotPx).coerceAtMost(fill)
}

/** Glyph fills most of the slot so portrait reads tight; landscape still fits. */
internal fun fastScrollerLetterPx(
    slotHeight: Float,
    fillFraction: Float = FAST_SCROLLER_LETTER_FILL,
): Float {
    if (slotHeight <= 0f) return 1f
    return (slotHeight * fillFraction).coerceAtLeast(1f)
}

/** Same ramp as settings [drawDescriptionEdgeMask] — multiplies content alpha. */
private fun DrawScope.drawDrawerDstInEdgeMask(
    showTop: Boolean,
    showBottom: Boolean,
    fadeHeightPx: Float,
) {
    if (!showTop && !showBottom) return
    val h = size.height
    val maxFadePx = h * 0.45f
    if (!maxFadePx.isFinite() || maxFadePx < 1f) return
    val fadePx = fadeHeightPx.coerceIn(1f, maxFadePx)
    val topEnd = if (showTop) (fadePx / h).coerceIn(0.02f, 0.45f) else 0f
    val bottomStart = if (showBottom) (1f - fadePx / h).coerceIn(0.55f, 0.98f) else 1f
    val stops = buildList {
        if (showTop) {
            add(0f to Color.Transparent)
            add((topEnd * 0.45f) to Color.White.copy(alpha = 0.35f))
            add(topEnd to Color.White)
        } else {
            add(0f to Color.White)
        }
        if (showBottom) {
            add(bottomStart to Color.White)
            add((bottomStart + (1f - bottomStart) * 0.55f) to Color.White.copy(alpha = 0.35f))
            add(1f to Color.Transparent)
        } else {
            add(1f to Color.White)
        }
    }
    drawRect(
        brush = Brush.verticalGradient(colorStops = stops.toTypedArray()),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * Software stand-in: paint the panel color over the edges. DstIn would vanish;
 * a matching scrim still hides the hard clip. On Android 8 below the band is a
 * little longer so it does not read as a step.
 */
private fun DrawScope.drawLegacyDrawerEdgeScrim(
    showTop: Boolean,
    showBottom: Boolean,
    fadeHeightPx: Float,
    fadeToColor: Color,
) {
    if (!showTop && !showBottom) return
    val h = size.height
    val maxFadePx = h * 0.45f
    if (!maxFadePx.isFinite() || maxFadePx < 1f) return
    val spanMul = if (SoftLegacyDrawerEdgeDissolve) 1.2f else 1f
    val fadePx = (fadeHeightPx * spanMul).coerceIn(1f, maxFadePx)
    if (showTop) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to fadeToColor,
                    0.45f to fadeToColor.copy(alpha = 0.35f),
                    1f to Color.Transparent,
                ),
                startY = 0f,
                endY = fadePx,
            ),
        )
    }
    if (showBottom) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.55f to fadeToColor.copy(alpha = 0.35f),
                    1f to fadeToColor,
                ),
                startY = h - fadePx,
                endY = h,
            ),
        )
    }
}
