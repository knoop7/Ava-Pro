package com.example.ava.wakewordlibrary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.ava.openwakeword.OpenWakeWordCatalogManager
import com.example.ava.openwakeword.OpenWakeWordManifest
import com.example.ava.openwakeword.OpenWakeWordModel
import com.example.ava.settings.WakeWordEngine
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class WakeWordLibraryManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, ROOT_DIR).apply { mkdirs() }
    private val microDir = File(rootDir, ENGINE_MICRO).apply { mkdirs() }
    private val vsDir = File(rootDir, ENGINE_VS).apply { mkdirs() }
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<WakeWordLibraryEntry>>(emptyList())
    val entries: StateFlow<List<WakeWordLibraryEntry>> = _entries.asStateFlow()

    val importedMicroDir: File get() = microDir
    val importedVsDir: File get() = vsDir
    val importedOpenDir: File get() = vsDir

    suspend fun importJsonUri(uri: Uri): WakeWordImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            when (classifyUri(uri)) {
                WakeWordFileKind.Json -> Unit
                WakeWordFileKind.Onnx -> {
                    val id = idFromUri(uri, WakeWordFileKind.Onnx)
                        ?: return@withContext WakeWordImportResult.Failed("invalid_name")
                    val bytes = readUriBytes(uri)
                        ?: return@withContext WakeWordImportResult.Failed("read_failed")
                    if (!OpenWakeWordCatalogManager.looksLikeOnnx(bytes)) {
                        return@withContext WakeWordImportResult.Failed("invalid_onnx")
                    }
                    val slug = OpenWakeWordCatalogManager.slug(id)
                    val ok = installOpenOnnx(
                        id = slug,
                        displayName = OpenWakeWordCatalogManager.displayName(id),
                        onnxBytes = bytes,
                    )
                    if (!ok) return@withContext WakeWordImportResult.Failed("install_failed")
                    mergePendingModels()
                    _entries.value = scanEntries()
                    return@withContext WakeWordImportResult.Complete(listOf(slug))
                }
                WakeWordFileKind.Zip -> return@withContext WakeWordImportResult.Failed("unsupported_file")
                WakeWordFileKind.Tflite -> return@withContext WakeWordImportResult.Failed("pick_json_first")
                WakeWordFileKind.Unsupported -> return@withContext WakeWordImportResult.Failed("unsupported_file")
            }
            val id = idFromUri(uri, WakeWordFileKind.Json)
                ?: return@withContext WakeWordImportResult.Failed("invalid_name")
            val bytes = readUriBytes(uri)
                ?: return@withContext WakeWordImportResult.Failed("read_failed")
            val jsonText = bytes.decodeToString()
            val engine = when {
                WakeWordJsonParser.isOpenManifest(jsonText) -> WakeWordEngine.OPEN_WAKE_WORD
                else -> WakeWordEngine.MICRO_WAKE_WORD
            }
            val outcome = when (engine) {
                WakeWordEngine.OPEN_WAKE_WORD -> installVs(id, jsonText, bytes, onnxBytes = null)
                WakeWordEngine.MICRO_WAKE_WORD -> installMicro(id, jsonText, bytes, tfliteBytes = null)
            }
            if (outcome is InstallOutcome.Error) {
                return@withContext WakeWordImportResult.Failed(outcome.message)
            }
            mergePendingModels()
            _entries.value = scanEntries()
            val entry = _entries.value.firstOrNull { it.id == id && it.engine == engine }
                ?: return@withContext WakeWordImportResult.Failed("install_failed")
            if (entry.ready) {
                WakeWordImportResult.Complete(listOf(id))
            } else {
                WakeWordImportResult.NeedsModel(id, engine)
            }
        }
    }

    suspend fun importZipUri(uri: Uri): WakeWordImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isZipUri(uri)) {
                return@withContext WakeWordImportResult.Failed("unsupported_file")
            }
            val staged = mutableMapOf<String, MutableMap<String, ByteArray>>()
            val opened = appContext.contentResolver.openInputStream(uri)
                ?: return@withContext WakeWordImportResult.Failed("read_failed")
            opened.use { stream ->
                java.util.zip.ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = entry.name.substringAfterLast('/')
                            if (fileName.isNotBlank()) {
                                stageFileName(staged, fileName, zip.readBytes())
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (staged.isEmpty()) {
                return@withContext WakeWordImportResult.Failed("zip_empty")
            }
            val batch = commitStaged(staged)
            mergePendingModels()
            _entries.value = scanEntries()
            when {
                batch.importedIds.isEmpty() ->
                    WakeWordImportResult.Failed(batch.errors.firstOrNull() ?: "zip_empty")
                else ->
                    WakeWordImportResult.Complete(batch.importedIds, batch.errors)
            }
        }
    }

    suspend fun importModelUri(
        uri: Uri,
        targetId: String,
        targetEngine: WakeWordEngine,
    ): WakeWordImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val kind = classifyUri(uri)
            val expectedKind = when (targetEngine) {
                WakeWordEngine.MICRO_WAKE_WORD -> WakeWordFileKind.Tflite
                WakeWordEngine.OPEN_WAKE_WORD -> WakeWordFileKind.Onnx
            }
            if (kind != expectedKind) {
                return@withContext WakeWordImportResult.Failed(
                    when (targetEngine) {
                        WakeWordEngine.MICRO_WAKE_WORD -> "expected_tflite"
                        WakeWordEngine.OPEN_WAKE_WORD -> "expected_onnx"
                    },
                )
            }
            val bytes = readUriBytes(uri)
                ?: return@withContext WakeWordImportResult.Failed("read_failed")
            val destDir = engineDir(targetEngine).resolve(targetId)
            if (!destDir.resolve("$targetId.json").exists()) {
                return@withContext WakeWordImportResult.Failed("missing_json")
            }
            when (targetEngine) {
                WakeWordEngine.MICRO_WAKE_WORD -> {
                    val jsonText = destDir.resolve("$targetId.json").readText()
                    val wakeWord = WakeWordJsonParser.parseMicroWakeWord(jsonText)
                        ?: return@withContext WakeWordImportResult.Failed("invalid_json")
                    destDir.resolve(wakeWord.model).writeBytes(bytes)
                }
                WakeWordEngine.OPEN_WAKE_WORD -> {
                    destDir.resolve("$targetId.onnx").writeBytes(bytes)
                    persistOpenVerifierHint(destDir, targetId, bytes)
                }
            }
            mergePendingModels()
            _entries.value = scanEntries()
            val entry = _entries.value.firstOrNull { it.id == targetId && it.engine == targetEngine }
            if (entry?.ready == true) {
                WakeWordImportResult.Complete(listOf(targetId))
            } else {
                WakeWordImportResult.Failed("install_failed")
            }
        }
    }

    fun isZipUri(uri: Uri): Boolean {
        val name = appContext.resolveDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        val mime = appContext.contentResolver.getType(uri).orEmpty()
        return name.endsWith(".zip", ignoreCase = true) ||
            mime == "application/zip" ||
            mime == "application/x-zip-compressed"
    }

    fun classifyUri(uri: Uri): WakeWordFileKind {
        if (isZipUri(uri)) return WakeWordFileKind.Zip
        val name = appContext.resolveDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        val mime = appContext.contentResolver.getType(uri).orEmpty()
        return when {
            name.endsWith(".json", ignoreCase = true) || mime == "application/json" ->
                WakeWordFileKind.Json
            name.endsWith(".tflite", ignoreCase = true) -> WakeWordFileKind.Tflite
            name.endsWith(".onnx", ignoreCase = true) -> WakeWordFileKind.Onnx
            else -> WakeWordFileKind.Unsupported
        }
    }

    suspend fun importUris(uris: List<Uri>): ImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) return@withContext ImportResult(emptyList(), emptyList())
            val staged = mutableMapOf<String, MutableMap<String, ByteArray>>()
            val errors = mutableListOf<String>()
            for (uri in uris) {
                val name = appContext.resolveDisplayName(uri) ?: uri.lastPathSegment
                if (name.isNullOrBlank()) continue
                val bytes = readUriBytes(uri)
                if (bytes == null) {
                    errors.add(name)
                    continue
                }
                stageFileName(staged, name, bytes)
            }
            val batch = commitStaged(staged)
            mergePendingModels()
            _entries.value = scanEntries()
            ImportResult(
                importedIds = batch.importedIds,
                errors = errors + batch.errors,
            )
        }
    }

    private fun stageFileName(
        staged: MutableMap<String, MutableMap<String, ByteArray>>,
        name: String,
        bytes: ByteArray,
    ) {
        when {
            name.endsWith(".json", ignoreCase = true) -> {
                val id = name.removeSuffix(".json").removeSuffix(".JSON")
                staged.getOrPut(id) { mutableMapOf() }["json"] = bytes
            }
            name.endsWith(".tflite", ignoreCase = true) -> {
                val id = name.removeSuffix(".tflite").removeSuffix(".TFLITE")
                staged.getOrPut(id) { mutableMapOf() }["tflite"] = bytes
            }
            name.endsWith(".onnx", ignoreCase = true) -> {
                val id = name.removeSuffix(".onnx").removeSuffix(".ONNX")
                staged.getOrPut(id) { mutableMapOf() }["onnx"] = bytes
            }
        }
    }

    private fun commitStaged(staged: Map<String, Map<String, ByteArray>>): ImportResult {
        val imported = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for ((id, parts) in staged) {
            val jsonBytes = parts["json"]
            if (jsonBytes == null) continue
            val jsonText = jsonBytes.decodeToString()
            when {
                WakeWordJsonParser.isOpenManifest(jsonText) -> {
                    when (val result = installVs(id, jsonText, jsonBytes, parts["onnx"])) {
                        is InstallOutcome.Ok -> imported.add(id)
                        is InstallOutcome.Error -> errors.add("$id: ${result.message}")
                    }
                }
                else -> {
                    when (val result = installMicro(id, jsonText, jsonBytes, parts["tflite"])) {
                        is InstallOutcome.Ok -> imported.add(id)
                        is InstallOutcome.Error -> errors.add("$id: ${result.message}")
                    }
                }
            }
        }
        for ((id, parts) in staged) {
            if (parts.containsKey("json")) continue
            val microDirEntry = microDir.resolve(id)
            val vsDirEntry = vsDir.resolve(id)
            parts["tflite"]?.let { bytes ->
                if (microDirEntry.isDirectory) {
                    val wakeWord = microDirEntry.resolve("$id.json").takeIf { it.exists() }?.readText()
                        ?.let(WakeWordJsonParser::parseMicroWakeWord)
                    if (wakeWord != null) {
                        microDirEntry.resolve(wakeWord.model).writeBytes(bytes)
                        imported.add(id)
                    }
                }
            }
            parts["onnx"]?.let { bytes ->
                if (vsDirEntry.isDirectory) {
                    vsDirEntry.resolve("$id.onnx").writeBytes(bytes)
                    persistOpenVerifierHint(vsDirEntry, id, bytes)
                    imported.add(id)
                } else if (OpenWakeWordCatalogManager.looksLikeOnnx(bytes)) {
                    val slug = OpenWakeWordCatalogManager.slug(id)
                    if (installOpenOnnx(
                            id = slug,
                            displayName = OpenWakeWordCatalogManager.displayName(id),
                            onnxBytes = bytes,
                        )
                    ) {
                        imported.add(slug)
                    }
                }
            }
        }
        return ImportResult(imported.distinct(), errors)
    }

    suspend fun deleteEntry(id: String, engine: WakeWordEngine): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val base = engineDir(engine)
            var removed = false
            for (name in idAliases(id)) {
                val dir = base.resolve(name)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    removed = true
                }
            }
            _entries.value = scanEntries()
            removed
        }
    }

    suspend fun refresh() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                mergePendingModels()
                _entries.value = scanEntries()
            }
        }
    }

    private fun mergePendingModels() {
        for (engine in listOf(WakeWordEngine.MICRO_WAKE_WORD, WakeWordEngine.OPEN_WAKE_WORD)) {
            val base = engineDir(engine)
            base.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val id = dir.name
                val jsonFile = dir.resolve("$id.json")
                if (!jsonFile.exists()) return@forEach
                val jsonText = jsonFile.readText()
                when (engine) {
                    WakeWordEngine.MICRO_WAKE_WORD -> {
                        val wakeWord = WakeWordJsonParser.parseMicroWakeWord(jsonText) ?: return@forEach
                        val modelName = wakeWord.model
                        val modelFile = dir.resolve(modelName)
                        if (!modelFile.exists()) {
                            dir.listFiles()
                                ?.firstOrNull { it.name.endsWith(".tflite", ignoreCase = true) }
                                ?.copyTo(modelFile, overwrite = true)
                        }
                    }
                    WakeWordEngine.OPEN_WAKE_WORD -> {
                        val onnxFile = dir.resolve("$id.onnx")
                        if (!onnxFile.exists()) {
                            dir.listFiles()
                                ?.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) }
                                ?.copyTo(onnxFile, overwrite = true)
                        }
                    }
                }
            }
        }
    }

    private fun installMicro(
        id: String,
        jsonText: String,
        jsonBytes: ByteArray,
        tfliteBytes: ByteArray?,
    ): InstallOutcome {
        val wakeWord = WakeWordJsonParser.parseMicroWakeWord(jsonText)
            ?: return InstallOutcome.Error("invalid json")
        val destDir = microDir.resolve(id).apply { mkdirs() }
        destDir.resolve("$id.json").writeBytes(jsonBytes)
        val modelName = wakeWord.model
        val modelFile = destDir.resolve(modelName)
        if (tfliteBytes != null) {
            modelFile.writeBytes(tfliteBytes)
        }
        return InstallOutcome.Ok
    }

    fun installMicroFromCatalog(
        id: String,
        displayName: String,
        tfliteBytes: ByteArray,
        author: String = "",
        sourceUrl: String = "",
        license: String = "",
        probabilityCutoff: Float = 0.85f,
        slidingWindowSize: Int = 5,
        featureStepSize: Int = 10,
        tensorArenaSize: Int = 22860,
    ): Boolean {
        if (id.isBlank() || tfliteBytes.size < 64) return false
        val modelFile = "$id.tflite"
        val obj = com.google.gson.JsonObject().apply {
            addProperty("type", "micro")
            addProperty("wake_word", displayName)
            addProperty("author", author)
            addProperty("website", sourceUrl)
            addProperty("model", modelFile)
            add("trained_languages", com.google.gson.JsonArray().apply { add("en") })
            addProperty("version", 3)
            add("micro", com.google.gson.JsonObject().apply {
                addProperty("probability_cutoff", probabilityCutoff)
                addProperty("feature_step_size", featureStepSize)
                addProperty("sliding_window_size", slidingWindowSize)
                addProperty("tensor_arena_size", tensorArenaSize)
            })
        }
        val json = Gson().toJson(obj)
        replaceEngineDir(microDir, id)
        val destDir = microDir.resolve(id).apply { mkdirs() }
        destDir.resolve("$id.json").writeText(json)
        destDir.resolve(modelFile).writeBytes(tfliteBytes)
        return true
    }

    fun installOpenOnnx(
        id: String,
        displayName: String,
        onnxBytes: ByteArray,
        author: String = "",
        website: String = "",
        license: String = "",
        sourceUrl: String = "",
        threshold: Float = 0.5f,
        requiredHits: Int = 1,
        cooldownMs: Int = 2000,
    ): Boolean {
        if (id.isBlank() || !OpenWakeWordCatalogManager.looksLikeOnnx(onnxBytes)) return false
        val manifest = OpenWakeWordManifest.wrapOnnx(
            id = id,
            wakeWord = displayName.ifBlank { OpenWakeWordCatalogManager.displayName(id) },
            author = author,
            website = website,
            license = license,
            sourceUrl = sourceUrl,
            threshold = threshold,
            requiredHits = requiredHits,
            cooldownMs = cooldownMs,
            builtInVerifier = OpenWakeWordModel.containsBuiltInVerifier(onnxBytes),
        )
        replaceEngineDir(vsDir, id)
        val destDir = vsDir.resolve(id).apply { mkdirs() }
        destDir.resolve("$id.json").writeText(Gson().toJson(OpenWakeWordManifest.toJsonObject(manifest)))
        destDir.resolve("$id.onnx").writeBytes(onnxBytes)
        return true
    }

    private fun installVs(
        id: String,
        jsonText: String,
        jsonBytes: ByteArray,
        onnxBytes: ByteArray?,
    ): InstallOutcome {
        val manifest = WakeWordJsonParser.parseOpenManifest(jsonText)
            ?: return InstallOutcome.Error("invalid manifest")
        val destDir = vsDir.resolve(id).apply { mkdirs() }
        destDir.resolve("$id.json").writeBytes(jsonBytes)
        val onnxFile = destDir.resolve("$id.onnx")
        if (onnxBytes != null) {
            onnxFile.writeBytes(onnxBytes)
            persistOpenVerifierHint(destDir, id, onnxBytes)
        }
        return InstallOutcome.Ok
    }

    private fun persistOpenVerifierHint(dir: File, id: String, onnxBytes: ByteArray) {
        val jsonFile = dir.resolve("$id.json")
        if (!jsonFile.exists()) return
        val manifest = OpenWakeWordManifest.fromJson(jsonFile.readText()) ?: return
        val updated = manifest.copy(
            builtInVerifier = OpenWakeWordModel.containsBuiltInVerifier(onnxBytes),
        )
        jsonFile.writeText(Gson().toJson(OpenWakeWordManifest.toJsonObject(updated)))
    }

    private fun idFromUri(uri: Uri, kind: WakeWordFileKind): String? {
        val name = appContext.resolveDisplayName(uri) ?: uri.lastPathSegment ?: return null
        return when (kind) {
            WakeWordFileKind.Json -> when {
                name.endsWith(".json", ignoreCase = true) ->
                    name.removeSuffix(".json").removeSuffix(".JSON")
                else -> name.substringBeforeLast('.').takeIf { it.isNotBlank() }
            }
            WakeWordFileKind.Tflite -> when {
                name.endsWith(".tflite", ignoreCase = true) ->
                    name.removeSuffix(".tflite").removeSuffix(".TFLITE")
                else -> name.substringBeforeLast('.').takeIf { it.isNotBlank() }
            }
            WakeWordFileKind.Onnx -> when {
                name.endsWith(".onnx", ignoreCase = true) ->
                    name.removeSuffix(".onnx").removeSuffix(".ONNX")
                else -> name.substringBeforeLast('.').takeIf { it.isNotBlank() }
            }
            WakeWordFileKind.Zip,
            WakeWordFileKind.Unsupported -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun scanEntries(): List<WakeWordLibraryEntry> = buildList {
        addAll(scanEngineDir(WakeWordEngine.MICRO_WAKE_WORD))
        addAll(scanEngineDir(WakeWordEngine.OPEN_WAKE_WORD))
    }.sortedBy { it.displayName.lowercase() }

    private fun scanEngineDir(engine: WakeWordEngine): List<WakeWordLibraryEntry> {
        val base = engineDir(engine)
        return base.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val id = dir.name
            if (id.startsWith(".")) return@mapNotNull null
            val jsonFile = dir.resolve("$id.json")
            if (!jsonFile.exists()) return@mapNotNull null
            val jsonText = jsonFile.readText()
            when (engine) {
                WakeWordEngine.MICRO_WAKE_WORD -> {
                    val wakeWord = WakeWordJsonParser.parseMicroWakeWord(jsonText) ?: return@mapNotNull null
                    val modelFile = dir.resolve(wakeWord.model)
                    WakeWordLibraryEntry(
                        id = id,
                        displayName = wakeWord.wake_word,
                        engine = engine,
                        ready = modelFile.exists() && modelFile.length() > 0,
                        modelFileName = wakeWord.model,
                    )
                }
                WakeWordEngine.OPEN_WAKE_WORD -> {
                    val manifest = WakeWordJsonParser.parseOpenManifest(jsonText) ?: return@mapNotNull null
                    val onnxFile = dir.resolve("$id.onnx")
                    WakeWordLibraryEntry(
                        id = id,
                        displayName = manifest.wakeWord.ifBlank { id },
                        engine = engine,
                        ready = onnxFile.exists() && onnxFile.length() > 0,
                        modelFileName = "$id.onnx",
                    )
                }
            }
        } ?: emptyList()
    }

    private fun engineDir(engine: WakeWordEngine): File = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> microDir
        WakeWordEngine.OPEN_WAKE_WORD -> vsDir
    }

    private fun replaceEngineDir(base: File, id: String) {
        for (name in idAliases(id)) {
            val dir = base.resolve(name)
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    private fun idAliases(id: String): Set<String> {
        val raw = id.trim()
        if (raw.isEmpty()) return emptySet()
        val lower = raw.lowercase()
        return buildSet {
            add(raw)
            add(lower)
            when {
                lower.startsWith("okay_") -> add("ok_" + lower.removePrefix("okay_"))
                lower.startsWith("ok_") -> add("okay_" + lower.removePrefix("ok_"))
            }
        }
    }

    private fun readUriBytes(uri: Uri): ByteArray? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private sealed class InstallOutcome {
        data object Ok : InstallOutcome()
        data class Error(val message: String) : InstallOutcome()
    }

    data class ImportResult(
        val importedIds: List<String>,
        val errors: List<String>,
    )

    companion object {
        private const val ROOT_DIR = "wake_word_library"
        private const val ENGINE_MICRO = "micro"
        private const val ENGINE_VS = "vs"

        @Volatile
        private var instance: WakeWordLibraryManager? = null

        fun getInstance(context: Context): WakeWordLibraryManager {
            return instance ?: synchronized(this) {
                instance ?: WakeWordLibraryManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

private fun Context.resolveDisplayName(uri: Uri): String? {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return null
}
