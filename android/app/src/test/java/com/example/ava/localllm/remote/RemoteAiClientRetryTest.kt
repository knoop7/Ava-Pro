package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RemoteAiClientRetryTest {
    @Test
    fun retriesDroppedSocketsAndGatewayCodes() {
        assertTrue(RemoteAiClient.isRetryableTransport(ConnectException("failed to connect")))
        assertTrue(RemoteAiClient.isRetryableTransport(UnknownHostException("api")))
        assertTrue(RemoteAiClient.isRetryableTransport(SocketException("Connection reset")))
        assertTrue(RemoteAiClient.isRetryableTransport(IOException("unexpected end of stream")))
        assertTrue(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 503")))
        assertTrue(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 429")))
        assertFalse(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 403")))
        assertFalse(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 403 {\"error\":\"expired\"}")))
        assertTrue(RemoteAiClient.isRetryableTransport(SocketTimeoutException("timeout")))
        assertTrue(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 500")))
        assertTrue(RemoteAiClient.isRetryableTransport(IOException("remote AI stream error overloaded")))
    }

    @Test
    fun doesNotRetryAuthOrBadRequest() {
        assertFalse(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 401")))
        assertFalse(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 400")))
        assertFalse(RemoteAiClient.isRetryableTransport(IOException("remote AI HTTP 403")))
        assertFalse(RemoteAiClient.isRetryableTransport(IllegalArgumentException("bad json")))
        assertEquals(0, RemoteAiClient.retryBudget(IOException("remote AI HTTP 403")))
        assertFalse(RemoteAiClient.canReplayTurn(IOException("remote AI HTTP 403"), beganWork = false))
        assertFalse(RemoteAiClient.canResumeCheckpoint(IOException("remote AI HTTP 403")))
    }

    @Test
    fun fourRetriesOnTransientTransport() {
        val busy = IOException("remote AI HTTP 503")
        assertEquals(4, RemoteAiClient.retryBudget(busy))
        assertEquals(2_000L, RemoteAiClient.retryDelayMs(busy, 1))
        assertEquals(4_000L, RemoteAiClient.retryDelayMs(busy, 2))
        assertEquals(4, RemoteAiClient.retryBudget(SocketException("Connection reset")))
        assertEquals(4, RemoteAiClient.retryBudget(SocketTimeoutException("timeout")))
        assertEquals(400L, RemoteAiClient.retryDelayMs(SocketException("Connection reset"), 1))
        assertEquals(403, RemoteAiClient.httpStatus(IOException("remote AI HTTP 403 expired")))
        assertEquals(0, RemoteAiClient.retryBudget(IOException("remote AI HTTP 401")))
        val streamFault = IOException("remote AI stream error Internal server error")
        assertTrue(RemoteAiClient.isProviderFault(streamFault))
        assertEquals(2_000L, RemoteAiClient.retryDelayMs(streamFault, 1))
        assertEquals(4_000L, RemoteAiClient.retryDelayMs(streamFault, 2))
        assertFalse(RemoteAiClient.isProviderFault(SocketException("Connection reset")))
    }

    @Test
    fun replaysAnEmptyTurnOnceOnTransportDrop() {
        val drop = SocketException("Connection reset")
        assertTrue(RemoteAiClient.canReplayTurn(drop, beganWork = false))
        assertFalse(RemoteAiClient.canReplayTurn(drop, beganWork = true))
        assertFalse(RemoteAiClient.canReplayTurn(IOException("remote AI HTTP 401"), beganWork = false))
        assertFalse(RemoteAiClient.canReplayTurn(IOException("Canceled"), beganWork = false))
        assertFalse(RemoteAiClient.canReplayTurn(kotlinx.coroutines.CancellationException("seat dropped"), beganWork = false))
        assertTrue(RemoteAiClient.canResumeCheckpoint(drop))
        assertTrue(RemoteAiClient.canResumeCheckpoint(kotlinx.coroutines.TimeoutCancellationException("slice")))
        assertFalse(RemoteAiClient.canResumeCheckpoint(IOException("remote AI HTTP 401")))
        assertFalse(RemoteAiClient.canResumeCheckpoint(kotlinx.coroutines.CancellationException("seat dropped")))
    }

    @Test
    fun visionRefusalAfterAttachedPictureIsFatal() {
        val refused = IOException(
            "remote AI HTTP 403 {\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Authorization failed\"}",
        )
        assertTrue(RemoteAiClient.isVisionRejected(refused, hadVision = true))
        assertFalse(RemoteAiClient.isVisionRejected(refused, hadVision = false))
        assertFalse(RemoteAiClient.isVisionRejected(IOException("remote AI HTTP 500"), hadVision = true))
        val wrapped = RemoteAiVisionRejected(refused)
        assertFalse(RemoteAiClient.isRetryableTransport(wrapped))
        assertFalse(RemoteAiClient.canResumeCheckpoint(wrapped))
        assertEquals(403, RemoteAiClient.httpStatus(wrapped))
        val body = JSONObject().put(
            "messages",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,xx")),
                    ),
                ),
            ),
        )
        assertTrue(RemoteAiClient.requestHasVision(body))
        assertFalse(
            RemoteAiClient.requestHasVision(
                JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello"))),
            ),
        )
    }

    @Test
    fun spokenLineKeepsHttpStatusAtTheEnd() {
        val refused = IOException("remote AI HTTP 403 {\"status\":403}")
        assertEquals(
            "当前模型不支持图像识别，接口已拒绝这次画面。 403",
            RemoteAiClient.withStatus("当前模型不支持图像识别，接口已拒绝这次画面。", refused),
        )
        assertEquals("远程模型没接通，这轮先停。", RemoteAiClient.withStatus("远程模型没接通，这轮先停。", null))
        assertEquals("接口拒绝了这次请求。 403", RemoteAiClient.withStatus("接口拒绝了这次请求。 403", refused))
    }

    @Test
    fun doesNotRetryCanceledCalls() {
        assertTrue(RemoteAiClient.isCanceledIo(IOException("Canceled")))
        assertFalse(RemoteAiClient.isCanceledIo(SocketException("Socket closed")))
        assertFalse(RemoteAiClient.isRetryableTransport(IOException("Canceled")))
        assertTrue(RemoteAiClient.isRetryableTransport(SocketException("Socket closed")))
    }
}
