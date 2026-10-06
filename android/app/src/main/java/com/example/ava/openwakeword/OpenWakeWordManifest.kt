package com.example.ava.openwakeword

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

data class OpenWakeWordManifest(
    val type: String = "openwakeword",
    val format: String = "openwakeword-v1",
    val id: String = "",
    @SerializedName("wake_word")
    val wakeWord: String = "",
    val model: String = "",
    val author: String = "",
    val website: String = "",
    val license: String = "",
    @SerializedName("source_url")
    val sourceUrl: String = "",
    val openwakeword: OpenWakeWordRuntime? = null,
    @SerializedName("built_in_verifier")
    val builtInVerifier: Boolean? = null,
    @SerializedName("stop_classifier")
    val stopClassifier: Boolean = false,
    /**
     * Tempo-remapped and silence-tail-patched scoring windows. On by default: these are
     * what recover one-breath "wake word + command" utterances, and every model shipped
     * or imported so far has run with them. A manifest may set `"alternate_paths": false`
     * to confine a model to the main path when its author measured them to false-fire.
     */
    @SerializedName("alternate_paths")
    val alternatePaths: Boolean = true,
) {
    companion object {
        fun wrapOnnx(
            id: String,
            wakeWord: String,
            author: String = "",
            website: String = "",
            license: String = "",
            sourceUrl: String = "",
            threshold: Float = 0.5f,
            requiredHits: Int = OpenWakeWordCutoffPolicy.DEFAULT_REQUIRED_HITS,
            cooldownMs: Int = 2000,
            slidingWindowSize: Int = OpenWakeWordCutoffPolicy.DEFAULT_SLIDING_WINDOW,
            builtInVerifier: Boolean? = null,
            alternatePaths: Boolean = true,
        ): OpenWakeWordManifest = OpenWakeWordManifest(
            type = "openwakeword",
            format = "openwakeword-v1",
            id = id,
            wakeWord = wakeWord,
            model = "$id.onnx",
            author = author,
            website = website,
            license = license,
            sourceUrl = sourceUrl,
            openwakeword = OpenWakeWordRuntime(
                threshold = threshold,
                requiredHits = requiredHits,
                cooldownMs = cooldownMs,
                slidingWindowSize = slidingWindowSize,
            ),
            builtInVerifier = builtInVerifier,
            stopClassifier = false,
            alternatePaths = alternatePaths,
        )

        fun isOpenFormat(json: String): Boolean = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val format = obj.get("format")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            format.contains("openwakeword", ignoreCase = true) ||
                type.equals("openwakeword", ignoreCase = true)
        }.getOrDefault(false)

        fun fromJson(json: String): OpenWakeWordManifest? = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            if (!isOpenFormat(json)) return@runCatching null
            val id = obj.string("id").orEmpty()
            val model = modelFileName(obj, id) ?: return@runCatching null
            val runtime = obj.get("openwakeword")?.takeIf { it.isJsonObject }?.asJsonObject
            OpenWakeWordManifest(
                type = obj.string("type") ?: "openwakeword",
                format = obj.string("format") ?: "openwakeword-v1",
                id = id,
                wakeWord = obj.string("wake_word").orEmpty(),
                model = model,
                author = obj.string("author").orEmpty(),
                website = obj.string("website").orEmpty(),
                license = obj.string("license").orEmpty(),
                sourceUrl = obj.string("source_url").orEmpty(),
                openwakeword = OpenWakeWordRuntime(
                    threshold = runtime?.float("threshold") ?: 0.5f,
                    requiredHits = runtime?.int("required_hits") ?: 1,
                    cooldownMs = runtime?.int("cooldown_ms") ?: 2000,
                    // Faithful mirror of the file, including the literal 1 that older
                    // installs carry. The gate detection actually runs with is resolved
                    // by OpenWakeWordCutoffPolicy.effectiveGate, so it also covers
                    // manifests already on disk rather than only newly parsed ones.
                    slidingWindowSize = runtime?.int("sliding_window_size") ?: 1,
                ),
                builtInVerifier = obj.get("built_in_verifier")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean,
                stopClassifier = obj.get("stop_classifier")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean ?: false,
                alternatePaths = obj.get("alternate_paths")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean ?: true,
            )
        }.getOrNull()

        fun toJsonObject(manifest: OpenWakeWordManifest): JsonObject = JsonObject().apply {
            addProperty("type", manifest.type.ifBlank { "openwakeword" })
            addProperty("format", manifest.format.ifBlank { "openwakeword-v1" })
            addProperty("id", manifest.id)
            addProperty("wake_word", manifest.wakeWord)
            addProperty("model", manifest.model.ifBlank { "${manifest.id}.onnx" })
            addProperty("author", manifest.author)
            addProperty("website", manifest.website)
            addProperty("license", manifest.license)
            addProperty("source_url", manifest.sourceUrl)
            add("openwakeword", JsonObject().apply {
                val runtime = manifest.openwakeword ?: OpenWakeWordRuntime()
                addProperty("threshold", runtime.threshold)
                addProperty("required_hits", runtime.requiredHits)
                addProperty("cooldown_ms", runtime.cooldownMs)
                addProperty("sliding_window_size", runtime.slidingWindowSize)
            })
            manifest.builtInVerifier?.let { addProperty("built_in_verifier", it) }
            addProperty("stop_classifier", manifest.stopClassifier)
            addProperty("alternate_paths", manifest.alternatePaths)
        }

        private fun modelFileName(obj: JsonObject, id: String): String? {
            val el = obj.get("model") ?: return id.takeIf { it.isNotBlank() }?.let { "$it.onnx" }
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                return el.asString.takeIf { it.isNotBlank() }
            }
            if (el.isJsonObject) {
                val nested = el.asJsonObject
                return nested.string("file")
                    ?: nested.string("onnx")
                    ?: nested.string("name")
                    ?: id.takeIf { it.isNotBlank() }?.let { "$it.onnx" }
            }
            return null
        }

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.int(key: String): Int? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

        private fun JsonObject.float(key: String): Float? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat
    }
}

