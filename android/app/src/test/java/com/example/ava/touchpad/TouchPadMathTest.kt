package com.example.ava.touchpad

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchPadMathTest {

    @Test
    fun sizeDpSnapsToSixteen() {
        assertEquals(160, TouchPadMath.sizeDp(160))
        assertEquals(160, TouchPadMath.sizeDp(165))
        assertEquals(176, TouchPadMath.sizeDp(176))
        assertEquals(320, TouchPadMath.sizeDp(400))
        assertEquals(160, TouchPadMath.sizeDp(10))
    }

    @Test
    fun padWidthAndHeightClampIndependently() {
        assertEquals(200, TouchPadMath.padWidthPx(200, 1f, 1000))
        assertEquals(280, TouchPadMath.padHeightPx(280, 1f, 2000))
        assertEquals(160, TouchPadMath.padWidthPx(80, 1f, 1000))
        assertEquals(976, TouchPadMath.padWidthPx(2000, 1f, 1000))
        assertEquals(1872, TouchPadMath.padHeightPx(4000, 1f, 2000))
    }

    @Test
    fun sliderRoundTrip() {
        val snapped = TouchPadMath.snapSizeFromSlider(0.5f)
        assertEquals(0, snapped % 16)
        assertEquals(snapped, TouchPadMath.sizeDp(snapped))
    }

    @Test
    fun leftCornerSummonCountsThreeTaps() {
        assertEquals(true, TouchPadMath.leftCornerContains(10f, 990f, 1000f, 1000f, 56f))
        assertEquals(false, TouchPadMath.leftCornerContains(80f, 990f, 1000f, 1000f, 56f))
        assertEquals(false, TouchPadMath.leftCornerContains(10f, 10f, 1000f, 1000f, 56f))
        assertEquals(1, TouchPadMath.nextCornerSummonTap(1000L, 0L, 0))
        assertEquals(2, TouchPadMath.nextCornerSummonTap(1400L, 1000L, 1))
        assertEquals(3, TouchPadMath.nextCornerSummonTap(1800L, 1400L, 2))
        assertEquals(1, TouchPadMath.nextCornerSummonTap(3000L, 1400L, 2))
        assertEquals(true, TouchPadMath.cornerSummonReady(3))
        assertEquals(false, TouchPadMath.cornerSummonReady(2))
    }

    @Test
    fun idleCloseDefaultsToThirtyMinutes() {
        assertEquals(30L * 60L * 1000L, TouchPadMath.DEFAULT_IDLE_CLOSE_MS)
    }

    @Test
    fun defaultSensitivityIsOnePointEight() {
        assertEquals(1.8f, TouchPadMath.sensitivityScale(100), 0.001f)
        assertEquals(0.72f, TouchPadMath.sensitivityScale(40), 0.001f)
    }

    @Test
    fun opacityByteMapsPercent() {
        assertEquals(224, TouchPadMath.opacityByte(88))
        assertEquals(255, TouchPadMath.opacityByte(100))
        assertEquals(30, TouchPadMath.opacityByte(12))
    }

    @Test
    fun cursorMoveClampsToScreen() {
        val next = TouchPadMath.move(10f, 100f, 1.8f, 0f, 50f)
        assertEquals(50f, next, 0.001f)
    }

    @Test
    fun overlayBoundsKeepMargins() {
        val bounds = TouchPadMath.overlayBounds(
            screenWidthPx = 1000,
            screenHeightPx = 2000,
            widthPx = 400,
            heightPx = 432,
            marginHPx = 8,
            marginVPx = 24,
        )
        assertEquals(8, bounds.minX)
        assertEquals(592, bounds.maxX)
        assertEquals(24, bounds.minY)
        assertEquals(1544, bounds.maxY)
    }

    @Test
    fun overlayBoundsReserveSidebarEdgeEqually() {
        val left = TouchPadMath.overlayBounds(
            screenWidthPx = 1000,
            screenHeightPx = 2000,
            widthPx = 400,
            heightPx = 432,
            marginHPx = 8,
            marginVPx = 24,
            extraLeftPx = 48,
        )
        assertEquals(48, left.minX)
        assertEquals(592, left.maxX)
        val right = TouchPadMath.overlayBounds(
            screenWidthPx = 1000,
            screenHeightPx = 2000,
            widthPx = 400,
            heightPx = 432,
            marginHPx = 8,
            marginVPx = 24,
            extraRightPx = 48,
        )
        assertEquals(8, right.minX)
        assertEquals(552, right.maxX)
    }

    @Test
    fun normRoundTrip() {
        val x = TouchPadMath.lerpNorm(0.25f, 8, 108)
        assertEquals(0.25f, TouchPadMath.toNorm(x, 8, 108), 0.001f)
    }

    @Test
    fun travelTicksOnlyWhenCrossingAStep() {
        assertEquals(false, TouchPadMath.travelTickChanged(0f, 10f, 14f))
        assertEquals(true, TouchPadMath.travelTickChanged(0f, 14f, 14f))
        assertEquals(false, TouchPadMath.travelTickChanged(14f, 20f, 14f))
        assertEquals(true, TouchPadMath.travelTickChanged(14f, 28f, 14f))
        assertEquals(false, TouchPadMath.travelTickChanged(10f, 10f, 14f))
        assertEquals(false, TouchPadMath.travelTickChanged(4f, 8f, 0f))
    }

    @Test
    fun avoidOverlayPushesPointOffThePad() {
        val pad = TouchPadMath.Box(100f, 800f, 400f, 1100f)
        val hit = TouchPadMath.avoidOverlay(200f, 900f, pad, 1000, 2000, 28f, 20f)
        assertEquals(false, pad.contains(hit.x, hit.y, 20f))
        val clear = TouchPadMath.avoidOverlay(500f, 200f, pad, 1000, 2000, 28f, 20f)
        assertEquals(500f, clear.x, 0.001f)
        assertEquals(200f, clear.y, 0.001f)
    }

    @Test
    fun holdPointerScaleIsGentlerThanPointerScale() {
        val slowHold = TouchPadMath.holdPointerScale(100, 4f, 0f)
        val slowMove = TouchPadMath.pointerScale(100, 4f, 0f)
        val flickHold = TouchPadMath.holdPointerScale(100, 40f, 0f)
        val flickMove = TouchPadMath.pointerScale(100, 40f, 0f)
        assertEquals(true, slowHold < slowMove)
        assertEquals(true, flickHold < flickMove)
        assertEquals(true, flickHold / slowHold < flickMove / slowMove)
    }

    @Test
    fun scrollScaleStaysGentle() {
        assertEquals(0.42f, TouchPadMath.scrollScale(100), 0.001f)
        assertEquals(true, TouchPadMath.scrollScale(100) < TouchPadMath.sensitivityScale(100))
    }

    @Test
    fun naturalScrollMapsFingerDownToBackward() {
        assertEquals(false, TouchPadMath.scrollActionForward(0f, 20f))
        assertEquals(true, TouchPadMath.scrollActionForward(0f, -20f))
        assertEquals(false, TouchPadMath.scrollActionForward(20f, 0f))
        assertEquals(true, TouchPadMath.scrollActionForward(-20f, 0f))
    }

    @Test
    fun scrollNudgeFadesOut() {
        assertEquals(1f, TouchPadMath.cursorNudgeDecay(0, 180), 0.001f)
        assertEquals(0f, TouchPadMath.cursorNudgeDecay(180, 180), 0.001f)
        assertEquals(true, TouchPadMath.cursorNudgeDecay(90, 180) in 0.4f..0.6f)
    }

    @Test
    fun scrollHintStaysInPositiveRangeAndFades() {
        assertEquals(0f, TouchPadMath.scrollHintOffset(0f, 100f), 0.001f)
        assertEquals(42f, TouchPadMath.scrollHintOffset(0.42f, 100f), 0.001f)
        assertEquals(100f, TouchPadMath.scrollHintOffset(1f, 100f), 0.001f)
        assertEquals(0f, TouchPadMath.scrollHintOffset(-0.4f, 100f), 0.001f)
        assertEquals(0f, TouchPadMath.scrollHintOffset(0.5f, -20f), 0.001f)
        assertEquals(44, TouchPadMath.scrollHintAlpha(0f))
        assertEquals(0, TouchPadMath.scrollHintAlpha(1f))
        assertEquals(true, TouchPadMath.scrollHintAlpha(0.5f) in 20..24)
        assertEquals(160, TouchPadMath.scrollHintAlpha(0f, TouchPadMath.SCROLL_PRESS_PEAK_ALPHA))
    }

    @Test
    fun scrollPressFollowsHandDirection() {
        val down = TouchPadMath.scrollDirection(0f, 20f)
        assertEquals(0f, down.x, 0.001f)
        assertEquals(1f, down.y, 0.001f)
        val left = TouchPadMath.scrollDirection(-8f, 0f)
        assertEquals(-1f, left.x, 0.001f)
        assertEquals(0f, left.y, 0.001f)
        val center = TouchPadMath.scrollPressCenter(100f, 100f, 0f, 10f, 8f)
        assertEquals(100f, center.x, 0.001f)
        assertEquals(108f, center.y, 0.001f)
        assertEquals(true, TouchPadMath.scrollPressLean(40f, 10f) > TouchPadMath.scrollPressLean(4f, 10f))
    }

    @Test
    fun twoFingerLinkGetsThickerOnALargerPad() {
        assertEquals(0f, TouchPadMath.padSizeT(160f, 160f, 320f), 0.001f)
        assertEquals(1f, TouchPadMath.padSizeT(320f, 160f, 320f), 0.001f)
        val thin = TouchPadMath.twoFingerLinkWidth(20f, 0f)
        val thick = TouchPadMath.twoFingerLinkWidth(20f, 1f)
        assertEquals(true, thin < thick)
        assertEquals(true, thin >= 20f * 0.6f)
        assertEquals(true, thick > thin)
    }

    @Test
    fun threeFingerPinchAndSwipeAreDistinct() {
        assertEquals(
            TouchPadMath.ThreeFingerKind.PINCH,
            TouchPadMath.threeFingerKind(40f, 72f, 100f, 100f, 104f, 102f),
        )
        assertEquals(
            TouchPadMath.ThreeFingerKind.SWIPE,
            TouchPadMath.threeFingerKind(40f, 42f, 100f, 180f, 102f, 110f),
        )
        assertEquals(
            TouchPadMath.ThreeFingerKind.NONE,
            TouchPadMath.threeFingerKind(40f, 41f, 100f, 100f, 101f, 101f),
        )
        assertEquals(true, TouchPadMath.threeFingerSwipeIsRecents(4f, -48f))
        assertEquals(false, TouchPadMath.threeFingerSwipeIsRecents(40f, -10f))
        assertEquals(false, TouchPadMath.threeFingerSwipeIsRecents(0f, 48f))
        assertEquals(true, TouchPadMath.threeFingerSwipeIsNotifications(4f, 48f))
        assertEquals(true, TouchPadMath.threeFingerSwipeIsNotifications(0f, 36f))
        assertEquals(false, TouchPadMath.threeFingerSwipeIsNotifications(4f, -48f))
        assertEquals(false, TouchPadMath.threeFingerSwipeIsNotifications(40f, 10f))
        assertEquals(false, TouchPadMath.threeFingerSwipeIsNotifications(0f, 20f))
        assertEquals(false, TouchPadMath.threeFingerSwipeIsNotifications(48f, 40f))
        val grab = TouchPadMath.shadeGrabY(84, 3f)
        assertEquals(true, grab >= 2f && grab < 84f)
        assertEquals(true, TouchPadMath.shadeFollowGain(160f, 800) >= 2.5f)
        assertEquals(400f, TouchPadMath.shadeFollowY(100f, 50f, 8f, 400f), 0.01f)
        assertEquals(8f, TouchPadMath.shadeFollowY(20f, -40f, 8f, 400f), 0.01f)
        assertEquals(true, TouchPadMath.shadeExpandedGrabY(1000) > 400f)
        assertEquals(360f, TouchPadMath.shadeSwipeTravelPx(20f, 160f, 800), 0.01f)
        assertEquals(true, TouchPadMath.shadeSwipeTravelPx(200f, 160f, 800) > 360f)
    }

    @Test
    fun threeFingerDragDoesNotStealRecents() {
        assertEquals(
            TouchPadMath.ThreeFingerKind.SWIPE,
            TouchPadMath.threeFingerKindWithDrag(40f, 42f, 100f, 180f, 104f, 130f),
        )
        assertEquals(
            TouchPadMath.ThreeFingerKind.NONE,
            TouchPadMath.threeFingerKindWithDrag(40f, 41f, 100f, 100f, 128f, 102f),
        )
        assertEquals(
            TouchPadMath.ThreeFingerKind.SWIPE,
            TouchPadMath.threeFingerKindWithDrag(40f, 41f, 100f, 100f, 104f, 160f),
        )
        assertEquals(
            TouchPadMath.ThreeFingerKind.DRAG,
            TouchPadMath.threeFingerKindWithDrag(40f, 41f, 100f, 100f, 160f, 104f),
        )
        assertEquals(
            TouchPadMath.ThreeFingerKind.PINCH,
            TouchPadMath.threeFingerKindWithDrag(40f, 72f, 100f, 100f, 104f, 102f),
        )
        assertEquals(true, TouchPadMath.threeFingerIsTap(8f))
        assertEquals(false, TouchPadMath.threeFingerIsTap(22f))
        assertEquals(
            TouchPadMath.ThreeFingerKind.PINCH,
            TouchPadMath.threeFingerKindWithDrag(40f, 64f, 100f, 100f, 145f, 115f),
        )
    }

    @Test
    fun twoFingerHorizontalGoesToSidebarAxis() {
        assertEquals(true, TouchPadMath.twoFingerPrefersHorizontal(20f, 8f))
        assertEquals(true, TouchPadMath.twoFingerPrefersHorizontal(-16f, 4f))
        assertEquals(false, TouchPadMath.twoFingerPrefersHorizontal(6f, 20f))
    }

    @Test
    fun twoFingerSidebarStaysOnAvaChromeOnly() {
        assertEquals(
            true,
            TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = false,
                avaBrowserUnderCursor = false,
                avaActivityResumed = true,
                foregroundPackage = "com.example.ava",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            true,
            TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = false,
                avaBrowserUnderCursor = true,
                avaActivityResumed = false,
                foregroundPackage = "com.android.launcher3",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            false,
            TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = true,
                avaBrowserUnderCursor = false,
                avaActivityResumed = true,
                foregroundPackage = "com.example.ava",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            false,
            TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = false,
                avaBrowserUnderCursor = false,
                avaActivityResumed = true,
                foregroundPackage = "com.android.launcher3",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            false,
            TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = false,
                avaBrowserUnderCursor = false,
                avaActivityResumed = true,
                foregroundPackage = "com.other.app",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            false,
            TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = false,
                avaBrowserUnderCursor = false,
                avaActivityResumed = false,
                foregroundPackage = "com.example.ava",
                avaPackage = "com.example.ava",
            ),
        )
    }

    @Test
    fun twoFingerForegroundPackagePrefersNonAvaWindow() {
        assertEquals(
            "com.android.launcher3",
            TouchPadMath.twoFingerForegroundPackage(
                atCursor = "com.example.ava",
                activeWindow = "com.android.launcher3",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            "com.other.app",
            TouchPadMath.twoFingerForegroundPackage(
                atCursor = "com.other.app",
                activeWindow = "com.example.ava",
                avaPackage = "com.example.ava",
            ),
        )
        assertEquals(
            "com.example.ava",
            TouchPadMath.twoFingerForegroundPackage(
                atCursor = "com.example.ava",
                activeWindow = "com.example.ava",
                avaPackage = "com.example.ava",
            ),
        )
    }

    @Test
    fun scrollAnchorLeavesRoomAtTheEdge() {
        val anchor = TouchPadMath.scrollAnchor(0f, 2000f, 1000, 2000, 28f)
        assertEquals(28f, anchor.x, 0.001f)
        assertEquals(1972f, anchor.y, 0.001f)
        val end = TouchPadMath.scrollEnd(anchor.x, anchor.y, 0f, 80f, 1000, 2000, 4f)
        assertEquals(true, end.y > anchor.y)
    }

    @Test
    fun screenSwipeDurationMatchesAFingerDrag() {
        assertEquals(220L, TouchPadMath.screenSwipeDuration(80L))
        assertEquals(300L, TouchPadMath.screenSwipeDuration(300L))
        assertEquals(520L, TouchPadMath.screenSwipeDuration(2_000L))
    }

    @Test
    fun doubleTapPulseGrowsThenSettles() {
        assertEquals(1f, TouchPadMath.cursorPulseScale(-1, 280, 0.32f), 0.001f)
        assertEquals(1f, TouchPadMath.cursorPulseScale(280, 280, 0.32f), 0.001f)
        val mid = TouchPadMath.cursorPulseScale(140, 280, 0.32f)
        assertEquals(true, mid > 1.2f)
        assertEquals(true, mid < 1.4f)
    }

    @Test
    fun aiCursorIsTheSameDotOnlyLarger() {
        assertEquals(true, TouchPadMath.AI_CURSOR_DOT_RADIUS_DP > TouchPadMath.CURSOR_DOT_RADIUS_DP)
        assertEquals(true, TouchPadMath.AI_CURSOR_SHADOW_RADIUS_DP > TouchPadMath.CURSOR_SHADOW_RADIUS_DP)
        assertEquals(true, TouchPadMath.AI_TAP_PULSE_EXTRA > TouchPadMath.TAP_PULSE_EXTRA)
        assertEquals(true, TouchPadMath.AI_TAP_PULSE_MS > TouchPadMath.TAP_PULSE_MS)
    }

    @Test
    fun tapPulseIsWeakerThanDoubleTap() {
        val tap = TouchPadMath.cursorPulseScale(90, 180, 0.16f)
        val doubleTap = TouchPadMath.cursorPulseScale(140, 280, 0.32f)
        assertEquals(true, tap > 1.1f)
        assertEquals(true, tap < 1.2f)
        assertEquals(true, tap < doubleTap)
    }

    @Test
    fun twoFingerBackNeedsAShortStillTap() {
        assertEquals(
            true,
            TouchPadMath.isTwoFingerBack(
                tapCandidate = true,
                scrolled = false,
                maxMovePx = 8f,
                elapsedMs = 160L,
            ),
        )
        assertEquals(
            true,
            TouchPadMath.isTwoFingerBack(
                tapCandidate = true,
                scrolled = false,
                maxMovePx = 8f,
                elapsedMs = 20L,
            ),
        )
        assertEquals(
            false,
            TouchPadMath.isTwoFingerBack(
                tapCandidate = true,
                scrolled = false,
                maxMovePx = 8f,
                elapsedMs = 400L,
            ),
        )
        assertEquals(
            false,
            TouchPadMath.isTwoFingerBack(
                tapCandidate = true,
                scrolled = true,
                maxMovePx = 8f,
                elapsedMs = 160L,
            ),
        )
        assertEquals(
            false,
            TouchPadMath.isTwoFingerBack(
                tapCandidate = false,
                scrolled = false,
                maxMovePx = 8f,
                elapsedMs = 160L,
            ),
        )
        assertEquals(
            false,
            TouchPadMath.isTwoFingerBack(
                tapCandidate = true,
                scrolled = false,
                maxMovePx = 20f,
                elapsedMs = 160L,
            ),
        )
    }

    @Test
    fun twoFingerTapTogetherIsBackStaggeredIsRightClick() {
        assertEquals(
            TouchPadMath.TwoFingerTapKind.BACK,
            TouchPadMath.twoFingerTapKind(
                tapCandidate = true,
                scrolled = false,
                maxMovePx = 6f,
                elapsedMs = 140L,
                arrivalGapMs = 40L,
            ),
        )
        assertEquals(
            TouchPadMath.TwoFingerTapKind.SECONDARY,
            TouchPadMath.twoFingerTapKind(
                tapCandidate = true,
                scrolled = false,
                maxMovePx = 6f,
                elapsedMs = 140L,
                arrivalGapMs = 160L,
            ),
        )
        assertEquals(
            TouchPadMath.TwoFingerTapKind.NONE,
            TouchPadMath.twoFingerTapKind(
                tapCandidate = true,
                scrolled = true,
                maxMovePx = 6f,
                elapsedMs = 140L,
                arrivalGapMs = 160L,
            ),
        )
        assertEquals(
            TouchPadMath.TwoFingerTapKind.NONE,
            TouchPadMath.twoFingerTapKind(
                tapCandidate = false,
                scrolled = false,
                maxMovePx = 6f,
                elapsedMs = 140L,
                arrivalGapMs = 160L,
            ),
        )
    }

    @Test
    fun liftCommitsNearTopAndAbandonsInMiddle() {
        assertEquals(true, TouchPadMath.liftShouldCommit(0, 2000))
        assertEquals(true, TouchPadMath.liftShouldCommit(200, 2000))
        assertEquals(false, TouchPadMath.liftShouldCommit(800, 2000))
        assertEquals(true, TouchPadMath.overlayLiftShouldCommit(-900f, 2000))
        assertEquals(false, TouchPadMath.overlayLiftShouldCommit(-200f, 2000))
        assertEquals(0f, TouchPadMath.overlayLiftTranslation(-100f, 40f, 2000), 0.001f)
        assertEquals(-2000f, TouchPadMath.overlayLiftTranslation(-1900f, -200f, 2000), 0.001f)
    }

    @Test
    fun pinchSpanIsMeanDistanceFromCentroid() {
        val span = TouchPadMath.pinchSpan(
            listOf(
                TouchPadMath.Point(0f, 0f),
                TouchPadMath.Point(10f, 0f),
            ),
        )
        assertEquals(5f, span, 0.001f)
        assertEquals(0f, TouchPadMath.pinchSpan(listOf(TouchPadMath.Point(1f, 1f))), 0.001f)
    }

    @Test
    fun pinchScaleIsCurrentOverStart() {
        assertEquals(1f, TouchPadMath.pinchScale(0.5f, 8f), 0.001f)
        assertEquals(2f, TouchPadMath.pinchScale(20f, 40f), 0.001f)
        assertEquals(0.5f, TouchPadMath.pinchScale(40f, 20f), 0.001f)
    }

    @Test
    fun pinchLimitedScaleKeepsAspectInsideBounds() {
        assertEquals(
            2f,
            TouchPadMath.pinchLimitedScale(3f, 200, 100, 80, 80, 400, 200),
            0.001f,
        )
        assertEquals(
            0.4f,
            TouchPadMath.pinchLimitedScale(0.1f, 200, 100, 80, 80, 400, 200),
            0.001f,
        )
    }

    @Test
    fun pinchFrameGrowsAroundCenter() {
        val frame = TouchPadMath.pinchFrame(
            originW = 200,
            originH = 100,
            centerX = 200f,
            centerY = 200f,
            scale = 2f,
            minW = 80,
            minH = 80,
            maxW = 800,
            maxH = 800,
        )
        assertEquals(400, frame.width)
        assertEquals(200, frame.height)
        assertEquals(0, frame.x)
        assertEquals(100, frame.y)
    }

    @Test
    fun pinchRenormalizeKeepsScaleAfterFingerLift() {
        val nextStart = TouchPadMath.pinchRenormalizeStartSpan(
            oldStartSpan = 30f,
            oldCurrentSpan = 60f,
            newCurrentSpan = 40f,
        )
        assertEquals(2f, TouchPadMath.pinchScale(nextStart, 40f), 0.001f)
    }

    @Test
    fun scrollRatchetStaysInside() {
        val next = TouchPadMath.scrollRatchet(50f, 50f, 5f, 8f, 10f, 90f, 10f, 90f)
        assertEquals(55f, next.x, 0.001f)
        assertEquals(58f, next.y, 0.001f)
        assertEquals(false, next.wrapped)
    }

    @Test
    fun scrollRatchetWrapsVerticalAtEdge() {
        val next = TouchPadMath.scrollRatchet(40f, 85f, 0f, 20f, 10f, 90f, 10f, 90f)
        assertEquals(40f, next.x, 0.001f)
        assertEquals(10f, next.y, 0.001f)
        assertEquals(true, next.wrapped)
    }

    @Test
    fun scrollRatchetWrapsUpToBottom() {
        val next = TouchPadMath.scrollRatchet(40f, 15f, 0f, -20f, 10f, 90f, 10f, 90f)
        assertEquals(40f, next.x, 0.001f)
        assertEquals(90f, next.y, 0.001f)
        assertEquals(true, next.wrapped)
    }

    @Test
    fun scrollRatchetWrapsHorizontalAtEdge() {
        val next = TouchPadMath.scrollRatchet(15f, 40f, -20f, 0f, 10f, 90f, 10f, 90f)
        assertEquals(90f, next.x, 0.001f)
        assertEquals(40f, next.y, 0.001f)
        assertEquals(true, next.wrapped)
    }

    @Test
    fun scrollInsetShrinksOnTinyContent() {
        assertEquals(28f, TouchPadMath.scrollInsetPx(200f, 400f, 28f), 0.001f)
        assertEquals(24f, TouchPadMath.scrollInsetPx(50f, 50f, 28f), 0.001f)
        assertEquals(true, TouchPadMath.scrollUsesWheel(50f, 50f, 24f))
        assertEquals(false, TouchPadMath.scrollUsesWheel(200f, 400f, 28f))
    }

    @Test
    fun scrollWheelAxisClamps() {
        assertEquals(0.5f, TouchPadMath.scrollWheelAxis(24f, 48f), 0.001f)
        assertEquals(-1f, TouchPadMath.scrollWheelAxis(-80f, 48f), 0.001f)
        assertEquals(1f, TouchPadMath.scrollWheelAxis(80f, 48f), 0.001f)
    }

    @Test
    fun originOnPadUsesSubtractedPosition() {
        val padX = 200f
        val padY = 400f
        val padW = 320f
        val padH = 280f
        val inside = TouchPadMath.screenMinusOrigin(250f, 450f, padX, padY)
        assertEquals(50f, inside.x, 0.001f)
        assertEquals(50f, inside.y, 0.001f)
        assertEquals(true, TouchPadMath.padContainsScreen(250f, 450f, padX, padY, padW, padH))
        assertEquals(true, TouchPadMath.padContainsScreen(200f, 400f, padX, padY, padW, padH))
        assertEquals(false, TouchPadMath.padContainsScreen(199f, 450f, padX, padY, padW, padH))
        assertEquals(false, TouchPadMath.padContainsScreen(250f, 399f, padX, padY, padW, padH))
        assertEquals(false, TouchPadMath.padContainsScreen(520f, 450f, padX, padY, padW, padH))
        assertEquals(false, TouchPadMath.padContainsScreen(250f, 680f, padX, padY, padW, padH))
    }

    @Test
    fun lerpClampsAndTravels() {
        assertEquals(10f, TouchPadMath.lerp(10f, 20f, 0f), 0.001f)
        assertEquals(20f, TouchPadMath.lerp(10f, 20f, 1f), 0.001f)
        assertEquals(15f, TouchPadMath.lerp(10f, 20f, 0.5f), 0.001f)
        assertEquals(10f, TouchPadMath.lerp(10f, 20f, -1f), 0.001f)
        assertEquals(20f, TouchPadMath.lerp(10f, 20f, 2f), 0.001f)
    }

    @Test
    fun autoFaceIconFollowsHalfTile() {
        assertEquals(35.2f, TouchPadMath.autoFaceIconDp(160f), 0.001f)
        assertEquals(19.008f, TouchPadMath.autoFaceTextSp(35.2f), 0.001f)
        val grown = TouchPadMath.autoFaceIconDp(240f)
        assertEquals(true, grown > 35.2f)
        assertEquals(true, grown <= 41f)
        assertEquals(41f, TouchPadMath.autoFaceIconDp(400f), 0.001f)
        assertEquals(22f, TouchPadMath.autoFaceTextSp(41f), 0.001f)
    }

    @Test
    fun autoFaceDialsFitThreeAcrossOnDefaultPad() {
        val icon = TouchPadMath.autoFaceIconDp(160f)
        val circle = TouchPadMath.autoFaceDialCircleDp(160f, icon)
        assertEquals(true, circle <= 48f * 0.80f + 0.01f)
        assertEquals(true, circle >= 28f)
        assertEquals(16f, TouchPadMath.autoFaceDialTextSp(19.008f), 0.05f)
        val pip = TouchPadMath.autoFaceDialPipDp(circle)
        assertEquals(true, pip < circle)
        assertEquals(true, pip <= 18f)
    }

    @Test
    fun autoFacePlayRecordShrinkOnMinPad() {
        val icon = TouchPadMath.autoFaceIconDp(160f)
        val play = TouchPadMath.autoFaceActionIconDp(160f, rows = 2, iconDp = icon, prominent = false)
        val record = TouchPadMath.autoFaceActionIconDp(160f, rows = 2, iconDp = icon, prominent = false)
        val dial = TouchPadMath.autoFaceDialCircleDp(160f, icon)
        assertEquals(play, record, 0.001f)
        assertEquals(true, play <= dial + 1f)
        assertEquals(true, play <= 38f)
        val text = TouchPadMath.autoFaceActionTextSp(play, prominent = false)
        assertEquals(true, text <= 17f)
        val solo = TouchPadMath.autoFaceActionIconDp(160f, rows = 1, iconDp = icon, prominent = true)
        assertEquals(true, solo < 56f)
        assertEquals(true, TouchPadMath.autoFaceActionTextSp(solo, prominent = true) <= 22f)
    }

    @Test
    fun autoFacePlayRecordGrowOnLargePad() {
        val icon = TouchPadMath.autoFaceIconDp(320f)
        val play = TouchPadMath.autoFaceActionIconDp(320f, rows = 2, iconDp = icon, prominent = false)
        assertEquals(true, play >= 64f)
        assertEquals(true, play <= 80f)
        assertEquals(true, TouchPadMath.autoFaceActionTextSp(play, prominent = false) >= 22f)
        val solo = TouchPadMath.autoFaceActionIconDp(320f, rows = 1, iconDp = icon, prominent = true)
        assertEquals(true, solo >= 80f)
    }

    @Test
    fun autoSlotChromeCompactsWhenOnlyOneCellFits() {
        val title = TouchPadMath.dp(TouchPadMath.TITLE_HEIGHT_DP, 1f)
        assertEquals(true, TouchPadMath.autoSlotChromeCompact(160, title, 1f))
        assertEquals(true, TouchPadMath.autoSlotChromeCompact(201, title, 1f))
        assertEquals(false, TouchPadMath.autoSlotChromeCompact(202, title, 1f))
        assertEquals(false, TouchPadMath.autoSlotChromeCompact(320, title, 1f))
    }

    @Test
    fun autoSlotStripStaysLeftOfCloseAndSmallerThanRow() {
        val title = TouchPadMath.dp(TouchPadMath.TITLE_HEIGHT_DP, 1f)
        val strip = TouchPadMath.autoSlotStripPx(160, title, 1f)
        val row = TouchPadMath.autoSlotRowPx(1f)
        assertEquals(true, strip < 160)
        assertEquals(true, strip < row)
        assertEquals(
            160 - 70 - 10 - 36,
            strip,
        )
    }

    @Test
    fun autoSlotEndScrollRevealsLastOnMinPad() {
        val title = TouchPadMath.dp(TouchPadMath.TITLE_HEIGHT_DP, 1f)
        val compact = TouchPadMath.autoSlotViewportPx(160, title, 1f, compact = true)
        val regular = TouchPadMath.autoSlotViewportPx(220, title, 1f, compact = false)
        assertEquals(true, compact < TouchPadMath.autoSlotRowPx(1f))
        assertEquals(true, TouchPadMath.autoSlotEndRevealsLast(compact, 1f))
        assertEquals(true, TouchPadMath.autoSlotEndRevealsLast(regular, 1f))
        assertEquals(false, TouchPadMath.autoSlotEndRevealsLast(25, 1f))
    }

    @Test
    fun autoSlotFadeDoesNotEatTheOnlyCell() {
        assertEquals(0, TouchPadMath.autoSlotFadePx(26, 1f))
        assertEquals(0, TouchPadMath.autoSlotFadePx(20, 1f))
        assertEquals(4, TouchPadMath.autoSlotFadePx(30, 1f))
        assertEquals(13, TouchPadMath.autoSlotFadePx(80, 1f))
        val minStrip = TouchPadMath.autoSlotStripPx(160, 35, 1f)
        assertEquals(true, minStrip > TouchPadMath.dp(TouchPadMath.AUTO_SLOT_CELL_DP, 1f))
        assertEquals(true, TouchPadMath.autoSlotFadePx(minStrip, 1f) < minStrip / 2)
    }

    @Test
    fun trashSlideIsLinearWithoutHop() {
        assertEquals(10f, TouchPadMath.trashFlightX(0f, 10f, 40f), 0.001f)
        assertEquals(25f, TouchPadMath.trashFlightX(0.5f, 10f, 40f), 0.001f)
        assertEquals(40f, TouchPadMath.trashFlightX(1f, 10f, 40f), 0.001f)
        assertEquals(10f, TouchPadMath.trashFlightY(0f, 10f, 20f), 0.001f)
        assertEquals(15f, TouchPadMath.trashFlightY(0.5f, 10f, 20f), 0.001f)
        assertEquals(20f, TouchPadMath.trashFlightY(1f, 10f, 20f), 0.001f)
        assertEquals(0.74f, TouchPadMath.trashFlightScale(0f, 0.74f, 1f), 0.001f)
        assertEquals(0.87f, TouchPadMath.trashFlightScale(0.5f, 0.74f, 1f), 0.001f)
        assertEquals(1f, TouchPadMath.trashFlightScale(1f, 0.74f, 1f), 0.001f)
    }

    @Test
    fun trashCrossfadeOverlapsInAndOut() {
        assertEquals(1f, TouchPadMath.trashFadeOut(0f), 0.001f)
        assertEquals(0f, TouchPadMath.trashFadeOut(1f), 0.001f)
        assertEquals(0f, TouchPadMath.trashFadeIn(0f), 0.001f)
        assertEquals(1f, TouchPadMath.trashFadeIn(1f), 0.001f)
        val midOut = TouchPadMath.trashFadeOut(0.5f)
        val midIn = TouchPadMath.trashFadeIn(0.5f)
        assertEquals(0.707f, midOut, 0.02f)
        assertEquals(0.707f, midIn, 0.02f)
    }
}
