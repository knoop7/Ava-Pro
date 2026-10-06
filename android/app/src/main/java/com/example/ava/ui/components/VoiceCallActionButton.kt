package com.example.ava.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared sizing for decline / answer / hangup on both call surfaces. */
object VoiceCallActionButtonMetrics {
    const val SLOT_COUNT = 3
    const val GLOW_OUTER_FACTOR = 1.22f
    /** Fraction of each slot used for the glow envelope — leaves side margin so glow is not clipped. */
    const val SLOT_FILL = 0.92f
    val MIN_DIAMETER = 117.dp
    val MAX_DIAMETER = 144.dp
    val ROW_HORIZONTAL_PADDING = 16.dp
}

fun voiceCallActionButtonDiameter(slotWidth: Dp, uiScale: Float = 1f): Dp {
    val outer = slotWidth * VoiceCallActionButtonMetrics.SLOT_FILL
    val diameter = outer / VoiceCallActionButtonMetrics.GLOW_OUTER_FACTOR
    val minD = VoiceCallActionButtonMetrics.MIN_DIAMETER.value * uiScale
    val maxD = VoiceCallActionButtonMetrics.MAX_DIAMETER.value * uiScale
    return diameter.coerceIn(minD.dp, maxD.dp)
}

fun voiceCallActionSlotHeight(diameter: Dp): Dp =
    diameter * VoiceCallActionButtonMetrics.GLOW_OUTER_FACTOR

/**
 * Three equal slots — middle slot stays geometric center in portrait and landscape.
 * Empty slots keep width so a lone hangup button never shifts sideways.
 */
@Composable
fun VoiceCallActionButtonRow(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = VoiceCallActionButtonMetrics.ROW_HORIZONTAL_PADDING,
    left: (@Composable (diameter: Dp) -> Unit)? = null,
    center: (@Composable (diameter: Dp) -> Unit)? = null,
    right: (@Composable (diameter: Dp) -> Unit)? = null
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val uiScale = rememberVoiceCallUiScale().control
        val slotWidth = (maxWidth - horizontalPadding * 2) / VoiceCallActionButtonMetrics.SLOT_COUNT
        val diameter = remember(maxWidth, horizontalPadding, uiScale) {
            voiceCallActionButtonDiameter(slotWidth, uiScale)
        }
        val slotHeight = voiceCallActionSlotHeight(diameter)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoiceCallActionSlot(slotHeight, left, diameter)
            VoiceCallActionSlot(slotHeight, center, diameter)
            VoiceCallActionSlot(slotHeight, right, diameter)
        }
    }
}

@Composable
private fun RowScope.VoiceCallActionSlot(
    slotHeight: Dp,
    content: (@Composable (diameter: Dp) -> Unit)?,
    diameter: Dp
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(slotHeight),
        contentAlignment = Alignment.Center
    ) {
        content?.invoke(diameter)
    }
}

/** Single call action (e.g. caller hangup) — same diameter formula as [VoiceCallActionButtonRow]. */
@Composable
fun VoiceCallActionButtonHost(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = VoiceCallActionButtonMetrics.ROW_HORIZONTAL_PADDING,
    content: @Composable (diameter: Dp) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val uiScale = rememberVoiceCallUiScale().control
        val slotWidth = (maxWidth - horizontalPadding * 2) / VoiceCallActionButtonMetrics.SLOT_COUNT
        val diameter = remember(maxWidth, horizontalPadding, uiScale) {
            voiceCallActionButtonDiameter(slotWidth, uiScale)
        }
        val outer = diameter * VoiceCallActionButtonMetrics.GLOW_OUTER_FACTOR
        Box(
            modifier = Modifier.size(outer),
            contentAlignment = Alignment.Center
        ) {
            content(diameter)
        }
    }
}

@Composable
fun VoiceCallCircleButton(
    color: Color,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    diameter: Dp,
    showGlow: Boolean = false,
    iconTint: Color = Color.White
) {
    val outer = diameter * VoiceCallActionButtonMetrics.GLOW_OUTER_FACTOR
    Box(
        modifier = Modifier.size(outer),
        contentAlignment = Alignment.Center
    ) {
        if (showGlow) {
            VoiceRadialColorGlow(
                color = color,
                modifier = Modifier.size(outer),
                enabled = true
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(diameter)
                .voiceAccentButtonShadow(color = color)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(diameter * 0.48f)
                )
            }
        }
    }
}
