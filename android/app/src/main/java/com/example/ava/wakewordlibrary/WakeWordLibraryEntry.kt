package com.example.ava.wakewordlibrary

import com.example.ava.settings.WakeWordEngine

data class WakeWordLibraryEntry(
    val id: String,
    val displayName: String,
    val engine: WakeWordEngine,
    val ready: Boolean,
    val modelFileName: String?,
)

sealed class WakeWordImportResult {
    data class Complete(
        val ids: List<String>,
        val warnings: List<String> = emptyList(),
    ) : WakeWordImportResult()

    data class NeedsModel(val id: String, val engine: WakeWordEngine) : WakeWordImportResult()
    data class Failed(val message: String) : WakeWordImportResult()
}

enum class WakeWordFileKind {
    Json,
    Tflite,
    Onnx,
    Zip,
    Unsupported,
}
