package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.entity.HaEntityLiveState
import com.example.ava.homeassistant.entity.HaEntitySummary
import com.example.ava.localllm.DeviceIndex
import com.example.ava.localllm.HaSpokenTarget
import com.example.ava.localllm.HaDomainActions
import com.example.ava.localllm.HaServicePayload
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OpenClaw Mini `ha_*` surface, grounded the Claw way: spoken name or exact
 * id → [DeviceIndex.resolve], then host service call. JSON `{ok,error}` per call.
 */
object RemoteAiHaTools {

    const val SEARCH = "ha_search"
    const val STATE = "ha_state"
    const val CAMERA_SNAPSHOT = "ha_camera_snapshot"
    const val TURN_ON = "ha_turn_on"
    const val TURN_OFF = "ha_turn_off"
    const val TOGGLE = "ha_toggle"
    const val CALL_SERVICE = "ha_call_service"
    const val GUIDE = "ha_guide"

    fun surface(house: Boolean = true): HaToolSet = HaToolSet(
        tools = buildList {
            add(
                ToolDef(
                    GUIDE,
                    "Playbook for the tools that are currently on. get=one section by name. search=find a section (one hit includes the body). overview=index with one-line use. Skip when the schema already answers.",
                    listOf(
                        ToolParam(
                            "action",
                            ToolParamType.Enum(listOf("overview", "list", "get", "search")),
                            "get=one section, search=find (one hit includes body), overview=index",
                            required = true,
                        ),
                        ToolParam("name", ToolParamType.Str, "section name, or a unique word from use or title", required = false),
                        ToolParam("query", ToolParamType.Str, "search text", required = false),
                        ToolParam("limit", ToolParamType.Int(1, 8), "search hit cap, default 3", required = false),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(action = "overview", fields = emptySet()),
                        ToolArgumentCase(action = "list", fields = emptySet()),
                        ToolArgumentCase(action = "get", fields = setOf("name"), required = setOf("name")),
                        ToolArgumentCase(action = "search", fields = setOf("query", "limit"), required = setOf("query")),
                    ),
                ),
            )
            if (!house) return@buildList
            add(
                ToolDef(
                    SEARCH,
                    "List or find exposed Home Assistant devices when the user wants a list or a count, or when you have no device name to act on. Not needed before ha_turn_on/off. query= the spoken name (a number like \"light 1\" stays in it), domain= the type (light/switch/cover), area= the room. Hits include live state and speakable values. A whole-house count of a type is domain= that type with no device name — count from state, skip unread rows, do not ha_state each hit. Never entity_id=all on a write.",
                    listOf(
                        ToolParam("query", ToolParamType.Str, "spoken device name", required = false),
                        ToolParam("domain", ToolParamType.Str, "optional domain such as light, switch, or sensor", required = false),
                        ToolParam("area", ToolParamType.Str, "optional spoken area", required = false),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(fields = setOf("query", "domain", "area"), atLeastOne = setOf("query", "domain", "area")),
                    ),
                ),
            )
            add(
                ToolDef(
                    STATE,
                    "Read one entity's current state when the user asked for it. Pass entity_id or the spoken name. Returns live state plus speakable values (brightness, temperature, fan, swing, position, tilt, volume, mute, source, color, effect, title) — not the whole HA attribute bag. Not the picture from a camera — that is ha_camera_snapshot. Not needed before or after ha_turn_on/off: their callback already carries the same values.",
                    listOf(
                        ToolParam("entity_id", ToolParamType.Str, "entity id or spoken name", required = true),
                        ToolParam("domain", ToolParamType.Str, "optional domain hint", required = false),
                    ),
                ),
            )
            add(
                ToolDef(
                    CAMERA_SNAPSHOT,
                    "Look at a house camera. Pass the spoken name or entity_id; the host fetches the current frame. After this returns, describe what you see. Never ha_turn_on a camera, and never invent a URL or token.",
                    listOf(
                        ToolParam("entity_id", ToolParamType.Str, "camera entity id or spoken name", required = true),
                    ),
                ),
            )
            add(
                ToolDef(
                    TURN_ON,
                    "Turn a device on. Pass the spoken name or entity_id; the host matches it — do not ha_search first. The callback includes live state and current values (brightness, temperature, position, volume). If the user said a value or mode, pass it here too (light brightness/color, climate temperature, cover position, fan percent, media volume, humidifier humidity); the host picks the real service. Do not use ha_call_service for those fields.",
                    listOf(
                        ToolParam("entity_id", ToolParamType.Str, "entity id or spoken name", required = true),
                        ToolParam("domain", ToolParamType.Str, "optional domain hint", required = false),
                        ToolParam("brightness", ToolParamType.Int(0, 255), "light brightness 0-255", required = false),
                        ToolParam("brightness_pct", ToolParamType.Int(1, 100), "light brightness percent", required = false),
                        ToolParam("color_name", ToolParamType.Str, "spoken color; host maps it to HA", required = false),
                        ToolParam("temperature", ToolParamType.Num(5.0, 80.0), "climate or water heater target", required = false),
                        ToolParam("hvac_mode", ToolParamType.Str, "climate mode; host maps spoken values", required = false),
                        ToolParam("position", ToolParamType.Int(0, 100), "cover or valve position percent", required = false),
                        ToolParam("percentage", ToolParamType.Int(0, 100), "fan speed percent", required = false),
                        ToolParam("volume_pct", ToolParamType.Int(0, 100), "media volume percent", required = false),
                        ToolParam("humidity", ToolParamType.Int(0, 100), "humidifier target percent", required = false),
                        ToolParam("code", ToolParamType.Str, "lock code when the user said one", required = false),
                    ),
                ),
            )
            add(
                ToolDef(
                    TURN_OFF,
                    "Turn a device off. Pass the spoken name or entity_id; the host matches it — do not ha_search first. The callback includes live state. For a cover this closes it, for a vacuum it returns to dock, for a lock it locks.",
                    listOf(
                        ToolParam("entity_id", ToolParamType.Str, "entity id or spoken name", required = true),
                        ToolParam("domain", ToolParamType.Str, "optional domain hint", required = false),
                    ),
                ),
            )
            add(
                ToolDef(
                    TOGGLE,
                    "Toggle a light, switch, or fan. Pass the spoken name; do not ha_search first. The callback includes live state.",
                    listOf(
                        ToolParam("entity_id", ToolParamType.Str, "entity id or spoken name", required = true),
                        ToolParam("domain", ToolParamType.Str, "optional domain hint", required = false),
                    ),
                ),
            )
            add(
                ToolDef(
                    CALL_SERVICE,
                    "Call a service that ha_turn_on/off does not cover (pause, locate, next track, alarm arm, play_media). Pass the spoken house-device name in entity_id; the host matches it. Never domain=frontend, never entity frontend, never set_theme — Settings and theme on the open Home Assistant page are ava_page_read then ava_page_act navigate path=/config/dashboard or path=/profile. Power, temperature, position, volume, and humidity belong on ha_turn_on instead.",
                    listOf(
                        ToolParam("domain", ToolParamType.Str, "the entity's domain, such as media_player or vacuum", required = true),
                        ToolParam("service", ToolParamType.Str, "service name such as media_pause or locate", required = true),
                        ToolParam("entity_id", ToolParamType.Str, "entity id or spoken name of an exposed house device", required = false),
                        ToolParam("data", ToolParamType.Obj, "service fields as a JSON object, e.g. {temperature:22}; never put service fields at the top level", required = false),
                    ),
                ),
            )
        },
    )

