package com.example.ava.localllm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaServicePayloadTest {
    @Test fun spokenColorAndBooleanColorBecomeHaFields() {
        val raw = JSONObject().put("color_name", "暖白").put("brightness_pct", 40)
        val out = HaServicePayload.normalize("light", "turn_on", raw)
        assertEquals(3000, out.getInt("color_temp_kelvin"))
        assertEquals(40, out.getInt("brightness_pct"))
        val lifted = HaServicePayload.normalize("light", "turn_on", JSONObject().put("white", true))
        assertEquals("white", lifted.getString("color_name"))
        assertFalse(lifted.has("white"))
    }

    @Test fun climateModeAndFanPercentAreHostMapped() {
        val climate = HaServicePayload.normalize(
            "climate",
            "set_temperature",
            JSONObject().put("temperature", 24).put("hvac_mode", "制冷"),
        )
        assertEquals("cool", climate.getString("hvac_mode"))
        val fan = HaServicePayload.normalize(
            "fan",
            "set_percentage",
            JSONObject().put("brightness_pct", 33),
        )
        assertEquals(33, fan.getInt("percentage"))
    }

    @Test fun overflowFieldsMergeIntoData() {
        val args = JSONObject()
            .put("entity_id", "light.desk")
            .put("color_name", "red")
            .put("data", JSONObject().put("brightness_pct", 20))
        val merged = HaServicePayload.mergeOverflow(args)
        val out = HaServicePayload.normalize("light", "turn_on", merged)
        assertEquals("red", out.getString("color_name"))
        assertEquals(20, out.getInt("brightness_pct"))
    }

    @Test fun nestedEntityIdDoesNotLeakIntoServiceData() {
        val args = JSONObject()
            .put("entity_id", "客厅灯")
            .put(
                "data",
                JSONObject().put("entity_id", "客厅灯").put("brightness_pct", 40),
            )
        val merged = HaServicePayload.mergeOverflow(args)
        assertFalse(merged.has("entity_id"))
        val map = HaServicePayload.asMap(HaServicePayload.normalize("light", "turn_on", merged))
        assertFalse(map.containsKey("entity_id"))
        assertEquals(40, map["brightness_pct"])
        assertFalse(HaServicePayload.isMatchToken("客厅灯"))
        assertTrue(HaServicePayload.isMatchToken("all"))
        assertTrue(HaServicePayload.isHaEntityId("light.desk"))
        assertFalse(HaServicePayload.isHaEntityId("客厅灯"))
    }

    @Test fun powerWithFieldsPicksTheRealService() {
        assertEquals(
            "climate.set_temperature",
            HaServicePayload.route("climate", "turn_on", JSONObject().put("temperature", 24).put("hvac_mode", "制冷")),
        )
        assertEquals(
            "cover.set_cover_position",
            HaServicePayload.route("cover", "turn_on", JSONObject().put("position", "一半")),
        )
        assertEquals(
            "fan.set_percentage",
            HaServicePayload.route("fan", "turn_on", JSONObject().put("风速", 33)),
        )
        assertEquals(
            "media_player.volume_set",
            HaServicePayload.route("media_player", "turn_on", JSONObject().put("volume_pct", 20)),
        )
        assertEquals(
            "humidifier.set_humidity",
            HaServicePayload.route("humidifier", "turn_on", JSONObject().put("humidity", 45)),
        )
        assertEquals(
            "water_heater.set_temperature",
            HaServicePayload.route("water_heater", "turn_on", JSONObject().put("temperature", 50)),
        )
        assertEquals(
            "valve.set_valve_position",
            HaServicePayload.route("valve", "turn_on", JSONObject().put("position", 80)),
        )
        assertEquals(
            "vacuum.set_fan_speed",
            HaServicePayload.route("vacuum", "turn_on", JSONObject().put("吸力", "强力")),
        )
    }

    @Test fun namedServiceAliasesStayOnTheDomain() {
        assertEquals("media_player.media_pause", HaServicePayload.route("media_player", "pause", JSONObject()))
        assertEquals("cover.open_cover", HaServicePayload.route("cover", "open", JSONObject()))
        assertEquals("vacuum.return_to_base", HaServicePayload.route("vacuum", "dock", JSONObject()))
        assertEquals("lawn_mower.start_mowing", HaServicePayload.route("lawn_mower", "turn_on", JSONObject()))
        assertEquals("alarm_control_panel.alarm_disarm", HaServicePayload.route("alarm_control_panel", "disarm", JSONObject()))
    }

    @Test fun hostMapsSpokenValuesOnNonLightDomains() {
        val climate = HaServicePayload.normalize(
            "climate",
            "set_temperature",
            JSONObject().put("temperature", "二十六").put("hvac_mode", "制冷"),
        )
        assertEquals(26.0, climate.getDouble("temperature"), 0.01)
        assertEquals("cool", climate.getString("hvac_mode"))
        val cover = HaServicePayload.normalize("cover", "set_cover_position", JSONObject().put("position", "一半"))
        assertEquals(50, cover.getInt("position"))
        val media = HaServicePayload.normalize("media_player", "volume_set", JSONObject().put("volume_pct", 20))
        assertEquals(0.2, media.getDouble("volume_level"), 0.001)
        val humid = HaServicePayload.normalize("humidifier", "set_humidity", JSONObject().put("湿度", 40))
        assertEquals(40, humid.getInt("humidity"))
        val vac = HaServicePayload.normalize("vacuum", "set_fan_speed", JSONObject().put("fan_speed", "强力"))
        assertEquals("strong", vac.getString("fan_speed"))
        val mute = HaServicePayload.normalize("media_player", "unmute", JSONObject())
        assertEquals(false, mute.getBoolean("is_volume_muted"))
    }
}
