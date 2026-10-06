package com.example.ava.platform

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PlatformCapabilitiesTest {

    @Test
    fun displayCutoutModeIsNotReadBeforePie() {
        val mode = PlatformCapabilities.displayCutoutModeForSdk(Build.VERSION_CODES.O_MR1) {
            fail("layoutInDisplayCutoutMode must not be read on API 21–27")
            1
        }
        assertEquals(0, mode)
        assertEquals(
            0,
            PlatformCapabilities.displayCutoutModeForSdk(Build.VERSION_CODES.N) {
                fail("layoutInDisplayCutoutMode must not be read on API 21–27")
                1
            },
        )
    }

    @Test
    fun exactAlarmsAreFreeBeforeAndroid12() {
        assertEquals(
            true,
            PlatformCapabilities.canScheduleExactAlarmsForSdk(Build.VERSION_CODES.R) {
                fail("AlarmManager.canScheduleExactAlarms must not be read before API 31")
                false
            },
        )
        assertEquals(
            false,
            PlatformCapabilities.canScheduleExactAlarmsForSdk(Build.VERSION_CODES.S) { false },
        )
        assertEquals(
            true,
            PlatformCapabilities.canScheduleExactAlarmsForSdk(Build.VERSION_CODES.S) { true },
        )
    }

    @Test
    fun displayCutoutModeIsReadFromPie() {
        assertEquals(
            2,
            PlatformCapabilities.displayCutoutModeForSdk(Build.VERSION_CODES.P) { 2 },
        )
        assertEquals(
            1,
            PlatformCapabilities.displayCutoutModeForSdk(Build.VERSION_CODES.Q) { 1 },
        )
    }
}
