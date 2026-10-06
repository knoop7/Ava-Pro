package com.example.ava.localllm.remote

import com.example.ava.settings.RemoteAiKind
import com.example.ava.settings.RemoteAiProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteAiFailoverTest {
    private fun profile(id: String, model: String) = RemoteAiProfile(
        id = id,
        kind = RemoteAiKind.OPENAI,
        label = id,
        baseUrl = "https://api.openai.com/v1",
        model = model,
        token = "t",
    )

    @Test fun twoFaultsAdvanceToTheNextFilledSlotThenGiveUp() {
        val chain = listOf(profile("1", "a"), profile("2", "b"))
        val fail = RemoteAiFailover(chain)
        assertEquals("a", fail.current()?.model)
        assertEquals(RemoteAiFailover.After.RetrySame, fail.noteFault())
        assertEquals("a", fail.current()?.model)
        assertEquals(RemoteAiFailover.After.Advance, fail.noteFault())
        assertEquals("b", fail.current()?.model)
        assertEquals(RemoteAiFailover.After.RetrySame, fail.noteFault())
        assertEquals(RemoteAiFailover.After.GiveUp, fail.noteFault())
        assertEquals("b", fail.current()?.model)
    }

    @Test fun emptyChainGivesUp() {
        val fail = RemoteAiFailover(emptyList())
        assertNull(fail.current())
        assertEquals(RemoteAiFailover.After.GiveUp, fail.noteFault())
    }

    @Test fun exhaustedIsNotRetryableAndNotResumable() {
        val dead = RemoteAiFailoverExhausted(IOException("remote AI stream error Internal server error"))
        assertFalse(RemoteAiClient.isRetryableTransport(dead))
        assertFalse(RemoteAiClient.canResumeCheckpoint(dead))
        assertTrue(dead.cause is IOException)
    }
}
