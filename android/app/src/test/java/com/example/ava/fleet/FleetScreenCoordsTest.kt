package com.example.ava.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FleetScreenCoordsTest {

    @Test
    fun portraitCenterUnchanged() {
        val p = FleetScreenCoords.resolve(0.5, 0.5, null, null, 1080, 1920)
        assertEquals(539, p!!.x)
        assertEquals(959, p.y)
        assertEquals(1080, p.displayWidth)
        assertEquals(1920, p.displayHeight)
    }

    @Test
    fun landscapeCenterUsesWideDisplay() {
        val p = FleetScreenCoords.resolve(0.5, 0.5, null, null, 1920, 1080)
        assertEquals(959, p!!.x)
        assertEquals(539, p.y)
        assertEquals(1920, p.displayWidth)
        assertEquals(1080, p.displayHeight)
    }

    @Test
    fun stalePortraitMetricsFollowLandscapeFrame() {
        val p = FleetScreenCoords.resolve(
            nx = 0.5,
            ny = 0.5,
            rawX = 540,
            rawY = 960,
            displayW = 1080,
            displayH = 1920,
            frameW = 640,
            frameH = 360,
        )
        assertEquals(959, p!!.x)
        assertEquals(539, p.y)
        assertEquals(1920, p.displayWidth)
        assertEquals(1080, p.displayHeight)
    }

    @Test
    fun landscapeMetricsWithPortraitFrameSwapBack() {
        val aligned = FleetScreenCoords.alignWithFrame(1920, 1080, 360, 640)
        assertEquals(1080, aligned.width)
        assertEquals(1920, aligned.height)
    }

    @Test
    fun matchingOrientationDoesNotSwap() {
        assertEquals(
            FleetScreenCoords.Size(1080, 1920),
            FleetScreenCoords.alignWithFrame(1080, 1920, 360, 640),
        )
        assertEquals(
            FleetScreenCoords.Size(1920, 1080),
            FleetScreenCoords.alignWithFrame(1920, 1080, 640, 360),
        )
    }

    @Test
    fun missingFrameLeavesDisplayAlone() {
        assertEquals(
            FleetScreenCoords.Size(1080, 1920),
            FleetScreenCoords.alignWithFrame(1080, 1920, 0, 0),
        )
    }

    @Test
    fun cornersStayOnDisplay() {
        val origin = FleetScreenCoords.resolve(0.0, 0.0, null, null, 1920, 1080)!!
        assertEquals(0, origin.x)
        assertEquals(0, origin.y)
        val far = FleetScreenCoords.resolve(1.0, 1.0, null, null, 1920, 1080)!!
        assertEquals(1919, far.x)
        assertEquals(1079, far.y)
    }

    @Test
    fun rawPixelsUsedWhenNormalizedMissing() {
        val p = FleetScreenCoords.resolve(null, null, 100, 200, 1920, 1080)
        assertEquals(100, p!!.x)
        assertEquals(200, p.y)
    }

    @Test
    fun missingAxisReturnsNull() {
        assertNull(FleetScreenCoords.resolve(0.5, null, 10, null, 1080, 1920))
        assertNull(FleetScreenCoords.resolve(null, 0.5, null, 10, 1080, 1920))
    }
}
