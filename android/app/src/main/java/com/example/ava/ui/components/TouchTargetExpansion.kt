package com.example.ava.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Expands a composable's touch/click target on each side *without* growing the space it
 * occupies in its parent layout. This is the Compose equivalent of CSS negative margin.
 *
 * IMPORTANT: [Modifier.padding] throws [IllegalArgumentException] on negative values by design —
 * do NOT attempt "negative padding then positive padding" to fake this; it crashes immediately.
 * Use this modifier instead.
 *
 * Usage: apply real (positive) padding for the touch area, put `clickable` after it, then wrap
 * the whole thing in this modifier so only the pre-padding size is reported upward:
 * ```
 * Text(
 *     modifier = Modifier
 *         .expandTouchTarget(bottom = 10.dp)
 *         .clickable(onClick = ...)
 *         .padding(bottom = 10.dp),
 *     ...
 * )
 * ```
 */
fun Modifier.expandTouchTarget(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp,
): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val startPx = start.roundToPx()
    val topPx = top.roundToPx()
    val endPx = end.roundToPx()
    val bottomPx = bottom.roundToPx()
    val width = (placeable.width - startPx - endPx).coerceAtLeast(0)
    val height = (placeable.height - topPx - bottomPx).coerceAtLeast(0)
    layout(width, height) {
        placeable.place(-startPx, -topPx)
    }
}

/** Symmetric shorthand when all sides should grow equally. */
fun Modifier.expandTouchTarget(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier =
    expandTouchTarget(start = horizontal, top = vertical, end = horizontal, bottom = vertical)
