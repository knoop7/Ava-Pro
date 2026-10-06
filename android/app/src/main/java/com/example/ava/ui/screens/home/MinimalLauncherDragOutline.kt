package com.example.ava.ui.screens.home

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Launcher3 [HolographicOutlineHelper.applyExpensiveOutlineWithBlur] —
 * white glowing stroke that follows icon alpha (round icons → circular line).
 */
fun createHolographicIconOutline(
    source: Bitmap,
    density: Float,
): Bitmap? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val mediumBlur = 2f * density
    val thinBlur = 1f * density
    val pad = ((mediumBlur * 2f) + 2f).toInt().coerceAtLeast(4)
    val w = source.width + pad * 2
    val h = source.height + pad * 2

    val stamped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(stamped).drawBitmap(source, pad.toFloat(), pad.toFloat(), null)

    // L3: punch out soft edges (alpha < 188 → 0) so the ring is crisp.
    val pixels = IntArray(w * h)
    stamped.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        if ((pixels[i] ushr 24) < 188) pixels[i] = 0
    }
    stamped.setPixels(pixels, 0, w, 0, 0, w, h)

    val solidAlpha = stamped.extractAlpha() ?: run {
        stamped.recycle()
        return null
    }

    val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    blurPaint.maskFilter = BlurMaskFilter(mediumBlur, BlurMaskFilter.Blur.OUTER)
    val mediumOff = IntArray(2)
    val mediumOuter = solidAlpha.extractAlpha(blurPaint, mediumOff) ?: run {
        solidAlpha.recycle()
        stamped.recycle()
        return null
    }

    blurPaint.maskFilter = BlurMaskFilter(thinBlur, BlurMaskFilter.Blur.OUTER)
    val thinOff = IntArray(2)
    val thinOuter = solidAlpha.extractAlpha(blurPaint, thinOff) ?: run {
        mediumOuter.recycle()
        solidAlpha.recycle()
        stamped.recycle()
        return null
    }

    blurPaint.maskFilter = BlurMaskFilter(mediumBlur, BlurMaskFilter.Blur.NORMAL)
    val innerOff = IntArray(2)
    val inner = solidAlpha.extractAlpha(blurPaint, innerOff) ?: run {
        thinOuter.recycle()
        mediumOuter.recycle()
        solidAlpha.recycle()
        stamped.recycle()
        return null
    }

    val erase = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    val innerCanvas = Canvas(inner)
    innerCanvas.drawBitmap(
        solidAlpha,
        -innerOff[0].toFloat(),
        -innerOff[1].toFloat(),
        erase,
    )
    innerCanvas.drawRect(0f, 0f, -innerOff[0].toFloat(), inner.height.toFloat(), erase)
    innerCanvas.drawRect(0f, 0f, inner.width.toFloat(), -innerOff[1].toFloat(), erase)

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val out = Canvas(result)
    out.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val draw = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    out.drawBitmap(inner, innerOff[0].toFloat(), innerOff[1].toFloat(), draw)
    out.drawBitmap(mediumOuter, mediumOff[0].toFloat(), mediumOff[1].toFloat(), draw)
    out.drawBitmap(thinOuter, thinOff[0].toFloat(), thinOff[1].toFloat(), draw)

    thinOuter.recycle()
    mediumOuter.recycle()
    inner.recycle()
    solidAlpha.recycle()
    stamped.recycle()
    return result
}
