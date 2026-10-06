package com.example.ava.mods

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModConversationEngineManifestTest {

    private val gson = Gson()

    @Test
    fun conversationEngineDefaultsFalse() {
        val manifest = gson.parseModManifest("""{"id":"a","name":"A"}""")
        assertFalse(manifest.conversationEngine)
        assertFalse(manifest.voicePipeline)
    }

    @Test
    fun conversationEngineParsesWithoutTouchingVoicePipeline() {
        val manifest = gson.parseModManifest(
            """{"id":"webrtc","name":"WebRTC","conversation_engine":true,"manager":"com.ex.Engine"}""",
        )
        assertTrue(manifest.conversationEngine)
        assertFalse(manifest.voicePipeline)
        assertEquals("com.ex.Engine", manifest.manager)
    }

    @Test
    fun bothFlagsCanCoexist() {
        val manifest = gson.parseModManifest(
            """{"id":"both","name":"Both","voice_pipeline":true,"conversation_engine":true,"manager":"com.ex.M"}""",
        )
        assertTrue(manifest.voicePipeline)
        assertTrue(manifest.conversationEngine)
    }
}
