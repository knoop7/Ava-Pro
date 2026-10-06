package com.example.ava.mods

import android.content.Context
import android.net.Uri
import com.example.ava.net.GithubProxyUrls
import android.os.Build
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dalvik.system.DexClassLoader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class ModManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ModManager"
        private const val STORE_URL = "https://raw.githubusercontent.com/knoop7/ava-mods/main/store.json"
        private const val MODS_DIR = "mods"
        private const val REGISTRY_FILE = "registry.json"
        private val MOD_ID_PATTERN: Pattern = Pattern.compile("^[A-Za-z0-9._-]{1,64}$")
        
        @Volatile
        private var instance: ModManager? = null
        
        fun getInstance(context: Context): ModManager {
            return instance ?: synchronized(this) {
                instance ?: ModManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /** Process-sticky race winner; cleared when that mirror starts failing. */
    @Volatile
    private var preferredProxyPrefix: String? = null
    
    private val gson = Gson()
    private val modsDir = File(context.filesDir, MODS_DIR)
    private val registryFile = File(modsDir, REGISTRY_FILE)
    private val operationMutex = Mutex()
    private val modConfigStore = ModConfigStore(context)
    
    private val _storeMods = MutableStateFlow<List<StoreMod>>(emptyList())
    val storeMods: StateFlow<List<StoreMod>> = _storeMods.asStateFlow()
    
    private val _installedMods = MutableStateFlow<List<InstalledMod>>(emptyList())
    val installedMods: StateFlow<List<InstalledMod>> = _installedMods.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    
    private val _downloadProgress = MutableStateFlow<String?>(null)
    val downloadProgress: StateFlow<String?> = _downloadProgress.asStateFlow()
    
    private val _manifestCache = MutableStateFlow<Map<String, ModManifest>>(emptyMap())
    val manifestCache: StateFlow<Map<String, ModManifest>> = _manifestCache.asStateFlow()
    
    private val modClassLoaders = mutableMapOf<String, ClassLoader>()
    private val nativeLibGeneration = mutableMapOf<String, Int>()
    private val dexOutputDir = context.getDir("mod_dex", Context.MODE_PRIVATE)

    private val _registryGeneration = MutableStateFlow(0)
    /** Bumped by [saveRegistryAtomic]. Satellite watches this to drop disabled-mod entities without a service restart. */
    val registryGenerationFlow: StateFlow<Int> = _registryGeneration.asStateFlow()

    var registryGeneration: Int
        get() = _registryGeneration.value
        private set(value) { _registryGeneration.value = value }

    @Volatile
    private var registryLoaded = false
    
    private var storeBaseUrl = ""
    
    init {
        modsDir.mkdirs()
    }

    suspend fun ensureRegistryLoaded() = withContext(Dispatchers.IO) {
        if (registryLoaded) return@withContext
        operationMutex.withLock {
            if (registryLoaded) return@withLock
            loadRegistrySync()
            registryLoaded = true
        }
    }
    
    private fun loadRegistrySync() {
        try {
            if (registryFile.exists()) {
                val registry = gson.parseModRegistry(registryFile.readText())
                _installedMods.value = registry.mods
                loadManifestCacheSync()
                Log.d(TAG, "Loaded ${registry.mods.size} installed mods")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load registry", e)
        }
    }
    
    private fun loadManifestCacheSync() {
        val cache = mutableMapOf<String, ModManifest>()
        for (mod in _installedMods.value) {
            try {
                val manifestFile = File(safeModDir(mod.id), "manifest.json")
                if (manifestFile.exists()) {
                    val manifest = gson.parseModManifest(manifestFile.readText())
                    cache[mod.id] = manifest
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache manifest for ${mod.id}", e)
            }
        }
        _manifestCache.value = cache
        // Keep installed version badges in sync with on-disk manifests (adb / silent pushes)
        // without tearing down ClassLoaders or voice hooks.
        syncInstalledVersionsFromManifests(persistQuietly = true)
        ModBleAdvProxyBridge.syncStandalonePolicies(context)
        ModCameraStreamBridge.syncHostPolicies(context)
    }

    /**
     * Align [InstalledMod.version] with each mod's disk [ModManifest.version].
     * Quiet persist avoids voice-pipeline invalidation on pull-to-refresh.
     */
    private fun syncInstalledVersionsFromManifests(persistQuietly: Boolean): Boolean {
        var changed = false
        val updated = _installedMods.value.map { installed ->
            val diskVersion = _manifestCache.value[installed.id]
                ?.version
                ?.trim()
                .orEmpty()
            if (diskVersion.isNotBlank() && diskVersion != installed.version.trim()) {
                changed = true
                installed.copy(version = diskVersion)
            } else {
                installed
            }
        }
        if (!changed) return false
        _installedMods.value = updated
        if (persistQuietly) {
            persistRegistryFileOnly()
        } else {
            saveRegistryAtomic()
        }
        Log.d(TAG, "Synced installed mod versions from disk manifests")
        return true
    }

    /** Write registry.json without bumping [registryGeneration] (UI-only version sync). */
    private fun persistRegistryFileOnly() {
        try {
            val registry = ModRegistry(mods = _installedMods.value)
            val json = gson.toJson(registry)
            val tempFile = File(modsDir, "registry.json.tmp")
            tempFile.writeText(json)
            tempFile.renameTo(registryFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quietly persist registry", e)
        }
    }
    
    private fun saveRegistryAtomic() {
        try {
            val registry = ModRegistry(mods = _installedMods.value)
            val json = gson.toJson(registry)
            val tempFile = File(modsDir, "registry.json.tmp")
            tempFile.writeText(json)
            tempFile.renameTo(registryFile)
            registryGeneration++
            ModVoicePipeline.invalidateCache()
            ModConversationEngine.invalidateCache()
            ModPlaybackReference.invalidateCache()
            ModAudioRouter.invalidateCache()
            ModOverlayZOrderBridge.invalidateCache()
            ModMediaOverlayExclusive.invalidateCache()
            ModBleAdvProxyBridge.invalidateCache(context.applicationContext)
            ModCameraStreamBridge.invalidateCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save registry", e)
        }
    }
    
    suspend fun refreshStore(): Result<List<StoreMod>> = withContext(Dispatchers.IO) {
        ensureRegistryLoaded()
        operationMutex.withLock {
            // Catalog refresh must not share download busy — installed toggles stay usable.
            // Coalesce concurrent refreshes instead of failing the caller.
            if (_isRefreshing.value) return@withContext Result.success(_storeMods.value)
            _isRefreshing.value = true
        }
        var connection: HttpURLConnection? = null
        try {
            // Always race mirrors on catalog refresh — fastest HTTP 200 sticks for jars.
            connection = openSuccessfulStoreConnection(
                rawUrl = "$STORE_URL?ts=${System.currentTimeMillis()}",
                connectTimeoutMs = 10000,
                readTimeoutMs = 10000,
                raceIfUnset = true,
            )

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val store = gson.parseModStore(json)
            storeBaseUrl = store.baseUrl
            _storeMods.value = store.mods
            // Refresh manifest cache to update version info in UI
            loadManifestCacheSync()
            Log.d(TAG, "Loaded ${store.mods.size} mods from store")
            Result.success(store.mods)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh store", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
            _isRefreshing.value = false
        }
    }
    
    suspend fun downloadMod(modId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isValidModId(modId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid mod id: $modId"))
        }

        operationMutex.withLock {
            if (_isLoading.value) return@withContext Result.failure(Exception("Already loading"))
            _isLoading.value = true
        }

        if (isInstalled(modId) && !hasUpdate(modId)) {
            _downloadProgress.value = "Up to date ✓"
            kotlinx.coroutines.delay(400)
            _downloadProgress.value = null
            _isLoading.value = false
            return@withContext Result.success(Unit)
        }
        
        val storeMod = _storeMods.value.find { it.id == modId }
            ?: run {
                _isLoading.value = false
                return@withContext Result.failure(Exception("Mod not found: $modId"))
            }
        
        var connection: HttpURLConnection? = null
        try {
            _downloadProgress.value = "Fetching manifest..."
            connection = openSuccessfulStoreConnection(
                rawUrl = "${storeBaseUrl}${storeMod.path}manifest.json?ts=${System.currentTimeMillis()}",
                connectTimeoutMs = 10000,
                readTimeoutMs = 10000,
                // Prefer sticky winner from catalog race; race only if none yet.
                raceIfUnset = false,
            )
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val manifest = gson.parseModManifest(json)
            val existingMod = _installedMods.value.find { it.id == modId }

            val modDir = safeModDir(modId)
            val existingModDir = if (modDir.exists()) modDir else File(modsDir, ".empty")
            val tempModDir = prepareTempModDir(modId)
            val existingJarHash = computeLocalJarHash(modId)
            val newJarHash = storeMod.jarHash?.takeIf { it.isNotBlank() }
                ?: manifest.jarHash?.takeIf { it.isNotBlank() }
            val primaryJar = manifest.libs?.find { it.endsWith(".jar") }
            val primaryJarHashMatches = newJarHash != null && existingJarHash != null &&
                newJarHash == existingJarHash

            try {
                if (existingMod?.enabled == true) {
                    destroyModManager(modId)
                }
                modClassLoaders.remove(modId)
                clearModDexCache(modId)

                File(tempModDir, "manifest.json").writeText(json)

                val libs = manifest.libs ?: emptyList()
                libs.forEachIndexed { index, lib ->
                    val shortName = lib.substringAfterLast("/")
                    _downloadProgress.value = "${index + 1}/${libs.size} $shortName"
                    downloadModLib(
                        modPath = storeMod.path,
                        tempModDir = tempModDir,
                        lib = lib,
                        existingModDir = existingModDir,
                        newJarHash = if (lib == primaryJar) newJarHash else null,
                        existingJarHash = if (lib == primaryJar) existingJarHash else null,
                        primaryJarHashMatches = primaryJarHashMatches,
                        isPrimaryJar = lib == primaryJar,
                    ) { progress ->
                        _downloadProgress.value = "${index + 1}/${libs.size} $progress"
                    }
                }

                _downloadProgress.value = "Installing..."
                replaceModDirectory(modDir, tempModDir)
                markInstalledJarsReadOnly(modDir, libs)
            } catch (e: Exception) {
                tempModDir.deleteRecursively()
                throw e
            }

            val newMod = InstalledMod(
                id = modId,
                // Prefer package manifest version so badge matches what was installed.
                version = manifest.version?.takeIf { it.isNotBlank() }
                    ?: storeMod.version.orEmptyMod(),
                enabled = existingMod?.enabled ?: true,
                // Store download owns this install; clears any prior local-import override.
                fromLocalImport = false,
            )
            _installedMods.value = _installedMods.value.filter { it.id != modId } + newMod
            _manifestCache.value = _manifestCache.value + (modId to manifest)
            saveRegistryAtomic()
            if (newMod.enabled && manifest.bleAdvProxy && manifest.usesStandaloneBleAdv()) {
                ModBleAdvProxyBridge.applyStandaloneHostPolicy(context)
            }
            if (newMod.enabled && (manifest.cameraStream || manifest.id == ModCameraStreamBridge.MOD_ID)) {
                ModCameraStreamBridge.invalidateCache()
                ModCameraStreamBridge.applyHostPolicy(context)
            }

            _downloadProgress.value = null
            Log.d(TAG, "Downloaded mod: $modId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download mod: $modId", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
            _downloadProgress.value = null
            _isLoading.value = false
        }
    }

    /**
     * Install or replace a mod from a local zip (SAF uri).
     * Zip root (or single top-level folder) must contain manifest.json + libs referenced therein.
     * @return installed mod id on success
     */
    suspend fun importModFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (_isLoading.value) return@withContext Result.failure(Exception("Already loading"))
            _isLoading.value = true
        }

        val extractRoot = File(modsDir, ".import_extract").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            _downloadProgress.value = "Importing…"
            context.contentResolver.openInputStream(uri)?.use { input ->
                unzipSafely(BufferedInputStream(input), extractRoot)
            } ?: return@withContext Result.failure(IOException("Cannot open selected file"))

            val contentRoot = resolveModPackageRoot(extractRoot)
                ?: return@withContext Result.failure(IllegalArgumentException("manifest.json not found in zip"))

            val manifestFile = File(contentRoot, "manifest.json")
            val json = manifestFile.readText()
            val manifest = gson.parseModManifest(json)
            val modId = manifest.id
            if (!isValidModId(modId)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid mod id: $modId"))
            }

            val libs = manifest.libs.orEmpty()
            if (libs.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("manifest.libs is empty"))
            }
            for (lib in libs) {
                val libFile = File(contentRoot, lib)
                if (!libFile.isFile || libFile.length() <= 0L) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Missing lib in package: $lib"),
                    )
                }
            }

            val existingMod = _installedMods.value.find { it.id == modId }
            val modDir = safeModDir(modId)
            val tempModDir = prepareTempModDir(modId)

            try {
                if (existingMod?.enabled == true) {
                    destroyModManager(modId)
                }
                modClassLoaders.remove(modId)
                clearModDexCache(modId)

                _downloadProgress.value = "Installing…"
                copyDirContents(contentRoot, tempModDir)
                replaceModDirectory(modDir, tempModDir)
                markInstalledJarsReadOnly(modDir, libs)
            } catch (e: Exception) {
                tempModDir.deleteRecursively()
                throw e
            }

            val newMod = InstalledMod(
                id = modId,
                version = manifest.version?.takeIf { it.isNotBlank() }.orEmptyMod(),
                enabled = existingMod?.enabled ?: true,
                // Local zip path — independent of store.json; do not treat as store updates.
                fromLocalImport = true,
            )
            _installedMods.value = _installedMods.value.filter { it.id != modId } + newMod
            _manifestCache.value = _manifestCache.value + (modId to manifest)
            saveRegistryAtomic()
            if (newMod.enabled && manifest.bleAdvProxy && manifest.usesStandaloneBleAdv()) {
                ModBleAdvProxyBridge.applyStandaloneHostPolicy(context)
            }
            if (newMod.enabled && (manifest.cameraStream || manifest.id == ModCameraStreamBridge.MOD_ID)) {
                ModCameraStreamBridge.invalidateCache()
                ModCameraStreamBridge.applyHostPolicy(context)
            }

            Log.d(TAG, "Imported mod from zip: $modId")
            Result.success(modId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mod from uri", e)
            Result.failure(e)
        } finally {
            extractRoot.deleteRecursively()
            _downloadProgress.value = null
            _isLoading.value = false
        }
    }

    private fun resolveModPackageRoot(extractRoot: File): File? {
        val direct = File(extractRoot, "manifest.json")
        if (direct.isFile) return extractRoot
        val dirs = extractRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (dirs.size == 1) {
            val nested = File(dirs[0], "manifest.json")
            if (nested.isFile) return dirs[0]
        }
        // Fallback: first manifest.json found one level deep
        dirs.forEach { dir ->
            val nested = File(dir, "manifest.json")
            if (nested.isFile) return dir
        }
        return null
    }

    private fun unzipSafely(input: BufferedInputStream, destDir: File) {
        val destCanonical = destDir.canonicalFile
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/').replace('\\', '/')
                if (name.isBlank() || name.contains("..")) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val outFile = File(destDir, name).canonicalFile
                if (!outFile.path.startsWith(destCanonical.path + File.separator) &&
                    outFile.path != destCanonical.path
                ) {
                    throw SecurityException("Zip path escapes destination: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zis.copyTo(output) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun copyDirContents(from: File, to: File) {
        from.walkTopDown().forEach { src ->
            val relative = src.relativeTo(from).path
            if (relative.isBlank()) return@forEach
            val dst = File(to, relative)
            if (src.isDirectory) {
                dst.mkdirs()
            } else {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }
    
    suspend fun deleteMod(modId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isValidModId(modId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid mod id: $modId"))
        }

        operationMutex.withLock {
            try {
                destroyModManager(modId)
                val modDir = safeModDir(modId)
                if (modDir.exists()) {
                    modDir.deleteRecursively()
                }
                _installedMods.value = _installedMods.value.filter { it.id != modId }
                _manifestCache.value = _manifestCache.value - modId
                modClassLoaders.remove(modId)
                modConfigStore.deleteConfig(modId)
                ModVoicePipeline.invalidateCache()
                ModConversationEngine.invalidateCache()
                ModPlaybackReference.invalidateCache()
                ModAudioRouter.invalidateCache()
                ModOverlayZOrderBridge.invalidateCache()
                ModMediaOverlayExclusive.invalidateCache()
                ModBleAdvProxyBridge.invalidateCache(context.applicationContext)
                ModCameraStreamBridge.invalidateCache()
                saveRegistryAtomic()
                Log.d(TAG, "Deleted mod: $modId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete mod: $modId", e)
                Result.failure(e)
            }
        }
    }
    
    suspend fun setModEnabled(modId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isValidModId(modId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid mod id: $modId"))
        }

        operationMutex.withLock {
            if (!_installedMods.value.any { it.id == modId }) {
                return@withLock Result.failure(Exception("Mod not installed: $modId"))
            }
            // Flip the flag before teardown. Sensor refresh and status polls call
            // getModClassLoader → getInstance(); if enabled is still true they build
            // a new ClassLoader after onDestroy and the mod starts again.
            _installedMods.value = _installedMods.value.map {
                if (it.id == modId) it.copy(enabled = enabled) else it
            }
            if (!enabled) {
                destroyModManager(modId)
            }
            saveRegistryAtomic()
            ModVoicePipeline.invalidateCache()
            ModConversationEngine.invalidateCache()
            ModPlaybackReference.invalidateCache()
            ModAudioRouter.invalidateCache()
            ModOverlayZOrderBridge.invalidateCache()
            ModMediaOverlayExclusive.invalidateCache()
            ModBleAdvProxyBridge.invalidateCache(context.applicationContext)
            ModCameraStreamBridge.invalidateCache()
            if (enabled) {
                getCachedManifest(modId)?.let { manifest ->
                    if (manifest.bleAdvProxy && manifest.usesStandaloneBleAdv()) {
                        ModBleAdvProxyBridge.applyStandaloneHostPolicy(context)
                    }
                    if (manifest.cameraStream || manifest.id == ModCameraStreamBridge.MOD_ID) {
                        ModCameraStreamBridge.applyHostPolicy(context)
                    }
                }
            }
            Log.d(TAG, "Set mod $modId enabled=$enabled")
            Result.success(Unit)
        }
    }

    /** Reload registry.json from disk (e.g. after adb push into files/mods/). */
    suspend fun refreshRegistryFromDisk(): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                loadRegistrySync()
                registryLoaded = true
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh registry from disk", e)
                Result.failure(e)
            }
        }
    }
    
    fun getCachedManifest(modId: String): ModManifest? {
        return _manifestCache.value[modId]?.normalized()
    }

    /** Re-read manifest.json from disk and refresh cache (e.g. after mod update or adb push). */
    fun reloadManifestFromDisk(modId: String): ModManifest? {
        if (!isValidModId(modId)) return null
        return try {
            val manifestFile = File(safeModDir(modId), "manifest.json")
            if (!manifestFile.exists()) {
                _manifestCache.value = _manifestCache.value - modId
                return null
            }
            val manifest = gson.parseModManifest(manifestFile.readText())
            _manifestCache.value = _manifestCache.value + (modId to manifest)
            manifest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload manifest for $modId", e)
            null
        }
    }

    /**
     * Synchronous method for mod self-update via reflection.
     * Returns: "ok" on success, or error message on failure.
     */
    fun updateModSync(modId: String): String {
        if (!isValidModId(modId)) {
            return "Invalid mod id: $modId"
        }
        return try {
            kotlinx.coroutines.runBlocking {
                refreshStore()
                val result = downloadMod(modId)
                if (result.isSuccess) "ok" else result.exceptionOrNull()?.message ?: "Unknown error"
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateModSync failed", e)
            e.message ?: "Unknown error"
        }
    }

    /**
     * Synchronous method to reload a mod after update.
     * Disables and re-enables the mod to force ClassLoader refresh.
     * Native .so mods get a unique staged library path (see [stageNativeLibraryPath])
     * so reload works in-process without killing Ava.
     * Returns: "ok" on success, or error message on failure.
     */
    fun reloadModSync(modId: String): String {
        if (!isValidModId(modId)) {
            return "Invalid mod id: $modId"
        }
        return try {
            kotlinx.coroutines.runBlocking {
                // Disable to destroy old manager, then drop cached ClassLoader/DEX so
                // adb-pushed JAR updates are picked up (same as downloadMod path).
                setModEnabled(modId, false)
                kotlinx.coroutines.delay(100)
                modClassLoaders.remove(modId)
                clearModDexCache(modId)
                val result = setModEnabled(modId, true)
                if (result.isSuccess) "ok" else result.exceptionOrNull()?.message ?: "Unknown error"
            }
        } catch (e: Exception) {
            Log.e(TAG, "reloadModSync failed", e)
            e.message ?: "Unknown error"
        }
    }

    /**
     * Synchronous method to update and reload a mod in one call.
     * Downloads the latest version and reloads the mod.
     * Returns: "ok" on success, or error message on failure.
     */
    fun updateAndReloadModSync(modId: String): String {
        if (!isValidModId(modId)) {
            return "Invalid mod id: $modId"
        }
        return try {
            kotlinx.coroutines.runBlocking {
                refreshStore()
                val downloadResult = downloadMod(modId)
                if (downloadResult.isFailure) {
                    return@runBlocking "Download failed: ${downloadResult.exceptionOrNull()?.message ?: "Unknown error"}"
                }
                // Same ClassLoader/DEX refresh as reloadModSync.
                kotlinx.coroutines.delay(200)
                val reload = reloadModSync(modId)
                if (reload == "ok") "ok" else "Reload failed: $reload"
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateAndReloadModSync failed", e)
            e.message ?: "Unknown error"
        }
    }

    fun getResolvedConfig(modId: String, manifest: ModManifest? = getCachedManifest(modId) ?: getModManifest(modId)): Map<String, String> {
        return modConfigStore.getResolvedConfig(modId, manifest)
    }

    suspend fun saveModConfig(modId: String, values: Map<String, String>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isValidModId(modId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid mod id: $modId"))
        }

        operationMutex.withLock {
            try {
                modConfigStore.saveConfig(modId, values)
                Log.d(TAG, "Saved config for mod: $modId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save config for mod: $modId", e)
                Result.failure(e)
            }
        }
    }
    
    fun getModManifest(modId: String): ModManifest? {
        if (!isValidModId(modId)) return null
        _manifestCache.value[modId]?.normalized()?.let { return it }
        return try {
            val manifestFile = File(safeModDir(modId), "manifest.json")
            if (manifestFile.exists()) {
                val manifest = gson.parseModManifest(manifestFile.readText())
                _manifestCache.value = _manifestCache.value + (modId to manifest)
                manifest
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load manifest for $modId", e)
            null
        }
    }
    
    fun getEnabledManifests(): List<ModManifest> {
        return _installedMods.value
            .filter { it.enabled }
            .mapNotNull { getCachedManifest(it.id) ?: getModManifest(it.id) }
    }
    
    fun isInstalled(modId: String): Boolean {
        return _installedMods.value.any { it.id == modId }
    }
    
    fun isEnabled(modId: String): Boolean {
        return _installedMods.value.find { it.id == modId }?.enabled == true
    }

    /** Installed and explicitly off. Unknown ids stay loadable (registry may not be read yet). */
    private fun isKnownDisabled(modId: String): Boolean {
        val installed = _installedMods.value.find { it.id == modId } ?: return false
        return !installed.enabled
    }

    /**
     * Manager singleton for an enabled mod.
     * Disabled mods return null and never construct a ClassLoader.
     * If [getInstance] races a disable and builds a manager anyway, it is destroyed here.
     */
    fun resolveEnabledManager(modId: String, managerClassName: String?): ResolvedModManager? {
        if (managerClassName.isNullOrBlank() || !isValidModId(modId) || !isEnabled(modId)) {
            return null
        }
        val loader = getModClassLoader(modId)
        if (!isEnabled(modId)) return null
        val managerClass = loader.loadClass(managerClassName)
        val instance = managerClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context)
        if (instance == null) {
            Log.w(TAG, "getInstance returned null for $modId ($managerClassName)")
            return null
        }
        if (!isEnabled(modId)) {
            runCatching {
                managerClass.methods.firstOrNull {
                    it.name == "onDestroy" && it.parameterTypes.isEmpty()
                }?.invoke(instance)
            }
            synchronized(modClassLoaders) { modClassLoaders.remove(modId) }
            Log.d(TAG, "Destroyed manager created after disable: $modId")
            return null
        }
        return ResolvedModManager(managerClass, instance)
    }
    
    fun hasUpdate(modId: String): Boolean {
        val installed = _installedMods.value.find { it.id == modId } ?: return false
        // Local zip imports are a separate channel from the catalog; never nudge store updates.
        if (installed.fromLocalImport) return false
        val store = _storeMods.value.find { it.id == modId } ?: return false
        val installedVersion = installed.version.orEmptyMod().trim()
        val storeVersion = store.version.orEmptyMod().trim()
        if (installedVersion.isBlank() || storeVersion.isBlank()) return false
        
        if (storeVersion != installedVersion) return true
        
        val storeHash = store.jarHash
        if (storeHash.isNullOrBlank()) return false
        
        val localHash = computeLocalJarHash(modId)
        return localHash != null && localHash != storeHash
    }
    
    private fun computeLocalJarHash(modId: String): String? {
        val manifest = _manifestCache.value[modId] ?: return null
        val libs = manifest.libs ?: return null
        val jarLib = libs.find { it.endsWith(".jar") } ?: return null
        val jarFile = File(safeModDir(modId), jarLib)
        if (!jarFile.exists()) return null
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            jarFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute jar hash for $modId", e)
            null
        }
    }

    private fun downloadModLib(
        modPath: String,
        tempModDir: File,
        lib: String,
        existingModDir: File,
        newJarHash: String?,
        existingJarHash: String?,
        primaryJarHashMatches: Boolean = false,
        isPrimaryJar: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ) {
        val libFile = File(tempModDir, lib)
        libFile.parentFile?.mkdirs()
        
        val existingLibFile = File(existingModDir, lib)
        
        // Native .so: reuse only when the primary jar is unchanged. Otherwise re-download
        // so AirPlay/native mods pick up real ABI updates (copying forever left natives stale).
        if (lib.endsWith(".so") && existingLibFile.exists() && existingLibFile.length() > 0) {
            if (primaryJarHashMatches) {
                val shortName = lib.substringAfterLast("/")
                onProgress?.invoke("$shortName ✓")
                existingLibFile.copyTo(libFile, overwrite = true)
                Log.d(TAG, "Copied existing lib (jar unchanged): $lib")
                return
            }
            Log.d(TAG, "Re-downloading native lib (jar changed): $lib")
        }
        
        // Secondary jars: reuse when primary manager jar hash is unchanged
        if (lib.endsWith(".jar") && !isPrimaryJar && primaryJarHashMatches &&
            existingLibFile.exists() && existingLibFile.length() > 0) {
            val shortName = lib.substringAfterLast("/")
            onProgress?.invoke("$shortName ✓")
            existingLibFile.copyTo(libFile, overwrite = true)
            Log.d(TAG, "Copied secondary jar (primary hash match): $lib")
            return
        }
        
        // For primary .jar: check hash to skip download if unchanged
        if (lib.endsWith(".jar") && isPrimaryJar && existingLibFile.exists() && existingLibFile.length() > 0 &&
            newJarHash != null && existingJarHash != null && newJarHash == existingJarHash) {
            val shortName = lib.substringAfterLast("/")
            onProgress?.invoke("$shortName ✓")
            existingLibFile.copyTo(libFile, overwrite = true)
            Log.d(TAG, "Copied existing jar (hash match, size=${existingLibFile.length()}): $lib")
            return
        }
        
        // Download the file with cache bypass (sticky proxy + sequential failover; no multi-jar race).
        var conn: HttpURLConnection? = null
        try {
            conn = openSuccessfulStoreConnection(
                rawUrl = "${storeBaseUrl}${modPath}$lib?ts=${System.currentTimeMillis()}",
                connectTimeoutMs = 30000,
                readTimeoutMs = 30000,
                raceIfUnset = false,
            )
            val contentLength = conn.contentLength.toLong()
            var downloaded = 0L
            val buffer = ByteArray(8192)
            conn.inputStream.use { input ->
                libFile.outputStream().use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (contentLength > 0) {
                            val percent = (downloaded * 100 / contentLength).toInt()
                            val shortName = lib.substringAfterLast("/")
                            onProgress?.invoke("$shortName $percent%")
                        }
                    }
                }
            }
            // Verify download completed
            if (downloaded == 0L) {
                throw IllegalStateException("Downloaded file is empty: $lib")
            }
            if (contentLength > 0 && downloaded != contentLength) {
                throw IllegalStateException("Download incomplete: $lib (expected $contentLength, got $downloaded)")
            }
            Log.d(TAG, "Downloaded lib: $lib (size=$downloaded)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download lib: $lib", e)
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadModLibFresh(
        modPath: String,
        tempModDir: File,
        lib: String,
        onProgress: ((String) -> Unit)? = null
    ) {
        val libFile = File(tempModDir, lib)
        libFile.parentFile?.mkdirs()
        
        var conn: HttpURLConnection? = null
        try {
            conn = openSuccessfulStoreConnection(
                rawUrl = "${storeBaseUrl}${modPath}$lib?ts=${System.currentTimeMillis()}",
                connectTimeoutMs = 30000,
                readTimeoutMs = 30000,
                raceIfUnset = false,
            )
            val contentLength = conn.contentLength.toLong()
            var downloaded = 0L
            val buffer = ByteArray(8192)
            conn.inputStream.use { input ->
                libFile.outputStream().use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (contentLength > 0) {
                            val percent = (downloaded * 100 / contentLength).toInt()
                            val shortName = lib.substringAfterLast("/")
                            onProgress?.invoke("$shortName $percent%")
                        }
                    }
                }
            }
            if (downloaded == 0L) {
                throw IllegalStateException("Downloaded file is empty: $lib")
            }
            if (contentLength > 0 && downloaded != contentLength) {
                throw IllegalStateException("Download incomplete: $lib (expected $contentLength, got $downloaded)")
            }
            Log.d(TAG, "Downloaded lib fresh: $lib (size=$downloaded)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download lib: $lib", e)
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun prepareTempModDir(modId: String): File {
        val tempDir = File(modsDir, "$modId.tmp")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()
        return tempDir
    }

    /**
     * Android 14+ refuses DexClassLoader if the input JAR/DEX is still writable.
     * Files written into `files/mods` keep default mode 0600, so mark them after
     * install and again immediately before constructing the loader.
     */
    private fun markInstalledJarsReadOnly(modDir: File, libs: List<String>) {
        libs.filter { it.endsWith(".jar") }.forEach { lib ->
            markJarReadOnly(File(modDir, lib))
        }
    }

    private fun markJarReadOnly(jar: File) {
        if (!jar.isFile) return
        if (!jar.setReadOnly()) {
            Log.w(TAG, "Failed to mark jar read-only: ${jar.absolutePath}")
        }
    }

    private fun replaceModDirectory(targetDir: File, tempDir: File) {
        require(tempDir.exists()) { "Temp mod directory does not exist: ${tempDir.absolutePath}" }
        val backupDir = File(targetDir.parentFile, "${targetDir.name}.bak")
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }

        if (targetDir.exists() && !targetDir.renameTo(backupDir)) {
            throw IllegalStateException("Failed to create backup for mod directory: ${targetDir.absolutePath}")
        }

        if (!tempDir.renameTo(targetDir)) {
            if (backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            throw IllegalStateException("Failed to replace mod directory for ${targetDir.name}")
        }

        if (backupDir.exists() && !backupDir.deleteRecursively()) {
            Log.w(TAG, "Failed to delete backup mod directory: ${backupDir.absolutePath}")
        }
    }
    
    private fun isValidModId(modId: String): Boolean = MOD_ID_PATTERN.matcher(modId).matches()

    /**
     * Returns an open connection that already responded HTTP 200.
     *
     * - zh locale + GitHub URL: prefix proxies
     * - [raceIfUnset]=true (catalog refresh): always race — first 200 wins and sticks
     * - [raceIfUnset]=false: sticky first, then sequential failover (jar-safe, no 4× bandwidth)
     * - If sticky is null with raceIfUnset=false: still race once to pick a winner
     */
    private fun openSuccessfulStoreConnection(
        rawUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        raceIfUnset: Boolean,
    ): HttpURLConnection {
        val direct = GithubProxyUrls.toDirectUrl(rawUrl)
        if (!GithubProxyUrls.shouldPreferProxy(context) || !GithubProxyUrls.isGithubHosted(direct)) {
            return openAndRequire200(direct, connectTimeoutMs, readTimeoutMs)
        }

        val shouldRace = raceIfUnset || preferredProxyPrefix == null
        if (shouldRace) {
            return raceProxyConnection(direct, connectTimeoutMs, readTimeoutMs)
        }

        var lastError: Exception? = null
        for (candidate in proxyCandidateUrls(direct)) {
            try {
                return openAndRequire200(candidate, connectTimeoutMs, readTimeoutMs).also {
                    rememberWinningPrefix(candidate)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "GitHub proxy failover miss: $candidate (${e.message})")
                val preferred = preferredProxyPrefix
                if (preferred != null && candidate.startsWith(preferred)) {
                    preferredProxyPrefix = null
                }
            }
        }

        Log.w(TAG, "All GitHub proxies failed; trying direct")
        return try {
            openAndRequire200(direct, connectTimeoutMs, readTimeoutMs)
        } catch (e: Exception) {
            throw lastError ?: e
        }
    }

    private fun raceProxyConnection(
        directUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val done = AtomicBoolean(false)
        val winnerRef = AtomicReference<HttpURLConnection?>(null)
        val allConns = ConcurrentLinkedQueue<HttpURLConnection>()
        val latch = CountDownLatch(1)

        for (prefix in GithubProxyUrls.PROXY_PREFIXES) {
            thread(name = "mod-proxy-race", isDaemon = true) {
                if (done.get()) return@thread
                var conn: HttpURLConnection? = null
                try {
                    conn = openStoreConnection("$prefix$directUrl").also {
                        applyStoreRequestDefaults(it, connectTimeoutMs, readTimeoutMs)
                    }
                    allConns.add(conn)
                    val code = conn.responseCode
                    if (code == 200 && done.compareAndSet(false, true)) {
                        preferredProxyPrefix = prefix
                        winnerRef.set(conn)
                        latch.countDown()
                        Log.d(TAG, "GitHub proxy race won by $prefix")
                    } else if (code != 200) {
                        Log.d(TAG, "GitHub proxy race HTTP $code from $prefix")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "GitHub proxy race miss $prefix: ${e.message}")
                    if (conn != null) allConns.add(conn)
                }
            }
        }

        val awaitMs = connectTimeoutMs.toLong() + 2_000L
        latch.await(awaitMs, TimeUnit.MILLISECONDS)
        val winner = winnerRef.get()
        // Drop losers (and any late finishers already queued).
        for (conn in allConns) {
            if (conn !== winner) {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        if (winner != null) return winner

        Log.w(TAG, "GitHub proxy race produced no winner; trying direct")
        return openAndRequire200(directUrl, connectTimeoutMs, readTimeoutMs)
    }

    private fun openAndRequire200(
        urlString: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val connection = openStoreConnection(urlString).also {
            applyStoreRequestDefaults(it, connectTimeoutMs, readTimeoutMs)
        }
        val code = connection.responseCode
        if (code != 200) {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
            throw IOException("HTTP $code for $urlString")
        }
        return connection
    }

    private fun applyStoreRequestDefaults(
        connection: HttpURLConnection,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ) {
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
        connection.setRequestProperty("Pragma", "no-cache")
    }

    private fun proxyCandidateUrls(directUrl: String): List<String> {
        val preferred = preferredProxyPrefix
        val ordered = buildList {
            if (preferred != null) add(preferred)
            for (prefix in GithubProxyUrls.PROXY_PREFIXES) {
                if (prefix != preferred) add(prefix)
            }
        }
        return ordered.map { "$it$directUrl" }
    }

    private fun rememberWinningPrefix(proxiedUrl: String) {
        for (prefix in GithubProxyUrls.PROXY_PREFIXES) {
            if (proxiedUrl.startsWith(prefix)) {
                preferredProxyPrefix = prefix
                return
            }
        }
    }

    /** Skip HTTPS cert verify (legacy devices may lack proxy CA roots). */
    private fun openStoreConnection(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return connection
    }


    private fun safeModDir(modId: String): File {
        require(isValidModId(modId)) { "Invalid mod id: $modId" }
        val base = modsDir.canonicalFile
        val target = File(base, modId).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Unsafe mod path: $modId" }
        return target
    }
    
    fun getModClassLoader(modId: String): ClassLoader {
        synchronized(modClassLoaders) {
            // Check before the cache hit. A leftover sensor tick must not receive the
            // loader we are about to release, or getInstance() builds a new manager.
            if (isKnownDisabled(modId)) {
                Log.d(TAG, "Refusing ClassLoader for disabled mod $modId")
                return context.classLoader
            }
            modClassLoaders[modId]?.let { return it }

            val manifest = getCachedManifest(modId) ?: getModManifest(modId) ?: return context.classLoader
            val libs = manifest.libs ?: return context.classLoader
            if (libs.isEmpty()) return context.classLoader

            val modDir = safeModDir(modId)
            val jarFiles = libs.mapNotNull { lib ->
                if (!lib.endsWith(".jar")) return@mapNotNull null
                val jarFile = File(modDir, lib)
                if (jarFile.isFile) jarFile else null
            }

            if (jarFiles.isEmpty()) return context.classLoader
            // Android 14+ (targetSdk ≥ 34) rejects writable DEX/JAR at load time.
            // Already-installed copies stay 0600 until we flip them here.
            jarFiles.forEach { markJarReadOnly(it) }
            val jarPaths = jarFiles.map { it.absolutePath }

            val nativeLibPaths = mutableSetOf<String>()
            libs.filter { it.endsWith(".so") }.forEach { lib ->
                val soFile = File(modDir, lib)
                if (soFile.exists()) {
                    soFile.parentFile?.absolutePath?.let { nativeLibPaths.add(it) }
                }
            }
            // Keep fat multi-ABI packs on disk; only expose process-compatible dirs
            // to DexClassLoader (avoids 32-bit process dlopen'ing arm64 .so first).
            val selectedNativeDirs = selectNativeLibraryDirs(nativeLibPaths)
            // Copy .so trees to a unique code-cache path per ClassLoader generation.
            // Android forbids reopening the same absolute .so path from a second
            // DexClassLoader in-process ("already opened by ClassLoader").
            val nativeLibPath = stageNativeLibraryPath(modId, selectedNativeDirs)

            val classLoader = DexClassLoader(
                jarPaths.joinToString(File.pathSeparator),
                dexOutputDir.absolutePath,
                nativeLibPath,
                context.classLoader
            )
            if (isKnownDisabled(modId)) {
                Log.d(TAG, "Discarded ClassLoader for disabled mod $modId")
                return context.classLoader
            }
            modClassLoaders[modId] = classLoader
            Log.d(TAG, "Created ClassLoader for $modId with ${jarPaths.size} JARs, nativeLibPath=$nativeLibPath")
            return classLoader
        }
    }

    /**
     * ABIs this **process** can load (not merely what the device supports).
     * A 64-bit phone running a 32-bit Ava APK must prefer armeabi-v7a.
     *
     * Note: [android.content.pm.ApplicationInfo.primaryCpuAbi] is not in the public
     * SDK stubs — use [Process.is64Bit] + 32/64 ABI lists instead.
     */
    private fun preferredProcessNativeAbis(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val byBitness = if (Process.is64Bit()) {
                Build.SUPPORTED_64_BIT_ABIS
            } else {
                Build.SUPPORTED_32_BIT_ABIS
            }
            if (byBitness.isNotEmpty()) return byBitness.toList()
        }
        // API 21–22: CPU_ABI reflects the ABI used by this process/APK.
        @Suppress("DEPRECATION")
        val legacy = listOfNotNull(
            Build.CPU_ABI.takeIf { it.isNotBlank() },
            Build.CPU_ABI2?.takeIf { it.isNotBlank() && it != Build.CPU_ABI },
        ).distinct()
        if (legacy.isNotEmpty()) return legacy
        return Build.SUPPORTED_ABIS.toList()
    }

    private fun processWants64BitNative(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Process.is64Bit()
        }
        @Suppress("DEPRECATION")
        return Build.CPU_ABI.orEmpty().contains("64")
    }

    /** ELF EI_CLASS: 1 = 32-bit, 2 = 64-bit. null if unreadable / not ELF. */
    private fun elfClassIs64Bit(so: File): Boolean? {
        return try {
            so.inputStream().use { input ->
                val hdr = ByteArray(5)
                if (input.read(hdr) != 5) return null
                if (hdr[0] != 0x7f.toByte() ||
                    hdr[1] != 'E'.code.toByte() ||
                    hdr[2] != 'L'.code.toByte() ||
                    hdr[3] != 'F'.code.toByte()
                ) {
                    return null
                }
                when (hdr[4].toInt() and 0xff) {
                    1 -> false
                    2 -> true
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Filter/order jni ABI dirs for the current process. Does not delete unused ABIs.
     * If nothing matches (non-standard layout), fall back to the original set.
     */
    private fun selectNativeLibraryDirs(sourceDirs: Set<String>): List<String> {
        if (sourceDirs.isEmpty()) return emptyList()
        val preferred = preferredProcessNativeAbis()
        val want64 = processWants64BitNative()
        val preferredIndex = preferred.withIndex().associate { (index, abi) -> abi to index }

        fun dirCompatible(dir: File): Boolean {
            if (dir.name !in preferredIndex) return false
            val sample = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".so") }
                ?: return true
            val is64 = elfClassIs64Bit(sample) ?: return true
            return is64 == want64
        }

        val selected = sourceDirs
            .map { File(it) }
            .filter { it.isDirectory && dirCompatible(it) }
            .sortedBy { preferredIndex[it.name] ?: Int.MAX_VALUE }
            .map { it.absolutePath }

        if (selected.isNotEmpty()) {
            val skipped = sourceDirs.filterNot { it in selected.toSet() }
            Log.i(
                TAG,
                "Native ABI filter: processAbi=$preferred want64=$want64 " +
                    "selected=$selected skipped=$skipped",
            )
            return selected
        }

        Log.w(
            TAG,
            "Native ABI filter matched nothing for preferred=$preferred; " +
                "falling back to all dirs: $sourceDirs",
        )
        return sourceDirs.toList()
    }

    /**
     * Stage native ABI dirs under codeCache so each reload gets distinct file paths.
     * Falls back to the original dirs if staging fails (cold start still works).
     * [sourceDirs] order is preserved (process-preferred ABI first).
     */
    private fun stageNativeLibraryPath(modId: String, sourceDirs: Collection<String>): String? {
        if (sourceDirs.isEmpty()) return null
        return try {
            val gen = (nativeLibGeneration[modId] ?: 0) + 1
            nativeLibGeneration[modId] = gen
            val stageRoot = File(context.codeCacheDir, "mod-native/$modId/g$gen")
            if (!stageRoot.exists() && !stageRoot.mkdirs()) {
                Log.w(TAG, "native stage mkdir failed: $stageRoot")
                return sourceDirs.joinToString(File.pathSeparator)
            }
            val staged = mutableListOf<String>()
            for (srcPath in sourceDirs) {
                val srcDir = File(srcPath)
                if (!srcDir.isDirectory) continue
                val dstDir = File(stageRoot, srcDir.name)
                if (!dstDir.exists() && !dstDir.mkdirs()) continue
                srcDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.forEach { so ->
                        so.copyTo(File(dstDir, so.name), overwrite = true)
                    }
                if (dstDir.listFiles()?.any { it.name.endsWith(".so") } == true) {
                    staged.add(dstDir.absolutePath)
                }
            }
            // Drop older generations (keep current + previous).
            File(context.codeCacheDir, "mod-native/$modId").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("g") && it.name != "g$gen" && it.name != "g${gen - 1}" }
                ?.forEach { old ->
                    runCatching { old.deleteRecursively() }
                }
            if (staged.isEmpty()) {
                Log.w(TAG, "native stage empty for $modId; using source paths")
                sourceDirs.joinToString(File.pathSeparator)
            } else {
                Log.i(TAG, "Staged native libs for $modId gen=$gen → ${staged.joinToString()}")
                staged.joinToString(File.pathSeparator)
            }
        } catch (e: Exception) {
            Log.w(TAG, "native stage failed for $modId: ${e.message}")
            sourceDirs.joinToString(File.pathSeparator)
        }
    }
    
    fun getModDir(modId: String): File? {
        if (!isValidModId(modId)) return null
        val dir = safeModDir(modId)
        return if (dir.exists()) dir else null
    }
    
    fun getRequiredPermissions(modId: String): List<String> {
        val manifest = getCachedManifest(modId) ?: return emptyList()
        return ModPermissions.resolveForMod(modId, manifest.permissions)
    }

    /** Best-effort privileged permissions; never required to enable the mod. */
    fun getOptionalPermissions(modId: String): List<String> {
        val manifest = getCachedManifest(modId) ?: return emptyList()
        return ModPermissions.resolve(manifest.optionalPermissions)
    }

    /**
     * Tear down a mod's manager singleton (if loaded) and drop its cached [DexClassLoader].
     *
     * Does **not** call [getModClassLoader] — never create a loader just to destroy it.
     * Does **not** clear the DEX/oat dir here (that races with any in-flight callers);
     * install/reload/delete paths still call [clearModDexCache] when replacing jars.
     *
     * Native libs stay safe across re-enable: the next [getModClassLoader] stages a new
     * generation path via [stageNativeLibraryPath].
     */
    fun destroyModManager(modId: String) {
        if (!isValidModId(modId)) return

        val manifest = getCachedManifest(modId) ?: getModManifest(modId)
        val classLoader = synchronized(modClassLoaders) { modClassLoaders[modId] }
        val managerClassName = manifest?.manager

        if (classLoader != null && !managerClassName.isNullOrBlank()) {
            runCatching {
                val managerClass = classLoader.loadClass(managerClassName)
                val getInstanceMethod = managerClass.getMethod("getInstance", Context::class.java)
                val instance = getInstanceMethod.invoke(null, context)
                val destroyMethod = managerClass.methods.firstOrNull {
                    it.name == "onDestroy" && it.parameterTypes.isEmpty()
                }
                if (destroyMethod != null) {
                    destroyMethod.invoke(instance)
                    Log.d(TAG, "Destroyed mod manager: $modId")
                } else {
                    Log.d(TAG, "No onDestroy() for mod manager: $modId")
                }
            }.onFailure {
                Log.w(TAG, "Failed to destroy mod manager: $modId", it)
            }
        }

        if (manifest?.conversationEngine == true) {
            ModConversationEngine.invalidateCache()
        }
        if (manifest?.bleAdvProxy == true) {
            ModBleAdvProxyBridge.invalidateCache(context.applicationContext)
        }
        if (manifest != null &&
            (manifest.cameraStream || manifest.id == ModCameraStreamBridge.MOD_ID)
        ) {
            ModCameraStreamBridge.invalidateCache()
        }

        synchronized(modClassLoaders) {
            if (modClassLoaders.remove(modId) != null) {
                Log.d(TAG, "Released ClassLoader for $modId")
            }
        }
    }
    
    private fun clearModDexCache(modId: String) {
        // Clear DEX/oat crumbs for this mod. Names may contain modId or the jar basename.
        val jarTokens = buildSet {
            add(modId)
            add("mimiclaw")
            val libs = (_manifestCache.value[modId] ?: getModManifest(modId))?.libs.orEmpty()
            libs.filter { it.endsWith(".jar") }.forEach { lib ->
                val base = lib.substringAfterLast('/').removeSuffix(".jar")
                if (base.isNotBlank()) add(base)
            }
        }
        dexOutputDir.listFiles()?.filter { file ->
            val name = file.name.lowercase()
            jarTokens.any { token -> name.contains(token.lowercase()) }
        }?.forEach { file ->
            if (file.deleteRecursively()) {
                Log.d(TAG, "Cleared DEX cache: ${file.name}")
            }
        }
    }

    fun destroyEnabledModManagers() {
        _installedMods.value
            .filter { it.enabled }
            .forEach { destroyModManager(it.id) }
        // Same host-side cache drop as setModEnabled(false): bridges must not keep
        // Class references from the loaders we just released (mods stay "enabled").
        ModVoicePipeline.invalidateCache()
        ModConversationEngine.invalidateCache()
        ModPlaybackReference.invalidateCache()
        ModAudioRouter.invalidateCache()
        ModOverlayZOrderBridge.invalidateCache()
        ModMediaOverlayExclusive.invalidateCache()
        ModBleAdvProxyBridge.invalidateCache(context.applicationContext)
        ModCameraStreamBridge.invalidateCache()
    }
    
    fun getAllRequiredPermissions(): List<String> {
        return _installedMods.value
            .filter { it.enabled }
            .flatMap { getRequiredPermissions(it.id) }
            .distinct()
    }
    
    fun getMissingPermissions(modId: String): List<String> {
        return ModPermissions.missingRuntimePermissions(
            context = context,
            packageManager = { permission ->
                androidx.core.content.ContextCompat.checkSelfPermission(context, permission)
            },
            permissions = getRequiredPermissions(modId)
        )
    }

    fun modRequiresPrivilegedShell(modId: String): Boolean {
        return ModPermissions.modRequiresPrivilegedShell(getRequiredPermissions(modId))
    }

    class ResolvedModManager(
        val managerClass: Class<*>,
        val instance: Any,
    )
}
