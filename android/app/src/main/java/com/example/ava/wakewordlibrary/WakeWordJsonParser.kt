package com.example.ava.wakewordlibrary

import com.example.ava.microwakeword.Micro
import com.example.ava.microwakeword.WakeWord
import com.example.ava.openwakeword.OpenWakeWordManifest
import com.google.gson.JsonParser

internal object WakeWordJsonParser {
    fun parseMicroWakeWord(json: String): WakeWord? = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        val microObj = obj.getAsJsonObject("micro")
            ?: return@runCatching null
        WakeWord(
            type = obj.get("type")?.asString ?: "micro",
            wake_word = obj.get("wake_word")?.asString?.takeIf { it.isNotBlank() }
                ?: return@runCatching null,
            author = obj.get("author")?.asString ?: "",
            website = obj.get("website")?.asString ?: "",
            model = obj.get("model")?.asString?.takeIf { it.isNotBlank() }
                ?: return@runCatching null,
            trained_languages = obj.getAsJsonArray("trained_languages")
                ?.map { it.asString }
                ?.toTypedArray()
                ?: emptyArray(),
            version = obj.get("version")?.asInt ?: 2,
            micro = Micro(
                probability_cutoff = microObj.get("probability_cutoff")?.asFloat ?: 0.85f,
                feature_step_size = microObj.get("feature_step_size")?.asInt ?: 10,
                sliding_window_size = microObj.get("sliding_window_size")?.asInt ?: 5,
                tensor_arena_size = microObj.get("tensor_arena_size")?.asInt ?: 30000,
                minimum_esphome_version = microObj.get("minimum_esphome_version")?.asString ?: "",
            ),
        )
    }.getOrNull()

    fun parseOpenManifest(json: String): OpenWakeWordManifest? = OpenWakeWordManifest.fromJson(json)

    fun isOpenManifest(json: String): Boolean = OpenWakeWordManifest.isOpenFormat(json)
}
