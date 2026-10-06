package com.example.ava.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for session-scoped call signaling (issue #203: hang-up/accept
 * applied to stale sessions). New fields must stay backward compatible with peers
 * that emit or parse only the legacy `from|to` shape.
 */
class AvaVoiceCallSignalingProtocolTest {

    @Test
    fun hangup_roundTrip_carriesSessionIds() {
        val wire = buildVoiceHangup("dev_a", "dev_b", listOf(123, 456))
        assertEquals("AVA_VOICE_HANGUP|dev_a|dev_b|123,456", wire)
        val parsed = parseVoiceHangup(wire)
        assertNotNull(parsed)
        assertEquals("dev_a", parsed!!.fromDeviceId)
        assertEquals("dev_b", parsed.toDeviceId)
        assertEquals(listOf(123, 456), parsed.hangupSessionIds)
    }

    @Test
    fun hangup_legacyWireWithoutSessionIds_parsesEmptyList() {
        val parsed = parseVoiceHangup("AVA_VOICE_HANGUP|dev_a|dev_b")
        assertNotNull(parsed)
        assertTrue(parsed!!.hangupSessionIds.isEmpty())
    }

    @Test
    fun hangup_withoutSessionIds_buildsLegacyShape() {
        assertEquals("AVA_VOICE_HANGUP|dev_a|dev_b", buildVoiceHangup("dev_a", "dev_b"))
    }

    @Test
    fun hangup_malformedSessionIdField_isIgnoredNotFatal() {
        val parsed = parseVoiceHangup("AVA_VOICE_HANGUP|dev_a|dev_b|junk,42")
        assertNotNull(parsed)
        assertEquals(listOf(42), parsed!!.hangupSessionIds)
    }

    @Test
    fun decline_roundTrip_carriesSessionId() {
        val wire = buildVoiceDecline("dev_b", "dev_a", 789)
        assertEquals("AVA_VOICE_DECLINE|dev_b|dev_a|789", wire)
        val parsed = parseVoiceDecline(wire)
        assertNotNull(parsed)
        assertEquals(789, parsed!!.sessionId)
    }

    @Test
    fun decline_legacyWire_parsesSessionZero() {
        val parsed = parseVoiceDecline("AVA_VOICE_DECLINE|dev_b|dev_a")
        assertNotNull(parsed)
        assertEquals(0, parsed!!.sessionId)
    }

    @Test
    fun decline_defaultSessionZero_buildsLegacyShape() {
        assertEquals("AVA_VOICE_DECLINE|dev_b|dev_a", buildVoiceDecline("dev_b", "dev_a"))
    }

    @Test
    fun noAnswer_roundTrip_carriesSessionId() {
        val wire = buildVoiceNoAnswer("dev_b", "dev_a", 321)
        assertEquals("AVA_VOICE_NO_ANSWER|dev_b|dev_a|321", wire)
        val parsed = parseVoiceNoAnswer(wire)
        assertNotNull(parsed)
        assertEquals(321, parsed!!.sessionId)
    }

    @Test
    fun noAnswer_legacyWire_parsesSessionZero() {
        val parsed = parseVoiceNoAnswer("AVA_VOICE_NO_ANSWER|dev_b|dev_a")
        assertNotNull(parsed)
        assertEquals(0, parsed!!.sessionId)
    }

    @Test
    fun beacon_optionalModel_roundTripAndLegacyIgnoresIt() {
        val wire = buildBeacon(
            deviceId = "ava_1",
            deviceName = "Vivo",
            deviceType = AvaVoiceDeviceType.PHONE,
            hostIp = "10.0.0.2",
            model = "V2307A",
        )
        assertTrue(wire.contains("|model=V2307A"))
        val parsed = parseBeacon(wire, "10.0.0.2")
        assertNotNull(parsed)
        assertEquals("Vivo", parsed!!.name)
        assertEquals("V2307A", parsed.model)
        assertEquals("Vivo（phone，V2307A）", parsed.identityLabel())
        assertEquals("Vivo  type=phone  model=V2307A", parsed.rosterLine())
        val legacy = parseBeacon(
            "AVA_VOICE_BEACON|ava_1|Vivo|phone|10.0.0.2|clusterPort=0|webConsole=0",
            "10.0.0.2",
        )
        assertNotNull(legacy)
        assertEquals("", legacy!!.model)
        assertEquals("Vivo（phone）", legacy.identityLabel())
        assertEquals("Vivo  type=phone", legacy.rosterLine())
    }
}
