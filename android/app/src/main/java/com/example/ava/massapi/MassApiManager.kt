package com.example.ava.massapi

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.KeyChain
import android.util.Log
import com.example.ava.sendspin.SendspinManager
import com.example.ava.settings.MassApiSettings
import com.example.ava.settings.MassApiSettingsStore
import com.example.ava.settings.massApiSettingsStore
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaSyncOffsetPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Music Assistant **WebSocket API** side-channel for frontend queue scheduling UI
 * (repeat / shuffle via `queue_updated`, optional progress via
 * `elapsed_time` / `queue_time_updated`).
 *
 * **Exclusive progress paint (must stay true):**
 * - **No Mass API** (disabled / disconnected / no queue clock): Sendspin alone
 *   owns vinyl progress, seek, and PCM.
 * - **Mass API connected with a bound queue clock**: Mass takes over vinyl
 *   progress paint via [SendspinManager.applyQueueProgressUiBridge]; Sendspin
 *   keeps PCM / 首/活尾 internally. Never seek/PCM from Mass — elapsed
 *   already seats the bar; seeking only restarts Queue Flow (false pauses).
 * - Queue `state` may only promote playing; never freeze the bar on idle/paused
 *   flashes. Real pause follows Sendspin.
 * - Voice/TTS overlay duck is **1:1 with [SendspinManager.duck]**: attenuate only,
 *   never pause/play. Mass has no separate PCM path — it forwards to the bound
 *   Sendspin player so MA sessions share the same overlay state as SP-only.
 * - HA media_player coordination mirrors the SP service path: duck/restore HA
 *   volume on voice overlay, mirror-title guards, and HA SHOW blocking while
 *   Mass/SP owns the session. Never pause HA or send Mass play/pause for duck.
 */
