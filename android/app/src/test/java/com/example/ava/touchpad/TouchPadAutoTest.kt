package com.example.ava.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPadAutoTest {

    @Test
    fun idleCloseStaysHeldWhileAutoIsOpen() {
        val auto = TouchPadAuto()
        assertFalse(auto.holdsIdleClose())
        auto.open()
        assertTrue(auto.holdsIdleClose())
        assertEquals(TouchPadAutoPage.ARM, auto.page)
        assertTrue(auto.beginRecord())
        assertTrue(auto.holdsIdleClose())
        auto.recordTap(10f, 20f, 1_000L)
        auto.finishRecord()
        assertTrue(auto.beginPlay())
        assertTrue(auto.holdsIdleClose())
        assertTrue(auto.pausePlay())
        assertTrue(auto.holdsIdleClose())
        auto.haltPlay()
        assertTrue(auto.holdsIdleClose())
        auto.close()
        assertFalse(auto.holdsIdleClose())
    }

    @Test
    fun openLandsOnArmReadyToRecord() {
        val auto = TouchPadAuto()
        auto.open()
        assertEquals(TouchPadAutoPage.ARM, auto.page)
        assertFalse(auto.hasTake())
        assertTrue(auto.beginRecord())
        assertEquals(TouchPadAutoPage.RECORD, auto.page)
    }

    @Test
    fun firstTapHasNoLeadingDelay() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(8f, 9f, 500L)
        val steps = auto.snapshot()
        assertEquals(1, steps.size)
        assertEquals(TouchPadAutoStepType.TAP, steps[0].type)
        assertEquals(8f, steps[0].x)
        assertEquals(9f, steps[0].y)
    }

    @Test
    fun secondTapKeepsThePause() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(1f, 1f, 1_000L, 50L)
        auto.recordTap(1f, 1f, 1_250L, 50L)
        val steps = auto.snapshot()
        assertEquals(3, steps.size)
        assertEquals(TouchPadAutoStepType.DELAY, steps[1].type)
        assertEquals(200L, steps[1].durationMs)
    }

    @Test
    fun oneTakeKeepsTapsAndSlides() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(2f, 2f, 100L)
        auto.recordSlide(2f, 2f, 40f, 40f, 200L, 80L)
        val steps = auto.snapshot()
        assertEquals(3, steps.size)
        assertEquals(TouchPadAutoStepType.TAP, steps[0].type)
        assertEquals(TouchPadAutoStepType.DELAY, steps[1].type)
        assertEquals(TouchPadAutoStepType.SLIDE, steps[2].type)
        assertEquals(40f, steps[2].x2)
    }

    @Test
    fun recordKeepsAvaSceneForReplay() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.bindScene("settings/sidebar/touch_pad", "com.example.ava")
        auto.recordSlide(1f, 2f, 40f, 8f, 120L, 80L)
        auto.finishRecord()
        assertEquals("settings/sidebar/touch_pad", auto.sceneRoute)
        assertEquals("com.example.ava", auto.scenePackage)
        assertEquals(TouchPadAutoStepType.SLIDE, auto.snapshot()[0].type)
    }

    @Test
    fun recordHooksKeepBackScrollAndRecents() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(4f, 5f, 100L)
        auto.recordBack(4f, 5f, 180L)
        auto.recordSecondary(8f, 9f, 240L)
        auto.recordRecents(8f, 9f, 300L)
        auto.recordScroll(10f, 12f, 0f, -80f, 360L)
        auto.recordSidebar(10f, 12f, 180f, 420L, 160L)
        auto.recordSidebar(10f, 12f, -200f, 700L, 180L)
        val types = auto.snapshot().map { it.type }
        assertTrue(types.contains(TouchPadAutoStepType.TAP))
        assertTrue(types.contains(TouchPadAutoStepType.BACK))
        assertTrue(types.contains(TouchPadAutoStepType.SECONDARY))
        assertTrue(types.contains(TouchPadAutoStepType.RECENTS))
        assertTrue(types.contains(TouchPadAutoStepType.SCROLL))
        assertTrue(types.contains(TouchPadAutoStepType.SIDEBAR))
        val swipes = auto.snapshot().filter { it.type == TouchPadAutoStepType.SIDEBAR }
        assertEquals(2, swipes.size)
        assertEquals(180f, swipes[0].x2)
        assertEquals(-200f, swipes[1].x2)
        assertTrue(auto.hasTake())
    }

    @Test
    fun recordSidebarSkipsTinyDx() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordSidebar(10f, 12f, 2f, 100L, 80L)
        assertFalse(auto.hasTake())
        auto.recordSidebar(10f, 12f, 80f, 200L, 120L)
        assertEquals(TouchPadAutoStepType.SIDEBAR, auto.snapshot()[0].type)
        assertEquals(120L, auto.snapshot()[0].durationMs)
    }

    @Test
    fun playNeedsATake() {
        val auto = TouchPadAuto()
        auto.open()
        assertFalse(auto.beginPlay())
        auto.beginRecord()
        auto.recordTap(3f, 4f, 10L)
        auto.finishRecord()
        assertTrue(auto.hasTake())
        assertTrue(auto.beginPlay())
        assertEquals(TouchPadAutoPage.PLAY, auto.page)
    }

    @Test
    fun delayIsCapped() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(1f, 1f, 0L, 50L)
        auto.recordTap(1f, 1f, 120_000L, 50L)
        val delay = auto.snapshot().first { it.type == TouchPadAutoStepType.DELAY }
        assertEquals(TouchPadAuto.MAX_GAP_MS, delay.durationMs)
    }

    @Test
    fun pauseKeepsCursorResumeDoesNotRestart() {
        val auto = recordedTake()
        assertTrue(auto.beginPlay())
        assertEquals(0, auto.playCursor)
        assertEquals(TouchPadAutoStepType.TAP, auto.consumePlayStep()?.type)
        assertEquals(1, auto.playCursor)
        assertTrue(auto.pausePlay())
        assertEquals(TouchPadAutoPage.PAUSED, auto.page)
        assertEquals(1, auto.playCursor)
        assertFalse(auto.beginPlay())
        assertTrue(auto.resumePlay())
        assertEquals(TouchPadAutoPage.PLAY, auto.page)
        assertEquals(1, auto.playCursor)
        assertEquals(TouchPadAutoStepType.DELAY, auto.consumePlayStep()?.type)
        assertEquals(2, auto.playCursor)
    }

    @Test
    fun playedActionIndexSkipsDelays() {
        val auto = recordedTake()
        assertEquals(-1, auto.playedActionIndex())
        assertTrue(auto.beginPlay())
        auto.consumePlayStep()
        assertEquals(0, auto.playedActionIndex())
        auto.consumePlayStep()
        assertEquals(0, auto.playedActionIndex())
        auto.consumePlayStep()
        assertEquals(1, auto.playedActionIndex())
    }

    @Test
    fun replayRestartsFromTheFirstStep() {
        val auto = recordedTake()
        assertTrue(auto.beginPlay())
        auto.consumePlayStep()
        assertTrue(auto.pausePlay())
        assertEquals(1, auto.playCursor)
        assertTrue(auto.replayPlay())
        assertEquals(TouchPadAutoPage.PLAY, auto.page)
        assertEquals(0, auto.playCursor)
        assertEquals(TouchPadAutoStepType.TAP, auto.consumePlayStep()?.type)
    }

    @Test
    fun finishedPassIsTrueAfterTheLastStep() {
        val auto = recordedTake()
        assertFalse(auto.loop)
        assertFalse(auto.finishedPass())
        assertTrue(auto.beginPlay())
        while (auto.consumePlayStep() != null) Unit
        assertTrue(auto.finishedPass())
        assertTrue(auto.wrapPlayCursor())
        assertEquals(0, auto.playCursor)
        assertFalse(auto.finishedPass())
    }

    @Test
    fun parkAfterPassStaysOnPlayFace() {
        val auto = recordedTake()
        assertTrue(auto.beginPlay())
        while (auto.consumePlayStep() != null) Unit
        assertTrue(auto.parkAfterPass())
        assertEquals(TouchPadAutoPage.PAUSED, auto.page)
        assertEquals(0, auto.playCursor)
        assertTrue(auto.hasTake())
        assertTrue(auto.resumePlay())
        assertEquals(TouchPadAutoPage.PLAY, auto.page)
    }

    @Test
    fun haltForgetsCursorAndReturnsToArm() {
        val auto = recordedTake()
        assertTrue(auto.beginPlay())
        auto.consumePlayStep()
        assertTrue(auto.pausePlay())
        auto.haltPlay()
        assertEquals(TouchPadAutoPage.ARM, auto.page)
        assertEquals(0, auto.playCursor)
        assertTrue(auto.hasTake())
        assertTrue(auto.beginPlay())
        assertEquals(0, auto.playCursor)
    }

    @Test
    fun pausedCannotStartANewRecording() {
        val auto = recordedTake()
        assertTrue(auto.beginPlay())
        assertTrue(auto.pausePlay())
        assertFalse(auto.beginRecord())
        assertEquals(TouchPadAutoPage.PAUSED, auto.page)
    }

    @Test
    fun secondRecordFillsTheNextSlot() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(1f, 1f, 10L)
        auto.finishRecord()
        assertEquals(0, auto.selected)
        assertTrue(auto.beginRecord())
        assertEquals(1, auto.selected)
        auto.recordTap(9f, 9f, 20L)
        auto.finishRecord()
        assertEquals(1, auto.selected)
        assertTrue(auto.filledMask()[0])
        assertTrue(auto.filledMask()[1])
        assertTrue(auto.select(0))
        assertEquals(1f, auto.snapshot()[0].x)
        assertTrue(auto.select(1))
        assertEquals(9f, auto.snapshot()[0].x)
    }

    @Test
    fun closeKeepsLibraryForTheNextOpen() {
        val auto = recordedTake()
        auto.close()
        assertFalse(auto.hasTake())
        auto.open()
        assertTrue(auto.hasTake())
        assertTrue(auto.hasAnyTake())
        assertEquals(TouchPadAutoStepType.TAP, auto.snapshot()[0].type)
    }

    @Test
    fun libraryCapsAtFiveAndOverwritesSelected() {
        val auto = TouchPadAuto()
        auto.open()
        repeat(5) { index ->
            assertTrue(auto.beginRecord())
            auto.recordTap(index.toFloat(), 1f, 10L + index)
            auto.finishRecord()
        }
        assertEquals(5, auto.filledMask().count { it })
        assertEquals(4, auto.selected)
        assertTrue(auto.beginRecord())
        assertEquals(4, auto.selected)
        auto.recordTap(99f, 1f, 80L)
        auto.finishRecord()
        assertEquals(5, auto.filledMask().count { it })
        assertEquals(99f, auto.snapshot()[0].x)
        assertTrue(auto.select(0))
        assertEquals(0f, auto.snapshot()[0].x)
    }

    @Test
    fun emptyFinishLeavesThePreviousTake() {
        val auto = recordedTake()
        assertTrue(auto.select(0))
        assertTrue(auto.beginRecord())
        auto.finishRecord()
        assertTrue(auto.hasTake())
        assertEquals(TouchPadAutoStepType.TAP, auto.snapshot()[0].type)
    }

    @Test
    fun clearTakeKeepsOtherSlotsAndLeavesArm() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(1f, 1f, 10L)
        auto.finishRecord()
        auto.beginRecord()
        auto.recordTap(8f, 8f, 20L)
        auto.finishRecord()
        assertEquals(1, auto.selected)
        assertTrue(auto.beginPlay())
        assertTrue(auto.clearTake(1))
        assertEquals(TouchPadAutoPage.ARM, auto.page)
        assertEquals(0, auto.selected)
        assertTrue(auto.filledMask()[0])
        assertFalse(auto.filledMask()[1])
        assertEquals(1f, auto.snapshot()[0].x)
        auto.beginRecord()
        assertFalse(auto.clearTake(0))
        assertEquals(TouchPadAutoPage.RECORD, auto.page)
        auto.finishRecord()
        assertTrue(auto.clearTake(0))
        assertFalse(auto.hasAnyTake())
    }

    @Test
    fun cannotSelectWhilePlaying() {
        val auto = recordedTake()
        assertTrue(auto.beginPlay())
        assertFalse(auto.select(1))
        assertEquals(0, auto.selected)
    }

    @Test
    fun midRecordWritesLocationRouteAndWindow() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.bindScene(TouchPadAutoScene("home", "com.example.ava"))
        auto.recordTap(1f, 1f, 10L)
        auto.recordSceneIfChanged(
            TouchPadAutoScene(
                route = "settings/sidebar/touch_pad",
                packageName = "com.example.ava",
                windows = listOf("com.foo.bar"),
            ),
            80L,
        )
        auto.recordTap(8f, 9f, 120L)
        auto.finishRecord()
        assertEquals("home", auto.sceneRoute)
        val steps = auto.snapshot()
        assertEquals(1, steps.count { it.type == TouchPadAutoStepType.ROUTE })
        assertEquals("settings/sidebar/touch_pad", steps.first { it.type == TouchPadAutoStepType.ROUTE }.extra)
        assertEquals(1, steps.count { it.type == TouchPadAutoStepType.WINDOW })
        assertEquals("com.foo.bar", steps.first { it.type == TouchPadAutoStepType.WINDOW }.extra)
        assertEquals(1f, steps.first { it.type == TouchPadAutoStepType.WINDOW }.x)
        assertEquals(2, steps.count { it.type == TouchPadAutoStepType.TAP })
        assertTrue(auto.beginPlay())
        assertEquals("home", auto.sceneRoute)
    }

    @Test
    fun midRecordWritesExternalPackageAtLocation() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.bindScene(TouchPadAutoScene("home", "com.example.ava"))
        auto.recordSceneIfChanged(
            TouchPadAutoScene(route = "", packageName = "com.other.app"),
            40L,
        )
        auto.finishRecord()
        val window = auto.snapshot().single { it.type == TouchPadAutoStepType.WINDOW }
        assertEquals("com.other.app", window.extra)
        assertEquals(1f, window.x)
        assertTrue(auto.snapshot().none { it.type == TouchPadAutoStepType.ROUTE })
        assertEquals("home", auto.sceneRoute)
        assertEquals("com.example.ava", auto.scenePackage)
    }

    @Test
    fun storeRoundTripsSceneAndSteps() {
        val take = TouchPadAutoTake(
            steps = listOf(
                TouchPadAutoStep(TouchPadAutoStepType.SLIDE, 1f, 2f, 40f, 8f, 80L),
                TouchPadAutoStep(TouchPadAutoStepType.DELAY, durationMs = 120L),
                TouchPadAutoStep(TouchPadAutoStepType.TAP, 4f, 5f, 4f, 5f, 50L),
                TouchPadAutoStep(TouchPadAutoStepType.SIDEBAR, 10f, 20f, -220f, 0f, 180L),
                TouchPadAutoStep(TouchPadAutoStepType.ROUTE, extra = "settings/ha"),
                TouchPadAutoStep(TouchPadAutoStepType.WINDOW, x = 1f, extra = "com.foo"),
                TouchPadAutoStep(TouchPadAutoStepType.KEY, keyCode = 82, durationMs = 0L),
                TouchPadAutoStep(TouchPadAutoStepType.TEXT, extra = "hello, world"),
            ),
            sceneRoute = "settings/sidebar/touch_pad",
            scenePackage = "com.example.ava",
            sceneWindows = listOf("com.foo"),
        )
        val packed = TouchPadAutoStore.encode(
            listOf(take, null, null, null, null),
            selected = 0,
        )
        val restored = TouchPadAutoStore.decode(packed)
        assertEquals(0, restored.second)
        val again = restored.first[0]
        requireNotNull(again)
        assertEquals("settings/sidebar/touch_pad", again.sceneRoute)
        assertEquals("com.example.ava", again.scenePackage)
        assertEquals(listOf("com.foo"), again.sceneWindows)
        assertEquals(8, again.steps.size)
        assertEquals(TouchPadAutoStepType.SLIDE, again.steps[0].type)
        assertEquals(40f, again.steps[0].x2)
        assertEquals(TouchPadAutoStepType.TAP, again.steps[2].type)
        assertEquals(TouchPadAutoStepType.SIDEBAR, again.steps[3].type)
        assertEquals(-220f, again.steps[3].x2)
        assertEquals(TouchPadAutoStepType.ROUTE, again.steps[4].type)
        assertEquals("settings/ha", again.steps[4].extra)
        assertEquals("com.foo", again.steps[5].extra)
        assertEquals(82, again.steps[6].keyCode)
        assertEquals(TouchPadAutoStepType.TEXT, again.steps[7].type)
        assertEquals("hello, world", again.steps[7].extra)
    }

    @Test
    fun haFilledIndexesSkipEmptySlots() {
        assertEquals(emptyList<Int>(), TouchPadHa.filledIndexes(""))
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(1f, 1f, 100L)
        auto.finishRecord()
        auto.beginRecord()
        auto.recordTap(2f, 2f, 200L)
        auto.finishRecord()
        val encoded = TouchPadAutoStore.encode(auto.snapshotLibrary(), auto.selected)
        assertEquals(listOf(0, 1), TouchPadHa.filledIndexes(encoded))
        auto.clearTake(0)
        val after = TouchPadAutoStore.encode(auto.snapshotLibrary(), auto.selected)
        assertEquals(listOf(1), TouchPadHa.filledIndexes(after))
    }

    @Test
    fun recordTextCoalescesTyping() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordText("h", 100L)
        auto.recordText("he", 160L)
        auto.recordText("hel", 220L)
        val steps = auto.snapshot()
        assertEquals(1, steps.size)
        assertEquals(TouchPadAutoStepType.TEXT, steps[0].type)
        assertEquals("hel", steps[0].extra)
    }

    @Test
    fun recordTextKeepsPauseBetweenBursts() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordText("hi", 100L)
        auto.recordText("hi there", 900L)
        val steps = auto.snapshot()
        assertEquals(3, steps.size)
        assertEquals(TouchPadAutoStepType.TEXT, steps[0].type)
        assertEquals("hi", steps[0].extra)
        assertEquals(TouchPadAutoStepType.DELAY, steps[1].type)
        assertEquals(TouchPadAutoStepType.TEXT, steps[2].type)
        assertEquals("hi there", steps[2].extra)
    }

    @Test
    fun recordTextSkipsLeadingEmpty() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordText("", 40L)
        assertFalse(auto.hasTake())
        auto.recordText("a", 80L)
        auto.recordText("", 200L)
        assertEquals("", auto.snapshot().last().extra)
    }

    @Test
    fun textPlayMsStaysEven() {
        assertEquals(TouchPadAuto.TEXT_MIN_MS, TouchPadAuto.textPlayMs(1))
        assertEquals(560L, TouchPadAuto.textPlayMs(10))
        assertEquals(TouchPadAuto.TEXT_MAX_MS, TouchPadAuto.textPlayMs(1_000))
    }

    @Test
    fun playSwipeMsDoesNotAccelerate() {
        assertEquals(TouchPadMath.SCREEN_SWIPE_MIN_MS, TouchPadAuto.playSwipeMs(80L))
        assertEquals(800L, TouchPadAuto.playSwipeMs(800L))
        assertEquals(TouchPadAuto.MAX_SWIPE_MS, TouchPadAuto.playSwipeMs(9_000L))
    }

    @Test
    fun recordKeyDedupsDoubleDown() {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordKey(66, 0, 100L)
        auto.recordKey(66, 0, 120L)
        assertEquals(1, auto.snapshot().size)
        auto.recordKey(66, 0, 200L)
        assertEquals(3, auto.snapshot().size)
    }

    private fun recordedTake(): TouchPadAuto {
        val auto = TouchPadAuto()
        auto.open()
        auto.beginRecord()
        auto.recordTap(1f, 1f, 1_000L, 50L)
        auto.recordTap(1f, 1f, 1_250L, 50L)
        auto.finishRecord()
        return auto
    }
}
