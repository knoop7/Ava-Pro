package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor

/** Default clipped height ≈ two lines of body hint. */
const val CollapsibleDescriptionDefaultCollapsedLines = 2

/** Full help / usage copy: same tight leading as [CollapsibleDescriptionText], no 2-line clamp. */
@Composable
fun SettingsHelpBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    if (text.isBlank()) return
    val resolved = if (color == Color.Unspecified) getSettingsDescriptionColor() else color
    Text(
        text = text,
        modifier = modifier,
        style = settingsDescriptionTextStyle(
            fontSize = settingsBodyTextSize(),
            lineHeight = settingsBodyLineHeight(),
            color = resolved,
            fontWeight = fontWeight,
        ),
    )
}

/** Drop Android font padding + extra line leading so wrapped hints sit tight. */
fun settingsDescriptionTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    textAlign: TextAlign = TextAlign.Start,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = settingsBodyLetterSpacing(),
) = TextStyle(
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    color = color,
    textAlign = textAlign,
    fontFamily = fontFamily,
    fontWeight = fontWeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * Description text clipped to [collapsedLines] with soft edge fades.
 *
 * Scroll is opt-in:
 * - No [verticalScroll] until content actually overflows (parent list keeps the gesture).
 * - While this region can still move, it scrolls first. At the bound, leftover drag
 *   continues onto the parent so a swipe that started here does not trap the page.
 *   Leftover fling stays here so a flick does not throw the parent list.
 */
@Composable
fun CollapsibleDescriptionText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = CollapsibleDescriptionDefaultCollapsedLines,
    /** When true, always reserve [collapsedLines] of height so sibling cards stay the same size. */
    pinViewport: Boolean = false,
    fontSize: TextUnit = 11.sp,
    lineHeight: TextUnit = 15.5.sp,
    color: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") fadeToColor: Color = getDialogBackground(),
    topPadding: Dp = settingsDescriptionTopPadding(),
    textAlign: TextAlign = TextAlign.Start,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    onClick: (() -> Unit)? = null,
) {
    if (text.isBlank()) return
    val themeDescriptionColor = getSettingsDescriptionColor()
    val resolvedColor = if (color == Color.Unspecified) themeDescriptionColor else color

    val density = LocalDensity.current
    val lines = collapsedLines.coerceAtLeast(1)
    val viewportHeight = remember(lineHeight, lines, density) {
        with(density) {
            (lineHeight.value * lines).sp.toDp()
        }
    }
    val fadeHeight = with(density) { (lineHeight.value * 0.55f).sp.toDp() }

    var hasOverflow by remember(text, lines, fontSize, lineHeight, fontFamily, fontWeight) {
        mutableStateOf(false)
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(0)
    }

    // Nested connection only when this region can actually scroll — never while
    // maxValue==0 (would steal leftover fling from the parent list).
    val canScroll = hasOverflow && scrollState.maxValue > 0
    val showTopFade = canScroll && scrollState.value > 2
    val showBottomFade = canScroll && scrollState.value < scrollState.maxValue - 2

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .then(
                when {
                    pinViewport -> Modifier.height(viewportHeight)
                    hasOverflow -> Modifier.heightIn(max = viewportHeight)
                    else -> Modifier
                },
            )
            .clipToBounds()
            .then(if (canScroll) Modifier.nestedScroll(BoundHandoffNestedScroll) else Modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawDescriptionEdgeMask(
                    showTopFade = showTopFade,
                    showBottomFade = showBottomFade,
                    fadeHeightPx = fadeHeight.toPx(),
                )
            },
    ) {
        Text(
            text = text,
            style = settingsDescriptionTextStyle(
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = resolvedColor,
                textAlign = textAlign,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
            ),
            maxLines = if (hasOverflow) Int.MAX_VALUE else lines,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(if (hasOverflow) Modifier.verticalScroll(scrollState) else Modifier),
            onTextLayout = { layout ->
                // Latch only: toggling heightIn/verticalScroll changes measure and can
                // flip hasVisualOverflow ↔ lineCount at the 2-line boundary (landscape flicker).
                if (!hasOverflow && layout.hasVisualOverflow) {
                    hasOverflow = true
                }
            },
        )
    }
}

/**
 * Single-line trailing label: if it cannot fit, scroll sideways with the same
 * DstIn edge dissolve as [CollapsibleDescriptionText] (right end first).
 */
