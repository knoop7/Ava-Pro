package com.example.ava.settings

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bulk intent payloads must not clone ESPHome identity across devices
 * (Ava#147, issue #201): `macAddress` in a voice_satellite patch marks the
 * payload as a cloned export blob — the UI never exposes MAC editing.
 */
class AvaSettingsApplierIdentityGuardTest {

    @Test
    fun clonedExportBlob_dropsNameAndMac_keepsOtherFields() {
        val bulk = buildJsonObject {
            put("voice_satellite", buildJsonObject {
                put("name", "living_room")
                put("macAddress", "AA:BB:CC:DD:EE:FF")
                put("serverPort", 6420)
                put("haRemoteUrl", "http://ha.local:8123")
            })
            put("microphone", buildJsonObject { put("voicePrintEnabled", true) })
        }

        val guarded = AvaSettingsApplier.withDeviceLocalEspHomeIdentity(bulk)
        val vs = guarded["voice_satellite"]!!.jsonObject

        assertFalse(vs.containsKey("name"))
        assertFalse(vs.containsKey("macAddress"))
        assertEquals(JsonPrimitive(6420), vs["serverPort"])
        assertEquals(JsonPrimitive("http://ha.local:8123"), vs["haRemoteUrl"])
        assertEquals(bulk["microphone"], guarded["microphone"])
    }

    @Test
    fun deliberatePerDeviceRename_passesThrough() {
        val bulk = buildJsonObject {
            put("voice_satellite", buildJsonObject { put("name", "kitchen_panel") })
        }
        assertEquals(bulk, AvaSettingsApplier.withDeviceLocalEspHomeIdentity(bulk))
    }

    @Test
    fun macWithoutName_stillDropped() {
        val bulk = buildJsonObject {
            put("voice_satellite", buildJsonObject {
                put("macAddress", "AA:BB:CC:DD:EE:FF")
                put("serverPort", 6420)
            })
        }
        val vs = AvaSettingsApplier.withDeviceLocalEspHomeIdentity(bulk)["voice_satellite"]!!.jsonObject
        assertFalse(vs.containsKey("macAddress"))
        assertTrue(vs.containsKey("serverPort"))
    }

    @Test
    fun payloadWithoutVoiceSatellite_untouched() {
        val bulk = buildJsonObject {
            put("player", buildJsonObject { put("volume", 50) })
        }
        assertEquals(bulk, AvaSettingsApplier.withDeviceLocalEspHomeIdentity(bulk))
    }
}
