package com.example.ava.localllm.remote

import android.util.Log
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.LocalToolCall
import com.example.ava.settings.RemoteAiKind
import com.example.ava.settings.RemoteAiProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call as HttpCall
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay

/**
 * One chat-with-tools POST, four wires.
 * Claude = `/v1/messages`, OpenAI = `/v1/chat/completions`,
 * OpenAI Responses = `/v1/responses`, Ollama = `/api/chat`.
 *
 * Every wire has a one-shot and a streaming form. The streaming form hands
 * text deltas to a sink as they arrive and still returns the same [Turn], so
 * the turn loop does not care which one ran.
 */
object RemoteAiClient {

    data class Usage(
        val input: Int,
        val output: Int,
        /** Prompt tokens served from the provider cache, when it reports them. */
        val cachedInput: Int = 0,
    )

    data class Turn(
        val text: String,
        val calls: List<Call>,
        /** DeepSeek / Qwen / Ollama chain-of-thought; replayed to the provider, never spoken. */
        val reasoning: String? = null,
        /** Raw Responses-API `reasoning` items; replayed verbatim so the model keeps its plan. */
        val reasoningItems: List<JSONObject> = emptyList(),
        val usage: Usage? = null,
        val elapsedMs: Long = 0L,
    )

    data class Call(
        val id: String,
        val name: String,
        val arguments: JSONObject,
    )

    suspend fun listModels(profile: RemoteAiProfile): List<String> = withContext(Dispatchers.IO) {
        val raw = get(profile, resolveModelsUrl(profile))
        parseModels(profile.kind, raw)
    }

