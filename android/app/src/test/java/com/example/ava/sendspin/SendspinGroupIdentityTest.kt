package com.example.ava.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinGroupIdentityTest {
    @Test
    fun firstIdIsSolo() {
        val gate = SendspinGroupIdentity()
        assertEquals(
            SendspinGroupIdentity.Decision.SOLO,
            gate.onGroupId("solo-a", userSanctioned = false),
        )
        assertFalse(gate.blocked)
        assertEquals("solo-a", gate.soloGroupId)
    }

    @Test
    fun emptyIdIsIgnored() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        assertEquals(
            SendspinGroupIdentity.Decision.UNCHANGED,
            gate.onGroupId("", userSanctioned = false),
        )
        assertEquals(
            SendspinGroupIdentity.Decision.UNCHANGED,
            gate.onGroupId("  ", userSanctioned = true),
        )
        assertEquals("solo-a", gate.soloGroupId)
        assertFalse(gate.blocked)
    }

    @Test
    fun sameIdIsUnchanged() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        assertEquals(
            SendspinGroupIdentity.Decision.UNCHANGED,
            gate.onGroupId("solo-a", userSanctioned = false),
        )
        assertFalse(gate.blocked)
    }

    @Test
    fun unsanctionedIdChangeIsForeign() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        assertEquals(
            SendspinGroupIdentity.Decision.FOREIGN,
            gate.onGroupId("room-b", userSanctioned = false),
        )
        assertTrue(gate.blocked)
        assertEquals("solo-a", gate.soloGroupId)
        assertEquals("room-b", gate.currentGroupId)
    }

    @Test
    fun sanctionedIdChangeJoinsShared() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        assertEquals(
            SendspinGroupIdentity.Decision.ADOPT_SHARED,
            gate.onGroupId("room-b", userSanctioned = true),
        )
        assertFalse(gate.blocked)
        // The user's pairing does not rewrite what our solo home is.
        assertEquals("solo-a", gate.soloGroupId)
        assertEquals("room-b", gate.currentGroupId)
    }

    @Test
    fun lateProofAdoptsCurrentAsShared() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        gate.onGroupId("room-b", userSanctioned = false)
        assertTrue(gate.blocked)
        assertTrue(gate.adoptCurrentAsShared())
        assertFalse(gate.blocked)
        assertEquals("solo-a", gate.soloGroupId)
        assertEquals("room-b", gate.currentGroupId)
        // Second call is a no-op.
        assertFalse(gate.adoptCurrentAsShared())
    }

    @Test
    fun quietForeignAdoptsCurrentAsSolo() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        gate.onGroupId("solo-b", userSanctioned = false)
        assertTrue(gate.blocked)
        assertTrue(gate.adoptCurrentAsSolo())
        assertFalse(gate.blocked)
        assertEquals("solo-b", gate.soloGroupId)
        assertEquals("solo-b", gate.currentGroupId)
        assertFalse(gate.adoptCurrentAsSolo())
    }

    @Test
    fun sameIdAfterLeaveStaysForeign() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        gate.onGroupId("room-b", userSanctioned = false)
        gate.markLeavingForeign()
        assertEquals(
            SendspinGroupIdentity.Decision.UNCHANGED,
            gate.onGroupId("room-b", userSanctioned = false),
        )
        assertTrue(gate.blocked)
        assertTrue(gate.expectingNewSolo)
    }

    @Test
    fun leaveThenNewIdBecomesSolo() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        gate.onGroupId("room-b", userSanctioned = false)
        gate.markLeavingForeign()
        assertEquals(
            SendspinGroupIdentity.Decision.ADOPT_NEW_SOLO,
            gate.onGroupId("solo-c", userSanctioned = false),
        )
        assertFalse(gate.blocked)
        assertEquals("solo-c", gate.soloGroupId)
    }

    @Test
    fun returnToOriginalSoloUnblocks() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        gate.onGroupId("room-b", userSanctioned = false)
        assertEquals(
            SendspinGroupIdentity.Decision.SOLO,
            gate.onGroupId("solo-a", userSanctioned = false),
        )
        assertFalse(gate.blocked)
    }

    @Test
    fun unsyncHandoutAfterSharedIsForeignUntilAdopted() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        // User pairs; server moves us into the leader's group.
        gate.onGroupId("room-b", userSanctioned = true)
        // User unsyncs; server re-homes us onto a fresh solo id while MA
        // already reports "not grouped". Quiet probation adopts it.
        assertEquals(
            SendspinGroupIdentity.Decision.FOREIGN,
            gate.onGroupId("solo-c", userSanctioned = false),
        )
        assertTrue(gate.blocked)
        assertTrue(gate.adoptCurrentAsSolo())
        assertFalse(gate.blocked)
        assertEquals("solo-c", gate.soloGroupId)
    }

    @Test
    fun adoptersAreNoOpsWhileUnblocked() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        assertFalse(gate.adoptCurrentAsShared())
        assertFalse(gate.adoptCurrentAsSolo())
        assertEquals("solo-a", gate.soloGroupId)
    }

    @Test
    fun resetClearsBaseline() {
        val gate = SendspinGroupIdentity()
        gate.onGroupId("solo-a", userSanctioned = false)
        gate.onGroupId("room-b", userSanctioned = false)
        gate.reset()
        assertEquals(
            SendspinGroupIdentity.Decision.SOLO,
            gate.onGroupId("solo-c", userSanctioned = false),
        )
        assertFalse(gate.blocked)
        assertEquals("solo-c", gate.soloGroupId)
    }
}
