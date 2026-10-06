package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

@Composable
fun AutoResizeText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = TextStyle.Default,
    // true even for single-line: overflow is detected when text would wrap to another line.
    softWrap: Boolean = true,
) {
    val baseFontSize = rememberUpdatedState(fontSize)
    var resolvedSize by remember(text, fontSize) { mutableStateOf(fontSize) }
    LaunchedEffect(text, baseFontSize.value) {
        resolvedSize = baseFontSize.value
    }

    Text(
        text = text,
        modifier = modifier,
        fontSize = resolvedSize,
        fontWeight = fontWeight,
        color = color,
        letterSpacing = letterSpacing,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        style = style,
        onTextLayout = { result ->
            val overflows =
                result.hasVisualOverflow ||
                    (maxLines == 1 && result.lineCount > 1) ||
                    result.didOverflowWidth
            if (overflows && resolvedSize.isSpecified && resolvedSize > minFontSize) {
                // Shrink faster so small screens settle in fewer frames.
                val nextSize = (resolvedSize.value * 0.88f).sp
                resolvedSize = if (nextSize < minFontSize) minFontSize else nextSize
            }
        }
    )
}

/** Shrinks [baseFontSize] uniformly until the row fits the available width. */
@Composable
fun AutoShrinkFontRow(
    modifier: Modifier = Modifier,
    baseFontSize: TextUnit,
    minFontSize: TextUnit = (baseFontSize.value * 0.72f).coerceAtLeast(9f).sp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.(fontSize: TextUnit) -> Unit,
) {
    SubcomposeLayout(
        modifier = modifier.fillMaxWidth(),
    ) { constraints ->
        val maxWidth = constraints.maxWidth
        // Must measure with unbounded width: a capped maxWidth clamps
        // placeable.width to maxWidth, so the shrink loop never runs.
        val measureConstraints = Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )

        fun measureRow(fontSize: TextUnit) =
            subcompose(fontSize) {
                Row(verticalAlignment = verticalAlignment) {
                    content(fontSize)
                }
            }.first().measure(measureConstraints)

        var placeable = measureRow(baseFontSize)

        if (maxWidth != Constraints.Infinity && placeable.width > maxWidth) {
            var currentSize = baseFontSize
            while (currentSize > minFontSize) {
                val next = (currentSize.value * 0.92f).sp
                currentSize = if (next < minFontSize) minFontSize else next
                placeable = measureRow(currentSize)
                if (placeable.width <= maxWidth) break
            }
        }

        val layoutWidth =
            if (maxWidth == Constraints.Infinity) placeable.width else maxWidth
        layout(layoutWidth, placeable.height) {
            placeable.place(0, 0)
        }
    }
}
