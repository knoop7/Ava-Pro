package com.example.ava.massapi

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Thin Music Assistant WebSocket client (massdroid-style):
 * handshake → `auth/login` (username/password) or `auth` (token) → events.
 */
class MassApiClient(
    private val scope: CoroutineScope,
) {
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val serverVersion: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    data class QueueTransport(
        val queueId: String,
        val repeatMode: String,
        val shuffleEnabled: Boolean,
        val name: String = "",
        val active: Boolean = false,
        /** Queue media position in seconds (MA `elapsed_time`). */
        val elapsedTimeSec: Double? = null,
        /** Server wall-clock seconds when [elapsedTimeSec] was captured. */
        val elapsedTimeLastUpdatedSec: Double? = null,
        /** Current item duration in seconds, if present. */
        val durationSec: Double? = null,
        /** MA queue/player state: playing / paused / idle / … */
        val state: String = "",
        val currentItemId: String = "",
        /** From `current_item.media_item` when present (identity bridge). */
        val currentTitle: String = "",
        val currentArtist: String = "",
        val currentImageUrl: String = "",
        /** MA `autoplay_enabled` (legacy: `dont_stop_the_music_enabled`). */
        val autoplayEnabled: Boolean = false,
        /** MA `crossfade_enabled` — queue fade in / fade out between tracks. */
        val crossfadeEnabled: Boolean = false,
    )

    data class ServerEvent(
        val event: String,
        /** MA event target id (queue_id for queue_* events). */
        val objectId: String? = null,
        val data: JSONObject? = null,
        /** Primitive payload (e.g. `queue_time_updated` elapsed seconds). */
        val dataNumber: Double? = null,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var authToken: String = ""
    private var pendingLogin: Pair<String, String>? = null
    private var savedCredentials: Pair<String, String>? = null
    private var onTokenReceived: ((String) -> Unit)? = null
    private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val connectionGeneration = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private val baseOkHttp = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Active client; may wrap [baseOkHttp] with mTLS like massdroid. */
    @Volatile
    private var okHttpClient: OkHttpClient = baseOkHttp

    fun setSavedCredentials(username: String, password: String) {
        savedCredentials = username to password
    }

    fun clearSavedCredentials() {
        savedCredentials = null
    }

    /** massdroid: attach client cert from Android KeyChain for HTTPS/WSS. */
    fun configureMtls(privateKey: PrivateKey, certChain: Array<X509Certificate>) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", privateKey, charArrayOf(), certChain)
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }
        val trustManager = tmf.trustManagers.first() as X509TrustManager
        okHttpClient = baseOkHttp.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
        Log.i(TAG, "mTLS configured with client certificate")
    }

    fun clearMtls() {
        okHttpClient = baseOkHttp
        Log.i(TAG, "mTLS cleared")
    }

    /** Reconnect with a previously issued access token. */
    fun connect(baseUrl: String, token: String) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank() || token.isBlank()) {
            _connectionState.value = ConnectionState.Error("Server URL and credentials are required")
            return
        }
        val same =
            serverUrl == normalized &&
                authToken == token &&
                pendingLogin == null &&
                (_connectionState.value is ConnectionState.Connected ||
                    _connectionState.value is ConnectionState.Connecting ||
                    reconnectJob?.isActive == true)
        if (!userDisconnected && same) return

        serverUrl = normalized
        authToken = token
        pendingLogin = null
        onTokenReceived = null
        userDisconnected = false
        cancelReconnect()
        doConnect(normalized)
    }

    /**
     * massdroid path: open WS → `auth/login` → save token → `auth`.
     */
    fun connectWithLogin(
        baseUrl: String,
        username: String,
        password: String,
        onToken: (String) -> Unit,
    ) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank() || username.isBlank() || password.isBlank()) {
            _connectionState.value = ConnectionState.Error("Fill in all fields")
            return
        }
        val login = username to password
        val same =
            serverUrl == normalized &&
                pendingLogin == login &&
                (_connectionState.value is ConnectionState.Connected ||
                    _connectionState.value is ConnectionState.Connecting ||
                    reconnectJob?.isActive == true)
        if (!userDisconnected && same) return

        serverUrl = normalized
        authToken = ""
        pendingLogin = login
        savedCredentials = login
        onTokenReceived = onToken
        userDisconnected = false
        cancelReconnect()
        doConnect(normalized)
    }

    fun disconnect() {
        userDisconnected = true
        cancelReconnect()
        connectionGeneration.incrementAndGet()
        failPending("Disconnected")
        webSocket?.close(1000, "user disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun close() {
        disconnect()
    }

    /** Normalized HTTP(S) base used to build imageproxy cover URLs. */
    fun serverBaseUrl(): String = serverUrl

    suspend fun fetchQueues(): List<QueueTransport> {
        val result = sendCommand("player_queues/all") ?: return emptyList()
        val arr = result.optJSONArray("result") ?: return emptyList()
        return parseQueueArray(arr, serverUrl)
    }

    suspend fun fetchPlayers(): List<MassPlayer> {
        val result = sendCommand(
            "players/all",
            JSONObject().put("return_unavailable", false).put("return_disabled", false),
        ) ?: return emptyList()
        val arr = result.optJSONArray("result") ?: return emptyList()
        return parsePlayerArray(arr)
    }

    /**
     * Queue items for [queueId], or **null** when the RPC itself failed (not
     * connected / exception / 20s timeout / malformed reply). Callers must treat
     * null as "unknown, keep current state" — only a non-null empty list means the
     * server genuinely reported an empty queue. A blank [queueId] is a genuine
     * "no queue" and returns an empty list, not null.
     */
    suspend fun fetchQueueItems(
        queueId: String,
        limit: Int = 80,
        offset: Int = 0,
    ): List<MassQueueItem>? {
        if (queueId.isBlank()) return emptyList()
        val result = sendCommand(
            "player_queues/items",
            JSONObject()
                .put("queue_id", queueId)
                .put("limit", limit)
                .put("offset", offset),
        ) ?: return null
        val arr = result.optJSONArray("result") ?: return null
        return parseQueueItemArray(arr, serverUrl)
    }

    suspend fun fetchPlaylists(limit: Int = 40, offset: Int = 0): List<MassPlaylist> {
        val result = sendCommand(
            "music/playlists/library_items",
            JSONObject()
                .put("limit", limit)
                .put("offset", offset)
                .put("order_by", "name"),
        ) ?: return emptyList()
        val arr = result.optJSONArray("result") ?: return emptyList()
        // List rows: no cover resolution — detail page loads art lazily.
        return parsePlaylistArray(arr, baseUrl = "", includeImage = false)
    }

    /**
     * Full playlist for the detail header (name / description / cover URL).
     * Tries current + legacy command names so older MA servers still work.
     */
    suspend fun fetchPlaylistDetail(itemId: String, provider: String): MassPlaylist? {
        if (itemId.isBlank()) return null
        val args = JSONObject()
            .put("item_id", itemId)
            .put("provider_instance_id_or_domain", provider.ifBlank { "library" })
        val commands = listOf(
            "music/playlists/get",
            "music/playlists/get_playlist",
            "music/playlist",
        )
        for (cmd in commands) {
            val result = sendCommand(cmd, args) ?: continue
            val data = result.optJSONObject("result") ?: continue
            parsePlaylist(data, baseUrl = serverUrl, includeImage = true)?.let { return it }
        }
        return null
    }

    /**
     * One server page of playlist tracks (`music/playlists/playlist_tracks`).
     * Some MA builds loop pages server-side and dump a large first page — callers
     * must window into the UI instead of painting everything at once.
     */
    suspend fun fetchPlaylistTracks(
        itemId: String,
        provider: String,
        page: Int = 0,
    ): List<MassPlaylistTrack> {
        if (itemId.isBlank()) return emptyList()
        val result = sendCommand(
            "music/playlists/playlist_tracks",
            JSONObject()
                .put("item_id", itemId)
                .put("provider_instance_id_or_domain", provider.ifBlank { "library" })
                .put("page", page.coerceAtLeast(0)),
        ) ?: return emptyList()
        val arr = result.optJSONArray("result") ?: return emptyList()
        return parsePlaylistTrackArray(arr)
    }

    /**
     * Global search across every configured provider (`music/search`).
     *
     * [limit] is MA's *per media type* cap, not a total — asking for three types can
     * return up to three times this many rows. `providers` is deliberately omitted so
     * the search spans all providers rather than only the local library.
     */
    suspend fun searchMusic(
        query: String,
        limit: Int = 20,
        mediaTypes: List<String> = SEARCH_MEDIA_TYPES,
    ): List<MassSearchItem> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val types = mediaTypes.ifEmpty { SEARCH_MEDIA_TYPES }
        val result = sendCommand(
            "music/search",
            JSONObject()
                .put("search_query", trimmed)
                .put("media_types", JSONArray(types))
                .put("limit", limit.coerceIn(1, 50)),
        ) ?: return emptyList()
        val data = result.optJSONObject("result") ?: return emptyList()
        return parseSearchResults(data, serverUrl)
    }

    suspend fun setGroupMembers(
        targetPlayer: String,
        add: List<String>? = null,
        remove: List<String>? = null,
    ): Boolean {
        if (targetPlayer.isBlank()) return false
        val args = JSONObject().put("target_player", targetPlayer)
        if (!add.isNullOrEmpty()) {
            args.put("player_ids_to_add", JSONArray(add))
        }
        if (!remove.isNullOrEmpty()) {
            args.put("player_ids_to_remove", JSONArray(remove))
        }
        return sendCommand("players/cmd/set_members", args) != null
    }

    suspend fun transferQueue(
        sourceQueueId: String,
        targetQueueId: String,
        autoPlay: Boolean = true,
    ): Boolean {
        if (sourceQueueId.isBlank() || targetQueueId.isBlank()) return false
        return sendCommand(
            "player_queues/transfer",
            JSONObject()
                .put("source_queue_id", sourceQueueId)
                .put("target_queue_id", targetQueueId)
                .put("auto_play", autoPlay),
        ) != null
    }

    suspend fun setPlayerVolume(playerId: String, volumeLevel: Int): Boolean {
        if (playerId.isBlank()) return false
        return sendCommand(
            "players/cmd/volume_set",
            JSONObject()
                .put("player_id", playerId)
                .put("volume_level", volumeLevel.coerceIn(0, 100)),
        ) != null
    }

    suspend fun setGroupVolume(playerId: String, volumeLevel: Int): Boolean {
        if (playerId.isBlank()) return false
        return sendCommand(
            "players/cmd/group_volume",
            JSONObject()
                .put("player_id", playerId)
                .put("volume_level", volumeLevel.coerceIn(0, 100)),
        ) != null
    }

    suspend fun playMedia(
        queueId: String,
        mediaUris: List<String>,
        option: String = "replace",
    ): Boolean {
        if (queueId.isBlank() || mediaUris.isEmpty()) return false
        return sendCommand(
            "player_queues/play_media",
            JSONObject()
                .put("queue_id", queueId)
                .put("media", JSONArray(mediaUris))
                .put("option", option),
        ) != null
    }

    suspend fun playQueueIndex(queueId: String, index: Int, queueItemId: String = ""): Boolean {
        if (queueId.isBlank()) return false
        // MA accepts a queue_item_id in `index`. A numeric slot drifts after
        // a long session (played rows drop, radio reindexes) and then hits
        // "media item could not be found" for a different row.
        val args = JSONObject().put("queue_id", queueId)
        if (queueItemId.isNotBlank()) args.put("index", queueItemId) else args.put("index", index)
        return sendCommand("player_queues/play_index", args) != null
    }

    /**
     * Music Assistant lyrics (massdroid [LyricsProvider] path):
     * 1) `music/tracks/get` → full track object
     * 2) `metadata/get_track_lyrics` with that track → `[plain, lrc]`
     *
     * Returns synced LRC text only (plain-only is a miss for Ava's timed wall).
     */
    suspend fun fetchTrackSyncedLrc(itemId: String, provider: String): String? {
        if (itemId.isBlank() || provider.isBlank()) return null
        val trackResp = sendCommand(
            "music/tracks/get",
            JSONObject()
                .put("item_id", itemId)
                .put("provider_instance_id_or_domain", provider),
        ) ?: return null
        val trackJson = trackResp.optJSONObject("result") ?: return null
        val lyricsResp = sendCommand(
            "metadata/get_track_lyrics",
            JSONObject().put("track", trackJson),
        ) ?: return null
        val arr = lyricsResp.optJSONArray("result") ?: return null
        val plain = arr.optString(0).takeIf { it.isNotBlank() }
        val lrc = arr.optString(1).takeIf { it.isNotBlank() }
        return pickSyncedLrc(plain, lrc)
    }

    suspend fun moveQueueItem(queueId: String, queueItemId: String, posShift: Int): Boolean {
        if (queueId.isBlank() || queueItemId.isBlank()) return false
        return sendCommand(
            "player_queues/move_item",
            JSONObject()
                .put("queue_id", queueId)
                .put("queue_item_id", queueItemId)
                .put("pos_shift", posShift),
        ) != null
    }

    /**
     * MA `player_queues/autoplay` — keep playing after the queue ends.
     * Falls back to legacy `player_queues/dont_stop_the_music` on older servers.
     */
    suspend fun setQueueAutoplay(queueId: String, enabled: Boolean): Boolean {
        if (queueId.isBlank()) return false
        val modern = sendCommand(
            "player_queues/autoplay",
            JSONObject()
                .put("queue_id", queueId)
                .put("autoplay_enabled", enabled),
        )
        if (modern != null) return true
        return sendCommand(
            "player_queues/dont_stop_the_music",
            JSONObject()
                .put("queue_id", queueId)
                .put("dont_stop_the_music_enabled", enabled),
        ) != null
    }

    /** MA `player_queues/crossfade` — enable/disable queue fade in / fade out. */
    suspend fun setQueueCrossfade(queueId: String, enabled: Boolean): Boolean {
        if (queueId.isBlank()) return false
        return sendCommand(
            "player_queues/crossfade",
            JSONObject()
                .put("queue_id", queueId)
                .put("crossfade_enabled", enabled),
        ) != null
    }

    /** MA `player_queues/clear` — wipe all items on the queue (stops playback). */
    suspend fun clearQueue(queueId: String): Boolean {
        if (queueId.isBlank()) return false
        return sendCommand(
            "player_queues/clear",
            JSONObject().put("queue_id", queueId),
        ) != null
    }

    /**
     * MA `music/favorites/add_item` — [itemUri] is a media URI (or share URL).
     */
    suspend fun addFavoriteItem(itemUri: String): Boolean {
        if (itemUri.isBlank()) return false
        return sendCommand(
            "music/favorites/add_item",
            JSONObject().put("item", itemUri),
        ) != null
    }

    /**
     * MA `music/favorites/remove_item` — needs library DB id (not provider track id).
     */
    suspend fun removeFavoriteItem(
        mediaType: String,
        libraryItemId: String,
    ): Boolean {
        if (libraryItemId.isBlank()) return false
        return sendCommand(
            "music/favorites/remove_item",
            JSONObject()
                .put("media_type", mediaType.ifBlank { "track" })
                .put("library_item_id", libraryItemId),
        ) != null
    }

    /**
     * Resolve the library DB id for a media URI so we can unfavorite.
     * Tries `music/item_by_uri`, then `music/get_library_item` by provider id.
     */
    suspend fun resolveLibraryFavoriteTarget(
        itemUri: String,
        mediaItemId: String = "",
        provider: String = "",
        mediaType: String = "track",
    ): Pair<String, String>? {
        val kind = mediaType.ifBlank { "track" }
        if (itemUri.isNotBlank()) {
            val byUri = sendCommand(
                "music/item_by_uri",
                JSONObject().put("uri", itemUri),
            )?.optJSONObject("result")
            parseLibraryFavoriteTarget(byUri)?.let { return it }
        }
        if (mediaItemId.isBlank() || provider.isBlank()) return null
        val byProv = sendCommand(
            "music/get_library_item",
            JSONObject()
                .put("media_type", kind)
                .put("item_id", mediaItemId)
                .put("provider_instance_id_or_domain", provider),
        )?.optJSONObject("result")
        return parseLibraryFavoriteTarget(byProv)
    }

    private fun parseLibraryFavoriteTarget(data: JSONObject?): Pair<String, String>? {
        if (data == null) return null
        val mediaType = data.optString("media_type", "track").ifBlank { "track" }
        val provider = data.optString("provider", "")
        val itemId = data.optString("item_id", "")
        if (provider == "library" && itemId.isNotBlank()) {
            return mediaType to itemId
        }
        // Provider item — try nested library mapping if present.
        val mappings = data.optJSONArray("provider_mappings")
        if (mappings != null) {
            for (i in 0 until mappings.length()) {
                val m = mappings.optJSONObject(i) ?: continue
                if (m.optString("provider_domain", m.optString("provider", "")) == "library" ||
                    m.optString("provider_instance", "").startsWith("library")
                ) {
                    val libId = m.optString("item_id", "")
                    if (libId.isNotBlank()) return mediaType to libId
                }
            }
        }
        // Last resort: numeric item_id often means already a library row.
        if (itemId.isNotBlank() && itemId.all { it.isDigit() }) {
            return mediaType to itemId
        }
        return null
    }

    /** Public command channel for rail / future typed wrappers. */
    suspend fun command(command: String, args: JSONObject? = null): JSONObject? =
        sendCommand(command, args)

    private fun doConnect(baseUrl: String) {
        webSocket?.close(1000, null)
        webSocket = null
        failPending("Reconnecting")

        val gen = connectionGeneration.incrementAndGet()
        _connectionState.value = ConnectionState.Connecting

        val wsUrl = baseUrl
            .replace("https://", "wss://", ignoreCase = true)
            .replace("http://", "ws://", ignoreCase = true)
            .trimEnd('/') + "/ws"

        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Invalid server URL")
            return
        }

        Log.i(TAG, "Connecting $wsUrl")
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connectionGeneration.get()) return
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connectionGeneration.get()) return
                handleMessage(text, gen)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration.get()) return
                Log.w(TAG, "WebSocket closed: $code $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connectionGeneration.get()) return
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String, gen: Int) {
        try {
            val obj = JSONObject(text)
            when {
                obj.has("server_id") -> {
                    val version = obj.optString("server_version", obj.optString("version", "?"))
                    Log.i(TAG, "Server handshake v$version")
                    authenticate(gen, version)
                }

                obj.has("message_id") -> {
                    val id = obj.optString("message_id")
                    val deferred = pending.remove(id) ?: return
                    if (obj.has("error") || obj.has("error_code")) {
                        val msg = obj.optString("error").ifBlank {
                            obj.optString("details", "Unknown error")
                        }
                        deferred.completeExceptionally(MassApiException(msg, obj.optInt("error_code", -1)))
                    } else {
                        deferred.complete(obj)
                    }
                }

                obj.has("event") -> {
                    val event = obj.optString("event")
                    val objectId = obj.optString("object_id").ifBlank { null }
                    val raw = if (obj.has("data") && !obj.isNull("data")) obj.get("data") else null
                    when (raw) {
                        is JSONObject ->
                            _events.tryEmit(ServerEvent(event, objectId, raw, null))
                        is Number ->
                            _events.tryEmit(ServerEvent(event, objectId, null, raw.toDouble()))
                        is String ->
                            _events.tryEmit(
                                ServerEvent(event, objectId, null, raw.toDoubleOrNull()),
                            )
                        else ->
                            _events.tryEmit(ServerEvent(event, objectId, null, null))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun authenticate(gen: Int, serverVersion: String) {
        scope.launch {
            try {
                val login = pendingLogin
                when {
                    login != null -> {
                        loginWithCredentials(login.first, login.second)
                    }
                    authToken.isNotBlank() -> {
                        try {
                            authorizeWithToken(authToken)
                        } catch (e: Exception) {
                            val creds = savedCredentials
                            if (creds != null) {
                                Log.w(TAG, "Token auth failed, trying saved credentials")
                                loginWithCredentials(creds.first, creds.second)
                            } else {
                                throw e
                            }
                        }
                    }
                    else -> {
                        _connectionState.value = ConnectionState.Error("No token or credentials available")
                        return@launch
                    }
                }
                if (gen != connectionGeneration.get()) return@launch
                reconnectAttempts = 0
                _connectionState.value = ConnectionState.Connected(serverVersion)
                Log.i(TAG, "Authenticated")
            } catch (e: Exception) {
                Log.e(TAG, "Auth failed: ${e.message}")
                if (gen == connectionGeneration.get()) {
                    authToken = ""
                    _connectionState.value = ConnectionState.Error("Authentication failed: ${e.message}")
                    webSocket?.close(1000, "auth failed")
                    webSocket = null
                }
            }
        }
    }

    private suspend fun loginWithCredentials(username: String, password: String) {
        Log.d(TAG, "Logging in as $username")
        val response = sendRawCommand(
            "auth/login",
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("device_name", "Ava"),
        )
        val result = response.optJSONObject("result") ?: response
        val success = result.optBoolean("success", false)
        val token = result.optString("access_token").ifBlank { null }
        val error = result.optString("error").ifBlank { null }
        if (!success || token.isNullOrBlank()) {
            throw MassApiException(error ?: "Login failed", -1)
        }
        authToken = token
        pendingLogin = null
        onTokenReceived?.invoke(token)
        onTokenReceived = null
        Log.d(TAG, "Login successful")
        authorizeWithToken(token)
    }

    private suspend fun authorizeWithToken(token: String) {
        sendRawCommand(
            "auth",
            JSONObject()
                .put("token", token)
                .put("device_name", "Ava"),
        )
    }

    /** Used during handshake auth before [ConnectionState.Connected]. */
    private suspend fun sendRawCommand(command: String, args: JSONObject? = null): JSONObject {
        val messageId = newMessageId()
        val deferred = CompletableDeferred<JSONObject>()
        pending[messageId] = deferred
        val msg = JSONObject()
            .put("command", command)
            .put("message_id", messageId)
        if (args != null) msg.put("args", args)
        val ws = webSocket
        if (ws == null || !ws.send(msg.toString())) {
            pending.remove(messageId)
            throw MassApiException("Failed to send $command", -1)
        }
        return withTimeout(20_000) { deferred.await() }
    }

    private suspend fun sendCommand(command: String, args: JSONObject? = null): JSONObject? {
        if (_connectionState.value !is ConnectionState.Connected) return null
        return try {
            sendRawCommand(command, args)
        } catch (e: Exception) {
            Log.w(TAG, "sendCommand($command) failed: ${e.message}")
            null
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) {
            if (_connectionState.value !is ConnectionState.Error) {
                _connectionState.value = ConnectionState.Disconnected
            }
            return
        }
        if (serverUrl.isBlank()) return
        if (authToken.isBlank() && pendingLogin == null && savedCredentials == null) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            reconnectAttempts += 1
            if (reconnectAttempts > MAX_RETRY) {
                _connectionState.value = ConnectionState.Error("Connection lost. Check server and retry.")
                return@launch
            }
            val delayMs = if (reconnectAttempts <= 3) 1_500L else 5_000L
            delay(delayMs)
            if (userDisconnected) return@launch
            // Prefer token; credentials remain as authenticate() fallback.
            if (authToken.isBlank() && savedCredentials != null) {
                pendingLogin = savedCredentials
            }
            doConnect(serverUrl)
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun failPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(MassApiException(reason, -1)) }
        pending.clear()
    }

    private fun newMessageId(): String =
        UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        private const val TAG = "MassApiClient"
        private const val MAX_RETRY = 30

        const val EVENT_QUEUE_UPDATED = "queue_updated"
        const val EVENT_QUEUE_TIME_UPDATED = "queue_time_updated"

        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            if (!s.contains("://")) s = "http://$s"
            if (s.endsWith("/ws", ignoreCase = true)) s = s.dropLast(3).trimEnd('/')
            return s
        }

        fun parseQueueTransport(data: JSONObject?, baseUrl: String = ""): QueueTransport? {
            if (data == null) return null
            val queueId = data.optString("queue_id").ifBlank { return null }
            val state = data.optString("state", "")
            val currentItem = data.optJSONObject("current_item")
            val currentParsed = parseQueueItem(currentItem, isCurrent = true, baseUrl = baseUrl)
            val durationSec = when {
                currentItem == null -> null
                currentItem.has("duration") && !currentItem.isNull("duration") ->
                    currentItem.optDouble("duration", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                else -> null
            }
            val elapsed = if (data.has("elapsed_time") && !data.isNull("elapsed_time")) {
                data.optDouble("elapsed_time", Double.NaN).takeIf { !it.isNaN() && it >= 0.0 }
            } else {
                null
            }
            val lastUpdated =
                if (data.has("elapsed_time_last_updated") && !data.isNull("elapsed_time_last_updated")) {
                    data.optDouble("elapsed_time_last_updated", Double.NaN)
                        .takeIf { !it.isNaN() && it > 0.0 }
                } else {
                    null
                }
            val autoplay = when {
                data.has("autoplay_enabled") -> data.optBoolean("autoplay_enabled", false)
                data.has("dont_stop_the_music_enabled") ->
                    data.optBoolean("dont_stop_the_music_enabled", false)
                else -> false
            }
            return QueueTransport(
                queueId = queueId,
                repeatMode = data.optString("repeat_mode", "off"),
                shuffleEnabled = data.optBoolean("shuffle_enabled", false),
                name = data.optString("display_name", data.optString("name", "")),
                active = data.optBoolean("active", false) ||
                    state.equals("playing", ignoreCase = true) ||
                    state.equals("paused", ignoreCase = true),
                elapsedTimeSec = elapsed,
                elapsedTimeLastUpdatedSec = lastUpdated,
                durationSec = durationSec,
                state = state,
                currentItemId = currentParsed?.queueItemId
                    ?: currentItem?.optString("queue_item_id", "").orEmpty(),
                currentTitle = currentParsed?.title.orEmpty(),
                currentArtist = currentParsed?.artist.orEmpty(),
                currentImageUrl = currentParsed?.imageUrl.orEmpty(),
                autoplayEnabled = autoplay,
                crossfadeEnabled = data.optBoolean("crossfade_enabled", false),
            )
        }

        /**
         * `queue_time_updated`: object_id = queue_id, data = elapsed seconds (primitive).
         */
        fun parseQueueTimeUpdated(event: ServerEvent): Pair<String, Double>? {
            val queueId = event.objectId?.ifBlank { null } ?: return null
            val elapsed = event.dataNumber
                ?: event.data?.optDouble("elapsed_time", Double.NaN)?.takeIf { !it.isNaN() }
                ?: return null
            if (elapsed < 0.0) return null
            return queueId to elapsed
        }

        /**
         * Convert MA server wall seconds to a local wall ms suitable for age math.
         * Clamps future timestamps to now (same defensive rule as massdroid / MA web).
         */
        fun serverWallSecondsToLocalMs(serverWallSeconds: Double): Long {
            val candidate = (serverWallSeconds * 1000.0).toLong()
            val now = System.currentTimeMillis()
            return if (candidate > now) now else candidate
        }

        private fun parseQueueArray(arr: JSONArray, baseUrl: String = ""): List<QueueTransport> {
            val out = ArrayList<QueueTransport>(arr.length())
            for (i in 0 until arr.length()) {
                parseQueueTransport(arr.optJSONObject(i), baseUrl)?.let { out.add(it) }
            }
            return out
        }

        private fun parsePlayerArray(arr: JSONArray): List<MassPlayer> {
            val out = ArrayList<MassPlayer>(arr.length())
            for (i in 0 until arr.length()) {
                parsePlayer(arr.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }

        fun parsePlayer(data: JSONObject?): MassPlayer? {
            if (data == null) return null
            val id = data.optString("player_id").ifBlank { return null }
            val media = data.optJSONObject("current_media")
            // MA: older `group_childs`, newer `group_members` — leader lists peer ids.
            val childList = parseIdList(data.optJSONArray("group_members"))
                .ifEmpty { parseIdList(data.optJSONArray("group_childs")) }
            val title = media?.optString("title")?.takeIf { it.isNotBlank() }
                ?: media?.optString("name")?.takeIf { it.isNotBlank() }
                ?: ""
            val artist = when {
                media == null -> ""
                media.optString("artist").isNotBlank() -> media.optString("artist")
                else -> {
                    val artists = media.optJSONArray("artists")
                    if (artists != null && artists.length() > 0) {
                        artists.optJSONObject(0)?.optString("name", "").orEmpty()
                            .ifBlank { artists.optString(0) }
                    } else {
                        ""
                    }
                }
            }
            return MassPlayer(
                playerId = id,
                displayName = data.optString("display_name", data.optString("name", id)),
                volumeLevel = data.optInt("volume_level", 0),
                groupVolume = if (data.has("group_volume") && !data.isNull("group_volume")) {
                    data.optInt("group_volume")
                } else {
                    null
                },
                syncedTo = optPlayerRef(data, "synced_to"),
                groupChilds = childList,
                available = data.optBoolean("available", true),
                enabled = data.optBoolean("enabled", true),
                state = data.optString("playback_state", data.optString("state", "idle")),
                trackTitle = title,
                trackArtist = artist,
                activeSource = optPlayerRef(data, "active_source"),
            )
        }

        /**
         * Optional player / queue id. Android [JSONObject.optString] turns JSON
         * `null` into the four-character string `"null"` — that must not become
         * a real [MassPlayer.syncedTo] or every unsynced peer looks grouped.
         */
        private fun optPlayerRef(data: JSONObject, key: String): String? {
            if (!data.has(key) || data.isNull(key)) return null
            return asPlayerRef(data.optString(key))
        }

        private fun asPlayerRef(raw: String?): String? {
            val id = raw?.trim().orEmpty()
            if (id.isEmpty() ||
                id.equals("null", ignoreCase = true) ||
                id.equals("undefined", ignoreCase = true)
            ) {
                return null
            }
            return id
        }

        private fun parseIdList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    if (arr.isNull(i)) continue
                    asPlayerRef(arr.optString(i))?.let { add(it) }
                }
            }
        }

        private fun parseQueueItemArray(arr: JSONArray, baseUrl: String = ""): List<MassQueueItem> {
            val out = ArrayList<MassQueueItem>(arr.length())
            for (i in 0 until arr.length()) {
                parseQueueItem(arr.optJSONObject(i), baseUrl = baseUrl)?.let { out.add(it) }
            }
            return out
        }

        fun parseQueueItem(
            data: JSONObject?,
            isCurrent: Boolean = false,
            baseUrl: String = "",
        ): MassQueueItem? {
            if (data == null) return null
            val id = data.optString("queue_item_id").ifBlank { return null }
            val media = data.optJSONObject("media_item")
            val title = media?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?: data.optString("name", "Unknown")
            val artists = media?.optJSONArray("artists")
            val artist = when {
                artists != null && artists.length() > 0 ->
                    artists.optJSONObject(0)?.optString("name", "").orEmpty()
                        .ifBlank { artists.optString(0) }
                media?.optString("artist").orEmpty().isNotBlank() ->
                    media.optString("artist")
                else -> ""
            }
            return MassQueueItem(
                queueItemId = id,
                title = title,
                artist = artist,
                durationSec = data.optDouble("duration", 0.0),
                isCurrent = isCurrent,
                mediaItemId = media?.optString("item_id", "").orEmpty(),
                provider = media?.optString("provider", "").orEmpty(),
                uri = media?.optString("uri", "").orEmpty(),
                imageUrl = resolveMediaImageUrl(media, baseUrl),
                favorite = media?.optBoolean("favorite", false) == true,
                mediaType = media?.optString("media_type", "track")
                    ?.ifBlank { "track" }
                    ?: "track",
                libraryItemId = when {
                    media?.optString("provider") == "library" ->
                        media.optString("item_id", "")
                    else -> ""
                },
            )
        }

        /**
         * Build a fetchable cover URL from MA `media_item.image` / `metadata.images`.
         * Prefer remotely_accessible http(s); else imageproxy via proxy_id / legacy path.
         */
        fun resolveMediaImageUrl(media: JSONObject?, baseUrl: String): String {
            if (media == null) return ""
            media.optJSONObject("image")?.let { img ->
                resolveMediaItemImage(img, baseUrl)?.let { return it }
            }
            val images = media.optJSONObject("metadata")?.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    resolveMediaItemImage(images.optJSONObject(i), baseUrl)?.let { return it }
                }
            }
            val bare = media.optString("image", "")
            if (bare.startsWith("http://") || bare.startsWith("https://")) return bare
            return ""
        }

        private fun resolveMediaItemImage(img: JSONObject?, baseUrl: String): String? {
            if (img == null) return null
            val path = img.optString("path", "")
            if (img.optBoolean("remotely_accessible", false) &&
                (path.startsWith("http://") || path.startsWith("https://"))
            ) {
                return path
            }
            val base = baseUrl.trimEnd('/')
            if (base.isBlank()) {
                return path.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            val proxyId = img.optString("proxy_id", "")
            if (proxyId.isNotBlank()) {
                return "$base/imageproxy/$proxyId?size=500"
            }
            val provider = img.optString("provider", "")
            if (path.isNotBlank() && provider.isNotBlank()) {
                return "$base/imageproxy?path=${android.net.Uri.encode(path)}" +
                    "&provider=${android.net.Uri.encode(provider)}&size=500"
            }
            return path.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

        private fun parsePlaylistArray(
            arr: JSONArray,
            baseUrl: String = "",
            includeImage: Boolean = false,
        ): List<MassPlaylist> {
            val out = ArrayList<MassPlaylist>(arr.length())
            for (i in 0 until arr.length()) {
                parsePlaylist(
                    arr.optJSONObject(i),
                    baseUrl = baseUrl,
                    includeImage = includeImage,
                )?.let { out.add(it) }
            }
            return out
        }

        fun parsePlaylist(
            data: JSONObject?,
            baseUrl: String = "",
            includeImage: Boolean = false,
        ): MassPlaylist? {
            if (data == null) return null
            val id = data.optString("item_id").ifBlank {
                data.optString("uri").ifBlank { return null }
            }
            val meta = data.optJSONObject("metadata")
            val count = when {
                data.has("track_count") && !data.isNull("track_count") -> data.optInt("track_count")
                meta != null && meta.has("track_count") -> meta.optInt("track_count")
                else -> null
            }
            // org.json gotcha: optString returns the LITERAL "null" for JSON null.
            val description = (if (data.isNull("description")) "" else data.optString("description"))
                .ifBlank {
                    meta?.takeUnless { it.isNull("description") }
                        ?.optString("description").orEmpty()
                }
            val imageUrl = if (includeImage) {
                resolveMediaImageUrl(data, baseUrl)
            } else {
                ""
            }
            return MassPlaylist(
                itemId = id,
                name = data.optString("name", id),
                provider = data.optString("provider", ""),
                uri = data.optString("uri", ""),
                trackCount = count,
                description = description,
                imageUrl = imageUrl,
            )
        }

        /**
         * Media types requested from `music/search`, and the order results are shown in.
         * Tracks lead because that is what the search box is for; each entry maps the
         * response bucket key to the `media_type` stamped on its rows.
         */
        private val SEARCH_BUCKETS = listOf(
            "tracks" to "track",
            "albums" to "album",
            "playlists" to "playlist",
        )
        /** Parsed when present; not in the default rail search types. */
        private val EXTRA_SEARCH_BUCKETS = listOf(
            "artists" to "artist",
            "artist" to "artist",
            "radios" to "radio",
            "radio" to "radio",
        )
        val SEARCH_MEDIA_TYPES: List<String> = SEARCH_BUCKETS.map { it.second }

        private fun parseSearchResults(data: JSONObject, baseUrl: String): List<MassSearchItem> {
            val out = ArrayList<MassSearchItem>()
            // The same recording often comes back from several providers; keep the first
            // (MA orders library hits ahead of streaming ones).
            val seen = HashSet<String>()
            for ((bucket, mediaType) in SEARCH_BUCKETS + EXTRA_SEARCH_BUCKETS) {
                val arr = data.optJSONArray(bucket) ?: continue
                for (i in 0 until arr.length()) {
                    val item = parseSearchItem(arr.optJSONObject(i), mediaType, baseUrl) ?: continue
                    if (seen.add(item.uri)) out.add(item)
                }
            }
            return out
        }

        private fun parseSearchItem(
            data: JSONObject?,
            mediaType: String,
            baseUrl: String,
        ): MassSearchItem? {
            if (data == null) return null
            val uri = data.optString("uri", "").ifBlank { return null }
            val meta = data.optJSONObject("metadata")
            val count = when {
                data.has("track_count") && !data.isNull("track_count") -> data.optInt("track_count")
                meta != null && meta.has("track_count") -> meta.optInt("track_count")
                else -> null
            }
            return MassSearchItem(
                uri = uri,
                name = data.optString("name", "").ifBlank { uri },
                // Trust the bucket over the payload: some providers omit media_type.
                mediaType = data.optString("media_type", mediaType).ifBlank { mediaType },
                artist = primaryArtistName(data),
                album = albumName(data, mediaType),
                provider = data.optString("provider", ""),
                imageUrl = resolveMediaImageUrl(data, baseUrl),
                durationSec = if (data.isNull("duration")) 0.0 else data.optDouble("duration", 0.0),
                trackCount = count,
            )
        }

        /** Parent album on a track; album/playlist rows already use [MassSearchItem.name]. */
        private fun albumName(media: JSONObject, mediaType: String): String {
            if (mediaType == "album") return ""
            val obj = media.optJSONObject("album")
            if (obj != null) {
                return obj.optString("name", "").ifBlank { obj.optString("title", "") }
            }
            val raw = media.optString("album", "")
            return if (raw.isBlank() || raw == "null") "" else raw
        }

        /** MA sends credits as an `artists` array of objects, or a flat `artist` string. */
        private fun primaryArtistName(media: JSONObject): String {
            val artists = media.optJSONArray("artists")
            if (artists != null && artists.length() > 0) {
                val named = artists.optJSONObject(0)?.optString("name", "").orEmpty()
                return named.ifBlank { artists.optString(0, "") }
            }
            return media.optString("artist", "")
        }

        private fun parsePlaylistTrackArray(arr: JSONArray): List<MassPlaylistTrack> {
            val out = ArrayList<MassPlaylistTrack>(arr.length())
            for (i in 0 until arr.length()) {
                parsePlaylistTrack(arr.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }

        fun parsePlaylistTrack(data: JSONObject?): MassPlaylistTrack? {
            if (data == null) return null
            // Mixed playlist items may wrap media under media_item.
            val media = data.optJSONObject("media_item") ?: data
            val id = media.optString("item_id").ifBlank {
                media.optString("uri").ifBlank {
                    data.optString("uri").ifBlank { return null }
                }
            }
            val artists = media.optJSONArray("artists")
            val artist = when {
                artists != null && artists.length() > 0 ->
                    artists.optJSONObject(0)?.optString("name", "").orEmpty()
                        .ifBlank { artists.optString(0) }
                media.optString("artist").isNotBlank() -> media.optString("artist")
                else -> ""
            }
            val duration = when {
                media.has("duration") && !media.isNull("duration") ->
                    media.optDouble("duration", 0.0)
                data.has("duration") && !data.isNull("duration") ->
                    data.optDouble("duration", 0.0)
                else -> 0.0
            }
            return MassPlaylistTrack(
                itemId = id,
                name = media.optString("name", id).ifBlank { id },
                artist = artist,
                uri = media.optString("uri", data.optString("uri", "")),
                durationSec = duration,
            )
        }

        /**
         * massdroid [LyricsProvider.normalizeLyricsResult]: prefer timed LRC;
         * if the "lrc" slot is plain prose, demote; if plain looks like LRC, promote.
         */
        fun pickSyncedLrc(plain: String?, lrc: String?): String? {
            var normalizedPlain = plain?.takeIf { it.isNotBlank() }
            var normalizedLrc = lrc?.takeIf { it.isNotBlank() }
            if (normalizedLrc != null && !looksLikeLrc(normalizedLrc)) {
                if (normalizedPlain == null) normalizedPlain = normalizedLrc
                normalizedLrc = null
            } else if (normalizedLrc == null && normalizedPlain != null && looksLikeLrc(normalizedPlain)) {
                normalizedLrc = normalizedPlain
            }
            return normalizedLrc
        }

        private val LRC_LINE_PATTERN = Regex("""\[\d+:\d+[.:]\d+]""")

        private fun looksLikeLrc(text: String): Boolean {
            val firstLines = text.lineSequence().take(5).toList()
            return firstLines.count { LRC_LINE_PATTERN.containsMatchIn(it) } >= 2
        }
    }
}

class MassApiException(message: String, val code: Int) : Exception(message)
