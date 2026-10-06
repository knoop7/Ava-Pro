package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.localllm.HaPlayGuard
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.MassSearchPlan
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import com.example.ava.massapi.MassApiClient
import com.example.ava.massapi.MassApiManager
import com.example.ava.sendspin.MediaCommandOutcome
import com.example.ava.massapi.MassSearchItem
import com.example.ava.sendspin.SendspinManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.utils.DeviceMusicVolumeMonitor
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * Ava-owned music surface. Never routed through Home Assistant.
 *
 * Music Assistant searches and queues (title / artist / album / playlist).
 * Sendspin only transports what is already playing — it cannot search.
 * A feature that is off is not in the schema.
 */
object AvaMusicTools {

    const val PLAY = "ava_music_play"
    const val CONTROL = "ava_music_control"
    const val NOW = "ava_music_now"
    const val VOLUME = "ava_volume"

    const val PREFIX = "ava_"

    private const val HIT_CAP = 8

    private val cacheLock = Any()
    private val hits = LinkedHashMap<Int, MassSearchItem>(16, 0.75f, true)
    private var nextN = 1

    data class Ready(
        val mass: Boolean,
        val sendspin: Boolean,
    ) {
        val any: Boolean get() = mass || sendspin
    }

    fun ready(): Ready {
        val mass = MassApiManager.get()?.isConnected() == true
        val sendspin = sendspinManager()?.enabled?.value == true
        return Ready(mass = mass, sendspin = sendspin)
    }

    fun surface(ready: Ready = ready()): HaToolSet {
        val tools = ArrayList<ToolDef>()
        if (ready.mass) {
            tools += ToolDef(
                PLAY,
                "Search Music Assistant and play on this speaker. query= the title, artist= the artist, album= the album, each in its own field — never combined. If the user named only an artist, pass artist= alone. One hit plays at once; several hits come back numbered with started=false — call again with n= the one the user meant.",
                listOf(
                    ToolParam("query", ToolParamType.Str, "song, album, playlist, or radio name as spoken", required = false),
                    ToolParam("artist", ToolParamType.Str, "artist if the user said one; alone plays that artist", required = false),
                    ToolParam("album", ToolParamType.Str, "album if the user said one", required = false),
                    ToolParam(
                        "type",
                        ToolParamType.Enum(listOf("track", "album", "playlist", "artist", "radio", "any")),
                        "what to search, default any. artist= the person; playlist= a list",
                        required = false,
                    ),
                    ToolParam("n", ToolParamType.Int(1, 99), "hit number from the last search", required = false),
                    ToolParam(
                        "mode",
                        ToolParamType.Enum(listOf("play", "next", "add", "replace")),
                        "play=now, next=after current, add=end of queue, replace=wipe queue. Default play",
                        required = false,
                    ),
                ),
            argumentCases = listOf(
                        ToolArgumentCase(fields = setOf("n", "mode"), required = setOf("n")),
                        ToolArgumentCase(fields = setOf("query", "artist", "album", "type", "mode"), atLeastOne = setOf("query", "artist", "album")),
                    ),
                )
        }
        if (ready.sendspin) {
            tools += ToolDef(
                CONTROL,
                "Transport for the music already playing on this speaker. Cannot search or start a new song.",
                listOf(
                    ToolParam(
                        "action",
                        ToolParamType.Enum(listOf("play", "pause", "stop", "next", "previous", "seek", "seek_relative")),
                        "seek needs position_s, seek_relative needs offset_s",
                        required = true,
                    ),
                    ToolParam("position_s", ToolParamType.Int(0, 36_000), "absolute position in seconds", required = false),
                    ToolParam("offset_s", ToolParamType.Int(-3_600, 3_600), "relative seconds, negative rewinds", required = false),
                ),
            argumentCases = listOf(
                        ToolArgumentCase(action = "play", fields = emptySet()),
                        ToolArgumentCase(action = "pause", fields = emptySet()),
                        ToolArgumentCase(action = "stop", fields = emptySet()),
                        ToolArgumentCase(action = "next", fields = emptySet()),
                        ToolArgumentCase(action = "previous", fields = emptySet()),
                        ToolArgumentCase(action = "seek", fields = setOf("position_s"), required = setOf("position_s")),
                        ToolArgumentCase(action = "seek_relative", fields = setOf("offset_s"), required = setOf("offset_s")),
                    ),
                )
        }
        if (ready.any) {
            tools += ToolDef(
                NOW,
                "Fresh read of this speaker's track: title, artist, album, position, duration, volume. The current title is already in ## Now — do not call this just to name the song.",
                emptyList(),
            )
        }
        val targets = buildList {
            add("tts")
            add("device")
            if (ready.sendspin) add("music")
        }
        tools += ToolDef(
            VOLUME,
            "Change one of this speaker's volumes. Call it only after the user made clear which one: tts=this speaker's voice, device=system media volume" +
                (if (ready.sendspin) ", music=the song playing" else "") +
                ". If they did not say which, ask instead of calling. Pass exactly one of level, step, or mute.",
            listOf(
                ToolParam("target", ToolParamType.Enum(targets), "which volume: tts=this speaker's voice, device=system volume" + (if (ready.sendspin) ", music=the song" else "") + ". Never guess it.", required = true),
                ToolParam("level", ToolParamType.Int(0, 100), "absolute percent", required = false),
                ToolParam("step", ToolParamType.Int(-100, 100), "relative percent, e.g. -10 for a little quieter", required = false),
                ToolParam("mute", ToolParamType.Bool, "true mutes, false unmutes", required = false),
            ),
            argumentCases = listOf(
                ToolArgumentCase(fields = setOf("target", "level", "step", "mute"), required = setOf("target"), exactlyOne = setOf("level", "step", "mute")),
            ),
        )
        return HaToolSet(tools)
    }

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result =
        when (call.name) {
            PLAY -> play(call.arguments)
            CONTROL -> control(call.arguments)
            NOW -> now()
            VOLUME -> volume(call.arguments, app)
            else -> AvaToolCallback.fail("not_found", "Tool not available: ${call.name}")
        }

