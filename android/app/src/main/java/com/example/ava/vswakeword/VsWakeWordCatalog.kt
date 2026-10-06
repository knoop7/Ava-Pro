package com.example.ava.vswakeword

/**
 * Remote catalog row for the openWakeWord picker.
 * [json] is optional — bare community ONNX files are wrapped on download.
 */
data class VsWakeWordCatalogEntry(
    val id: String,
    val name: String,
    val json: String,
    val onnx: String,
    val stopClassifier: Boolean = false,
    val author: String = "",
    val website: String = "",
    val license: String = "",
    val sha256: String = "",
    // Per-model runtime tuning from the catalog index (defaults match manifest).
    val threshold: Float = 0.5f,
    val requiredHits: Int = 1,
    val cooldownMs: Int = 2000,
)
