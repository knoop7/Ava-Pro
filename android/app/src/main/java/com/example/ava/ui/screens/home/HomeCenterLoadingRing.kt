package com.example.ava.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.SlateSecondaryDark

/**
 * Lightweight centered loading ring for home rebuild / bind / launcher hydrate.
 * Replaces the large satellite-card spinner so rotation feels continuous.
 */
@Composable
fun HomeCenterLoadingRing(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    ringSize: Dp = 60.dp,
    strokeWidth: Dp = 3.dp,
) {
    val ringColor = if (isDarkMode) {
        SlateSecondaryDark.copy(alpha = 0.82f)
    } else {
        AccentBlue.copy(alpha = 0.55f)
    }
    val trackColor = ringColor.copy(alpha = 0.18f)

    val transition = rememberInfiniteTransition(label = "ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Canvas(modifier = modifier.size(ringSize).rotate(rotation)) {
        val sw = strokeWidth.toPx()
        val d = ringSize.toPx()
        val center = Offset(d / 2f, d / 2f)
        val radius = d / 2f - sw / 2f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)

        drawCircle(color = trackColor, radius = radius, center = center, style = stroke)

        val sweep = Brush.sweepGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.55f to Color.Transparent,
                0.7f to ringColor.copy(alpha = 0.15f),
                0.85f to ringColor.copy(alpha = 0.5f),
                1f to ringColor,
            ),
            center = center,
        )
        drawArc(
            brush = sweep,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(d - sw, d - sw),
            style = stroke,
        )
    }
}
