package com.example.ava.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Capture the desktop under overview and apply a light gaussian (stack) blur.
 *
 * - API 26+: [PixelCopy] of the window region under [view]
 * - API 21–25: [View.draw] software snapshot
 * - All APIs: [AmbientBitmapBlur] (Compose [androidx.compose.ui.draw.blur] is API 31+ only)
 */
object OverviewBackdropCapture {

    /** Slight frosted look — not a heavy glassmorphism wash. */
    private const val BLUR_MAX_EDGE_PX = 200
    private const val BLUR_RADIUS = 12

    /**
     * Software [View.draw] snapshot max edge. Full-screen ARGB_8888 on API 21–25
     * (no PixelCopy) is a large alloc on 1080p+ panels; drawing already-scaled is
     * cheaper and the later stack blur downsamples again anyway.
     */
    private const val SOFTWARE_CAPTURE_MAX_EDGE_PX = 360

    suspend fun captureBlurred(
        view: View,
        window: Window?,
        maxEdgePx: Int = BLUR_MAX_EDGE_PX,
        radius: Int = BLUR_RADIUS,
    ): Bitmap? {
        val raw = captureRaw(view, window) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                AmbientBitmapBlur.create(
                    source = raw,
                    maxEdgePx = maxEdgePx,
                    radius = radius,
                )
            } finally {
                if (!raw.isRecycled) raw.recycle()
            }
        }
    }

    /**
     * Scaled snapshot **without** stack blur. Liquid Glass re-blurs this when the
     * intensity slider moves, so the frost can follow the same control as API 31+.
     */
    suspend fun captureUnblurred(
        view: View,
        window: Window?,
        maxEdgePx: Int = 640,
    ): Bitmap? {
        val raw = captureRaw(view, window, drawMaxEdgePx = maxEdgePx) ?: return null
        return try {
            scaleDownToMaxEdge(raw, maxEdgePx)
        } catch (_: Exception) {
            if (!raw.isRecycled) raw.recycle()
            null
        }
    }

    private fun scaleDownToMaxEdge(source: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdgePx) return source
        val scale = maxEdgePx.toFloat() / longest
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        return scaled
    }

    private suspend fun captureRaw(
        view: View,
        window: Window?,
        drawMaxEdgePx: Int = SOFTWARE_CAPTURE_MAX_EDGE_PX,
    ): Bitmap? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window != null) {
            capturePixelCopy(window, view, width, height)
        } else {
            captureDraw(view, width, height, drawMaxEdgePx)
        }
    }

    private fun captureDraw(view: View, width: Int, height: Int, maxEdgePx: Int): Bitmap? {
        val scale = (maxEdgePx.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            if (scale != 1f) canvas.scale(scale, scale)
            view.draw(canvas)
            bitmap
        } catch (_: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        }
    }

    private suspend fun capturePixelCopy(
        window: Window,
        view: View,
        width: Int,
        height: Int,
    ): Bitmap? {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val srcRect = Rect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    srcRect,
                    bitmap,
                    { result ->
                        if (continuation.isActive) {
                            if (result == PixelCopy.SUCCESS) {
                                continuation.resume(bitmap)
                            } else {
                                if (!bitmap.isRecycled) bitmap.recycle()
                                continuation.resume(null)
                            }
                        } else if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
