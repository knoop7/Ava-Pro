package com.example.ava.homeassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HaServiceJsonTest {
    @Test fun rgbRemainsAnArrayOnTheWire() {
        val data = JSONObject()
        HaServiceJson.put(data, "rgb_color", JSONArray(listOf(255, 0, 0)))
        val wire = JSONObject(JSONObject().put("service_data", data).toString()).getJSONObject("service_data")
        assertEquals(255, wire.getJSONArray("rgb_color").getInt(0))
        assertEquals(3, wire.getJSONArray("rgb_color").length())
    }

    @Test fun nestedObjectsMapsArraysAndNullPreserveTheirTypes() {
        val data = JSONObject()
        HaServiceJson.put(data, "object", JSONObject().put("enabled", true).put("missing", JSONObject.NULL))
        HaServiceJson.put(data, "map", mapOf("values" to listOf(1, false, null), "nested" to mapOf("x" to 2)))
        HaServiceJson.put(data, "null", JSONObject.NULL)
        val wire = JSONObject(data.toString())
        assertTrue(wire.getJSONObject("object").getBoolean("enabled"))
        assertTrue(wire.getJSONObject("object").isNull("missing"))
        assertFalse(wire.getJSONObject("map").getJSONArray("values").getBoolean(1))
        assertTrue(wire.getJSONObject("map").getJSONArray("values").isNull(2))
        assertEquals(2, wire.getJSONObject("map").getJSONObject("nested").getInt("x"))
        assertTrue(wire.isNull("null"))
    }

    @Test fun stringCodesRetainLeadingZeros() {
        val data = JSONObject()
        HaServiceJson.put(data, "code", "0012")
        assertEquals("0012", JSONObject(data.toString()).get("code"))
    }
}
