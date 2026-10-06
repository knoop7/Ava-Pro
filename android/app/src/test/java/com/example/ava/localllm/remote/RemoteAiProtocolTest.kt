package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolDef
import com.example.ava.settings.RemoteAiKind
import com.example.ava.settings.RemoteAiProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RemoteAiProtocolTest {
    private fun messages(): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "read"))
        val call = RemoteAiClient.Call("call-1", "ava_web_read", JSONObject().put("mode", "viewport"))
        RemoteAiClient.appendAssistant(messages, RemoteAiClient.Turn("", listOf(call)))
        RemoteAiClient.appendToolResults(messages, listOf(call to "{\"ok\":true}"))
        return messages
    }
    private val system = RemoteAiPrompt.Text("host", "clock")

    @Test fun nativeOllamaUsesArgumentObjectsAndToolNames() {
        val profile = RemoteAiProfile("test", RemoteAiKind.OLLAMA, "test", baseUrl = "http://localhost:11434", model = "model")
        val body = RemoteAiClient.requestBody(profile, system, messages(), HaToolSet.empty())
        val rows = body.getJSONArray("messages")
        val function = rows.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
        assertTrue(function.get("arguments") is JSONObject)
        assertEquals("ava_web_read", rows.getJSONObject(3).getString("tool_name"))
        assertFalse(rows.getJSONObject(3).has("tool_call_id"))
        assertFalse(body.has("tools"))
    }

    @Test fun ollamaCompatibilityEndpointPreservesOpenAiWireFormat() {
        val profile = RemoteAiProfile("test", RemoteAiKind.OLLAMA, "test", baseUrl = "http://localhost:11434/v1", model = "model")
        val body = RemoteAiClient.requestBody(profile, system, messages(), HaToolSet.empty())
        val rows = body.getJSONArray("messages")
        assertTrue(rows.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").get("arguments") is String)
        assertEquals("call-1", rows.getJSONObject(3).getString("tool_call_id"))
    }
    @Test fun endpointDetectionUsesPathSegmentsAndHonorsExplicitNativeEndpoint() {
        val profile = RemoteAiProfile("test", RemoteAiKind.OLLAMA, "test", model = "model")
        assertTrue(RemoteAiClient.usesNativeOllama(profile.copy(baseUrl = "http://localhost/v10")))
        assertTrue(RemoteAiClient.usesNativeOllama(profile.copy(baseUrl = "http://localhost/v1/proxy/api/chat")))
        assertFalse(RemoteAiClient.usesNativeOllama(profile.copy(baseUrl = "http://localhost/proxy/chat/completions")))
    }

    @Test fun cameraSnapshotFollowsToolWithOpenAiUserVision() {
        val file = cameraJpeg()
        val body = RemoteAiClient.requestBody(
            RemoteAiProfile("test", RemoteAiKind.OPENAI, "test", baseUrl = "https://api.openai.com/v1", model = "gpt"),
            system,
            cameraMessages(file),
            HaToolSet.empty(),
        )
        val rows = body.getJSONArray("messages")
        val tool = rows.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertTrue(tool.get("content") is String)
        val vision = rows.getJSONObject(4)
        assertEquals("user", vision.getString("role"))
        val parts = vision.getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        file.delete()
    }

    @Test fun cameraSnapshotUsesOllamaImagesArrayOnNativeChat() {
        val file = cameraJpeg()
        val body = RemoteAiClient.requestBody(
            RemoteAiProfile("test", RemoteAiKind.OLLAMA, "test", baseUrl = "http://localhost:11434", model = "llava"),
            system,
            cameraMessages(file),
            HaToolSet.empty(),
        )
        val rows = body.getJSONArray("messages")
        val tool = rows.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertTrue(tool.get("content") is String)
        val vision = rows.getJSONObject(4)
        assertEquals("user", vision.getString("role"))
        assertTrue(vision.get("content") is String)
        val data = vision.getJSONArray("images").getString(0)
        assertTrue(data.isNotEmpty())
        assertFalse(data.startsWith("data:"))
        file.delete()
    }

    @Test fun cameraSnapshotKeepsJpegInsideClaudeToolResult() {
        val file = cameraJpeg()
        val body = RemoteAiClient.requestBody(
            RemoteAiProfile("test", RemoteAiKind.CLAUDE, "test", baseUrl = "https://api.anthropic.com", model = "claude"),
            system,
            cameraMessages(file),
            HaToolSet.empty(),
        )
        val rows = body.getJSONArray("messages")
        val last = rows.getJSONObject(rows.length() - 1)
        assertEquals("user", last.getString("role"))
        val tool = last.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", tool.getString("type"))
        assertEquals("image", tool.getJSONArray("content").getJSONObject(1).getString("type"))
        file.delete()
    }

    @Test fun cameraSnapshotFollowsResponsesOutputWithInputImage() {
        val file = cameraJpeg()
        val body = RemoteAiClient.requestBody(
            RemoteAiProfile("test", RemoteAiKind.OPENAI_RESPONSES, "test", baseUrl = "https://api.openai.com/v1", model = "gpt"),
            system,
            cameraMessages(file),
            HaToolSet.empty(),
        )
        val input = body.getJSONArray("input")
        val output = input.getJSONObject(input.length() - 2)
        assertEquals("function_call_output", output.getString("type"))
        assertTrue(output.get("output") is String)
        val vision = input.getJSONObject(input.length() - 1)
        assertEquals("user", vision.getString("role"))
        assertEquals("input_image", vision.getJSONArray("content").getJSONObject(1).getString("type"))
        file.delete()
    }

    @Test fun cameraSnapshotSkipsVisionWhenJpegFileIsGone() {
        val file = cameraJpeg()
        val messages = cameraMessages(file)
        file.delete()
        val body = RemoteAiClient.requestBody(
            RemoteAiProfile("test", RemoteAiKind.OPENAI, "test", baseUrl = "https://api.openai.com/v1", model = "gpt"),
            system,
            messages,
            HaToolSet.empty(),
        )
        val rows = body.getJSONArray("messages")
        assertEquals("tool", rows.getJSONObject(rows.length() - 1).getString("role"))
    }

    @Test fun describeAskNamesVisionGoalAndTools() {
        val file = cameraJpeg()
        val messages = cameraMessages(file)
        val tools = HaToolSet(listOf(ToolDef("ha_camera_snapshot", "snap", emptyList())))
        val line = RemoteAiClient.describeAsk(
            RemoteAiProfile("test", RemoteAiKind.OPENAI, "test", baseUrl = "https://api.openai.com/v1", model = "gpt-4o"),
            messages,
            tools,
            stream = true,
        )
        assertTrue(line.contains("openai"))
        assertTrue(line.contains("model=gpt-4o"))
        assertTrue(line.contains("host=api.openai.com"))
        assertTrue(line.contains("stream=1"))
        assertTrue(line.contains("vision=1"))
        assertTrue(line.contains("offered=ha_camera_snapshot"))
        assertTrue(line.contains("last_tools=ha_camera_snapshot"))
        assertTrue(line.contains("doing=look at camera snapshot"))
        assertTrue(line.contains("门口有人吗"))
        val stats = RemoteAiClient.wireStats(
            RemoteAiClient.requestBody(
                RemoteAiProfile("test", RemoteAiKind.OPENAI, "test", baseUrl = "https://api.openai.com/v1", model = "gpt"),
                system,
                messages,
                tools,
                stream = true,
            ),
        )
        assertTrue(stats.contains("wire="))
        assertTrue(stats.contains("vision_b64="))
        assertFalse(stats.contains("data:image"))
        file.delete()
    }

    private fun cameraJpeg(): File {
        val file = File.createTempFile("ava-cam", ".jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        return file
    }

    private fun cameraMessages(file: File): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "门口有人吗"))
        val call = RemoteAiClient.Call("c1", "ha_camera_snapshot", JSONObject().put("entity_id", "门口"))
        RemoteAiClient.appendAssistant(messages, RemoteAiClient.Turn("", listOf(call)))
        RemoteAiClient.appendToolResults(messages, listOf(call to """{"ok":true,"result":{"captured":true}}"""))
        messages.getJSONObject(messages.length() - 1)
            .getJSONArray("content")
            .getJSONObject(0)
            .put("image_path", file.absolutePath)
        return messages
    }

}
