package com.example.ava.backup

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaBackupMacStripTest {

    @Test
    fun withoutClonedEspHomeIdentity_removesMacAndName_keepsOtherFields() {
        val input = JsonObject(
            mapOf(
                "name" to JsonPrimitive("LR Tablet Ava"),
                "macAddress" to JsonPrimitive("AA:BB:CC:DD:EE:FF"),
                "serverPort" to JsonPrimitive(6053),
            ),
        )

        val stripped = AvaBackupManager.withoutClonedEspHomeIdentity(input)

        assertFalse(stripped.containsKey("macAddress"))
        assertFalse(stripped.containsKey("name"))
        assertEquals(6053, stripped.getValue("serverPort").jsonPrimitive.content.toInt())
    }

    @Test
    fun withoutClonedEspHomeIdentity_noopWhenIdentityAbsent() {
        val input = JsonObject(mapOf("serverPort" to JsonPrimitive(6053)))
        assertSame(input, AvaBackupManager.withoutClonedEspHomeIdentity(input))
        assertTrue(input.containsKey("serverPort"))
    }
}
