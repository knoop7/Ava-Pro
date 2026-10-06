package com.example.ava.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Drop ASCII / fullwidth parentheticals from titles (Remastered, 现场, etc.). */
fun stripParenthetical(title: String): String =
    title
        .replace(Regex("\\s*\\([^)]*\\)"), "")
        .replace(Regex("\\s*（[^）]*）"), "")
        .trim()
        .replace(Regex("\\s{2,}"), " ")

/** End inset so dissolve / marquee does not eat the last glyph (same idea as NP lyrics). */
private val MarqueeEndPad = 10.dp

/**
 * Single-line centered text: marquee + soft edge dissolve when overflowing;
 * short lines stay static (no permanent fade).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CautiousMarqueeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    if (text.isBlank()) return
    var containerWidthPx by remember(text) { mutableIntStateOf(0) }
    var textWidthPx by remember(text) { mutableIntStateOf(0) }
    val overflowing = containerWidthPx > 0 && textWidthPx > containerWidthPx
    Box(
        modifier = modifier
            .onSizeChanged { containerWidthPx = it.width }
            .then(
                if (overflowing) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.12f to Color.White.copy(alpha = 0.38f),
                                        0.24f to Color.White,
                                        0.76f to Color.White,
                                        0.88f to Color.White.copy(alpha = 0.38f),
                                        1.0f to Color.Transparent,
                                    ),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            onTextLayout = { layout ->
                // Intrinsic paint width — not layout box width (fillMaxWidth would
                // otherwise make overflow never trip).
                textWidthPx = kotlin.math.ceil(layout.multiParagraph.width).toInt()
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (overflowing) Modifier.padding(end = MarqueeEndPad) else Modifier,
                )
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 2_000,
                    repeatDelayMillis = 0,
                    spacing = MarqueeSpacing(20.dp),
                    velocity = 24.dp,
                ),
        )
    }
}
