package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.ava.ui.components.SystemStyleEdgeHandleSpec
import com.example.ava.ui.components.rememberSystemStyleEdgeHandleMetrics
import com.example.ava.ui.screens.settings.DarkBackground
import com.example.ava.ui.screens.settings.PureWhiteBackground

/**
 * Stops ModalBottomSheet handle bounce when a nested scroller is flung to its top/bottom.
 *
 * Leftover fling velocity otherwise leaks into the sheet's anchored-draggable spring and
 * the drag handle jitters up/down. Only [onPostFling] is consumed so drag-to-dismiss
 * (onPostScroll) still works when the list is already at the top.
 *
 * Apply with `Modifier.nestedScroll(rememberModalSheetScrollFlingGuard())` on the
 * height-capped container that wraps the scrollable content.
 */
@Composable
fun rememberModalSheetScrollFlingGuard(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            available
    }
}

/** Same transparent → surface fade used behind the main settings bottom drawer handle. */
fun bottomSheetFadeBrush(isDarkMode: Boolean, fadeToColor: Color? = null): Brush =
    Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            fadeToColor ?: if (isDarkMode) DarkBackground else PureWhiteBackground
        )
    )

/** Horizontal mirror of [bottomSheetFadeBrush] — transparent at the left edge, solid toward content. */
fun leftSheetFadeBrush(isDarkMode: Boolean, fadeToColor: Color? = null): Brush =
    Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            fadeToColor ?: if (isDarkMode) DarkBackground else PureWhiteBackground
        )
    )

/** Inverse of [leftSheetFadeBrush] — solid on the drawer side, transparent toward main content. */
fun leftSheetFadeBrushMirrored(isDarkMode: Boolean, fadeFromColor: Color? = null): Brush =
    Brush.horizontalGradient(
        colors = listOf(
            fadeFromColor ?: if (isDarkMode) DarkBackground else PureWhiteBackground,
            Color.Transparent
        )
    )

/** Header/list seam only — shorter and lighter than [SettingsTopFadeOverlay] / handle scrims. */
private val SettingsNavBarEdgeFadeHeight = 10.dp
private const val SettingsNavBarEdgeFadePeakAlpha = 0.32f

/**
 * Soften the nav-bar / list join. Draw-only: no layout space and no extra hit target.
 * Apply after header [androidx.compose.foundation.layout.padding] so the band sits on the list.
 */
fun Modifier.settingsNavBarEdgeFade(color: Color): Modifier = this.drawWithContent {
    drawContent()
    val fadePx = SettingsNavBarEdgeFadeHeight.toPx()
    if (!fadePx.isFinite() || fadePx < 1f || size.height < 1f || size.width < 1f) return@drawWithContent
    val h = fadePx.coerceAtMost(size.height)
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = SettingsNavBarEdgeFadePeakAlpha),
                0.50f to color.copy(alpha = 0.10f),
                1f to Color.Transparent,
            ),
            startY = 0f,
            endY = h,
        ),
        size = Size(size.width, h),
    )
}

/** Inverse of [bottomSheetFadeBrush] — surface → transparent for top edge scrims. */
fun topSheetFadeBrush(
    isDarkMode: Boolean,
    fadeFromColor: Color? = null,
    deep: Boolean = false
): Brush {
    val from = fadeFromColor ?: if (isDarkMode) DarkBackground else PureWhiteBackground
    return if (deep) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to from,
                0.55f to from,
                0.9f to from.copy(alpha = 0.85f),
                1f to Color.Transparent
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(from, Color.Transparent)
        )
    }
}

/**
 * Top fade scrim — overlays a scrolling list without reserving layout space.
 * Solid at the top edge, fading to transparent downward (inverse of [BottomSheetHandle]).
 */
@Composable
fun SettingsTopFadeOverlay(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    fadeFromColor: Color? = null,
    fadeHeight: Dp = 60.dp,
    alpha: Float = 1f,
    deepFade: Boolean = false
) {
    if (alpha <= 0f) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fadeHeight)
            .graphicsLayer { this.alpha = alpha }
            .background(topSheetFadeBrush(isDarkMode, fadeFromColor, deep = deepFade))
    )
}