class MassApiManager private constructor(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val settingsStore = MassApiSettingsStore(appContext.massApiSettingsStore)
    private val client = MassApiClient(scope)

    /** Read-only handle to paint UI; never used to drive audio/seek. */
    @Volatile
    var sendspinProvider: () -> SendspinManager? = { null }

    /**
     * Voice/TTS overlay latch — same role as [SendspinManager]'s `isDucked`.
     * True while listening/TTS should keep music attenuated under speech.
     * HA media_player volume ducking is NOT done here: the SP voice path
     * ([VoiceSatellite.duckHaMediaPlayerVolume]) already covers every source,
     * so duplicating it from Mass would double HA `volume_set` traffic and
     * race the synchronous restore at conversation end.
     */
    @Volatile
    private var isDucked = false

    /** Guards the check-then-act on [isDucked]; callers arrive from mixed threads. */
    private val duckLock = Any()

    /** Bound queue current title — HA mirror / SHOW guards (1:1 with SP cached title). */
    @Volatile
    private var boundCurrentTitle: String = ""

    /**
     * Bumped on intentional identity wipe (clear queue / unsync). In-flight queue
     * list fetches must not paint stale title/artist after the bump.
     */
    @Volatile
    private var mediaIdentityEpoch: Int = 0

    val connectionState: StateFlow<MassApiClient.ConnectionState> = client.connectionState

    private val _players = MutableStateFlow<List<MassPlayer>>(emptyList())
    val players: StateFlow<List<MassPlayer>> = _players.asStateFlow()

    private val _queueItems = MutableStateFlow<List<MassQueueItem>>(emptyList())
    val queueItems: StateFlow<List<MassQueueItem>> = _queueItems.asStateFlow()

    private val _playlists = MutableStateFlow<List<MassPlaylist>>(emptyList())
    val playlists: StateFlow<List<MassPlaylist>> = _playlists.asStateFlow()

    private val _activePlayerId = MutableStateFlow("")
    val activePlayerId: StateFlow<String> = _activePlayerId.asStateFlow()

    private val _sleepMinutes = MutableStateFlow(0)
    val sleepMinutes: StateFlow<Int> = _sleepMinutes.asStateFlow()

    private val _autoplayEnabled = MutableStateFlow(false)
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

    private val _crossfadeEnabled = MutableStateFlow(false)
    val crossfadeEnabled: StateFlow<Boolean> = _crossfadeEnabled.asStateFlow()

    /**
     * Rail search. Unlike players / queues / playlists these do not arrive as server
     * events — `music/search` is request-response, so the latest answer is held here.
     */
    private val _searchResults = MutableStateFlow<List<MassSearchItem>>(emptyList())
    val searchResults: StateFlow<List<MassSearchItem>> = _searchResults.asStateFlow()

    private val _searchBusy = MutableStateFlow(false)
    val searchBusy: StateFlow<Boolean> = _searchBusy.asStateFlow()

    private var searchJob: Job? = null
    /**
     * Bumped by every [searchAsync] / [clearSearch]. A request that is no longer the
     * newest must not publish its results or clear the spinner the newest one owns —
     * cancellation alone cannot guarantee that, since a job can be cancelled mid-IO.
     */
    private var searchSeq = 0

    private var settingsJob: Job? = null
    private var eventsJob: Job? = null
    private var connectedJob: Job? = null
    private var sleepJob: Job? = null

    /** Queue id we paint transport/progress for (this Ava Sendspin player when possible). */
    @Volatile
    private var boundQueueId: String? = null

    /** True when the bind followed MA active_source (synced follower → leader queue). */
    private var boundViaActiveSource: Boolean = false

    /** Last active_source we tried to rebind to — loop guard for non-queue sources. */
    private var lastRebindAttemptSrc: String? = null

    /**
     * Last MA sync source we observed ([activeSourceQueueId]). Used to detect a
     * fresh join / leader switch so we can seed title/artist/cover immediately
     * instead of waiting for a mid-track metadata re-push that often never comes.
     */
    private var lastKnownSyncSourceId: String? = null

    /** Cached identity — survives Sendspin restarts / late binding. */
    @Volatile
    private var cachedSelfPlayerId: String = ""

    private var lastBoundCurrentItemId: String = ""
    private var lyricsPrefetchJob: Job? = null

    /**
     * After auto-next / crossfade [itemChanged], reject late [queue_time_updated]
     * clocks from the previous track (e.g. 4:04) that would thrash the bar
     * 0 ↔ end. Wall window only — no protocol seek.
     */
    private var progressReseatGraceUntilElapsed: Long = 0L
    /** Last progress we accepted onto the Mass UI bridge (for stale jump detect). */
    private var lastAcceptedBridgeProgressMs: Long = 0L
    /** Duration last painted on the bridge — previous-track tail detect. */
    private var lastBridgedDurationMs: Long = 0L
    /**
     * Playhead / duration captured at [itemChanged], before we reseat.
     * Grace must drop ticks near *this* tail (e.g. 4:04), not every value
     * above 4s — that rejected the user's real mid-track elapsed.
     */
    private var preReseatProgressMs: Long = 0L
    private var preReseatDurationMs: Long = 0L

    /**
     * Coalesce MA [itemChanged] bursts (unplayable skip storms). Each skip used
     * to paint 0:00 + a new duration and arm Sendspin rescue play, which then
     * retried `play_index` and kept the storm going. One change waits
     * [ITEM_CHANGE_COALESCE_MS]; another within [SKIP_STORM_GAP_MS] becomes a
     * storm and waits [SKIP_STORM_SETTLE_MS] for the last item.
     */
    private var itemChangeCoalesceJob: Job? = null
    private var pendingItemChange: MassApiClient.QueueTransport? = null
    private var itemChangeBurstCount: Int = 0
    /** Wall time of the last [itemChanged], for chaining skips that land after coalesce. */
    private var lastItemChangeElapsed: Long = 0L

    fun boundQueueId(): String? = boundQueueId

    /**
     * Queue neighbors around the current item (prev [radius] + next [radius]),
     * excluding the current track. Empty when unbound / no current marker.
     */
    fun neighborQueueItems(radius: Int = 2): List<MassQueueItem> {
        if (radius <= 0) return emptyList()
        val items = _queueItems.value
        if (items.isEmpty()) return emptyList()
        val idx = items.indexOfFirst { it.isCurrent }
        if (idx < 0) return emptyList()
        val out = ArrayList<MassQueueItem>(radius * 2)
        for (d in 1..radius) {
            items.getOrNull(idx - d)?.let(out::add)
            items.getOrNull(idx + d)?.let(out::add)
        }
        return out
    }

    /**
     * Synced LRC for a concrete queue [item] (media_item id + provider).
     * Used by neighbor prefetch; skips title matching.
     */
    suspend fun fetchSyncedLrcForItem(item: MassQueueItem): String? {
        if (client.connectionState.value !is MassApiClient.ConnectionState.Connected) {
            return null
        }
        if (item.mediaItemId.isBlank() || item.provider.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                client.fetchTrackSyncedLrc(item.mediaItemId, item.provider)
            } catch (e: Exception) {
                Log.w(TAG, "fetchSyncedLrcForItem failed: ${e.message}")
                null
            }
        }
    }

    /** Debounced MA neighbor lyrics warm (memory + cacheDir temp). */
    private fun scheduleNeighborLyricsPrefetch() {
        if (client.connectionState.value !is MassApiClient.ConnectionState.Connected) return
        lyricsPrefetchJob?.cancel()
        lyricsPrefetchJob = scope.launch {
            delay(350)
            runCatching {
                com.example.ava.lyrics.LyricsRepository.prefetchMassNeighborLyrics(appContext, radius = 2)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Neighbor lyrics prefetch failed: ${e.message}")
            }
        }
    }

    /**
     * Synced LRC for the bound queue track matching [title]/[artist], via the
     * massdroid lyrics path. No-op when Mass API is disabled / disconnected —
     * callers fall through to QQ/LRCLIB.
     */
    suspend fun fetchSyncedLrcForTrack(title: String, artist: String = ""): String? {
        if (client.connectionState.value !is MassApiClient.ConnectionState.Connected) {
            return null
        }
        val t = title.trim()
        if (t.isEmpty()) return null
        if (_queueItems.value.isEmpty()) {
            // Lyrics often load before the rail refresh; pull once, then match.
            runCatching { refreshRailQueues() }
        }
        val item = resolveLyricsQueueItem(t, artist) ?: return null
        if (item.mediaItemId.isBlank() || item.provider.isBlank()) {
            Log.d(TAG, "lyrics skip: no media_item id/provider for «$t»")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                client.fetchTrackSyncedLrc(item.mediaItemId, item.provider)
            } catch (e: Exception) {
                Log.w(TAG, "fetchSyncedLrcForTrack failed: ${e.message}")
                null
            }
        }
    }

    private fun resolveLyricsQueueItem(title: String, artist: String): MassQueueItem? {
        val items = _queueItems.value
        if (items.isEmpty()) return null
        val wantTitle = title.trim().lowercase()
        val wantArtist = artist.trim().lowercase()
        val current = items.firstOrNull { it.isCurrent }
        if (current != null && titlesMatch(current.title, wantTitle)) {
            if (wantArtist.isEmpty() ||
                current.artist.isBlank() ||
                current.artist.trim().lowercase().contains(wantArtist) ||
                wantArtist.contains(current.artist.trim().lowercase())
            ) {
                return current
            }
        }
        return items.firstOrNull { item ->
            titlesMatch(item.title, wantTitle) &&
                (wantArtist.isEmpty() ||
                    item.artist.isBlank() ||
                    item.artist.trim().lowercase().contains(wantArtist) ||
                    wantArtist.contains(item.artist.trim().lowercase()))
        }
    }

    private fun titlesMatch(a: String, wantLower: String): Boolean {
        val left = a.trim().lowercase()
        if (left.isEmpty() || wantLower.isEmpty()) return false
        return left == wantLower || left.contains(wantLower) || wantLower.contains(left)
    }

    fun start() {
        if (settingsJob != null) return
        Log.i(TAG, "Starting Mass API side-channel")
        settingsJob = scope.launch {
            // massdroid: restore KeyChain mTLS before any connect attempt.
            val savedAlias = settingsStore.get().clientCertAlias
            if (savedAlias.isNotBlank()) {
                loadCertificate(savedAlias, appContext)
            }
            settingsStore.getFlow()
                .map {
                    AuthSnapshot(
                        enabled = it.enabled,
                        serverUrl = it.serverUrl,
                        authToken = it.authToken,
                        username = it.username,
                        password = it.password,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { snap ->
                    if (snap.username.isNotBlank() && snap.password.isNotBlank()) {
                        client.setSavedCredentials(snap.username, snap.password)
                    } else {
                        client.clearSavedCredentials()
                    }
                    if (!snap.enabled || snap.serverUrl.isBlank()) {
                        // Sign-in UI / signOut owns disconnect; avoid fighting an in-flight login.
                        if (client.connectionState.value !is MassApiClient.ConnectionState.Connecting) {
                            client.disconnect()
                            clearProgressBridge()
                        }
                        return@collectLatest
                    }
                    // Already up (e.g. token just persisted after login) — do not bounce the socket.
                    if (client.connectionState.value is MassApiClient.ConnectionState.Connected ||
                        client.connectionState.value is MassApiClient.ConnectionState.Connecting
                    ) {
                        return@collectLatest
                    }
                    when {
                        snap.authToken.isNotBlank() -> client.connect(snap.serverUrl, snap.authToken)
                        snap.username.isNotBlank() && snap.password.isNotBlank() -> {
                            client.connectWithLogin(snap.serverUrl, snap.username, snap.password) { token ->
                                scope.launch { settingsStore.authToken.set(token) }
                            }
                        }
                    }
                }
        }
        eventsJob = scope.launch {
            client.events.collect { event ->
                when (event.event) {
                    MassApiClient.EVENT_QUEUE_UPDATED -> {
                        val transport = MassApiClient.parseQueueTransport(
                            event.data,
                            baseUrl = client.serverBaseUrl(),
                        ) ?: return@collect
                        onQueueUpdated(transport)
                        scope.launch { refreshRailQueues() }
                    }
                    MassApiClient.EVENT_QUEUE_TIME_UPDATED -> {
                        val (queueId, elapsedSec) = MassApiClient.parseQueueTimeUpdated(event)
                            ?: return@collect
                        onQueueTimeUpdated(queueId, elapsedSec)
                    }
                    "players_updated", "player_added", "player_removed", "player_updated" -> {
                        scope.launch {
                            refreshRailPlayers()
                            // Sync pairing changed mid-session → the queue that
                            // feeds us may have moved (leader's ↔ our own).
                            maybeRebindForSyncChange()
                        }
                    }
                }
            }
        }
        connectedJob = scope.launch {
            client.connectionState.collectLatest { state ->
                when (state) {
                    is MassApiClient.ConnectionState.Connected -> {
                        refreshQueuesOnce()
                        refreshRail()
                    }
                    is MassApiClient.ConnectionState.Disconnected,
                    is MassApiClient.ConnectionState.Error,
                    -> {
                        boundQueueId = null
                        boundViaActiveSource = false
                        lastRebindAttemptSrc = null
                        lastKnownSyncSourceId = null
                        lastBoundCurrentItemId = ""
                        boundCurrentTitle = ""
                        clearProgressBridge()
                        _players.value = emptyList()
                        _queueItems.value = emptyList()
                        _playlists.value = emptyList()
                        clearSearch()
                        _activePlayerId.value = ""
                        _autoplayEnabled.value = false
                        _crossfadeEnabled.value = false
                    }
                    is MassApiClient.ConnectionState.Connecting -> Unit
                }
            }
        }
    }

    fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        lyricsPrefetchJob?.cancel()
        lyricsPrefetchJob = null
        eventsJob?.cancel()
        eventsJob = null
        connectedJob?.cancel()
        connectedJob = null
        boundQueueId = null
        lastBoundCurrentItemId = ""
        boundCurrentTitle = ""
        sleepJob?.cancel()
        sleepJob = null
        clearSearch()
        _sleepMinutes.value = 0
        _autoplayEnabled.value = false
        _crossfadeEnabled.value = false
        // Drop overlay latch; restore SP attenuation if we were holding duck.
        unDuck()
        clearProgressBridge()
        client.close()
    }

    /** massdroid: username/password → auth/login → persist token. */
    suspend fun login(serverUrl: String, username: String, password: String) {
        settingsStore.update {
            it.copy(
                enabled = true,
                serverUrl = MassApiSettingsStore.normalizeServerUrl(serverUrl),
                username = username.trim(),
                password = password,
                authToken = "",
            )
        }
        client.setSavedCredentials(username.trim(), password)
        client.connectWithLogin(serverUrl, username.trim(), password) { token ->
            scope.launch { settingsStore.authToken.set(token) }
        }
    }

    /** massdroid: blank creds + saved token → connect with token. */
    suspend fun connectWithToken(serverUrl: String) {
        val token = settingsStore.get().authToken
        if (token.isBlank()) return
        settingsStore.enabled.set(true)
        settingsStore.serverUrl.set(serverUrl)
        client.connect(serverUrl, token)
    }

    /** massdroid signOut: wipe sign-in, keep server URL. */
    suspend fun signOut() {
        client.disconnect()
        client.clearSavedCredentials()
        boundQueueId = null
        lastBoundCurrentItemId = ""
        clearProgressBridge()
        settingsStore.update {
            it.copy(
                enabled = false,
                authToken = "",
                username = "",
                password = "",
            )
        }
    }

    suspend fun currentSettings(): MassApiSettings = settingsStore.get()

    fun settingsFlow() = settingsStore.getFlow()

    /** massdroid: KeyChain alias picked → persist + configure OkHttp mTLS. */
    fun onCertificateSelected(alias: String?, context: Context) {
        if (alias.isNullOrBlank()) return
        scope.launch {
            deletePkcs12File()
            settingsStore.update {
                it.copy(clientCertAlias = alias, clientCertPassword = "")
            }
            loadCertificate(alias, context.applicationContext)
        }
    }

    /**
     * Copy a PKCS#12 into app-private storage (kiosk / no KeyChain path).
     * @return false if the password is wrong or the file has no private key.
     */
    suspend fun importPkcs12(bytes: ByteArray, password: String): Boolean {
        val parsed = withContext(Dispatchers.IO) { readPkcs12(bytes, password) } ?: return false
        withContext(Dispatchers.IO) {
            pkcs12File().writeBytes(bytes)
        }
        settingsStore.update {
            it.copy(
                clientCertAlias = MassApiClientCertChooser.PKCS12_ALIAS,
                clientCertPassword = password,
            )
        }
        client.configureMtls(parsed.first, parsed.second)
        Log.d(TAG, "mTLS loaded from PKCS#12")
        return true
    }

    fun loadSavedCertificate() {
        scope.launch {
            val settings = settingsStore.get()
            if (settings.clientCertAlias.isNotBlank()) {
                loadCertificate(settings.clientCertAlias, appContext)
            }
        }
    }

    fun clearCertificate() {
        scope.launch {
            deletePkcs12File()
            settingsStore.update {
                it.copy(clientCertAlias = "", clientCertPassword = "")
            }
            client.clearMtls()
        }
    }

    private suspend fun loadCertificate(alias: String, context: Context) {
        withContext(Dispatchers.IO) {
            try {
                if (alias == MassApiClientCertChooser.PKCS12_ALIAS) {
                    val settings = settingsStore.get()
                    val file = pkcs12File()
                    if (!file.isFile) {
                        Log.e(TAG, "PKCS#12 file missing")
                        settingsStore.update {
                            it.copy(clientCertAlias = "", clientCertPassword = "")
                        }
                        client.clearMtls()
                        return@withContext
                    }
                    val parsed = readPkcs12(file.readBytes(), settings.clientCertPassword)
                    if (parsed != null) {
                        client.configureMtls(parsed.first, parsed.second)
                        Log.d(TAG, "mTLS loaded: PKCS#12")
                    } else {
                        Log.e(TAG, "Failed to load PKCS#12")
                        settingsStore.update {
                            it.copy(clientCertAlias = "", clientCertPassword = "")
                        }
                        client.clearMtls()
                    }
                    return@withContext
                }
                val privateKey = KeyChain.getPrivateKey(context, alias)
                val certChain = KeyChain.getCertificateChain(context, alias)
                if (privateKey != null && certChain != null) {
                    client.configureMtls(privateKey, certChain)
                    Log.d(TAG, "mTLS loaded: $alias")
                } else {
                    Log.e(TAG, "Failed to load cert for alias: $alias")
                    settingsStore.clientCertAlias.set("")
                    client.clearMtls()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cert: ${e.message}")
                settingsStore.update {
                    it.copy(clientCertAlias = "", clientCertPassword = "")
                }
                client.clearMtls()
            }
        }
    }

    private fun pkcs12File(): File =
        File(appContext.filesDir, MassApiClientCertChooser.PKCS12_FILE)

    private fun deletePkcs12File() {
        runCatching { pkcs12File().delete() }
    }

    private fun readPkcs12(
        bytes: ByteArray,
        password: String,
    ): Pair<PrivateKey, Array<X509Certificate>>? {
        return try {
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(ByteArrayInputStream(bytes), password.toCharArray())
            val keyAlias = keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
                ?: return null
            val key = keyStore.getKey(keyAlias, password.toCharArray()) as? PrivateKey
                ?: return null
            val chain = keyStore.getCertificateChain(keyAlias)
                ?.mapNotNull { it as? X509Certificate }
                ?.toTypedArray()
                ?: return null
            if (chain.isEmpty()) return null
            key to chain
        } catch (e: Exception) {
            Log.e(TAG, "PKCS#12 parse failed: ${e.message}")
            null
        }
    }

    fun isConnected(): Boolean =
        client.connectionState.value is MassApiClient.ConnectionState.Connected

    /** Refresh players / Up Next / Library for the overlay rail. */
    fun refreshRail() {
        scope.launch {
            refreshRailPlayers()
            refreshRailQueues()
            refreshRailPlaylists()
        }
    }

    suspend fun refreshRailPlayers() {
        val raw = withContext(Dispatchers.IO) { client.fetchPlayers() }
            .filter { it.available && it.enabled }
        val selfId = resolveSelfPlayerId(raw)
        // Always pin this device first — never trust MA list ranking.
        val list = if (selfId.isBlank()) {
            raw
        } else {
            raw.sortedWith(
                compareByDescending<MassPlayer> { it.playerId == selfId }
                    .thenBy { it.displayName.lowercase() },
            )
        }
        _players.value = list
        // Rail "自己" is this device or nothing. Adopting the first MA row as
        // self makes every pairing / peer check run against a stranger, which
        // is what put another device at the top of the rail. A stale cached id
        // is already covered inside [resolveSelfPlayerId], so a blank result
        // here means "genuinely unknown".
        _activePlayerId.value = selfId
        // A sync pairing made moments ago may be exactly the proof a blocked
        // Sendspin client is waiting for — let it re-judge right away.
        sendspinProvider()?.onMassPlayersChanged()
    }

    /**
     * Local Ava player id, strongest signal first.
     *
     * MA's `player_id` is whatever the Sendspin session identified itself as:
     * the X25519 key from `client/init` on an encrypted (MA 7.x) session, the
     * raw ANDROID_ID on a plaintext one — [SendspinManager.deviceClientId]
     * returns the one in use. `display_name` mirrors the announced Ava name.
     *
     * The live session id is tried first. Both transports may have left a row
     * in MA, and the one not in use is frozen at the last track it played;
     * adopting it as self is what pinned cover/artist/title on the rail.
     *
     * Returns "" when this device genuinely is not an MA player — callers must
     * never substitute another player for "self".
     */
    private fun resolveSelfPlayerId(players: List<MassPlayer>): String {
        if (players.isEmpty()) return cachedSelfPlayerId

        val sendspin = sendspinProvider()
        val spId = sendspin?.deviceClientId().orEmpty()
        matchByDeviceId(players, spId)?.let {
            cachedSelfPlayerId = it
            return it
        }

        val androidId = readAndroidId()
        if (androidId != spId) {
            matchByDeviceId(players, androidId)?.let {
                cachedSelfPlayerId = it
                return it
            }
        }

        // Beacon form, for a server that stores the `ava_<hash>` id directly.
        // [AvaVoiceDiscovery.stableAvaDeviceId] is a 32-bit String.hashCode, so
        // an ambiguous match must be rejected rather than resolved positionally
        // — a colliding peer would otherwise be adopted as self.
        val localAvaId = AvaVoiceDiscovery.resolveLocalDeviceId(appContext)
        if (localAvaId.isNotBlank()) {
            players.singleOrNull {
                localAvaId in AvaSyncOffsetPeer.avaIdCandidates(it.playerId)
            }?.playerId?.let {
                cachedSelfPlayerId = it
                return it
            }
        }

        // Name match: MA mirrors the name we announced and a rename re-announces,
        // so the two stay aligned. Unique match only, for the same reason.
        val label = sendspin?.clientDisplayName().orEmpty()
        if (label.isNotBlank()) {
            players.singleOrNull { it.displayName.equals(label, ignoreCase = true) }
                ?.playerId?.let {
                    cachedSelfPlayerId = it
                    return it
                }
        }

        if (cachedSelfPlayerId.isNotBlank() &&
            players.any { it.playerId == cachedSelfPlayerId }
        ) {
            return cachedSelfPlayerId
        }

        return ""
    }

    /**
     * The Mass player id standing for [deviceId], exact form preferred.
     *
     * Mass may namespace the client id it was handed — this device announces
     * ANDROID_ID `ce77…86b9` and Mass reports the player as `upce77…86b9` — so a
     * trailing match counts as the same device. Suffix hits must be unique:
     * ANDROID_ID is 64 random bits, so a second row ending the same way means the
     * server listed one device under two providers, and either seat is a guess.
     */
    private fun matchByDeviceId(players: List<MassPlayer>, deviceId: String): String? {
        if (deviceId.isBlank()) return null
        players.firstOrNull { it.playerId == deviceId }?.let { return it.playerId }
        return players.singleOrNull { it.playerId.endsWith(deviceId) }?.playerId
    }

    private fun readAndroidId(): String = try {
        Settings.Secure.getString(
            appContext.contentResolver, Settings.Secure.ANDROID_ID,
        ).orEmpty()
    } catch (_: Exception) { "" }

    /**
     * Re-run identity resolution after late Sendspin binding or
     * when the caller suspects the cached id is stale.
     */
    internal fun retryIdentityResolution() {
        if (connectionState.value !is MassApiClient.ConnectionState.Connected) return
        scope.launch { refreshRailPlayers() }
    }

    suspend fun refreshRailQueues() {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: _activePlayerId.value
        if (qid.isBlank()) {
            _queueItems.value = emptyList()
            return
        }
        val epochAtStart = mediaIdentityEpoch
        val items = withContext(Dispatchers.IO) { client.fetchQueueItems(qid) }
        if (epochAtStart != mediaIdentityEpoch) {
            _queueItems.value = emptyList()
            return
        }
        if (items == null) {
            // Transient RPC/WS failure (not a real empty queue): keep the current
            // overlay identity/progress instead of wiping to "waiting for media".
            return
        }
        val currentId = lastBoundCurrentItemId
        val hadContent =
            _queueItems.value.isNotEmpty() || currentId.isNotBlank()
        // Keep optimistic favorite + library id when MA queue payloads omit them.
        // Re-read live state at merge time so an unfavorite during fetch is not resurrected.
        val prevById = _queueItems.value.associateBy { it.queueItemId }
        _queueItems.value = items.map { item ->
            val prev = prevById[item.queueItemId]
            val live = _queueItems.value.firstOrNull { it.queueItemId == item.queueItemId }
            val favorite = when {
                item.favorite -> true
                // Locally cleared while this fetch was in flight — trust the clear.
                live != null && !live.favorite && prev?.favorite == true -> false
                prev?.favorite == true -> true
                else -> false
            }
            item.copy(
                isCurrent = currentId.isNotBlank() && item.queueItemId == currentId,
                favorite = favorite,
                libraryItemId = item.libraryItemId
                    .ifBlank { live?.libraryItemId.orEmpty() }
                    .ifBlank { prev?.libraryItemId.orEmpty() },
            )
        }
        // Upstream empty list after we had content — same soft waiting shell as the button.
        if (items.isEmpty() && hadContent) {
            wipeLocalPlaybackIdentity()
            return
        }
        if (epochAtStart != mediaIdentityEpoch) {
            // Cleared/unsynced while this fetch was in flight — do not resurrect.
            return
        }
        // Queue list often has fuller media_item than the thin queue_updated event —
        // fill missing artist/cover (and catch auto-next if event lacked title).
        items.firstOrNull { it.queueItemId == currentId && currentId.isNotBlank() }
            ?.let { applyTrackIdentityFromMa(it.title, it.artist, it.imageUrl, trackChanged = false) }
        scheduleNeighborLyricsPrefetch()
    }

    suspend fun refreshRailPlaylists() {
        _playlists.value = withContext(Dispatchers.IO) { client.fetchPlaylists() }
    }

    /**
     * Debounced search-as-you-type. MA applies no rate limit of its own and each query
     * fans out to every provider, so a new keystroke replaces the pending request rather
     * than queueing behind it. Short queries are dropped entirely — one or two letters
     * match most of a library and cost a full provider fan-out to say so.
     */
    /** Voice path: one-shot `music/search`, no debounce / min-length UI guard. */
    suspend fun searchNow(query: String, mediaTypes: List<String>? = null): List<MassSearchItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            if (mediaTypes.isNullOrEmpty()) client.searchMusic(trimmed)
            else client.searchMusic(trimmed, mediaTypes = mediaTypes)
        }
    }

    fun searchAsync(query: String, tracksOnly: Boolean = false) {
        searchJob?.cancel()
        val trimmed = query.trim()
        val seq = ++searchSeq
        if (trimmed.length < SEARCH_MIN_CHARS) {
            searchJob = null
            _searchBusy.value = false
            _searchResults.value = emptyList()
            return
        }
        searchJob = scope.launch {
            try {
                delay(SEARCH_DEBOUNCE_MS)
                _searchBusy.value = true
                val types = if (tracksOnly) listOf("track") else null
                val hits = withContext(Dispatchers.IO) {
                    if (types != null) {
                        client.searchMusic(trimmed, mediaTypes = types)
                    } else {
                        client.searchMusic(trimmed)
                    }
                }
                if (seq == searchSeq) _searchResults.value = hits
            } finally {
                // Only the newest request owns the spinner: a superseded one clearing it
                // would hide that its replacement is still working.
                if (seq == searchSeq) _searchBusy.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        searchSeq++
        _searchBusy.value = false
        _searchResults.value = emptyList()
    }

    /**
     * Detail header only — cover URL comes from here; bitmap load stays in UI
     * after the user opens the page.
     */
    suspend fun fetchPlaylistDetail(playlist: MassPlaylist): MassPlaylist? {
        if (playlist.itemId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            client.fetchPlaylistDetail(playlist.itemId, playlist.provider)
                ?: playlist
        }
    }

    /**
     * One MA `page` of playlist tracks. Callers window into the rail UI and only
     * request the next page after the user scrolls to the bottom (and any local
     * overflow buffer is drained).
     */
    suspend fun fetchPlaylistTracksPage(playlist: MassPlaylist, page: Int): List<MassPlaylistTrack> {
        if (playlist.itemId.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            client.fetchPlaylistTracks(playlist.itemId, playlist.provider, page = page)
        }
    }

    /**
     * Inject the playlist URI into the bound queue (`replace`). Never called from
     * list-row taps — only from the detail "Play playlist" action.
     */
    suspend fun playPlaylist(uri: String): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: _activePlayerId.value
        if (qid.isBlank() || uri.isBlank()) return false
        sendspinProvider()?.noteQueuedTrackStart()
        val ok = withContext(Dispatchers.IO) {
            client.playMedia(qid, listOf(uri), option = "replace")
        }
        if (ok) {
            delay(300)
            refreshRailQueues()
        }
        return ok
    }

    /**
     * Inject one track URI into the bound device queue and play it now
     * (`player_queues/play_media` option=`play`). Does not wipe the whole queue
     * like playlist replace — MA inserts/plays this item on the active queue.
     */
    suspend fun playTrackUri(uri: String, replaceIfPlayRejected: Boolean = true): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: _activePlayerId.value
        if (qid.isBlank() || uri.isBlank()) return false
        sendspinProvider()?.noteQueuedTrackStart()
        val ok = withContext(Dispatchers.IO) {
            // Prefer play-now inject; optionally fall back to replace-single if older MA
            // rejects play. Search "add this song" never uses replace — that would wipe
            // the live queue, which is the opposite of adding one track.
            client.playMedia(qid, listOf(uri), option = "play") ||
                (replaceIfPlayRejected && client.playMedia(qid, listOf(uri), option = "replace"))
        }
        if (ok) {
            delay(300)
            refreshRailQueues()
        }
        return ok
    }

    /**
     * Host tools: inject [uri] into the bound queue with a raw MA option
     * (`play` / `next` / `add` / `replace`). Same queue resolution as [playTrackUri].
     */
    suspend fun enqueueUri(uri: String, option: String): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: _activePlayerId.value
        if (qid.isBlank() || uri.isBlank()) return false
        if (option == "play" || option == "replace") sendspinProvider()?.noteQueuedTrackStart()
        val ok = withContext(Dispatchers.IO) { client.playMedia(qid, listOf(uri), option = option) }
        if (ok) {
            delay(300)
            refreshRailQueues()
        }
        return ok
    }

    /**
     * Toggle favorite on an Up Next row via MA favorites add/remove.
     * Remove needs a library DB id — resolved via URI after add / on demand.
     * Optimistic paint; rolls back on failure.
     *
     * @return true on success; [wasFavorite] is the state **before** the toggle
     *   (so UI can toast added vs removed).
     */
    suspend fun toggleQueueItemFavorite(queueItemId: String): Pair<Boolean, Boolean> {
        if (queueItemId.isBlank()) return false to false
        val item = _queueItems.value.firstOrNull { it.queueItemId == queueItemId }
            ?: return false to false
        val uri = item.favoriteItemUri()
        if (uri.isBlank()) return false to false
        val wasFavorite = item.favorite
        val targetFavorite = !wasFavorite

        fun paint(fav: Boolean, libraryId: String = item.libraryItemId) {
            _queueItems.value = _queueItems.value.map {
                if (it.queueItemId == queueItemId) {
                    it.copy(favorite = fav, libraryItemId = libraryId.ifBlank { it.libraryItemId })
                } else {
                    it
                }
            }
        }

        paint(targetFavorite)
        val ok = withContext(Dispatchers.IO) {
            if (targetFavorite) {
                if (!client.addFavoriteItem(uri)) return@withContext false
                // Cache library id so the next tap can unfavorite without a miss.
                val resolved = client.resolveLibraryFavoriteTarget(
                    itemUri = uri,
                    mediaItemId = item.mediaItemId,
                    provider = item.provider,
                    mediaType = item.mediaType,
                )
                if (resolved != null) {
                    paint(true, libraryId = resolved.second)
                }
                true
            } else {
                var mediaType = item.mediaType.ifBlank { "track" }
                var libId = item.libraryItemId
                if (libId.isBlank()) {
                    val resolved = client.resolveLibraryFavoriteTarget(
                        itemUri = uri,
                        mediaItemId = item.mediaItemId,
                        provider = item.provider,
                        mediaType = mediaType,
                    )
                    if (resolved != null) {
                        mediaType = resolved.first.ifBlank { mediaType }
                        libId = resolved.second
                    }
                }
                if (libId.isBlank()) return@withContext false
                val removed = client.removeFavoriteItem(mediaType, libId)
                if (removed) paint(false, libraryId = libId)
                removed
            }
        }
        if (!ok) paint(wasFavorite)
        return ok to wasFavorite
    }

    /**
     * Sync multi-select: [memberIds] are the players that should play with this
     * device, excluding itself.
     *
     * MA writes membership on the **leader**, so `set_members` must target the
     * leader of the group being edited — this device when it leads, otherwise
     * the leader it follows. Without that, a device that was paired *by* someone
     * else could not leave the group: the diff was computed as if it led a group
     * of its own, came out empty, and no command was ever sent.
     *
     * Removed peers always get a full MA queue clear so their Overlay drops our
     * title/artist (peer reacts via queue_updated → [wipeLocalPlaybackIdentity]).
     * This cleanup is mandatory — leftover sync media must not stick — so it is
     * not user-configurable.
     *
     * Newly added peers are detached from any prior sync tree first (leave the
     * leader they followed, dissolve a group they led) and get their own queue
     * cleared — one device, one group. Their Overlay then rebinds to this
     * leader's queue and seeds title/artist/cover without waiting for a
     * mid-track Sendspin metadata re-push.
     */
    suspend fun saveSyncMembers(memberIds: Set<String>): Boolean {
        val selfId = _activePlayerId.value.ifBlank { return false }
        val current = _players.value
        val selfPlayer = current.firstOrNull { it.playerId == selfId } ?: return false
        val leaderId = selfPlayer.syncedTo?.takeIf { it.isNotBlank() && it != selfId } ?: selfId
        val leaderPlayer = current.firstOrNull { it.playerId == leaderId }
        val existing = peerIdsSyncedTo(leaderId, leaderPlayer, current)

        val add: List<String>
        val remove: List<String>
        when {
            leaderId == selfId -> {
                add = (memberIds - existing).toList()
                remove = (existing - memberIds).toList()
            }
            // Follower seat with the leader unchecked = "leave this group".
            // Sibling membership is the leader's to change, not ours.
            leaderId !in memberIds -> {
                add = emptyList()
                remove = listOf(selfId)
            }
            // Staying in the leader's group: diff the other members only, so a
            // redraw can never propose removing ourselves.
            else -> {
                val desired = memberIds - leaderId + selfId
                add = (desired - existing).toList()
                remove = (existing - desired).filter { it != selfId }
            }
        }
        if (add.isEmpty() && remove.isEmpty()) return true
        val ok = withContext(Dispatchers.IO) {
            // Human logic: joining this group abandons every other sync source.
            for (peerId in add) {
                detachPeerPriorSyncSources(peerId, current, joiningLeaderId = leaderId)
            }
            val grouped = client.setGroupMembers(
                targetPlayer = leaderId,
                add = add.takeIf { it.isNotEmpty() },
                remove = remove.takeIf { it.isNotEmpty() },
            )
            if (grouped) {
                for (peerId in remove) {
                    clearPeerQueueAfterUnsync(peerId)
                }
            }
            grouped
        }
        if (ok) {
            refreshRailPlayers()
            // Leader seat: seed our own sticky identity once so a just-added peer
            // that rebinds to this queue can read a full current item immediately.
            if (add.isNotEmpty() && leaderId == selfId) {
                scope.launch {
                    refreshQueuesOnce()
                    refreshRailQueues()
                }
            }
        }
        return ok
    }

    /**
     * Before [peerId] joins [joiningLeaderId]: leave any other leader, dissolve
     * a group this peer still leads, and clear their own queue so old title /
     * artist / cover cannot stick under the new stream.
     */
    private suspend fun detachPeerPriorSyncSources(
        peerId: String,
        players: List<MassPlayer>,
        joiningLeaderId: String,
    ) {
        if (peerId.isBlank() || peerId == joiningLeaderId) return
        val peer = players.firstOrNull { it.playerId == peerId } ?: return
        val priorLeader = peer.syncedTo
            ?.takeIf { it.isNotBlank() && it != peerId && it != joiningLeaderId }
        if (priorLeader != null) {
            runCatching {
                client.setGroupMembers(
                    targetPlayer = priorLeader,
                    remove = listOf(peerId),
                )
            }
            Log.i(TAG, "sync-join: detached $peerId from prior leader $priorLeader")
        }
        val children = peer.groupChilds.filter { it != peerId && it != joiningLeaderId }
        if (children.isNotEmpty()) {
            runCatching {
                client.setGroupMembers(
                    targetPlayer = peerId,
                    remove = children,
                )
            }
            for (childId in children) {
                clearPeerQueueAfterUnsync(childId)
            }
            Log.i(
                TAG,
                "sync-join: dissolved $peerId's own group (${children.size} members)",
            )
        }
        // Own queue wipe → peer Overlay drops stale identity (queue_updated empty).
        clearPeerQueueAfterUnsync(peerId)
    }

    /**
     * Self-heal on the follower seat: if MA still lists us as leading children
     * while we already follow someone else, dissolve that leftover group.
     */
    private suspend fun dissolveOwnGroupIfFollowing(players: List<MassPlayer>) {
        val selfId = resolveSelfPlayerId(players).ifBlank { return }
        val me = players.firstOrNull { it.playerId == selfId } ?: return
        val following = me.syncedTo?.takeIf { it.isNotBlank() && it != selfId } ?: return
        val children = me.groupChilds.filter { it != selfId && it != following }
        if (children.isEmpty()) return
        runCatching {
            client.setGroupMembers(targetPlayer = selfId, remove = children)
        }
        for (childId in children) {
            clearPeerQueueAfterUnsync(childId)
        }
        Log.i(TAG, "sync-join self-heal: dissolved own group while following $following")
    }

    /** Transfer active queue to [targetPlayerId] and clear sync. */
    suspend fun switchPlaybackTo(targetPlayerId: String): Boolean {
        val source = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: _activePlayerId.value
        if (source.isBlank() || targetPlayerId.isBlank() || source == targetPlayerId) return false
        val dropping = peerIdsSyncedTo(
            source,
            _players.value.firstOrNull { it.playerId == source },
            _players.value,
        ).toList()
        // Drop sync on source first, then wipe each former peer's queue / overlay.
        withContext(Dispatchers.IO) {
            client.setGroupMembers(
                targetPlayer = source,
                remove = dropping.takeIf { it.isNotEmpty() },
            )
            for (peerId in dropping) {
                clearPeerQueueAfterUnsync(peerId)
            }
        }
        val ok = withContext(Dispatchers.IO) {
            client.transferQueue(sourceQueueId = source, targetQueueId = targetPlayerId)
        }
        if (ok) {
            // Keep identity on this device; only rebind queue transport to the target.
            boundQueueId = targetPlayerId
            refreshRail()
        }
        return ok
    }

    /**
     * After unsync: wipe that player's **entire** MA queue (all items) so leftover
     * sync media cannot stick. Peer Overlay clears when MA emits empty current
     * ([wipeLocalPlaybackIdentity]); if [playerId] is this device, wipe locally too.
     */
    private suspend fun clearPeerQueueAfterUnsync(playerId: String) {
        if (playerId.isBlank()) return
        var cleared = runCatching { client.clearQueue(playerId) }.getOrDefault(false)
        if (!cleared) {
            // One retry — MA sometimes drops the first clear during group churn.
            cleared = runCatching { client.clearQueue(playerId) }.getOrDefault(false)
        }
        Log.i(TAG, "sync-peer-clear: $playerId cleared=$cleared")
        val selfId = resolveSelfPlayerId(_players.value)
        val deviceId = sendspinProvider()?.deviceClientId().orEmpty()
        if (playerId == selfId ||
            (deviceId.isNotBlank() && playerId == deviceId) ||
            playerId == boundQueueId
        ) {
            // We were the cleared peer (or our bound queue was wiped) — do not wait
            // for a late queue_updated before dropping title/artist on Overlay.
            withContext(Dispatchers.Main.immediate) {
                wipeLocalPlaybackIdentity()
            }
        }
    }

    /**
     * Local Overlay + rail identity wipe after MA clear / unsync / empty current.
     * Bumps [mediaIdentityEpoch] so an in-flight [refreshRailQueues] cannot paint
     * the old title/artist back onto the shell.
     */
    private fun wipeLocalPlaybackIdentity() {
        mediaIdentityEpoch++
        lastBoundCurrentItemId = ""
        boundCurrentTitle = ""
        _queueItems.value = emptyList()
        cancelItemChangeCoalesce()
        lastItemChangeElapsed = 0L
        clearProgressBridge()
        sendspinProvider()?.paintWaitingForMediaAfterMaQueueClear()
    }

    suspend fun setVolume(playerId: String, level: Int, group: Boolean = false): Boolean {
        val ok = withContext(Dispatchers.IO) {
            if (group) client.setGroupVolume(playerId, level)
            else client.setPlayerVolume(playerId, level)
        }
        if (ok) refreshRailPlayers()
        return ok
    }

    /**
     * Enter voice/TTS overlay latch — Mass has no PCM of its own.
     * [VoiceSatelliteService] already calls [SendspinManager.duck] at the same
     * hook; we only flip this latch (HA SHOW / mirror gates). Calling SP duck
     * here would dual-own attenuation and let a later [unDuck] restore music
     * mid-TTS.
     * No-op unless Mass API is connected (pure-SP users stay untouched).
     */
    fun duck() {
        if (connectionState.value !is MassApiClient.ConnectionState.Connected) return
        synchronized(duckLock) {
            isDucked = true
        }
    }

    /**
     * Leave voice/TTS overlay latch. Does **not** touch Sendspin PCM —
     * [VoiceSatelliteService.onConversationEnd] owns [SendspinManager.unDuck].
     */
    fun unDuck() {
        synchronized(duckLock) {
            isDucked = false
        }
    }

    /** Whether Mass is in the same voice-overlay ducked latch as SP. */
    fun isVoiceOverlayDucked(): Boolean = isDucked

    /**
     * True when HA SHOW / progress must yield — **1:1** with
     * [VoiceSatelliteService] Sendspin HA-blocking gates, plus Mass overlay latch.
     */
    fun isBlockingHaMediaOverlay(): Boolean {
        if (isDucked) return true
        val sp = sendspinProvider() ?: return false
        if (sp.isActive.value) return true
        if (sp.wasRecentlyAudible()) return true
        // Bound Mass queue clock painting through the SP vinyl owner.
        if (
            connectionState.value is MassApiClient.ConnectionState.Connected &&
            !boundQueueId.isNullOrBlank()
        ) {
            return com.example.ava.services.VinylCoverService.isSendspinProgressOwner()
        }
        return false
    }

    /**
     * HA "playing" mirror of this Mass/SP session — **1:1** with
     * [SendspinManager.isLikelyMirrorOfCurrentTrack], using Mass current title
     * when SP cache is empty.
     */
    fun isLikelyMirrorOfCurrentTrack(haTitle: String?): Boolean {
        if (haTitle.isNullOrEmpty()) return false
        val sp = sendspinProvider()
        if (sp?.isActive?.value != true && sp?.isAudiblyPlayingNow() != true) return false
        val own = currentTrackTitle()
        return own.isNotEmpty() && own == haTitle
    }

    /**
     * Title-only HA mirror for queue-transport UI bridge — **1:1** with
     * [SendspinManager.isLikelyHaMirrorEntity].
     */
    fun isLikelyHaMirrorEntity(haTitle: String?): Boolean {
        if (haTitle.isNullOrEmpty()) return false
        val own = currentTrackTitle()
        return own.isNotEmpty() && own == haTitle
    }

    /** Bound Mass / queue current title (falls back to Up Next current row). */
    fun currentTrackTitle(): String {
        if (boundCurrentTitle.isNotBlank()) return boundCurrentTitle
        return _queueItems.value.firstOrNull { it.isCurrent }?.title.orEmpty()
    }

    suspend fun playQueueIndex(index: Int): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: return false
        val item = _queueItems.value.getOrNull(index) ?: return false
        val previousItems = _queueItems.value
        val previousItemId = lastBoundCurrentItemId
        // Paint the tapped row immediately, but do not hard-seat 0 until MA
        // accepts. A rejected play_index used to freeze the bar at the start
        // and drop the real position (e.g. 21s) as stale, with no PCM.
        lastBoundCurrentItemId = item.queueItemId
        _queueItems.value = previousItems.map {
            it.copy(isCurrent = it.queueItemId == item.queueItemId)
        }
        val ok = withContext(Dispatchers.IO) {
            client.playQueueIndex(qid, index, item.queueItemId)
        }
        if (!ok) {
            lastBoundCurrentItemId = previousItemId
            _queueItems.value = previousItems
            refreshRailQueues()
            return false
        }
        sendspinProvider()?.noteQueuedTrackStart()
        if (item.title.isNotBlank()) {
            sendspinProvider()?.applyKnownTrackIdentity(
                title = item.title,
                artist = item.artist.takeIf { it.isNotBlank() },
                coverUrl = item.imageUrl.takeIf { it.isNotBlank() },
            )
        }
        delay(200)
        refreshRailQueues()
        return true
    }

    /**
     * Overlay transport when the Sendspin socket is down. MA is the side that
     * dials port 8928; a local play/next into a null client never reaches it,
     * so the listener stays up with no peer and playback never resumes.
     */
    fun requestPlayerCommand(command: String) {
        val wire = when (command) {
            "play" -> "players/cmd/play"
            "pause" -> "players/cmd/pause"
            "next" -> "players/cmd/next"
            "previous" -> "players/cmd/previous"
            else -> return
        }
        val playerId = _activePlayerId.value.ifBlank { cachedSelfPlayerId }
        if (playerId.isBlank()) return
        scope.launch {
            client.command(wire, JSONObject().put("player_id", playerId))
        }
    }

    /**
     * Reorder an Up Next row via MA `player_queues/move_item`.
     * [posShift] >0 moves down, <0 moves up (MA semantics). Current/buffered
     * items may be rejected by the server — we still refresh after the call.
     */
    suspend fun moveQueueItem(queueItemId: String, posShift: Int): Boolean {
        if (queueItemId.isBlank() || posShift == 0) return false
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: return false
        val list = _queueItems.value.toMutableList()
        val from = list.indexOfFirst { it.queueItemId == queueItemId }
        if (from < 0) return false
        val to = (from + posShift).coerceIn(0, list.lastIndex)
        if (to == from) return false
        // Optimistic paint so the rail does not snap back while MA ACKs.
        list.add(to, list.removeAt(from))
        _queueItems.value = list
        val ok = withContext(Dispatchers.IO) {
            client.moveQueueItem(qid, queueItemId, posShift)
        }
        delay(200)
        refreshRailQueues()
        return ok
    }

    fun setSleepMinutes(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        val m = minutes.coerceAtLeast(0)
        _sleepMinutes.value = m
        if (m <= 0) return
        sleepJob = scope.launch {
            delay(m * 60_000L)
            _sleepMinutes.value = 0
            val playerId = _activePlayerId.value.ifBlank { return@launch }
            // Fade then pause (client-side sleep; MA has no sleep RPC).
            val startVol = _players.value.firstOrNull { it.playerId == playerId }?.volumeLevel ?: 40
            for (step in 4 downTo 0) {
                client.setPlayerVolume(playerId, (startVol * step / 4f).toInt())
                delay(400)
            }
            client.command(
                "players/cmd/pause",
                JSONObject().put("player_id", playerId),
            )
            refreshRailPlayers()
        }
    }

    /** MA `player_queues/autoplay` on the bound queue. */
    suspend fun setAutoplayEnabled(enabled: Boolean): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: return false
        val previous = _autoplayEnabled.value
        _autoplayEnabled.value = enabled
        val ok = withContext(Dispatchers.IO) { client.setQueueAutoplay(qid, enabled) }
        if (!ok) {
            _autoplayEnabled.value = previous
            refreshRailQueues()
        }
        return ok
    }

    /** MA `player_queues/crossfade` on the bound queue. */
    suspend fun setCrossfadeEnabled(enabled: Boolean): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: return false
        val previous = _crossfadeEnabled.value
        _crossfadeEnabled.value = enabled
        val ok = withContext(Dispatchers.IO) { client.setQueueCrossfade(qid, enabled) }
        if (!ok) {
            _crossfadeEnabled.value = previous
            refreshRailQueues()
        }
        return ok
    }

    /** MA `player_queues/clear` on the bound queue — one-shot wipe of all items. */
    suspend fun clearCurrentQueue(): Boolean {
        val qid = boundQueueId
            ?: sendspinProvider()?.deviceClientId()?.takeIf { it.isNotBlank() }
            ?: return false
        val ok = withContext(Dispatchers.IO) { client.clearQueue(qid) }
        if (ok) {
            // Soft NP shell → waiting-for-media (not SP stream/end hide timeline).
            wipeLocalPlaybackIdentity()
        }
        return ok
    }

    private suspend fun refreshQueuesOnce() {
        // Cold start: Connected runs this before refreshRail() — without player
        // sync state a follower would strong-match its own idle queue.
        if (_players.value.isEmpty()) {
            runCatching { refreshRailPlayers() }
        }
        val queues = client.fetchQueues()
        if (queues.isEmpty()) {
            Log.i(TAG, "No queues from player_queues/all — SP progress branch unchanged")
            return
        }
        val pick = pickQueue(queues) ?: return
        boundQueueId = pick.queueId
        boundViaActiveSource = pick.queueId == activeSourceQueueId()
        lastBoundCurrentItemId = pick.currentItemId
        applyQueueSettings(pick)
        applyTransport(pick)
        if (pick.currentTitle.isNotBlank()) {
            applyTrackIdentityFromMa(
                title = pick.currentTitle,
                artist = pick.currentArtist,
                imageUrl = pick.currentImageUrl,
                trackChanged = false,
            )
        }
        applyProgressFromQueue(pick, itemChanged = false)
        Log.i(
            TAG,
            "Bound queue=${pick.queueId} repeat=${pick.repeatMode} shuffle=${pick.shuffleEnabled} " +
                "elapsed=${pick.elapsedTimeSec}",
        )
    }

    private fun onQueueUpdated(transport: MassApiClient.QueueTransport) {
        val bound = boundQueueId
        if (bound == null) {
            // First useful update before refresh completes — bind if it looks like ours.
            if (!isLikelyOurQueue(transport)) return
            boundQueueId = transport.queueId
        } else if (transport.queueId != bound) {
            // Rebind if a better match for this device appears (e.g. Ava queue starts).
            // While synced as follower our own queue stays idle — an inactive
            // strong match must not steal the bind from the leader's live queue.
            if (isStrongMatch(transport) && !isStrongMatchId(bound) &&
                (transport.active || activeSourceQueueId() == null)
            ) {
                boundQueueId = transport.queueId
                boundViaActiveSource = false
            } else {
                return
            }
        }
        val itemChanged =
            transport.currentItemId.isNotBlank() &&
                lastBoundCurrentItemId.isNotBlank() &&
                transport.currentItemId != lastBoundCurrentItemId
        val firstBindWithIdentity =
            lastBoundCurrentItemId.isBlank() &&
                transport.currentItemId.isNotBlank() &&
                transport.currentTitle.isNotBlank()
        // Upstream clear (MA clear / empty queue): had a current item, now none.
        val lostCurrent =
            lastBoundCurrentItemId.isNotBlank() &&
                transport.currentItemId.isBlank() &&
                transport.currentTitle.isBlank()
        if (lostCurrent) {
            progressReseatGraceUntilElapsed = 0L
            lastAcceptedBridgeProgressMs = 0L
            applyQueueSettings(transport)
            applyTransport(transport)
            // Peer unsync clear / MA clear arrives here — wipe Overlay title/artist.
            wipeLocalPlaybackIdentity()
            return
        }
        if (transport.currentItemId.isNotBlank()) {
            lastBoundCurrentItemId = transport.currentItemId
        }
        if (transport.currentTitle.isNotBlank()) {
            boundCurrentTitle = transport.currentTitle.trim()
        }
        applyQueueSettings(transport)
        applyTransport(transport)
        when {
            itemChanged -> scheduleItemChangeCoalesce(transport)
            firstBindWithIdentity -> {
                applyTrackIdentityFromMa(
                    title = transport.currentTitle,
                    artist = transport.currentArtist,
                    imageUrl = transport.currentImageUrl,
                    trackChanged = false,
                )
                applyProgressFromQueue(transport, itemChanged = false)
            }
            pendingItemChange == null -> applyProgressFromQueue(transport, itemChanged = false)
        }
    }

    private fun onQueueTimeUpdated(queueId: String, elapsedSec: Double) {
        val bound = boundQueueId
        if (bound != null && queueId != bound) return
        if (bound == null) {
            // Cold start: MA broadcasts every queue's clock. Never claim a
            // foreign queue from a bare time tick (boundQueueId also drives
            // play/pause/seek) — adopt only on a device-id match and let
            // refreshQueuesOnce / queue_updated do the real binding otherwise.
            val deviceId = sendspinProvider()?.deviceClientId().orEmpty()
            if (deviceId.isBlank() || !queueId.contains(deviceId)) return
            boundQueueId = queueId
        }
        val sendspin = sendspinProvider() ?: return
        // Paint-only when Sendspin vinyl is up — never invent SHOW / seek.
        if (!sendspin.isActive.value) return
        // Skip-storm / coalesce: hold the last good bar. A dead item's
        // queue_time (0 or leftover) is what pinned the needle after recovery.
        if (pendingItemChange != null || sendspin.inUpstreamSkipStorm()) return
        val positionMs = (elapsedSec * 1000.0).toLong().coerceAtLeast(0L)
        // Auto-next / crossfade: [queue_time_updated] has no item id. A late
        // tick from the previous track (near old duration, e.g. 4:04) must not
        // overwrite the reseated 0:00 — that thrash also re-fires lyric loads.
        val now = SystemClock.elapsedRealtime()
        if (now < progressReseatGraceUntilElapsed) {
            if (looksLikePreviousTrackTail(positionMs)) {
                Log.d(
                    TAG,
                    "drop stale queue_time ${positionMs}ms during track reseat " +
                        "(preReseat=${preReseatProgressMs}ms dur=${preReseatDurationMs}ms)",
                )
                return
            }
        }
        lastAcceptedBridgeProgressMs = positionMs
        sendspin.applyQueueProgressUiBridge(
            positionMs = positionMs,
            durationMs = null,
            playing = null,
            capturedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * [itemChanged] used to force 0 even when MA already had a mid-track
     * [elapsedTimeSec] (sync-join / id flicker / seek). Only invent 0 for a
     * missing clock, open-lead, or leftover sitting on the previous tail.
     */
    private fun elapsedSecForItemChange(transport: MassApiClient.QueueTransport): Double {
        val reported = transport.elapsedTimeSec ?: return 0.0
        val reportedMs = (reported * 1000.0).toLong().coerceAtLeast(0L)
        if (reportedMs <= PROGRESS_RESEAT_OPEN_LEAD_MS) return 0.0
        if (looksLikePreviousTrackTail(reportedMs)) return 0.0
        return reported
    }

    /** Previous-track leftover (e.g. 4:04), not a mid-track clock we should keep. */
    private fun looksLikePreviousTrackTail(positionMs: Long): Boolean {
        if (positionMs <= PROGRESS_RESEAT_OPEN_LEAD_MS) return false
        val prevDur = preReseatDurationMs
        val prevPos = preReseatProgressMs
        if (prevDur > 0L &&
            positionMs >= prevDur - PROGRESS_RESEAT_STALE_JUMP_MS &&
            prevPos >= (prevDur - 5_000L).coerceAtLeast(0L)
        ) {
            return true
        }
        return prevPos > PROGRESS_RESEAT_OPEN_LEAD_MS &&
            kotlin.math.abs(positionMs - prevPos) <= PROGRESS_RESEAT_STALE_JUMP_MS &&
            prevDur > 0L &&
            prevPos >= (prevDur - 5_000L).coerceAtLeast(0L)
    }

    private fun applyProgressFromQueue(
        transport: MassApiClient.QueueTransport,
        itemChanged: Boolean,
    ) {
        val sendspin = sendspinProvider() ?: return
        if (!sendspin.isActive.value) return
        if (itemChanged) {
            preReseatProgressMs = lastAcceptedBridgeProgressMs
            preReseatDurationMs = lastBridgedDurationMs
        }
        val elapsedSec = when {
            itemChanged -> elapsedSecForItemChange(transport)
            transport.elapsedTimeSec != null -> transport.elapsedTimeSec
            else -> return
        }
        val capturedAtElapsed = captureElapsedRealtime(transport.elapsedTimeLastUpdatedSec)
        val durationMs = transport.durationSec
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000.0).toLong() }
        // Never demote the bar from queue state flashes (idle/paused during
        // Start Queue Flow). Only promote playing=true; freeze follows SP via
        // playing=null → bridgedUiProgressPlaying mirrors _isPlaying/audible.
        val playing = when (transport.state.lowercase()) {
            "playing" -> true
            else -> null
        }
        if (itemChanged) {
            // Soft reseat: clear previous duration seat + arm stale-time grace.
            // Do not clearProgressBridge() — that hands paint back to SP mid-crossfade.
            progressReseatGraceUntilElapsed =
                SystemClock.elapsedRealtime() + PROGRESS_RESEAT_GRACE_MS
        }
        val positionMs = (elapsedSec * 1000.0).toLong().coerceAtLeast(0L)
        lastAcceptedBridgeProgressMs = positionMs
        durationMs?.takeIf { it > 0L }?.let { lastBridgedDurationMs = it }
        sendspin.applyQueueProgressUiBridge(
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
            capturedAtElapsedRealtime = if (itemChanged) {
                SystemClock.elapsedRealtime()
            } else {
                capturedAtElapsed
            },
            newTrack = itemChanged,
        )
    }

    private fun captureElapsedRealtime(serverWallSec: Double?): Long {
        if (serverWallSec == null) return SystemClock.elapsedRealtime()
        val capturedWallMs = MassApiClient.serverWallSecondsToLocalMs(serverWallSec)
        val ageMs = (System.currentTimeMillis() - capturedWallMs).coerceAtLeast(0L)
        return SystemClock.elapsedRealtime() - ageMs
    }

    private fun applyQueueSettings(transport: MassApiClient.QueueTransport) {
        _autoplayEnabled.value = transport.autoplayEnabled
        _crossfadeEnabled.value = transport.crossfadeEnabled
    }

    private fun applyTransport(transport: MassApiClient.QueueTransport) {
        val sendspin = sendspinProvider() ?: return
        // Paint-only when Sendspin vinyl is up — never invent SHOW / seek.
        if (!sendspin.isActive.value) return
        sendspin.applyQueueTransportUiBridge(
            repeatMode = transport.repeatMode,
            shuffleEnabled = transport.shuffleEnabled,
        )
        Log.d(
            TAG,
            "UI-bridged queue ${transport.queueId}: repeat=${transport.repeatMode} shuffle=${transport.shuffleEnabled}",
        )
    }

    /**
     * Push MA title/artist/cover onto the NP vinyl when the bound current item
     * changes (or to fill sparse Sendspin title-only packets). Does not seek — identity paint only.
     *
     * @param trackChanged true on auto-next / Up Next: replace sticky credits.
     *   false on queue refresh: only fill blanks for the same/current title.
     * @param syncJoin true right after we joined / switched sync leader: replace
     *   sticky identity even before Sendspin marks active (mid-track joins often
     *   get PCM without a metadata re-push).
     */
    private fun cancelItemChangeCoalesce() {
        itemChangeCoalesceJob?.cancel()
        itemChangeCoalesceJob = null
        pendingItemChange = null
        itemChangeBurstCount = 0
    }

    private fun scheduleItemChangeCoalesce(transport: MassApiClient.QueueTransport) {
        val now = SystemClock.elapsedRealtime()
        val rapid = lastItemChangeElapsed > 0L &&
            now - lastItemChangeElapsed < SKIP_STORM_GAP_MS
        lastItemChangeElapsed = now
        pendingItemChange = transport
        itemChangeBurstCount = if (rapid) {
            (itemChangeBurstCount + 1).coerceAtLeast(2)
        } else {
            1
        }
        val storm = itemChangeBurstCount > 1
        if (storm) {
            sendspinProvider()?.noteUpstreamSkipStorm()
        }
        val waitMs = if (storm) SKIP_STORM_SETTLE_MS else ITEM_CHANGE_COALESCE_MS
        itemChangeCoalesceJob?.cancel()
        itemChangeCoalesceJob = scope.launch {
            delay(waitMs)
            val pending = pendingItemChange ?: return@launch
            val burst = itemChangeBurstCount
            pendingItemChange = null
            itemChangeBurstCount = 0
            itemChangeCoalesceJob = null
            commitCoalescedItemChange(pending, burst)
        }
    }

    /**
     * One identity/progress paint after [itemChanged] has settled.
     * A skip storm that lands on idle/paused (unplayable) keeps the last good
     * bar — painting that dead item is what froze progress after recovery.
     */
    private fun commitCoalescedItemChange(
        transport: MassApiClient.QueueTransport,
        burstCount: Int,
    ) {
        val storm = burstCount > 1
        val playing = transport.state.equals("playing", ignoreCase = true)
        if (storm && !playing) {
            Log.i(
                TAG,
                "skip-storm settle hold last good: '${transport.currentTitle}' " +
                    "state=${transport.state}",
            )
            sendspinProvider()?.noteUpstreamSkipStorm()
            sendspinProvider()?.noteUpstreamPlaybackDead("skip-storm-idle")
            return
        }
        applyTrackIdentityFromMa(
            title = transport.currentTitle,
            artist = transport.currentArtist,
            imageUrl = transport.currentImageUrl,
            trackChanged = true,
            armRescue = !storm,
        )
        applyProgressFromQueue(transport, itemChanged = true)
        sendspinProvider()?.clearUpstreamSkipStorm()
    }

    private fun applyTrackIdentityFromMa(
        title: String,
        artist: String,
        imageUrl: String,
        trackChanged: Boolean,
        syncJoin: Boolean = false,
        armRescue: Boolean = trackChanged,
    ) {
        val t = title.trim()
        if (t.isBlank()) return
        // After clear/unsync wipe, wait for a new current item id before painting.
        // Sync-join may seed from the leader player row before item id lands.
        if (lastBoundCurrentItemId.isBlank() && !syncJoin) return
        boundCurrentTitle = t
        val sendspin = sendspinProvider() ?: return
        // Do not require isActive: mid-track sync join must seed sticky caches so
        // the first audible birth can paint title/artist/cover. applyKnownTrackIdentity
        // still no-ops the window when inactive + GONE, but keeps the caches.
        if (trackChanged || syncJoin) {
            if (armRescue && trackChanged) {
                sendspin.noteExpectedTrackChangeWhilePlaying()
            }
            sendspin.applyKnownTrackIdentity(
                title = t,
                artist = artist.takeIf { it.isNotBlank() },
                coverUrl = imageUrl.takeIf { it.isNotBlank() },
            )
        } else {
            sendspin.fillTrackIdentityGaps(
                title = t,
                artist = artist.takeIf { it.isNotBlank() },
                coverUrl = imageUrl.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * After a sync join rebind: paint leader identity from bound queue and/or
     * the leader's player row (MA often has track_title there before queue items).
     */
    private fun paintBoundIdentityAfterSyncJoin() {
        val src = activeSourceQueueId()
        val leader = src?.let { id -> _players.value.firstOrNull { it.playerId == id } }
        val currentItem = _queueItems.value.firstOrNull { it.isCurrent }
            ?: _queueItems.value.firstOrNull()
        val title = boundCurrentTitle
            .ifBlank { currentItem?.title.orEmpty() }
            .ifBlank { leader?.trackTitle.orEmpty() }
        if (title.isBlank()) return
        val artist = currentItem?.artist.orEmpty()
            .ifBlank { leader?.trackArtist.orEmpty() }
        val image = currentItem?.imageUrl.orEmpty()
        applyTrackIdentityFromMa(
            title = title,
            artist = artist,
            imageUrl = image,
            trackChanged = true,
            syncJoin = true,
        )
        Log.i(TAG, "sync-join identity seeded title=$title artist=$artist")
    }

    private fun clearProgressBridge() {
        progressReseatGraceUntilElapsed = 0L
        lastAcceptedBridgeProgressMs = 0L
        sendspinProvider()?.clearQueueProgressUiBridge()
    }

    /**
     * This device is in a user-made MA sync group (leader or member).
     *
     * Three-valued on purpose: null = no arbiter (not connected / self identity
     * unresolved), and the Sendspin group gate must only refuse a server-side
     * grouping on a live `false` — otherwise a device whose MA socket is warming
     * up would treat its own user's pairing as a hijack.
     */
    fun isSelfMassSyncGrouped(): Boolean? {
        if (!isConnected()) return null
        val players = _players.value
        if (players.isEmpty()) return null
        val selfId = _activePlayerId.value.ifBlank { resolveSelfPlayerId(players) }
        if (selfId.isBlank()) return null
        val self = players.firstOrNull { it.playerId == selfId } ?: return null
        val leader = self.syncedTo?.trim().orEmpty()
        val hasLeader = leader.isNotEmpty() &&
            !leader.equals("null", ignoreCase = true) &&
            !leader.equals("undefined", ignoreCase = true) &&
            leader != selfId
        return hasLeader || self.groupChilds.any { it != selfId }
    }

    /**
     * LAN beacon [peerAvaId] is in our Music Assistant sync group.
     * Only [synced_to] / [MassPlayer.groupChilds] — same streamKey is not pairing.
     *
     * Three-valued: null = no arbiter yet (WS not connected, player list not
     * loaded, self identity unresolved). On cold start MA takes seconds to come
     * up while music is already playing — a `false` there silenced the whole
     * LAN fallback layer in both directions. Callers must refuse only on a
     * live `false`; the same-stream key door still gates every unknown packet.
     */
    fun isLanPeerMassPaired(peerAvaId: String): Boolean? {
        if (peerAvaId.isBlank()) return false
        if (!isConnected()) return null
        val players = _players.value
        if (players.isEmpty()) return null
        val selfId = _activePlayerId.value.ifBlank { resolveSelfPlayerId(players) }
        if (selfId.isBlank()) return null
        val self = players.firstOrNull { it.playerId == selfId } ?: return null
        val peer = players.firstOrNull { player ->
            peerAvaId == player.playerId ||
                peerAvaId in AvaSyncOffsetPeer.avaIdCandidates(player.playerId)
        } ?: return false
        if (peer.playerId == selfId) return false
        if (peer.syncedTo == selfId || peer.playerId in self.groupChilds) return true
        if (selfId in peer.groupChilds) return true
        val leader = self.syncedTo?.trim().orEmpty()
        if (leader.isEmpty() ||
            leader.equals("null", ignoreCase = true) ||
            leader.equals("undefined", ignoreCase = true)
        ) {
            return false
        }
        return peer.playerId == leader || peer.syncedTo == leader
    }

    /** Peers currently paired with [leaderId] (members of our group). */
    private fun peerIdsSyncedTo(
        leaderId: String,
        leader: MassPlayer?,
        players: List<MassPlayer>,
    ): Set<String> {
        if (leaderId.isBlank()) return emptySet()
        val fromLeader = leader?.groupChilds.orEmpty().filter { it != leaderId }.toSet()
        val fromPeers = players
            .filter { it.playerId != leaderId && it.syncedTo == leaderId }
            .map { it.playerId }
            .toSet()
        return fromLeader + fromPeers
    }

    /**
     * Re-run queue binding when MA player sync state moves the feeding queue
     * (join → leader's queue owns the clock; unsync → back to our own).
     * [lastRebindAttemptSrc] stops retry loops when the active source is not a
     * queue at all (e.g. leader on Spotify Connect).
     *
     * Fresh join / leader switch also dissolves leftover self-led members and
     * seeds title/artist/cover from the leader queue — mid-track PCM joins do
     * not re-push Sendspin metadata.
     */
    private fun maybeRebindForSyncChange() {
        val bound = boundQueueId
        val src = activeSourceQueueId()
        val prevSrc = lastKnownSyncSourceId
        val syncJoin = src != null && src != prevSrc
        lastKnownSyncSourceId = src

        if (syncJoin) {
            lastRebindAttemptSrc = src
            scope.launch {
                runCatching { dissolveOwnGroupIfFollowing(_players.value) }
                // Brief settle so MA finishes writing synced_to / group_members.
                delay(250)
                runCatching { refreshRailPlayers() }
                refreshQueuesOnce()
                refreshRailQueues()
                paintBoundIdentityAfterSyncJoin()
            }
            return
        }

        if (bound == null) return
        if (src != null) {
            if (bound != src && lastRebindAttemptSrc != src) {
                lastRebindAttemptSrc = src
                scope.launch { refreshQueuesOnce() }
            }
        } else {
            lastRebindAttemptSrc = null
            if (boundViaActiveSource) {
                boundViaActiveSource = false
                scope.launch { refreshQueuesOnce() }
            }
        }
    }

    /**
     * Queue actually feeding this device per MA player state. Synced follower:
     * our own queue exists but is idle — the leader's queue owns elapsed-time
     * ticks, so a strong id match must not shadow it (frozen follower bar).
     * Null when solo / unknown, falling back to the match ladder.
     */
    private fun activeSourceQueueId(): String? {
        // Resolved identity, not the raw client id: Mass may namespace the player
        // id it derived from it, and then no row here would ever match.
        val myId = _activePlayerId.value.takeIf { it.isNotBlank() } ?: return null
        val me = _players.value.firstOrNull { it.playerId == myId } ?: return null
        return (me.activeSource?.takeIf { it.isNotBlank() } ?: me.syncedTo)
            ?.takeIf { it.isNotBlank() && it != myId }
    }

    private fun pickQueue(queues: List<MassApiClient.QueueTransport>): MassApiClient.QueueTransport? {
        if (queues.isEmpty()) return null
        activeSourceQueueId()?.let { srcId ->
            queues.firstOrNull { it.queueId == srcId }?.let { return it }
        }
        queues.firstOrNull { isStrongMatch(it) }?.let { return it }
        queues.firstOrNull { isLikelyOurQueue(it) }?.let { return it }
        return queues.firstOrNull { it.active } ?: queues.firstOrNull()
    }

    private fun isStrongMatch(transport: MassApiClient.QueueTransport): Boolean =
        isStrongMatchId(transport.queueId) || isStrongMatchName(transport.name)

    private fun isStrongMatchId(queueId: String): Boolean {
        val sendspin = sendspinProvider() ?: return false
        val id = sendspin.deviceClientId()
        // Trailing form too — a Mass-namespaced queue id is still our queue, and
        // without this the pick falls through to the fuzzy name/`contains` tiers.
        return id.isNotBlank() && (queueId == id || queueId.endsWith(id))
    }

    private fun isStrongMatchName(name: String): Boolean {
        if (name.isBlank()) return false
        val sendspin = sendspinProvider() ?: return false
        val label = sendspin.clientDisplayName()
        return label.isNotBlank() && name.equals(label, ignoreCase = true)
    }

    private fun isLikelyOurQueue(transport: MassApiClient.QueueTransport): Boolean {
        if (isStrongMatch(transport)) return true
        val name = transport.name
        if (name.contains("Ava", ignoreCase = true)) return transport.active || name.isNotBlank()
        val sendspin = sendspinProvider() ?: return transport.active
        val id = sendspin.deviceClientId()
        return id.isNotBlank() && transport.queueId.contains(id)
    }

    private data class AuthSnapshot(
        val enabled: Boolean,
        val serverUrl: String,
        val authToken: String,
        val username: String,
        val password: String,
    )

    companion object {
        private const val TAG = "MassApiManager"

        /** Align with SP open-lead: post-reseat time ticks above this look like old-track. */
        private const val PROGRESS_RESEAT_OPEN_LEAD_MS = 4_000L
        /** Minimum jump from last accepted seat to treat as stale old-tail clock. */
        private const val PROGRESS_RESEAT_STALE_JUMP_MS = 1_500L
        /** How long after itemChanged to drop late previous-track queue_time ticks. */
        private const val PROGRESS_RESEAT_GRACE_MS = 5_000L
        /** Single current-item change: wait for a same-ms skip burst. */
        private const val ITEM_CHANGE_COALESCE_MS = 800L
        /** Two-plus item changes: wait for MA to finish skipping unplayable rows. */
        private const val SKIP_STORM_SETTLE_MS = 1_500L
        /** Item changes this close are one skip storm, even across a coalesce fire. */
        private const val SKIP_STORM_GAP_MS = 2_500L

        /** Keystroke settle before a search leaves the device. */
        private const val SEARCH_DEBOUNCE_MS = 350L
        /** Below this, a query matches too much of a library to be worth a provider fan-out. */
        const val SEARCH_MIN_CHARS = 2

        @Volatile
        private var instance: MassApiManager? = null

        /**
         * Reactive [instance] for Compose consumers. A plain `remember { get() }`
         * composed before [ensure] runs would cache null for the composition's
         * lifetime; collecting this flow picks the manager up once it exists.
         */
        private val _instanceFlow = MutableStateFlow<MassApiManager?>(null)
        val instanceFlow: StateFlow<MassApiManager?> = _instanceFlow.asStateFlow()

        fun get(): MassApiManager? = instance

        fun ensure(context: Context): MassApiManager {
            synchronized(this) {
                instance?.let { return it }
                val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                return MassApiManager(context.applicationContext, appScope).also {
                    instance = it
                    _instanceFlow.value = it
                    it.start()
                }
            }
        }

        fun bindSendspin(provider: () -> SendspinManager?) {
            instance?.let { mgr ->
                mgr.sendspinProvider = provider
                mgr.retryIdentityResolution()
            }
        }

        fun shutdown() {
            synchronized(this) {
                instance?.stop()
                instance = null
                _instanceFlow.value = null
            }
        }
    }
}
