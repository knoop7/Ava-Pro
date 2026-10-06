package com.example.ava.appwindow

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Remembered position and size (device px) for the single global app-window
 * overlay. Portrait and landscape each keep their own slot, so rotating the
 * device restores the frame the user arranged for that orientation instead of
 * carrying over a half-off-screen one.
 *
 * Defaults adapt to the real screen (same real-metrics probing as the weather
 * overlay): the window is centered, portrait-leaning, never spans the full
 * height, and keeps at least 20dp of breathing room from every edge (growing
 * gently on larger screens) so resize/drag handles stay reachable and the
 * system swipe-up gesture keeps working. Square or otherwise odd screens are
 * handled by fitting inside the available box instead of forcing 9:16.
 *
 * Only defaults avoid the edges — frames the user deliberately drags flush to
 * an edge are kept there; clamping only prevents a frame from being lost
 * fully off-screen.
 */
object AppWindowGeometryStore {
    private const val PREFS = "app_window_geometry"
    private const val MIN_SIDE_PX = 240

    data class Geometry(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun orientationSuffix(context: Context): String =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "_land"
        } else {
            "_port"
        }

    /**
     * Per-window, per-orientation key suffix. [key] (typically the mirrored
     * package name) keeps every floating window's frame independent; an empty
     * key preserves the original single-slot layout for back-compat.
     */
    private fun suffix(context: Context, key: String): String {
        val orientation = orientationSuffix(context)
        return if (key.isBlank()) orientation else "_$key$orientation"
    }

    /** Full physical screen size (like the weather overlay's getRealMetrics). */
    private fun realScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(dm)
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    return dm.widthPixels to dm.heightPixels
                }
            }
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Whether [key] already has a saved frame for the current orientation. */
    fun hasSaved(context: Context, key: String = ""): Boolean =
        prefs(context).contains("w${suffix(context, key)}")

    /** Geometry for the current orientation; defaults are screen-adaptive. */
    fun read(context: Context, key: String = ""): Geometry {
        val p = prefs(context)
        val (screenW, screenH) = realScreenSize(context)
        val density = context.resources.displayMetrics.density
        val shortest = min(screenW, screenH)

        // Edge avoidance: at least 20dp on every side, ~2.5% of the shortest
        // side on physically larger screens, capped at 48dp.
        val inset = max((20f * density).toInt(), (shortest * 0.025f).toInt())
            .coerceAtMost((48f * density).toInt())
        val availW = (screenW - 2 * inset).coerceAtLeast(MIN_SIDE_PX)
        val availH = (screenH - 2 * inset).coerceAtLeast(MIN_SIDE_PX)

        // Larger screens can afford a slightly larger default window.
        val shortestDp = shortest / density
        val heightFraction = when {
            shortestDp >= 720f -> 0.72f
            shortestDp >= 480f -> 0.66f
            else -> 0.6f
        }
        var defH = (availH * heightFraction).toInt().coerceAtLeast(MIN_SIDE_PX)
        var defW = (defH * 9 / 16).coerceAtLeast(MIN_SIDE_PX)
        if (defW > availW) {
            // Square-ish or unusually narrow screens: fit by width instead.
            defW = availW
            defH = (defW * 16 / 9).coerceAtMost(availH)
        }

        val s = suffix(context, key)
        val w = p.getInt("w$s", defW).coerceIn(MIN_SIDE_PX, screenW)
        val h = p.getInt("h$s", defH).coerceIn(MIN_SIDE_PX, screenH)
        val x = p.getInt("x$s", (screenW - w) / 2).coerceIn(0, (screenW - w).coerceAtLeast(0))
        val y = p.getInt("y$s", (screenH - h) / 2).coerceIn(0, (screenH - h).coerceAtLeast(0))
        return Geometry(x, y, w, h)
    }

    fun save(context: Context, key: String = "", g: Geometry) {
        val s = suffix(context, key)
        prefs(context).edit()
            .putInt("x$s", g.x)
            .putInt("y$s", g.y)
            .putInt("w$s", g.width)
            .putInt("h$s", g.height)
            .apply()
    }
}
