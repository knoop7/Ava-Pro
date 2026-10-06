package com.example.ava.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.example.ava.R

/**
 * Canvas-drawn covers for the widget tray.
 *
 * Two widgets out of three ship no `previewImage`, and the framework's stand-in is the
 * provider's app icon stretched across the cell — every cover ends up looking like the
 * same app logo. These draw a small mock of the widget instead: a brand mark, a kicker,
 * the name, and an accent rule, so a row of covers reads as a row of different widgets.
 *
 * Everything is laid out on a 112 unit square and scaled, so one set of numbers works at
 * any request size.
 */
object MinimalLauncherWidgetCover {

    private const val GRID = 112f

    /**
     * Cover for a provider with no usable preview.
     *
     * @param brand the provider's own app icon; for Ava's widgets pass the Ava mark
     *   ([avaMark]) so the cover is not a black icon plate.
     */
    fun default(
        label: CharSequence,
        appLabel: CharSequence?,
        brand: Drawable?,
        sizePx: Int,
        isDarkMode: Boolean,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = size / GRID
        val palette = Palette.of(isDarkMode)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
        val left = 9f * scale
        val textWidth = 94f * scale

        brand?.let {
            val mark = 24f * scale
            it.setBounds(left.toInt(), (8f * scale).toInt(), (left + mark).toInt(), ((8f * scale) + mark).toInt())
            it.draw(canvas)
        }

        paint.color = palette.accent
        paint.textSize = 9f * scale
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.letterSpacing = 0.10f
        canvas.drawText("WIDGET", left, 52f * scale, paint)

        paint.color = palette.ink
        paint.textSize = 13f * scale
        paint.letterSpacing = 0f
        val titleLines = wrapLines(paint, label.toString(), textWidth, maxLines = 2)
        titleLines.forEachIndexed { index, line ->
            canvas.drawText(line, left, (70f + index * 16f) * scale, paint)
        }

        // The app name only earns a line when the widget name did not need both.
        if (titleLines.size < 2 && !appLabel.isNullOrBlank()) {
            paint.color = palette.muted
            paint.textSize = 10f * scale
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            val app = wrapLines(paint, appLabel.toString(), textWidth, maxLines = 1)
            app.firstOrNull()?.let { canvas.drawText(it, left, 86f * scale, paint) }
        }

        paint.color = palette.accent
        paint.strokeWidth = 2f * scale
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(left, 100f * scale, 37f * scale, 100f * scale, paint)
        return bitmap
    }

    /**
     * Cover for Ava's transparent title/description widget. Text-first, because the widget
     * itself is only text over the wallpaper — a screenshot of it would be near-empty.
     */
    fun avaText(context: Context, sizePx: Int, isDarkMode: Boolean): Bitmap = avaCard(
        context = context,
        kicker = "TEXT",
        title = context.getString(R.string.ava_widget_default_title),
        description = context.getString(R.string.ava_widget_default_description),
        sizePx = sizePx,
        isDarkMode = isDarkMode,
    )

    /**
     * Same sheet as [avaText]: kicker, mark, title, description, accent rule.
     * The three action widgets use this so the exclusive row is one visual family,
     * instead of a text card next to three coloured layout screenshots.
     */
    fun avaCard(
        context: Context,
        kicker: String,
        title: String,
        description: String,
        sizePx: Int,
        isDarkMode: Boolean,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = size / GRID
        val palette = Palette.of(isDarkMode)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
        val left = 9f * scale
        val textWidth = 94f * scale

        paint.color = palette.accent
        paint.textSize = 9f * scale
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.letterSpacing = 0.10f
        canvas.drawText(kicker, left, 22f * scale, paint)

        avaMark(context)?.let {
            val mark = 18f * scale
            val right = 103f * scale
            it.setBounds(
                (right - mark).toInt(),
                (8f * scale).toInt(),
                right.toInt(),
                ((8f * scale) + mark).toInt(),
            )
            it.draw(canvas)
        }

        paint.color = palette.ink
        paint.textSize = 22f * scale
        paint.letterSpacing = 0f
        val titleLine = wrapLines(paint, title, textWidth, maxLines = 1).firstOrNull() ?: title
        canvas.drawText(titleLine, left, 57f * scale, paint)

        paint.color = palette.muted
        paint.textSize = 11f * scale
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val descLine = wrapLines(paint, description, textWidth, maxLines = 1).firstOrNull()
        if (descLine != null) {
            canvas.drawText(descLine, left, 78f * scale, paint)
        }

        paint.color = palette.accent
        paint.strokeWidth = 2f * scale
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(left, 91f * scale, 68f * scale, 91f * scale, paint)
        return bitmap
    }

    /** The launcher-icon mark (capsule + dot) — never the lightning bolt of the service widget. */
    fun avaMark(context: Context): Drawable? =
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_ava_logo)

    private class Palette(val ink: Int, val muted: Int, val accent: Int) {
        companion object {
            fun of(isDarkMode: Boolean) = Palette(
                ink = if (isDarkMode) Color.WHITE else Color.rgb(30, 41, 59),
                muted = if (isDarkMode) Color.rgb(203, 213, 225) else Color.rgb(100, 116, 139),
                accent = Color.rgb(87, 154, 205),
            )
        }
    }

    /**
     * Breaks [text] to fit [maxWidth], ellipsizing the last line. Prefers a space when the
     * line has one past its midpoint, so Latin names do not snap mid-word; CJK has no
     * spaces and wraps per character, which is what it wants anyway.
     */
    private fun wrapLines(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxLines: Int,
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || maxLines <= 0) return emptyList()
        val lines = mutableListOf<String>()
        var rest = trimmed
        while (rest.isNotEmpty() && lines.size < maxLines) {
            val fits = paint.breakText(rest, true, maxWidth, null)
            if (fits <= 0) break
            if (fits >= rest.length) {
                lines += rest
                break
            }
            if (lines.size == maxLines - 1) {
                val room = paint
                    .breakText(rest, true, maxWidth - paint.measureText("…"), null)
                    .coerceAtLeast(1)
                lines += rest.substring(0, room).trimEnd() + "…"
                break
            }
            val space = rest.lastIndexOf(' ', fits - 1)
            val cut = if (space > fits / 2) space else fits
            lines += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        return lines
    }
}
