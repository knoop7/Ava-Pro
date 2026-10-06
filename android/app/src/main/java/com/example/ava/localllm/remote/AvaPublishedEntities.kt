package com.example.ava.localllm.remote

import com.example.ava.localllm.HaSpokenHear
import com.example.ava.esphome.entities.ButtonEntity
import com.example.ava.esphome.entities.LockEntity
import com.example.ava.esphome.entities.NumberEntity
import com.example.ava.esphome.entities.SelectEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.esphome.entities.TextEntity
import com.example.ava.services.VoiceSatelliteService
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.LockCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * This speaker's published ESPHome commandables. Off features are not
 * registered and must not appear in any tool JSON. List is none-only;
 * search may hit config. Inject hits the entity setter, never ha_*.
 */
object AvaPublishedEntities {

    const val BROWSER_DISPLAY = "browser_display"

    data class Item(
        val id: String,
        val name: String,
        val kind: String,
        val category: String,
        val state: String?,
        val options: List<String>? = null,
        val min: Float? = null,
        val max: Float? = null,
        private val switchSet: (suspend (Boolean) -> Unit)? = null,
        private val buttonPress: (suspend () -> Unit)? = null,
        private val selectSet: (suspend (String) -> Unit)? = null,
        private val numberSet: (suspend (Float) -> Unit)? = null,
        private val textSet: (suspend (String) -> Unit)? = null,
        private val lockSet: (suspend (LockCommand) -> Unit)? = null,
    ) {
        suspend fun inject(on: Boolean?, value: String?, press: Boolean): AvaToolCallback.Result =
            withContext(Dispatchers.Main.immediate) {
                val result = toJson().also { it.remove("state") }
                when (kind) {
                    "switch" -> {
                        val next = on ?: return@withContext AvaToolCallback.fail("invalid_request", "switch requires on=true/false")
                        if (displaySwitchUnchanged(id, state, next)) {
                            result.put("on", next)
                            if (AvaOverlayReceipts.isOverlayTarget(id)) {
                                AvaOverlayReceipts.awaitAndAttach(result, id, next)
                            }
                            return@withContext AvaToolCallback.ok(result, status = "applied")
                        }
                        val setter = switchSet ?: return@withContext AvaToolCallback.fail("tool_error", "switch setter unavailable")
                        setter(next)
                        result.put("on", next)
                    }
                    "button" -> {
                        if (!press) return@withContext AvaToolCallback.fail("invalid_request", "button requires press=true")
                        val setter = buttonPress ?: return@withContext AvaToolCallback.fail("tool_error", "button setter unavailable")
                        setter()
                        result.put("pressed", true)
                    }
                    "select" -> {
                        val raw = value?.trim().orEmpty()
                        val exact = options.orEmpty().filter { it.equals(raw, ignoreCase = true) }
                        val matches = if (exact.isNotEmpty()) exact else options.orEmpty().filter { raw.isNotEmpty() && it.contains(raw, ignoreCase = true) }
                        val pick = matches.singleOrNull() ?: return@withContext AvaToolCallback.fail("invalid_request", "choose one exact option: ${options.orEmpty().joinToString()}")
                        val setter = selectSet ?: return@withContext AvaToolCallback.fail("tool_error", "select setter unavailable")
                        setter(pick)
                        result.put("value", pick)
                    }
                    "number" -> {
                        val n = value?.toFloatOrNull()
                        if (n == null || !n.isFinite() || min != null && n < min || max != null && n > max) {
                            return@withContext AvaToolCallback.fail("invalid_request", "value must be a finite number within ${min ?: "-infinity"}..${max ?: "infinity"}")
                        }
                        val setter = numberSet ?: return@withContext AvaToolCallback.fail("tool_error", "number setter unavailable")
                        setter(n)
                        result.put("value", n)
                    }
                    "text" -> {
                        val raw = value ?: return@withContext AvaToolCallback.fail("invalid_request", "text requires value")
                        val setter = textSet ?: return@withContext AvaToolCallback.fail("tool_error", "text setter unavailable")
                        setter(raw)
                        result.put("value", raw)
                    }
                    "lock" -> {
                        val next = on ?: return@withContext AvaToolCallback.fail("invalid_request", "lock requires on=true/false")
                        val setter = lockSet ?: return@withContext AvaToolCallback.fail("tool_error", "lock setter unavailable")
                        setter(if (next) LockCommand.LOCK_LOCK else LockCommand.LOCK_UNLOCK)
                        result.put("on", next)
                    }
                    else -> return@withContext AvaToolCallback.fail("invalid_request", "cannot set $kind")
                }
                when {
                    AvaOverlayReceipts.isOverlayTarget(id) -> {
                        val wanted = when (kind) {
                            "switch", "lock" -> on
                            else -> true
                        }
                        AvaOverlayReceipts.awaitAndAttach(result, id, wanted)
                    }
                    id == "minimal_launcher_app" ->
                        AvaOverlayReceipts.awaitAndAttach(result, AvaOverlayReceipts.APP_WINDOW, true)
                }
                AvaToolCallback.ok(result, status = "accepted")
            }

        fun toJson(): JSONObject {
            val out = JSONObject()
                .put("id", id)
                .put("name", name)
                .put("kind", kind)
                .put("category", category)
            min?.let { out.put("min", it) }
            max?.let { out.put("max", it) }
            if (!state.isNullOrBlank()) out.put("state", state)
            if (!options.isNullOrEmpty()) {
                val arr = JSONArray()
                for (option in options) arr.put(option)
                out.put("options", arr)
            }
            return out
        }
    }

