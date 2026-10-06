package com.example.ava.localllm.remote

import com.example.ava.settings.RemoteAiKind
import com.example.ava.settings.RemoteAiProfile
import com.example.ava.settings.RemoteAiSettings
import com.example.ava.settings.failoverChain
import com.example.ava.settings.voiceProfile
import com.example.ava.settings.voiceWireKey
import com.example.ava.settings.wireKey
import com.example.ava.settings.wireLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class RemoteAiWireTest {
    private fun profile(id: String, model: String, token: String = "t") = RemoteAiProfile(
        id = id,
        kind = RemoteAiKind.OPENAI,
        label = id,
        baseUrl = "https://api.openai.com/v1",
        model = model,
        token = token,
    )

    @Test fun modelChangeIsANewWire() {
        assertNotEquals(profile("1", "gpt-4o").wireKey(), profile("1", "gpt-4o-mini").wireKey())
    }

    @Test fun slotIdIsNotTheWire() {
        assertEquals(profile("1", "gpt-4o").wireKey(), profile("2", "gpt-4o").wireKey())
    }

    @Test fun settingsUsesTheSelectedProfile() {
        val a = profile("1", "a")
        val b = profile("2", "b")
        val settings = RemoteAiSettings(selectedId = "1", profiles = listOf(a, b))
        assertEquals(a.wireKey(), settings.wireKey())
        assertEquals(b.wireKey(), settings.copy(selectedId = "2").wireKey())
    }

    @Test fun tokenChangeIsANewWire() {
        assertNotEquals(profile("1", "gpt-4o", "old").wireKey(), profile("1", "gpt-4o", "new").wireKey())
    }

    @Test fun labelOmitsTheToken() {
        val line = profile("1", "gpt-4o", "sk-secret").wireLabel()
        assertFalse(line.contains("sk-secret"))
        assertFalse(line.contains("\u0001"))
        assertTrue(line.contains("gpt-4o"))
    }

    @Test fun clearingMemoryDropsAnUnfinishedResume() {
        val memory = RemoteAiSessionMemory()
        memory.remember(
            "看看画面中有什么",
            JSONArray().put(JSONObject().put("role", "user").put("content", "看看画面中有什么")),
            false,
            true,
            true,
            awaitingContinuation = true,
        )
        memory.clear()
        assertNull(memory.resumeFor("看看画面中有什么", true, true))
    }

    @Test fun continuationKeepsSlotOneAsVoicePrimary() {
        val a = profile("1", "primary")
        val b = profile("2", "backup")
        val off = RemoteAiSettings(selectedId = "2", profiles = listOf(a, b), fallbackEnabled = false)
        assertEquals("backup", off.voiceProfile()?.model)
        val on = off.copy(fallbackEnabled = true)
        assertEquals("primary", on.voiceProfile()?.model)
        assertEquals(a.wireKey(), on.voiceWireKey())
        assertEquals(listOf("primary", "backup"), on.failoverChain().map { it.model })
    }

    @Test fun emptyLaterSlotsAreSkippedInTheChain() {
        val a = profile("1", "primary")
        val empty = profile("2", "").copy(model = "")
        val c = profile("3", "third")
        val settings = RemoteAiSettings(selectedId = "1", profiles = listOf(a, empty, c), fallbackEnabled = true)
        assertEquals(listOf("primary", "third"), settings.failoverChain().map { it.model })
    }
}
