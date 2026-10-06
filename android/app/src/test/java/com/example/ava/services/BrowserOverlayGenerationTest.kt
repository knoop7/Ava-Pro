package com.example.ava.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser overlay is assembled by a coroutine that writes into service fields across several
 * suspension points, so it has to be able to tell that it no longer owns the overlay.
 */
class BrowserOverlayGenerationTest {

    @Test
    fun freshBuildOwnsTheOverlay() {
        val gen = BrowserOverlayGeneration()
        val token = gen.begin()
        assertFalse(gen.isStale(token))
    }

    @Test
    fun teardownAbandonsTheBuildInFlight() {
        val gen = BrowserOverlayGeneration()
        val token = gen.begin()
        gen.invalidate()
        assertTrue(gen.isStale(token))
    }

    @Test
    fun newerBuildRetiresTheOlderOne() {
        val gen = BrowserOverlayGeneration()
        val first = gen.begin()
        val second = gen.begin()
        assertTrue(gen.isStale(first))
        assertFalse(gen.isStale(second))
    }

    @Test
    fun tearingDownTheNewerBuildDoesNotReviveTheOlderOne() {
        val gen = BrowserOverlayGeneration()
        val first = gen.begin()
        val second = gen.begin()
        gen.invalidate()
        assertTrue(gen.isStale(first))
        assertTrue(gen.isStale(second))
    }

    @Test
    fun rebuildAfterTeardownOwnsTheOverlayAgain() {
        val gen = BrowserOverlayGeneration()
        val abandoned = gen.begin()
        gen.invalidate()
        val rebuild = gen.begin()
        assertTrue(gen.isStale(abandoned))
        assertFalse(gen.isStale(rebuild))
    }
}