    fun hasNone(): Boolean = catalog(noneOnly = true).isNotEmpty()

    /** Object ids this speaker publishes, plus the four unpublished overlays. */
    fun ownObjectIds(): Set<String> {
        val out = catalog(noneOnly = false).mapTo(HashSet()) { it.id }
        out += AvaLocalFeatures.DREAM_CLOCK
        out += AvaLocalFeatures.SIMPLE_CLOCK
        out += AvaLocalFeatures.WEATHER
        out += AvaLocalFeatures.VOICE_MESSAGE
        return out
    }

    /** True when this HA entity_id is this Ava, not a house device. */
    fun isOwnEntityId(entityId: String): Boolean {
        val tail = entityId.substringAfterLast('.').trim().lowercase()
        if (tail.isEmpty()) return false
        return ownObjectIds().any { id ->
            val key = id.lowercase()
            tail == key || tail.endsWith("_$key")
        }
    }

    fun catalog(noneOnly: Boolean): List<Item> {
        val out = ArrayList<Item>()
        for (entity in VoiceSatelliteService.getInstance()?.snapshotEsphomeEntities().orEmpty()) {
            val item = wrap(entity) ?: continue
            if (noneOnly && item.category != "none") continue
            out += item
        }
        return out
    }

    fun search(spoken: String): List<Item> {
        val key = normalize(spoken)
        if (key.isEmpty()) return emptyList()
        return catalog(noneOnly = false).filter { item ->
            keys(item).any { k -> namesMeet(key, k) }
        }
    }

    /**
     * Open/close a family name from the owned list is the display
     * switch. Theme, timer entity, and status slots only win when the user
     * named that setting. The model must not be asked to pick among them.
     */
    fun resolve(spoken: String): List<Item> {
        val hits = filterBindTimer(spoken, search(spoken))
        if (hits.size <= 1) return hits
        val key = normalize(spoken)
        hits.firstOrNull { normalize(it.id) == key || normalize(it.name) == key }?.let {
            return listOf(it)
        }
        val theme = hits.filter { it.id.contains("flip_style") || it.id.contains("theme") }
        val timer = hits.filter { it.id.endsWith("_timer") || it.id.contains("timer") }
        val slots = hits.filter { it.id.contains("status_slot") }
        when {
            isTheme(key) && theme.isNotEmpty() -> return theme
            isTimer(key) && timer.isNotEmpty() -> return timer
            isSlot(key) && slots.isNotEmpty() -> return slots
        }
        val display = hits.filter { it.id.endsWith("_display") && it.kind == "switch" }
        if (display.size == 1) return display
        return hits
    }

    fun nextAction(item: Item): JSONObject {
        val args = JSONObject().put("action", "set").put("target", item.id)
        val hint = when (item.kind) {
            "switch" -> "Call ava_self action=set target=${item.id} on=true|false."
            "lock" -> "Call ava_self action=set target=${item.id}: on=true locks, on=false unlocks."
            "button" -> "Call ava_self action=set target=${item.id} press=true."
            "select" -> "Call ava_self action=set target=${item.id} value= one option."
            else -> "Call ava_self action=set target=${item.id} value=."
        }
        return JSONObject()
            .put("hint", hint)
            .put(
                "next_action",
                JSONObject()
                    .put("tool", AvaSelfTools.NAME)
                    .put("arguments", args)
                    .put("required_arguments", JSONArray(listOf(when (item.kind) { "switch", "lock" -> "on"; "button" -> "press"; else -> "value" }))),
            )
    }

