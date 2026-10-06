package com.example.ava.multidevice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChorusPeerSessionTest {

    @Test
    fun peerClaimHoldsUntilEnd() {
        var now = 1_000L
        val session = ChorusPeerSession(timeoutMs = 90_000L, nowMs = { now })
        assertTrue(session.markPeer())
        assertTrue(session.isActive())
        now = 40_000L
        assertTrue(session.isActive())
        assertTrue(session.endPeer())
        assertFalse(session.isActive())
    }

    @Test
    fun localWinnerIgnoresPeerHold() {
        val session = ChorusPeerSession(timeoutMs = 90_000L, nowMs = { 1_000L })
        session.setLocalWinner(true)
        assertFalse(session.markPeer())
        assertFalse(session.isActive())
    }

    @Test
    fun winningClearsAnExistingHold() {
        val session = ChorusPeerSession(timeoutMs = 90_000L, nowMs = { 1_000L })
        assertTrue(session.markPeer())
        session.setLocalWinner(true)
        assertFalse(session.isActive())
        assertFalse(session.markPeer())
    }

    @Test
    fun timeoutFiresOnceThenStaysClear() {
        var now = 0L
        val session = ChorusPeerSession(timeoutMs = 90_000L, nowMs = { now })
        assertTrue(session.markPeer())
        now = 89_999L
        assertFalse(session.consumeExpired())
        assertTrue(session.isActive())
        now = 90_000L
        assertTrue(session.consumeExpired())
        assertFalse(session.isActive())
        assertFalse(session.consumeExpired())
    }

    @Test
    fun keepaliveSustainsHoldAndExpiresAfterBeatsStop() {
        var now = 0L
        val session = ChorusPeerSession(timeoutMs = 15_000L, nowMs = { now })
        assertTrue(session.markPeer())
        // Keepalive beat while already held: extends the window, not a fresh engage.
        now = 10_000L
        assertFalse(session.markPeer())
        // 10s beat + 15s sustain = deadline at 25s; still held short of it.
        now = 24_999L
        assertTrue(session.isActive())
        assertFalse(session.consumeExpired())
        // Beats stopped: hold expires on its own (lost END must not deafen for 90s).
        now = 25_000L
        assertTrue(session.consumeExpired())
        assertFalse(session.isActive())
    }

    @Test
    fun resetDropsWinnerAndHold() {
        val session = ChorusPeerSession(timeoutMs = 90_000L, nowMs = { 1_000L })
        session.setLocalWinner(true)
        session.reset()
        assertFalse(session.isLocalWinner())
        assertTrue(session.markPeer())
        assertTrue(session.isActive())
    }
}
