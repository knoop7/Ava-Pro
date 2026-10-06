package com.example.ava.homeassistant.entity

enum class HaEntityDomainFilter(val prefixes: Set<String>) {
    All(emptySet()),
    Weather(setOf("weather.")),
    Timer(setOf("timer.")),
    MediaPlayer(setOf("media_player.")),
    DawnSlot(setOf("sensor.", "binary_sensor.", "light.", "switch.", "weather.")),
    SimpleClockStatus(setOf("lock.", "cover.", "binary_sensor.", "switch.", "sensor.", "light.")),
    QuickEntity(
        setOf(
            "switch.",
            "button.",
            "light.",
            "sensor.",
            "binary_sensor.",
            "cover.",
            "climate.",
            "fan.",
            "input_boolean.",
            "input_number.",
            "input_select.",
            "input_text.",
            "scene.",
            "script.",
            "vacuum.",
            "water_heater.",
            "timer.",
            "camera.",
            "lock.",
            "automation.",
        ),
    ),
    ;

    fun matches(entity: HaEntitySummary): Boolean {
        if (prefixes.isEmpty()) return true
        return prefixes.any { entity.entityId.startsWith(it) }
    }
}

fun List<HaEntitySummary>.search(
    query: String,
    filter: HaEntityDomainFilter = HaEntityDomainFilter.All,
    limit: Int = HaEntitySearchLimit,
): List<HaEntitySummary> {
    val q = query.trim().lowercase()
    return asSequence()
        .filter { filter.matches(it) }
        .filter {
            if (q.isEmpty()) true
            else it.entityId.contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true)
        }
        .take(limit)
        .toList()
}

const val HaEntitySearchLimit = 5
const val HaEntityPickerLimit = 40