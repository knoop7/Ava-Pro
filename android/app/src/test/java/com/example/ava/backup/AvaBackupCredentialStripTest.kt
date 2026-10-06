package com.example.ava.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaBackupCredentialStripTest {

    @Test
    fun withoutHaSecrets_dropsUrlAndToken_keepsPipeline() {
        val input = JsonObject(
            mapOf(
                "serverUrl" to JsonPrimitive("http://ha.local:8123"),
                "accessToken" to JsonPrimitive("secret-token"),
                "preferredPipeline" to JsonPrimitive("home"),
            ),
        )

        val stripped = AvaBackupManager.withoutHaSecrets(input)

        assertFalse(stripped.containsKey("serverUrl"))
        assertFalse(stripped.containsKey("accessToken"))
        assertEquals("home", stripped.getValue("preferredPipeline").jsonPrimitive.content)
    }

    @Test
    fun withoutMassApiSecrets_dropsLogin_keepsEnabled() {
        val input = JsonObject(
            mapOf(
                "enabled" to JsonPrimitive(true),
                "serverUrl" to JsonPrimitive("http://ma.local:8095"),
                "username" to JsonPrimitive("user"),
                "password" to JsonPrimitive("pw"),
                "authToken" to JsonPrimitive("tok"),
            ),
        )

        val stripped = AvaBackupManager.withoutMassApiSecrets(input)

        assertTrue(stripped.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertFalse(stripped.containsKey("serverUrl"))
        assertFalse(stripped.containsKey("username"))
        assertFalse(stripped.containsKey("password"))
        assertFalse(stripped.containsKey("authToken"))
    }

    @Test
    fun withoutClonedMassApiCert_dropsAliasAndPassword() {
        val input = JsonObject(
            mapOf(
                "serverUrl" to JsonPrimitive("http://ma.local:8095"),
                "clientCertAlias" to JsonPrimitive("user:alias"),
                "clientCertPassword" to JsonPrimitive("pkcs12"),
            ),
        )

        val stripped = AvaBackupManager.withoutClonedMassApiCert(input)

        assertEquals("http://ma.local:8095", stripped.getValue("serverUrl").jsonPrimitive.content)
        assertFalse(stripped.containsKey("clientCertAlias"))
        assertFalse(stripped.containsKey("clientCertPassword"))
    }

    @Test
    fun stripHelpers_noopWhenKeysAbsent() {
        val input = JsonObject(mapOf("preferredPipeline" to JsonPrimitive("home")))
        assertSame(input, AvaBackupManager.withoutHaSecrets(input))
        assertSame(input, AvaBackupManager.withoutMassApiSecrets(input))
        assertSame(input, AvaBackupManager.withoutClonedMassApiCert(input))
    }
}
