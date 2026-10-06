package com.example.ava.homeassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal object HaServiceAcknowledgement {
    suspend fun await(reply: Deferred<JSONObject>, timeoutMs: Long = 15_000L): JSONObject? = try {
        withTimeoutOrNull(timeoutMs) { reply.await() }
    } catch (_: CancellationException) {
        // Reconnect cancels the pending reply, but does not cancel the caller's task.
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Exception) {
        null
    }
}
