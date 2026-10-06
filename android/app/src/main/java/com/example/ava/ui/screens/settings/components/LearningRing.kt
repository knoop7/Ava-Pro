package com.example.ava.ui.screens.settings.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular gauge with slow ripples expanding behind it — the "learning" motif of the
 * self-learning wake page. The arc sweeps [progress] of the circle with a soft gradient;
 * up to three concentric ripples breathe outward and fade while [live] is set.
 */
@Composable
fun LearningRing(
    progress: Float,
    tint: Color,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 5.dp,
    live: Boolean = true,
    trackColor: Color = tint.copy(alpha = 0.14f),
    labelColor: Color = tint,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "ring-progress",
    )
    val transition = rememberInfiniteTransition(label = "ring-ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring-ripple-phase",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = stroke.toPx()
            // Leave room for the ripples to grow past the ring.
            val ringRadius = (this.size.minDimension / 2f) * 0.72f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            if (live) {
                repeat(3) { i ->
                    val t = ((phase + i / 3f) % 1f)
                    val r = ringRadius + (this.size.minDimension / 2f - ringRadius) * t
                    val alpha = (1f - t) * 0.22f
                    drawCircle(
                        color = tint.copy(alpha = alpha),
                        radius = r,
                        center = center,
                        style = Stroke(width = strokePx * 0.35f),
                    )
                }
            }

            val arcSize = Size(ringRadius * 2f, ringRadius * 2f)
            val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                rotate(degrees = -90f, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to tint.copy(alpha = 0.55f),
                            animated.coerceAtLeast(0.02f) to tint,
                            1f to tint.copy(alpha = 0.55f),
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }
        }
        val labelSize = (size.value * 0.19f).sp
        Text(
            text = label,
            color = labelColor,
            maxLines = 1,
            // No Material line box: the glyphs must sit optically centred in the ring.
            style = TextStyle(
                fontSize = labelSize,
                lineHeight = labelSize,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}