data class OpenWakeWordRuntime(
    val threshold: Float = 0.5f,
    @SerializedName("required_hits")
    val requiredHits: Int = 1,
    @SerializedName("cooldown_ms")
    val cooldownMs: Int = 2000,
    @SerializedName("sliding_window_size")
    val slidingWindowSize: Int = 1,
)

data class OpenFrontendManifest(
    val format: String = "openwakeword-v1",
    val embedding: OpenFrontendFile? = null,
    @SerializedName("chunk_samples")
    val chunkSamples: Int = 640,
)

data class OpenFrontendFile(
    val file: String = "embedding_model.owweb",
)

class OpenWakeWordModel(
    val id: String,
    val manifest: OpenWakeWordManifest,
    private val onnxLoader: () -> ByteArray,
) {
    val onnxBytes: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { onnxLoader() }

    val displayName: String
        get() = correctedDisplayName(id, manifest.wakeWord.ifBlank { id })

    val threshold: Float
        get() = OpenWakeWordCutoffPolicy.sanitize(manifest.openwakeword?.threshold ?: 0.5f)

    /**
     * True when the classifier graph carries its own second-stage verifier: an ONNX
     * `If` node gating a verifier network on the main network's output (the official
     * hey_jarvis is built this way). New downloads persist this as manifest metadata;
     * older imports are detected by scanning for the NodeProto op_type
     * marker `0x22 0x02 "If"` — field 4, wire type 2, length 2 — which no other op
     * name ("Identity" has length 8) or graph string produces. A random byte match
     * inside weight data is ~2^-32 per position and would only make the gate follow
     * the manifest, so the scan is not parsed further.
     *
     * Deliberately lazy and only read on the detector's load path: evaluating it
     * pulls the full model file via [onnxBytes], which catalog/UI enumeration must
     * not do.
     */
    val verifierHint: Boolean
        get() = manifest.builtInVerifier == true || id.equals("hey_jarvis", ignoreCase = true)

    val hasBuiltInVerifier: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        manifest.builtInVerifier == true || containsBuiltInVerifier(onnxBytes)
    }

    companion object {
        /** Catalog correction verified against the shipped ONNX on seven voices. */
        fun correctedDisplayName(id: String, declaredName: String): String =
            when (id.lowercase()) {
                "hey_home_assistant" -> "Home Assistant"
                else -> declaredName
            }

        fun containsBuiltInVerifier(bytes: ByteArray): Boolean =
            (0..bytes.size - 4).any { i ->
                bytes[i] == 0x22.toByte() && bytes[i + 1] == 0x02.toByte() &&
                    bytes[i + 2] == 'I'.code.toByte() && bytes[i + 3] == 'f'.code.toByte()
            }
    }

    // The gate resolution covers manifests already on disk (which carry the literal
    // window=1/hits=1 our own writer produced) as well as newly parsed ones. This
    // metadata-level view deliberately omits the verifier exemption so that listing
    // models never loads their weights; the detector resolves the real gate with
    // [hasBuiltInVerifier] once the bytes are loaded anyway.
    private val gate: OpenWakeWordCutoffPolicy.GateParams
        get() = OpenWakeWordCutoffPolicy.effectiveGate(
            manifest.openwakeword?.slidingWindowSize ?: 1,
            manifest.openwakeword?.requiredHits ?: 1,
        )

    val requiredHits: Int
        get() = gate.requiredHits

    val cooldownMs: Int
        get() = manifest.openwakeword?.cooldownMs ?: 2000

    val slidingWindowSize: Int
        get() = gate.slidingWindow
}
