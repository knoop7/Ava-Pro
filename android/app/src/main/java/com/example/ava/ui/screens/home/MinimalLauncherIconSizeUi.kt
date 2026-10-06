package com.example.ava.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.haptic.rememberSliderTickHaptic
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import kotlin.math.abs
import kotlin.math.roundToInt

/** Chrome button that opens the icon size adjuster, sitting beside the wallpaper action. */
@Composable
fun MinimalLauncherIconSizeButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_minimal_launcher_icon_size),
            contentDescription = stringResource(R.string.minimal_launcher_icon_size_title),
            // Overview sits on a darkened scrim — keep icons white in both themes.
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Bottom sheet for choosing icon size, shown over a live desktop.
 *
 * The point of the whole control is watching the real icons resize underneath, so this
 * stays deliberately short and never dims the workspace. Every notch writes a preview
 * value that the workspace picks up immediately; nothing touches disk or moves an icon
 * until [onConfirm].
 *
 * [detents] is resolved from the page actually on screen, so on a phone that cannot go
 * past three-across there is simply no notch there to hit.
 *
 * Chrome matches the first-run notice: same translucent surface, same hairline, and the
 * same accent the overview actions already use (brown in dark, [AccentBlue] in light).
 */
@Composable
fun MinimalLauncherIconSizeSheet(
    visible: Boolean,
    detents: List<MinimalLauncherIconSizeDetent>,
    currentStep: Int,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    onPreview: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible && detents.size > 1,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(200)) +
            slideInVertically(animationSpec = tween(260)) { it / 2 },
        exit = fadeOut(animationSpec = tween(150)) +
            slideOutVertically(animationSpec = tween(200)) { it / 3 },
    ) {
        val tick = rememberSliderTickHaptic()
        // Nearest rather than exact: a stored step can lose its notch when the page
        // geometry changes, and the slider still has to show where the user stands.
        val startIndex = remember(detents, currentStep) {
            detents.indices.minByOrNull { abs(detents[it].step - currentStep) } ?: 0
        }
        var index by remember(detents) { mutableIntStateOf(startIndex) }
        // The exit animation keeps composing this after the caller has moved on, and a
        // rotation can empty the detents in that window.
        val selected = detents.getOrNull(index) ?: detents.lastOrNull()
            ?: return@AnimatedVisibility

        val shape = RoundedCornerShape(22.dp)
        // Same family as the first-run notice, a touch more see-through so the live
        // preview of the icons underneath is still readable.
        val panelColor = if (isDarkMode) Color(0xCC1B1F2A) else Color(0xCCFAF8FF)
        val hairline = if (isDarkMode) Color(0x33FFFFFF) else Color(0x14000000)
        val titleColor = if (isDarkMode) Color(0xFFE2E2EA) else Color(0xFF191B22)
        val subtitleColor = if (isDarkMode) Color(0xFFB6C6F2) else Color(0xFF4F5E84)
        val accent = if (isDarkMode) AccentBrown else AccentBlue

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(panelColor)
                .border(1.dp, hairline, shape)
                // Swallows taps on empty chrome so they cannot reach the desktop
                // underneath. Children (track, buttons) still win the hit test.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.minimal_launcher_icon_size_title),
                    color = titleColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        R.string.minimal_launcher_icon_size_row_count,
                        selected.seatsAcross,
                    ),
                    color = subtitleColor,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            DetentTrack(
                count = detents.size,
                index = index,
                accent = accent,
                isDarkMode = isDarkMode,
                onIndexChange = { next ->
                    index = next
                    tick()
                    onPreview(detents[next].step)
                },
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.minimal_launcher_icon_size_cancel),
                        color = subtitleColor,
                        fontSize = 14.sp,
                    )
                }
                TextButton(onClick = { onConfirm(selected.step) }) {
                    Text(
                        text = stringResource(R.string.minimal_launcher_icon_size_apply),
                        color = accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Notched track. One pointer handler owns both tap and drag: two sibling
 * `pointerInput` blocks used to race, and `detectTapGestures` consumed the down
 * before a horizontal drag could ever start, which is why the thumb would not move.
 *
 * Position maps straight to a notch, so the finger is followed instead of having to
 * accumulate a large delta before the size flips.
 */
@Composable
private fun DetentTrack(
    count: Int,
    index: Int,
    accent: Color,
    isDarkMode: Boolean,
    onIndexChange: (Int) -> Unit,
) {
    val trackColor = if (isDarkMode) Color(0x33FFFFFF) else Color(0x1A0417E0)
    val notchColor = if (isDarkMode) Color(0x66FFFFFF) else accent.copy(alpha = 0.35f)
    val fillColor = accent.copy(alpha = if (isDarkMode) 0.55f else 0.40f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val density = LocalDensity.current
        val thumb: Dp = 24.dp
        val travel = (maxWidth - thumb).coerceAtLeast(0.dp)
        val travelPx = with(density) { travel.toPx() }.coerceAtLeast(1f)
        val thumbPx = with(density) { thumb.toPx() }
        val fraction = if (count > 1) index.toFloat() / (count - 1) else 0f
        val animated by animateFloatAsState(
            targetValue = fraction,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "iconSizeThumb",
        )

        val indexState = rememberUpdatedState(index)
        val onIndexChangeState = rememberUpdatedState(onIndexChange)

        fun indexAt(x: Float): Int {
            if (count <= 1) return 0
            val f = ((x - thumbPx / 2f) / travelPx).coerceIn(0f, 1f)
            return (f * (count - 1)).roundToInt().coerceIn(0, count - 1)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(count, travelPx, thumbPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val pressed = indexAt(down.position.x)
                        if (pressed != indexState.value) {
                            onIndexChangeState.value(pressed)
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            change.consume()
                            if (change.changedToUp() || !change.pressed) break
                            if (!change.positionChanged()) continue
                            val next = indexAt(change.position.x)
                            if (next != indexState.value) {
                                onIndexChangeState.value(next)
                            }
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(8.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = trackColor,
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                        val filled = size.width * animated
                        if (filled > 0f) {
                            drawRoundRect(
                                color = fillColor,
                                size = Size(filled, size.height),
                                cornerRadius = CornerRadius(size.height / 2f),
                            )
                        }
                        if (count <= 1) return@drawBehind
                        val inset = size.height
                        val span = size.width - inset * 2f
                        val pip = size.height * 0.28f
                        repeat(count) { i ->
                            val cx = inset + span * i / (count - 1)
                            drawCircle(
                                color = notchColor,
                                radius = pip,
                                center = Offset(cx, size.height / 2f),
                            )
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = travel * animated)
                    .size(thumb)
                    .background(accent, RoundedCornerShape(50)),
            )
        }
    }
}

/**
 * Dashed seat outlines for a page with nothing on it.
 *
 * Without this the adjuster is useless exactly when it is most needed — a fresh install
 * has an empty desktop, so there would be no icon whose size could be judged. Drawing the
 * grid the new size produces gives the change something to show.
 */
@Composable
fun MinimalLauncherIconSizeGhostSeats(
    pageWidthPx: Float,
    pageHeightPx: Float,
    showDesktopLabels: Boolean,
    isDarkMode: Boolean,
    sizeStep: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val grid = remember(pageWidthPx, pageHeightPx, density, showDesktopLabels, sizeStep) {
        minimalLauncherSeatGrid(
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            density = density,
            showDesktopLabels = showDesktopLabels,
            sizeStep = sizeStep,
        )
    } ?: return
    val color = if (isDarkMode) {
        Color.White.copy(alpha = 0.22f)
    } else {
        Color.Black.copy(alpha = 0.18f)
    }
    val strokePx = with(LocalDensity.current) { 1.5.dp.toPx() }
    val radiusPx = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val seatW = grid.seatW * size.width
                val seatH = grid.seatH * size.height
                grid.seats().forEach { seat ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(seat.x * size.width, seat.y * size.height),
                        size = Size(seatW, seatH),
                        cornerRadius = CornerRadius(radiusPx),
                        style = Stroke(width = strokePx),
                    )
                }
            },
    )
}
