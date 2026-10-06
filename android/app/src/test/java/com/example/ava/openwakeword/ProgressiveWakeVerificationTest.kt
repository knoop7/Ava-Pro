package com.example.ava.openwakeword

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveWakeVerificationTest {
    @Test
    fun clearWakeDoesNotWaitForFullTail() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = ProgressiveWakeVerification.confirm(550,
            readWindow = { requested.add(it); shortArrayOf(1) },
            classify = { true },
        )
        assertTrue(result == true)
        assertEquals(listOf(80), requested)
    }

    @Test
    fun earlyRejectionStillGetsFullWindowChance() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = ProgressiveWakeVerification.confirm(550,
            readWindow = { requested.add(it); shortArrayOf(it.toShort()) },
            classify = { it[0] == 550.toShort() },
        )
        assertTrue(result == true)
        assertEquals(listOf(80, 550), requested)
    }

    @Test
    fun negativeMustFailTheFinalWindow() = runBlocking {
        var attempts = 0
        val result = ProgressiveWakeVerification.confirm(550,
            readWindow = { shortArrayOf(1) },
            classify = { attempts++; false },
        )
        assertFalse(result == true)
        assertEquals(false, result)
        assertEquals(2, attempts)
    }

    @Test
    fun missingEarlyAudioDoesNotLoseLaterWake() = runBlocking {
        val result = ProgressiveWakeVerification.confirm(550,
            readWindow = { if (it < 550) null else shortArrayOf(1) },
            classify = { true },
        )
        assertTrue(result == true)
    }

    @Test
    fun missingFinalEvidenceKeepsExistingFallbackPolicy() = runBlocking {
        val result = ProgressiveWakeVerification.confirm(550,
            readWindow = { if (it == 550) null else shortArrayOf(1) },
            classify = { false },
        )
        assertNull(result)
    }

    @Test
    fun builtInVerifierWaitsForTheFullTail() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = ProgressiveWakeVerification.confirm(
            fullAfterMs = 550,
            earlyAfterMs = 550,
            readWindow = { requested.add(it); shortArrayOf(1) },
            classify = { true },
        )
        assertTrue(result == true)
        assertEquals(listOf(550), requested)
    }

    @Test
    fun shortWindowIsNotEvaluatedTwice() = runBlocking {
        var attempts = 0
        val result = ProgressiveWakeVerification.confirm(40,
            readWindow = { shortArrayOf(1) },
            classify = { attempts++; false },
        )
        assertEquals(false, result)
        assertEquals(1, attempts)
    }
}
