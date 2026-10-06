package com.example.ava.openwakeword

/**
 * Wake-word 1/2 picker rules for openWakeWord.
 *
 * The picker is Ava's curated set (bundled assets + `index.json`). Community
 * models and file imports live in the wake-word library; they reach the picker
 * only after the user sets them as an active wake word.
 */
object OpenWakeWordSelectorPolicy {
    fun catalogIdKey(id: String): String =
        id.trim().lowercase().replace("okay_", "ok_")

    /**
     * Curated downloads are served from the Ava repo. Community ONNX files come
     * from fwartner (or a user file) and must not piggy-back on this check —
     * bundled `ok_nabu.json` itself cites fwartner as upstream.
     */
    fun isAvaCuratedSource(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains("fwartner/home-assistant-wakewords-collection", ignoreCase = true)) {
            return false
        }
        return url.contains("/knoop7/Ava/", ignoreCase = true)
    }

    fun visibleInWakeWordPicker(
        id: String,
        bundled: Boolean,
        sourceUrl: String,
        curatedCatalogIds: Set<String>,
        selectedIds: Set<String>,
    ): Boolean {
        if (bundled) return true
        if (id in selectedIds) return true
        val key = catalogIdKey(id)
        if (key.isNotEmpty() && curatedCatalogIds.any { catalogIdKey(it) == key }) return true
        return isAvaCuratedSource(sourceUrl)
    }

    fun removableFromWakeWordPicker(
        id: String,
        imported: Boolean,
        bundled: Boolean,
        sourceUrl: String,
        curatedCatalogIds: Set<String>,
    ): Boolean {
        if (!imported || bundled) return false
        val key = catalogIdKey(id)
        if (key.isNotEmpty() && curatedCatalogIds.any { catalogIdKey(it) == key }) return true
        return isAvaCuratedSource(sourceUrl)
    }
}
