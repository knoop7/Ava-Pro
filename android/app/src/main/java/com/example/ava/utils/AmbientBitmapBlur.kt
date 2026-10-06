package com.example.ava.utils

import android.graphics.Bitmap
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compose [androidx.compose.ui.draw.blur] only works on API 31+ (RenderEffect).
 * Below that it is a no-op, so ambient cover backdrops look sharp.
 *
 * This helper builds a small software-blurred copy for pre-31 devices.
 */
object AmbientBitmapBlur {

    val supportsNativeComposeBlur: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Returns a new low-res blurred bitmap suitable as a full-screen ambient backdrop.
     * Caller owns the result. Do NOT [Bitmap.recycle] it while a Compose painter /
     * exit animation may still draw it — the copy is tiny, prefer letting GC collect.
     */
    fun create(
        source: Bitmap,
        maxEdgePx: Int = 72,
        radius: Int = 18,
    ): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val safeRadius = radius.coerceIn(1, 25)
        // Hardware bitmaps cannot be read by getPixels / reliable scaling on older APIs.
        val softSource = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            source
        }
        val scale = min(
            maxEdgePx.toFloat() / softSource.width.toFloat(),
            maxEdgePx.toFloat() / softSource.height.toFloat(),
        ).coerceAtMost(1f)
        val w = max(1, (softSource.width * scale).roundToInt())
        val h = max(1, (softSource.height * scale).roundToInt())
        val scaled = try {
            Bitmap.createScaledBitmap(softSource, w, h, true)
        } catch (_: Exception) {
            if (softSource !== source) softSource.recycle()
            return null
        }
        if (softSource !== source && softSource !== scaled) {
            softSource.recycle()
        }
        val working = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (scaled !== source) scaled.recycle()
            copy ?: return null
        }
        return try {
            stackBlurInPlace(working, safeRadius)
            working
        } catch (_: Exception) {
            if (working !== source) working.recycle()
            null
        }
    }

    /**
     * Mario Klingemann stack blur (in-place). Fast enough for tiny ambient bitmaps.
     */
    private fun stackBlurInPlace(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val div = radius + radius + 1
        val divsum = (div + 1) shr 1
        val divsumSq = divsum * divsum
        val dv = IntArray(256 * divsumSq) { it / divsumSq }

        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)

        val vmin = IntArray(max(w, h))
        val stack = IntArray(div * 3)

        var yi = 0
        for (y in 0 until h) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0

            for (i in -radius..radius) {
                val p = pixels[yi + min(w - 1, max(i, 0))]
                val sir = ((i + radius) * 3)
                stack[sir] = (p shr 16) and 0xff
                stack[sir + 1] = (p shr 8) and 0xff
                stack[sir + 2] = p and 0xff
                val rbs = radius + 1 - kotlin.math.abs(i)
                rSum += stack[sir] * rbs
                gSum += stack[sir + 1] * rbs
                bSum += stack[sir + 2] * rbs
                if (i > 0) {
                    rInSum += stack[sir]
                    gInSum += stack[sir + 1]
                    bInSum += stack[sir + 2]
                } else {
                    rOutSum += stack[sir]
                    gOutSum += stack[sir + 1]
                    bOutSum += stack[sir + 2]
                }
            }

            var stackPointer = radius
            for (x in 0 until w) {
                r[yi + x] = dv[rSum]
                g[yi + x] = dv[gSum]
                b[yi + x] = dv[bSum]

                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                val stackStart = ((stackPointer - radius + div) % div) * 3
                rOutSum -= stack[stackStart]
                gOutSum -= stack[stackStart + 1]
                bOutSum -= stack[stackStart + 2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, w - 1)
                }
                val p = pixels[yi + vmin[x]]
                stack[stackStart] = (p shr 16) and 0xff
                stack[stackStart + 1] = (p shr 8) and 0xff
                stack[stackStart + 2] = p and 0xff

                rInSum += stack[stackStart]
                gInSum += stack[stackStart + 1]
                bInSum += stack[stackStart + 2]

                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                val sir = stackPointer * 3
                rOutSum += stack[sir]
                gOutSum += stack[sir + 1]
                bOutSum += stack[sir + 2]

                rInSum -= stack[sir]
                gInSum -= stack[sir + 1]
                bInSum -= stack[sir + 2]
            }
            yi += w
        }

        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0
            var yp = -radius * w

            for (i in -radius..radius) {
                val yi2 = max(0, yp) + x
                val sir = ((i + radius) * 3)
                stack[sir] = r[yi2]
                stack[sir + 1] = g[yi2]
                stack[sir + 2] = b[yi2]
                val rbs = radius + 1 - kotlin.math.abs(i)
                rSum += r[yi2] * rbs
                gSum += g[yi2] * rbs
                bSum += b[yi2] * rbs
                if (i > 0) {
                    rInSum += stack[sir]
                    gInSum += stack[sir + 1]
                    bInSum += stack[sir + 2]
                } else {
                    rOutSum += stack[sir]
                    gOutSum += stack[sir + 1]
                    bOutSum += stack[sir + 2]
                }
                if (i < h - 1) yp += w
            }

            var yi3 = x
            var stackPointer = radius
            for (y in 0 until h) {
                pixels[yi3] = (pixels[yi3] and 0xff000000.toInt()) or
                    (dv[rSum] shl 16) or
                    (dv[gSum] shl 8) or
                    dv[bSum]

                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                val stackStart = ((stackPointer - radius + div) % div) * 3
                rOutSum -= stack[stackStart]
                gOutSum -= stack[stackStart + 1]
                bOutSum -= stack[stackStart + 2]

                if (x == 0) {
                    vmin[y] = min(y + radius + 1, h - 1) * w
                }
                val p = x + vmin[y]
                stack[stackStart] = r[p]
                stack[stackStart + 1] = g[p]
                stack[stackStart + 2] = b[p]

                rInSum += stack[stackStart]
                gInSum += stack[stackStart + 1]
                bInSum += stack[stackStart + 2]

                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                val sir = stackPointer * 3
                rOutSum += stack[sir]
                gOutSum += stack[sir + 1]
                bOutSum += stack[sir + 2]

                rInSum -= stack[sir]
                gInSum -= stack[sir + 1]
                bInSum -= stack[sir + 2]

                yi3 += w
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
