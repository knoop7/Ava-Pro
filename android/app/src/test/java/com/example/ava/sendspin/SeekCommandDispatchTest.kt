package com.example.ava.sendspin

import com.example.ava.localllm.remote.AvaMusicTools
import org.junit.Assert.*
import org.junit.Test

class SeekCommandDispatchTest {
    @Test fun unavailableOrChangingStreamNeverDispatches() {
        for ((dead, pending) in listOf(true to false, false to true, true to true)) {
            var sent = false
            var notified = false
            val outcome = SeekCommandDispatch.run(dead, pending, { notified = true }, { sent = true; true })
            assertEquals(MediaCommandOutcome.IGNORED, outcome)
            assertFalse(sent)
            assertTrue(notified)
            assertTrue(outcome.handled) // UI must not fall back and bypass the refusal.
            val callback = AvaMusicTools.controlResult("seek", outcome)
            assertFalse(callback.ok)
            assertEquals("action_ignored", callback.error?.type)
        }
    }

    @Test fun sentAndRejectedCommandsHaveDistinctCallbacks() {
        val sent = SeekCommandDispatch.run(false, false, {}, { true })
        val rejected = SeekCommandDispatch.run(false, false, {}, { false })
        assertEquals("accepted", AvaMusicTools.controlResult("seek", sent).status)
        assertEquals("command_rejected", AvaMusicTools.controlResult("seek", rejected).error?.type)
        assertFalse(rejected.handled)
    }
}
