package com.example.ava.localllm

import com.example.ava.homeassistant.HaEntityNames
import com.example.ava.homeassistant.entity.HaEntitySummary
import java.util.Locale

/**
 * Spoken-name → entity lookup. The tool model only emits a name; this index
 * composes interest slots and (when HA is signed in) the Assist expose list.
 *
 * Names come from the same places Assist's own matcher reads: friendly name,
 * registry aliases, and `<area> <name>` (own area or the device's). The model
 * never sees this table.
 */
class DeviceIndex {
    private val entities = LinkedHashMap<String, HaEntitySummary>()
    private val keys = LinkedHashMap<String, MutableSet<String>>() // retain collisions for clarification
    private val extraNames = HashMap<String, MutableList<String>>() // entityId → aliases / area combos
    private val areaNames = HashMap<String, List<String>>() // entityId → registry area names

    val size: Int get() = entities.size
    val values: Collection<HaEntitySummary> get() = entities.values

    fun add(entity: HaEntitySummary) {
        if (entity.entityId.isBlank() || '.' !in entity.entityId) return
        val previous = entities[entity.entityId]
        val merged = if (previous == null) {
            entity
        } else {
            val name = when {
                entity.name.isNotBlank() && entity.name != prettyObjectId(entity.entityId) -> entity.name
                previous.name.isNotBlank() -> previous.name
                else -> entity.name
            }
            val state = if (entity.state != "unknown" && entity.state.isNotBlank()) entity.state else previous.state
            previous.copy(name = name, state = state)
        }
        entities[entity.entityId] = merged
        keys.values.forEach { it.remove(entity.entityId) }
        indexKeys(merged)
        extraNames[entity.entityId]?.forEach { putKey(it, entity.entityId) }
    }

    fun addAll(list: Iterable<HaEntitySummary>) {
        for (e in list) add(e)
    }

    /** Attach registry names (override, aliases, area) to an entity already in the index. */
    fun addNames(entityId: String, names: HaEntityNames) {
        val entity = entities[entityId] ?: return
        if (names.areaNames.isNotEmpty()) areaNames[entityId] = names.areaNames
        val bucket = extraNames.getOrPut(entityId) { ArrayList() }
        val base = (listOf(entity.name) + names.names).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (n in names.names) {
            val t = n.trim()
            if (t.isEmpty() || t in bucket) continue
            bucket += t
            putKey(t, entityId)
        }
        for (area in names.areaNames) {
            val a = area.trim()
            if (a.isEmpty()) continue
            for (n in base) {
                // "客厅灯" and "living room light" both: with and without separator.
                for (combo in listOf("$a$n", "$a $n")) {
                    if (combo in bucket) continue
                    bucket += combo
                    putKey(combo, entityId)
                }
            }
        }
    }

    /**
     * Claw `_resolve_entity_for_query`: exact entity_id first, then
     * SmartDiscovery-style [discover] on the spoken hint. Ambiguous → null.
     */
    fun resolve(hint: String, domain: String? = null): HaEntitySummary? =
        unique(candidates(hint, domain, limit = 2))

    fun candidates(hint: String, domain: String? = null, limit: Int = 8): List<HaEntitySummary> {
        val raw = hint.trim()
        if (raw.isEmpty() || HaServicePayload.isMatchToken(raw)) return emptyList()
        entities[raw]?.let { hit ->
            return if (domain == null || hit.domain == domain) listOf(hit) else emptyList()
        }
        val target = HaSpokenTarget.parse(raw, domain)
        val norm = normalize(HaSpokenTarget.unwrap(raw))
        if (norm.isEmpty() && target.domain == null) return emptyList()
        if (isGenericHint(norm) && target.domain == null) return emptyList()
        if (norm.isNotEmpty() && !isGenericHint(norm)) {
            val exact = keys[norm].orEmpty().mapNotNull { entities[it] }
                .filter { domain == null || it.domain == domain }
            if (exact.isNotEmpty()) return exact.take(limit)
        }
        return discover(nameContains = raw, domain = domain, limit = limit)
    }

