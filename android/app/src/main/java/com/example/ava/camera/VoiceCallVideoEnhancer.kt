package com.example.ava.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Subtle polish + soft sharpen for LAN voice-call JPEG frames.
 * Kept lightweight for ~480p @ 8fps on legacy devices.
 */
object VoiceCallVideoEnhancer {
    private const val MAX_PIXELS = 640 * 480
    private const val SATURATION = 1.07f
    private const val CONTRAST = 1.05f
    private const val BRIGHTNESS_OFFSET = 6f
    private const val UNSHARP_AMOUNT = 0.20f

    private const val DISPLAY_SATURATION = 1.04f
    private const val DISPLAY_CONTRAST = 1.03f
    private const val DISPLAY_BRIGHTNESS_OFFSET = 3f
    private const val DISPLAY_UNSHARP_AMOUNT = 0.12f

    /** Outgoing LAN video — full strength before JPEG encode. */
    fun enhanceForCapture(source: Bitmap): Bitmap = enhanceInternal(source, forDisplay = false)

    /** Incoming preview — lighter pass so it stacks safely with peer-side capture polish. */
    fun enhanceForDisplay(source: Bitmap): Bitmap = enhanceInternal(source, forDisplay = true)

    private fun enhanceInternal(source: Bitmap, forDisplay: Boolean): Bitmap {
        if (source.width * source.height > MAX_PIXELS) return source
        if (source.config != Bitmap.Config.ARGB_8888 && source.config != Bitmap.Config.RGB_565) {
            return source
        }
        val argb = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val polished = applyColorPolish(argb, forDisplay)
        val sharpened = applySoftUnsharp(polished, forDisplay)
        if (polished != sharpened) polished.recycle()
        if (argb != sharpened) argb.recycle()
        if (source != argb && source != sharpened) source.recycle()
        return sharpened
    }

    private fun applyColorPolish(source: Bitmap, forDisplay: Boolean): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(buildPolishMatrix(forDisplay))
        }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun buildPolishMatrix(forDisplay: Boolean): ColorMatrix {
        val saturation = if (forDisplay) DISPLAY_SATURATION else SATURATION
        val contrast = if (forDisplay) DISPLAY_CONTRAST else CONTRAST
        val brightness = if (forDisplay) DISPLAY_BRIGHTNESS_OFFSET else BRIGHTNESS_OFFSET
        val sat = ColorMatrix().apply { setSaturation(saturation) }
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + brightness
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        sat.postConcat(contrastMatrix)
        return sat
    }

    /** Cheap unsharp via downscale/upscale blur — stable on low-end SoCs. */
    private fun applySoftUnsharp(source: Bitmap, forDisplay: Boolean): Bitmap {
        val amount = if (forDisplay) DISPLAY_UNSHARP_AMOUNT else UNSHARP_AMOUNT
        if (amount <= 0f) return source
        val blur = fastDownUpBlur(source)
        val width = source.width
        val height = source.height
        val srcPixels = IntArray(width * height)
        val blurPixels = IntArray(width * height)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)
        blur.getPixels(blurPixels, 0, width, 0, 0, width, height)
        if (blur != source) blur.recycle()

        for (i in srcPixels.indices) {
            srcPixels[i] = unsharpPixel(srcPixels[i], blurPixels[i], amount)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(srcPixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun fastDownUpBlur(source: Bitmap): Bitmap {
        val div = if (source.width * source.height > 320 * 240) 4 else 3
        val smallW = (source.width / div).coerceAtLeast(1)
        val smallH = (source.height / div).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, source.width, source.height, true).also {
            if (small != it) small.recycle()
        }
    }

    private fun unsharpPixel(original: Int, blurred: Int, amount: Float): Int {
        val oA = Color.alpha(original)
        val oR = Color.red(original)
        val oG = Color.green(original)
        val oB = Color.blue(original)
        val bR = Color.red(blurred)
        val bG = Color.green(blurred)
        val bB = Color.blue(blurred)
        return Color.argb(
            oA,
            clampChannel(oR + ((oR - bR) * amount).roundToInt()),
            clampChannel(oG + ((oG - bG) * amount).roundToInt()),
            clampChannel(oB + ((oB - bB) * amount).roundToInt())
        )
    }

    private fun clampChannel(value: Int): Int = min(255, value.coerceAtLeast(0))
}