    private suspend fun play(args: JSONObject): AvaToolCallback.Result {
        val mass = MassApiManager.get()
        if (mass == null || mass.connectionState.value !is MassApiClient.ConnectionState.Connected) {
            return AvaToolCallback.fail("mass_not_connected", "Music Assistant is not connected. You cannot search or start a song.")
        }
        val mode = args.optString("mode").trim().ifEmpty { "play" }
        val n = optInt(args, "n")
        if (n != null) {
            val hit = cached(n) ?: return AvaToolCallback.fail("not_found", "no hit $n. Search again.")
            return startHit(mass, hit, mode)
        }
        val title = args.optString("query").trim()
        val artist = args.optString("artist").trim()
        val album = args.optString("album").trim()
        val kind = args.optString("type").trim().ifEmpty { "any" }
        val plan = MassSearchPlan.of(title, artist, album, kind)
        if (plan.searchName.length < 2) {
            return AvaToolCallback.fail("invalid_request", "query, artist, or album is required")
        }
        val found = ArrayList<MassSearchItem>()
        val seen = HashSet<String>()
        if (plan.includeLibraryPlaylists) {
            for (hit in libraryPlaylists(mass, plan)) {
                if (seen.add(hit.uri)) found += hit
            }
        }
        for (hit in searchMass(mass, plan.searchName, plan.types)) {
            if (!matches(hit, plan)) continue
            if (seen.add(hit.uri)) found += hit
        }
        if (found.isEmpty() && plan.fallbackTypes.isNotEmpty()) {
            for (hit in searchMass(mass, plan.searchName, plan.fallbackTypes)) {
                if (!matches(hit, plan)) continue
                if (seen.add(hit.uri)) found += hit
            }
        }
        if (found.isEmpty()) {
            return AvaToolCallback.fail("not_found", "no matching music for ${plan.searchName}")
        }
        val picked = MassSearchPlan.prefer(found, plan)
        remember(picked)
        if (picked.size == 1) return startHit(mass, picked.first(), mode)
        return AvaToolCallback.ok(hitsJson(picked, mode))
    }

    private suspend fun startHit(
        mass: MassApiManager,
        hit: MassSearchItem,
        mode: String,
    ): AvaToolCallback.Result {
        val ok = enqueue(mass, hit, mode)
        if (!ok) return AvaToolCallback.fail("tool_error", "${hit.name} did not start")
        return playbackResult(hit, mode)
    }