    fun match(spoken: String): HaEntitySummary? = resolve(spoken)

    /** The registry area this entity sits in, so the host can name its own room. */
    fun areaOf(entityId: String): String? =
        areaNames[entityId.trim()]?.firstOrNull { it.isNotBlank() }?.trim()

    /**
     * Claw SmartDiscovery `name_contains` + optional domain/area. The host
     * unwraps "打开灯光一" the same way Claw splits "bedroom light".
     */
    fun discover(
        nameContains: String? = null,
        domain: String? = null,
        area: String? = null,
        limit: Int = 8,
    ): List<HaEntitySummary> {
        val areaNorm = area?.let { normalize(it) }.orEmpty()
        val target = nameContains?.let { HaSpokenTarget.parse(it, domain) }
        val domainUse = domain ?: target?.domain
        val name = when {
            target == null -> ""
            target.name.isNotEmpty() -> {
                val scoped = if (domainUse != null || areaNorm.isNotEmpty()) {
                    stripListScope(target.name)
                } else {
                    target.name
                }
                if (scoped.isEmpty() && domainUse == null && areaNorm.isEmpty()) target.name else scoped
            }
            else -> ""
        }
        if (name.isEmpty() && domainUse == null && areaNorm.isEmpty()) return emptyList()
        val out = ArrayList<HaEntitySummary>()
        for (e in entities.values) {
            if (domainUse != null && e.domain != domainUse) continue
            if (name.isNotEmpty() && !nameHits(e, name, domainBound = domainUse != null)) continue
            if (areaNorm.isNotEmpty() && !areaHits(e, areaNorm)) continue
            out += e
            if (out.size >= limit) break
        }
        return out
    }

    fun hintNames(limit: Int = HINT_LIMIT): List<String> =
        entities.values.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(limit)

    private fun unique(hits: List<HaEntitySummary>): HaEntitySummary? {
        if (hits.isEmpty()) return null
        val ids = hits.map { it.entityId }.toSet()
        return if (ids.size == 1) hits.first() else null
    }

    private fun nameHits(e: HaEntitySummary, name: String, domainBound: Boolean = false): Boolean {
        val shortOk = domainBound && name.isNotEmpty() && name.all { it.isDigit() }
        if (!shortOk && (name.length < MIN_SUBSTRING_KEY || isGenericHint(name))) return false
        if (name.contains('.')) {
            val id = normalize(e.entityId)
            if (id == name || id.contains(name) || name.contains(id)) return true
        }
        return candidateNames(e).any { key ->
            if (key.isEmpty()) return@any false
            if (key == name) return@any true
            if (shortOk) return@any digitTokenHits(key, name)
            if (key.length < MIN_SUBSTRING_KEY || isGenericHint(key)) return@any false
            key.contains(name) || name.contains(key) ||
                HaSpokenHear.close(key, name, loose = domainBound)
        }
    }

    /** "灯光1" / "light 1" / "lamp1" match spoken ordinal 1; "11" does not match "1". */
    private fun digitTokenHits(key: String, digit: String): Boolean {
        if (key == digit) return true
        if (!key.endsWith(digit)) return false
        val prev = key[key.length - digit.length - 1]
        return !prev.isDigit()
    }

    private fun areaHits(e: HaEntitySummary, area: String): Boolean {
        fun hit(raw: String): Boolean {
            val n = normalize(raw)
            if (n.contains(area) || area.contains(n)) return true
            return area.length >= 3 && HaSpokenHear.close(n, area)
        }
        if (areaNames[e.entityId]?.any { hit(it) } == true) return true
        val extras = extraNames[e.entityId].orEmpty()
        return extras.any { hit(it) } || hit(e.name)
    }

