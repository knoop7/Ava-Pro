package com.example.ava.fleet

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * Maps fleet-console taps onto the device display.
 *
 * Application/Service [WindowManager.currentWindowMetrics] often keep the
 * process-start orientation, so a landscape screencap is paired with portrait
 * `displayWidth/Height` and remote control misses. Prefer [Display.getRealSize]
 * (rotation-aware) and, if a live frame's landscape/portrait disagrees, swap
 * the reported size to match what the user actually clicked.
 */
object FleetScreenCoords {
    data class Size(val width: Int, val height: Int)

    data class PointPx(
        val x: Int,
        val y: Int,
        val displayWidth: Int,
        val displayHeight: Int,
    )

    /**
     * If [frameW]×[frameH] is landscape xor [displayW]×[displayH] is landscape,
     * swap the display size. Equal edges (square) are left alone.
     */
    fun alignWithFrame(displayW: Int, displayH: Int, frameW: Int, frameH: Int): Size {
        if (displayW <= 0 || displayH <= 0 || frameW <= 0 || frameH <= 0) {
            return Size(displayW, displayH)
        }
        val displayLandscape = displayW > displayH
        val frameLandscape = frameW > frameH
        return if (displayLandscape == frameLandscape) {
            Size(displayW, displayH)
        } else {
            Size(displayH, displayW)
        }
    }

    fun resolve(
        nx: Double?,
        ny: Double?,
        rawX: Int?,
        rawY: Int?,
        displayW: Int,
        displayH: Int,
        frameW: Int = 0,
        frameH: Int = 0,
    ): PointPx? {
        if (rawX == null && nx == null) return null
        if (rawY == null && ny == null) return null
        val aligned = alignWithFrame(displayW, displayH, frameW, frameH)
        val dw = aligned.width
        val dh = aligned.height
        val absX = when {
            nx != null && dw > 0 -> (nx.coerceIn(0.0, 1.0) * (dw - 1).coerceAtLeast(0)).toInt()
            rawX != null -> rawX
            else -> 0
        }
        val absY = when {
            ny != null && dh > 0 -> (ny.coerceIn(0.0, 1.0) * (dh - 1).coerceAtLeast(0)).toInt()
            rawY != null -> rawY
            else -> 0
        }
        val cx = if (dw > 0) absX.coerceIn(0, dw - 1) else absX.coerceAtLeast(0)
        val cy = if (dh > 0) absY.coerceIn(0, dh - 1) else absY.coerceAtLeast(0)
        return PointPx(cx, cy, dw, dh)
    }

    fun realDisplaySize(context: Context): Size {
        return try {
            val display = defaultDisplay(context)
            if (display != null) {
                val point = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                if (point.x > 0 && point.y > 0) return Size(point.x, point.y)
            }
            fallbackWindowSize(context)
        } catch (_: Exception) {
            fallbackWindowSize(context)
        }
    }

    private fun defaultDisplay(context: Context): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.let { return it }
        }
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.let { return it }
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    }

    private fun fallbackWindowSize(context: Context): Size {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Size(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                Size(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (_: Exception) {
            Size(0, 0)
        }
    }
}
