package com.example.ava.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Immutable
data class AdaptiveSpec(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val shortestSideDp: Int,
    val compact: Boolean,
    val medium: Boolean,
    val expanded: Boolean,
    val contentScale: Float,
    val panelScale: Float,
    val controlScale: Float
)

@Composable
fun rememberAdaptiveSpec(): AdaptiveSpec {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val shortestSideDp = minOf(screenWidthDp, screenHeightDp)
    return remember(screenWidthDp, screenHeightDp) {
        when {
            shortestSideDp < 360 -> AdaptiveSpec(
                screenWidthDp = screenWidthDp,
                screenHeightDp = screenHeightDp,
                shortestSideDp = shortestSideDp,
                compact = true,
                medium = false,
                expanded = false,
                contentScale = 0.92f,
                panelScale = 0.92f,
                controlScale = 0.76f
            )

            shortestSideDp < 420 || screenWidthDp < 520 -> AdaptiveSpec(
                screenWidthDp = screenWidthDp,
                screenHeightDp = screenHeightDp,
                shortestSideDp = shortestSideDp,
                compact = false,
                medium = true,
                expanded = false,
                contentScale = 1.0f,
                panelScale = 1.0f,
                controlScale = 0.92f
            )

            screenWidthDp < 840 -> AdaptiveSpec(
                screenWidthDp = screenWidthDp,
                screenHeightDp = screenHeightDp,
                shortestSideDp = shortestSideDp,
                compact = false,
                medium = false,
                expanded = true,
                contentScale = 1.04f,
                panelScale = 1.03f,
                controlScale = 0.96f
            )

            else -> AdaptiveSpec(
                screenWidthDp = screenWidthDp,
                screenHeightDp = screenHeightDp,
                shortestSideDp = shortestSideDp,
                compact = false,
                medium = false,
                expanded = true,
                contentScale = 1.08f,
                panelScale = 1.05f,
                controlScale = 0.9f
            )
        }
    }
}

/**
 * Square panels whose longer pixel edge is at most 680 (480×480, 680×680).
 * Pulled-out sheets fill that screen. Larger and non-square screens do not.
 *
 * Uses the logical real size, not the app rect. A 320×320 kiosk is often a
 * square crop of a 480×272 mode; after the status and nav bars the app rect
 * is about 320×266 (ratio ~1.20) and used to be classified as landscape.
 */
@Composable
fun rememberCompactSquareScreen(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    return remember(widthDp, heightDp) {
        isCompactSquareDisplay(context, widthDp, heightDp)
    }
}

internal fun isCompactSquareDisplay(context: Context, widthDp: Int, heightDp: Int): Boolean {
    val real = realDisplayPixels(context)
    if (isCompactSquarePixels(real.first, real.second)) return true
    val density = context.resources.displayMetrics.density.coerceAtLeast(0.75f)
    val w = (widthDp * density).toInt().coerceAtLeast(1)
    val h = (heightDp * density).toInt().coerceAtLeast(1)
    return isCompactSquarePixels(maxOf(w, h), minOf(w, h))
}

/**
 * Landscape layout. Compact squares stay portrait.
 *
 * This panel's logical size is 320×320, but Configuration is
 * `w426dp h354dp port`: height is the inset app size (~266px) while width
 * stays the full side. `width > height` and even `width > height * 1.2`
 * (426/354 ≈ 1.20) both look like landscape. Orientation stays portrait.
 */
@Composable
fun rememberPaneIsLandscape(): Boolean {
    if (rememberCompactSquareScreen()) return false
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    val height = configuration.screenHeightDp.coerceAtLeast(1)
    if (width <= height * 1.2f) return false
    if (configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
        width <= height * 1.28f
    ) {
        return false
    }
    return true
}

internal fun isCompactSquarePixels(longest: Int, shortest: Int): Boolean {
    if (longest > 680 || shortest < 1) return false
    if (longest.toFloat() / shortest <= 1.12f) return true
    // Status + nav on a square panel (320×320 → app about 320×266).
    return longest - shortest <= 80 && shortest >= (longest * 0.78f).toInt()
}

private fun realDisplayPixels(context: Context): Pair<Int, Int> {
    val metrics = DisplayMetrics()
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        wm.defaultDisplay.getRealMetrics(metrics)
    } else {
        wm.defaultDisplay.getMetrics(metrics)
    }
    return metrics.widthPixels to metrics.heightPixels
}