    internal fun playbackResult(hit: MassSearchItem, mode: String): AvaToolCallback.Result {
        val queued = mode == "next" || mode == "add"
        return AvaToolCallback.ok(hitJson(hit).put("mode", mode).put("queued", queued)
            .put("playback_confirmed", false), status = if (queued) "queued" else "accepted")
    }

    private suspend fun enqueue(mass: MassApiManager, hit: MassSearchItem, mode: String): Boolean =
        when (mode) {
            "play" -> if (hit.isTrack) mass.playTrackUri(hit.uri) else mass.playPlaylist(hit.uri)
            "replace" -> mass.enqueueUri(hit.uri, "replace")
            "next", "add" -> mass.enqueueUri(hit.uri, mode)
            else -> false
        }

    private suspend fun searchMass(
        mass: MassApiManager,
        query: String,
        types: List<String>,
    ): List<MassSearchItem> =
        runCatching { mass.searchNow(query, types) }.getOrDefault(emptyList())

    private suspend fun libraryPlaylists(
        mass: MassApiManager,
        plan: MassSearchPlan,
    ): List<MassSearchItem> {
        if (mass.playlists.value.isEmpty()) {
            runCatching { mass.refreshRailPlaylists() }
        }
        val needle = plan.title.ifBlank { plan.searchName }
        return mass.playlists.value.mapNotNull { playlist ->
            if (playlist.uri.isBlank()) return@mapNotNull null
            if (!HaPlayGuard.titleMatches(needle, playlist.name) &&
                !HaPlayGuard.titleMatches(plan.searchName, playlist.name)
            ) {
                return@mapNotNull null
            }
            MassSearchItem(
                uri = playlist.uri,
                name = playlist.name,
                mediaType = "playlist",
                trackCount = playlist.trackCount,
            )
        }
    }

    private fun matches(hit: MassSearchItem, plan: MassSearchPlan): Boolean {
        val name = hit.name
        val who = hit.artist
        val record = hit.album.ifBlank { if (hit.mediaType == "album") hit.name else "" }
        if (plan.title.isNotBlank() && hit.mediaType != "artist" &&
            !HaPlayGuard.titleMatches(plan.title, name, "$who $record")
        ) {
            return false
        }
        if (plan.artist.isNotBlank()) {
            val hay = if (hit.mediaType == "artist") name else who
            if (!HaPlayGuard.titleMatches(plan.artist, hay, name)) return false
        }
        if (plan.album.isNotBlank() &&
            !HaPlayGuard.titleMatches(plan.album, record, name)
        ) {
            return false
        }
        if (plan.title.isBlank() && plan.artist.isBlank() && plan.album.isBlank()) return false
        return true
    }

    private fun control(args: JSONObject): AvaToolCallback.Result {
        val sendspin = sendspinManager()
            ?: return AvaToolCallback.fail("no_session", "Sendspin is not running.")
        if (sendspin.uiStateSnapshot() == null) {
            return AvaToolCallback.fail("no_session", "No music session on this speaker.")
        }
        val action = args.optString("action").trim()
        val sent = when (action) {
            "play", "pause", "stop", "next", "previous" -> sendspin.sendMediaCommandReporting(action)
            "seek" -> {
                val pos = optInt(args, "position_s")
                    ?: return AvaToolCallback.fail("invalid_request", "seek needs position_s")
                sendspin.seekToReporting(pos * 1000L)
            }
            "seek_relative" -> {
                val off = optInt(args, "offset_s")
                    ?: return AvaToolCallback.fail("invalid_request", "seek_relative needs offset_s")
                sendspin.seekRelativeReporting(off * 1000L)
            }
            else -> return AvaToolCallback.fail("invalid_request", "unknown action $action")
        }
        return controlResult(action, sent)
    }

    internal fun controlResult(action: String, outcome: MediaCommandOutcome): AvaToolCallback.Result = when (outcome) {
        MediaCommandOutcome.ACCEPTED -> AvaToolCallback.ok(JSONObject().put("action", action), status = "accepted")
        MediaCommandOutcome.IGNORED -> AvaToolCallback.fail("action_ignored", "$action was ignored because the stream is unavailable or changing. Wait for playback before retrying.")
        MediaCommandOutcome.REJECTED -> AvaToolCallback.fail("command_rejected", "$action was not accepted")
    }

