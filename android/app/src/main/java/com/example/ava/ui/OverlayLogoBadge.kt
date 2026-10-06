package com.example.ava.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared top-end HA / Music Assistant watermark sizing and insets.
 * Edge policy matches [DashboardOverlayChrome] and [OverlayImmersive] (5% vmin, dp floor).
 */
object OverlayLogoBadge {
    /** ~vmin×13%; phones ~47dp @360, tablets keep growing until max. */
    const val SIZE_VMIN_FRACTION = 0.13f
    const val SIZE_MIN_DP = 40f
    const val SIZE_MAX_DP = 72f
    const val EDGE_VMIN_FRACTION = 0.05f
    const val EDGE_MIN_DP = 32f
    const val ALPHA = 64

    data class LayoutPx(
        val sizePx: Int,
        val marginEndPx: Int,
        val marginTopPx: Int,
    )

    data class LayoutDp(
        val sizeDp: Dp,
        val edgeInsetDp: Dp,
    )

    fun layoutPx(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        cutoutTopPx: Int = 0,
        cutoutEndPx: Int = 0,
        portraitTopExtraDp: Float = 0f,
    ): LayoutPx {
        if (widthPx <= 0 || heightPx <= 0) {
            return LayoutPx(0, 0, 0)
        }
        val vmin = min(widthPx, heightPx).toFloat()
        val safeDensity = density.coerceAtLeast(0.75f)
        val sizePx = (vmin * SIZE_VMIN_FRACTION).roundToInt()
            .coerceIn(
                (SIZE_MIN_DP * safeDensity).roundToInt(),
                (SIZE_MAX_DP * safeDensity).roundToInt()
            )
        val edgeBasePx = (vmin * EDGE_VMIN_FRACTION).roundToInt()
        val edgeMinPx = (EDGE_MIN_DP * safeDensity).roundToInt()
        val marginEndPx = max(edgeBasePx, max(cutoutEndPx, edgeMinPx))
        val marginTopPx = max(edgeBasePx, max(cutoutTopPx, edgeMinPx)) +
            (portraitTopExtraDp * safeDensity).roundToInt()
        return LayoutPx(sizePx, marginEndPx, marginTopPx)
    }

    fun layoutPx(view: View, portraitTopExtraDp: Float = 0f): LayoutPx {
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.rootWindowInsets?.displayCutout
        } else {
            null
        }
        val density = view.resources.displayMetrics.density
        return layoutPx(
            widthPx = view.width,
            heightPx = view.height,
            density = density,
            cutoutTopPx = cutout?.safeInsetTop ?: 0,
            cutoutEndPx = cutout?.safeInsetRight ?: 0,
            portraitTopExtraDp = portraitTopExtraDp,
        )
    }

    fun destRect(widthPx: Int, layout: LayoutPx): Rect {
        val iconRight = widthPx - layout.marginEndPx
        val iconTop = layout.marginTopPx
        return Rect(iconRight - layout.sizePx, iconTop, iconRight, iconTop + layout.sizePx)
    }

    fun draw(
        canvas: Canvas,
        widthPx: Int,
        bitmap: Bitmap,
        layout: LayoutPx,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = ALPHA },
    ) {
        if (layout.sizePx <= 0) return
        canvas.drawBitmap(bitmap, null, destRect(widthPx, layout), paint)
    }

    fun loadHaLogo(context: android.content.Context): Bitmap? {
        return try {
            context.assets.open("ha_logo.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    @Composable
    fun rememberLayoutDp(): LayoutDp {
        val configuration = LocalConfiguration.current
        val vminDp = min(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
        return remember(vminDp) {
            val edgeDp = max(vminDp * EDGE_VMIN_FRACTION, EDGE_MIN_DP)
            val sizeDp = (vminDp * SIZE_VMIN_FRACTION).coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
            LayoutDp(sizeDp.dp, edgeDp.dp)
        }
    }
}