@Composable
fun HorizontalDissolveText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.End,
) {
    if (text.isBlank()) return
    val themeDescriptionColor = getSettingsDescriptionColor()
    val resolvedColor = if (color == Color.Unspecified) themeDescriptionColor else color
    val density = LocalDensity.current
    val fadeWidth = with(density) { (fontSize.value * 0.85f).sp.toDp() }
    var hasOverflow by remember(text, fontSize, fontWeight) {
        mutableStateOf(false)
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(0)
    }
    val canScroll = hasOverflow && scrollState.maxValue > 0
    val showStartFade = canScroll && scrollState.value > 2
    val showEndFade = canScroll && scrollState.value < scrollState.maxValue - 2

    Box(
        modifier = modifier
            .clipToBounds()
            .then(if (canScroll) Modifier.nestedScroll(BoundHandoffNestedScroll) else Modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawHorizontalDescriptionEdgeMask(
                    showStartFade = showStartFade,
                    showEndFade = showEndFade,
                    fadeWidthPx = fadeWidth.toPx(),
                )
            },
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = resolvedColor,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.then(
                if (hasOverflow) Modifier.horizontalScroll(scrollState) else Modifier,
            ),
            onTextLayout = { layout ->
                if (!hasOverflow && layout.hasVisualOverflow) {
                    hasOverflow = true
                }
            },
        )
    }
}

/**
 * Full description in a bounded height (e.g. landscape left card): no 2-line cap.
 * Soft edge fade only when [edgeFadeEnabled] and content actually overflows.
 * Same nested-scroll handoff as [CollapsibleDescriptionText]: this region first, then the parent.
 */
@Composable
fun FillHeightDescriptionText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    lineHeight: TextUnit = 15.5.sp,
    color: Color = Color.Unspecified,
    topPadding: Dp = settingsDescriptionTopPadding(),
    textAlign: TextAlign = TextAlign.Start,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    /** When false, never draw edge fades (preferred for spacious landscape left card). */
    edgeFadeEnabled: Boolean = false,
) {
    if (text.isBlank()) return
    val themeDescriptionColor = getSettingsDescriptionColor()
    val resolvedColor = if (color == Color.Unspecified) themeDescriptionColor else color
    val density = LocalDensity.current
    val fadeHeight = with(density) { (lineHeight.value * 0.55f).sp.toDp() }
    var needsScroll by remember(text, fontSize, lineHeight, fontFamily, fontWeight) {
        mutableStateOf(false)
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(0)
    }
    val canScroll = needsScroll && scrollState.maxValue > 0
    val showTopFade = edgeFadeEnabled && canScroll && scrollState.value > 2
    val showBottomFade = edgeFadeEnabled && canScroll && scrollState.value < scrollState.maxValue - 2

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(top = topPadding)
            .clipToBounds()
            .then(if (canScroll) Modifier.nestedScroll(BoundHandoffNestedScroll) else Modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawDescriptionEdgeMask(
                    showTopFade = showTopFade,
                    showBottomFade = showBottomFade,
                    fadeHeightPx = fadeHeight.toPx(),
                )
            },
    ) {
        val viewportPx = constraints.maxHeight
        Text(
            text = text,
            style = settingsDescriptionTextStyle(
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = resolvedColor,
                textAlign = textAlign,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
            ),
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (needsScroll) Modifier.verticalScroll(scrollState) else Modifier),
            onTextLayout = { layout ->
                // Latch only — leftover viewport can jitter (title autosize / padding) in landscape.
                if (!needsScroll && viewportPx > 0 && layout.size.height > viewportPx + 1) {
                    needsScroll = true
                }
            },
        )
    }
}

/**
 * Bounded column with the same DstIn edge-dissolve as [CollapsibleDescriptionText].
 *
 * Use when a nested list should show at most [maxHeight] and scroll with soft fades
 * when content overflows. Leftover drag at the bound continues onto the parent.
 */
@Composable
fun SettingsEdgeFadeScrollColumn(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    scrollEnabled: Boolean = true,
    fadeHeight: Dp = 20.dp,
    fadeBottom: Boolean = true,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    handoffOverscrollToParent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsEdgeFadeScrollColumn(
        modifier = modifier.heightIn(max = maxHeight),
        scrollState = scrollState,
        scrollEnabled = scrollEnabled,
        fadeHeight = fadeHeight,
        fadeBottom = fadeBottom,
        verticalArrangement = verticalArrangement,
        flingBehavior = flingBehavior,
        handoffOverscrollToParent = handoffOverscrollToParent,
        content = content,
    )
}

