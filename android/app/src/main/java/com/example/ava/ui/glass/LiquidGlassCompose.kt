package com.example.ava.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.settings.SettingsStyleSession
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Compose-side snapshot of the Liquid Glass style; recomposes when the user flips it. */
data class LiquidGlassState(
    val enabled: Boolean,
    val intensity: Float,
    val pressGlow: Boolean = true,
    /** 0..1 fade of the material. Tracks [enabled] with a crossfade so toggles do not pop. */
    val visibility: Float = 1f,
    /** Animated same-window frost radius; 0 when blur is off or glass is hidden. */
    val viewBlurRadiusPx: Float = 0f,
)

private const val GLASS_FADE_MS = 320
private const val GLASS_INTENSITY_MS = 160

@Composable
fun rememberLiquidGlassState(): State<LiquidGlassState> {
    val enabled by SettingsStyleSession.liquidGlassEnabled.collectAsStateWithLifecycle()
    val intensityRaw by SettingsStyleSession.liquidGlassIntensity.collectAsStateWithLifecycle()
    val pressGlow by SettingsStyleSession.liquidGlassPressGlow.collectAsStateWithLifecycle()
    val blurOn by SettingsStyleSession.liquidGlassBlur.collectAsStateWithLifecycle()
    val visibility by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(GLASS_FADE_MS, easing = FastOutSlowInEasing),
        label = "liquidGlassVisibility",
    )
    val intensity by animateFloatAsState(
        targetValue = LiquidGlass.remapMaterialIntensity(
            intensityRaw.coerceIn(0, 100) / 100f,
        ),
        animationSpec = tween(GLASS_INTENSITY_MS, easing = FastOutSlowInEasing),
        label = "liquidGlassIntensity",
    )
    val viewBlurRadiusPx by animateFloatAsState(
        targetValue = if (enabled && blurOn) LiquidGlass.viewBlurRadiusPx() else 0f,
        animationSpec = tween(GLASS_FADE_MS, easing = FastOutSlowInEasing),
        label = "liquidGlassBlur",
    )
    return rememberUpdatedState(
        LiquidGlassState(
            enabled = enabled || visibility > 0.01f,
            intensity = intensity,
            pressGlow = pressGlow,
            visibility = visibility,
            viewBlurRadiusPx = viewBlurRadiusPx,
        ),
    )
}

/**
 * Paints the Liquid Glass material behind the content and clips to [shape]: translucent
 * body, top sheen, specular rim lit from the top-left, faint bottom inner shadow.
 *
 * Used for floating chrome inside Compose: the settings bottom dock capsule, the home /
 * settings sidebar drawer, and the live preview on the Liquid Glass settings card.
 * Regular settings cards stay flat.
 *
 * While the surface is pressed (and [LiquidGlassState.pressGlow] is on) a specular bloom
 * follows the finger, iOS-26 style. The touch observer sits on the Initial pass and never
 * consumes, so buttons and sliders on top of the glass keep working untouched.
 *
 * @param flatColor  the pre-glass surface colour; drawn at `1 - alpha` so [alpha] crossfades
 *                   flat → glass.
 * @param alpha      0..1 glass amount. 1 = full material, 0 = plain [flatColor].
 * @param hasBackdropBlur  the host blurs what lies behind this surface (sidebar page frost).
 *                   The body can then stay thin; without blur it thickens a little so text
 *                   on top survives busy content underneath.
 * @param shadow     cast an outer drop shadow. This is what grounds glass on a flat page —
 *                   on a white settings screen it is the main cue that the slab exists.
 */
