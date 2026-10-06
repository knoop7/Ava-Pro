package com.example.ava.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineSeatTest {

    @Test
    fun issueHoldsUntilRelease() {
        var now = 1_000L
        val seat = ConversationEngineSeat(
            nowMs = { now },
            leaseMs = 10_000L,
            tokenFactory = { "t1" },
        )
        val lease = seat.issue("engine")
        assertEquals("t1", lease.token)
        assertTrue(seat.isHeld())
        assertTrue(seat.isCurrent("t1"))
        assertFalse(seat.isCurrent("other"))
        assertTrue(seat.release("t1"))
        assertFalse(seat.isHeld())
        assertFalse(seat.release("t1"))
    }

    @Test
    fun staleReleaseIsIgnored() {
        var now = 1_000L
        val tokens = ArrayDeque(listOf("old", "new"))
        val seat = ConversationEngineSeat(
            nowMs = { now },
            leaseMs = 10_000L,
            tokenFactory = { tokens.removeFirst() },
        )
        seat.issue("engine")
        seat.clear()
        seat.issue("engine")
        assertFalse(seat.release("old"))
        assertTrue(seat.isCurrent("new"))
        assertTrue(seat.release("new"))
        assertNull(seat.current)
    }

    @Test
    fun latterIssueSupersedes() {
        var now = 1_000L
        val tokens = ArrayDeque(listOf("a", "b"))
        val seat = ConversationEngineSeat(
            nowMs = { now },
            leaseMs = 10_000L,
            tokenFactory = { tokens.removeFirst() },
        )
        val first = seat.issue("engine")
        val second = seat.issue("engine")
        assertNotEquals(first.token, second.token)
        assertFalse(seat.isCurrent("a"))
        assertTrue(seat.isCurrent("b"))
        assertEquals(2, second.generation)
    }

    @Test
    fun renewExtendsLease() {
        var now = 1_000L
        val seat = ConversationEngineSeat(
            nowMs = { now },
            leaseMs = 5_000L,
            tokenFactory = { "t" },
        )
        seat.issue("engine")
        now = 4_000L
        assertTrue(seat.renew("t"))
        now = 8_000L
        assertTrue(seat.isCurrent("t"))
        now = 10_000L
        assertFalse(seat.isCurrent("t"))
        assertTrue(seat.isExpired())
        assertFalse(seat.renew("t"))
    }

    @Test
    fun expireWithoutRelease() {
        var now = 0L
        val seat = ConversationEngineSeat(
            nowMs = { now },
            leaseMs = 100L,
            tokenFactory = { "t" },
        )
        seat.issue("engine")
        now = 99L
        assertFalse(seat.isExpired())
        now = 100L
        assertTrue(seat.isExpired())
        assertFalse(seat.isHeld())
        val cleared = seat.clear()
        assertEquals("t", cleared?.token)
        assertNull(seat.current)
    }
}
