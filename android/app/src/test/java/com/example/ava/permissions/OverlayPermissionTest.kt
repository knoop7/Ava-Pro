package com.example.ava.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPermissionTest {

    @Test
    fun lowRamToggleIsBlockedOnlyFromAndroid10() {
        // Echo Show 8 LineageOS 18.1: Android 11 + ro.config.low_ram=true (issue #208).
        assertTrue(OverlayPermission.isLowRamToggleBlocked(sdkInt = 30, lowRam = true))
        assertTrue(OverlayPermission.isLowRamToggleBlocked(sdkInt = 29, lowRam = true))
        // Android 9 Go still shows the switch.
        assertFalse(OverlayPermission.isLowRamToggleBlocked(sdkInt = 28, lowRam = true))
        assertFalse(OverlayPermission.isLowRamToggleBlocked(sdkInt = 34, lowRam = false))
    }

    @Test
    fun requestPathPrefersExistingGrant() {
        assertEquals(
            OverlayPermission.RequestPath.ALREADY_GRANTED,
            OverlayPermission.requestPath(granted = true, privilegedOk = false, toggleBlocked = true),
        )
    }

    @Test
    fun requestPathUsesRootWhenAvailable() {
        assertEquals(
            OverlayPermission.RequestPath.PRIVILEGED_GRANT,
            OverlayPermission.requestPath(granted = false, privilegedOk = true, toggleBlocked = true),
        )
    }

    @Test
    fun requestPathOpensSettingsOnNormalRoms() {
        assertEquals(
            OverlayPermission.RequestPath.OPEN_SETTINGS,
            OverlayPermission.requestPath(granted = false, privilegedOk = false, toggleBlocked = false),
        )
    }

    @Test
    fun requestPathFallsBackToAdbWhenSwitchIsDisabled() {
        assertEquals(
            OverlayPermission.RequestPath.NEEDS_ADB,
            OverlayPermission.requestPath(granted = false, privilegedOk = false, toggleBlocked = true),
        )
    }

    @Test
    fun grantCommandUsesCmdAppOpsOnModernAndroid() {
        // Verified on crown / LineageOS 18.1 (Android 11) in issue #208.
        assertEquals(
            "cmd appops set com.example.ava SYSTEM_ALERT_WINDOW allow",
            OverlayPermission.shellGrantCommand("com.example.ava", sdkInt = 30),
        )
        assertEquals(
            "adb shell cmd appops set com.example.ava SYSTEM_ALERT_WINDOW allow",
            OverlayPermission.adbGrantCommand("com.example.ava", sdkInt = 30),
        )
    }

    @Test
    fun grantCommandFallsBackToAppOpsScriptBeforeNougat() {
        assertEquals(
            "appops set com.example.ava SYSTEM_ALERT_WINDOW allow",
            OverlayPermission.shellGrantCommand("com.example.ava", sdkInt = 23),
        )
        assertEquals(
            "appops set com.example.ava SYSTEM_ALERT_WINDOW allow",
            OverlayPermission.legacyShellGrantCommand("com.example.ava"),
        )
    }
}
