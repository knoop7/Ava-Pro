package com.example.ava.touchpad

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Numbers taken from One Hand Control 1.2.6 Touch Pad. */
object TouchPadMath {
    const val MIN_SIZE_DP = 160
    const val MAX_SIZE_DP = 320
    const val TITLE_HEIGHT_DP = 35
    const val TITLE_PAD_TOP_DP = 0
    const val TITLE_PAD_BOTTOM_DP = 0
    const val TITLE_ICON_PAD_DP = 6
    const val HANDLE_SIZE_DP = 40
    const val HANDLE_HIDE_MS = 1400L
    const val CURSOR_SIZE_DP = 52
    /** Window is larger than the dot so a playback comet tail can draw around it. */
    const val CURSOR_TRAIL_SIZE_DP = 168
    const val CURSOR_DOT_RADIUS_DP = 8f
    /** Same family as the pad dot; a bit larger so an AI tap is obvious. */
    const val AI_CURSOR_DOT_RADIUS_DP = 11f
    const val CURSOR_SHADOW_RADIUS_DP = 18f
    const val AI_CURSOR_SHADOW_RADIUS_DP = 23f
    const val AI_APPEAR_MS = 90L
    const val AI_LINGER_MS = 720L
    const val CURSOR_TRAIL_MS = 320L
    const val CURSOR_TRAIL_POINTS = 16
    const val AUTO_STREAK_MS = 120L
    /** Icon sits in half the body; keep glyph and label a matched pair. */
    const val AUTO_FACE_ICON_FRACTION = 0.44f
    const val AUTO_FACE_ICON_MIN_DP = 30f
    /** Above the 160 pivot only a little extra, not a jump to 52. */
    const val AUTO_FACE_ICON_MAX_DP = 41f
    const val AUTO_FACE_ICON_GROW = 0.28f
    const val AUTO_FACE_TEXT_FROM_ICON = 0.54f
    const val AUTO_FACE_TEXT_MIN_SP = 15f
    const val AUTO_FACE_TEXT_MAX_SP = 22f
    const val TAP_SLOP_PX = 15f
    const val TWO_FINGER_SCROLL_STEP_DP = 2f
    const val SCROLL_GAIN = 0.42f
    const val PAGE_SCROLL_STEP_DP = 6f
    const val LIFT_COMMIT_FRACTION = 0.14f
    const val OVERLAY_LIFT_COMMIT_FRACTION = 0.42f
    const val SCROLL_INSET_DP = 28f
    const val SCROLL_OVERLAY_GAP_DP = 20f
    const val SCROLL_NODE_STEP_DP = 12f
    const val SCROLL_PRESS_MS = 50L
    const val SCREEN_SWIPE_SETTLE_MS = 48L
    const val SCREEN_SWIPE_MIN_MS = 220L
    const val SCREEN_SWIPE_MAX_MS = 520L
    const val SCROLL_NUDGE_DP = 7f
    const val SCROLL_NUDGE_MS = 180L
    const val SCROLL_HINT_MS = 360L
    /** Finger glow must be gone this soon after lift — no leftover dot. */
    const val CONTACT_FADE_MS = 90L
    const val THREE_CONTACT_IN_MS = 150L
    const val THREE_CONTACT_FADE_MS = 170L
    const val SCROLL_PRESS_RADIUS_DP = 20f
    /** Dark glass: a larger bloom so the mark is not a tight dark pit. */
    const val SCROLL_PRESS_RADIUS_DARK_DP = 28f
    const val SCROLL_PRESS_LEAN_DP = 4f
    // Gentle glass feel: finger contacts barely visible like on real glass
    const val SCROLL_PRESS_PEAK_ALPHA = 80
    const val THREE_CONTACT_PEAK_ALPHA = 40
    const val CONTACT_HALO_SCALE = 0.26f
    const val CONTACT_HALO_SCALE_DARK = 0.14f
    const val THREE_CONTACT_HALO_SCALE = 0.18f
    const val THREE_CONTACT_HALO_SCALE_DARK = 0.10f
    const val SCROLL_HINT_PEAK_ALPHA = 28
    const val CONTACT_LINK_ALPHA = 48
    const val THREE_CONTACT_LINK_ALPHA = 20
    const val SCROLL_RATCHET_MIN_TRAVEL = 16f
    const val SCROLL_WHEEL_REF_PX = 48f
    const val THREE_FINGER_SLOP_PX = 22f
    const val THREE_FINGER_RECENTS_MIN_PX = 36f
    /** After-lift shade swipe must cover this fraction of the screen to commit. */
    const val SHADE_SWIPE_MIN_SCREEN = 0.45f
    /** Sideways/down travel must beat Recents before we lock into drag. */
    const val THREE_FINGER_DRAG_COMMIT_PX = 52f
    const val PINCH_SCALE_SLOP = 0.10f

