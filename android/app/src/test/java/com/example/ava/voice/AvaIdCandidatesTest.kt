package com.example.ava.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beacon ids derived from a Mass player_id. Pure hash path, no Android runtime.
 *
 * Mass may namespace the client id it was handed, so the bare ANDROID_ID has to
 * stay reachable: a device announcing `ce77174d1b9986b9` was listed as
 * `upce77174d1b9986b9`, and hashing only that form pointed every peer lookup at a
 * beacon id nobody advertises.
 */
class AvaIdCandidatesTest {

    private val androidId = "ce77174d1b9986b9"
    private val beaconId = AvaVoiceDiscovery.stableAvaDeviceId(androidId)

    @Test
    fun namespacedMassIdStillResolvesToTheDeviceBeacon() {
        val candidates = AvaSyncOffsetPeer.avaIdCandidates("up$androidId")
        assertTrue("$candidates should reach $beaconId", beaconId in candidates)
    }

    @Test
    fun namespacedMassIdKeepsItsOwnHashFirst() {
        val candidates = AvaSyncOffsetPeer.avaIdCandidates("up$androidId")
        assertEquals(AvaVoiceDiscovery.stableAvaDeviceId("up$androidId"), candidates.first())
    }

    @Test
    fun bareAndroidIdIsNotStripped() {
        assertEquals(listOf(beaconId), AvaSyncOffsetPeer.avaIdCandidates(androidId))
    }

    @Test
    fun beaconIdPassesThrough() {
        assertEquals(listOf(beaconId), AvaSyncOffsetPeer.avaIdCandidates(beaconId))
    }

    @Test
    fun nonHexTailIsNotStripped() {
        val id = "airplay-living-room-speaker"
        assertEquals(listOf(AvaVoiceDiscovery.stableAvaDeviceId(id)), AvaSyncOffsetPeer.avaIdCandidates(id))
    }

    @Test
    fun blankHasNoCandidates() {
        assertEquals(emptyList<String>(), AvaSyncOffsetPeer.avaIdCandidates("   "))
    }

    @Test
    fun distinctDevicesNeverShareACandidate() {
        val a = AvaSyncOffsetPeer.avaIdCandidates("up$androidId")
        val b = AvaSyncOffsetPeer.avaIdCandidates("upfee8b70503462206")
        assertTrue("$a and $b overlap", a.intersect(b.toSet()).isEmpty())
    }
}
