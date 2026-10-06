package com.example.ava.touchpad

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPhonePointerPolicyTest {
    @Test
    fun preferForeignSkipsTheHostOverlay() {
        assertEquals(
            true,
            AiPhonePointerPolicy.skipHostWindow(
                windowPackage = "com.example.ava",
                hostPackage = "com.example.ava",
                preferForeign = true,
            ),
        )
    }

    @Test
    fun preferForeignKeepsTheOtherApp() {
        assertEquals(
            false,
            AiPhonePointerPolicy.skipHostWindow(
                windowPackage = "com.tencent.mm",
                hostPackage = "com.example.ava",
                preferForeign = true,
            ),
        )
    }

    @Test
    fun secondPassCanClickTheHost() {
        assertEquals(
            false,
            AiPhonePointerPolicy.skipHostWindow(
                windowPackage = "com.example.ava",
                hostPackage = "com.example.ava",
                preferForeign = false,
            ),
        )
    }

    @Test
    fun inAppHitDoesNotNeedAccessibility() {
        assertEquals(false, AiPhonePointerPolicy.needsAccessibility(inApp = true, localHit = true, goingOutside = false))
        assertEquals(false, AiPhonePointerPolicy.needsAccessibility(inApp = true, localHit = false, goingOutside = false))
        assertEquals(true, AiPhonePointerPolicy.needsAccessibility(inApp = true, localHit = false, goingOutside = true))
        assertEquals(true, AiPhonePointerPolicy.needsAccessibility(inApp = false, localHit = false, goingOutside = false))
    }

    @Test
    fun pointerDoesNotNeedThePadWindow() {
        assertEquals(false, AiPhonePointerPolicy.pointerNeedsPadOverlay(padShowing = false, padSidebarOn = false))
        assertEquals(false, AiPhonePointerPolicy.pointerNeedsPadOverlay(padShowing = true, padSidebarOn = true))
        assertEquals(
            "android.settings.ACCESSIBILITY_SETTINGS",
            AiPhonePointerPolicy.ACCESSIBILITY_SETTINGS_ACTION,
        )
    }

    @Test
    fun blankPackagesNeverSkip() {
        assertEquals(
            false,
            AiPhonePointerPolicy.skipHostWindow(
                windowPackage = "",
                hostPackage = "com.example.ava",
                preferForeign = true,
            ),
        )
        assertEquals(
            false,
            AiPhonePointerPolicy.skipHostWindow(
                windowPackage = "com.example.ava",
                hostPackage = "",
                preferForeign = true,
            ),
        )
    }
}
