package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Single-line text that can be dragged sideways when it overflows,
 * with the same left/right DstIn dissolve as the main-settings handle values.
 */
@Composable
fun SettingsHorizontalFadeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    fadeWidth: Dp = 22.dp,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) { scrollState.scrollTo(0) }
    val canScroll = scrollState.maxValue > 0
    val showRightFade = canScroll && scrollState.value < scrollState.maxValue - 2
    val showLeftFade = canScroll && scrollState.value > 2
    Box(
        modifier = modifier
            .clipToBounds()
            .then(
                if (canScroll) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawHorizontalValueEdgeMask(
                                showLeftFade = showLeftFade,
                                showRightFade = showRightFade,
                                fadePx = fadeWidth.toPx(),
                            )
                        }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.horizontalScroll(scrollState),
        )
    }
}

private fun DrawScope.drawHorizontalValueEdgeMask(
    showLeftFade: Boolean,
    showRightFade: Boolean,
    fadePx: Float,
) {
    if (!showLeftFade && !showRightFade) return
    val w = size.width
    if (!w.isFinite() || w < 2f) return
    val fade = fadePx.coerceIn(1f, w * 0.45f)
    val leftEnd = if (showLeftFade) (fade / w).coerceIn(0.02f, 0.45f) else 0f
    val rightStart = if (showRightFade) (1f - fade / w).coerceIn(0.55f, 0.98f) else 1f
    val stops = buildList {
        if (showLeftFade) {
            add(0f to Color.Transparent)
            add((leftEnd * 0.45f) to Color.White.copy(alpha = 0.35f))
            add(leftEnd to Color.White)
        } else {
            add(0f to Color.White)
        }
        if (showRightFade) {
            add(rightStart to Color.White)
            add((rightStart + (1f - rightStart) * 0.55f) to Color.White.copy(alpha = 0.35f))
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
