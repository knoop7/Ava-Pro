package com.example.ava.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * "Dawn" countdown: silver-slate light spreads up from the bottom edge for the
 * whole countdown while a big translucent digit ticks in the center. At zero
 * everything melts away in a staggered, flash-free dissolve (~2.8s), then
 * [onFinished] fires. Visual-only (no i18n). Mirrors
 * docs/previews/start-service-countdown-dawn-final.html.
 */
@Composable
fun StartServiceOnAppOpenCountdown(
    isDarkMode: Boolean,
    totalSeconds: Int = 7,
    onFinished: () -> Unit,
) {
    val seconds = totalSeconds.coerceAtLeast(1)
    var remaining by remember(seconds) { mutableIntStateOf(seconds) }

    // Continuous spread: one small glow grows past half the screen across the
    // whole countdown (+0.9s deceleration tail that keeps sliding during the
    // dissolve so the light never gets a new "move" command).
    val spread = remember { Animatable(0f) }

    // Per-layer alphas: entrance staggering in, dissolve staggering out.
    val veilA = remember { Animatable(0f) }
    val fogA = remember { Animatable(0f) }
    val glowA = remember { Animatable(0f) }
    val mistA = remember { Animatable(0f) }
    val hairA = remember { Animatable(0f) }
    val hairScale = remember { Animatable(0f) }
    val digitA = remember { Animatable(0f) }
    // 1 = below resting spot (entering), 0 = resting, negative = drifting up (exit).
    val digitShift = remember { Animatable(1f) }

    // Slow breathing on the glow core so the light feels alive mid-countdown.
    val breath by rememberInfiniteTransition(label = "dawnBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(
            tween(4200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "dawnBreathValue",
    )

    val decel = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val softInOut = CubicBezierEasing(0.45f, 0.05f, 0.35f, 1f)

    LaunchedEffect(seconds) {
        // Entrance: fog first, then glow, mist, hairline, digit.
        launch { veilA.animateTo(1f, tween(800)) }
        launch { fogA.animateTo(1f, tween(1600, easing = decel)) }
        launch { glowA.animateTo(1f, tween(1600, 120, decel)) }
        launch { mistA.animateTo(1f, tween(1800, 200, decel)) }
        launch { hairA.animateTo(1f, tween(900, 350)) }
        launch { hairScale.animateTo(1f, tween(1300, 350, decel)) }
        launch { digitA.animateTo(1f, tween(1100, 450)) }
        launch { digitShift.animateTo(0f, tween(1100, 450, decel)) }
        // Spread runs front-fast/late-slow so the light almost hovers on the
        // last second — the dissolve then rides its remaining tail.
        launch {
            spread.animateTo(
                1f,
                tween(seconds * 1000 + 900, easing = CubicBezierEasing(0.3f, 0.5f, 0.4f, 0.98f)),
            )
        }

        for (left in (seconds - 1) downTo 1) {
            delay(1000)
            remaining = left
        }
        delay(1000)

        // Flash-free dissolve: digit melts up, then mist/veil/light fade in
        // near-unison; nothing moves except the spread tail.
        launch { hairA.animateTo(0f, tween(1200, easing = softInOut)) }
        launch { digitShift.animateTo(-1f, tween(1550, 50, softInOut)) }
        launch { digitA.animateTo(0f, tween(1500, 50, softInOut)) }
        launch { mistA.animateTo(0f, tween(2000, 200, softInOut)) }
        launch { veilA.animateTo(0f, tween(2300, 300, softInOut)) }
        launch { glowA.animateTo(0f, tween(2400, easing = softInOut)) }
        launch { fogA.animateTo(0f, tween(2600, 100, softInOut)) }
        delay(2800)
        onFinished()
    }

    val configuration = LocalConfiguration.current
    val isLandscape = rememberPaneIsLandscape()
    val shortestDp = min(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val digitSp = with(LocalDensity.current) {
        // Proportional to the screen, immune to user font scale.
        (shortestDp * (if (isLandscape) 0.32f else 0.36f)).coerceIn(88f, 180f).dp.toSp()
    }

    val veilColor = if (isDarkMode) {
        Color(0xFF070A0F).copy(alpha = 0.52f)
    } else {
        Color(0xFF0F172A).copy(alpha = 0.42f)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val s = spread.value

            drawRect(color = veilColor, alpha = veilA.value)

            // Base fog: broad silver bleed rising from below the bottom edge.
            // Softness is baked into the gradient stops instead of a blur.
            // ponytail: no grain layer — Canvas gradients can band on cheap
            // panels; upgrade path is a RuntimeShader noise overlay (API 33+).
            val maxDim = max(w, h)
            val fogScale = 0.72f + 0.88f * s
            val fogR = maxDim * 0.82f * fogScale
            val fogCenter = Offset(w / 2f, h * (1.34f - 0.44f * s))
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFAAB8CC).copy(alpha = 0.30f),
                    0.42f to Color(0xFF94A3B8).copy(alpha = 0.14f),
                    0.62f to Color(0xFF94A3B8).copy(alpha = 0.05f),
                    0.78f to Color.Transparent,
                    center = fogCenter,
                    radius = fogR,
                ),
                radius = fogR,
                center = fogCenter,
                alpha = fogA.value,
            )

            // Glow core: smaller, brighter, with a hint of theme blue and a
            // slow breath layered on top of the spread.
            val glowScale = (0.60f + 0.85f * s) * (1f + 0.04f * breath)
            val glowR = maxDim * 0.55f * glowScale
            val glowCenter = Offset(w / 2f, h * (1.26f - 0.46f * s))
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFE2E8F0).copy(alpha = 0.42f),
                    0.38f to Color(0xFFCBD5E1).copy(alpha = 0.18f),
                    0.58f to Color(0xFFB0C6FF).copy(alpha = 0.07f),
                    0.74f to Color.Transparent,
                    center = glowCenter,
                    radius = glowR,
                ),
                radius = glowR,
                center = glowCenter,
                alpha = glowA.value * (0.82f + 0.18f * breath),
            )

            // Vertical mist: faint brightening near the bottom edge.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color(0xFFCBD5E1).copy(alpha = 0.02f),
                    0.75f to Color(0xFFCBD5E1).copy(alpha = 0.07f),
                    1f to Color(0xFFE2E8F0).copy(alpha = 0.12f),
                    startY = h * 0.32f,
                    endY = h,
                ),
                topLeft = Offset(0f, h * 0.32f),
                alpha = mistA.value,
            )

            // Horizon hairline, scaling out from the center.
            if (hairA.value > 0.01f && hairScale.value > 0.01f) {
                val y = h * 0.81f
                val halfSpan = w * 0.36f * hairScale.value
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0xFFE2E8F0).copy(alpha = 0.65f),
                        1f to Color.Transparent,
                        startX = w / 2f - halfSpan,
                        endX = w / 2f + halfSpan,
                    ),
                    start = Offset(w / 2f - halfSpan, y),
                    end = Offset(w / 2f + halfSpan, y),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // Digit is deliberately effect-free: no shadow, no AnimatedContent tick
        // layer — that extra composited layer over the glow produced clipped
        // bands on low-end GPUs. Seconds swap instantly; only the overlay-level
        // fade/shift (entrance + dissolve) animates via graphicsLayer.
        val shiftPx = with(LocalDensity.current) { 18.dp.toPx() }
        Text(
            text = remaining.toString(),
            color = Color(0xFFE8EDF4).copy(alpha = 0.66f),
            fontSize = digitSp,
            fontWeight = FontWeight(520),
            letterSpacing = digitSp * -0.04f,
            modifier = Modifier.graphicsLayer {
                alpha = digitA.value
                translationY = digitShift.value * shiftPx
            },
        )
    }
}