    data class NowFacts(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val playing: Boolean? = null,
        val volume: Int? = null,
        val muted: Boolean? = null,
    )

    /** Title / volume for `## Now`. Progress stays on [now] so the prompt does not move every second. */
    fun nowFacts(): NowFacts {
        val snap = nowSnapshot() ?: return NowFacts()
        return NowFacts(
            title = snap.optString("title").takeIf { it.isNotBlank() },
            artist = snap.optString("artist").takeIf { it.isNotBlank() },
            album = snap.optString("album").takeIf { it.isNotBlank() },
            playing = if (snap.has("playing")) snap.optBoolean("playing") else null,
            volume = if (snap.has("volume")) snap.optInt("volume") else null,
            muted = if (snap.has("muted")) snap.optBoolean("muted") else null,
        )
    }

    private fun now(): AvaToolCallback.Result {
        val snap = nowSnapshot()
            ?: return AvaToolCallback.fail("no_session", "No music session on this speaker.")
        if (!snap.has("title") && !snap.has("playing")) {
            return AvaToolCallback.fail("no_session", "Nothing is queued on Music Assistant.")
        }
        return AvaToolCallback.ok(snap)
    }

    private fun nowSnapshot(): JSONObject? {
        sendspinManager()?.uiStateSnapshot()?.let { state ->
            val playing = state.playbackState.equals("playing", ignoreCase = true) ||
                (state.playbackSpeed ?: 0) > 0
            val out = JSONObject().put("playing", playing)
            state.trackTitle?.takeIf { it.isNotBlank() }?.let { out.put("title", it) }
            state.trackArtist?.takeIf { it.isNotBlank() }?.let { out.put("artist", it) }
            state.albumTitle?.takeIf { it.isNotBlank() }?.let { out.put("album", it) }
            state.trackProgress?.let { out.put("position_s", it / 1000) }
            state.trackDuration?.let { out.put("duration_s", it / 1000) }
            out.put("volume", state.playerVolume)
            out.put("muted", state.playerMuted)
            state.repeatMode?.let { out.put("repeat", it) }
            state.shuffleEnabled?.let { out.put("shuffle", it) }
            return out
        }
        val mass = MassApiManager.get()?.takeIf { it.isConnected() } ?: return null
        val current = mass.queueItems.value.firstOrNull { it.isCurrent }
            ?: mass.queueItems.value.firstOrNull()
        val player = mass.players.value.firstOrNull { it.playerId == mass.activePlayerId.value }
            ?: mass.players.value.firstOrNull()
        val title = current?.title?.takeIf { it.isNotBlank() } ?: player?.trackTitle.orEmpty()
        if (title.isBlank() && player == null) return null
        if (title.isBlank()) return JSONObject()
        val out = JSONObject().put("title", title)
        val artist = current?.artist?.takeIf { it.isNotBlank() } ?: player?.trackArtist.orEmpty()
        if (artist.isNotBlank()) out.put("artist", artist)
        current?.durationSec?.takeIf { it > 0 }?.let { out.put("duration_s", it.toInt()) }
        player?.volumeLevel?.takeIf { it in 0..100 }?.let { out.put("volume", it) }
        player?.state?.takeIf { it.isNotBlank() }?.let { state ->
            out.put("playing", state.equals("playing", ignoreCase = true))
            out.put("state", state)
        }
        return out
    }

