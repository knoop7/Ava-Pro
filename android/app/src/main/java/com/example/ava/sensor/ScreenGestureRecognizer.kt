package com.example.ava.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.ava.settings.ExperimentalSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Local multi-touch + $P stroke recognizer. Emits into [ScreenGestureSensor].
 */
object ScreenGestureRecognizer {
    private const val X_PAUSE_MS = 550L

    private enum class Corner { TL, TR, BL, BR }

    @Volatile
    private var enabledTokens: Set<String> = emptySet()

    @Volatile
    private var masterOn = false

    private var scaleDetector: ScaleGestureDetector? = null
    private var pinchArmed = false
    private var pinchEmitted = false
    private var pinchStartSpan = 0f

    private val stroke = ArrayList<GesturePoint>(128)
    private var startX = 0f
    private var startY = 0f
    private var maxPointers = 1
    private var fiveFingerArmed = false
    private var fiveFingerEmitted = false
    private var startedInCorner: Corner? = null
    private var screenW = 1
    private var screenH = 1
    private var density = 1f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingXStroke: ArrayList<GesturePoint>? = null
    private var awaitingXSecond = false
    private val flushPendingStroke = Runnable {
        val held = pendingXStroke
        pendingXStroke = null
        awaitingXSecond = false
        if (held != null) {
            stroke.clear()
            stroke.addAll(held)
            val last = held.last()
            startX = held.first().x
            startY = held.first().y
            classifyStroke(last.x, last.y)
            resetStroke()
        }
    }

    fun sync(settings: ExperimentalSettings) {
        masterOn = settings.screenGestureEnabled
        enabledTokens = ScreenGestureCatalog.resolvedTokens(settings)
        if (!masterOn) {
            ScreenGestureSensor.reset()
            cancelXWait()
            resetStroke()
        }
    }