fun Modifier.liquidGlass(
    state: LiquidGlassState,
    shape: Shape,
    dark: Boolean,
    flatColor: Color,
    alpha: Float = 1f,
    hasBackdropBlur: Boolean = false,
    shadow: Boolean = true,
): Modifier = composed {
    val a = (alpha * state.visibility).coerceIn(0f, 1f)
    val pressPoint = remember { mutableStateOf(Offset.Zero) }
    val glow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val pressTracking = if (state.enabled && state.pressGlow && a > 0f) {
        Modifier.pointerInput(state.enabled, state.pressGlow) {
            // The bloom centre is quantized to an 8px grid: a step no one feels, but the
            // cached radial brush rebuilds far less often while the finger travels.
            fun q(v: Float) = (v / 8f).roundToInt() * 8f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressPoint.value = Offset(q(down.position.x), q(down.position.y))
                scope.launch { glow.animateTo(1f, tween(PRESS_GLOW_IN_MS, easing = LinearOutSlowInEasing)) }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.firstOrNull { it.pressed }
                    if (pressed != null) {
                        pressPoint.value = Offset(q(pressed.position.x), q(pressed.position.y))
                    } else {
                        scope.launch { glow.animateTo(0f, tween(PRESS_GLOW_OUT_MS, easing = FastOutSlowInEasing)) }
                        break
                    }
                }
            }
        }
    } else Modifier

    // Outer drop shadow, drawn before the clip so it lands outside the outline. Light
    // glass over a pale page gets a whisper; dark glass a deeper one (Apple lowers
    // shadow opacity over solid light backgrounds). Scaled by [alpha] so the preview
    // card's flat → glass crossfade also fades the shadow in.
    //
    // Drawn by hand — [Modifier.shadow] uses RenderNode elevation, which treats the
    // outline as an opaque occluder. In light mode the skipped backdrop is the
    // window's white background, so translucent glass reads as a solid white sheet
    // the moment rows start scrolling over it.
    val glassShadow = if (shadow && state.enabled && a > 0f) {
        val elevation = lerp(4f, 10f, state.intensity) * a
        val shadowAlpha = (if (dark) LiquidGlass.SHADOW_ALPHA_DARK else LiquidGlass.SHADOW_ALPHA_LIGHT) * a
        Modifier.liquidGlassOuterShadow(
            shape = shape,
            elevationDp = elevation,
            color = Color.Black.copy(alpha = shadowAlpha),
        )
    } else Modifier

    pressTracking.then(
        glassShadow
            .then(
                if (!state.enabled && a <= 0f) {
                    Modifier.clip(shape)
                } else {
                    // Canvas clip, not a RenderNode. graphicsLayer(clip) / clip()
                    // promote the slab into a hardware FBO; in light mode that
                    // buffer clears to the window's white, so scrolling rows ride
                    // a solid sheet instead of the glass.
                    Modifier
                }
            )
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                if (!state.enabled && a <= 0f) {
                    onDrawBehind { drawOutline(outline, color = flatColor) }
                } else {
                    val t = state.intensity
                    // Body: thin, even tint. Blur + rim + shadow carry the look, not the
                    // fill. Light glass is pure white (a grey-white on a white page only
                    // dims); dark glass is a lifted charcoal so it sits above a dark page.
                    // Intensity drives blur / rim / shadow, not body density. Without a
                    // real backdrop blur the body thickens a little for legibility.
                    // Dark glass is a smoked black-grey, not a lifted charcoal: a mid-alpha
                    // blue-grey over content plus a white lift reads as dishwater grey.
                    // Deeper, near-neutral body at a denser alpha keeps it black.
                    val bodyAlpha = when {
                        dark && hasBackdropBlur -> 0.52f
                        dark -> 0.64f
                        hasBackdropBlur -> 0.18f
                        else -> 0.30f
                    } * a
                    val body = (if (dark) Color(0xFF0F1114) else Color.White).copy(alpha = bodyAlpha)
                    // Even luminous lift across the whole face. Kept very low on dark glass —
                    // the rim and shadow define the slab; a stronger lift only greys it.
                    val lift = Color.White.copy(
                        alpha = lerp(if (dark) 0.025f else 0.04f, if (dark) 0.045f else 0.06f, t) * a,
                    )
                    // Specular rim: light source top-left. On a tall sidebar a steep
                    // 135° falloff left the bottom edge dim; light glass keeps the
                    // whole silhouette bright so top and bottom both read as glass.
                    val rimBright = lerp(if (dark) 0.26f else 0.50f, if (dark) 0.55f else 0.85f, t) * a
                    val rim = Brush.linearGradient(
                        0f to Color.White.copy(alpha = rimBright),
                        0.55f to Color.White.copy(
                            alpha = rimBright * (if (dark) 0.18f else 0.70f),
                        ),
                        1f to Color.White.copy(
                            alpha = rimBright * (if (dark) 0.60f else 0.82f),
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                    // Light glass on a pale page needs two concentric edges or the
                    // slab dissolves into the page: a dark outer lip that defines the
                    // silhouette, then the white specular rim sitting just inside it.
                    // Same-path stacking used to hide the dark lip under the white.
                    val lightEdge = if (dark) {
                        Color.Transparent
                    } else {
                        Color(0xFF1E293B).copy(alpha = lerp(0.18f, 0.28f, t) * a)
                    }
                    // Inner hairline tucked inside the rim — the shadow side of the bevel.
                    // Dark glass: a faint cool-white inner edge (black would vanish).
                    // Light glass: skip it. A black wash along the bottom of a tall
                    // sidebar reads as a dirty band, not thickness; the white rim is
                    // the iOS edge.
                    val bevelAlpha = if (dark) lerp(0.05f, 0.09f, t) * a else 0f
                    val bevelColor = Color.White
                    val bevel = Brush.verticalGradient(
                        0f to bevelColor.copy(alpha = bevelAlpha * 0.35f),
                        0.5f to bevelColor.copy(alpha = bevelAlpha * 0.7f),
                        1f to bevelColor.copy(alpha = bevelAlpha),
                        startY = 0f,
                        endY = size.height,
                    )
                    val outerWidth = 1.2.dp.toPx()
                    val rimWidth = 1.25.dp.toPx()
                    val bevelWidth = 1.dp.toPx()
                    val outerStroke = Stroke(width = outerWidth)
                    val stroke = Stroke(width = rimWidth)
                    val bevelStroke = Stroke(width = bevelWidth)
                    // Strokes are centred on their path. Light glass: dark lip on the
                    // silhouette, white rim inset so the two layers stay distinct.
                    // Dark glass: white rim at the edge, inner bevel just inside it.
                    val outerInset = outerWidth / 2f
                    val outerOutline = shape.createOutline(
                        Size(size.width - outerWidth, size.height - outerWidth), layoutDirection, this,
                    )
                    val rimInset = if (dark) rimWidth / 2f else outerWidth + rimWidth / 2f
                    val rimOutline = shape.createOutline(
                        Size(size.width - rimInset * 2f, size.height - rimInset * 2f),
                        layoutDirection,
                        this,
                    )
                    val bevelInset = rimInset + rimWidth / 2f + bevelWidth / 2f
                    val bevelOutline = shape.createOutline(
                        Size(size.width - bevelInset * 2f, size.height - bevelInset * 2f), layoutDirection, this,
                    )
                    val flat = flatColor.copy(alpha = flatColor.alpha * (1f - a))
                    // Soft, wide bloom built once per (quantized) touch point at full
                    // strength; the press animation only scales [drawOutline]'s alpha, so
                    // tracking a finger never allocates a new brush per frame.
                    val bloom = Brush.radialGradient(
                        0f to Color.White.copy(alpha = lerp(0.04f, 0.10f, t)),
                        0.4f to Color(0xFFEBF2FF).copy(alpha = lerp(0.04f, 0.10f, t) * 0.35f),
                        1f to Color.Transparent,
                        center = pressPoint.value,
                        radius = (maxOf(size.width, size.height) * 0.6f)
                            .coerceIn(80.dp.toPx(), 190.dp.toPx()),
                    )
                    val contentClip = Path().apply { addOutline(outline) }
                    onDrawWithContent {
                        val content = this
                        if (flat.alpha > 0.002f) drawOutline(outline, color = flat)
                        drawOutline(outline, color = body)
                        drawOutline(outline, color = lift)
                        // Specular bloom under the finger; state reads here only redraw
                        // the layer, they never recompose the tree.
                        val progress = glow.value
                        if (progress > 0.005f) {
                            drawOutline(outline, brush = bloom, alpha = progress)
                        }
                        if (lightEdge.alpha > 0.002f) {
                            translate(outerInset, outerInset) {
                                drawOutline(outerOutline, color = lightEdge, style = outerStroke)
                            }
                        }
                        if (bevelAlpha > 0.002f) {
                            translate(bevelInset, bevelInset) {
                                drawOutline(bevelOutline, brush = bevel, style = bevelStroke)
                            }
                        }
                        translate(rimInset, rimInset) {
                            drawOutline(rimOutline, brush = rim, style = stroke)
                        }
                        clipPath(contentClip) { content.drawContent() }
                    }
                }
            },
    )
}

