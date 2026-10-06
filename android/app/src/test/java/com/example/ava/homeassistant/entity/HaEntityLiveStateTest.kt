package com.example.ava.homeassistant.entity

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HaEntityLiveStateTest {

    @Test
    fun fromRestKeepsUnitAndDeviceClass() {
        val live = HaEntityLiveState.fromRest(
            JSONObject(
                """
                {
                  "entity_id": "sensor.kitchen_temperature",
                  "state": "23.5",
                  "last_changed": "2026-09-12T15:00:00+00:00",
                  "attributes": {
                    "friendly_name": "Kitchen temperature",
                    "unit_of_measurement": "°C",
                    "device_class": "temperature",
                    "entity_picture": "/local/secret.png"
                  }
                }
                """.trimIndent(),
            ),
        )
        requireNotNull(live)
        assertEquals("sensor.kitchen_temperature", live.entityId)
        assertEquals("Kitchen temperature", live.name)
        assertEquals("sensor", live.domain)
        assertEquals("23.5", live.state)
        assertEquals("°C", live.unit)
        assertEquals("temperature", live.deviceClass)
        assertEquals("2026-09-12T15:00:00+00:00", live.lastChanged)
        val json = live.putSpeakable(JSONObject())
        assertFalse(json.has("entity_picture"))
        assertFalse(json.has("attributes"))
    }

    @Test
    fun fromRestKeepsSpeakableValuesAndDropsTheAttributeBag() {
        val light = HaEntityLiveState.fromRest(
            JSONObject(
                """
                {
                  "entity_id": "light.desk",
                  "state": "on",
                  "attributes": {
                    "brightness": 128,
                    "color_temp_kelvin": 2700,
                    "color_name": "warm",
                    "supported_features": 44,
                    "supported_color_modes": ["brightness"],
                    "entity_picture": "/local/secret.png"
                  }
                }
                """.trimIndent(),
            ),
        )
        requireNotNull(light)
        assertEquals(128, light.brightness)
        assertEquals(50, light.brightnessPct)
        assertEquals(2700, light.colorTempKelvin)
        assertEquals("warm", light.colorName)
        val spoken = light.putSpeakable(JSONObject())
        assertEquals(50, spoken.getInt("brightness_pct"))
        assertFalse(spoken.has("supported_features"))
        assertFalse(spoken.has("supported_color_modes"))
        assertFalse(spoken.has("entity_picture"))
        assertFalse(spoken.has("attributes"))

        val climate = HaEntityLiveState.fromRest(
            JSONObject(
                """
                {
                  "entity_id": "climate.hall",
                  "state": "cool",
                  "attributes": {
                    "temperature": 24,
                    "current_temperature": 26.5,
                    "humidity": 50,
                    "hvac_mode": "cool",
                    "fan_mode": "low",
                    "swing_mode": "vertical",
                    "preset_mode": "sleep"
                  }
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals(24.0, climate.temperature!!, 0.01)
        assertEquals(26.5, climate.currentTemperature!!, 0.01)
        assertEquals(50, climate.humidity)
        assertEquals("cool", climate.hvacMode)
        assertEquals("low", climate.fanMode)
        assertEquals("vertical", climate.swingMode)
        assertEquals("sleep", climate.presetMode)

        val fan = HaEntityLiveState.fromRest(
            JSONObject("""{"entity_id":"fan.desk","state":"on","attributes":{"percentage":33,"oscillating":true,"direction":"forward"}}"""),
        )!!
        assertEquals(33, fan.percentage)
        assertEquals(true, fan.oscillating)
        assertEquals("forward", fan.direction)

        val cover = HaEntityLiveState.fromRest(
            JSONObject("""{"entity_id":"cover.shade","state":"open","attributes":{"current_position":40,"current_tilt_position":15}}"""),
        )!!
        assertEquals(40, cover.position)
        assertEquals(15, cover.tiltPosition)

        val lightRgb = HaEntityLiveState.fromRest(
            JSONObject("""{"entity_id":"light.lamp","state":"on","attributes":{"rgb_color":[10,20,30],"effect":"rainbow"}}"""),
        )!!
        assertEquals(listOf(10, 20, 30), lightRgb.rgbColor)
        assertEquals("rainbow", lightRgb.effect)

        val speaker = HaEntityLiveState.fromRest(
            JSONObject(
                """
                {
                  "entity_id": "media_player.room",
                  "state": "playing",
                  "attributes": {
                    "volume_level": 0.2,
                    "is_volume_muted": true,
                    "source": "HDMI",
                    "media_title": "Song",
                    "media_artist": "Band",
                    "media_album": "LP"
                  }
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals(20, speaker.volumePct)
        assertEquals(true, speaker.muted)
        assertEquals("HDMI", speaker.source)
        assertEquals("Song", speaker.mediaTitle)
        assertEquals("Band", speaker.mediaArtist)
        assertEquals("LP", speaker.mediaAlbum)
    }

    @Test
    fun fromRestRejectsMissingEntityId() {
        assertNull(HaEntityLiveState.fromRest(JSONObject("""{"state":"on"}""")))
    }
}