    fun onTouchEvent(
        context: Context,
        event: MotionEvent,
        width: Int,
        height: Int,
    ): Boolean {
        if (!masterOn || enabledTokens.isEmpty() || width <= 0 || height <= 0) return false
        screenW = width
        screenH = height
        density = context.resources.displayMetrics.density

        ensureScaleDetector(context)
        if (wantsPinch()) {
            scaleDetector?.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (awaitingXSecond && pendingXStroke != null) {
                    mainHandler.removeCallbacks(flushPendingStroke)
                    awaitingXSecond = false
                    resetStroke(keepPendingX = true)
                } else {
                    cancelXWait()
                    resetStroke()
                }
                startX = event.x
                startY = event.y
                startedInCorner = cornerAt(startX, startY)
                maxPointers = 1
                stroke.add(GesturePoint(event.x, event.y))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, event.pointerCount)
                if (event.pointerCount >= 5 && enabled("five_finger") && !fiveFingerEmitted) {
                    fiveFingerArmed = true
                    if (emit("five_finger")) {
                        fiveFingerEmitted = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    stroke.add(GesturePoint(event.x, event.y))
                }
                if (pinchEmitted) return true
            }
            MotionEvent.ACTION_UP -> {
                if (fiveFingerEmitted || pinchEmitted) {
                    val consume = fiveFingerEmitted || pinchEmitted
                    cancelXWait()
                    resetStroke()
                    return consume
                }
                val first = pendingXStroke
                if (first != null && !awaitingXSecond) {
                    if (looksLikeX(first, stroke)) {
                        emit("x")
                        cancelXWait()
                        resetStroke()
                        return false
                    }
                    cancelXWait()
                }
                if (enabled("x") && looksLikeXLeg(stroke)) {
                    pendingXStroke = ArrayList(stroke)
                    awaitingXSecond = true
                    mainHandler.removeCallbacks(flushPendingStroke)
                    mainHandler.postDelayed(flushPendingStroke, X_PAUSE_MS)
                    resetStroke(keepPendingX = true)
                    return false
                }
                classifyStroke(event.x, event.y)
                resetStroke()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelXWait()
                resetStroke()
            }
        }
        return pinchEmitted || fiveFingerEmitted
    }

    private fun classifyStroke(endX: Float, endY: Float) {
        val dx = endX - startX
        val dy = endY - startY
        val dist = hypot(dx, dy)
        val tapSlop = 48f * density
        val swipeMin = max(80f * density, 0.15f * min(screenW, screenH))
        val startCorner = startedInCorner

        if (startCorner != null) {
            if (dist < tapSlop) {
                emit(cornerTapId(startCorner))
                return
            }
            if (dist >= swipeMin) {
                emit(cornerSwipeId(startCorner))
                return
            }
            return
        }

        val straightness = strokeStraightness()
        val axisAligned = isAxisAligned(dx, dy)
        val vertical = abs(dy) >= abs(dx) * 1.6f
        val verticalSwipeOn = enabled("swipe_up") || enabled("swipe_down")
        val swipeLike = dist >= swipeMin && straightness < 1.22f && axisAligned
        if (swipeLike && (enabled("swipe_up") || enabled("swipe_down") ||
                enabled("swipe_left") || enabled("swipe_right"))
        ) {
            val token = if (abs(dx) >= abs(dy)) {
                if (dx > 0f) "swipe_right" else "swipe_left"
            } else {
                if (dy > 0f) "swipe_down" else "swipe_up"
            }
            if (emit(token)) return
        }

        if (!wantsShape()) return
        if (stroke.size < 8) return
        val box = strokeBounds()
        val shortSide = min(screenW, screenH).toFloat()
        if (box < 0.25f * shortSide) return
        // Straight leftovers stay geometric: 1 if vertical swipe is off, otherwise drop.
        if (straightness < 1.32f && axisAligned) {
            if (vertical && enabled("digit_1") && !verticalSwipeOn) {
                emit("digit_1")
            }
            return
        }
        val allowed = enabledTokens.filter {
            it in ScreenGestureCatalog.ALL_DIGIT_IDS || it in ScreenGestureCatalog.ALL_GEOMETRY_IDS
        }.toMutableSet()
        allowed.remove("digit_1")
        if (allowed.isEmpty()) return
        val templates = ScreenGestureTemplates.all.filter { it.id in allowed }
        val match = DollarPRecognizer.recognize(stroke.toList(), templates) ?: return
        val refined = DigitDisambiguator.refine(stroke.toList(), match, allowed) ?: return
        emit(refined)
    }

    private fun strokeBounds(): Float {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in stroke) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return max(maxX - minX, maxY - minY)
    }

    private fun strokeStraightness(): Float {
        if (stroke.size < 3) return 1f
        val first = stroke.first()
        val last = stroke.last()
        val chord = hypot(last.x - first.x, last.y - first.y)
        if (chord < 1f) return 1f
        var path = 0f
        for (i in 1 until stroke.size) {
            path += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
        }
        return path / chord
    }

    private fun isAxisAligned(dx: Float, dy: Float): Boolean {
        val ax = abs(dx)
        val ay = abs(dy)
        val shortest = min(ax, ay).coerceAtLeast(1f)
        return max(ax, ay) / shortest >= 1.6f
    }

    private fun cornerAt(x: Float, y: Float): Corner? {
        val inset = max(48f * density, 0.10f * min(screenW, screenH))
        val left = x <= inset
        val right = x >= screenW - inset
        val top = y <= inset
        val bottom = y >= screenH - inset
        return when {
            left && top -> Corner.TL
            right && top -> Corner.TR
            left && bottom -> Corner.BL
            right && bottom -> Corner.BR
            else -> null
        }
    }

    private fun cornerTapId(corner: Corner): String = when (corner) {
        Corner.TL -> "corner_tl"
        Corner.TR -> "corner_tr"
        Corner.BL -> "corner_bl"
        Corner.BR -> "corner_br"
    }

    private fun cornerSwipeId(corner: Corner): String = when (corner) {
        Corner.TL -> "corner_tl_swipe"
        Corner.TR -> "corner_tr_swipe"
        Corner.BL -> "corner_bl_swipe"
        Corner.BR -> "corner_br_swipe"
    }

    private fun emit(token: String): Boolean {
        if (!enabled(token)) return false
        return ScreenGestureSensor.emit(token)
    }

    private fun enabled(token: String): Boolean = token in enabledTokens

    private fun wantsPinch(): Boolean = enabled("pinch_in") || enabled("pinch_out")

    private fun wantsShape(): Boolean =
        enabledTokens.any {
            it in ScreenGestureCatalog.ALL_DIGIT_IDS || it in ScreenGestureCatalog.ALL_GEOMETRY_IDS
        }

    private fun ensureScaleDetector(context: Context) {
        if (scaleDetector != null) return
        scaleDetector = ScaleGestureDetector(
            context.applicationContext,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinchArmed = true
                    pinchStartSpan = detector.currentSpan
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (pinchEmitted || !pinchArmed) return true
                    val start = pinchStartSpan
                    if (start <= 1f) return true
                    val factor = detector.currentSpan / start
                    when {
                        factor <= 0.85f && emit("pinch_in") -> pinchEmitted = true
                        factor >= 1.15f && emit("pinch_out") -> pinchEmitted = true
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    pinchArmed = false
                }
            },
        )
    }

    private fun looksLikeXLeg(points: List<GesturePoint>): Boolean {
        if (points.size < 4) return false
        val a = points.first()
        val b = points.last()
        val chord = hypot(b.x - a.x, b.y - a.y)
        val minLen = max(64f * density, 0.12f * min(screenW, screenH))
        if (chord < minLen) return false
        var path = 0f
        for (i in 1 until points.size) {
            path += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        if (path / chord > 1.35f) return false
        val dx = abs(b.x - a.x)
        val dy = abs(b.y - a.y)
        val longest = max(dx, dy)
        return longest > 0f && min(dx, dy) / longest > 0.32f
    }

    private fun looksLikeX(first: List<GesturePoint>, second: List<GesturePoint>): Boolean {
        if (!looksLikeXLeg(first) || !looksLikeXLeg(second)) return false
        val a = first.first()
        val b = first.last()
        val c = second.first()
        val d = second.last()
        if (!segmentsCross(a, b, c, d)) return false
        val v1x = b.x - a.x
        val v1y = b.y - a.y
        val v2x = d.x - c.x
        val v2y = d.y - c.y
        val dot = abs(v1x * v2x + v1y * v2y)
        val mag = hypot(v1x, v1y) * hypot(v2x, v2y)
        return mag > 0f && dot / mag < 0.82f
    }

    private fun segmentsCross(
        a: GesturePoint,
        b: GesturePoint,
        c: GesturePoint,
        d: GesturePoint,
    ): Boolean {
        fun cross(ax: Float, ay: Float, bx: Float, by: Float) = ax * by - ay * bx
        val d1 = cross(b.x - a.x, b.y - a.y, c.x - a.x, c.y - a.y)
        val d2 = cross(b.x - a.x, b.y - a.y, d.x - a.x, d.y - a.y)
        val d3 = cross(d.x - c.x, d.y - c.y, a.x - c.x, a.y - c.y)
        val d4 = cross(d.x - c.x, d.y - c.y, b.x - c.x, b.y - c.y)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    private fun cancelXWait() {
        mainHandler.removeCallbacks(flushPendingStroke)
        pendingXStroke = null
        awaitingXSecond = false
    }

    private fun resetStroke(keepPendingX: Boolean = false) {
        stroke.clear()
        maxPointers = 1
        fiveFingerArmed = false
        fiveFingerEmitted = false
        pinchArmed = false
        pinchEmitted = false
        startedInCorner = null
        if (!keepPendingX) {
            pendingXStroke = null
            awaitingXSecond = false
        }
    }
}