    suspend fun execute(
        call: AvaToolCallback.Call,
        ctx: AvaToolCallback.Context,
        index: DeviceIndex,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
        app: Context,
    ): AvaToolCallback.Result {
        if (call.name == GUIDE) return guide(call.arguments, app, ctx.browserOnly)
        if (!ctx.haReady) {
            return AvaToolCallback.fail("ha_not_configured", "Home Assistant is not signed in.")
        }
        return when (call.name) {
            SEARCH -> search(call.arguments, index)
            STATE -> state(call.arguments, index)
            CAMERA_SNAPSHOT -> cameraSnapshot(call.arguments, index, app)
            TURN_ON -> power(call.arguments, index, caller, on = true)
            TURN_OFF -> power(call.arguments, index, caller, on = false)
            TOGGLE -> toggle(call.arguments, index, caller)
            CALL_SERVICE -> callService(call.arguments, index, caller)
            else -> AvaToolCallback.fail("not_found", "Tool not available: ${call.name}")
        }
    }

    private suspend fun guide(args: JSONObject, app: Context, browserOnly: Boolean): AvaToolCallback.Result {
        val ready = RemoteAiGuide.readyNow(app).let {
            if (browserOnly) it.copy(ha = false, musicPlay = false, musicTransport = false, self = false, voice = false, phone = false, page = false) else it
        }
        val action = args.optString("action").trim()
        return when (action) {
            "overview" -> AvaToolCallback.ok(RemoteAiGuide.overview(ready))
            "list" -> AvaToolCallback.ok(RemoteAiGuide.list(ready))
            "get" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) {
                    return AvaToolCallback.fail("invalid_request", "name is required for get")
                }
                val doc = RemoteAiGuide.get(ready, name)
                    ?: return AvaToolCallback.fail("not_found", "unknown or unavailable guide section")
                AvaToolCallback.ok(doc)
            }
            "search" -> {
                val query = args.optString("query").trim()
                if (query.isEmpty()) {
                    return AvaToolCallback.fail("invalid_request", "query is required for search")
                }
                val hits = RemoteAiGuide.search(ready, query, args.optInt("limit", 3))
                if (hits.length() == 0) {
                    return AvaToolCallback.fail("not_found", "no matching guide section")
                }
                AvaToolCallback.ok(JSONObject().put("count", hits.length()).put("docs", hits))
            }
            else -> AvaToolCallback.fail("invalid_request", "action must be overview, list, get, or search")
        }
    }

    private suspend fun search(args: JSONObject, index: DeviceIndex): AvaToolCallback.Result {
        val query = args.optString("query").trim()
        val domain = args.optString("domain").trim().ifEmpty { null }
        val area = args.optString("area").trim().ifEmpty { null }
        if (query.isEmpty() && domain == null && area == null) {
            return AvaToolCallback.fail("invalid_request", "query, domain, or area is required")
        }
        val hits = index.discover(nameContains = query.ifEmpty { null }, domain = domain, area = area, limit = SEARCH_LIMIT)
            .filterNot { AvaPublishedEntities.isOwnEntityId(it.entityId) }
        if (hits.isEmpty()) {
            return AvaToolCallback.fail("not_found", "no matching exposed house device")
        }
        val ha = HaManager.get()
            ?: return AvaToolCallback.fail("ha_not_configured", "Home Assistant is not signed in.")
        val live = ha.fetchEntityLiveStates(hits.map { it.entityId })
        val arr = JSONArray()
        var unread = 0
        for (e in hits) {
            val row = entityJson(e, live[e.entityId]).put("area", index.areaOf(e.entityId).orEmpty())
            if (live[e.entityId] == null) unread += 1
            arr.put(row)
        }
        val listing = isTypeList(query, domain, area)
        val body = JSONObject().put("count", hits.size).put("entities", arr)
        if (unread > 0) body.put("unread", unread)
        if (listing || unread > 0) body.put("hint", listHint(unread))
        return AvaToolCallback.ok(body)
    }

    private suspend fun state(args: JSONObject, index: DeviceIndex): AvaToolCallback.Result {
        val entity = resolve(args, index) ?: return ungrounded(args, index)
        val ha = HaManager.get()
            ?: return AvaToolCallback.fail("ha_not_configured", "Home Assistant is not signed in.")
        val live = ha.fetchEntityLiveStates(listOf(entity.entityId))[entity.entityId]
            ?: return AvaToolCallback.fail("tool_error", "could not read live state")
        return AvaToolCallback.ok(entityJson(entity, live))
    }

    private suspend fun cameraSnapshot(args: JSONObject, index: DeviceIndex, app: Context): AvaToolCallback.Result {
        val entity = resolve(args, index, domainHint = "camera") ?: return ungrounded(args, index, domainHint = "camera")
        if (entity.domain != "camera") {
            return AvaToolCallback.fail(
                "invalid_request",
                "ha_camera_snapshot only reads camera.* entities",
                entityJson(entity, null),
            )
        }
        val ha = HaManager.get()
            ?: return AvaToolCallback.fail("ha_not_configured", "Home Assistant is not signed in.")
        val raw = ha.fetchCameraSnapshot(entity.entityId)
            ?: return AvaToolCallback.fail("tool_error", "could not capture a frame from ${entity.name.ifBlank { entity.entityId }}")
        val jpeg = compressSnapshot(raw)
            ?: return AvaToolCallback.fail(
                "tool_error",
                "could not decode a still frame from ${entity.name.ifBlank { entity.entityId }}",
            )
        val path = persistSnapshot(app, jpeg)
            ?: return AvaToolCallback.fail("tool_error", "could not save the camera frame")
        Log.i(TAG, "camera snapshot ${entity.entityId} bytes=${jpeg.size}")
        val live = ha.fetchEntityLiveStates(listOf(entity.entityId))[entity.entityId]
        val details = entityJson(entity, live)
            .put("captured", true)
            .put("hint", "The picture is attached. Describe what you see. Do not invent a URL.")
        return AvaToolCallback.ok(details, imagePath = path)
    }

    private suspend fun power(
        args: JSONObject,
        index: DeviceIndex,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
        on: Boolean,
    ): AvaToolCallback.Result {
        val entity = resolve(args, index) ?: return ungrounded(args, index)
        cameraPowerBlocked(entity)?.let { return it }
        val merged = HaServicePayload.mergeOverflow(args)
        val requested = if (on) "turn_on" else "turn_off"
        val service = HaServicePayload.route(entity.domain, requested, merged)
        val data = if (on || service.substringAfter('.') in setOf("unlock", "lock")) {
            HaServicePayload.asMap(
                HaServicePayload.normalize(entity.domain, service.substringAfter('.'), merged),
            )
        } else {
            emptyMap()
        }
        return act(caller, service, entity, data)
    }

    private suspend fun toggle(
        args: JSONObject,
        index: DeviceIndex,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
    ): AvaToolCallback.Result {
        val entity = resolve(args, index) ?: return ungrounded(args, index)
        cameraPowerBlocked(entity)?.let { return it }
        val service = when (entity.domain) {
            "cover" -> "cover.toggle"
            "lock" -> "lock.lock"
            "vacuum" -> HaDomainActions.service(entity.domain, true)
            else -> "${entity.domain}.toggle"
        }
        return act(caller, service, entity, emptyMap())
    }

    private suspend fun callService(
        args: JSONObject,
        index: DeviceIndex,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
    ): AvaToolCallback.Result {
        val domain = args.optString("domain").trim()
        val serviceName = args.optString("service").trim()
        if (domain.isEmpty() || serviceName.isEmpty()) {
            return AvaToolCallback.fail("invalid_request", "domain and service are required")
        }
        val hint = args.optString("entity_id").ifBlank { args.optString("name") }.trim()
        pageUiServiceMessage(domain, serviceName, hint)?.let { return AvaToolCallback.fail("tool_call_blocked", it) }
        if (hint.isEmpty()) return AvaToolCallback.fail("invalid_request", "an exposed entity_id is required")
        val entity = resolve(args, index) ?: return ungrounded(args, index)
        if (!serviceName.matches(Regex("[a-z][a-z0-9_]*"))) {
            return AvaToolCallback.fail("tool_call_blocked", "service must belong to the exposed entity domain")
        }
        if (domain != entity.domain && !crossDomain(domain, entity.domain)) {
            return AvaToolCallback.fail("tool_call_blocked", "service must belong to the exposed entity domain")
        }
        val merged = HaServicePayload.mergeOverflow(args)
        val blocked = setOf("entity_id", "area_id", "device_id", "floor_id", "label_id", "target")
        val dataKeys = merged.keys()
        while (dataKeys.hasNext()) {
            if (dataKeys.next() in blocked) {
                return AvaToolCallback.fail("tool_call_blocked", "service data cannot override the exposed target")
            }
        }
        val mapped = HaServicePayload.route(entity.domain, serviceName, merged)
        val action = mapped.substringAfter('.')
        if (entity.domain == "camera" && action in setOf("turn_on", "turn_off")) {
            return cameraPowerBlocked(entity)!!
        }
        val data = HaServicePayload.asMap(HaServicePayload.normalize(entity.domain, action, merged))
        return act(caller, mapped, entity, data)
    }

    internal fun pageUiServiceMessage(domain: String, service: String, entityHint: String): String? {
        val d = domain.trim().lowercase()
        val s = service.trim().lowercase().substringAfter('.')
        val h = entityHint.trim().lowercase()
        val pageDomain = d == "frontend" || d == "lovelace"
        val pageService = s.contains("theme")
        val pageHint = h in setOf("frontend", "lovelace", "theme", "主题", "profile")
        if (!pageDomain && !pageService && !pageHint) return null
        return "Home Assistant settings and theme are ava_page_read, then ava_page_act navigate path=/config/dashboard or path=/profile and tap. Not ha_call_service."
    }

    private fun cameraPowerBlocked(entity: HaEntitySummary): AvaToolCallback.Result? {
        if (entity.domain != "camera") return null
        return AvaToolCallback.fail(
            "tool_call_blocked",
            "Reading a camera is ha_camera_snapshot, not turn on or off.",
            entityJson(entity, null).put("preferred", CAMERA_SNAPSHOT),
        )
    }

    private suspend fun act(
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
        service: String,
        entity: HaEntitySummary,
        data: Map<String, Any?>,
    ): AvaToolCallback.Result {
        // null is the ESPHome route without an acknowledgement, not a rejection.
        val acknowledged = caller(service, entity.entityId, HaServicePayload.withoutTarget(data))
        val live = HaManager.get()?.fetchEntityLiveStates(listOf(entity.entityId))?.get(entity.entityId)
        return serviceResult(acknowledged, service, entity, live)
    }

    internal fun serviceResult(
        acknowledged: Boolean?,
        service: String,
        entity: HaEntitySummary,
        live: HaEntityLiveState? = null,
    ): AvaToolCallback.Result {
        val details = entityJson(entity, live).put("service", service)
        return when (acknowledged) {
            true -> AvaToolCallback.ok(details, status = "accepted")
            false -> AvaToolCallback.fail("command_rejected", "${entity.name.ifBlank { entity.entityId }} request was rejected", details)
            null -> AvaToolCallback.unknown("No acknowledgement is available. The action may already have run; do not repeat it automatically.", details)
        }
    }

    private fun resolve(args: JSONObject, index: DeviceIndex, domainHint: String? = null): HaEntitySummary? {
        val hint = args.optString("entity_id").ifBlank { args.optString("name") }.trim()
        if (hint.isEmpty()) return null
        val domain = args.optString("domain").trim().ifEmpty { null } ?: domainHint
        index.resolve(hint, domain)?.let { return it }
        val alt = when (domain) {
            "light" -> "switch"
            "switch" -> "light"
            else -> null
        }
        return alt?.let { index.resolve(hint, it) }
    }

    private fun crossDomain(asked: String, actual: String): Boolean =
        (asked == "light" && actual == "switch") || (asked == "switch" && actual == "light")

    private suspend fun ungrounded(args: JSONObject, index: DeviceIndex, domainHint: String? = null): AvaToolCallback.Result {
        val hint = args.optString("entity_id").ifBlank { args.optString("name") }.trim()
        if (hint.isEmpty()) {
            return AvaToolCallback.fail("ungrounded", "no matching device")
        }
        val domain = args.optString("domain").ifBlank { null } ?: domainHint
        val candidates = index.candidates(hint, domain)
        if (candidates.size > 1) return AvaToolCallback.fail("ambiguous", "More than one device matches; ask the user which one.",
            JSONObject().put("candidates", JSONArray(candidates.map { entity ->
                JSONObject().put("entity_id", entity.entityId).put("name", entity.name).put("domain", entity.domain)
                    .put("area", index.areaOf(entity.entityId).orEmpty())
            })))
        if (index.resolve(hint) == null && looksLikeEntityId(hint) && HaManager.get()?.entityExists(hint) == true) {
            return AvaToolCallback.fail(
                "not_exposed",
                "that entity exists in Home Assistant but is not exposed to this assistant",
            )
        }
        return AvaToolCallback.fail("ungrounded", "no exposed house device matches \"$hint\"")
    }

    private fun entityJson(entity: HaEntitySummary, live: HaEntityLiveState?): JSONObject {
        val out = JSONObject()
            .put("entity_id", entity.entityId)
            .put("name", live?.name?.takeIf { it.isNotBlank() } ?: entity.name)
            .put("domain", entity.domain)
        if (live == null) {
            out.put("hint", "could not read live state")
            return out
        }
        out.put("state", live.state)
        if (live.unit.isNotBlank()) out.put("unit", live.unit)
        if (live.deviceClass.isNotBlank()) out.put("device_class", live.deviceClass)
        if (live.lastChanged.isNotBlank()) out.put("last_changed", live.lastChanged)
        live.putSpeakable(out)
        if (live.state == "unknown" || live.state == "unavailable") {
            out.put("hint", "Home Assistant itself reports this state")
        }
        return out
    }

    private fun looksLikeEntityId(hint: String): Boolean =
        ENTITY_ID.matches(hint.trim())

    private fun optInt(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.opt(key) == JSONObject.NULL) return null
        return when (val raw = args.opt(key)) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun mergeData(raw: Any?, into: MutableMap<String, Any?>) {
        when (raw) {
            is JSONObject -> {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    into[key] = raw.opt(key)
                }
            }
            is String -> {
                val text = raw.trim()
                if (text.startsWith("{")) {
                    runCatching { mergeData(JSONObject(text), into) }
                }
            }
        }
    }

    private fun compressSnapshot(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > AI_MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        if (w > AI_MAX_DIM || h > AI_MAX_DIM) {
            val scale = minOf(AI_MAX_DIM.toFloat() / w, AI_MAX_DIM.toFloat() / h)
            val scaled = Bitmap.createScaledBitmap(bitmap, kotlin.math.round(w * scale).toInt(), kotlin.math.round(h * scale).toInt(), true)
            bitmap.recycle()
            bitmap = scaled
        }
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, AI_JPEG_QUALITY, out)
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    private fun persistSnapshot(app: Context, jpeg: ByteArray): String? {
        val dir = File(app.cacheDir, SNAPSHOT_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        dir.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
            if (files.size >= SNAPSHOT_KEEP) {
                files.dropLast(SNAPSHOT_KEEP - 1).forEach { it.delete() }
            }
        }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        return runCatching {
            file.writeBytes(jpeg)
            file.absolutePath
        }.getOrNull()
    }

    private fun isTypeList(query: String, domain: String?, area: String?): Boolean {
        if (query.isEmpty()) return domain != null || !area.isNullOrEmpty()
        val target = HaSpokenTarget.parse(query, domain)
        return target.name.isEmpty() && (target.domain != null || domain != null || !area.isNullOrEmpty())
    }

    private fun listHint(unread: Int): String {
        val skip = if (unread > 0) "$unread unread — skip those. " else ""
        return skip + "Count from state on this list. Do not ha_state each hit. A write still needs a spoken name — never entity_id=all."
    }

    private val ENTITY_ID = Regex("^(?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)$")
    private const val TAG = "RemoteAiHaTools"
    private const val SEARCH_LIMIT = 80
    private const val SNAPSHOT_DIR = "ha_camera_snapshots"
    private const val SNAPSHOT_KEEP = 5
    private const val AI_MAX_DIM = 650
    private const val AI_JPEG_QUALITY = 65
}