    private fun candidateNames(e: HaEntitySummary): Sequence<String> = sequence {
        yield(normalize(e.name))
        yield(normalize(e.entityId))
        yield(normalize(e.entityId.substringAfter('.')))
        extraNames[e.entityId]?.forEach { yield(normalize(it)) }
    }.filter { it.isNotEmpty() }

    private fun indexKeys(entity: HaEntitySummary) {
        putKey(entity.name, entity.entityId)
        putKey(entity.entityId, entity.entityId)
        putKey(entity.entityId.substringAfter('.'), entity.entityId)
        putKey(prettyObjectId(entity.entityId), entity.entityId)
    }

    private fun putKey(key: String, entityId: String) {
        val n = normalize(key)
        if (n.isNotEmpty()) keys.getOrPut(n) { LinkedHashSet() }.add(entityId)
    }

    companion object {
        const val HINT_LIMIT = 12
        private const val MIN_SUBSTRING_KEY = 2

        /** Bare domain words must not uniquely resolve a random device. */
        private val GENERIC_HINTS = setOf(
            "灯", "灯光", "灯带", "灯具", "开关", "插座", "窗帘", "卷帘", "百叶", "风扇", "空调",
            "门锁", "锁", "电视", "音箱", "音响", "音乐", "歌曲", "播放器",
            "加湿器", "除湿机", "热水器", "阀门", "场景", "割草机", "扫地机",
            "摄像头", "监控",
            "light", "lights", "lamp", "lamps", "switch", "fan", "lock", "cover",
            "climate", "tv", "vacuum", "media player", "mediaplayer",
            "humidifier", "valve", "scene", "water heater", "lawn mower",
            "camera", "cam",
        )

        internal fun isGenericHint(name: String): Boolean = name in GENERIC_HINTS

        /**
         * "全屋" / "所有灯" is a type list, not a device name. Only strip
         * when a domain or area already bounds the walk — never dump
         * every exposed entity.
         */
        internal fun stripListScope(name: String): String {
            var s = normalize(name)
            if (s.isEmpty() || s in HOUSE_SCOPE_EXACT) return ""
            var changed = true
            while (changed && s.isNotEmpty()) {
                changed = false
                if (s in HOUSE_SCOPE_EXACT) return ""
                for (prefix in HOUSE_SCOPE_PREFIX) {
                    if (s == prefix) return ""
                    if (s.startsWith(prefix)) {
                        s = normalize(s.removePrefix(prefix))
                        changed = true
                        break
                    }
                }
            }
            return if (s in HOUSE_SCOPE_EXACT) "" else s
        }

        private val HOUSE_SCOPE_EXACT = setOf(
            "全屋", "全家", "整屋", "全房", "全宅", "所有", "全部", "每一个",
            "whole house", "entire house", "all the", "all",
        )
        private val HOUSE_SCOPE_PREFIX = listOf(
            "全屋的", "全屋", "全家的", "全家", "整屋", "全房", "全宅",
            "所有的", "所有", "全部的", "全部", "每一个",
        ).sortedByDescending { it.length }

        fun prettyObjectId(entityId: String): String =
            entityId.substringAfter('.').replace('_', ' ').trim()

        fun fromExposedIds(
            ids: Collection<String>,
            known: Map<String, HaEntitySummary> = emptyMap(),
        ): List<HaEntitySummary> {
            val out = ArrayList<HaEntitySummary>(ids.size)
            for (id in ids) {
                val trimmed = id.trim()
                if (trimmed.isEmpty() || '.' !in trimmed) continue
                val cached = known[trimmed]
                out += cached ?: HaEntitySummary(
                    entityId = trimmed,
                    name = prettyObjectId(trimmed),
                    domain = trimmed.substringBefore('.'),
                    state = "unknown",
                )
            }
            return out
        }

        internal fun normalize(raw: String): String {
            val lower = HaSpokenLocale.foldDigits(raw.trim().lowercase(Locale.ROOT))
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
}
