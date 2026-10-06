package com.example.ava.ui.haptic

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayHapticsTest {
    @Test
    fun intensitiesGetWeakerDurationsAndAmplitudes() {
        assertEquals(12L, overlayHapticDurationMs(OverlayHaptics.Intensity.TICK))
        assertEquals(16L, overlayHapticDurationMs(OverlayHaptics.Intensity.CLICK))
        assertEquals(24L, overlayHapticDurationMs(OverlayHaptics.Intensity.HEAVY))
        assertTrue(
            overlayHapticAmplitude(OverlayHaptics.Intensity.TICK) <
                overlayHapticAmplitude(OverlayHaptics.Intensity.CLICK),
        )
        assertTrue(
            overlayHapticAmplitude(OverlayHaptics.Intensity.CLICK) <
                overlayHapticAmplitude(OverlayHaptics.Intensity.HEAVY),
        )
    }

    @Test
    fun intensitiesMapToWakeButtonConstants() {
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            overlayHapticConstant(OverlayHaptics.Intensity.TICK),
        )
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            overlayHapticConstant(OverlayHaptics.Intensity.CLICK),
        )
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            overlayHapticConstant(OverlayHaptics.Intensity.HEAVY),
        )
    }

    @Test
    fun scrollEnvelopeGoesHeavyToLight() {
        assertEquals(
            OverlayHaptics.Intensity.HEAVY,
            overlayScrollHapticIntensity(0),
        )
        assertEquals(
            OverlayHaptics.Intensity.CLICK,
            overlayScrollHapticIntensity(1),
        )
        assertEquals(
            OverlayHaptics.Intensity.TICK,
            overlayScrollHapticIntensity(2),
        )
        assertEquals(
            OverlayHaptics.Intensity.TICK,
            overlayScrollHapticIntensity(8),
        )
    }

    @Test
    fun travelTicksMatchSliderStyleDetents() {
        assertFalse(travelTickChanged(0f, 10f, 14f))
        assertTrue(travelTickChanged(13f, 14f, 14f))
        assertFalse(travelTickChanged(14.2f, 20f, 14f))
        assertTrue(travelTickChanged(27f, 29f, 14f))
    }
}
