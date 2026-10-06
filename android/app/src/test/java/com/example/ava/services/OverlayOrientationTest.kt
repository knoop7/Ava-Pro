package com.example.ava.services

import android.content.pm.ActivityInfo
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OverlayOrientationTest {

    @After
    fun reset() {
        OverlayOrientation.syncForceSettings(false, "auto")
    }

    @Test
    fun autoFollowsUserNotTheWindowBehind() {
        OverlayOrientation.syncForceSettings(false, "auto")
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER,
            OverlayOrientation.screenOrientation(),
        )
        OverlayOrientation.syncForceSettings(false, "portrait")
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER,
            OverlayOrientation.screenOrientation(),
        )
    }

    @Test
    fun forceAutoStillFollowsUser() {
        OverlayOrientation.syncForceSettings(true, "auto")
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER,
            OverlayOrientation.screenOrientation(),
        )
    }

    @Test
    fun forcePortraitLocksPortrait() {
        OverlayOrientation.syncForceSettings(true, "portrait")
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OverlayOrientation.screenOrientation(),
        )
    }

    @Test
    fun forceLandscapeLocksLandscape() {
        OverlayOrientation.syncForceSettings(true, "landscape")
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OverlayOrientation.screenOrientation(),
        )
    }

    @Test
    fun neverUnspecified() {
        OverlayOrientation.syncForceSettings(false, "auto")
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OverlayOrientation.screenOrientation(),
        )
        OverlayOrientation.syncForceSettings(true, "auto")
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OverlayOrientation.screenOrientation(),
        )
        OverlayOrientation.syncForceSettings(true, "portrait")
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OverlayOrientation.screenOrientation(),
        )
    }

    @Test
    fun applyStampsParams() {
        OverlayOrientation.syncForceSettings(true, "landscape")
        val params = WindowManager.LayoutParams()
        OverlayOrientation.apply(params)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            params.screenOrientation,
        )
    }
}