    enum class ThreeFingerKind { NONE, PINCH, SWIPE, DRAG }
    const val DOUBLE_TAP_PULSE_MS = 300L
    const val DOUBLE_TAP_PULSE_EXTRA = 0.20f
    const val TAP_PULSE_MS = 200L
    const val TAP_PULSE_EXTRA = 0.10f
    const val AI_TAP_PULSE_MS = 240L
    const val AI_TAP_PULSE_EXTRA = 0.14f
    const val HOLD_SCALE = 1.08f
    const val ENTER_SCALE = 0.96f
    const val SWIPE_TICK_DP = 14f
    const val CURSOR_TICK_DP = 22f
    const val RESIZE_TICK_DP = 8f
    const val TWO_FINGER_TAP_MIN_MS = 0L
    const val TWO_FINGER_TAP_MAX_MS = 280L
    /** Second finger within this of the first counts as “together” → Back. */
    const val TWO_FINGER_TOGETHER_MS = 100L
    const val SLIDE_MIN_DP = 8f
    const val HOLD_FOLLOW_MIN_DP = 10f
    /** Hold-drag uses a slightly lower pointer gain so the stroke stays stable. */
    const val HOLD_SENSITIVITY = 0.74f
    const val TAP_DURATION_MS = 50L
    const val HOLD_EXTRA_MS = 180L
    const val HOLD_THEN_SLIDE_MS = 220L
    const val FADE_MS = 230L
    /** Dismiss is slower so a cold or busy GPU cannot skip the fade into a flash. */
    const val FADE_OUT_MS = 380L
    const val FADE_OUT_SETTLE_MS = 32L
    const val CURSOR_IDLE_MS = 6000L
    /** No pad touch for this long → close the overlay. */
    const val DEFAULT_IDLE_CLOSE_MS = 30L * 60L * 1000L
    const val DEFAULT_SIZE_DP = 160
    const val DEFAULT_OPACITY = 88
    const val DEFAULT_SENSITIVITY = 100
    const val DEFAULT_HOLD_DELAY_MS = 420L
    const val DEFAULT_DOUBLE_TAP_MS = 230L
    const val DEFAULT_SLIDE_MS = 130L
    const val MIN_OPACITY = 12
    const val MAX_OPACITY = 100
    const val MIN_SENSITIVITY = 40
    const val MAX_SENSITIVITY = 240
    const val SENSITIVITY_GAIN = 1.8f
    const val MARGIN_H_DP = 8
    const val MARGIN_V_DP = 24
    const val DEFAULT_BOTTOM_GAP_DP = 60
    const val CURSOR_ABOVE_GAP_DP = 14
    const val SIZE_STEP_DP = 16
    const val PINCH_MIN_SPAN_PX = 1f
    const val CORNER_SUMMON_SIZE_DP = 56f
    const val CORNER_SUMMON_TAPS = 3
    const val CORNER_SUMMON_GAP_MS = 520L

    data class WindowFrame(val x: Int, val y: Int, val width: Int, val height: Int)

    fun clamp(value: Float, min: Float, max: Float): Float {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return value.coerceIn(lo, hi)
    }

    fun clamp(value: Int, min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return value.coerceIn(lo, hi)
    }

    fun sizeDp(raw: Int): Int {
        val stepped = (raw / SIZE_STEP_DP) * SIZE_STEP_DP
        return clamp(stepped, MIN_SIZE_DP, MAX_SIZE_DP)
    }

    fun snapSizeFromSlider(fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val raw = (t * (MAX_SIZE_DP - MIN_SIZE_DP) + MIN_SIZE_DP).roundToInt()
        return sizeDp(raw)
    }

    fun sliderFraction(sizeDp: Int): Float {
        val span = (MAX_SIZE_DP - MIN_SIZE_DP).toFloat()
        if (span <= 0f) return 0f
        return ((sizeDp(sizeDp) - MIN_SIZE_DP) / span).coerceIn(0f, 1f)
    }

    fun sensitivityScale(pref: Int): Float =
        (clamp(pref, MIN_SENSITIVITY, MAX_SENSITIVITY) / 100f) * SENSITIVITY_GAIN

    /** Apple-like pointer acceleration: precise at low speed, faster for large deltas. */
    fun pointerScale(pref: Int, dx: Float, dy: Float): Float {
        val base = sensitivityScale(pref)
        val speed = hypot(dx, dy)
        val accel = (0.78f + (speed / 42f).coerceIn(0f, 1.35f) * 0.42f)
        return base * accel
    }

    /** Hold-drag: a bit slower, almost linear, so the press does not twitch. */
    fun holdPointerScale(pref: Int, dx: Float, dy: Float): Float {
        val base = sensitivityScale(pref) * HOLD_SENSITIVITY
        val speed = hypot(dx, dy)
        val accel = 0.94f + (speed / 64f).coerceIn(0f, 0.40f) * 0.12f
        return base * accel
    }

    fun opacityByte(opacity: Int): Int =
        ((clamp(opacity, MIN_OPACITY, MAX_OPACITY) * 255) / 100).coerceIn(0, 255)

