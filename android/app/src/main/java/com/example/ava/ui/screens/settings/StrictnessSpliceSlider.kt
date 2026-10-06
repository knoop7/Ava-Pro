package com.example.ava.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.ava.ui.haptic.rememberSliderTickHaptic
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import kotlin.math.roundToInt

/**
 * Touch-to-value mapping for [StrictnessSpliceSlider]. Extracted so the last detent
 * sits at fraction 1.0 (the bar end) and can be unit-tested without Compose.
 */
internal object StrictnessSpliceMapping {
    /** Fraction of the bar occupied by the native (continuous) strictness zone. */
    const val SOLID_FRACTION = 0.72f

    /** End of the first virtual detent ("strict+"); the second runs to the bar end. */
    const val ZONE1_END = 0.86f

    data class Position(val value: Float, val extraLevel: Int)

    fun fromFraction(
        fraction: Float,
        lo: Float,
        hi: Float,
        extraZoneEnabled: Boolean,
    ): Position {
        val f = fraction.coerceIn(0f, 1f)
        if (!extraZoneEnabled || f <= SOLID_FRACTION) {
            val t = if (extraZoneEnabled) f / SOLID_FRACTION else f
            val raw = lo + t * (hi - lo)
            val quantized = ((raw * 100f).roundToInt() / 100f).coerceIn(lo, hi)
            return Position(quantized, 0)
        }
        return Position(hi, if (f <= ZONE1_END) 1 else 2)
    }

