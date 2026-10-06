package com.example.ava.homeassistant

import android.net.Uri
import android.util.Log
import com.example.ava.homeassistant.entity.HaEntityLiveState
import com.example.ava.homeassistant.entity.HaEntitySummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home Assistant WebSocket API client.
 * Auth flow: connect → receive auth_required → send auth token → receive auth_ok.
 * Then JSON RPC with incrementing message IDs.
 */
class HaWsClient(private val scope: CoroutineScope) {

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val haVersion: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pipelines = MutableStateFlow<List<HaPipeline>>(emptyList())
    val pipelines: StateFlow<List<HaPipeline>> = _pipelines.asStateFlow()

    private val _preferredPipelineId = MutableStateFlow<String?>(null)
    val preferredPipelineId: StateFlow<String?> = _preferredPipelineId.asStateFlow()

    private val _entities = MutableStateFlow<List<HaEntitySummary>>(emptyList())
    val entities: StateFlow<List<HaEntitySummary>> = _entities.asStateFlow()

    private val _catalog = MutableStateFlow(HaPipelineCatalog())
    val catalog: StateFlow<HaPipelineCatalog> = _catalog.asStateFlow()

    private val messageId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val eventListeners = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val generation = AtomicInteger(0)

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var accessToken: String = ""
    private var reconnectJob: Job? = null
    private var userDisconnected = false
    private val reconnectAttempts = AtomicInteger(0)

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun connect(url: String, token: String) {
        userDisconnected = false
        serverUrl = url
        accessToken = token
        reconnectAttempts.set(0)
        reconnectJob?.cancel()
        doConnect()
    }

    fun disconnect() {
        userDisconnected = true
        reconnectAttempts.set(0)
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        _pipelines.value = emptyList()
        _preferredPipelineId.value = null
        _entities.value = emptyList()
        _catalog.value = HaPipelineCatalog()
        pending.values.forEach { it.cancel() }
        pending.clear()
        eventListeners.clear()
    }

