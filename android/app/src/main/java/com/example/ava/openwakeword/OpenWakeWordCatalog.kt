package com.example.ava.openwakeword

import com.example.ava.vswakeword.VsWakeWordCatalogEntry

/** Split remote catalogs so the picker and the library do not share a list. */
data class OpenWakeWordCatalog(
    val curated: List<VsWakeWordCatalogEntry> = emptyList(),
    val community: List<VsWakeWordCatalogEntry> = emptyList(),
)