    /**
     * Handle center as a fraction of bar width. Level 2 is 1.0 — the actual end —
     * not a mid-tail detent. The canvas insets by the handle half-width so the
     * thumb is fully visible rather than clipped against the capsule.
     */
    fun handleFraction(
        value: Float,
        lo: Float,
        hi: Float,
        extraLevel: Int,
        extraZoneEnabled: Boolean,
    ): Float {
        val level = if (extraZoneEnabled) extraLevel.coerceIn(0, 2) else 0
        val solid = if (extraZoneEnabled) SOLID_FRACTION else 1f
        return when (level) {
            0 -> {
                val t = ((value - lo) / (hi - lo).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                t * solid
            }
            1 -> (SOLID_FRACTION + ZONE1_END) / 2f
            else -> 1f
        }
    }
}

/**
 * Spliced strictness slider: one bar whose leading segment is the engine's native
 * cutoff (continuous, exactly the historical slider range) and whose hatched tail
 * holds two discrete extra-strictness detents ("strict+" / "max"). The native cutoff
 * is pinned at the range ceiling while a detent is active — extra strictness is a
 * separate channel, never encoded into the cutoff float.
 *
 * Fill, value label, and hint recolor per level (accent -> amber -> hot) so leaving
 * the native zone is unmistakable. The level-2 handle rides at the very end of the
 * bar rather than a mid-tail detent center.
 */
@Composable
internal fun StrictnessSpliceSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    extraLevel: Int,
    extraZoneEnabled: Boolean,
    level1Label: String,
    level2Label: String,
    hint: String?,
    onChange: (Float, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lo = valueRange.start
    val hi = valueRange.endInclusive
    val level = if (extraZoneEnabled) extraLevel.coerceIn(0, 2) else 0
    val solidFraction = if (extraZoneEnabled) StrictnessSpliceMapping.SOLID_FRACTION else 1f
    val tick = rememberSliderTickHaptic()
    val tickRef = rememberUpdatedState(tick)
    val isDark = isDarkModeEnabled()
    val trackColor = if (isDark) TrackDark else getSliderInactiveColor()
    val hatchColor = if (isDark) HatchDark else HatchLight
    val dividerColor = if (isDark) DividerDark else DividerLight
    val dotOnTrack = if (isDark) DotOnTrackDark else DotOnTrackLight
    val zoneLabelIdle = if (isDark) ZoneLabelIdleDark else ZoneLabelIdleLight

    val stateColor = when (level) {
        0 -> getAccentColor()
        1 -> AMBER
        else -> HOT
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
            )
            Text(
                text = when (level) {
                    0 -> String.format("%.2f", value.coerceIn(lo, hi))
                    1 -> level1Label
                    else -> level2Label
                },
                fontSize = settingsBodyTextSize(),
                fontWeight = FontWeight.Bold,
                color = stateColor,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOUCH_HEIGHT)
                .pointerInput(lo, hi, extraZoneEnabled) {
                    val widthPx = size.width.toFloat()
                    var lastTickValue = Float.NaN
                    var lastTickLevel = -1
                    fun apply(x: Float) {
                        val mapped = StrictnessSpliceMapping.fromFraction(
                            fraction = x / widthPx,
                            lo = lo,
                            hi = hi,
                            extraZoneEnabled = extraZoneEnabled,
                        )
                        val changed = mapped.value != lastTickValue ||
                            mapped.extraLevel != lastTickLevel
                        if (changed) {
                            tickRef.value()
                            lastTickValue = mapped.value
                            lastTickLevel = mapped.extraLevel
                        }
                        onChange(mapped.value, mapped.extraLevel)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        lastTickValue = Float.NaN
                        lastTickLevel = -1
                        apply(down.position.x)
                        drag(down.id) { change ->
                            change.consume()
                            apply(change.position.x)
                        }
                    }
                },
        ) {
            val zoneWidth1 = maxWidth * (StrictnessSpliceMapping.ZONE1_END - StrictnessSpliceMapping.SOLID_FRACTION)
            val zoneWidth2 = maxWidth * (1f - StrictnessSpliceMapping.ZONE1_END)

            if (!isDark) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .shadow(
                            elevation = 3.dp,
                            shape = RoundedCornerShape(percent = 50),
                            ambientColor = Color.Black.copy(alpha = 0.16f),
                            spotColor = Color.Black.copy(alpha = 0.10f),
                        ),
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
            ) {
                val w = size.width
                val barH = BAR_HEIGHT.toPx()
                val top = (size.height - barH) / 2f
                val bottom = top + barH
                val radius = barH / 2f
                val capsule = Path().apply {
                    addRoundRect(RoundRect(0f, top, w, bottom, CornerRadius(radius)))
                }

                // Base track.
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, top),
                    size = Size(w, barH),
                    cornerRadius = CornerRadius(radius),
                )

                val solidEnd = w * solidFraction
                if (extraZoneEnabled) {
                    // Hatched virtual tail.
                    clipPath(capsule) {
                        clipRect(solidEnd, top, w, bottom) {
                            val step = HATCH_STEP.toPx()
                            var x = solidEnd - barH
                            while (x < w) {
                                drawLine(
                                    color = hatchColor,
                                    start = Offset(x, bottom),
                                    end = Offset(x + barH, top),
                                    strokeWidth = HATCH_STROKE.toPx(),
                                )
                                x += step
                            }
                        }
                    }
                }

                // Only the handle half-width is reserved, so level 2 sits flush
                // on the bar tip rather than stopping a radius short of the end.
                val handleHalf = HANDLE_WIDTH.toPx() / 2f
                val handleX = (
                    StrictnessSpliceMapping.handleFraction(
                        value = value,
                        lo = lo,
                        hi = hi,
                        extraLevel = level,
                        extraZoneEnabled = extraZoneEnabled,
                    ) * w
                ).coerceIn(handleHalf, w - handleHalf)

                // Fill the whole capsule at the last detent so the tail is not left empty.
                val fillWidth = if (level >= 2) w else handleX
                clipPath(capsule) {
                    drawRoundRect(
                        color = stateColor,
                        topLeft = Offset(0f, top),
                        size = Size(fillWidth, barH),
                        cornerRadius = CornerRadius(radius),
                    )
                }

                // Sparse pearls on the native span only. Count follows a minimum
                // gap so a narrow dialog never packs them into a dotted line.
                val midY = (top + bottom) / 2f
                val gapHalf = HANDLE_WIDTH.toPx() / 2f + HANDLE_GAP.toPx()
                val dotR = DOT_RADIUS.toPx()
                val firstDot = radius + DOT_EDGE.toPx()
                val lastDot = solidEnd - DOT_EDGE.toPx()
                val span = lastDot - firstDot
                if (span > DOT_MIN_GAP.toPx()) {
                    val steps = (span / DOT_MIN_GAP.toPx()).toInt().coerceIn(2, DOT_MAX - 1)
                    for (i in 0..steps) {
                        val x = firstDot + span * i / steps
                        if (kotlin.math.abs(x - handleX) < gapHalf + dotR * 2f) continue
                        drawCircle(
                            color = if (x < fillWidth) dotOnFill else dotOnTrack,
                            radius = dotR,
                            center = Offset(x, midY),
                        )
                    }
                }

                if (extraZoneEnabled) {
                    // Short hairline ticks — a full-height bar would crowd the
                    // zone labels sitting in the same 18 dp strip.
                    val tickH = barH * 0.42f
                    val tickTop = midY - tickH / 2f
                    for (fraction in floatArrayOf(
                        StrictnessSpliceMapping.SOLID_FRACTION,
                        StrictnessSpliceMapping.ZONE1_END,
                    )) {
                        drawRect(
                            color = dividerColor,
                            topLeft = Offset(w * fraction - DIVIDER_WIDTH.toPx() / 2f, tickTop),
                            size = Size(DIVIDER_WIDTH.toPx(), tickH),
                        )
                    }
                }

                // M3-style gap: punch the track around the handle, then the handle bar.
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(handleX - gapHalf, top - 1f),
                    size = Size(gapHalf * 2f, barH + 2f),
                    blendMode = BlendMode.Clear,
                )
                val handleH = HANDLE_HEIGHT.toPx()
                val handleTop = (size.height - handleH) / 2f
                val handleLeft = handleX - HANDLE_WIDTH.toPx() / 2f
                if (!isDark) {
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.18f),
                        topLeft = Offset(handleLeft + 0.6f, handleTop + 1.2f),
                        size = Size(HANDLE_WIDTH.toPx(), handleH),
                        cornerRadius = CornerRadius(HANDLE_WIDTH.toPx() / 2f),
                    )
                }
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(handleLeft, handleTop),
                    size = Size(HANDLE_WIDTH.toPx(), handleH),
                    cornerRadius = CornerRadius(HANDLE_WIDTH.toPx() / 2f),
                )
            }

            if (extraZoneEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = maxWidth * StrictnessSpliceMapping.SOLID_FRACTION)
                        .width(zoneWidth1)
                        .height(BAR_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    ZoneLabel(
                        text = level1Label,
                        color = if (level >= 1) zoneLabelOnFill else zoneLabelIdle,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = maxWidth * StrictnessSpliceMapping.ZONE1_END)
                        .width(zoneWidth2)
                        .height(BAR_HEIGHT)
                        .padding(start = 4.dp, end = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ZoneLabel(
                        text = level2Label,
                        color = if (level >= 2) zoneLabelOnFill else zoneLabelIdle,
                    )
                }
            }
        }

        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                fontSize = 11.sp,
                maxLines = 1,
                color = if (level > 0) stateColor.copy(alpha = 0.85f) else getSettingsDescriptionColor(),
            )
        }
    }
}

