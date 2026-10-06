package com.example.ava.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft radial glow behind circular voice controls — matches subtitle sphere /
 * home [MainControlButton] accent wash (4s breathe, theme-tinted, no black halo).
 */
@Composable
fun VoiceRadialColorGlow(
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (!enabled) return

    val transition = rememberInfiniteTransition(label = "voice_radial_glow")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )
    val glowScale by transition.animateFloat(
        initialValue = 1.12f,
        targetValue = 1.17f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )

    Canvas(
        modifier = modifier
            .scale(glowScale)
            .alpha(glowAlpha)
    ) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color,
                    color.copy(alpha = 0.45f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

/** Colored ambient/spot shadow — same as home Start/Stop service button when active. */
fun Modifier.voiceAccentButtonShadow(
    color: Color,
    shape: Shape = CircleShape,
    elevation: Dp = 20.dp,
    enabled: Boolean = true
): Modifier = if (enabled) {
    shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = color.copy(alpha = 0.3f),
        spotColor = color.copy(alpha = 0.3f)
    )
} else {
    this
}
