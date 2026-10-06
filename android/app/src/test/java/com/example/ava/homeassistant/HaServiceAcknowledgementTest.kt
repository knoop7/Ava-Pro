package com.example.ava.homeassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HaServiceAcknowledgementTest {
    @Test fun lostConnectionReplyIsUnknownWhileCallerRemainsActive() = runBlocking {
        val reply = CompletableDeferred<JSONObject>()
        reply.cancel()
        assertNull(HaServiceAcknowledgement.await(reply))
    }

    @Test fun userCancellationStillCancelsTheTask() = runBlocking {
        var returnedNormally = false
        val task = launch {
            HaServiceAcknowledgement.await(CompletableDeferred())
            returnedNormally = true
        }
        yield()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertFalse(returnedNormally)
    }

    @Test fun actualReplyAndTimeoutRemainDistinct() = runBlocking {
        val reply = CompletableDeferred(JSONObject().put("success", true))
        assertTrue(HaServiceAcknowledgement.await(reply)!!.getBoolean("success"))
        assertNull(HaServiceAcknowledgement.await(CompletableDeferred(), timeoutMs = 1))
    }
}