/**
 * Same edge-dissolve scroller as [SettingsEdgeFadeScrollColumn], sized by [modifier]
 * (e.g. [Modifier.weight] / [Modifier.fillMaxHeight] in a fill column).
 */
@Composable
fun SettingsEdgeFadeScrollColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    scrollEnabled: Boolean = true,
    fadeHeight: Dp = 20.dp,
    fadeBottom: Boolean = true,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    /** False: leftover drag/fling stays here so a parent page does not jump. */
    handoffOverscrollToParent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(scrollEnabled) {
        if (!scrollEnabled) scrollState.scrollTo(0)
    }
    val canScroll = scrollEnabled && scrollState.maxValue > 0
    val showTopFade = canScroll && scrollState.value > 2
    val showBottomFade = fadeBottom && canScroll && scrollState.value < scrollState.maxValue - 2

    if (!scrollEnabled) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(
                if (canScroll) {
                    Modifier.nestedScroll(
                        if (handoffOverscrollToParent) {
                            BoundHandoffNestedScroll
                        } else {
                            BoundTrapNestedScroll
                        },
                    )
                } else {
                    Modifier
                },
            )
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawDescriptionEdgeMask(
                    showTopFade = showTopFade,
                    showBottomFade = showBottomFade,
                    fadeHeightPx = fadeHeight.toPx(),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(
                    state = scrollState,
                    flingBehavior = flingBehavior,
                ),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Sits between this scroller and any parent list/sheet.
 *
 * Drag leftover is not consumed — at the top/bottom the parent can take over so
 * the finger is not trapped (portrait and landscape). Fling leftover is consumed
 * so a flick here does not throw the parent page or bounce a sheet handle.
 */
private object BoundTrapNestedScroll : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

private object BoundHandoffNestedScroll : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

private fun DrawScope.drawHorizontalDescriptionEdgeMask(
    showStartFade: Boolean,
    showEndFade: Boolean,
    fadeWidthPx: Float,
) {
    if (!showStartFade && !showEndFade) return
    val w = size.width
    val maxFadePx = w * 0.45f
    if (!maxFadePx.isFinite() || maxFadePx < 1f) return
    val fadePx = fadeWidthPx.coerceIn(1f, maxFadePx)
    val startEnd = if (showStartFade) (fadePx / w).coerceIn(0.02f, 0.45f) else 0f
    val endStart = if (showEndFade) (1f - fadePx / w).coerceIn(0.55f, 0.98f) else 1f
    val stops = buildList {
        if (showStartFade) {
            add(0f to Color.Transparent)
            add((startEnd * 0.45f) to Color.White.copy(alpha = 0.35f))
            add(startEnd to Color.White)
        } else {
            add(0f to Color.White)
        }
        if (showEndFade) {
            add(endStart to Color.White)
            add((endStart + (1f - endStart) * 0.55f) to Color.White.copy(alpha = 0.35f))
            add(1f to Color.Transparent)
        } else {
            add(1f to Color.White)
        }
    }
    drawRect(
        brush = Brush.horizontalGradient(colorStops = stops.toTypedArray()),
        blendMode = BlendMode.DstIn,
    )
}

private fun DrawScope.drawDescriptionEdgeMask(
    showTopFade: Boolean,
    showBottomFade: Boolean,
    fadeHeightPx: Float,
) {
    if (!showTopFade && !showBottomFade) return
    val h = size.height
    val maxFadePx = h * 0.45f
    // A collapsing row or a settling sheet can hand us a near-zero height while the
    // fade flags still say yes. Below ~2px there is no room for a gradient, and the
    // clamp below would be an empty range (max under its own 1px minimum).
    if (!maxFadePx.isFinite() || maxFadePx < 1f) return
    val fadePx = fadeHeightPx.coerceIn(1f, maxFadePx)
    val topEnd = if (showTopFade) (fadePx / h).coerceIn(0.02f, 0.45f) else 0f
    val bottomStart = if (showBottomFade) (1f - fadePx / h).coerceIn(0.55f, 0.98f) else 1f
    val stops = buildList {
        if (showTopFade) {
            add(0f to Color.Transparent)
            add((topEnd * 0.45f) to Color.White.copy(alpha = 0.35f))
            add(topEnd to Color.White)
        } else {
            add(0f to Color.White)
        }
        if (showBottomFade) {
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