@Composable
private fun ZoneLabel(text: String, color: Color) {
    Text(
        text = text,
        maxLines = 1,
        color = color,
        style = ZoneLabelStyle,
    )
}

private val ZoneLabelStyle = TextStyle(
    fontSize = 10.sp,
    lineHeight = 10.sp,
    fontWeight = FontWeight.SemiBold,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

private val BAR_HEIGHT = 18.dp
private val TOUCH_HEIGHT = 34.dp
private val HANDLE_WIDTH = 4.dp
private val HANDLE_HEIGHT = 26.dp
private val HANDLE_GAP = 4.dp
private val DIVIDER_WIDTH = 1.dp
private val HATCH_STEP = 12.dp
private val HATCH_STROKE = 1.5.dp
private val DOT_RADIUS = 1.dp
private val DOT_EDGE = 12.dp
private val DOT_MIN_GAP = 28.dp
private const val DOT_MAX = 5

private val AMBER = Color(0xFFFFB454)
private val HOT = Color(0xFFFF7A59)
private val TrackDark = Color(0xFF3A414E)
private val HatchDark = Color.White.copy(alpha = 0.07f)
private val HatchLight = Color.Black.copy(alpha = 0.06f)
private val DividerDark = Color.Black.copy(alpha = 0.28f)
private val DividerLight = Color.Black.copy(alpha = 0.14f)
private val DotOnTrackDark = Color.White.copy(alpha = 0.20f)
private val DotOnTrackLight = Color.Black.copy(alpha = 0.16f)
private val dotOnFill = Color(0xFF1A1206).copy(alpha = 0.22f)
private val ZoneLabelIdleDark = Color(0xFF8B93A3)
private val ZoneLabelIdleLight = Color(0xFF64748B)
private val zoneLabelOnFill = Color(0xE61A1206)
