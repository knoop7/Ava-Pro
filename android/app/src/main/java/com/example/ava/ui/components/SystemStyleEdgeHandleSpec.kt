package com.example.ava.ui.components

import android.content.Context
import kotlin.math.min

/**
 * Shared SystemUI GlobalGestureAnimationView constants + morph math.
 * Used by both Compose [SystemStyleEdgeHandle] and View [SystemStyleEdgeHandleView]
 * so home / settings / WebView stay pixel-consistent.
 */
object SystemStyleEdgeHandleSpec {
    const val REF_STROKE_DP = 8f
    const val REF_HEIGHT_DP = 106f
    const val REF_MIN_HEIGHT_DP = 60f
    const val REF_OFFSET_DP = 10f
    const val REF_HIT_DP = 30f
    /** Extra inward grab so left and right stay reachable past OEM edge-gesture windows. */
    const val HIT_WIDTH_EXPANSION_DP = 18f
    const val HIT_VERTICAL_PAD_DP = 30f
    const val REF_THRESHOLD_DONE_DP = 60f
    /** SystemUI THRESHOLD_VERTICAL_UI_DONE = AppCompatTheme_windowNoTitle (= 108). */
    const val REF_THRESHOLD_ARROW_END_DP = 108f

    const val PRESENT_ALPHA = 0.5f
    const val PRESENT_MS = 300L
    const val HIDE_MS = 200L
    const val AUTO_HIDE_MS = 3_000L

    private const val REF_SHORTEST_SIDE_DP = 800f
    private const val MIN_SHORTEST_SIDE_DP = 320f
    private const val MIN_SCALE = 0.72f

    fun scaleForShortestSide(shortestSideDp: Int): Float {
        val sw = shortestSideDp.toFloat()
        if (sw >= REF_SHORTEST_SIDE_DP) return 1f
        val t = ((sw - MIN_SHORTEST_SIDE_DP) / (REF_SHORTEST_SIDE_DP - MIN_SHORTEST_SIDE_DP))
            .coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return (MIN_SCALE + (1f - MIN_SCALE) * eased).coerceIn(MIN_SCALE, 1f)
    }

    fun scaleFor(context: Context): Float {
        val cfg = context.resources.configuration
        return scaleForShortestSide(minOf(cfg.screenWidthDp, cfg.screenHeightDp))
    }

    fun expandedHitWidthPx(context: Context): Float {
        val density = context.resources.displayMetrics.density
        return pxMetrics(context).hitWidthPx + HIT_WIDTH_EXPANSION_DP * density
    }

    fun expandedHitHeightPx(context: Context): Float {
        val density = context.resources.displayMetrics.density
        return pxMetrics(context).pivotHeightPx + 2f * HIT_VERTICAL_PAD_DP * density
    }

    fun isSidebarEdgeHit(
        x: Float,
        y: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        rightEdge: Boolean,
        hitWidthPx: Float,
        hitHeightPx: Float,
    ): Boolean {
        val width = hitWidthPx.coerceAtLeast(1f)
        val height = hitHeightPx.coerceAtLeast(1f)
        val top = (screenHeightPx - height) / 2f
        if (y < top || y > top + height) return false
        return if (rightEdge) {
            x >= screenWidthPx - width
        } else {
            x <= width
        }
    }

    fun pxMetrics(context: Context): SystemStyleEdgeHandlePxMetrics {
        val density = context.resources.displayMetrics.density
        val scale = scaleFor(context)
        fun px(refDp: Float) = refDp * scale * density
        return SystemStyleEdgeHandlePxMetrics(
            scale = scale,
            strokeWidthPx = px(REF_STROKE_DP),
            pivotHeightPx = px(REF_HEIGHT_DP),
            pivotMinHeightPx = px(REF_MIN_HEIGHT_DP),
            edgeOffsetPx = px(REF_OFFSET_DP),
            hitWidthPx = (REF_HIT_DP * scale * density).coerceAtLeast(26f * density),
            thresholdDonePx = px(REF_THRESHOLD_DONE_DP),
            thresholdArrowEndPx = px(REF_THRESHOLD_ARROW_END_DP),
        )
    }

    /**
     * Exact SystemUI [GlobalGestureAnimationView.updateDistance] mapping.
     * @return pivotHeightPx to pivotControlX (edge-offset space, before +stroke/2)
     */
    /**
     * Multiplier for handle draw alpha while the drawer opens.
     * 0 drawer → 1 (full present); 1 drawer → 0 (fully hidden).
     */
    fun openFade(drawerOpenFraction: Float): Float =
        (1f - drawerOpenFraction.coerceIn(0f, 1f))

    fun morphPivot(
        distancePx: Float,
        maxHeightPx: Float,
        minHeightPx: Float,
        edgeOffsetPx: Float,
        thresholdDonePx: Float,
        thresholdArrowEndPx: Float,
    ): Pair<Float, Float> {
        val heightInterval = maxHeightPx - minHeightPx
        val thresholdInterval = (thresholdArrowEndPx - thresholdDonePx).coerceAtLeast(1f)
        val distance = distancePx.coerceAtLeast(0f)
        if (distance <= 0f) {
            return maxHeightPx to edgeOffsetPx
        }
        if (distance <= thresholdDonePx) {
            val height = maxHeightPx - heightInterval * (distance / thresholdDonePx)
            return height to edgeOffsetPx
        }
        val t = min(
            (min(distance, thresholdArrowEndPx) - thresholdDonePx) / thresholdInterval,
            1f,
        )
        val height = minHeightPx
        val controlX = (t * height / 4f) + edgeOffsetPx
        return height to controlX
    }
}

data class SystemStyleEdgeHandlePxMetrics(
    val scale: Float,
    val strokeWidthPx: Float,
    val pivotHeightPx: Float,
    val pivotMinHeightPx: Float,
    val edgeOffsetPx: Float,
    val hitWidthPx: Float,
    val thresholdDonePx: Float,
    val thresholdArrowEndPx: Float,
)
