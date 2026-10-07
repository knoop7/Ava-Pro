package com.example.ava.homeassistant

import android.content.Context
import android.util.Log
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.homeassistant.entity.HaEntityLiveState
import com.example.ava.homeassistant.entity.HaEntitySummary
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.haSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.utils.HaMediaUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Singleton manager for the Home Assistant WebSocket integration.
 * Handles auto-connect from persisted settings and exposes reactive state to the UI.
 */
class HaManager private constructor(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    val settingsStore = HaSettingsStore(appContext.haSettingsStore)
    private val client = HaWsClient(scope)
    private val scanModeSync = HaBluetoothScanModeSync(appContext, scope, client, settingsStore)
    private val serviceCallsSync = HaAllowServiceCallsSync(appContext, scope, client, settingsStore)
    private val bluetoothCleanupSync = HaBluetoothProxyCleanupSync(appContext, scope, client, settingsStore)
    private val entityStateSync = HaEntityStateSync(scope, client, settingsStore)

    val connectionState: StateFlow<HaWsClient.ConnectionState> = client.connectionState
    val pipelines: StateFlow<List<HaPipeline>> = client.pipelines
    val preferredPipelineId: StateFlow<String?> = client.preferredPipelineId
    val entities: StateFlow<List<HaEntitySummary>> = client.entities
    val catalog: StateFlow<HaPipelineCatalog> = client.catalog
    private val _entityLoading = MutableStateFlow(false)
    val entityLoading: StateFlow<Boolean> = _entityLoading.asStateFlow()

    private fun start() {
        scope.launch {
            combine(
                settingsStore.serverUrl,
                settingsStore.accessToken,
                settingsStore.mediaBearerEnabled,
            ) { url, token, mediaBearer ->
                Triple(url, token, mediaBearer)
            }.collectLatest { (url, token, mediaBearer) ->
                HaMediaAuth.update(url, token, mediaBearer)
                if (url.isNotBlank() && token.isNotBlank()) {
                    client.connect(url, token)
                } else {
                    client.disconnect()
                }
            }
        }
        scanModeSync.start()
        serviceCallsSync.start()
        bluetoothCleanupSync.start()
        entityStateSync.start()
    }

    fun connect(url: String, token: String) {
        scope.launch {
            settingsStore.serverUrl.set(url)
            settingsStore.accessToken.set(token)
        }
    }

    fun disconnect() {
        scope.launch {
            settingsStore.accessToken.set("")
        }
        client.disconnect()
    }

    fun refreshPipelines() {
        scope.launch { client.fetchPipelines() }
    }

    fun refreshCatalog() {
        scope.launch { client.fetchCatalog() }
    }

    suspend fun fetchTtsVoices(engineId: String, language: String): List<HaEngineOption> {
        return client.fetchTtsVoices(engineId, language)
    }

    fun refreshEntities() {
        scope.launch {
            _entityLoading.value = true
            try {
                client.fetchEntities()
            } finally {
                _entityLoading.value = false
            }
        }
    }

    fun setEntityPickerEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.entityPickerEnabled.set(enabled)
        }
    }

    fun setScanModeSyncEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.scanModeSyncEnabled.set(enabled)
        }
    }

    fun setAllowServiceCalls(enabled: Boolean) {
        scope.launch {
            settingsStore.allowServiceCalls.set(enabled)
        }
    }

    fun setWsCallServiceEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.wsCallServiceEnabled.set(enabled)
        }
    }

    fun setLiveStateEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.liveStateEnabled.set(enabled)
        }
    }

    fun setBtProxyCleanupEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.btProxyCleanupEnabled.set(enabled)
        }
    }

    fun setMediaBearerEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.mediaBearerEnabled.set(enabled)
        }
    }

    /**
     * OpenClaw Mini `ha_camera_snapshot`: REST `GET /api/camera_proxy/<id>`
     * with the signed-in bearer. This is the picture, not `camera.turn_on`.
     */
    suspend fun fetchCameraSnapshot(entityId: String): ByteArray? {
        val id = entityId.trim()
        if (!VALID_ENTITY_ID.matches(id) || !id.startsWith("camera.")) return null
        val url = HaMediaUrl.signedInCameraProxyUrl(id) ?: return null
        val auth = HaMediaAuth.bearerHeader() ?: return null
        val request = Request.Builder().url(url).header("Authorization", auth).get().build()
        return withContext(Dispatchers.IO) {
            runCatching {
                snapshotHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "camera_proxy $id -> ${response.code}")
                        return@use null
                    }
                    val type = response.header("Content-Type").orEmpty()
                        .substringBefore(';').trim().lowercase()
                    val bytes = response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return@use null
                    if (type.startsWith("text/") || type.contains("json") || type.contains("html")) {
                        Log.w(TAG, "camera_proxy $id content-type=$type")
                        return@use null
                    }
                    bytes
                }
            }.onFailure { Log.w(TAG, "camera_proxy $id failed: ${it.message}") }.getOrNull()
        }
    }

    /**
     * Ask HA to open the camera's live HLS playlist (`camera/stream`).
     * Relative paths are joined to the signed-in origin.
     */
    suspend fun requestCameraStreamUrl(entityId: String): String? {
        if (!HaMediaAuth.signedIn) return null
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) {
            val next = withTimeoutOrNull(10_000) {
                connectionState.first {
                    it is HaWsClient.ConnectionState.Connected ||
                        it is HaWsClient.ConnectionState.Error
                }
            }
            if (next !is HaWsClient.ConnectionState.Connected) return null
        }
        val raw = client.requestCameraStreamUrl(entityId) ?: return null
        val resolved = HaMediaUrl.resolvePreferringSignedIn(raw, null)
        if (!resolved.isNullOrBlank() && !resolved.startsWith("/")) return resolved
        val origin = HaMediaAuth.serverUrl
        return if (raw.startsWith("/") && origin.isNotBlank()) origin + raw else raw
    }

    /**
     * Force-read current timer entity states over the authenticated WebSocket.
     * The push channels (ESPHome reverse channel, `subscribe_entities`) are only
     * as fresh as their last delivery; a direct `get_states` is the current
     * truth. Empty when signed out, disconnected, or live state is disabled.
     */
    suspend fun fetchTimerStates(entityIds: Set<String>): List<HaTimerSnapshot> {
        if (entityIds.isEmpty()) return emptyList()
        if (!settingsStore.liveStateEnabled.get()) return emptyList()
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return emptyList()
        return client.fetchTimerStates(entityIds)
    }

    fun setHistoryBackfillEnabled(enabled: Boolean) {
        scope.launch {
            settingsStore.historyBackfillEnabled.set(enabled)
        }
    }

    /**
     * Fetches state history for [entityId] covering the last [windowMs].
     * Used by sensor widgets to backfill the sparkline on first subscription.
     */
    suspend fun fetchEntityHistory(
        entityId: String,
        windowMs: Long = 8L * 60 * 60_000,
    ): List<Pair<Long, String>> {
        if (!settingsStore.historyBackfillEnabled.get()) return emptyList()
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return emptyList()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val now = System.currentTimeMillis()
        val startIso = fmt.format(java.util.Date(now - windowMs))
        val endIso = fmt.format(java.util.Date(now))
        return client.fetchEntityHistory(entityId, startIso, endIso)
    }

    /**
     * This device's HA "Finished speaking detection" select, if the ESPHome
     * integration has created it. Null entity id means it is not there yet.
     * The option is HA's state (`default` / `relaxed` / `aggressive`), or null
     * when the state is missing or not one of those three.
     */
    suspend fun finishedSpeaking(mac: String): Pair<String?, String?> {
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return null to null
        val entityId = client.findRegistryEntity { uniqueId, platform ->
            platform == "esphome" && com.example.ava.voice.FinishedSpeaking.matches(uniqueId, mac)
        } ?: return null to null
        val state = fetchEntityLiveStates(listOf(entityId))[entityId]?.state
        return entityId to com.example.ava.voice.FinishedSpeaking.canonical(state)
    }

    /** Follow HA state changes for the finished-speaking select. Null if the socket is down. */
    suspend fun watchFinishedSpeaking(entityId: String, onState: (String) -> Unit): Int? {
        if (!VALID_ENTITY_ID.matches(entityId)) return null
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return null
        return client.subscribeEntityState(entityId, onState)
    }

    fun unwatchFinishedSpeaking(subscriptionId: Int) {
        client.unsubscribe(subscriptionId)
    }

    /** Write HA's finished-speaking select. Does not go through the AI service gate. */
    suspend fun setFinishedSpeaking(mac: String, option: String): Boolean {
        val canonical = com.example.ava.voice.FinishedSpeaking.canonical(option) ?: return false
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return false
        val entityId = client.findRegistryEntity { uniqueId, platform ->
            platform == "esphome" && com.example.ava.voice.FinishedSpeaking.matches(uniqueId, mac)
        } ?: return false
        if (!VALID_ENTITY_ID.matches(entityId)) return false
        return client.callService(
            "select",
            "select_option",
            entityId,
            mapOf("option" to canonical),
        )
    }

    /**
     * User-initiated HA action (toggle / media / scene).
     * True means the WS request was accepted or may have been accepted; do not send
     * a second fallback command. Use tryCallServiceReporting for acknowledgement status.
     */
    suspend fun tryCallService(
        service: String,
        entityId: String,
        data: Map<String, Any?> = emptyMap(),
    ): Boolean {
        return tryCallServiceReporting(service, entityId, data) != false
    }

    /** Keep an unacknowledged send distinct from a known rejection for AI recovery. */
    suspend fun tryCallServiceReporting(
        service: String,
        entityId: String,
        data: Map<String, Any?> = emptyMap(),
    ): Boolean? {
        if (!settingsStore.wsCallServiceEnabled.get()) return false
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return false
        if (!VALID_ENTITY_ID.matches(entityId.trim())) return false
        val split = splitService(service) ?: return false
        val ok = client.callServiceReporting(
            split.first,
            split.second,
            entityId.trim(),
            com.example.ava.localllm.HaServicePayload.withoutTarget(data),
        )
        if (ok == true) {
            Log.i(TAG, "WS call_service ${split.first}.${split.second} $entityId")
        }
        return ok
    }

    /**
     * Live states for the ids the caller already chose. One REST GET each, in
     * parallel — never `get_states` for the whole house. Empty when signed out
     * or disconnected. Missing ids are omitted, not invented as unknown.
     */
    suspend fun fetchEntityLiveStates(entityIds: Collection<String>): Map<String, HaEntityLiveState> {
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return emptyMap()
        val want = entityIds.map { it.trim() }.filter { VALID_ENTITY_ID.matches(it) }.distinct()
        if (want.isEmpty()) return emptyMap()
        return coroutineScope {
            want.map { id ->
                async { client.fetchEntityLiveState(id)?.let { id to it } }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /** True only when HA has that id right now. Does not say whether it is exposed. */
    suspend fun entityExists(entityId: String): Boolean {
        val id = entityId.trim()
        if (!VALID_ENTITY_ID.matches(id)) return false
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return false
        return client.fetchEntityLiveState(id) != null
    }

    /** Assist-exposed entity ids, or null when HA WS is not connected / command unsupported. */
    suspend fun fetchAssistExposedEntityIds(): Set<String>? = client.fetchAssistExposedEntityIds()

    /**
     * Structured result of the Assist run that just processed [sttText], from the pipeline
     * debug log (admin token). Every known pipeline is asked in parallel because the
     * satellite's assigned pipeline may differ from the preferred one; the run whose
     * `intent_input` equals the transcript wins. Null when the log is unavailable.
     */
    suspend fun fetchAssistVerdict(sttText: String): HaAssistVerdict? {
        val want = sttText.trim()
        if (want.isEmpty()) return null
        val preferred = preferredPipelineId.value
        val candidates = pipelines.value
            .sortedByDescending { it.id == preferred }
            .take(MAX_VERDICT_PIPELINES)
        if (candidates.isEmpty()) return null
        return coroutineScope {
            candidates.map { pipeline ->
                async {
                    client.fetchLatestPipelineRunEvents(pipeline.id)?.let(HaAssistVerdict::fromEvents)
                }
            }.awaitAll()
        }.filterNotNull().firstOrNull { it.intentInput.trim() == want }
    }

    /**
     * Spoken names Assist itself matches against: registry name override, aliases, and
     * the entity's area (own or inherited from its device). Empty when HA is offline.
     */
    suspend fun fetchAssistNames(entityIds: Collection<String>): Map<String, HaEntityNames> {
        // `cv.entity_ids` rejects the whole request on one bad id, and the expose list can
        // carry non-entity keys (seen live: a bare "Light 5").
        val valid = entityIds.filter { VALID_ENTITY_ID.matches(it) }.distinct()
        if (valid.isEmpty()) return emptyMap()
        val entries = client.fetchEntityRegistryEntries(valid) ?: return emptyMap()
        if (entries.isEmpty()) return emptyMap()
        val areas = client.fetchAreaNames().orEmpty()
        val needsDeviceArea = entries.values.any {
            it.optString("area_id").isBlank() && it.optString("device_id").isNotBlank()
        }
        val deviceAreas = if (needsDeviceArea) client.fetchDeviceAreas().orEmpty() else emptyMap()
        val out = HashMap<String, HaEntityNames>(entries.size)
        for ((id, entry) in entries) {
            val names = ArrayList<String>()
            entry.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let(names::add)
            entry.optJSONArray("aliases")?.let { aliases ->
                for (i in 0 until aliases.length()) {
                    if (aliases.isNull(i)) continue
                    aliases.optString(i).takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
            val areaId = entry.optString("area_id").takeIf { it.isNotBlank() && it != "null" }
                ?: deviceAreas[entry.optString("device_id")]
            out[id] = HaEntityNames(
                names = names.distinct(),
                areaNames = areaId?.let { areas[it] }.orEmpty(),
            )
        }
        return out
    }

    /** Pull `get_states` synchronously (suspending) so callers can use the fresh list at once. */
    suspend fun fetchEntitiesNow(): List<HaEntitySummary> {
        client.fetchEntities()
        return entities.value
    }

    /** Inject [text] into Assist as a user turn. Returns agent speech, or null. */
    suspend fun processConversation(text: String, conversationId: String? = null): String? {
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return null
        return client.processConversation(text, conversationId)
    }

    /** True when Assist accepted the user turn, even if it spoke nothing. */
    suspend fun processConversationAck(text: String, conversationId: String? = null): Boolean {
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) return false
        return client.processConversationAck(text, conversationId)
    }

    /**
     * Synthesise [message] with the preferred pipeline's TTS engine. Returns a URL the
     * satellite's TTS player can resolve, or null when no pipeline / engine is known.
     */
    suspend fun synthesizeWithPipelineTts(message: String): String? {
        val pipelines = pipelines.value
        val preferredId = preferredPipelineId.value ?: settingsStore.preferredPipeline.get().ifBlank { null }
        val pipeline = pipelines.firstOrNull { it.id == preferredId } ?: pipelines.firstOrNull() ?: return null
        val engine = pipeline.ttsEngine ?: return null
        return client.fetchTtsUrl(engine, message, pipeline.ttsLanguage ?: pipeline.language, pipeline.ttsVoice)
    }

    fun setPreferredPipeline(pipelineId: String) {
        scope.launch {
            if (client.setPreferredPipeline(pipelineId)) {
                settingsStore.preferredPipeline.set(pipelineId)
            }
        }
    }

    fun updatePipeline(
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
    ) {
        scope.launch {
            client.updatePipeline(
                pipelineId = pipelineId,
                name = name,
                language = language,
                conversationEngine = conversationEngine,
                conversationLanguage = conversationLanguage,
                sttEngine = sttEngine,
                sttLanguage = sttLanguage,
                ttsEngine = ttsEngine,
                ttsLanguage = ttsLanguage,
                ttsVoice = ttsVoice,
                wakeWordEntity = wakeWordEntity,
                wakeWordId = wakeWordId,
            )
        }
    }

    fun dismissGuide() {
        scope.launch {
            settingsStore.guideDismissed.set(true)
        }
    }

    fun dismissPipelineGuide() {
        scope.launch {
            settingsStore.pipelineGuideDismissed.set(true)
        }
    }

    suspend fun cleanupBluetoothProxyDevices(): HaBluetoothProxyCleanup.Result {
        if (connectionState.value !is HaWsClient.ConnectionState.Connected) {
            return HaBluetoothProxyCleanup.Result(reason = HaBluetoothProxyCleanup.Reason.NOT_CONNECTED)
        }
        val settings = appContext.voiceSatelliteSettingsStore.data.first()
        val detectEnabled = BluetoothPresenceManager.getInstance(appContext).isDetectEnabled
        return HaBluetoothProxyCleanup.run(client, settings, detectEnabled)
    }

    companion object {
        private const val TAG = "HaManager"
        private const val MAX_VERDICT_PIPELINES = 6
        /** Same rule as `homeassistant.core.valid_entity_id`. */
        private val VALID_ENTITY_ID = Regex("^(?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)$")
        private val snapshotHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var instance: HaManager? = null

        private val _instanceFlow = MutableStateFlow<HaManager?>(null)
        val instanceFlow: StateFlow<HaManager?> = _instanceFlow.asStateFlow()

        fun get(): HaManager? = instance

        fun ensure(context: Context): HaManager {
            synchronized(this) {
                instance?.let { return it }
                val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                return HaManager(context.applicationContext, appScope).also {
                    instance = it
                    _instanceFlow.value = it
                    it.start()
                }
            }
        }

        suspend fun tryCallService(
            context: Context,
            service: String,
            entityId: String,
            data: Map<String, Any?> = emptyMap(),
        ): Boolean {
            val existing = get()
            val manager = existing ?: run {
                val store = HaSettingsStore(context.applicationContext.haSettingsStore)
                if (!store.wsCallServiceEnabled.get()) return false
                if (store.serverUrl.get().isBlank() || store.accessToken.get().isBlank()) return false
                ensure(context)
            }
            return manager.tryCallService(service, entityId, data)
        }

        private fun splitService(service: String): Pair<String, String>? {
            val dot = service.indexOf('.')
            if (dot <= 0 || dot == service.lastIndex) return null
            return service.substring(0, dot) to service.substring(dot + 1)
        }

        fun shutdown() {
            synchronized(this) {
                instance?.client?.disconnect()
                instance = null
                _instanceFlow.value = null
                HaMediaAuth.clear()
            }
        }
    }
}
