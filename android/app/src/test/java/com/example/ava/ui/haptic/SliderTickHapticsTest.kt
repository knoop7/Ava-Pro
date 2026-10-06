package com.example.ava.ui.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderTickHapticsTest {
    @Test
    fun steppedSliderTicksOnlyOnDetentChange() {
        val range = 0f..100f
        assertTrue(sliderTickChanged(0f, 1f, range, steps = 99))
        assertFalse(sliderTickChanged(10f, 10f, range, steps = 99))
        assertEquals(
            sliderTickBucket(25f, range, 99),
            sliderTickBucket(25.2f, range, 99),
        )
    }

    @Test
    fun wideContinuousSliderTicksOnWholeUnits() {
        val range = 0f..500f
        assertTrue(sliderTickChanged(10f, 11f, range, steps = 0))
        assertFalse(sliderTickChanged(10.2f, 10.8f, range, steps = 0))
    }

    @Test
    fun narrowContinuousSliderTicksOnHundredths() {
        val range = 1f..3.5f
        assertTrue(sliderTickChanged(1.00f, 1.02f, range, steps = 0))
        assertFalse(sliderTickChanged(1.001f, 1.004f, range, steps = 0))
    }
}