    private fun wrap(entity: Any): Item? = when (entity) {
        is SwitchEntity -> Item(
            id = entity.objectId,
            name = entity.name,
            kind = "switch",
            category = category(entity.entityCategory),
            state = peek(entity.getState)?.let { if (it) "on" else "off" },
            switchSet = entity.setState,
        )
        is ButtonEntity -> Item(
            id = entity.objectId,
            name = entity.name,
            kind = "button",
            category = category(entity.entityCategory),
            state = null,
            buttonPress = entity.onPress,
        )
        is SelectEntity -> Item(
            id = entity.objectId,
            name = entity.name,
            kind = "select",
            category = category(entity.entityCategory),
            state = peek(entity.getState),
            options = entity.getOptions?.invoke() ?: entity.options,
            selectSet = entity.setState,
        )
        is NumberEntity -> Item(
            id = entity.objectId,
            name = entity.name,
            kind = "number",
            category = category(entity.entityCategory),
            state = peek(entity.getState)?.toString(),
            min = entity.minValue,
            max = entity.maxValue,
            numberSet = entity.setState,
        )
        is TextEntity -> Item(
            id = entity.objectId,
            name = entity.name,
            kind = "text",
            category = category(entity.entityCategory),
            state = peek(entity.getState),
            textSet = entity.setState,
        )
        is LockEntity -> Item(
            id = entity.objectId,
            name = entity.name,
            kind = "lock",
            category = category(entity.entityCategory),
            state = peek(entity.getState)?.name,
            lockSet = { command -> entity.setState(command, null) },
        )
        else -> null
    }

    private fun keys(item: Item): List<String> = listOf(
        normalize(item.name),
        normalize(item.id),
        normalize(item.id.replace('_', ' ')),
    ).filter { it.isNotEmpty() }

    private fun category(raw: EntityCategory): String = when (raw) {
        EntityCategory.ENTITY_CATEGORY_CONFIG -> "config"
        EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC -> "diagnostic"
        else -> "none"
    }

    private fun <T> peek(flow: kotlinx.coroutines.flow.Flow<T>): T? =
        (flow as? StateFlow<T>)?.value

    private fun namesMeet(spoken: String, candidate: String): Boolean {
        if (spoken.length < 2 || candidate.length < 2) return spoken == candidate
        return HaSpokenHear.meets(spoken, candidate)
    }

    private fun isTheme(key: String): Boolean =
        key.contains("theme") || key.contains("style") || key.contains("主题")

    /** Null when this Ava has no browser-display feature. */
    internal fun browserDisplayOn(): Boolean? {
        val item = catalog(noneOnly = true).firstOrNull { it.id == BROWSER_DISPLAY } ?: return null
        return item.state == "on"
    }

    /** True when set would write the display switch to the state it already has. */
    internal fun displaySwitchUnchanged(id: String, state: String?, on: Boolean): Boolean =
        id == BROWSER_DISPLAY && state == if (on) "on" else "off"

    /** True when the spoken name is the bind setting, not a request to start a countdown. */
    internal fun prefersTimerSetting(spoken: String): Boolean = isTimer(normalize(spoken))

    /** Drop the HA-timer bind entry unless the user named that setting. */
    internal fun filterBindTimer(spoken: String, hits: List<Item>): List<Item> {
        if (isTimer(normalize(spoken))) return hits
        return hits.filterNot { it.id.endsWith("_timer") }
    }

    private fun isTimer(key: String): Boolean {
        val namedSetting = key.contains("entity") || key.contains("bind") ||
            key.contains("实体") || key.contains("绑定")
        if (!namedSetting) return false
        return key.contains("timer") || key.contains("countdown") ||
            key.contains("倒计时") || key.contains("计时")
    }

    private fun isSlot(key: String): Boolean =
        key.contains("slot") || key.contains("槽")

    private fun normalize(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return ""
        val sb = StringBuilder(lower.length)
        var space = false
        for (ch in lower) {
            val keep = ch.isLetterOrDigit() || ch == '.'
            if (keep) {
                sb.append(ch)
                space = false
            } else if (!space) {
                sb.append(' ')
                space = true
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }
}
