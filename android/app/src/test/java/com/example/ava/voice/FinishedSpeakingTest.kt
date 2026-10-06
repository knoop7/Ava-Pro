package com.example.ava.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedSpeakingTest {
    @Test
    fun matchesThisDevicesMacOnly() {
        assertTrue(FinishedSpeaking.matches("AA:BB:CC:DD:EE:FF-vad_sensitivity", "aa:bb:cc:dd:ee:ff"))
        assertFalse(FinishedSpeaking.matches("11:22:33:44:55:66-vad_sensitivity", "aa:bb:cc:dd:ee:ff"))
        assertFalse(FinishedSpeaking.matches("AA:BB:CC:DD:EE:FF-pipeline", "aa:bb:cc:dd:ee:ff"))
        assertFalse(FinishedSpeaking.matches("000000000000-vad_sensitivity", "00:00:00:00:00:00"))
    }

    @Test
    fun canonicalKeepsTheThreeHaOptions() {
        assertEquals(FinishedSpeaking.RELAXED, FinishedSpeaking.canonical("Relaxed"))
        assertEquals(FinishedSpeaking.DEFAULT, FinishedSpeaking.canonical("default"))
        assertNull(FinishedSpeaking.canonical("unavailable"))
        assertNull(FinishedSpeaking.canonical(null))
    }
}
