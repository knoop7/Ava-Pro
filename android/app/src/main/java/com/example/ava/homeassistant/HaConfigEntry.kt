package com.example.ava.homeassistant

data class HaConfigEntrySummary(
    val entryId: String,
    val title: String,
    val domain: String,
    val state: String,
)

data class HaDeviceSummary(
    val id: String,
    val name: String,
    val primaryConfigEntry: String?,
    val configEntries: List<String>,
    val connections: List<Pair<String, String>>,
    val identifiers: List<Pair<String, String>>,
    val viaDeviceId: String? = null,
)

data class HaOptionsFlowForm(
    val flowId: String,
    val defaults: Map<String, Any?>,
)
