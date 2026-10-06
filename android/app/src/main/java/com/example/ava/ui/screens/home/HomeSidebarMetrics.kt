package com.example.ava.ui.screens.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared home/browser sidebar sizing.
 *
 * Phone / small square (e.g. 480×480) stay near base sizes. Landscape smart-display
 * panels like X08A (1280×800 @ mdpi) are physically large but low-dpi — the old
 * ~1.08× tier left menu text looking tiny; those devices get a clearer bump.
 */
object HomeSidebarMetrics {
    private const val BASE_DRAWER_WIDTH_DP = 288f
    /** Allow room for the larger X08A-class type scale. */
    private const val MAX_DRAWER_WIDTH_DP = 400f
    private const val BASE_TITLE_SP = 28f
    private const val BASE_ENTRY_SP = 17f
    private const val BASE_ICON_DP = 22f
    private const val BASE_ENTRY_PADDING_HORIZONTAL_DP = 12f
    private const val BASE_ENTRY_PADDING_VERTICAL_DP = 14f
    private const val BASE_SWITCH_PADDING_VERTICAL_DP = 10f
    private const val BASE_ROW_CORNER_DP = 12f

    /**
     * @param density Android density (1.0 = mdpi). Large mdpi landscape panels need
     * extra boost because dp maps nearly 1:1 to px on a wide physical screen.
     */
    fun textScale(
        shortestSideDp: Int,
        longestSideDp: Int,
        isLandscape: Boolean,
        density: Float = 1f,
    ): Float {
        // Stepped so larger panels keep getting larger type; phone / 480 stay at 1f.
        val tierScale = when {
            shortestSideDp >= 1440 || longestSideDp >= 2560 -> 1.36f
            shortestSideDp >= 960 || longestSideDp >= 1920 -> 1.28f
            // X08A-class landscape smart displays (sw≈800) — was looking tiny at ~1.08×.
            isLandscape && shortestSideDp >= 720 -> 1.38f
            shortestSideDp >= 600 || longestSideDp >= 1280 -> 1.16f
            else -> 1f
        }
        // mdpi/hdpi- landscape panels map dp≈px on a wide physical screen → extra boost.
        val lowDpiLandscapeBoost =
            if (isLandscape && shortestSideDp >= 720 && density <= 1.15f) 1.10f else 1f
        val smallLandscapeBoost =
            if (isLandscape && shortestSideDp < 720) 1.04f else 1f
        return tierScale * lowDpiLandscapeBoost * smallLandscapeBoost
    }

    /** Drawer width grows with the panel; still capped so it does not dominate the canvas. */
    fun drawerWidthScale(
        shortestSideDp: Int,
        longestSideDp: Int,
        isLandscape: Boolean = false,
    ): Float = when {
        shortestSideDp >= 1440 || longestSideDp >= 2560 -> 1.22f
        shortestSideDp >= 960 || longestSideDp >= 1920 -> 1.16f
        isLandscape && shortestSideDp >= 720 -> 1.28f
        shortestSideDp >= 600 || longestSideDp >= 1280 -> 1.12f
        else -> 1f
    }

    fun drawerWidthDp(
        shortestSideDp: Int,
        longestSideDp: Int,
        isLandscape: Boolean = false,
    ): Float {
        val scaled = BASE_DRAWER_WIDTH_DP * drawerWidthScale(
            shortestSideDp = shortestSideDp,
            longestSideDp = longestSideDp,
            isLandscape = isLandscape,
        )
        return scaled.coerceAtMost(MAX_DRAWER_WIDTH_DP)
    }

    fun titleTextSize(scale: Float): TextUnit = (BASE_TITLE_SP * scale).sp

    fun entryTextSize(scale: Float): TextUnit = (BASE_ENTRY_SP * scale).sp

    fun iconSize(scale: Float): Dp = (BASE_ICON_DP * scale).dp

    fun entryPaddingHorizontal(scale: Float): Dp = (BASE_ENTRY_PADDING_HORIZONTAL_DP * scale).dp

    fun entryPaddingVertical(scale: Float): Dp = (BASE_ENTRY_PADDING_VERTICAL_DP * scale).dp

    fun switchPaddingVertical(scale: Float): Dp = (BASE_SWITCH_PADDING_VERTICAL_DP * scale).dp

    fun rowCornerRadius(scale: Float): Dp = (BASE_ROW_CORNER_DP * scale).dp
}

@Composable
fun rememberHomeSidebarTextScale(): Float {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return HomeSidebarMetrics.textScale(
        shortestSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp),
        longestSideDp = maxOf(configuration.screenWidthDp, configuration.screenHeightDp),
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        density = density,
    )
}

@Composable
fun homeSidebarDrawerWidth(): Dp {
    val configuration = LocalConfiguration.current
    return HomeSidebarMetrics.drawerWidthDp(
        shortestSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp),
        longestSideDp = maxOf(configuration.screenWidthDp, configuration.screenHeightDp),
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    ).dp
}
