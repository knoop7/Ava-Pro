package com.example.ava.localllm.remote

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaSelfClockAlertTest {
    @Test fun schemaAddsHourOnlyWhenAlarmIsOn() {
        val off = AvaSelfTools.schema(listOf("read", "stop")).tools.single()
        assertTrue(off.params.none { it.name == "hour" })
        val on = AvaSelfTools.schema(listOf("alarm", "reminder")).tools.single()
        assertTrue(on.params.any { it.name == "hour" })
        assertTrue(on.params.any { it.name == "minute" })
        assertTrue(on.description.contains("action=alarm"))
        assertTrue(on.description.contains("action=reminder"))
        assertTrue(on.params.single { it.name == "action" }.description.contains("alarm="))
    }

    @Test fun alarmNeedsHourAndABareRemindIsStillAnAlarm() {
        val tools = AvaSelfTools.schema(listOf("alarm", "reminder"))
        val def = tools.tools.single()
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "alarm")))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "alarm").put("hour", 7).put("minute", 0)))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "alarm").put("cancel", true)))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "reminder").put("hour", 14)))
        assertNull(
            AvaToolCallback.validate(
                def,
                JSONObject().put("action", "reminder").put("hour", 14).put("minute", 0).put("target", "medicine"),
            ),
        )
        assertTrue(def.description.contains("do not ask"))
        assertTrue(def.description.contains("not a clock time"))
    }

    @Test fun schemaExposesDateFieldsForAlarm() {
        val on = AvaSelfTools.schema(listOf("alarm")).tools.single()
        assertTrue(on.params.any { it.name == "year" })
        assertTrue(on.params.any { it.name == "month" })
        assertTrue(on.params.any { it.name == "day" })
        assertTrue(on.params.any { it.name == "days" })
        assertTrue(on.description.contains("days="))
        assertNull(
            AvaToolCallback.validate(
                on,
                JSONObject().put("action", "alarm").put("hour", 7).put("days", 2),
            ),
        )
        assertNull(
            AvaToolCallback.validate(
                on,
                JSONObject()
                    .put("action", "alarm")
                    .put("hour", 8)
                    .put("year", 2026)
                    .put("month", 9)
                    .put("day", 23),
            ),
        )
    }
}