/**
 * Bottom fade scrim from settings — floats content above a scrolling list without a hard cut.
 * [fadeHeight] is the transparent-to-solid transition band above [content].
 */
@Composable
fun SettingsBottomFadeOverlay(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    fadeToColor: Color? = null,
    fadeHeight: Dp = 60.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bottomSheetFadeBrush(isDarkMode, fadeToColor))
            .padding(top = fadeHeight),
        content = content
    )
}

@Composable
fun BottomSheetHandle(
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradientHeight: Dp = 60.dp,
    /**
     * When null, length tracks sidebar pivot at [SHEET_HANDLE_LENGTH_SCALE];
     * thickness is ~5dp (slightly above the old 4dp pill).
     */
    handleWidth: Dp? = null,
    handleHeight: Dp? = null,
    /** Settings keep the edge fade; overlay surfaces use pill-only (no scrim). */
    showGradient: Boolean = true,
) {
    val edgeMetrics = rememberSystemStyleEdgeHandleMetrics()
    val resolvedWidth = handleWidth ?: (edgeMetrics.pivotHeight * SHEET_HANDLE_LENGTH_SCALE)
    val resolvedHeight = handleHeight ?: (edgeMetrics.strokeWidth * SHEET_HANDLE_THICKNESS_SCALE)
    val handleColor = if (isDarkMode) {
        Color.White.copy(alpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
    } else {
        Color.Black.copy(alpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(gradientHeight)
            .then(
                if (showGradient) {
                    Modifier.background(bottomSheetFadeBrush(isDarkMode))
                } else {
                    Modifier
                }
            )
            .navigationBarsPadding()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(resolvedWidth)
                .height(resolvedHeight)
                .clip(RoundedCornerShape(resolvedHeight / 2))
                .background(handleColor)
        )
    }
}

/**
 * Left-edge pull handle — visual mirror of [BottomSheetHandle] (vertical pill + optional edge fade).
 * Set [showGradient] to false for pill-only (no fade background).
 */
@Composable
fun LeftSheetHandle(
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradientWidth: Dp = 60.dp,
    handleWidth: Dp? = null,
    handleHeight: Dp? = null,
    mirrored: Boolean = false,
    showGradient: Boolean = true
) {
    val edgeMetrics = rememberSystemStyleEdgeHandleMetrics()
    val resolvedWidth = handleWidth ?: (edgeMetrics.strokeWidth * SHEET_HANDLE_THICKNESS_SCALE)
    val resolvedHeight = handleHeight ?: (edgeMetrics.pivotHeight * SHEET_HANDLE_LENGTH_SCALE)
    val handleColor = if (isDarkMode) {
        Color.White.copy(alpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
    } else {
        Color.Black.copy(alpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
    }
    Box(
        modifier = modifier
            .width(gradientWidth)
            .fillMaxHeight()
            .then(
                if (showGradient) {
                    Modifier.background(
                        if (mirrored) {
                            leftSheetFadeBrushMirrored(isDarkMode)
                        } else {
                            leftSheetFadeBrush(isDarkMode)
                        }
                    )
                } else {
                    Modifier
                }
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(resolvedWidth)
                .height(resolvedHeight)
                .clip(RoundedCornerShape(resolvedWidth / 2))
                .background(handleColor)
        )
    }
}

/** Horizontal length vs sidebar pivot (106dp). */
private const val SHEET_HANDLE_LENGTH_SCALE = 0.80f

/**
 * Thickness vs sidebar stroke (8dp). Was 0.80 → 6.4dp (too thick); old pill was 4dp.
 * 5/8 → ~5dp at scale 1 — slightly above the old pill.
 */
private const val SHEET_HANDLE_THICKNESS_SCALE = 5f / 8f


@Composable
fun ModalSheetDragHandle(
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isDarkMode) Color(0xFF4B5563) else Color(0xFFD1D5DB)),
        )
    }
}