    /**
     * One provider round. With [onText] the request streams and each text
     * delta reaches the sink on an OkHttp thread — the sink must only count or
     * enqueue. Nothing is spoken from deltas, so a stream that drops mid-way is
     * retried like a one-shot call; only the returned [Turn] is ever used.
     */
    suspend fun chat(
        profile: RemoteAiProfile,
        system: RemoteAiPrompt.Text,
        messages: JSONArray,
        tools: HaToolSet,
        onText: ((String) -> Unit)? = null,
        think: Boolean = true,
    ): Turn = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val emit = onText
        val stream = emit != null
        val body = requestBody(profile, system, messages, tools, stream, think)
        val hadVision = requestHasVision(body)
        Log.i(TAG, "${describeAsk(profile, messages, tools, stream)} ${wireStats(body)}")
        try {
            val turn = if (emit == null) {
                parse(profile, post(profile, body))
            } else {
                val sink: (String) -> Unit = { delta -> if (delta.isNotEmpty()) emit(delta) }
                postStream(profile, body, canRetry = { true }) { source ->
                    when {
                        profile.kind == RemoteAiKind.CLAUDE -> streamClaude(source, sink)
                        profile.kind == RemoteAiKind.OPENAI_RESPONSES -> streamResponses(source, sink)
                        profile.kind == RemoteAiKind.OLLAMA && usesNativeOllama(profile) -> streamOllama(source, sink)
                        else -> streamOpenAi(source, sink)
                    }
                }
            }
            turn.copy(elapsedMs = (System.nanoTime() - started) / 1_000_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isVisionRejected(e, hadVision)) throw e
            Log.w(TAG, "gateway refused attached picture model=${profile.model.trim()} ${e.message}")
            throw if (e is RemoteAiVisionRejected) e else RemoteAiVisionRejected(e)
        }
    }

    fun appendAssistant(messages: JSONArray, turn: Turn) {
        val content = JSONArray()
        for (item in turn.reasoningItems) {
            content.put(JSONObject().put("type", "reasoning_item").put("item", item))
        }
        if (turn.reasoning != null) {
            content.put(JSONObject().put("type", "reasoning").put("text", turn.reasoning))
        }
        if (turn.text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", turn.text))
        }
        for (c in turn.calls) {
            content.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("input", c.arguments),
            )
        }
        messages.put(JSONObject().put("role", "assistant").put("content", content))
    }

    fun appendToolResults(messages: JSONArray, results: List<Pair<Call, String>>) {
        val content = JSONArray()
        for ((call, text) in results) {
            content.put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", call.id)
                    .put("content", text),
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", content))
    }

    fun toLocalCalls(turn: Turn): List<LocalToolCall> =
        turn.calls.map { LocalToolCall(it.name, it.arguments) }

    internal fun requestBody(
        profile: RemoteAiProfile,
        system: RemoteAiPrompt.Text,
        messages: JSONArray,
        tools: HaToolSet,
        stream: Boolean = false,
        think: Boolean = true,
    ): JSONObject {
        val model = profile.model.trim()
        val nativeOllama = usesNativeOllama(profile)
        val body = when (profile.kind) {
            RemoteAiKind.CLAUDE -> JSONObject().put("model", model).put("max_tokens", MAX_OUTPUT_TOKENS)
                .put("system", claudeSystem(system)).put("messages", claudeMessages(messages))
            RemoteAiKind.OPENAI -> JSONObject().put("model", model)
                .put(outputCapField(profile), MAX_OUTPUT_TOKENS)
                .put("messages", openAiMessages(system.joined(), messages, replayThink = !tools.isEmpty))
            RemoteAiKind.OPENAI_RESPONSES -> JSONObject().put("model", model).put("max_output_tokens", MAX_OUTPUT_TOKENS)
                .put("instructions", system.joined()).put("input", responsesInput(messages))
            RemoteAiKind.OLLAMA -> JSONObject().put("model", model)
                .put(
                    "messages",
                    if (nativeOllama) ollamaMessages(system.joined(), messages, replayThink = !tools.isEmpty)
                    else openAiMessages(system.joined(), messages, replayThink = !tools.isEmpty),
                )
        }
        if (!tools.isEmpty) {
            when (profile.kind) {
                RemoteAiKind.CLAUDE -> body.put("tools", claudeTools(tools))
                RemoteAiKind.OPENAI_RESPONSES -> body.put("tools", responsesTools(tools))
                    .put("tool_choice", "auto").put("parallel_tool_calls", true)
                RemoteAiKind.OPENAI -> body.put("tools", openAiTools(tools))
                    .put("tool_choice", "auto").put("parallel_tool_calls", true)
                RemoteAiKind.OLLAMA -> body.put("tools", openAiTools(tools))
            }
        }
        when (profile.kind) {
            RemoteAiKind.OPENAI -> if (stream) {
                body.put("stream", true)
                // Most OpenAI-compatible hosts reject stream_options and return 400.
                if (isOfficialOpenAi(profile)) {
                    body.put("stream_options", JSONObject().put("include_usage", true))
                }
            }
            RemoteAiKind.CLAUDE, RemoteAiKind.OPENAI_RESPONSES -> if (stream) body.put("stream", true)
            RemoteAiKind.OLLAMA -> body.put("stream", stream)
        }
        if (!think) suppressThinking(profile, body, nativeOllama)
        return body
    }

    /**
     * On leaves the body alone. Off only adds a field the host already
     * documents: native Ollama `think`, DeepSeek `thinking.type`, and
     * `enable_thinking` for other OpenAI-compatible servers. Official OpenAI
     * and Claude reject unknown keys, so those wires stay unchanged.
     */
    private fun suppressThinking(profile: RemoteAiProfile, body: JSONObject, nativeOllama: Boolean) {
        when (profile.kind) {
            RemoteAiKind.OLLAMA -> if (nativeOllama) {
                body.put("think", false)
            } else {
                body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
            RemoteAiKind.OPENAI -> if (!isOfficialOpenAi(profile)) {
                if (isDeepSeek(profile)) {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                } else {
                    body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }
            }
            RemoteAiKind.CLAUDE, RemoteAiKind.OPENAI_RESPONSES -> Unit
        }
    }

    private fun isDeepSeek(profile: RemoteAiProfile): Boolean {
        val host = runCatching { java.net.URI(profile.baseUrl.trim()).host }.getOrNull().orEmpty().lowercase()
        return host == "api.deepseek.com" || host.endsWith(".deepseek.com")
    }

    /**
     * OpenAI's own servers rejected `max_tokens` on reasoning models and moved
     * to `max_completion_tokens`; most other OpenAI-compatible servers only
     * implement the older field (Alibaba silently drops the new one).
     */
    internal fun outputCapField(profile: RemoteAiProfile): String =
        if (isOfficialOpenAi(profile)) "max_completion_tokens" else "max_tokens"

    internal fun isOfficialOpenAi(profile: RemoteAiProfile): Boolean {
        val host = runCatching { java.net.URI(profile.baseUrl.trim()).host }.getOrNull().orEmpty().lowercase()
        return host == "api.openai.com" || host.endsWith(".openai.com") || host.endsWith(".openai.azure.com")
    }

    internal fun usesNativeOllama(profile: RemoteAiProfile): Boolean {
        if (profile.kind != RemoteAiKind.OLLAMA) return false
        val path = runCatching { java.net.URI(profile.baseUrl.trim()).path }.getOrNull().orEmpty().trimEnd('/')
        if (path.endsWith("/api/chat")) return true
        return !path.endsWith("/chat/completions") && !Regex("/v1(?:/|$)").containsMatchIn(path)
    }

    /** Replay native argument objects and associate each result with its function name. */
    internal fun ollamaMessages(system: String, messages: JSONArray, replayThink: Boolean = false): JSONArray {
        val rows = openAiMessages(system, messages, replayThink)
        val names = HashMap<String, String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val calls = row.optJSONArray("tool_calls")
            if (calls != null) for (j in 0 until calls.length()) {
                val call = calls.getJSONObject(j)
                val fn = call.getJSONObject("function")
                names[call.getString("id")] = fn.getString("name")
                fn.put("arguments", JSONObject(fn.getString("arguments")))
                call.remove("id")
            }
            if (row.has("reasoning_content")) {
                row.put("thinking", row.remove("reasoning_content"))
            }
            if (row.optString("role") == "tool") {
                row.put("tool_name", names[row.optString("tool_call_id")].orEmpty())
                row.remove("tool_call_id")
            }
            if (row.optString("role") == "user") ollamaVision(row)
        }
        return rows
    }

    /** Native Ollama sees JPEGs on `images`, not OpenAI `image_url` parts. */
    private fun ollamaVision(row: JSONObject) {
        val parts = row.opt("content") as? JSONArray ?: return
        val text = StringBuilder()
        val images = JSONArray()
        for (j in 0 until parts.length()) {
            val part = parts.optJSONObject(j) ?: continue
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text"))
                "image_url" -> {
                    val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        .ifEmpty { part.optString("image_url") }
                    val data = url.substringAfter("base64,", missingDelimiterValue = "")
                    if (data.isNotEmpty()) images.put(data)
                }
            }
        }
        row.put("content", text.toString().ifBlank { "The picture is attached." })
        if (images.length() > 0) row.put("images", images)
    }

    /**
     * The prompt as two blocks with a cache breakpoint between them.
     *
     * Anthropic hashes the request prefix in tools → system → messages order,
     * so this single marker covers the whole tool schema plus everything in
     * the prompt above the clock. That is the bulk of a turn and it is
     * byte-identical from one turn to the next; a cache read bills at a tenth
     * of the input rate, while without the marker every turn pays full price
     * for all of it. The clock goes in the second block, unmarked, so a new
     * minute costs a few tokens instead of the entire prefix.
     *
     * OpenAI needs no marker — it caches long prefixes on its own — and Ollama
     * is local, so both take the joined string.
     */
    private fun claudeSystem(system: RemoteAiPrompt.Text): JSONArray {
        val out = JSONArray()
        if (system.stable.isNotBlank()) {
            out.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", system.stable)
                    .put("cache_control", JSONObject().put("type", "ephemeral")),
            )
        }
        if (system.live.isNotBlank()) {
            out.put(JSONObject().put("type", "text").put("text", system.live))
        }
        return out
    }

    private fun claudeMessages(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") == "system") continue
            val content = m.opt("content")
            if (content !is JSONArray) {
                out.put(m)
                continue
            }
            // Reasoning blocks belong to other wires; Claude rejects unknown types.
            val kept = JSONArray()
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") in HOST_ONLY_BLOCKS) continue
                kept.put(if (block.optString("type") == "tool_result") claudeToolResult(block) else block)
            }
            if (kept.length() == 0) continue
            out.put(JSONObject().put("role", m.optString("role")).put("content", kept))
        }
        return out
    }

    private fun openAiMessages(system: String, messages: JSONArray, replayThink: Boolean = false): JSONArray {
        val out = JSONArray()
        if (system.isNotBlank()) {
            out.put(JSONObject().put("role", "system").put("content", system))
        }
        val validIds = HashSet<String>()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            val content = m.opt("content")
            if (content is String) {
                out.put(JSONObject().put("role", role).put("content", content))
                validIds.clear()
                continue
            }
            if (content !is JSONArray) continue
            if (role == "assistant") {
                val text = StringBuilder()
                val reasoning = StringBuilder()
                var sawReasoning = false
                val toolCalls = JSONArray()
                validIds.clear()
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "reasoning" -> {
                            sawReasoning = true
                            reasoning.append(block.optString("text"))
                        }
                        "tool_use" -> {
                            val id = block.optString("id")
                            validIds += id
                            val input = block.optJSONObject("input") ?: JSONObject()
                            toolCalls.put(
                                JSONObject()
                                    .put("id", id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", block.optString("name"))
                                            .put("arguments", input.toString()),
                                    ),
                            )
                        }
                    }
                }
                val row = JSONObject().put("role", "assistant").put("content", text.toString())
                // DeepSeek: with `tools` on the request, reasoning_content must come back
                // even as "". Qwen3.5 leaks think into content if the field is dropped.
                if (sawReasoning || (replayThink && toolCalls.length() > 0)) {
                    row.put("reasoning_content", reasoning.toString())
                }
                if (toolCalls.length() > 0) row.put("tool_calls", toolCalls)
                out.put(row)
            } else {
                val images = ArrayList<String>()
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    if (block.optString("type") != "tool_result") continue
                    val id = block.optString("tool_use_id")
                    if (id.isNotEmpty() && id !in validIds) continue
                    visionDataUrl(block)?.let { images += it }
                    out.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", toolResultText(block)),
                    )
                }
                if (images.isNotEmpty()) {
                    out.put(openAiVisionUser(images))
                    Log.i(TAG, "camera frame(s) attached as user vision parts count=${images.size}")
                }
            }
        }
        return out
    }

    /** Responses API items: messages, replayed reasoning, function calls and their outputs. */
    internal fun responsesInput(messages: JSONArray): JSONArray {
        val out = JSONArray()
        val validIds = HashSet<String>()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            val content = m.opt("content")
            if (content is String) {
                out.put(JSONObject().put("role", role).put("content", content))
                validIds.clear()
                continue
            }
            if (content !is JSONArray) continue
            if (role == "assistant") {
                validIds.clear()
                val text = StringBuilder()
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    when (block.optString("type")) {
                        "reasoning_item" -> block.optJSONObject("item")?.let { out.put(JSONObject(it.toString())) }
                        "text" -> text.append(block.optString("text"))
                    }
                }
                if (text.isNotEmpty()) {
                    out.put(JSONObject().put("role", "assistant").put("content", text.toString()))
                }
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    if (block.optString("type") != "tool_use") continue
                    val id = block.optString("id")
                    validIds += id
                    out.put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", id)
                            .put("name", block.optString("name"))
                            .put("arguments", (block.optJSONObject("input") ?: JSONObject()).toString()),
                    )
                }
            } else {
                val images = ArrayList<String>()
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    if (block.optString("type") != "tool_result") continue
                    val id = block.optString("tool_use_id")
                    if (id.isNotEmpty() && id !in validIds) continue
                    visionDataUrl(block)?.let { images += it }
                    out.put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", id)
                            .put("output", toolResultText(block)),
                    )
                }
                if (images.isNotEmpty()) {
                    out.put(responsesVisionUser(images))
                    Log.i(TAG, "camera frame(s) attached as responses vision parts count=${images.size}")
                }
            }
        }
        return out
    }

    /**
     * Claude takes the full schema. Every other wire gets the flat form: the
     * per-action `oneOf` branches are rejected or ignored by several
     * OpenAI-compatible servers (Gemini, DashScope), and the host validates
     * those branches anyway.
     */
    private fun schemaFor(kind: RemoteAiKind, tools: HaToolSet): JSONArray {
        val src = JSONArray(tools.toJsonSchema())
        if (kind == RemoteAiKind.CLAUDE) return src
        for (i in 0 until src.length()) {
            src.getJSONObject(i).optJSONObject("parameters")?.remove("oneOf")
        }
        return src
    }

    private fun openAiTools(tools: HaToolSet): JSONArray {
        val src = schemaFor(RemoteAiKind.OPENAI, tools)
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val def = src.getJSONObject(i)
            out.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", def.optString("name"))
                            .put("description", def.optString("description"))
                            .put("parameters", def.optJSONObject("parameters") ?: JSONObject()),
                    ),
            )
        }
        return out
    }

    private fun responsesTools(tools: HaToolSet): JSONArray {
        val src = schemaFor(RemoteAiKind.OPENAI_RESPONSES, tools)
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val def = src.getJSONObject(i)
            out.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", def.optString("name"))
                    .put("description", def.optString("description"))
                    .put("parameters", def.optJSONObject("parameters") ?: JSONObject())
                    // Optional fields make strict mode impossible; say so instead of letting the server guess.
                    .put("strict", false),
            )
        }
        return out
    }

    private fun claudeTools(tools: HaToolSet): JSONArray {
        val src = schemaFor(RemoteAiKind.CLAUDE, tools)
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val def = src.getJSONObject(i)
            out.put(
                JSONObject()
                    .put("name", def.optString("name"))
                    .put("description", def.optString("description"))
                    .put("input_schema", def.optJSONObject("parameters") ?: JSONObject()),
            )
        }
        return out
    }

    private fun parse(profile: RemoteAiProfile, raw: String): Turn {
        val root = JSONObject(raw)
        return when (profile.kind) {
            RemoteAiKind.CLAUDE -> parseClaude(root)
            RemoteAiKind.OPENAI_RESPONSES -> parseResponses(root)
            RemoteAiKind.OPENAI, RemoteAiKind.OLLAMA -> parseOpenAi(root)
        }
    }

    private fun parseClaude(root: JSONObject): Turn {
        val calls = ArrayList<Call>()
        val text = StringBuilder()
        val content = root.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> calls += Call(
                    id = block.optString("id").ifBlank { "call_$i" },
                    name = block.optString("name"),
                    arguments = block.optJSONObject("input") ?: JSONObject(),
                )
            }
        }
        return Turn(
            RemoteAiThinkFilter.spoken(text.toString()),
            calls.filter { it.name.isNotBlank() },
            usage = claudeUsage(root.optJSONObject("usage")),
        )
    }

    private fun parseOpenAi(root: JSONObject): Turn {
        val message = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: root.optJSONObject("message")
            ?: JSONObject()
        val calls = ArrayList<Call>()
        val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            calls += Call(
                id = tc.optString("id").ifBlank { "call_$i" },
                name = fn.optString("name"),
                arguments = parseArguments(fn.opt("arguments")),
            )
        }
        val (spoken, reasoning) = voice(message.optString("content"), siblingThink(message))
        return Turn(
            spoken,
            calls.filter { it.name.isNotBlank() },
            reasoning = reasoning,
            usage = openAiUsage(root),
        )
    }

    private fun parseResponses(root: JSONObject): Turn {
        val calls = ArrayList<Call>()
        val text = StringBuilder()
        val reasoningItems = ArrayList<JSONObject>()
        val output = root.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val parts = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") text.append(part.optString("text"))
                    }
                }
                "function_call" -> calls += Call(
                    id = item.optString("call_id").ifBlank { item.optString("id") }.ifBlank { "call_$i" },
                    name = item.optString("name"),
                    arguments = parseArguments(item.opt("arguments")),
                )
                "reasoning" -> reasoningItems += JSONObject(item.toString())
            }
        }
        return Turn(
            RemoteAiThinkFilter.spoken(text.toString()),
            calls.filter { it.name.isNotBlank() },
            reasoningItems = reasoningItems,
            usage = openAiUsage(root),
        )
    }

    private fun parseArguments(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        null -> JSONObject()
        else -> {
            val s = raw.toString().trim()
            if (s.isEmpty()) JSONObject()
            else runCatching { JSONObject(s) }.getOrElse { throw IllegalArgumentException("invalid remote tool arguments") }
        }
    }

    /** Empty string still counts: DeepSeek requires the key back when tools are on. */
    private fun siblingThink(obj: JSONObject?): String? {
        if (obj == null) return null
        for (key in THINK_KEYS) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            if (value is String) return value
        }
        return null
    }

    /**
     * Prefer the host's think field. If the host dumped CoT into `content`
     * (no reasoning parser), lift the fence body so the next tool round can
     * pass it back instead of leaking it again.
     */
    private fun voice(content: String, sibling: String?): Pair<String, String?> {
        val split = RemoteAiThinkFilter.split(content)
        val reasoning = when {
            !sibling.isNullOrEmpty() -> sibling
            split.thought.isNotEmpty() -> split.thought
            else -> sibling
        }
        return split.spoken to reasoning
    }

    private fun claudeUsage(usage: JSONObject?): Usage? {
        usage ?: return null
        return Usage(
            input = usage.optInt("input_tokens") + usage.optInt("cache_read_input_tokens") + usage.optInt("cache_creation_input_tokens"),
            output = usage.optInt("output_tokens"),
            cachedInput = usage.optInt("cache_read_input_tokens"),
        )
    }

    private fun openAiUsage(root: JSONObject): Usage? {
        val usage = root.optJSONObject("usage")
        if (usage != null) {
            val input = usage.optInt("prompt_tokens", usage.optInt("input_tokens"))
            val output = usage.optInt("completion_tokens", usage.optInt("output_tokens"))
            val cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")
                ?: usage.optJSONObject("input_tokens_details")?.optInt("cached_tokens")
                ?: usage.optInt("prompt_cache_hit_tokens")
            return Usage(input, output, cached)
        }
        if (root.has("prompt_eval_count") || root.has("eval_count")) {
            return Usage(root.optInt("prompt_eval_count"), root.optInt("eval_count"))
        }
        return null
    }

    // ---- streaming parsers -------------------------------------------------

    /** Accumulates one streamed tool call; arguments arrive as JSON fragments. */
    private class PendingCall(var id: String, var name: String) {
        val arguments = StringBuilder()
        var complete: JSONObject? = null
        fun toCall(index: Int): Call? {
            if (name.isBlank()) return null
            val args = complete ?: parseArguments(arguments.toString())
            return Call(id.ifBlank { "call_$index" }, name, args)
        }
    }

    private fun sseData(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("data:")) return null
        val data = t.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return null
        return data
    }

    private inline fun forEachLine(source: BufferedSource, block: (String) -> Unit) {
        var total = 0L
        while (true) {
            val line = source.readUtf8Line() ?: break
            total += line.length
            if (total > STREAM_CAP) throw IOException("remote AI stream exceeds cap")
            block(line)
        }
    }

    internal fun streamClaude(source: BufferedSource, sink: (String) -> Unit): Turn {
        val text = StringBuilder()
        val blocks = HashMap<Int, PendingCall>()
        var input = 0
        var cached = 0
        var output = 0
        forEachLine(source) { line ->
            val data = sseData(line) ?: return@forEachLine
            val event = runCatching { JSONObject(data) }.getOrNull() ?: return@forEachLine
            when (event.optString("type")) {
                "message_start" -> event.optJSONObject("message")?.optJSONObject("usage")?.let {
                    cached = it.optInt("cache_read_input_tokens")
                    input = it.optInt("input_tokens") + cached + it.optInt("cache_creation_input_tokens")
                }
                "content_block_start" -> {
                    val block = event.optJSONObject("content_block") ?: return@forEachLine
                    if (block.optString("type") == "tool_use") {
                        blocks[event.optInt("index")] = PendingCall(block.optString("id"), block.optString("name"))
                    }
                }
                "content_block_delta" -> {
                    val delta = event.optJSONObject("delta") ?: return@forEachLine
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val piece = delta.optString("text")
                            text.append(piece)
                            sink(piece)
                        }
                        "input_json_delta" -> blocks[event.optInt("index")]?.arguments?.append(delta.optString("partial_json"))
                    }
                }
                "message_delta" -> event.optJSONObject("usage")?.let { output = it.optInt("output_tokens", output) }
                "error" -> throw IOException("remote AI stream error " + (event.optJSONObject("error")?.optString("message") ?: data.take(160)))
            }
        }
        val calls = blocks.entries.sortedBy { it.key }.mapNotNull { it.value.toCall(it.key) }
        return Turn(
            RemoteAiThinkFilter.spoken(text.toString()), calls,
            usage = if (input > 0 || output > 0) Usage(input, output, cached) else null,
        )
    }

    internal fun streamOpenAi(source: BufferedSource, sink: (String) -> Unit): Turn {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = java.util.TreeMap<Int, PendingCall>()
        var usage: Usage? = null
        var sawSse = false
        var sawSibling = false
        var oneshot: JSONObject? = null
        forEachLine(source) { line ->
            val data = sseData(line)
            val raw = when {
                data != null -> {
                    sawSse = true
                    data
                }
                !sawSse && line.trim().startsWith("{") -> line.trim()
                else -> return@forEachLine
            }
            val chunk = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEachLine
            if (!sawSse && chunk.has("choices")) oneshot = chunk
            chunk.optJSONObject("error")?.let { throw IOException("remote AI stream error " + it.optString("message").take(160)) }
            openAiUsage(chunk)?.let { usage = it }
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@forEachLine
            val delta = choice.optJSONObject("delta")
            val message = choice.optJSONObject("message")
            val piece = delta?.optString("content")?.ifEmpty { delta.optString("text") }.orEmpty()
                .ifEmpty { if (!sawSse) message?.optString("content").orEmpty() else "" }
            if (piece.isNotEmpty()) {
                text.append(piece)
                sink(piece)
            }
            val think = siblingThink(delta) ?: siblingThink(message)
            if (think != null) {
                sawSibling = true
                reasoning.append(think)
            }
            val toolCalls = delta?.optJSONArray("tool_calls") ?: message?.optJSONArray("tool_calls") ?: return@forEachLine
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val index = tc.optInt("index", i)
                val pending = calls.getOrPut(index) { PendingCall("", "") }
                tc.optString("id").takeIf { it.isNotBlank() }?.let { pending.id = it }
                val fn = tc.optJSONObject("function") ?: continue
                fn.optString("name").takeIf { it.isNotBlank() }?.let { pending.name = it }
                pending.arguments.append(fn.optString("arguments"))
            }
        }
        if (!sawSse && text.isEmpty() && calls.isEmpty() && oneshot != null) {
            val turn = parseOpenAi(oneshot)
            if (turn.text.isNotEmpty()) sink(turn.text)
            return turn
        }
        val (spoken, think) = voice(text.toString(), if (sawSibling) reasoning.toString() else null)
        return Turn(
            spoken,
            calls.entries.mapNotNull { it.value.toCall(it.key) },
            reasoning = think,
            usage = usage,
        )
    }

    internal fun streamResponses(source: BufferedSource, sink: (String) -> Unit): Turn {
        val text = StringBuilder()
        val calls = LinkedHashMap<String, PendingCall>()
        val reasoningItems = ArrayList<JSONObject>()
        var usage: Usage? = null
        forEachLine(source) { line ->
            val data = sseData(line) ?: return@forEachLine
            val event = runCatching { JSONObject(data) }.getOrNull() ?: return@forEachLine
            when (val type = event.optString("type")) {
                "response.output_text.delta" -> {
                    val piece = event.optString("delta")
                    text.append(piece)
                    sink(piece)
                }
                "response.output_item.added" -> {
                    val item = event.optJSONObject("item") ?: return@forEachLine
                    if (item.optString("type") == "function_call") {
                        calls[item.optString("id").ifBlank { item.optString("call_id") }] =
                            PendingCall(item.optString("call_id").ifBlank { item.optString("id") }, item.optString("name"))
                    }
                }
                "response.function_call_arguments.delta" ->
                    calls[event.optString("item_id")]?.arguments?.append(event.optString("delta"))
                "response.function_call_arguments.done" ->
                    calls[event.optString("item_id")]?.complete = parseArguments(event.opt("arguments"))
                "response.output_item.done" -> {
                    val item = event.optJSONObject("item") ?: return@forEachLine
                    when (item.optString("type")) {
                        "reasoning" -> reasoningItems += JSONObject(item.toString())
                        "function_call" -> {
                            val key = item.optString("id").ifBlank { item.optString("call_id") }
                            val pending = calls.getOrPut(key) { PendingCall(item.optString("call_id"), item.optString("name")) }
                            if (pending.name.isBlank()) pending.name = item.optString("name")
                            if (pending.id.isBlank()) pending.id = item.optString("call_id")
                            if (item.has("arguments")) pending.complete = parseArguments(item.opt("arguments"))
                        }
                    }
                }
                "response.completed", "response.incomplete" ->
                    event.optJSONObject("response")?.let { r -> openAiUsage(r)?.let { usage = it } }
                "response.failed", "error" -> throw IOException(
                    "remote AI stream error " + (
                        event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                            ?: event.optString("message").ifBlank { type }
                        ).take(160),
                )
            }
        }
        return Turn(
            RemoteAiThinkFilter.spoken(text.toString()),
            calls.values.mapIndexedNotNull { i, p -> p.toCall(i) },
            reasoningItems = reasoningItems,
            usage = usage,
        )
    }

    internal fun streamOllama(source: BufferedSource, sink: (String) -> Unit): Turn {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = ArrayList<Call>()
        var usage: Usage? = null
        var sawThinking = false
        forEachLine(source) { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEachLine
            val chunk = runCatching { JSONObject(t) }.getOrNull() ?: return@forEachLine
            if (chunk.has("error")) throw IOException("remote AI stream error " + chunk.optString("error").take(160))
            val message = chunk.optJSONObject("message")
            if (message != null) {
                val piece = message.optString("content")
                if (piece.isNotEmpty()) {
                    text.append(piece)
                    sink(piece)
                }
                if (message.has("thinking") && !message.isNull("thinking")) {
                    sawThinking = true
                    thinking.append(message.optString("thinking"))
                }
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null) for (i in 0 until toolCalls.length()) {
                    val fn = toolCalls.optJSONObject(i)?.optJSONObject("function") ?: continue
                    calls += Call("call_${calls.size}", fn.optString("name"), parseArguments(fn.opt("arguments")))
                }
            }
            if (chunk.optBoolean("done")) openAiUsage(chunk)?.let { usage = it }
        }
        val (spoken, think) = voice(text.toString(), if (sawThinking) thinking.toString() else null)
        return Turn(
            spoken,
            calls.filter { it.name.isNotBlank() },
            reasoning = think,
            usage = usage,
        )
    }

    private fun parseModels(kind: RemoteAiKind, raw: String): List<String> {
        val root = JSONObject(raw)
        val names = LinkedHashSet<String>()
        when (kind) {
            RemoteAiKind.OLLAMA -> {
                val models = root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val row = models.optJSONObject(i) ?: continue
                    val name = row.optString("name").ifBlank { row.optString("model") }
                    if (name.isNotBlank()) names += name
                }
            }
            RemoteAiKind.CLAUDE, RemoteAiKind.OPENAI, RemoteAiKind.OPENAI_RESPONSES -> {
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) names += id
                }
            }
        }
        return names.toList()
    }

    internal fun evictConnections() {
        http.connectionPool.evictAll()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Silence gap between bytes. A streamed reply that keeps producing
        // tokens may run far longer than this; only a stalled socket trips it.
        .readTimeout(90, TimeUnit.SECONDS)
        // Whole call, including a slow local model's long tool-call output.
        .callTimeout(300, TimeUnit.SECONDS)
        // A provider redirect must not carry credentials to another origin.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private suspend fun get(profile: RemoteAiProfile, url: String): String =
        send(profile, Request.Builder().url(url), canRetry = { true }) { it.readUtf8() }

    private suspend fun post(profile: RemoteAiProfile, body: JSONObject): String =
        send(profile, postBuilder(profile, body), canRetry = { true }) { it.readUtf8() }

    private suspend fun postStream(
        profile: RemoteAiProfile,
        body: JSONObject,
        canRetry: () -> Boolean,
        read: (BufferedSource) -> Turn,
    ): Turn = send(
        profile,
        postBuilder(profile, body).header("Accept", "text/event-stream, application/x-ndjson, application/json"),
        canRetry,
        preflightCap = false,
        read,
    )

    private fun postBuilder(profile: RemoteAiProfile, body: JSONObject): Request.Builder =
        Request.Builder().url(resolveUrl(profile))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    private suspend fun <T> send(
        profile: RemoteAiProfile,
        builder: Request.Builder,
        canRetry: () -> Boolean,
        preflightCap: Boolean = true,
        read: (BufferedSource) -> T,
    ): T {
        if (builder.build().header("Accept") == null) builder.header("Accept", "application/json")
        when (profile.kind) {
            RemoteAiKind.CLAUDE -> {
                builder.header("x-api-key", profile.token.trim())
                builder.header("anthropic-version", "2023-06-01")
            }
            RemoteAiKind.OPENAI, RemoteAiKind.OPENAI_RESPONSES ->
                if (profile.token.isNotBlank()) builder.header("Authorization", "Bearer ${profile.token.trim()}")
            RemoteAiKind.OLLAMA -> Unit
        }
        val request = builder.build()
        var attempt = 0
        while (true) {
            try {
                return execute(request, preflightCap, read)
            } catch (e: IOException) {
                evictConnections()
                if (!isRetryableTransport(e) || !canRetry()) throw e
                attempt++
                val budget = retryBudget(e)
                if (attempt > budget) throw e
                val wait = retryDelayMs(e, attempt)
                // OkHttp will not retry a POST after the body left the socket.
                // A dead HTTP/2 or local Ollama socket then stays failed until
                // the process dies. 403 is not a drop — do not replay it.
                Log.w(
                    TAG,
                    "AI API dropped (${e.message}); reconnecting $attempt/$budget in ${wait}ms" +
                        if (isProviderFault(e)) " provider_fault" else "",
                )
                delay(wait)
            }
        }
    }

    private suspend fun <T> execute(request: Request, preflightCap: Boolean, read: (BufferedSource) -> T): T =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: HttpCall, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: HttpCall, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) {
                                val hint = runCatching {
                                    val source = it.body?.source() ?: return@runCatching ""
                                    source.request(201)
                                    source.buffer.clone().readUtf8().replace('\n', ' ').trim().take(160)
                                }.getOrDefault("")
                                throw IOException(
                                    if (hint.isEmpty()) "remote AI HTTP ${it.code}"
                                    else "remote AI HTTP ${it.code} $hint",
                                )
                            }
                            val source = it.body?.source() ?: throw IOException("empty remote AI response")
                            // Streaming parsers read line-by-line. request(2 MiB) here would
                            // wait for EOF and the sink would never see a live delta.
                            if (preflightCap) capCheck(source)
                            read(source)
                        }
                        if (cont.isActive) cont.resume(value)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }

    /** One-shot bodies only. Streamed reads enforce [STREAM_CAP] in [forEachLine]. */
    private fun capCheck(source: BufferedSource) {
        source.request(STREAM_CAP + 1)
        if (source.buffer.size > STREAM_CAP) throw IOException("remote AI response exceeds 2 MiB")
    }

    private fun resolveUrl(profile: RemoteAiProfile): String {
        var base = profile.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) {
            if (profile.kind == RemoteAiKind.OPENAI || profile.kind == RemoteAiKind.OLLAMA) return base
            base = base.removeSuffix("/chat/completions")
        } else if (base.endsWith("/responses")) {
            if (profile.kind == RemoteAiKind.OPENAI_RESPONSES) return base
            base = base.removeSuffix("/responses")
        } else if (base.endsWith("/messages")) {
            base = base.removeSuffix("/messages")
        } else if (base.endsWith("/api/chat")) {
            return "$base"
        }
        return when (profile.kind) {
            RemoteAiKind.CLAUDE ->
                if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            RemoteAiKind.OPENAI ->
                if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
            RemoteAiKind.OPENAI_RESPONSES ->
                if (base.endsWith("/v1")) "$base/responses" else "$base/v1/responses"
            RemoteAiKind.OLLAMA ->
                if (!usesNativeOllama(profile)) "$base/chat/completions" else "$base/api/chat"
        }
    }

    private fun resolveModelsUrl(profile: RemoteAiProfile): String {
        var base = profile.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) {
            base = base.removeSuffix("/chat/completions")
        } else if (base.endsWith("/responses")) {
            base = base.removeSuffix("/responses")
        } else if (base.endsWith("/messages")) {
            base = base.removeSuffix("/messages")
        } else if (base.endsWith("/api/chat")) {
            base = base.removeSuffix("/api/chat")
        } else if (base.endsWith("/api/tags")) {
            return base
        }
        return when (profile.kind) {
            RemoteAiKind.CLAUDE, RemoteAiKind.OPENAI, RemoteAiKind.OPENAI_RESPONSES ->
                if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
            RemoteAiKind.OLLAMA ->
                if (!usesNativeOllama(profile)) "$base/models" else "$base/api/tags"
        }
    }

    /**
     * Tool role stays a string. OpenAI-compatible hosts (and native Ollama)
     * reject `image_url` on `role=tool`; the JPEG goes on a following user
     * vision message instead. Claude still embeds the frame on the tool_result.
     */
    internal fun toolResultText(block: JSONObject): String =
        block.optString("content").ifBlank {
            if (block.optString("image_path").isNotBlank()) "The picture is attached." else ""
        }

    private fun visionDataUrl(block: JSONObject): String? {
        val path = block.optString("image_path").trim()
        if (path.isEmpty()) return null
        val image = imageDataUrl(path)
        if (image == null) Log.w(TAG, "camera frame missing, model will not see it path=$path")
        return image
    }

    private fun visionCaption(count: Int): String =
        if (count == 1) "The picture is attached. Describe what you see."
        else "The pictures are attached. Describe what you see."

    private fun openAiVisionUser(images: List<String>): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", visionCaption(images.size)))
        for (image in images) {
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
        }
        return JSONObject().put("role", "user").put("content", content)
    }

    private fun responsesVisionUser(images: List<String>): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", visionCaption(images.size)))
        for (image in images) {
            content.put(JSONObject().put("type", "input_image").put("image_url", image))
        }
        return JSONObject().put("role", "user").put("content", content)
    }

    private fun claudeToolResult(block: JSONObject): JSONObject {
        val image = visionDataUrl(block) ?: return JSONObject(block.toString()).apply { remove("image_path") }
        val data = image.substringAfter("base64,", missingDelimiterValue = "")
        if (data.isEmpty()) return JSONObject(block.toString()).apply { remove("image_path") }
        return JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", block.optString("tool_use_id"))
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", block.optString("content").ifBlank { "The picture is attached." }))
                    .put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", data),
                            ),
                    ),
            )
    }

    internal fun imageDataUrl(path: String): String? {
        val file = File(path.trim())
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * One log line for an outbound ask: who, stream, tools, last user text,
     * last tool names, attached JPEG sizes, and what this round is doing.
     * No tokens, no base64.
     */
    internal fun describeAsk(
        profile: RemoteAiProfile,
        messages: JSONArray,
        tools: HaToolSet,
        stream: Boolean,
    ): String {
        var lastUser = ""
        var lastResult = ""
        val lastTools = ArrayList<String>()
        val visionBytes = ArrayList<Long>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            when (val content = m.opt("content")) {
                is String -> if (role == "user" && content.isNotBlank()) lastUser = content
                is JSONArray -> {
                    val names = ArrayList<String>()
                    for (j in 0 until content.length()) {
                        val block = content.optJSONObject(j) ?: continue
                        when (block.optString("type")) {
                            "text" -> {
                                val text = block.optString("text")
                                if (role == "user" && text.isNotBlank()) lastUser = text
                            }
                            "tool_use" -> {
                                val name = block.optString("name")
                                if (name.isNotBlank()) names += name
                            }
                            "tool_result" -> {
                                val path = block.optString("image_path").trim()
                                if (path.isNotEmpty()) {
                                    visionBytes += File(path).takeIf { it.isFile }?.length() ?: 0L
                                }
                                val result = block.optString("content").replace('\n', ' ').trim()
                                if (result.isNotBlank()) lastResult = result.take(100)
                            }
                        }
                    }
                    if (names.isNotEmpty()) {
                        lastTools.clear()
                        lastTools.addAll(names)
                    }
                }
            }
        }
        val offered = tools.tools.map { it.name }
        val camera = lastTools.any { it.contains("camera", ignoreCase = true) || it.contains("snapshot", ignoreCase = true) }
        val doing = when {
            visionBytes.isNotEmpty() && camera -> "look at camera snapshot"
            visionBytes.isNotEmpty() -> "look at attached picture"
            lastTools.isNotEmpty() -> "act after ${lastTools.joinToString(",")}"
            lastUser.isNotBlank() -> "answer user"
            else -> "continue turn"
        }
        val visionPart = if (visionBytes.isEmpty()) "vision=0"
        else "vision=${visionBytes.size} jpeg=${visionBytes.joinToString("+") { sizeLabel(it) }}"
        val user = lastUser.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(80)
        val host = runCatching { java.net.URI(profile.baseUrl).host }.getOrNull()
            ?.ifBlank { null }
            ?: profile.baseUrl.trim().ifBlank { "-" }
        return buildString {
            append("ask ${profile.kind.name.lowercase()} model=${profile.model} host=$host")
            append(" stream=${if (stream) 1 else 0} msgs=${messages.length()} $visionPart")
            append(" tools=${offered.size}")
            if (offered.isNotEmpty()) append(" offered=${offered.joinToString(",")}")
            if (lastTools.isNotEmpty()) append(" last_tools=${lastTools.joinToString(",")}")
            if (lastResult.isNotEmpty()) append(" last_result='$lastResult'")
            append(" doing=$doing")
            if (user.isNotEmpty()) append(" user='$user'")
        }
    }

    /** Wire size of the POST after vision is inlined. Base64 is counted, not printed. */
    internal fun wireStats(body: JSONObject): String {
        val raw = body.toString()
        var visionChars = 0
        DATA_URL.findAll(raw).forEach { visionChars += it.value.length }
        OLLAMA_IMAGES.findAll(raw).forEach { visionChars += it.groupValues[1].length }
        return "wire=${raw.length}b text=${raw.length - visionChars}b vision_b64=$visionChars"
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> "0B"
        bytes < 1024L -> "${bytes}B"
        else -> "${(bytes + 512) / 1024}kB"
    }

    private const val TAG = "RemoteAiClient"
    /** Thinking models spend tokens before `</think>`; 1024 truncates the think block so the answer never arrives. */
    private const val MAX_OUTPUT_TOKENS = 8192
    private val THINK_KEYS = arrayOf("reasoning_content", "thinking_content", "thinking", "reasoning")
    private const val STREAM_CAP = 2L * 1024 * 1024
    /** Extra POSTs after the first drop. First token already spoken → [canRetry] stops this. */
    private const val TRANSPORT_RETRIES = 4
    private val HOST_ONLY_BLOCKS = setOf("reasoning", "reasoning_item")
    private val DATA_URL = Regex("data:image/[^\"\\s]+")
    private val OLLAMA_IMAGES = Regex("\"images\"\\s*:\\s*\\[([^\\]]*)\\]")
    private val VISION_REFUSAL_CODES = setOf(400, 403, 404, 413, 415, 422)

    /**
     * A fresh socket can heal drops, timeouts, and gateway blips.
     * 400 / 401 / 403 stay fatal — those are refusals, not healed by replay.
     * User cancel stays fatal. After the first spoken delta, [canRetry] is false.
     */
    internal fun isRetryableTransport(error: Throwable): Boolean {
        if (error is RemoteAiFailoverExhausted || error is RemoteAiVisionRejected) return false
        if (isCanceledIo(error)) return false
        when (error) {
            is SocketTimeoutException,
            is UnknownHostException,
            is ConnectException,
            is SocketException -> return true
        }
        if (error !is IOException) return false
        val code = httpStatus(error)
        if (code != null) {
            return code == 408 || code == 429 || code in 500..504
        }
        return true
    }

    /**
     * One extra full turn when the first request never came back.
     * After a token or a tool has run, a blank replay would repeat writes — refuse.
     */
    internal fun canReplayTurn(error: Throwable, beganWork: Boolean): Boolean {
        if (beganWork) return false
        if (error is kotlinx.coroutines.CancellationException) return false
        return isRetryableTransport(error)
    }

    /**
     * Same task, from the checkpoint already in session memory.
     * Writes stay in the ledger; the model is asked again, nothing is redone.
     */
    internal fun canResumeCheckpoint(error: Throwable): Boolean {
        if (error is RemoteAiVisionRejected) return false
        if (error is kotlinx.coroutines.CancellationException &&
            error !is kotlinx.coroutines.TimeoutCancellationException
        ) return false
        return isRetryableTransport(error) || error is kotlinx.coroutines.TimeoutCancellationException
    }

    /** Extra POST attempts after the first failure. 4 sits in the 3–5 window. */
    internal fun retryBudget(error: Throwable): Int {
        if (!isRetryableTransport(error)) return 0
        return TRANSPORT_RETRIES
    }

    /**
     * SSE `error` events have no HTTP status. Treat provider 5xx / overload
     * the same as HTTP 500 so we do not hammer the same vision POST every 400ms.
     */
    internal fun isProviderFault(error: Throwable): Boolean {
        val code = httpStatus(error)
        if (code != null) return code == 429 || code in 500..504
        val msg = error.message.orEmpty().lowercase()
        return msg.contains("internal server error") ||
            msg.contains("overloaded") ||
            msg.contains("capacity")
    }

    /** Claw uses 2s × 2^(n-1) for transient agent errors; socket drops stay short. */
    internal fun retryDelayMs(error: Throwable, attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)
        val slow = httpStatus(error) != null || isProviderFault(error)
        return if (slow) minOf(15_000L, 2_000L * (1L shl (n - 1))) else 400L
    }

    /**
     * The POST already carried a camera frame. A client refusal means this
     * model or gateway will not take pictures — do not replay the same JPEG.
     */
    internal fun isVisionRejected(error: Throwable, hadVision: Boolean): Boolean {
        if (!hadVision) return false
        if (error is RemoteAiVisionRejected) return true
        val code = httpStatus(error)
        if (code != null) return code in VISION_REFUSAL_CODES
        val msg = error.message.orEmpty().lowercase()
        return msg.contains("image") && (
            msg.contains("support") ||
                msg.contains("vision") ||
                msg.contains("multimodal") ||
                msg.contains("invalid") ||
                msg.contains("not allowed")
            )
    }

    /** True when the outbound JSON already inlined a JPEG. */
    internal fun requestHasVision(body: JSONObject): Boolean {
        val raw = body.toString()
        return raw.contains("image_url") ||
            raw.contains("input_image") ||
            raw.contains("\"media_type\":\"image/jpeg\"") ||
            raw.contains("data:image") ||
            OLLAMA_IMAGES.containsMatchIn(raw)
    }

    /** Spoken line, then the HTTP status when the host has one. */
    internal fun withStatus(line: String, error: Throwable?): String {
        val text = line.trim()
        if (text.isEmpty()) return ""
        val code = error?.let { httpStatus(it) } ?: return text
        val suffix = code.toString()
        return if (text.endsWith(suffix)) text else "$text $suffix"
    }

    internal fun httpStatus(error: Throwable): Int? {
        var current: Throwable? = error
        while (current != null) {
            val msg = current.message.orEmpty()
            if (msg.startsWith("remote AI HTTP ")) {
                return msg.removePrefix("remote AI HTTP ").substringBefore(' ').toIntOrNull()
            }
            current = current.cause
        }
        return null
    }

    /** OkHttp abort after [okhttp3.Call.cancel]. Real socket drops stay retryable. */
    internal fun isCanceledIo(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message.equals("Canceled", ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }
}
