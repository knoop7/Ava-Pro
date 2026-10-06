package com.example.ava.voiceprint

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeDetectAudioSourceTest {
    @Test
    fun openIdleWithSoftwareNsUsesStreamNotRaw() {
        assertEquals(
            WakeDetectAudioTap.STREAM,
            WakeDetectAudioSource.tap(
                openEngine = true,
                softwareNsOn = true,
                softwareAecOn = true,
                bargeIn = false,
                detectTapAvailable = true,
            ),
        )
    }

    @Test
    fun openIdleWithoutNsUsesRawWakeCopy() {
        assertEquals(
            WakeDetectAudioTap.VS_WAKE,
            WakeDetectAudioSource.tap(
                openEngine = true,
                softwareNsOn = false,
                softwareAecOn = true,
                bargeIn = false,
                detectTapAvailable = true,
            ),
        )
    }

    @Test
    fun openBargeInPrefersDetectTap() {
        assertEquals(
            WakeDetectAudioTap.DETECT_TAP,
            WakeDetectAudioSource.tap(
                openEngine = true,
                softwareNsOn = true,
                softwareAecOn = true,
                bargeIn = true,
                detectTapAvailable = true,
            ),
        )
    }

    @Test
    fun openBargeInWithoutTapFallsToStream() {
        assertEquals(
            WakeDetectAudioTap.STREAM,
            WakeDetectAudioSource.tap(
                openEngine = true,
                softwareNsOn = false,
                softwareAecOn = true,
                bargeIn = true,
                detectTapAvailable = false,
            ),
        )
    }

    @Test
    fun microIdleKeepsRawEvenWhenNsIsOn() {
        assertEquals(
            WakeDetectAudioTap.PROCESSED,
            WakeDetectAudioSource.tap(
                openEngine = false,
                softwareNsOn = true,
                softwareAecOn = true,
                bargeIn = false,
                detectTapAvailable = true,
            ),
        )
    }

    @Test
    fun microBargeInUsesDetectTap() {
        assertEquals(
            WakeDetectAudioTap.DETECT_TAP,
            WakeDetectAudioSource.tap(
                openEngine = false,
                softwareNsOn = false,
                softwareAecOn = true,
                bargeIn = true,
                detectTapAvailable = true,
            ),
        )
    }

    @Test
    fun noSoftwareAecUsesStream() {
        assertEquals(
            WakeDetectAudioTap.STREAM,
            WakeDetectAudioSource.tap(
                openEngine = false,
                softwareNsOn = false,
                softwareAecOn = false,
                bargeIn = false,
                detectTapAvailable = false,
            ),
        )
    }
}
