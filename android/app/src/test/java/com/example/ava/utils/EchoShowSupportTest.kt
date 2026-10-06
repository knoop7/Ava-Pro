package com.example.ava.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoShowSupportTest {

    @Test
    fun lineageCrownIsEchoShow() {
        assertTrue(
            EchoShowSupport.matchesBuild(
                model = "LineageOS",
                board = "mt8163",
                device = "crown",
                product = "lineage_crown",
                manufacturer = "Amazon",
            ),
        )
    }

    @Test
    fun echoShow8ModelIsEchoShow() {
        assertTrue(
            EchoShowSupport.matchesBuild(
                model = "Echo Show 8",
                board = "crown",
                device = "crown",
                product = "crown",
            ),
        )
    }

    @Test
    fun checkersAndCronosAreEchoShow() {
        assertTrue(EchoShowSupport.matchesBuild("Echo Show 5", "checkers", "checkers", "checkers"))
        assertTrue(EchoShowSupport.matchesBuild("Echo Show 5", "cronos", "cronos", "lineage_cronos"))
    }

    @Test
    fun rookSpotIsEchoShow() {
        assertTrue(EchoShowSupport.matchesBuild("Echo Spot", "rook", "rook", "rook"))
    }

    @Test
    fun pixelIsNotEchoShow() {
        assertFalse(
            EchoShowSupport.matchesBuild(
                model = "Pixel 8",
                board = "oriole",
                device = "oriole",
                product = "oriole",
                manufacturer = "Google",
            ),
        )
    }
}
