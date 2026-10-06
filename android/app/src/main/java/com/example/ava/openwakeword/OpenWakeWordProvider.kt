package com.example.ava.openwakeword

import android.content.res.AssetManager
import android.util.Log
import com.example.ava.settings.WakeWordEngine
import com.example.ava.wakelearn.LinearVerifierHead
import com.example.ava.wakelearn.WakeLearnStore
import com.example.microfeatures.OpenWakeWordEngine
import com.google.gson.Gson
import java.io.File

class OpenWakeWordProvider(
    private val assets: AssetManager,
    private val path: String = ASSET_PATH,
    private val importedRoot: File? = null,
    /** On-device learning output; its heads take precedence over imported and bundled ones. */
    private val learnStore: WakeLearnStore? = null,
) {
    private val gson = Gson()
    private val modelCache = mutableMapOf<String, OpenWakeWordModel>()
    private var embeddingCache: ByteArray? = null

    /** Wake-slot catalog: dedicated stop classifiers never appear here. */
    fun listModels(): List<OpenWakeWordModel> = listAllModels().filterNot { it.manifest.stopClassifier }

    /** Stop-slot catalog: every installed model, including stopClassifier heads. */
    fun listStopEligibleModels(): List<OpenWakeWordModel> = listAllModels()

    /** Asset ids only — excludes anything sitting in the wake-word library folder. */
    fun listBundledIds(): Set<String> =
        assets.list(path)
            ?.asSequence()
            ?.filter { it.endsWith(".json") }
            ?.map { it.removeSuffix(".json") }
            ?.filter { it != "frontend" }
            ?.toSet()
            .orEmpty()

    fun loadEmbedding(): ByteArray {
        embeddingCache?.let { return it }
        val frontend = runCatching {
            val json = assets.open("$path/frontend.json").bufferedReader().use { it.readText() }
            gson.fromJson(json, OpenFrontendManifest::class.java)
        }.getOrNull()
        val file = frontend?.embedding?.file?.ifBlank { null } ?: "embedding_model.owweb"
        val bytes = assets.open("$path/$file").use { it.readBytes() }
        embeddingCache = bytes
        return bytes
    }

    private fun listAllModels(): List<OpenWakeWordModel> {
        val ids = linkedSetOf<String>()
        assets.list(path)?.filter { it.endsWith(".json") }?.forEach { name ->
            val id = name.removeSuffix(".json")
            if (id != "frontend") ids.add(id)
        }
        importedRoot?.listFiles()?.filter { it.isDirectory }?.forEach { ids.add(it.name) }
        return ids.mapNotNull { id ->
            runCatching { loadModel(id) }
                .onFailure { Log.e(TAG, "Failed loading openWakeWord model $id", it) }
                .getOrNull()
        }
    }

    @Synchronized
    fun loadModel(id: String): OpenWakeWordModel = modelCache.getOrPut(id) {
        val importedDir = importedRoot?.resolve(id)
        if (importedDir?.isDirectory == true) {
            loadImportedModel(id, importedDir)
        } else {
            OpenWakeWordModel(id = id, manifest = loadManifest(id)) {
                val modelName = loadManifest(id).model.ifBlank { "$id.onnx" }
                assets.open("$path/$modelName").use { it.readBytes() }
            }
        }
    }

    private fun loadImportedModel(id: String, importedDir: File): OpenWakeWordModel {
        val jsonFile = importedDir.resolve("$id.json")
        val onnxFile = importedDir.resolve("$id.onnx").takeIf { it.exists() }
            ?: importedDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) }
            ?: importedDir.resolve("$id.onnx")
        val text = jsonFile.takeIf { it.exists() }?.readText()
        val parsed = text?.let(OpenWakeWordManifest::fromJson)
        val manifest = parsed ?: run {
            if (!onnxFile.exists() || onnxFile.length() <= 0) {
                error("not an openWakeWord manifest: $id")
            }
            val wakeWord = text?.let { raw ->
                runCatching {
                    com.google.gson.JsonParser.parseString(raw).asJsonObject
                        .get("wake_word")?.takeIf { it.isJsonPrimitive }?.asString
                }.getOrNull()
            }.orEmpty().ifBlank { id }
            OpenWakeWordManifest.wrapOnnx(id = id, wakeWord = wakeWord).also { rewritten ->
                jsonFile.writeText(gson.toJson(OpenWakeWordManifest.toJsonObject(rewritten)))
                Log.i(TAG, "rewrote $id.json as openwakeword-v1")
            }
        }
        return OpenWakeWordModel(id = id, manifest = manifest) { onnxFile.readBytes() }
    }

    /**
     * Optional glue-rescue head for a model: a tiny sidecar classifier over the
     * same embedding window, trained to spot the wake word anywhere in the
     * window so "ok nabu turn on the lights" said in one breath still wakes.
     * Convention: `<id>_rescue.onnx` beside the model. Null when absent.
     */
    fun loadRescue(id: String): ByteArray? {
        val imported = importedRoot?.resolve(id)?.resolve("${id}_rescue.onnx")
        if (imported?.exists() == true) return runCatching { imported.readBytes() }.getOrNull()
        return runCatching {
            assets.open("$path/${id}_rescue.onnx").use { it.readBytes() }
        }.getOrNull()
    }

    /**
     * Near-word verifier head for a model, most specific source first: the head
     * on-device learning trained from this household's wakes, then `<id>_verifier.bin`
     * beside an imported model, then the bundled factory calibration. A logistic layer
     * over the same 16x96 window the classifier scores; the engine consults it only at
     * fire time. Null when no source has one (the model keeps its plain behavior).
     */
    fun loadVerifier(id: String): LinearVerifierHead? =
        learnStore?.readHead(WakeWordEngine.OPEN_WAKE_WORD, id, OpenWakeWordEngine.FIRE_WINDOW_SIZE)
            ?: loadFactoryVerifier(id)

    /** Imported or bundled head only — the prior on-device learning refines. */
    fun loadFactoryVerifier(id: String): LinearVerifierHead? {
        val imported = importedRoot?.resolve(id)?.resolve("${id}_verifier.bin")
        val bytes = if (imported?.exists() == true) {
            runCatching { imported.readBytes() }.getOrNull()
        } else {
            runCatching { assets.open("$path/${id}_verifier.bin").use { it.readBytes() } }.getOrNull()
        }
        return LinearVerifierHead.parse(bytes, OpenWakeWordEngine.FIRE_WINDOW_SIZE)
    }

    fun loadManifest(id: String): OpenWakeWordManifest {
        val importedFile = importedRoot?.resolve(id)?.resolve("$id.json")
        if (importedFile?.exists() == true) return loadManifestFromFile(importedFile)
        val json = assets.open("$path/$id.json").bufferedReader().use { it.readText() }
        return OpenWakeWordManifest.fromJson(json)
            ?: error("not an openWakeWord manifest: $id")
    }

    private fun loadManifestFromFile(file: File): OpenWakeWordManifest {
        return OpenWakeWordManifest.fromJson(file.readText())
            ?: error("not an openWakeWord manifest: ${file.name}")
    }

    fun invalidateCache() {
        modelCache.clear()
        embeddingCache = null
    }

    companion object {
        private const val TAG = "OpenWakeWordProvider"
        const val ASSET_PATH = "openwakeword"
    }
}
