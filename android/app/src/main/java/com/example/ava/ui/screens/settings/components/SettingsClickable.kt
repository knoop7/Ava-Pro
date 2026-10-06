package com.example.ava.ui.screens.settings.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Light-theme press: circle from the finger only. No rectangular state layer.
 * Canvas clipRect — not Modifier.clip — so elevated cards keep their original shadow.
 */
private object SettingsLightRipple : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return SettingsLightRippleNode(interactionSource)
    }

    override fun hashCode(): Int = 0

    override fun equals(other: Any?): Boolean = other === this
}

private class SettingsLightRippleNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

    private var origin = Offset.Zero
    private var lastSize = Size.Zero
    private val radius = Animatable(0f)
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> onPress(interaction)
                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> onEnd()
                }
            }
        }
    }

    private fun onPress(press: PressInteraction.Press) {
        origin = press.pressPosition
        val target = if (lastSize.width > 0f) {
            furthestCorner(origin, lastSize)
        } else {
            320f
        }
        coroutineScope.launch {
            radius.snapTo(0f)
            alpha.snapTo(RipplePeakAlpha)
            invalidateDraw()
            launch {
                alpha.animateTo(
                    RipplePeakAlpha * 0.35f,
                    tween(durationMillis = 200, easing = FastOutSlowInEasing),
                ) {
                    invalidateDraw()
                }
            }
            radius.animateTo(
                target,
                tween(durationMillis = 200, easing = FastOutSlowInEasing),
            ) {
                invalidateDraw()
            }
        }
    }

    private fun onEnd() {
        coroutineScope.launch {
            alpha.animateTo(0f, tween(durationMillis = 180, easing = FastOutSlowInEasing)) {
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        lastSize = size
        drawContent()
        val a = alpha.value
        val r = radius.value
        if (a <= 0.001f || r <= 0.5f) return
        clipRect {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = a),
                        Color.Black.copy(alpha = a * 0.4f),
                        Color.Transparent,
                    ),
                    center = origin,
                    radius = r.coerceAtLeast(1f),
                ),
                radius = r,
                center = origin,
            )
        }
    }
}

private const val RipplePeakAlpha = 0.026f

private fun furthestCorner(origin: Offset, size: Size): Float {
    val w = size.width
    val h = size.height
    return maxOf(
        hypot(origin.x, origin.y),
        hypot(w - origin.x, origin.y),
        hypot(origin.x, h - origin.y),
        hypot(w - origin.x, h - origin.y),
    )
}

/** Light theme only. Dark theme keeps Material defaults. */
@Composable
fun ProvideSettingsLightRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides SettingsLightRipple,
        content = content,
    )
}

fun Modifier.settingsClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = settingsFocusHighlight().clickable(enabled = enabled, onClick = onClick)