    fun padSizePx(
        sizeDp: Int,
        density: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Int = minOf(
        padWidthPx(sizeDp, density, screenWidthPx),
        padHeightPx(sizeDp, density, screenHeightPx),
    )

    fun padWidthPx(sizeDp: Int, density: Float, screenWidthPx: Int): Int {
        val wanted = (sizeDp.coerceAtLeast(MIN_SIZE_DP) * density).roundToInt()
        val maxFit = screenWidthPx - dp(24, density)
        val minPx = dp(MIN_SIZE_DP, density)
        return clamp(wanted, minPx, maxOf(minPx, maxFit))
    }

    fun padHeightPx(sizeDp: Int, density: Float, screenHeightPx: Int): Int {
        val wanted = (sizeDp.coerceAtLeast(MIN_SIZE_DP) * density).roundToInt()
        val maxFit = screenHeightPx - dp(96, density) - titleBarPx(density)
        val minPx = dp(MIN_SIZE_DP, density)
        return clamp(wanted, minPx, maxOf(minPx, maxFit))
    }

    const val AUTO_SLOT_CELL_DP = 26
    const val AUTO_SLOT_GAP_DP = 4
    const val AUTO_SLOT_STAR_GAP_DP = 10
    const val AUTO_SLOT_CLOSE_RESERVE_DP = 36
    const val AUTO_SLOT_COUNT = 5
    const val AUTO_SLOT_FADE_DP = 13
    const val AUTO_TRASH_SLIDE_MS = 460L

    fun autoSlotStripPx(padWidthPx: Int, titlePx: Int, density: Float): Int =
        (
            padWidthPx -
                titlePx * 2 -
                dp(AUTO_SLOT_STAR_GAP_DP, density) -
                dp(AUTO_SLOT_CLOSE_RESERVE_DP, density)
            ).coerceAtLeast(0)

    /**
     * Fade on the overflowing edge only. Never eat the one visible cell,
     * and never grow to half the window (that looks like the slots vanished).
     */
    fun autoSlotFadePx(viewportPx: Int, density: Float): Int {
        val cell = dp(AUTO_SLOT_CELL_DP, density)
        if (viewportPx <= cell) return 0
        val fade = dp(AUTO_SLOT_FADE_DP, density)
        return fade.coerceAtMost(viewportPx - cell).coerceAtMost((viewportPx * 0.22f).toInt())
    }

    fun autoSlotRowPx(density: Float, count: Int = AUTO_SLOT_COUNT): Int {
        val n = count.coerceAtLeast(1)
        val cell = dp(AUTO_SLOT_CELL_DP, density)
        val gap = dp(AUTO_SLOT_GAP_DP, density)
        return cell * n + gap * (n - 1)
    }

    /** HSV viewport beside the star: trash stays outside when not compact. */
    fun autoSlotViewportPx(
        padWidthPx: Int,
        titlePx: Int,
        density: Float,
        compact: Boolean,
    ): Int {
        val strip = autoSlotStripPx(padWidthPx, titlePx, density)
        if (compact) return strip
        val cell = dp(AUTO_SLOT_CELL_DP, density)
        val gap = dp(AUTO_SLOT_GAP_DP, density)
        return (strip - cell - gap).coerceAtLeast(0)
    }

    /**
     * After scrolling to the end, slot [count] sits fully in the viewport —
     * left of the close (X), not clipped by the parent. End fade is off at max scroll.
     */
    fun autoSlotEndRevealsLast(
        viewportPx: Int,
        density: Float,
        count: Int = AUTO_SLOT_COUNT,
    ): Boolean {
        val cell = dp(AUTO_SLOT_CELL_DP, density)
        val gap = dp(AUTO_SLOT_GAP_DP, density)
        if (viewportPx < cell) return false
        val n = count.coerceAtLeast(1)
        val row = autoSlotRowPx(density, n)
        val scroll = (row - viewportPx).coerceAtLeast(0)
        val lastLeft = (n - 1) * (cell + gap)
        val lastRight = lastLeft + cell
        return lastLeft >= scroll && lastRight <= scroll + viewportPx
    }

    /** True when 1–5 plus trailing trash can show only one cell. */
    fun autoSlotChromeCompact(padWidthPx: Int, titlePx: Int, density: Float): Boolean {
        val strip = autoSlotStripPx(padWidthPx, titlePx, density)
        val cell = dp(AUTO_SLOT_CELL_DP, density)
        val gap = dp(AUTO_SLOT_GAP_DP, density)
        return strip - cell - gap < cell * 2 + gap
    }

    /** Straight lerp. No hop, tilt, or scale pulse. */
    fun trashFlightX(t: Float, from: Float, to: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return from + (to - from) * u
    }

    fun trashFlightY(t: Float, from: Float, to: Float): Float =
        trashFlightX(t, from, to)

    fun trashFlightScale(t: Float, from: Float, to: Float): Float =
        trashFlightX(t, from, to)

    /**
     * Equal-power dissolve. In and out overlap the whole way, so the
     * trash never pops, gaps, or cuts hard when it leaves the row.
     */
    fun trashFadeOut(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return kotlin.math.cos(u * kotlin.math.PI / 2.0).toFloat()
    }

    fun trashFadeIn(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return kotlin.math.sin(u * kotlin.math.PI / 2.0).toFloat()
    }

    fun titleChromePx(density: Float): Int = dp(TITLE_HEIGHT_DP, density)

    fun titlePadTopPx(density: Float): Int = dp(TITLE_PAD_TOP_DP, density)

    fun titlePadBottomPx(density: Float): Int = dp(TITLE_PAD_BOTTOM_DP, density)

    fun titleBarPx(density: Float): Int =
        titleChromePx(density) + titlePadTopPx(density) + titlePadBottomPx(density)

    fun dp(value: Int, density: Float): Int = (value * density).roundToInt()

    fun dp(value: Float, density: Float): Float = value * density

    fun hypot(dx: Float, dy: Float): Float = hypot(dx.toDouble(), dy.toDouble()).toFloat()

    fun scrollScale(pref: Int): Float =
        (clamp(pref, MIN_SENSITIVITY, MAX_SENSITIVITY) / 100f) * SCROLL_GAIN

    fun screenSwipeDuration(heldMs: Long): Long =
        heldMs.coerceIn(SCREEN_SWIPE_MIN_MS, SCREEN_SWIPE_MAX_MS)

    data class Point(val x: Float, val y: Float)

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float, inflate: Float = 0f): Boolean =
            x >= left - inflate &&
                x <= right + inflate &&
                y >= top - inflate &&
                y <= bottom + inflate
    }

    /** Screen point minus the pad's current origin. */
    fun screenMinusOrigin(
        screenX: Float,
        screenY: Float,
        originX: Float,
        originY: Float,
    ): Point = Point(screenX - originX, screenY - originY)

    /** Bottom-left hot corner used to summon the pad. */
    fun leftCornerContains(
        x: Float,
        y: Float,
        screenW: Float,
        screenH: Float,
        sizePx: Float,
    ): Boolean {
        if (sizePx <= 0f || screenW <= 0f || screenH <= 0f) return false
        return x >= 0f && x <= sizePx && y >= screenH - sizePx && y <= screenH
    }

    fun nextCornerSummonTap(nowMs: Long, lastAtMs: Long, count: Int, gapMs: Long = CORNER_SUMMON_GAP_MS): Int {
        if (count <= 0 || nowMs - lastAtMs > gapMs) return 1
        return count + 1
    }

    fun cornerSummonReady(count: Int): Boolean = count >= CORNER_SUMMON_TAPS

    /**
     * Origin is on the floating pad when the subtracted local point
     * lands inside the pad. That hit must click through to the layer below.
     */
    fun padContainsScreen(
        screenX: Float,
        screenY: Float,
        padX: Float,
        padY: Float,
        padW: Float,
        padH: Float,
    ): Boolean {
        if (padW <= 0f || padH <= 0f) return false
        val local = screenMinusOrigin(screenX, screenY, padX, padY)
        return local.x >= 0f && local.y >= 0f && local.x < padW && local.y < padH
    }

    /** Finger up / left → forward, matching Apple-style natural scroll. */
    fun scrollActionForward(dx: Float, dy: Float): Boolean =
        if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) dy < 0f else dx < 0f

    /** Two-finger travel that should hit the sidebar edge-swipe, not page scroll. */
    fun twoFingerPrefersHorizontal(dx: Float, dy: Float): Boolean =
        abs(dx) >= abs(dy)

    /**
     * Horizontal two-finger binds Ava's drawer only while Ava's own UI is in front.
     * Recents, other apps, and floating windows must not pull the Ava sidebar.
     */
    fun twoFingerBindsSidebar(
        appWindowUnderCursor: Boolean,
        avaBrowserUnderCursor: Boolean,
        avaActivityResumed: Boolean,
        foregroundPackage: String,
        avaPackage: String,
    ): Boolean {
        if (appWindowUnderCursor) return false
        if (avaBrowserUnderCursor) return true
        if (!avaActivityResumed) return false
        if (foregroundPackage.isNotBlank() &&
            avaPackage.isNotBlank() &&
            foregroundPackage != avaPackage
        ) {
            return false
        }
        return true
    }

    /**
     * Prefer a non-Ava window under the cursor or as the active root, so Recents
     * sitting on top of a still-resumed Ava activity does not look like Ava chrome.
     */
    fun twoFingerForegroundPackage(
        atCursor: String,
        activeWindow: String,
        avaPackage: String,
    ): String {
        if (atCursor.isNotBlank() && atCursor != avaPackage) return atCursor
        if (activeWindow.isNotBlank() && activeWindow != avaPackage) return activeWindow
        return atCursor.ifBlank { activeWindow }
    }

    fun avoidOverlay(
        x: Float,
        y: Float,
        blocked: Box?,
        screenWidthPx: Int,
        screenHeightPx: Int,
        insetPx: Float,
        gapPx: Float,
    ): Point {
        val inset = insetPx.coerceAtLeast(1f)
        val maxX = (screenWidthPx - inset).coerceAtLeast(inset)
        val maxY = (screenHeightPx - inset).coerceAtLeast(inset)
        val px = clamp(x, inset, maxX)
        val py = clamp(y, inset, maxY)
        val box = blocked ?: return Point(px, py)
        val grow = gapPx.coerceAtLeast(0f)
        if (!box.contains(px, py, grow)) return Point(px, py)
        val options = listOf(
            Point(px, box.top - grow),
            Point(px, box.bottom + grow),
            Point(box.left - grow, py),
            Point(box.right + grow, py),
        ).filter { it.x in inset..maxX && it.y in inset..maxY }
        if (options.isEmpty()) {
            val cx = clamp(screenWidthPx / 2f, inset, maxX)
            val cy = clamp(screenHeightPx * 0.28f, inset, maxY)
            return if (!box.contains(cx, cy, grow)) Point(cx, cy) else Point(cx, inset)
        }
        return options.minBy { hypot(it.x - px, it.y - py) }
    }

    fun cursorNudgeDecay(elapsedMs: Long, durationMs: Long = SCROLL_NUDGE_MS): Float {
        if (durationMs <= 0L || elapsedMs < 0L || elapsedMs >= durationMs) return 0f
        return 1f - elapsedMs.toFloat() / durationMs.toFloat()
    }

    /** Chevron travel stays on the scroll side of the pad, never the opposite half. */
    fun scrollHintOffset(progress: Float, spanPx: Float): Float =
        progress.coerceIn(0f, 1f) * spanPx.coerceAtLeast(0f)

    fun scrollHintAlpha(progress: Float, peak: Int = SCROLL_HINT_PEAK_ALPHA): Int {
        val t = progress.coerceIn(0f, 1f)
        // Ease-out curve: fades quickly at first, then slows down for a natural feel
        val eased = 1f - (t * t)
        val cap = peak.coerceAtLeast(0)
        return (eased * cap).toInt().coerceIn(0, cap)
    }

    fun scrollDirection(dx: Float, dy: Float): Point {
        val mag = hypot(dx, dy)
        if (mag < 0.01f) return Point(0f, 1f)
        return Point(dx / mag, dy / mag)
    }

    fun padSizeT(padWidthPx: Float, minWidthPx: Float, maxWidthPx: Float): Float {
        val span = (maxWidthPx - minWidthPx).coerceAtLeast(1f)
        return ((padWidthPx - minWidthPx) / span).coerceIn(0f, 1f)
    }

    /** Two-finger band: thin on a small pad, a little thicker on a large one. */
    fun twoFingerLinkWidth(radiusPx: Float, sizeT: Float): Float {
        val t = sizeT.coerceIn(0f, 1f)
        return radiusPx.coerceAtLeast(0f) * (0.62f + 0.28f * t)
    }

    fun threeFingerLinkWidth(radiusPx: Float, sizeT: Float): Float {
        val t = sizeT.coerceIn(0f, 1f)
        return radiusPx.coerceAtLeast(0f) * (0.26f + 0.10f * t)
    }

    fun scrollPressLean(speedPx: Float, maxLeanPx: Float): Float {
        val speed = speedPx.coerceAtLeast(0f)
        val max = maxLeanPx.coerceAtLeast(0f)
        return max * (speed / (speed + 14f))
    }

    /**
     * Pinch-in or pinch-out. Spreading moves the centroid more, so
     * pinch-out is allowed a bit more travel than pinch-in before it looks like a drag.
     */
    fun isThreeFingerPinch(
        startSpan: Float,
        currentSpan: Float,
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        slopPx: Float = THREE_FINGER_SLOP_PX,
    ): Boolean {
        val scaleDelta = abs(pinchScale(startSpan, currentSpan) - 1f)
        val travel = hypot(currentX - startX, currentY - startY)
        val pinchPx = abs(currentSpan - startSpan)
        val spreading = currentSpan > startSpan
        val need = if (spreading) slopPx * 0.75f else slopPx
        val vsTravel = if (spreading) 0.35f else 0.55f
        return pinchPx >= need && pinchPx >= travel * vsTravel && scaleDelta >= PINCH_SCALE_SLOP
    }

    fun threeFingerKind(
        startSpan: Float,
        currentSpan: Float,
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        slopPx: Float = THREE_FINGER_SLOP_PX,
    ): ThreeFingerKind {
        if (isThreeFingerPinch(startSpan, currentSpan, startX, startY, currentX, currentY, slopPx)) {
            return ThreeFingerKind.PINCH
        }
        val travel = hypot(currentX - startX, currentY - startY)
        val pinchPx = abs(currentSpan - startSpan)
        if (travel >= slopPx && pinchPx < travel * 0.45f) {
            return ThreeFingerKind.SWIPE
        }
        return ThreeFingerKind.NONE
    }

    /**
     * Pinch vs Recents / notification swipe vs MacBook-style drag.
     * Vertical flicks (up = Recents, down = shade) are decided first so they
     * are not stolen as drag. Drag only locks after a longer sideways travel.
     */
    fun threeFingerKindWithDrag(
        startSpan: Float,
        currentSpan: Float,
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        slopPx: Float = THREE_FINGER_SLOP_PX,
        recentsMinPx: Float = THREE_FINGER_RECENTS_MIN_PX,
        dragCommitPx: Float = THREE_FINGER_DRAG_COMMIT_PX,
    ): ThreeFingerKind {
        if (isThreeFingerPinch(startSpan, currentSpan, startX, startY, currentX, currentY, slopPx)) {
            return ThreeFingerKind.PINCH
        }
        val travel = hypot(currentX - startX, currentY - startY)
        val pinchPx = abs(currentSpan - startSpan)
        if (travel < slopPx || pinchPx >= travel * 0.45f) {
            return ThreeFingerKind.NONE
        }
        val dx = currentX - startX
        val dy = currentY - startY
        if (threeFingerSwipeIsRecents(dx, dy, recentsMinPx) ||
            threeFingerSwipeIsNotifications(dx, dy, recentsMinPx)
        ) {
            return ThreeFingerKind.SWIPE
        }
        if (travel >= dragCommitPx) {
            return ThreeFingerKind.DRAG
        }
        return ThreeFingerKind.NONE
    }

    fun threeFingerIsTap(
        travelPx: Float,
        slopPx: Float = TAP_SLOP_PX,
    ): Boolean = travelPx <= slopPx

    fun threeFingerSwipeIsRecents(
        dx: Float,
        dy: Float,
        minPx: Float = THREE_FINGER_RECENTS_MIN_PX,
    ): Boolean {
        if (-dy < minPx) return false
        return -dy >= abs(dx) * 0.85f
    }

    /**
     * Three-finger pull down, the Recents swipe inverted. Only a clearly
     * vertical downward stroke counts; pinch, tap, and sideways travel do not.
     */
    fun threeFingerSwipeIsNotifications(
        dx: Float,
        dy: Float,
        minPx: Float = THREE_FINGER_RECENTS_MIN_PX,
    ): Boolean {
        if (dy < minPx) return false
        return dy >= abs(dx) * 0.85f
    }

    /** Status-bar grab: inside the bar, never at 0 where some panels ignore the pointer. */
    fun shadeGrabY(statusBarPx: Int, density: Float): Float {
        val min = dp(2f, density).coerceAtLeast(2f)
        val bar = statusBarPx.toFloat().coerceAtLeast(min)
        return (bar * 0.42f).coerceIn(min, (bar - 1f).coerceAtLeast(min))
    }

    /** Expanded shade handle: mid-panel so a second pull can drag it closed. */
    fun shadeExpandedGrabY(screenHeightPx: Int): Float =
        screenHeightPx.coerceAtLeast(1) * 0.48f

    /**
     * Pad travel → screen travel. A full stroke on the pad covers the shade.
     * Follow is immediate; this is only the scale, not a delay.
     */
    fun shadeFollowGain(padHeightPx: Float, screenHeightPx: Int): Float {
        val pad = padHeightPx.coerceAtLeast(1f)
        val screen = screenHeightPx.coerceAtLeast(1).toFloat()
        return (screen / pad).coerceIn(2.5f, 10f)
    }

    fun shadeFollowY(currentY: Float, dy: Float, grabY: Float, maxY: Float): Float {
        val lo = minOf(grabY, maxY)
        val hi = maxOf(grabY, maxY)
        return (currentY + dy).coerceIn(lo, hi)
    }

    fun shadeSwipeTravelPx(
        padDy: Float,
        padHeightPx: Float,
        screenHeightPx: Int,
    ): Float {
        val min = screenHeightPx.coerceAtLeast(1) * SHADE_SWIPE_MIN_SCREEN
        val mapped = abs(padDy) * shadeFollowGain(padHeightPx, screenHeightPx)
        return maxOf(min, mapped)
    }

    fun scrollPressCenter(
        originX: Float,
        originY: Float,
        dx: Float,
        dy: Float,
        leanPx: Float,
    ): Point {
        val dir = scrollDirection(dx, dy)
        val lean = leanPx.coerceAtLeast(0f)
        return Point(originX + dir.x * lean, originY + dir.y * lean)
    }

    /** Keep the scroll stroke on-screen so a cursor on the edge can still pan. */
    fun scrollAnchor(
        x: Float,
        y: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        insetPx: Float,
    ): Point {
        val inset = insetPx.coerceAtLeast(1f)
        val maxX = (screenWidthPx - inset).coerceAtLeast(inset)
        val maxY = (screenHeightPx - inset).coerceAtLeast(inset)
        return Point(clamp(x, inset, maxX), clamp(y, inset, maxY))
    }

    data class ScrollRatchet(val x: Float, val y: Float, val wrapped: Boolean)

    /** Shrink the preferred inset so a small mirror still has a drag range. */
    fun scrollInsetPx(width: Float, height: Float, preferred: Float): Float {
        val room = minOf(width, height) / 2f - 1f
        return preferred.coerceIn(0f, room.coerceAtLeast(0f))
    }

    fun scrollUsesWheel(
        width: Float,
        height: Float,
        inset: Float,
        minTravel: Float = SCROLL_RATCHET_MIN_TRAVEL,
    ): Boolean {
        val travelX = (width - 2f * inset).coerceAtLeast(0f)
        val travelY = (height - 2f * inset).coerceAtLeast(0f)
        return travelX < minTravel && travelY < minTravel
    }

    /** Hand travel on the pad → wheel at a fixed origin. Not a slide length. */
    fun scrollWheelAxis(deltaPx: Float, refPx: Float = SCROLL_WHEEL_REF_PX): Float =
        (deltaPx / refPx.coerceAtLeast(1f)).coerceIn(-1f, 1f)

    /**
     * Keep a drag-scroll inside a small window. When the next step would leave
     * the inset box, jump to the opposite edge so two-finger travel can continue.
     */
    fun scrollRatchet(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
    ): ScrollRatchet {
        val loX = minOf(minX, maxX)
        val hiX = maxOf(minX, maxX)
        val loY = minOf(minY, maxY)
        val hiY = maxOf(minY, maxY)
        val nx = x + dx
        val ny = y + dy
        if (nx in loX..hiX && ny in loY..hiY) {
            return ScrollRatchet(nx, ny, wrapped = false)
        }
        if (hiX - loX < 1f || hiY - loY < 1f) {
            return ScrollRatchet(x.coerceIn(loX, hiX), y.coerceIn(loY, hiY), wrapped = false)
        }
        val outX = nx < loX || nx > hiX
        val outY = ny < loY || ny > hiY
        val wrapY = outY && (!outX || abs(dy) >= abs(dx))
        return if (wrapY) {
            ScrollRatchet(
                x = nx.coerceIn(loX, hiX),
                y = if (dy < 0f) hiY else loY,
                wrapped = true,
            )
        } else {
            ScrollRatchet(
                x = if (dx < 0f) hiX else loX,
                y = ny.coerceIn(loY, hiY),
                wrapped = true,
            )
        }
    }

    fun scrollEnd(
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        edgePadPx: Float,
    ): Point {
        val pad = edgePadPx.coerceAtLeast(1f)
        val maxX = (screenWidthPx - pad).coerceAtLeast(pad)
        val maxY = (screenHeightPx - pad).coerceAtLeast(pad)
        return Point(clamp(startX + dx, pad, maxX), clamp(startY + dy, pad, maxY))
    }

    fun cursorPulseScale(elapsedMs: Long, durationMs: Long, extra: Float): Float {
        if (durationMs <= 0L || elapsedMs < 0L || elapsedMs >= durationMs) return 1f
        val t = elapsedMs.toFloat() / durationMs.toFloat()
        return 1f + extra.coerceAtLeast(0f) * kotlin.math.sin((Math.PI * t).toFloat())
    }

    fun travelTickChanged(previousPx: Float, nextPx: Float, stepPx: Float): Boolean =
        com.example.ava.ui.haptic.travelTickChanged(previousPx, nextPx, stepPx)

    enum class TwoFingerTapKind { NONE, BACK }

    /**
     * Two-finger tap together → Back. Any scroll or travel past slop is neither.
     * Staggered two-finger tap no longer triggers right-click.
     */
    fun twoFingerTapKind(
        tapCandidate: Boolean,
        scrolled: Boolean,
        maxMovePx: Float,
        elapsedMs: Long,
        arrivalGapMs: Long,
        slopPx: Float = TAP_SLOP_PX,
        togetherMs: Long = TWO_FINGER_TOGETHER_MS,
        maxTapMs: Long = TWO_FINGER_TAP_MAX_MS,
    ): TwoFingerTapKind {
        if (!tapCandidate || scrolled || maxMovePx > slopPx) return TwoFingerTapKind.NONE
        if (elapsedMs !in TWO_FINGER_TAP_MIN_MS..maxTapMs) return TwoFingerTapKind.NONE
        return if (arrivalGapMs <= togetherMs) {
            TwoFingerTapKind.BACK
        } else {
            TwoFingerTapKind.NONE
        }
    }

    fun isTwoFingerBack(
        tapCandidate: Boolean,
        scrolled: Boolean,
        maxMovePx: Float,
        elapsedMs: Long,
        slopPx: Float = TAP_SLOP_PX,
        arrivalGapMs: Long = 0L,
    ): Boolean =
        twoFingerTapKind(
            tapCandidate = tapCandidate,
            scrolled = scrolled,
            maxMovePx = maxMovePx,
            elapsedMs = elapsedMs,
            arrivalGapMs = arrivalGapMs,
            slopPx = slopPx,
        ) == TwoFingerTapKind.BACK

    fun move(
        current: Float,
        delta: Float,
        scale: Float,
        min: Float,
        max: Float,
    ): Float = clamp(current + delta * scale, min, max)

    data class Bounds(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
    )

    fun overlayBounds(
        screenWidthPx: Int,
        screenHeightPx: Int,
        widthPx: Int,
        heightPx: Int,
        marginHPx: Int,
        marginVPx: Int,
        extraLeftPx: Int = 0,
        extraRightPx: Int = 0,
    ): Bounds {
        val w = widthPx.coerceAtLeast(0)
        val h = heightPx.coerceAtLeast(0)
        val mh = marginHPx.coerceAtLeast(0)
        val mv = marginVPx.coerceAtLeast(0)
        val minX = maxOf(mh, extraLeftPx.coerceAtLeast(0))
        var maxX = (screenWidthPx - w) - maxOf(mh, extraRightPx.coerceAtLeast(0))
        if (maxX < minX) maxX = minX
        var maxY = (screenHeightPx - h) - mv
        if (maxY < mv) maxY = mv
        return Bounds(minX, maxX, mv, maxY)
    }

    fun lerp(from: Float, to: Float, t: Float): Float =
        from + (to - from) * t.coerceIn(0f, 1f)

    fun autoFaceIconDp(bodyMinDp: Float): Float {
        val raw = (bodyMinDp * 0.5f) * AUTO_FACE_ICON_FRACTION
        val pivot = (DEFAULT_SIZE_DP * 0.5f) * AUTO_FACE_ICON_FRACTION
        val scaled = if (raw <= pivot) {
            raw
        } else {
            pivot + (raw - pivot) * AUTO_FACE_ICON_GROW
        }
        return scaled.coerceIn(AUTO_FACE_ICON_MIN_DP, AUTO_FACE_ICON_MAX_DP)
    }

    fun autoFaceTextSp(iconDp: Float): Float =
        (iconDp * AUTO_FACE_TEXT_FROM_ICON)
            .coerceIn(AUTO_FACE_TEXT_MIN_SP, AUTO_FACE_TEXT_MAX_SP)

    /** Play-page dial circle: fit three across, shrink with the row. */
    fun autoFaceDialCircleDp(bodyWidthDp: Float, iconDp: Float): Float {
        val column = ((bodyWidthDp - 16f) / 3f).coerceAtLeast(32f)
        val fitted = column * 0.80f
        val fromIcon = iconDp * 1.08f
        return minOf(fitted, fromIcon).coerceIn(28f, 44f)
    }

    fun autoFaceDialTextSp(textSp: Float): Float =
        (textSp * 0.84f).coerceIn(12f, 17.5f)

    fun autoFaceDialPipDp(circleDp: Float): Float =
        (circleDp * 0.40f).coerceIn(14f, 18f)

    /**
     * Arm-page play/record: fill the stacked row. 160 stays compact;
     * a large pad must actually get large buttons.
     */
    fun autoFaceActionIconDp(
        bodyHeightDp: Float,
        rows: Int,
        iconDp: Float,
        prominent: Boolean,
    ): Float {
        val row = (bodyHeightDp / rows.coerceAtLeast(1)).coerceAtLeast(48f)
        val fitted = row * if (prominent) 0.34f else 0.46f
        val lo = if (prominent) 32f else 28f
        val hi = if (prominent) 96f else 80f
        return fitted.coerceIn(lo, hi)
    }

    fun autoFaceActionTextSp(iconDp: Float, prominent: Boolean): Float {
        val raw = iconDp * if (prominent) 0.40f else 0.38f
        val lo = if (prominent) 13f else 12f
        val hi = if (prominent) 32f else 28f
        return raw.coerceIn(lo, hi)
    }

    fun lerpNorm(norm: Float, min: Int, max: Int): Int {
        val t = norm.coerceIn(0f, 1f)
        val span = max - min
        if (span <= 0) return min
        return clamp(min + (span * t).roundToInt(), min, max)
    }

    fun toNorm(value: Int, min: Int, max: Int): Float {
        val span = max - min
        if (span <= 0) return 0f
        return ((value - min).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Four-finger lift: window top near the screen top commits; mid-screen abandons. */
    fun liftShouldCommit(windowY: Int, screenHeightPx: Int): Boolean {
        if (screenHeightPx <= 0) return windowY <= 0
        return windowY <= (screenHeightPx * LIFT_COMMIT_FRACTION).roundToInt()
    }

    fun overlayLiftShouldCommit(translationY: Float, heightPx: Int): Boolean {
        if (heightPx <= 0) return translationY < 0f
        return -translationY >= heightPx * OVERLAY_LIFT_COMMIT_FRACTION
    }

    fun overlayLiftTranslation(current: Float, dy: Float, heightPx: Int): Float {
        val min = if (heightPx > 0) -heightPx.toFloat() else current + dy
        return (current + dy).coerceIn(min, 0f)
    }

    fun pinchCentroid(points: List<Point>): Point {
        if (points.isEmpty()) return Point(0f, 0f)
        var x = 0f
        var y = 0f
        for (point in points) {
            x += point.x
            y += point.y
        }
        val n = points.size.toFloat()
        return Point(x / n, y / n)
    }

    /** Mean distance from the centroid — stable for 2+ fingers. */
    fun pinchSpan(points: List<Point>): Float {
        if (points.size < 2) return 0f
        val center = pinchCentroid(points)
        var sum = 0f
        for (point in points) {
            sum += hypot(point.x - center.x, point.y - center.y)
        }
        return sum / points.size
    }

    fun pinchScale(startSpan: Float, currentSpan: Float): Float {
        if (startSpan <= PINCH_MIN_SPAN_PX) return 1f
        return currentSpan / startSpan
    }

    /**
     * Keep a pinch scale uniform so both axes stay inside [minW]..[maxW] /
     * [minH]..[maxH] instead of stretching the window.
     */
    fun pinchLimitedScale(
        scale: Float,
        originW: Int,
        originH: Int,
        minW: Int,
        minH: Int,
        maxW: Int,
        maxH: Int,
    ): Float {
        if (originW <= 0 || originH <= 0) return 1f
        val minScale = maxOf(minW.toFloat() / originW, minH.toFloat() / originH)
        val maxScale = minOf(maxW.toFloat() / originW, maxH.toFloat() / originH)
        return clamp(scale, minScale, maxScale)
    }

    /** Keep [oldScale] continuous after a finger is added or lifted. */
    fun pinchRenormalizeStartSpan(
        oldStartSpan: Float,
        oldCurrentSpan: Float,
        newCurrentSpan: Float,
    ): Float {
        if (oldStartSpan <= PINCH_MIN_SPAN_PX || oldCurrentSpan <= 0f) {
            return newCurrentSpan.coerceAtLeast(PINCH_MIN_SPAN_PX)
        }
        if (newCurrentSpan <= PINCH_MIN_SPAN_PX) return oldStartSpan
        val scale = (oldCurrentSpan / oldStartSpan).coerceAtLeast(0.05f)
        return newCurrentSpan / scale
    }

    fun pinchFrame(
        originW: Int,
        originH: Int,
        centerX: Float,
        centerY: Float,
        scale: Float,
        minW: Int,
        minH: Int,
        maxW: Int,
        maxH: Int,
    ): WindowFrame {
        val limited = pinchLimitedScale(scale, originW, originH, minW, minH, maxW, maxH)
        val width = clamp((originW * limited).roundToInt(), minW, maxW)
        val height = clamp((originH * limited).roundToInt(), minH, maxH)
        return WindowFrame(
            x = (centerX - width / 2f).roundToInt(),
            y = (centerY - height / 2f).roundToInt(),
            width = width,
            height = height,
        )
    }
}