private const val PRESS_GLOW_IN_MS = 140
private const val PRESS_GLOW_OUT_MS = 380

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * Soft outer shadow that does not create a platform elevation layer.
 * Stacked, slightly larger copies of [shape] stand in for a blur. The panel
 * interior is clipped out — a filled copy would sit under translucent light
 * glass and read as a black-grey sheet, darkest along the bottom.
 */
private fun Modifier.liquidGlassOuterShadow(
    shape: Shape,
    elevationDp: Float,
    color: Color,
): Modifier {
    if (elevationDp <= 0f || color.alpha <= 0.001f) return this
    return drawBehind {
        val elevPx = elevationDp.dp.toPx()
        val pad = 2.dp.toPx()
        val hole = Path().apply {
            addOutline(
                shape.createOutline(
                    Size(size.width + pad * 2f, size.height + pad * 2f),
                    layoutDirection,
                    this@drawBehind,
                ),
            )
            this.translate(Offset(-pad, -pad))
        }
        val steps = 5
        clipPath(hole, clipOp = ClipOp.Difference) {
            for (i in steps downTo 1) {
                val f = i / steps.toFloat()
                val spread = elevPx * 0.48f * f
                val stepAlpha = color.alpha * 0.24f * (1.1f - f * 0.6f)
                translate(-spread, -spread) {
                    drawOutline(
                        shape.createOutline(
                            Size(size.width + spread * 2f, size.height + spread * 2f),
                            layoutDirection,
                            this@drawBehind,
                        ),
                        color = color.copy(alpha = stepAlpha),
                    )
                }
            }
        }
    }
}