    private suspend fun volume(args: JSONObject, app: Context): AvaToolCallback.Result {
        val sendspin = sendspinManager()?.takeIf { it.enabled.value }
        val raw = args.optString("target").trim().lowercase()
        if (raw.isEmpty()) {
            return AvaToolCallback.fail(
                "invalid_request",
                "target is required. tts=Ava's voice, device=system volume. If the user did not say which, ask — do not guess.",
            )
        }
        val target = when (raw) {
            "voice" -> "tts"
            "system" -> "device"
            "tts", "device", "music" -> raw
            else -> return AvaToolCallback.fail(
                "invalid_request",
                "target must be tts, device, or music. If the user did not name one, ask — do not guess.",
            )
        }
        val level = optInt(args, "level")
        val step = optInt(args, "step")
        val mute = if (args.has("mute") && args.opt("mute") != JSONObject.NULL) args.optBoolean("mute") else null
        if (listOfNotNull(level, step, mute).size != 1) {
            return AvaToolCallback.fail("invalid_request", "give exactly one of level, step, mute")
        }
        return when (target) {
            "music" -> {
                val sp = sendspin ?: return AvaToolCallback.fail("no_session", "Sendspin is not running.")
                val state = sp.uiStateSnapshot()
                    ?: return AvaToolCallback.fail("no_session", "No music session on this speaker.")
                if (mute != null) {
                    if (!sp.sendMediaCommand("mute", mute = mute)) {
                        return AvaToolCallback.fail("tool_error", "mute was not accepted")
                    }
                    return AvaToolCallback.ok(JSONObject().put("target", target).put("muted", mute), status = "accepted")
                }
                val next = (level ?: (state.playerVolume + (step ?: 0))).coerceIn(0, 100)
                if (!sp.sendMediaCommand("volume", volume = next)) {
                    return AvaToolCallback.fail("tool_error", "volume was not accepted")
                }
                AvaToolCallback.ok(JSONObject().put("target", target).put("volume", next), status = "accepted")
            }
            "device" -> {
                val current = (DeviceMusicVolumeMonitor.readNormalizedLevel(app) * 100f).toInt()
                val next = when {
                    mute != null -> if (mute) 0 else current.coerceAtLeast(20)
                    level != null -> level
                    else -> current + (step ?: 0)
                }.coerceIn(0, 100)
                val applied = DeviceMusicVolumeMonitor.writeNormalizedLevel(app, next / 100f)
                AvaToolCallback.ok(
                    JSONObject().put("target", target).put("volume", (applied * 100f + 0.5f).toInt()), status = "applied",
                )
            }
            "tts" -> {
                val store = PlayerSettingsStore(app.playerSettingsStore)
                val current = (store.getCached().whisperResponseVolume * 100f).toInt()
                val minPct = (PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME * 100f).toInt()
                val nextPct = when {
                    mute != null -> if (mute) minPct else current.coerceAtLeast(
                        (PlayerSettings.DEFAULT_WHISPER_RESPONSE_VOLUME * 100f).toInt(),
                    )
                    level != null -> level
                    else -> current + (step ?: 0)
                }.coerceIn(minPct, 100)
                val next = nextPct / 100f
                store.whisperResponseVolume.set(next)
                VoiceSatelliteService.applyVoiceReplyVolumeLive(next)
                AvaToolCallback.ok(
                    JSONObject().put("target", "tts").put("volume", nextPct),
                    status = "applied",
                )
            }
            else -> AvaToolCallback.fail("invalid_request", "target must be tts, device, or music")
        }
    }

    private fun remember(items: List<MassSearchItem>) {
        synchronized(cacheLock) {
            hits.clear()
            nextN = 1
            for (item in items.take(HIT_CAP)) {
                hits[nextN] = item
                nextN += 1
            }
        }
    }

    private fun cached(n: Int): MassSearchItem? = synchronized(cacheLock) { hits[n] }

    private fun hitsJson(items: List<MassSearchItem>, mode: String): JSONObject {
        val arr = JSONArray()
        items.take(HIT_CAP).forEachIndexed { i, hit ->
            arr.put(hitJson(hit).put("n", i + 1))
        }
        return JSONObject()
            .put("count", arr.length())
            .put("hits", arr)
            .put("mode", mode)
            .put("started", false)
            .put("hint", "Several matches. Call ava_music_play n= with the one the user meant.")
    }

    private fun hitJson(hit: MassSearchItem): JSONObject {
        val out = JSONObject()
            .put("title", hit.name)
            .put("type", hit.mediaType)
        if (hit.artist.isNotBlank()) out.put("artist", hit.artist)
        val album = hit.album.ifBlank { if (hit.mediaType == "album") hit.name else "" }
        if (album.isNotBlank()) out.put("album", album)
        hit.trackCount?.let { out.put("tracks", it) }
        return out
    }

    private fun sendspinManager(): SendspinManager? =
        VoiceSatelliteService.getInstance()?.sendspinManager

    private fun optInt(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.opt(key) == JSONObject.NULL) return null
        return when (val raw = args.opt(key)) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}