    /**
     * Home Assistant `camera/stream`. Starts the HLS worker and returns the
     * playlist path (`/api/hls/.../master_playlist.m3u8`) or null.
     */
    suspend fun requestCameraStreamUrl(entityId: String): String? {
        if (_connectionState.value !is ConnectionState.Connected) return null
        val result = sendCommand(
            "camera/stream",
            mapOf("entity_id" to entityId, "format" to "hls"),
        ) ?: return null
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "camera/stream $entityId failed: ${result.optJSONObject("error")}")
            return null
        }
        val url = result.optJSONObject("result")?.optString("url").orEmpty()
        return url.takeIf { it.isNotBlank() }
    }

    /**
     * Home Assistant `conversation/process` — inject text into the Assist
     * pipeline as a user turn. No satellite STT, no 15s VAD.
     */
    suspend fun processConversation(text: String, conversationId: String? = null): String? {
        if (text.isBlank()) return null
        val params = mutableMapOf<String, Any?>("text" to text)
        if (!conversationId.isNullOrBlank()) params["conversation_id"] = conversationId
        val result = sendCommand("conversation/process", params) ?: return null
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "conversation/process failed: ${result.optJSONObject("error")}")
            return null
        }
        val speech = result.optJSONObject("result")
            ?.optJSONObject("response")
            ?.optJSONObject("speech")
            ?.optJSONObject("plain")
            ?.optString("speech")
            .orEmpty()
        return speech.takeIf { it.isNotBlank() }
    }

    /**
     * Same [conversation/process] as [processConversation], but empty speech
     * still counts as landed. Null / timeout / error does not.
     */
    suspend fun processConversationAck(text: String, conversationId: String? = null): Boolean {
        if (text.isBlank()) return false
        val params = mutableMapOf<String, Any?>("text" to text)
        if (!conversationId.isNullOrBlank()) params["conversation_id"] = conversationId
        val result = sendCommand("conversation/process", params) ?: return false
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "conversation/process ack failed: ${result.optJSONObject("error")}")
            return false
        }
        return true
    }

    suspend fun sendCommand(type: String, params: Map<String, Any?> = emptyMap()): JSONObject? {
        val ws = webSocket ?: return null
        val id = messageId.incrementAndGet()
        val msg = JSONObject().apply {
            put("id", id)
            put("type", type)
            params.forEach { (k, v) ->
                if (v == null) put(k, JSONObject.NULL) else put(k, v)
            }
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        ws.send(msg.toString())
        return try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            Log.w(TAG, "Command $type timed out: ${e.message}")
            null
        }
    }

    /**
     * Home Assistant `call_service`. Builds nested `target` / `service_data`
     * objects — [sendCommand] cannot put maps as JSON objects.
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String? = null,
        data: Map<String, Any?> = emptyMap(),
    ): Boolean {
        return callServiceReporting(domain, service, entityId, data) != false
    }

    /** false = rejected/not sent; null = sent without a reliable acknowledgement. */
    suspend fun callServiceReporting(
        domain: String,
        service: String,
        entityId: String? = null,
        data: Map<String, Any?> = emptyMap(),
    ): Boolean? {
        if (_connectionState.value !is ConnectionState.Connected) return false
        val ws = webSocket ?: return false
        val id = messageId.incrementAndGet()
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            if (!entityId.isNullOrBlank()) {
                put("target", JSONObject().put("entity_id", entityId))
            }
            if (data.isNotEmpty()) {
                val serviceData = JSONObject()
                com.example.ava.localllm.HaServicePayload.withoutTarget(data).forEach { (key, value) ->
                    putJsonValue(serviceData, key, value)
                }
                if (serviceData.length() > 0) put("service_data", serviceData)
            }
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        if (!ws.send(msg.toString())) { pending.remove(id, deferred); return false }
        val result = try {
            HaServiceAcknowledgement.await(deferred)
        } finally {
            // IDs restart after reconnect; do not remove a newer generation's pending call.
            pending.remove(id, deferred)
        }
        if (result == null || !result.has("success")) return null
        val ok = result.optBoolean("success", false)
        if (!ok) {
            Log.w(TAG, "call_service $domain.$service failed: ${result.optJSONObject("error")}")
        }
        return ok
    }

    suspend fun fetchPipelines() {
        val result = sendCommand("assist_pipeline/pipeline/list") ?: return
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "pipeline/list failed: ${result.optJSONObject("error")}")
            return
        }
        val data = result.optJSONObject("result") ?: return
        val arr = data.optJSONArray("pipelines") ?: return
        val preferred = data.optString("preferred_pipeline", "")

        val list = mutableListOf<HaPipeline>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                HaPipeline(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    language = obj.optString("language"),
                    sttEngine = obj.optString("stt_engine").takeIf { it.isNotBlank() },
                    sttLanguage = obj.optString("stt_language").takeIf { it.isNotBlank() },
                    ttsEngine = obj.optString("tts_engine").takeIf { it.isNotBlank() },
                    ttsLanguage = obj.optString("tts_language").takeIf { it.isNotBlank() },
                    ttsVoice = obj.optString("tts_voice").takeIf { it.isNotBlank() },
                    conversationEngine = obj.optString("conversation_engine").takeIf { it.isNotBlank() },
                    conversationLanguage = obj.optString("conversation_language").takeIf { it.isNotBlank() },
                    wakeWordEntity = obj.optString("wake_word_entity").takeIf { it.isNotBlank() },
                    wakeWordId = obj.optString("wake_word_id").takeIf { it.isNotBlank() },
                )
            )
        }
        _pipelines.value = list
        _preferredPipelineId.value = preferred.ifBlank { null }
    }

    suspend fun setPreferredPipeline(pipelineId: String): Boolean {
        val result = sendCommand(
            "assist_pipeline/pipeline/set_preferred",
            mapOf("pipeline_id" to pipelineId),
        ) ?: return false
        if (result.optBoolean("success", false)) {
            _preferredPipelineId.value = pipelineId
            return true
        }
        Log.w(TAG, "set_preferred failed: ${result.optJSONObject("error")}")
        return false
    }

    suspend fun updatePipeline(
        pipelineId: String,
        name: String? = null,
        language: String? = null,
        conversationEngine: String? = null,
        conversationLanguage: String? = null,
        sttEngine: String? = null,
        sttLanguage: String? = null,
        ttsEngine: String? = null,
        ttsLanguage: String? = null,
        ttsVoice: String? = null,
        wakeWordEntity: String? = null,
        wakeWordId: String? = null,
    ): Boolean {
        val current = _pipelines.value.find { it.id == pipelineId }
        if (current == null) {
            Log.w(TAG, "pipeline/update: pipeline $pipelineId not in cache")
            return false
        }

        // HA's assist_pipeline/pipeline/update schema marks every field Required.
        // Partial payloads are rejected. Merge onto the current pipeline, same as
        // core's async_update_pipeline().
        val nextLanguage = language ?: current.language
        val nextSttEngine = sttEngine ?: current.sttEngine
        val nextTtsEngine = ttsEngine ?: current.ttsEngine
        val nextTtsLanguage = ttsLanguage ?: current.ttsLanguage
        val engineChanged = ttsEngine != null && ttsEngine != current.ttsEngine
        val languageChanged = (language != null && language != current.language) ||
            (ttsLanguage != null && ttsLanguage != current.ttsLanguage)
        val nextTtsVoice = when {
            ttsVoice != null -> ttsVoice
            engineChanged || languageChanged -> null
            else -> current.ttsVoice
        }

        val params = mapOf(
            "pipeline_id" to pipelineId,
            "name" to (name ?: current.name),
            "language" to nextLanguage,
            "conversation_engine" to (
                conversationEngine ?: current.conversationEngine ?: "homeassistant"
            ),
            "conversation_language" to (
                conversationLanguage ?: current.conversationLanguage ?: nextLanguage
            ),
            "stt_engine" to nextSttEngine,
            "stt_language" to if (nextSttEngine == null) null else (sttLanguage ?: current.sttLanguage),
            "tts_engine" to nextTtsEngine,
            "tts_language" to if (nextTtsEngine == null) null else (nextTtsLanguage),
            "tts_voice" to nextTtsVoice,
            "wake_word_entity" to (wakeWordEntity ?: current.wakeWordEntity),
            "wake_word_id" to (wakeWordId ?: current.wakeWordId),
        )

        val result = sendCommand("assist_pipeline/pipeline/update", params) ?: return false
        if (result.optBoolean("success", false)) {
            fetchPipelines()
            return true
        }
        Log.w(TAG, "pipeline/update failed: ${result.optJSONObject("error")}")
        return false
    }

    suspend fun fetchEntities() {
        val result = sendCommand("get_states") ?: return
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "get_states failed: ${result.optJSONObject("error")}")
            return
        }
        val arr = result.optJSONArray("result") ?: return
        val list = ArrayList<HaEntitySummary>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("entity_id")
            if (id.isBlank()) continue
            val attrs = obj.optJSONObject("attributes")
            val name = attrs?.optString("friendly_name").orEmpty().ifBlank { id.substringAfterLast('.') }
            list.add(
                HaEntitySummary(
                    entityId = id,
                    name = name,
                    domain = id.substringBefore('.', ""),
                    state = obj.optString("state"),
                ),
            )
        }
        list.sortBy { it.entityId }
        _entities.value = list
    }

    /**
     * One entity via REST `GET /api/states/<id>`. The host token stays here.
     * This is not `get_states` and does not pull the house.
     */
    suspend fun fetchEntityLiveState(entityId: String): HaEntityLiveState? {
        val id = entityId.trim()
        if (id.isEmpty()) return null
        val path = "/api/states/${Uri.encode(id)}"
        val (code, json) = restGetJson(path)
        if (code == 404 || json == null) return null
        return HaEntityLiveState.fromRest(json)
    }

    /**
     * Targeted `get_states` read for timer tiles. Push channels are only as
     * fresh as their last delivery; this is the current truth on demand.
     */
    suspend fun fetchTimerStates(entityIds: Set<String>): List<HaTimerSnapshot> {
        if (entityIds.isEmpty()) return emptyList()
        val result = sendCommand("get_states") ?: return emptyList()
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "get_states (timers) failed: ${result.optJSONObject("error")}")
            return emptyList()
        }
        val arr = result.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<HaTimerSnapshot>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("entity_id")
            if (id !in entityIds) continue
            val attrs = obj.optJSONObject("attributes")
            list.add(
                HaTimerSnapshot(
                    entityId = id,
                    state = obj.optString("state"),
                    remaining = attrs?.optString("remaining").orEmpty(),
                    finishesAt = attrs?.optString("finishes_at").orEmpty(),
                ),
            )
        }
        return list
    }

    suspend fun fetchCatalog() {
        val languages = parseLanguageList(sendCommand("assist_pipeline/language/list"))
        val conversation = parseConversationAgents(sendCommand("conversation/agent/list"))
        val stt = parseEngineProviders(sendCommand("stt/engine/list"), idKey = "engine_id")
        val tts = parseEngineProviders(sendCommand("tts/engine/list"), idKey = "engine_id")
        _catalog.value = HaPipelineCatalog(
            languages = languages,
            conversationAgents = conversation,
            sttEngines = stt,
            ttsEngines = tts,
        )
    }

    /**
     * `homeassistant/expose_entity/list` — entity ids exposed to Assist
     * (`conversation` assistant). Null when the command is unavailable / fails.
     */
    suspend fun fetchAssistExposedEntityIds(): Set<String>? {
        if (_connectionState.value !is ConnectionState.Connected) return null
        val result = sendCommand("homeassistant/expose_entity/list") ?: return null
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "expose_entity/list failed: ${result.optJSONObject("error")}")
            return null
        }
        val exposed = result.optJSONObject("result")?.optJSONObject("exposed_entities") ?: return emptySet()
        val out = HashSet<String>()
        val keys = exposed.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val assistants = exposed.optJSONObject(id) ?: continue
            if (assistants.optBoolean("conversation", false)) out += id
        }
        return out
    }

    /**
     * `assist_pipeline/pipeline_debug/list` + `get` — events of the newest run on
     * [pipelineId]. Both commands need an admin token; on `unauthorized` this client
     * remembers the denial until the next auth so no run pays the round trip twice.
     * Null when unavailable, empty array when the pipeline has no recorded runs.
     */
    suspend fun fetchLatestPipelineRunEvents(pipelineId: String): JSONArray? {
        if (_connectionState.value !is ConnectionState.Connected) return null
        if (pipelineDebugDenied) return null
        val list = sendCommand(
            "assist_pipeline/pipeline_debug/list",
            mapOf("pipeline_id" to pipelineId),
        ) ?: return null
        if (!list.optBoolean("success", false)) {
            noteDebugFailure("pipeline_debug/list", list)
            return null
        }
        val runs = list.optJSONObject("result")?.optJSONArray("pipeline_runs") ?: return JSONArray()
        var newestId = ""
        var newestTs = ""
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val ts = run.optString("timestamp")
            // ISO-8601 with a fixed offset sorts lexicographically; ties keep insertion order.
            if (newestId.isEmpty() || ts >= newestTs) {
                newestId = run.optString("pipeline_run_id")
                newestTs = ts
            }
        }
        if (newestId.isEmpty()) return JSONArray()
        val get = sendCommand(
            "assist_pipeline/pipeline_debug/get",
            mapOf("pipeline_id" to pipelineId, "pipeline_run_id" to newestId),
        ) ?: return null
        if (!get.optBoolean("success", false)) {
            noteDebugFailure("pipeline_debug/get", get)
            return null
        }
        return get.optJSONObject("result")?.optJSONArray("events")
    }

    @Volatile
    private var pipelineDebugDenied = false

    private fun noteDebugFailure(command: String, result: JSONObject) {
        val error = result.optJSONObject("error")
        if (error?.optString("code") == "unauthorized") {
            pipelineDebugDenied = true
            Log.w(TAG, "$command needs an admin token; structured Assist verdicts disabled")
        } else {
            Log.w(TAG, "$command failed: $error")
        }
    }

    /**
     * `config/entity_registry/get_entries` — extended registry entries (name override,
     * `aliases`, `area_id`, `device_id`) keyed by entity id. Missing entries are dropped.
     */
    suspend fun fetchEntityRegistryEntries(entityIds: Collection<String>): Map<String, JSONObject>? {
        if (_connectionState.value !is ConnectionState.Connected) return null
        if (entityIds.isEmpty()) return emptyMap()
        val ids = JSONArray()
        entityIds.forEach { ids.put(it) }
        val result = sendCommand("config/entity_registry/get_entries", mapOf("entity_ids" to ids)) ?: return null
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "entity_registry/get_entries failed: ${result.optJSONObject("error")}")
            return null
        }
        val entries = result.optJSONObject("result") ?: return emptyMap()
        val out = HashMap<String, JSONObject>()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            entries.optJSONObject(id)?.let { out[id] = it }
        }
        return out
    }

    /** `config/area_registry/list` — area id → spoken names (name plus aliases). */
    suspend fun fetchAreaNames(): Map<String, List<String>>? {
        val arr = listResult("config/area_registry/list") ?: return null
        val out = HashMap<String, List<String>>()
        for (i in 0 until arr.length()) {
            val area = arr.optJSONObject(i) ?: continue
            val id = area.optString("area_id")
            if (id.isEmpty()) continue
            val names = ArrayList<String>()
            area.optString("name").takeIf { it.isNotBlank() }?.let(names::add)
            val aliases = area.optJSONArray("aliases")
            if (aliases != null) {
                for (j in 0 until aliases.length()) {
                    aliases.optString(j).takeIf { it.isNotBlank() && it != "null" }?.let(names::add)
                }
            }
            out[id] = names
        }
        return out
    }

    /** `config/device_registry/list` — device id → area id, for entities without their own area. */
    suspend fun fetchDeviceAreas(): Map<String, String>? {
        val arr = listResult("config/device_registry/list") ?: return null
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val device = arr.optJSONObject(i) ?: continue
            val id = device.optString("id")
            val area = device.optString("area_id")
            if (id.isNotEmpty() && area.isNotEmpty() && area != "null") out[id] = area
        }
        return out
    }

    /**
     * `config/entity_registry/list`. [match] sees unique_id and platform.
     * Returns the first entity id that matches, or null when the registry
     * cannot be read.
     */
    suspend fun findRegistryEntity(match: (uniqueId: String, platform: String) -> Boolean): String? {
        val arr = listResult("config/entity_registry/list") ?: return null
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            if (!match(entry.optString("unique_id"), entry.optString("platform"))) continue
            val id = entry.optString("entity_id")
            if (id.isNotBlank()) return id
        }
        return null
    }

    private suspend fun listResult(type: String): JSONArray? {
        if (_connectionState.value !is ConnectionState.Connected) return null
        val result = sendCommand(type) ?: return null
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "$type failed: ${result.optJSONObject("error")}")
            return null
        }
        return result.optJSONArray("result")
    }

    /**
     * REST `POST /api/tts_get_url` — synthesise [message] with a TTS engine and return
     * the media URL (absolute when HA knows its external/internal URL, else `/api/tts_proxy/...`).
     */
    suspend fun fetchTtsUrl(engineId: String, message: String, language: String?, voice: String?): String? {
        val body = JSONObject().apply {
            put("engine_id", engineId)
            put("message", message)
            if (!language.isNullOrBlank()) put("language", language)
            if (!voice.isNullOrBlank()) put("options", JSONObject().put("voice", voice))
            put("cache", true)
        }
        val result = restJson("POST", "/api/tts_get_url", body) ?: return null
        val url = result.optString("url").takeIf { it.isNotBlank() }
            ?: result.optString("path").takeIf { it.isNotBlank() }
        return url
    }

    suspend fun fetchTtsVoices(engineId: String, language: String): List<HaEngineOption> {
        val result = sendCommand(
            "tts/engine/voices",
            mapOf("engine_id" to engineId, "language" to language),
        ) ?: return emptyList()
        if (!result.optBoolean("success", false)) return emptyList()
        val data = result.optJSONObject("result") ?: return emptyList()
        val arr = data.optJSONArray("voices") ?: return emptyList()
        val list = mutableListOf<HaEngineOption>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("voice_id").ifBlank { obj.optString("id") }
            if (id.isBlank()) continue
            list.add(
                HaEngineOption(
                    id = id,
                    name = obj.optString("name").ifBlank { id },
                ),
            )
        }
        return list
    }

    suspend fun listEsphomeEntries(): List<HaConfigEntrySummary> = listConfigEntries("esphome")

    suspend fun listConfigEntries(domain: String? = null): List<HaConfigEntrySummary> {
        val params = if (domain.isNullOrBlank()) emptyMap() else mapOf("domain" to domain)
        val result = sendCommand("config_entries/get", params) ?: return emptyList()
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "config_entries/get failed: ${result.optJSONObject("error")}")
            return emptyList()
        }
        val arr = result.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<HaConfigEntrySummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("entry_id")
            if (id.isBlank()) continue
            list.add(
                HaConfigEntrySummary(
                    entryId = id,
                    title = obj.optString("title"),
                    domain = obj.optString("domain"),
                    state = obj.optString("state"),
                ),
            )
        }
        return list
    }

    suspend fun listDevices(): List<HaDeviceSummary> {
        val result = sendCommand("config/device_registry/list") ?: return emptyList()
        if (!result.optBoolean("success", false)) {
            Log.w(TAG, "device_registry/list failed: ${result.optJSONObject("error")}")
            return emptyList()
        }
        val arr = result.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<HaDeviceSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                HaDeviceSummary(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    primaryConfigEntry = obj.optString("primary_config_entry").takeIf { it.isNotBlank() },
                    configEntries = parseStringArray(obj.optJSONArray("config_entries") ?: JSONArray()),
                    connections = parsePairList(obj.optJSONArray("connections")),
                    identifiers = parsePairList(obj.optJSONArray("identifiers")),
                    viaDeviceId = optionalId(obj, "via_device_id"),
                ),
            )
        }
        return list
    }

    /** Frontend delete path: `DELETE /api/config/config_entries/entry/{id}`. */
    suspend fun deleteConfigEntry(entryId: String): Boolean {
        if (entryId.isBlank()) return false
        val json = restJson("DELETE", "/api/config/config_entries/entry/$entryId", body = null)
        return json != null
    }

    /**
     * Home Assistant `subscribe_entities` — the compressed state feed covering the whole
     * state machine.
     *
     * Emits flattened `(entityId, attribute, state)` triples that mirror what the ESPHome
     * reverse channel delivers, so both channels can share one ingress. An empty
     * [attribute] carries the primary state. The subscription opens with a full snapshot,
     * which is what lets the WebSocket take over from ESPHome without a gap.
     */
    suspend fun subscribeEntities(onState: (String, String, String) -> Unit): Int? {
        val ws = webSocket ?: return null
        val id = messageId.incrementAndGet()
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "subscribe_entities")
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        eventListeners[id] = { json -> emitEntityStates(json, onState) }
        ws.send(msg.toString())
        val result = try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            eventListeners.remove(id)
            Log.w(TAG, "subscribe_entities timed out: ${e.message}")
            return null
        }
        if (!result.optBoolean("success", false)) {
            eventListeners.remove(id)
            Log.w(TAG, "subscribe_entities failed: ${result.optJSONObject("error")}")
            return null
        }
        return id
    }

    suspend fun subscribeConfigEntries(onEvent: (JSONObject) -> Unit): Int? {
        val ws = webSocket ?: return null
        val id = messageId.incrementAndGet()
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "config_entries/subscribe")
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        eventListeners[id] = onEvent
        ws.send(msg.toString())
        val result = try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            eventListeners.remove(id)
            Log.w(TAG, "config_entries/subscribe timed out: ${e.message}")
            return null
        }
        if (!result.optBoolean("success", false)) {
            eventListeners.remove(id)
            Log.w(TAG, "config_entries/subscribe failed: ${result.optJSONObject("error")}")
            return null
        }
        return id
    }

    /**
     * `state_changed` for one entity. [onState] receives `new_state.state`.
     * Callback may run on the websocket thread.
     */
    suspend fun subscribeEntityState(entityId: String, onState: (String) -> Unit): Int? {
        val ws = webSocket ?: return null
        val id = messageId.incrementAndGet()
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        eventListeners[id] = { json ->
            val data = json.optJSONObject("event")?.optJSONObject("data")
            if (data != null && data.optString("entity_id") == entityId) {
                val state = data.optJSONObject("new_state")?.optString("state").orEmpty()
                if (state.isNotEmpty()) onState(state)
            }
        }
        ws.send(msg.toString())
        val result = try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            eventListeners.remove(id)
            Log.w(TAG, "subscribe_events state_changed timed out: ${e.message}")
            return null
        }
        if (!result.optBoolean("success", false)) {
            eventListeners.remove(id)
            Log.w(TAG, "subscribe_events state_changed failed: ${result.optJSONObject("error")}")
            return null
        }
        return id
    }

    fun unsubscribe(subscriptionId: Int) {
        eventListeners.remove(subscriptionId)
        scope.launch {
            sendCommand("unsubscribe_events", mapOf("subscription" to subscriptionId))
        }
    }

    /**
     * Open the ESPHome options flow and read current field defaults.
     * Caller must either [submitOptionsFlow] or [abortOptionsFlow].
     */
    suspend fun startOptionsFlow(entryId: String): HaOptionsFlowForm? {
        val body = JSONObject().put("handler", entryId)
        val json = restJson("POST", "/api/config/config_entries/options/flow", body) ?: return null
        val flowId = json.optString("flow_id")
        if (flowId.isBlank() || json.optString("type") != "form") {
            Log.w(TAG, "options flow start unexpected: ${json.optString("type")} ${json.opt("errors")}")
            if (flowId.isNotBlank()) abortOptionsFlow(flowId)
            return null
        }
        return HaOptionsFlowForm(
            flowId = flowId,
            defaults = parseSchemaDefaults(json.optJSONArray("data_schema")),
        )
    }

    suspend fun submitOptionsFlow(form: HaOptionsFlowForm, values: Map<String, Any?>): Boolean {
        val body = JSONObject()
        values.forEach { (key, value) ->
            when (value) {
                null -> body.put(key, JSONObject.NULL)
                else -> body.put(key, value)
            }
        }
        val json = restJson(
            "POST",
            "/api/config/config_entries/options/flow/${form.flowId}",
            body,
        ) ?: return false
        val type = json.optString("type")
        if (type == "create_entry") return true
        Log.w(TAG, "options flow submit failed: $type ${json.opt("errors")}")
        return false
    }

    suspend fun abortOptionsFlow(flowId: String) {
        restJson("DELETE", "/api/config/config_entries/options/flow/$flowId", body = null)
    }

    /**
     * Fetches state history for [entityId] between two ISO-8601 timestamps.
     * Uses the REST `/api/history/period` endpoint. Returns (epoch-millis, state)
     * pairs sorted chronologically, skipping unavailable/unknown states.
     */
    suspend fun fetchEntityHistory(
        entityId: String,
        startIso: String,
        endIso: String,
    ): List<Pair<Long, String>> {
        val path = "/api/history/period/$startIso" +
            "?filter_entity_id=$entityId&minimal_response&no_attributes&end_time=$endIso"
        val arr = restJsonArray("GET", path) ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val entityArr = arr.optJSONArray(0) ?: return emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        for (i in 0 until entityArr.length()) {
            val obj = entityArr.optJSONObject(i) ?: continue
            val state = obj.optString("state").takeIf { it.isNotBlank() } ?: continue
            if (state in HISTORY_SKIP_STATES) continue
            val lastChanged = obj.optString("last_changed")
            val epoch = parseIso8601(lastChanged) ?: continue
            out.add(epoch to state)
        }
        return out
    }

    /** GET that returns the HTTP code so a 404 is a miss, not a warning. */
    private suspend fun restGetJson(path: String): Pair<Int, JSONObject?> {
        val base = serverUrl.trimEnd('/')
        val token = accessToken
        if (base.isBlank() || token.isBlank()) return 0 to null
        val request = Request.Builder()
            .url("$base$path")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val text = response.body?.string().orEmpty()
                    if (code == 404) return@use 404 to null
                    if (!response.isSuccessful) {
                        Log.w(TAG, "REST GET $path -> $code $text")
                        return@use code to null
                    }
                    if (text.isBlank()) return@use code to null
                    code to JSONObject(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "REST GET $path failed: ${e.message}")
            0 to null
        }
    }

    private suspend fun restJsonArray(method: String, path: String): JSONArray? {
        val base = serverUrl.trimEnd('/')
        val token = accessToken
        if (base.isBlank() || token.isBlank()) return null
        val url = "$base$path"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, null)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "REST $method $path -> ${response.code} $text")
                        return@use null
                    }
                    if (text.isBlank()) null else JSONArray(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "REST $method $path failed: ${e.message}")
            null
        }
    }

    private suspend fun restJson(method: String, path: String, body: JSONObject?): JSONObject? {
        val base = serverUrl.trimEnd('/')
        val token = accessToken
        if (base.isBlank() || token.isBlank()) return null
        val url = "$base$path"
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, if (method == "DELETE" || method == "GET") null else requestBody)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "REST $method $path -> ${response.code} $text")
                        return@use null
                    }
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "REST $method $path failed: ${e.message}")
            null
        }
    }

    private fun doConnect() {
        val gen = generation.incrementAndGet()
        webSocket?.close(1000, "reconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Connecting
        messageId.set(0)
        pending.values.forEach { it.cancel() }
        pending.clear()
        eventListeners.clear()

        val wsUrl = buildWsUrl(serverUrl)
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation.get() != gen) return
                Log.i(TAG, "WebSocket opened to $wsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() != gen) return
                handleMessage(text, gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation.get() != gen) return
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation.get() != gen) return
                Log.i(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.Disconnected
                if (!userDisconnected) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String, gen: Int) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "auth_required" -> {
                    val authMsg = JSONObject().apply {
                        put("type", "auth")
                        put("access_token", accessToken)
                    }
                    webSocket?.send(authMsg.toString())
                }
                "auth_ok" -> {
                    val version = json.optString("ha_version", "unknown")
                    pipelineDebugDenied = false
                    reconnectAttempts.set(0)
                    _connectionState.value = ConnectionState.Connected(version)
                    Log.i(TAG, "Authenticated, HA version: $version")
                    scope.launch {
                        fetchPipelines()
                        fetchCatalog()
                    }
                }
                "auth_invalid" -> {
                    val msg = json.optString("message", "Invalid access token")
                    _connectionState.value = ConnectionState.Error(msg)
                    userDisconnected = true
                    webSocket?.close(1000, "Auth invalid")
                }
                "result" -> {
                    val id = json.optInt("id", -1)
                    pending.remove(id)?.complete(json)
                }
                "event" -> {
                    val id = json.optInt("id", -1)
                    val listener = eventListeners[id]
                    if (listener != null) {
                        listener(json)
                    } else {
                        pending.remove(id)?.complete(json)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WS message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        reconnectJob?.cancel()
        val attempt = reconnectAttempts.incrementAndGet()
        val delayMs = nextReconnectDelayMs(attempt)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userDisconnected && generation.get() > 0) {
                Log.i(TAG, "Reconnecting... (attempt $attempt after ${delayMs}ms)")
                doConnect()
            }
        }
    }

    /** Exponential backoff capped at [RECONNECT_MAX_DELAY_MS], plus up to 25% jitter. */
    private fun nextReconnectDelayMs(attempt: Int): Long {
        val capped = minOf(
            RECONNECT_DELAY_MS shl (attempt - 1).coerceAtMost(10),
            RECONNECT_MAX_DELAY_MS,
        )
        return capped + Random.nextLong(capped / 4 + 1)
    }

    companion object {
        private const val TAG = "HaWsClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HISTORY_SKIP_STATES = setOf("unavailable", "unknown")

        fun buildWsUrl(baseUrl: String): String {
            val normalized = baseUrl.trimEnd('/')
            val wsBase = when {
                normalized.startsWith("https://") -> "wss://" + normalized.removePrefix("https://")
                normalized.startsWith("http://") -> "ws://" + normalized.removePrefix("http://")
                else -> "ws://$normalized"
            }
            return "$wsBase/api/websocket"
        }

        /**
         * Flattens one `subscribe_entities` event. The payload uses HA's compressed shape:
         * `a` full additions, `c` per-entity changes (`+` set, `-` cleared), `r` removals.
         */
        private fun emitEntityStates(json: JSONObject, onState: (String, String, String) -> Unit) {
            val event = json.optJSONObject("event") ?: return

            event.optJSONObject("a")?.let { added ->
                added.keys().forEach { entityId ->
                    val entity = added.optJSONObject(entityId) ?: return@forEach
                    emitEntityPayload(entityId, entity, onState)
                }
            }

            event.optJSONObject("c")?.let { changed ->
                changed.keys().forEach { entityId ->
                    val change = changed.optJSONObject(entityId) ?: return@forEach
                    change.optJSONObject("+")?.let { emitEntityPayload(entityId, it, onState) }
                    val cleared = change.optJSONObject("-")?.optJSONArray("a")
                    if (cleared != null) {
                        for (i in 0 until cleared.length()) {
                            val attr = cleared.optString(i)
                            if (attr.isNotBlank()) onState(entityId, attr, "")
                        }
                    }
                }
            }

            event.optJSONArray("r")?.let { removed ->
                for (i in 0 until removed.length()) {
                    val entityId = removed.optString(i)
                    if (entityId.isNotBlank()) onState(entityId, "", "unavailable")
                }
            }
        }

        private fun emitEntityPayload(
            entityId: String,
            payload: JSONObject,
            onState: (String, String, String) -> Unit,
        ) {
            if (payload.has("s")) {
                onState(entityId, "", stringifyHaValue(payload.opt("s")))
            }
            payload.optJSONObject("a")?.let { attrs ->
                attrs.keys().forEach { attr ->
                    onState(entityId, attr, stringifyHaValue(attrs.opt(attr)))
                }
            }
        }

        /**
         * Matches how HA renders attribute values onto the ESPHome channel: scalars become
         * their plain text form, and lists/dicts (forecasts, source lists) stay JSON so
         * existing consumers can parse them unchanged.
         */
        private fun stringifyHaValue(value: Any?): String = when {
            value == null || value == JSONObject.NULL -> ""
            else -> value.toString()
        }

        private fun parseLanguageList(result: JSONObject?): List<String> {
            if (result == null || !result.optBoolean("success", false)) return emptyList()
            val arr = result.optJSONObject("result")?.optJSONArray("languages") ?: return emptyList()
            return parseStringArray(arr).sorted()
        }

        private fun parseConversationAgents(result: JSONObject?): List<HaEngineOption> {
            if (result == null || !result.optBoolean("success", false)) return emptyList()
            val arr = result.optJSONObject("result")?.optJSONArray("agents") ?: return emptyList()
            val list = mutableListOf<HaEngineOption>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                list.add(
                    HaEngineOption(
                        id = id,
                        name = obj.optString("name").ifBlank { id.substringAfterLast('.') },
                        languages = parseLanguages(obj.opt("supported_languages")),
                    ),
                )
            }
            return list
        }

        private fun parseEngineProviders(result: JSONObject?, idKey: String): List<HaEngineOption> {
            if (result == null || !result.optBoolean("success", false)) return emptyList()
            val arr = result.optJSONObject("result")?.optJSONArray("providers") ?: return emptyList()
            val list = mutableListOf<HaEngineOption>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString(idKey)
                if (id.isBlank()) continue
                list.add(
                    HaEngineOption(
                        id = id,
                        name = obj.optString("name").ifBlank { id.substringAfterLast('.') },
                        languages = parseLanguages(obj.opt("supported_languages")),
                    ),
                )
            }
            return list
        }

        private fun parseLanguages(raw: Any?): List<String> {
            return when (raw) {
                null, JSONObject.NULL -> emptyList()
                is String -> if (raw == "*" || raw.isBlank()) emptyList() else listOf(raw)
                is JSONArray -> parseStringArray(raw)
                else -> emptyList()
            }
        }

        private fun parseStringArray(arr: JSONArray): List<String> {
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val value = arr.optString(i)
                if (value.isNotBlank() && value != "*") out.add(value)
            }
            return out
        }

        private fun putJsonValue(json: JSONObject, key: String, value: Any?) {
            HaServiceJson.put(json, key, value)
        }

        private fun optionalId(obj: JSONObject, key: String): String? {
            val raw = obj.opt(key)
            if (raw == null || raw == JSONObject.NULL) return null
            return raw.toString().takeIf { it.isNotBlank() && it != "null" }
        }

        private fun parsePairList(arr: JSONArray?): List<Pair<String, String>> {
            if (arr == null) return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                val key = pair.optString(0)
                val value = pair.optString(1)
                if (key.isNotBlank() && value.isNotBlank()) out.add(key to value)
            }
            return out
        }

        private fun parseSchemaDefaults(schema: JSONArray?): Map<String, Any?> {
            if (schema == null) return emptyMap()
            val out = linkedMapOf<String, Any?>()
            for (i in 0 until schema.length()) {
                val field = schema.optJSONObject(i) ?: continue
                val name = field.optString("name")
                if (name.isBlank() || !field.has("default")) continue
                out[name] = field.opt("default").takeUnless { it == JSONObject.NULL }
            }
            return out
        }

        private fun parseIso8601(text: String): Long? {
            if (text.isBlank()) return null
            return try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(text.substringBefore('.').substringBefore('+').substringBefore('Z'))?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** One timer entity as read by [HaWsClient.fetchTimerStates]. */
data class HaTimerSnapshot(
    val entityId: String,
    val state: String,
    val remaining: String,
    val finishesAt: String,
)
