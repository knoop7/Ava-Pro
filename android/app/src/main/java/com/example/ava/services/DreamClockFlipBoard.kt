package com.example.ava.services

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import com.example.ava.settings.DreamClockFlipBackdrop
import com.example.ava.settings.DreamClockFlipCardColors
import com.example.ava.settings.DreamClockFlipImage
import com.example.ava.settings.DreamClockFlipStyle
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * StandBy Retro Flip 1:1: three split-flap cards (HH MM SS) plus the 46 color styles.
 */
class DreamClockFlipBoard(
    private val density: Float,
    typeface: Typeface,
) {
    var style: DreamClockFlipStyle = DreamClockFlipStyle.BLACK
    var twelveHour: Boolean = false
    private var ampmLabel: String? = null
    private val ampmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        this.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    /**
     * FlipFlow rest pose: AM top-left, PM bottom-left, same X. Tuck is latched on the
     * hour string so the label cannot twitch against the “1” in 10–12 every frame.
     */
    private fun drawAmPm(
        canvas: Canvas,
        card: RectF,
        colors: DreamClockFlipCardColors,
        label: String,
        hour: String,
    ) {
        val textSize = card.height() * 0.10f
        ampmPaint.textSize = textSize
        val pad = card.width() * 0.04f
        val gap = 4f * density
        val slotW = max(ampmPaint.measureText("AM"), ampmPaint.measureText("PM"))
        val restX = card.left + card.width() * 0.125f
        val yOff = card.height() * 0.33f - textSize * 0.35f
        val restY = if (label == "AM") card.centerY() - yOff else card.centerY() + yOff

        val fontId = digitPaint.typeface?.hashCode() ?: 0
        val measureKey = "$hour|$fontId|${card.width().toInt()}|${card.height().toInt()}"
        val contentChanged = hour != ampmPoseHour || label != ampmPoseLabel
        if (measureKey != ampmMeasureKey) {
            ampmMeasureKey = measureKey
            if (hour.isNotEmpty()) {
                fitDigit(card, hour)
                captureDigitInk(digitOriginX, digitOriginY, hour)
                val firstLeft = if (!firstDigitInk.isEmpty) firstDigitInk.left else Float.POSITIVE_INFINITY
                ampmFirstLeftFrac = (firstLeft - card.left) / card.width().coerceAtLeast(1f)
                if (hour != ampmPoseHour || fontId != ampmFontId) {
                    ampmTucked = restX + slotW + gap - firstLeft > 2f * density
                    ampmFontId = fontId
                }
            } else {
                ampmTucked = false
            }
        }

        val firstLeft = card.left + ampmFirstLeftFrac * card.width()
        var x = restX
        var y = restY
        if (ampmTucked) {
            x = (firstLeft - gap - slotW).coerceAtLeast(card.left + pad)
            val tuck = min(card.height() * 0.035f, 7f * density)
            y = if (label == "AM") restY - tuck else restY + tuck
        }

        val now = SystemClock.elapsedRealtime()
        if (!ampmHasDraw) {
            ampmFromX = x
            ampmFromY = y
            ampmDrawX = x
            ampmDrawY = y
            ampmDrawA = 0f
            ampmAnimAt = now
            ampmHasDraw = true
        } else if (contentChanged) {
            ampmFromX = ampmDrawX
            ampmFromY = ampmDrawY
            ampmAnimAt = now
        }
        ampmPoseHour = hour
        ampmPoseLabel = label

        var t = 1f
        if (ampmAnimAt >= 0L) {
            t = ((now - ampmAnimAt) / ampmAnimMs).coerceIn(0f, 1f)
            t = t * t * (3f - 2f * t)
            if (t >= 1f) ampmAnimAt = -1L
        }
        ampmDrawX = ampmFromX + (x - ampmFromX) * t
        ampmDrawY = ampmFromY + (y - ampmFromY) * t
        val sliding = abs(x - ampmFromX) > 1f || abs(y - ampmFromY) > 1f
        ampmDrawA = when {
            ampmAnimAt < 0L -> 1f
            sliding -> 1f
            else -> t
        }

        val alpha = (0xA0 * ampmDrawA).toInt().coerceIn(0, 255)
        ampmPaint.color = (colors.text and 0x00FFFFFF) or (alpha shl 24)
        canvas.drawText(label, ampmDrawX, ampmDrawY, ampmPaint)
    }

    var showSeconds: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (value) secondsSlot.snapTo(
                    "%02d".format(java.util.Calendar.getInstance().get(java.util.Calendar.SECOND))
                )
            }
        }

    fun setDigitTypeface(tf: Typeface) {
        if (digitPaint.typeface != tf) digitPaint.typeface = tf
    }

    /**
     * Countdown target as wall-clock epoch millis (FlipFlow `tempTime` / HA `finishes_at`).
     * Null = clock mode. While paused, [pausedRemainingMs] freezes the display.
     */
    var countdownTargetMs: Long? = null
        private set
    var pausedRemainingMs: Long? = null
        private set
    /** Set when the countdown hit zero; cleared by [clearCountdown]. */
    var countdownFinished = false
        private set

    val isCountingDown: Boolean get() = countdownTargetMs != null || pausedRemainingMs != null

    fun startCountdown(durationMs: Long) {
        countdownTargetMs = System.currentTimeMillis() + durationMs
        pausedRemainingMs = null
        countdownFinished = false
    }

    fun setCountdownTarget(targetEpochMs: Long) {
        countdownTargetMs = targetEpochMs
        pausedRemainingMs = null
        countdownFinished = false
    }

    fun pauseCountdown() {
        val target = countdownTargetMs ?: return
        pausedRemainingMs = (target - System.currentTimeMillis()).coerceAtLeast(0L)
        countdownTargetMs = null
    }

    fun setPausedRemaining(remainingMs: Long) {
        pausedRemainingMs = remainingMs.coerceAtLeast(0L)
        countdownTargetMs = null
        countdownFinished = false
    }

    fun resumeCountdown() {
        val remaining = pausedRemainingMs ?: return
        countdownTargetMs = System.currentTimeMillis() + remaining
        pausedRemainingMs = null
    }

    fun clearCountdown() {
        countdownTargetMs = null
        pausedRemainingMs = null
        countdownFinished = false
    }

    /** Remaining millis in the current countdown (0 when finished / not running). */
    fun countdownRemainingMs(): Long {
        pausedRemainingMs?.let { return it }
        val target = countdownTargetMs ?: return 0L
        return (target - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private val hoursSlot = FlipSlot()
    private val minutesSlot = FlipSlot()
    private val secondsSlot = FlipSlot()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 0, 0, 0)
    }
    private val hingePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        this.typeface = typeface
        isSubpixelText = true
        isLinearText = true
    }
    private val digitBounds = Rect()
    private val firstDigitBounds = Rect()
    private val digitInk = RectF()
    private val firstDigitInk = RectF()
    private var digitOriginX = 0f
    private var digitOriginY = 0f
    private var ampmMeasureKey = ""
    private var ampmPoseHour = ""
    private var ampmPoseLabel = ""
    private var ampmFontId = 0
    private var ampmTucked = false
    private var ampmFirstLeftFrac = 0f
    private var ampmHasDraw = false
    private var ampmFromX = 0f
    private var ampmFromY = 0f
    private var ampmDrawX = 0f
    private var ampmDrawY = 0f
    private var ampmDrawA = 1f
    private var ampmAnimAt = -1L
    private val ampmAnimMs = 360f
    private val shadowRect = RectF()
    private val chipPath = Path()
    private val chipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val miniCardRect = RectF()

    companion object {
        const val CHIP_WIDTH_DP = 114f
        const val CHIP_HEIGHT_DP = 64f
        const val CHIP_GAP_DP = 12f
        const val CHIP_PAD_DP = 16f
        /**
         * Height cap for digits. Wide cards (seconds hidden, 12h single digit) are
         * height-bound and fill to this; narrow three-card landscape is width-bound
         * via the 0.88 fit and lands a little lower.
         */
        private const val DIGIT_FRACTION = 0.86f
    }

    /**
     * StandBy-style entrance: HH and MM (and SS) flip in together the next frame.
     * Cards flip from a blank face to the current time.
     */
    fun beginEntrance() {
        hoursSlot.entrance()
        minutesSlot.entrance()
        secondsSlot.entrance()
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        painted: DreamClockFlipStyle = style,
        animate: Boolean = true,
        layoutHeight: Int = height,
    ) {
        val hh: String
        val mm: String
        val ss: String
        ampmLabel = null
        if (isCountingDown) {
            val remainingMs = countdownRemainingMs()
            if (remainingMs <= 0L && countdownTargetMs != null) countdownFinished = true
            val total = (remainingMs + 999L) / 1000L
            hh = "%02d".format(total / 3600)
            mm = "%02d".format((total % 3600) / 60)
            ss = "%02d".format(total % 60)
        } else {
            val now = Calendar.getInstance()
            val h24 = now.get(Calendar.HOUR_OF_DAY)
            if (twelveHour) {
                // FlipFlow hourFormat 0: 1–12 unpadded, AM on the top half, PM on the bottom.
                val h12 = if (h24 % 12 == 0) 12 else h24 % 12
                hh = h12.toString()
                ampmLabel = if (h24 < 12) "AM" else "PM"
            } else {
                hh = "%02d".format(h24)
            }
            mm = "%02d".format(now.get(Calendar.MINUTE))
            ss = "%02d".format(now.get(Calendar.SECOND))
        }
        hoursSlot.setTarget(hh)
        minutesSlot.setTarget(mm)
        if (showSeconds) {
            secondsSlot.setTarget(ss)
        } else {
            secondsSlot.snapTo(ss)
        }

        drawBackdrop(canvas, 0f, 0f, width.toFloat(), height.toFloat(), painted)

        val cardH = layoutHeight.coerceIn(1, height)
        val landscape = width >= cardH
        val count = if (showSeconds) 3 else 2
        val cards = layoutCards(width, cardH, landscape, count)
        paintCard(canvas, cards[0], hoursSlot, painted.hours, animate)
        val period = ampmLabel
        if (period != null) {
            drawAmPm(canvas, cards[0], painted.hours, period, hh)
        } else {
            ampmHasDraw = false
            ampmMeasureKey = ""
            ampmPoseHour = ""
            ampmPoseLabel = ""
            ampmAnimAt = -1L
        }
        paintCard(canvas, cards[1], minutesSlot, painted.minutes, animate)
        if (showSeconds) {
            paintCard(canvas, cards[2], secondsSlot, painted.seconds, animate)
        }
    }

    fun isAnimating(): Boolean =
        hoursSlot.isAnimating() ||
            minutesSlot.isAnimating() ||
            (showSeconds && secondsSlot.isAnimating()) ||
            ampmAnimAt >= 0L

    fun cycleNext(): DreamClockFlipStyle {
        style = style.next()
        return style
    }

    fun cyclePrevious(): DreamClockFlipStyle {
        style = style.previous()
        return style
    }

    fun handleSwipe(event: MotionEvent, width: Int, triggerPx: Float, maxVerticalPx: Float): FlipSwipe? {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX = event.x
                swipeY = event.y
                return null
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - swipeX
                val dy = event.y - swipeY
                if (abs(dy) >= maxVerticalPx) return null
                if (dx < -triggerPx) return FlipSwipe.NEXT
                if (dx > triggerPx) return FlipSwipe.PREVIOUS
            }
        }
        return null
    }

    private var swipeX = 0f
    private var swipeY = 0f

    /**
     * StandBy gives every card `weight(1)` in the row/column, so cards always share
     * the full span equally — hiding seconds stretches HH/MM to fill the freed space.
     */
    private fun layoutCards(width: Int, height: Int, landscape: Boolean, count: Int): Array<RectF> {
        val n = count.coerceAtLeast(1)
        val gaps = (n - 1).toFloat()
        if (landscape) {
            val padV = height * 0.12f
            val gap = width * 0.038f
            val cardH = height - padV * 2f
            val maxW = width * 0.94f
            val cardW = (maxW - gap * gaps) / n
            val left = (width - maxW) / 2f
            val top = (height - cardH) / 2f
            return Array(n) { i ->
                val x = left + i * (cardW + gap)
                RectF(x, top, x + cardW, top + cardH)
            }
        }
        val padH = width * 0.10f
        val gap = height * 0.032f
        val cardW = width - padH * 2f
        val maxH = height * 0.88f
        val cardH = (maxH - gap * gaps) / n
        val left = (width - cardW) / 2f
        val top = (height - maxH) / 2f
        return Array(n) { i ->
            val y = top + i * (cardH + gap)
            RectF(left, y, left + cardW, y + cardH)
        }
    }

    fun drawStyleChip(canvas: Canvas, bounds: RectF, preview: DreamClockFlipStyle, selected: Boolean) {
        val radius = 12f * density
        canvas.save()
        chipPath.reset()
        chipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(chipPath)
        drawBackdrop(canvas, bounds.left, bounds.top, bounds.width(), bounds.height(), preview)
        val padH = 8f * density
        val padV = 20f * density
        val gap = 2f * density
        val innerW = bounds.width() - padH * 2f
        val innerH = bounds.height() - padV * 2f
        val count = if (showSeconds) 3 else 2
        val cardW = (innerW - gap * (count - 1)) / count
        val labels = arrayOf("04", "10", "56")
        val colors = arrayOf(preview.hours, preview.minutes, preview.seconds)
        for (i in 0 until count) {
            val left = bounds.left + padH + i * (cardW + gap)
            miniCardRect.set(left, bounds.top + padV, left + cardW, bounds.top + padV + innerH)
            drawStaticCard(canvas, miniCardRect, colors[i], labels[i], innerH * 0.14f)
        }
        canvas.restore()
        chipBorderPaint.strokeWidth = (if (selected) 4f else 0.5f) * density
        canvas.drawRoundRect(bounds, radius, radius, chipBorderPaint)
    }

    private fun paintCard(
        canvas: Canvas,
        bounds: RectF,
        slot: FlipSlot,
        colors: DreamClockFlipCardColors,
        animate: Boolean,
    ) {
        if (!animate) {
            val frame = slot.frame()
            val text = if (frame.progress > 0.5f) frame.incoming else frame.shown
            drawStaticCard(canvas, bounds, colors, text, 12f * density)
            return
        }
        drawFlap(canvas, bounds, slot, colors)
    }

    private fun drawStaticCard(
        canvas: Canvas,
        bounds: RectF,
        colors: DreamClockFlipCardColors,
        text: String,
        radius: Float,
    ) {
        cardPaint.color = colors.card
        canvas.drawRoundRect(bounds, radius, radius, cardPaint)
        digitPaint.color = colors.text
        drawDigit(canvas, bounds, text)
        hingePaint.color = Color.argb(70, 0, 0, 0)
        canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), hingePaint)
    }

    fun paintBackdrop(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        preview: DreamClockFlipStyle,
    ) {
        drawBackdrop(canvas, left, top, width, height, preview)
    }

    /**
     * Gradient shaders are cached per (kind, size) and repositioned with a local
     * matrix. Building a new Radial/LinearGradient on every frame is what made the
     * horizontal color swipe stutter — two backdrops per frame, 60 frames a second.
     */
    private val shaderCache = HashMap<Long, Shader>()
    private val shaderMatrix = Matrix()

    private fun cachedShader(key: Long, build: () -> Shader): Shader =
        shaderCache.getOrPut(key) {
            if (shaderCache.size > 24) shaderCache.clear()
            build()
        }

    private fun shaderKey(kind: Int, width: Float, height: Float): Long =
        (kind.toLong() shl 48) or
            ((width.toInt().toLong() and 0xFFFFFF) shl 24) or
            (height.toInt().toLong() and 0xFFFFFF)

    private fun drawBackdrop(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        preview: DreamClockFlipStyle,
    ) {
        bgPaint.shader = null
        when (val backdrop = preview.backdrop) {
            is DreamClockFlipBackdrop.Solid -> {
                bgPaint.color = backdrop.color
                canvas.drawRect(left, top, left + width, top + height, bgPaint)
            }
            is DreamClockFlipBackdrop.Linear -> {
                val shader = cachedShader(shaderKey(100 + preview.ordinal, width, height)) {
                    LinearGradient(
                        0f, 0f, 0f, height,
                        backdrop.start, backdrop.end,
                        Shader.TileMode.CLAMP
                    )
                }
                shaderMatrix.setTranslate(left, top)
                shader.setLocalMatrix(shaderMatrix)
                bgPaint.shader = shader
                canvas.drawRect(left, top, left + width, top + height, bgPaint)
                bgPaint.shader = null
            }
            is DreamClockFlipBackdrop.Image -> {
                drawImageBackdrop(canvas, left, top, width, height, backdrop.image)
            }
        }
    }

    private fun drawImageBackdrop(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        image: DreamClockFlipImage,
    ) {
        val sx = width / 800f
        val sy = height / 360f
        val fill = when (image) {
            DreamClockFlipImage.ONE,
            DreamClockFlipImage.FIVE -> Color.BLACK
            DreamClockFlipImage.TWO -> 0xFF153F32.toInt()
            DreamClockFlipImage.THREE -> 0xFFFEFFFE.toInt()
            DreamClockFlipImage.FOUR -> 0xFF01002E.toInt()
        }
        bgPaint.shader = null
        bgPaint.color = fill
        canvas.drawRect(left, top, left + width, top + height, bgPaint)
        val shader = cachedShader(shaderKey(image.ordinal, width, height)) {
            when (image) {
                DreamClockFlipImage.ONE -> RadialGradient(
                    52.5f * sx, -407f * sy, 1352.15f * min(sx, sy).coerceAtLeast(0.4f),
                    intArrayOf(0xFFFFA2DF.toInt(), Color.BLACK, 0xFFFFF971.toInt()),
                    floatArrayOf(0f, 0.5901f, 1f),
                    Shader.TileMode.CLAMP
                )
                DreamClockFlipImage.TWO -> RadialGradient(
                    243.5f * sx, -14.5f * sy, 646.044f * min(sx, sy).coerceAtLeast(0.4f),
                    intArrayOf(0xFF1D734D.toInt(), 0x00164133, 0xFF0F121A.toInt()),
                    floatArrayOf(0f, 0.5142f, 1f),
                    Shader.TileMode.CLAMP
                )
                DreamClockFlipImage.THREE -> RadialGradient(
                    183.5f * sx, -105.5f * sy, 914.165f * min(sx, sy).coerceAtLeast(0.4f),
                    intArrayOf(0xFF74E6C4.toInt(), 0xFFFAFFFC.toInt(), 0xFF6CF275.toInt()),
                    floatArrayOf(0f, 0.5901f, 1f),
                    Shader.TileMode.CLAMP
                )
                DreamClockFlipImage.FOUR -> RadialGradient(
                    48f * sx, 29f * sy, 1024.83f * min(sx, sy).coerceAtLeast(0.4f),
                    intArrayOf(0xFFFD9D6A.toInt(), 0xFF8352BB.toInt(), 0xFF5A6CEC.toInt(), 0x0001002E),
                    floatArrayOf(0f, 0.3221f, 0.5909f, 0.9114f),
                    Shader.TileMode.CLAMP
                )
                DreamClockFlipImage.FIVE -> RadialGradient(
                    258f * sx, -100f * sy, 915.982f * min(sx, sy).coerceAtLeast(0.4f),
                    intArrayOf(0xFFC7D461.toInt(), 0xFFFFC692.toInt(), 0xFFAD35FE.toInt()),
                    floatArrayOf(0f, 0.5901f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        shaderMatrix.setTranslate(left, top)
        shader.setLocalMatrix(shaderMatrix)
        bgPaint.shader = shader
        canvas.drawRect(left, top, left + width, top + height, bgPaint)
        bgPaint.shader = null
    }

    private fun drawFlap(
        canvas: Canvas,
        bounds: RectF,
        slot: FlipSlot,
        colors: DreamClockFlipCardColors,
    ) {
        val radius = 12f * density
        val lift = 6f * density
        shadowRect.set(bounds)
        shadowRect.offset(0f, lift)
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)

        val midY = bounds.centerY()
        val frame = slot.frame()
        val shown = frame.shown
        val incoming = frame.incoming
        val progress = frame.progress
        hingePaint.color = Color.argb(80, 0, 0, 0)

        if (shown == incoming) {
            drawCardFace(canvas, bounds, colors, shown, radius)
            canvas.drawLine(bounds.left, midY, bounds.right, midY, hingePaint)
            return
        }

        val angle = progress * Math.PI
        val sy = abs(cos(angle)).toFloat().coerceAtLeast(0.06f)
        if (progress < 0.5f) {
            drawHalfFace(canvas, bounds, midY, top = false, colors, shown, radius)
            drawHalfFace(canvas, bounds, midY, top = true, colors, incoming, radius)
            canvas.save()
            canvas.clipRect(bounds.left, bounds.top, bounds.right, midY)
            canvas.scale(1f, sy, bounds.centerX(), midY)
            drawCardFace(canvas, bounds, colors, shown, radius)
            shadeFlap(canvas, bounds, progress * 2f)
            canvas.restore()
        } else {
            drawHalfFace(canvas, bounds, midY, top = true, colors, incoming, radius)
            drawHalfFace(canvas, bounds, midY, top = false, colors, shown, radius)
            canvas.save()
            canvas.clipRect(bounds.left, midY, bounds.right, bounds.bottom)
            canvas.scale(1f, sy, bounds.centerX(), midY)
            drawCardFace(canvas, bounds, colors, incoming, radius)
            shadeFlap(canvas, bounds, (1f - progress) * 2f)
            canvas.restore()
        }
        canvas.drawLine(bounds.left, midY, bounds.right, midY, hingePaint)
    }

    private fun shadeFlap(canvas: Canvas, bounds: RectF, edge: Float) {
        cardPaint.color = Color.argb((edge.coerceIn(0f, 1f) * 70f).toInt(), 0, 0, 0)
        canvas.drawRect(bounds, cardPaint)
    }

    private fun drawHalfFace(
        canvas: Canvas,
        bounds: RectF,
        midY: Float,
        top: Boolean,
        colors: DreamClockFlipCardColors,
        text: String,
        radius: Float,
    ) {
        canvas.save()
        if (top) {
            canvas.clipRect(bounds.left, bounds.top, bounds.right, midY)
        } else {
            canvas.clipRect(bounds.left, midY, bounds.right, bounds.bottom)
        }
        drawCardFace(canvas, bounds, colors, text, radius)
        canvas.restore()
    }

    private fun drawCardFace(
        canvas: Canvas,
        bounds: RectF,
        colors: DreamClockFlipCardColors,
        text: String,
        radius: Float,
    ) {
        cardPaint.color = colors.card
        canvas.drawRoundRect(bounds, radius, radius, cardPaint)
        digitPaint.color = colors.text
        drawDigit(canvas, bounds, text)
    }

    /**
     * LEFT origin that optically centers [text] in [bounds]. Advance-width centering
     * pulls “12” / “13” toward the heavier 2/3; ink-box center keeps them upright.
     */
    private fun fitDigit(bounds: RectF, text: String) {
        digitPaint.textSize = bounds.height() * DIGIT_FRACTION
        if (text.isNotEmpty()) {
            val maxTextW = bounds.width() * 0.88f
            digitPaint.getTextBounds(text, 0, text.length, digitBounds)
            if (digitBounds.width() > maxTextW && digitBounds.width() > 0) {
                digitPaint.textSize *= maxTextW / digitBounds.width()
                digitPaint.getTextBounds(text, 0, text.length, digitBounds)
            }
        } else {
            digitBounds.setEmpty()
        }
        digitOriginX = bounds.centerX() - digitBounds.exactCenterX()
        digitOriginY = bounds.centerY() - digitBounds.exactCenterY()
    }

    private fun captureDigitInk(originX: Float, originY: Float, text: String) {
        if (text.isEmpty()) {
            digitInk.setEmpty()
            firstDigitInk.setEmpty()
            return
        }
        digitInk.set(
            originX + digitBounds.left,
            originY + digitBounds.top,
            originX + digitBounds.right,
            originY + digitBounds.bottom,
        )
        digitPaint.getTextBounds(text, 0, 1, firstDigitBounds)
        firstDigitInk.set(
            originX + firstDigitBounds.left,
            originY + firstDigitBounds.top,
            originX + firstDigitBounds.right,
            originY + firstDigitBounds.bottom,
        )
    }

    private fun drawDigit(canvas: Canvas, bounds: RectF, text: String) {
        fitDigit(bounds, text)
        canvas.drawText(text, digitOriginX, digitOriginY, digitPaint)
        captureDigitInk(digitOriginX, digitOriginY, text)
    }

    enum class FlipSwipe { NEXT, PREVIOUS }

    private class FlipSlot {
        private var current = "00"
        private var next = "00"
        private var startMs = 0L
        private var primed = false
        private var entrancePending = false
        /**
         * Value that arrived while a flip was mid-air. A flip is never restarted: it
         * finishes, then chains into this. Without the queue a seconds tick landing
         * during a synchronized H/M/S flip would yank the S card and desync the three.
         */
        private var queued: String? = null

        /** Instantly show [value] with no flip animation. */
        fun snapTo(value: String) {
            current = value
            next = value
            queued = null
            startMs = 0L
            primed = true
        }

        /** Next setTarget flips in from a blank card instead of snapping. */
        fun entrance() {
            entrancePending = true
        }

        fun setTarget(value: String) {
            if (!primed) {
                primed = true
                if (entrancePending) {
                    entrancePending = false
                    current = ""
                    next = value
                    startMs = SystemClock.elapsedRealtime()
                } else {
                    current = value
                    next = value
                }
                return
            }
            if (entrancePending) {
                entrancePending = false
                current = ""
                next = value
                queued = null
                startMs = SystemClock.elapsedRealtime()
                return
            }
            settle()
            if (current != next) {
                // Mid-flip: remember the newest value, keep this flip's timing intact.
                queued = if (value != next) value else null
                return
            }
            if (value == current) return
            next = value
            startMs = SystemClock.elapsedRealtime()
        }

        fun frame(): FlipFrame {
            settle()
            if (current == next) return FlipFrame(current, next, 0f)
            val p = ((SystemClock.elapsedRealtime() - startMs) / FLIP_MS).coerceIn(0f, 1f)
            return FlipFrame(current, next, p)
        }

        fun isAnimating(): Boolean {
            settle()
            return current != next
        }

        private fun settle() {
            if (current == next || startMs <= 0L) return
            if (SystemClock.elapsedRealtime() - startMs >= FLIP_MS) {
                current = next
                val q = queued
                queued = null
                if (q != null && q != current) {
                    next = q
                    startMs = SystemClock.elapsedRealtime()
                }
            }
        }

        companion object {
            private const val FLIP_MS = 520f
        }
    }

    private data class FlipFrame(
        val shown: String,
        val incoming: String,
        val progress: Float,
    )
}
