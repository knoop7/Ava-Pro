package com.example.ava.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothClaimLedgerTest {

    @Test
    fun parseOldClaim_withoutRssi() {
        val msg = BluetoothClaimProtocol.parse("AVA_CLAIM:panel-a:12345")
        requireNotNull(msg)
        assertEquals(ClaimMessageKind.CLAIM, msg.kind)
        assertEquals("panel-a", msg.senderId)
        assertEquals(12345L, msg.address)
        assertEquals(UNKNOWN_CLAIM_RSSI, msg.rssi)
        assertFalse(msg.rssi.hasClaimRssi())
    }

    @Test
    fun parseNewClaim_andRelease() {
        val encoded = BluetoothClaimProtocol.encodeClaim("panel-a", 9L, -55, 15_000L, "echo1")
        val claim = BluetoothClaimProtocol.parse(encoded)
        requireNotNull(claim)
        assertEquals(-55, claim.rssi)
        assertEquals(15_000L, claim.heldMs)
        assertEquals("echo1", claim.echoTag)

        val release = BluetoothClaimProtocol.parse(
            BluetoothClaimProtocol.encodeRelease("panel-a", 9L, "echo1"),
        )
        requireNotNull(release)
        assertEquals(ClaimMessageKind.RELEASE, release.kind)
        assertEquals(9L, release.address)
    }

    @Test
    fun firstCome_blocksSecondPanel() {
        var now = 1_000L
        val a = BluetoothClaimLedger("a", "tag-a", nowMs = { now })
        val b = BluetoothClaimLedger("b", "tag-b", nowMs = { now })
        assertEquals(ClaimOutcome.NEW, a.claim(1L, -70))
        b.applyRemote(
            ParsedClaimMessage(ClaimMessageKind.CLAIM, "a", 1L, rssi = -70, heldMs = 0L),
        )
        assertEquals(ClaimOutcome.REJECTED, b.claim(1L, -70))
        assertTrue(a.localAddresses().contains(1L))
        assertTrue(b.localAddresses().isEmpty())
    }

    @Test
    fun staleRemote_canBeReclaimed() {
        var now = 1_000L
        val b = BluetoothClaimLedger("b", "tag-b", nowMs = { now })
        b.applyRemote(ParsedClaimMessage(ClaimMessageKind.CLAIM, "a", 1L, rssi = -80))
        now += BluetoothClaimLedger.REMOTE_EXPIRE_MS + 1
        assertEquals(ClaimOutcome.NEW, b.claim(1L, -60))
    }

    @Test
    fun strongerRssi_takesOverAfterHoldAndStrikes() {
        var now = 1_000L
        val b = BluetoothClaimLedger("b", "tag-b", nowMs = { now })
        b.applyRemote(
            ParsedClaimMessage(
                kind = ClaimMessageKind.CLAIM,
                senderId = "a",
                address = 1L,
                rssi = -80,
                heldMs = BluetoothClaimLedger.MIN_HOLD_MS,
            ),
        )
        now += BluetoothClaimLedger.MIN_HOLD_MS
        repeat(BluetoothClaimLedger.TAKEOVER_STRIKES - 1) {
            assertFalse(b.noteObservation(1L, -70))
        }
        assertTrue(b.noteObservation(1L, -70))
        assertEquals(listOf(1L), b.localAddresses())
    }

    @Test
    fun weakerOrEqualRssi_doesNotTakeOver() {
        var now = 1_000L
        val b = BluetoothClaimLedger("b", "tag-b", nowMs = { now })
        b.applyRemote(
            ParsedClaimMessage(
                kind = ClaimMessageKind.CLAIM,
                senderId = "a",
                address = 1L,
                rssi = -70,
                heldMs = BluetoothClaimLedger.MIN_HOLD_MS,
            ),
        )
        now += BluetoothClaimLedger.MIN_HOLD_MS
        repeat(5) {
            assertFalse(b.noteObservation(1L, -70))
        }
        assertTrue(b.localAddresses().isEmpty())
    }

    @Test
    fun localExpiry_releasesUnseenClaim() {
        var now = 1_000L
        val a = BluetoothClaimLedger("a", "tag-a", nowMs = { now })
        assertEquals(ClaimOutcome.NEW, a.claim(1L, -60))
        now += BluetoothClaimLedger.LOCAL_EXPIRE_MS + 1
        assertEquals(listOf(1L), a.expire())
        assertTrue(a.localAddresses().isEmpty())
    }

    @Test
    fun oldClaimPacket_doesNotWinOnUnknownRssi() {
        var now = 1_000L
        val b = BluetoothClaimLedger("b", "tag-b", nowMs = { now })
        b.applyRemote(BluetoothClaimProtocol.parse("AVA_CLAIM:panel-a:1")!!)
        now += BluetoothClaimLedger.MIN_HOLD_MS
        repeat(5) {
            assertFalse(b.noteObservation(1L, -40))
        }
        assertTrue(b.localAddresses().isEmpty())
        now += BluetoothClaimLedger.REMOTE_EXPIRE_MS + 1
        assertEquals(ClaimOutcome.NEW, b.claim(1L, -40))
    }

    @Test
    fun selfEcho_isIgnored() {
        val a = BluetoothClaimLedger("a", "echo-a")
        a.claim(1L, -50)
        val changed = a.applyRemote(
            ParsedClaimMessage(
                kind = ClaimMessageKind.CLAIM,
                senderId = "a",
                address = 2L,
                echoTag = "echo-a",
            ),
        )
        assertFalse(changed)
        assertEquals(listOf(1L), a.localAddresses())
    }

    @Test
    fun yield_whenPeerIsStrongerLongEnough() {
        var now = 1_000L
        val a = BluetoothClaimLedger("a", "tag-a", nowMs = { now })
        a.claim(1L, -80)
        now += BluetoothClaimLedger.MIN_HOLD_MS
        val peer = ParsedClaimMessage(
            kind = ClaimMessageKind.CLAIM,
            senderId = "b",
            address = 1L,
            rssi = -70,
            heldMs = BluetoothClaimLedger.MIN_HOLD_MS,
        )
        repeat(BluetoothClaimLedger.TAKEOVER_STRIKES - 1) {
            assertFalse(a.applyRemote(peer))
        }
        assertTrue(a.applyRemote(peer))
        assertTrue(a.localAddresses().isEmpty())
    }
}
