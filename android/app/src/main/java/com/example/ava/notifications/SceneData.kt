package com.example.ava.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.example.ava.net.GithubProxyUrls
import androidx.annotation.ColorInt
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.ava.utils.LocaleUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


data class NotificationScene(
    val id: String,
    val icon: String,           
    val iconColor: String,      
    val title: String,          
    val desc: String,           
    val subDesc: String,        
    val themeColors: List<String>,  
    val beamColor: String,      
    val dividerColor: String,   
    val dotColor: String,       
    val animation: String,
    /** 场景专属提示音；null = 未配置，回退到全局通知提示音设置。 */
    val soundUri: String? = null,
    /** false = 该场景静音；null = 未配置，回退全局。 */
    val soundEnabled: Boolean? = null,
) {

    /**
     * 解析本场景应播放的提示音 URI。
     * 优先级：soundEnabled=false 静音 → soundUri → 全局 notificationSettings。
     */
    fun resolveSoundUri(global: com.example.ava.settings.NotificationSettings): String? {
        if (soundEnabled == false) return null
        soundUri?.takeIf { it.isNotBlank() }?.let { return it }
        if (global.soundEnabled && global.soundUri.isNotEmpty()) return global.soundUri
        return null
    }
    
    @ColorInt
    fun getPrimaryColor(): Int {
        return parseHexColor(themeColors.firstOrNull() ?: "#f59e0b")
    }
    
    
    @ColorInt
    fun getThemeColorInts(): List<Int> {
        return themeColors.map { parseHexColor(it) }
    }
    
    
    @ColorInt
    fun getBeamColorInt(): Int {
        return parseAnyColor(beamColor)
    }
    
    
    @ColorInt
    fun getDividerColorInt(): Int {
        return parseAnyColor(dividerColor)
    }
    
    
    @ColorInt
    fun getDotColorInt(): Int {
        return parseAnyColor(dotColor)
    }
    
    
    @ColorInt
    fun getIconColorInt(): Int {
        return parseAnyColor(iconColor)
    }
    
    companion object {
        @ColorInt
        fun parseHexColor(hex: String): Int {
            val cleanHex = hex.removePrefix("#")
            return try {
                when (cleanHex.length) {
                    6 -> android.graphics.Color.parseColor("#$cleanHex")
                    8 -> android.graphics.Color.parseColor("#$cleanHex")
                    else -> android.graphics.Color.parseColor("#f59e0b") 
                }
            } catch (e: Exception) {
                android.graphics.Color.parseColor("#f59e0b") 
            }
        }
        
        
        @ColorInt
        fun parseRgbaColor(rgba: String): Int {
            val regex = """rgba?\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+))?\s*\)""".toRegex()
            val match = regex.find(rgba)
            return if (match != null) {
                val r = match.groupValues[1].toIntOrNull() ?: 0
                val g = match.groupValues[2].toIntOrNull() ?: 0
                val b = match.groupValues[3].toIntOrNull() ?: 0
                val a = (match.groupValues[4].toFloatOrNull() ?: 1f) * 255
                android.graphics.Color.argb(a.toInt(), r, g, b)
            } else {
                parseHexColor(rgba)
            }
        }
        
        
        @ColorInt
        fun parseAnyColor(colorStr: String): Int {
            return when {
                colorStr.startsWith("#") -> parseHexColor(colorStr)
                colorStr.startsWith("rgba") || colorStr.startsWith("rgb") -> parseRgbaColor(colorStr)
                
                colorStr.contains("amber-200") -> android.graphics.Color.parseColor("#fde68a")
                colorStr.contains("amber-300") -> android.graphics.Color.parseColor("#fcd34d")
                colorStr.contains("amber-400") -> android.graphics.Color.parseColor("#fbbf24")
                colorStr.contains("amber-500") -> android.graphics.Color.parseColor("#f59e0b")
                colorStr.contains("amber-600") -> android.graphics.Color.parseColor("#d97706")
                colorStr.contains("amber-700") -> android.graphics.Color.parseColor("#b45309")
                
                colorStr.contains("yellow-200") -> android.graphics.Color.parseColor("#fef08a")
                colorStr.contains("yellow-300") -> android.graphics.Color.parseColor("#fde047")
                colorStr.contains("yellow-400") -> android.graphics.Color.parseColor("#facc15")
                colorStr.contains("yellow-500") -> android.graphics.Color.parseColor("#eab308")
                
                colorStr.contains("orange-200") -> android.graphics.Color.parseColor("#fed7aa")
                colorStr.contains("orange-300") -> android.graphics.Color.parseColor("#fdba74")
                colorStr.contains("orange-400") -> android.graphics.Color.parseColor("#fb923c")
                colorStr.contains("orange-500") -> android.graphics.Color.parseColor("#f97316")
                colorStr.contains("orange-600") -> android.graphics.Color.parseColor("#ea580c")
                
                colorStr.contains("red-300") -> android.graphics.Color.parseColor("#fca5a5")
                colorStr.contains("red-400") -> android.graphics.Color.parseColor("#f87171")
                colorStr.contains("red-500") -> android.graphics.Color.parseColor("#ef4444")
                
                colorStr.contains("rose-300") -> android.graphics.Color.parseColor("#fda4af")
                colorStr.contains("rose-400") -> android.graphics.Color.parseColor("#fb7185")
                colorStr.contains("rose-500") -> android.graphics.Color.parseColor("#f43f5e")
                
                colorStr.contains("pink-300") -> android.graphics.Color.parseColor("#f9a8d4")
                colorStr.contains("pink-400") -> android.graphics.Color.parseColor("#f472b6")
                
                colorStr.contains("fuchsia-300") -> android.graphics.Color.parseColor("#f0abfc")
                colorStr.contains("fuchsia-400") -> android.graphics.Color.parseColor("#e879f9")
                colorStr.contains("fuchsia-500") -> android.graphics.Color.parseColor("#d946ef")
                
                colorStr.contains("purple-300") -> android.graphics.Color.parseColor("#c4b5fd")
                colorStr.contains("purple-400") -> android.graphics.Color.parseColor("#a78bfa")
                
                colorStr.contains("violet-300") -> android.graphics.Color.parseColor("#c4b5fd")
                colorStr.contains("violet-400") -> android.graphics.Color.parseColor("#a78bfa")
                
                colorStr.contains("indigo-200") -> android.graphics.Color.parseColor("#c7d2fe")
                colorStr.contains("indigo-300") -> android.graphics.Color.parseColor("#a5b4fc")
                colorStr.contains("indigo-400") -> android.graphics.Color.parseColor("#818cf8")
                
                colorStr.contains("blue-200") -> android.graphics.Color.parseColor("#bfdbfe")
                colorStr.contains("blue-300") -> android.graphics.Color.parseColor("#93c5fd")
                colorStr.contains("blue-400") -> android.graphics.Color.parseColor("#60a5fa")
                colorStr.contains("blue-500") -> android.graphics.Color.parseColor("#3b82f6")
                
                colorStr.contains("sky-200") -> android.graphics.Color.parseColor("#bae6fd")
                colorStr.contains("sky-300") -> android.graphics.Color.parseColor("#7dd3fc")
                colorStr.contains("sky-400") -> android.graphics.Color.parseColor("#38bdf8")
                
                colorStr.contains("cyan-200") -> android.graphics.Color.parseColor("#a5f3fc")
                colorStr.contains("cyan-300") -> android.graphics.Color.parseColor("#67e8f9")
                colorStr.contains("cyan-400") -> android.graphics.Color.parseColor("#22d3ee")
                
                colorStr.contains("teal-300") -> android.graphics.Color.parseColor("#5eead4")
                colorStr.contains("teal-400") -> android.graphics.Color.parseColor("#2dd4bf")
                
                colorStr.contains("emerald-200") -> android.graphics.Color.parseColor("#a7f3d0")
                colorStr.contains("emerald-300") -> android.graphics.Color.parseColor("#6ee7b7")
                colorStr.contains("emerald-400") -> android.graphics.Color.parseColor("#34d399")
                colorStr.contains("emerald-500") -> android.graphics.Color.parseColor("#10b981")
                
                colorStr.contains("green-300") -> android.graphics.Color.parseColor("#86efac")
                colorStr.contains("green-400") -> android.graphics.Color.parseColor("#4ade80")
                colorStr.contains("green-500") -> android.graphics.Color.parseColor("#22c55e")
                
                colorStr.contains("lime-300") -> android.graphics.Color.parseColor("#bef264")
                colorStr.contains("lime-400") -> android.graphics.Color.parseColor("#a3e635")
                colorStr.contains("lime-500") -> android.graphics.Color.parseColor("#84cc16")
                
                colorStr.contains("stone-200") -> android.graphics.Color.parseColor("#e7e5e4")
                colorStr.contains("stone-300") -> android.graphics.Color.parseColor("#d6d3d1")
                
                colorStr.contains("neutral-300") -> android.graphics.Color.parseColor("#d4d4d4")
                colorStr.contains("neutral-400") -> android.graphics.Color.parseColor("#a3a3a3")
                
                colorStr.contains("gray-300") -> android.graphics.Color.parseColor("#d1d5db")
                colorStr.contains("gray-400") -> android.graphics.Color.parseColor("#9ca3af")
                colorStr.contains("gray-500") -> android.graphics.Color.parseColor("#6b7280")
                
                colorStr.contains("slate-300") -> android.graphics.Color.parseColor("#cbd5e1")
                colorStr.contains("slate-400") -> android.graphics.Color.parseColor("#94a3b8")
                colorStr.contains("slate-500") -> android.graphics.Color.parseColor("#64748b")
                else -> parseHexColor("#f59e0b") 
            }
        }
        
        
        fun fromJson(json: JSONObject): NotificationScene {
            val rawThemeColors = json.opt("themeColors")
            val themeColorsArray = when (rawThemeColors) {
                is org.json.JSONArray -> rawThemeColors
                is String -> {
                    try { org.json.JSONArray(rawThemeColors) } catch (e: Exception) { null }
                }
                else -> null
            }
            
            val themeColors = mutableListOf<String>()
            if (themeColorsArray != null) {
                for (i in 0 until themeColorsArray.length()) {
                    val color = themeColorsArray.optString(i)
                    if (color != null) themeColors.add(color)
                }
            }
            
            val soundUri = when {
                json.has("soundUri") -> json.optString("soundUri", "").ifBlank { null }
                json.has("sound") -> json.optString("sound", "").ifBlank { null }
                else -> null
            }
            val soundEnabled = if (json.has("soundEnabled")) json.optBoolean("soundEnabled") else null

            return NotificationScene(
                id = json.optString("id", ""),
                icon = json.optString("icon", "fa-bell"),
                iconColor = json.optString("iconColor", "text-amber-200"),
                title = json.optString("title", "Notification"),
                desc = json.optString("desc", ""),
                subDesc = json.optString("subDesc", ""),
                themeColors = themeColors.ifEmpty { listOf("#f59e0b", "#d97706", "#92400e", "#78350f") },
                beamColor = json.optString("beamColor", "rgba(251, 191, 36, 0.8)"),
                dividerColor = json.optString("dividerColor", "rgba(251, 191, 36, 0.8)"),
                dotColor = json.optString("dotColor", "bg-amber-300"),
                animation = json.optString("animation", ""),
                soundUri = soundUri,
                soundEnabled = soundEnabled,
            )
        }
    }
}


object NotificationScenes {
    private const val TAG = "NotificationScenes"
    private const val SCENES_URL_ZH =
        "https://raw.githubusercontent.com/knoop7/Ava/refs/heads/master/scenes_zh.json"
    private const val SCENES_URL_DE =
        "https://raw.githubusercontent.com/knoop7/Ava/refs/heads/master/scenes_de.json"
    private const val SCENES_URL_EN =
        "https://raw.githubusercontent.com/knoop7/Ava/refs/heads/master/scenes_en.json"
    private const val CACHE_FILE_ZH = "scenes_zh_cache.json"
    private const val CACHE_FILE_DE = "scenes_de_cache.json"
    private const val CACHE_FILE_EN = "scenes_en_cache.json"
    private const val CACHE_FILE_CUSTOM = "scenes_custom_cache.json"
    
    private var _builtInScenes: List<NotificationScene> = emptyList()
    private var _customScenes: List<NotificationScene> = emptyList()
    private var _localScenes: List<NotificationScene> = emptyList()
    /**
     * Ephemeral editor/preview scene. Looked up by [getSceneById] only —
     * never merges into [ALL_SCENES] / titles / HA entity subscriptions.
     */
    @Volatile
    private var _previewOverride: NotificationScene? = null
    private var isLoaded = false
    private var appContext: Context? = null
    private var loadedLanguage: String? = null
    private val customLoadGeneration = AtomicInteger(0)
    private val customNetworkRetryLock = Any()
    private val customNetworkRetryInFlight = AtomicBoolean(false)
    private val customNetworkRetryOwner = AtomicInteger(0)
    private val customNetworkRetryQueued = AtomicBoolean(false)
    private var customNetworkRetry: CustomSceneNetworkRetry? = null

    private class CustomSceneNetworkRetry(
        val generation: Int,
        val callback: ConnectivityManager.NetworkCallback,
    )

    private enum class CustomFetchResult { APPLIED, DONE, RETRY }
    
    
    sealed class SceneLoadState {
        object Idle : SceneLoadState()
        object Loading : SceneLoadState()
        object Success : SceneLoadState()
        data class Error(val resId: Int, val detail: String? = null) : SceneLoadState()
    }

    
    var refreshCount = androidx.compose.runtime.mutableStateOf(0)
        private set

    /** 场景列表加载/刷新后回调，供外部（VoiceSatelliteService）重新订阅占位符引用的 HA 实体。 */
    @Volatile
    var onScenesReloaded: (() -> Unit)? = null

    private fun notifyReloaded() {
        refreshCount.value++
        try { onScenesReloaded?.invoke() } catch (_: Exception) {}
    }

    var loadState: SceneLoadState = SceneLoadState.Idle
        private set
    
    
    /**
     * Merge order:
     * 1) User section — every local-store entry (pure `local_*` + overlays),
     *    in store order (newest / last-saved first).
     * 2) Untouched built-in + URL (ids without a local overlay).
     */
    private val _scenes: List<NotificationScene>
        get() {
            val overlayIds = _localScenes.map { it.id }.toHashSet()
            val base = _builtInScenes + _customScenes
            val untouched = base.filter { it.id !in overlayIds }
            return _localScenes + untouched
        }

    /** Pure local or a saved overlay of built-in/URL — library "user" pin group. */
    fun isUserPinnedScene(id: String): Boolean =
        isLocalScene(id) || hasLocalOverride(id)
    
    val ALL_SCENES: List<NotificationScene>
        get() = _scenes

    val LOCAL_SCENES: List<NotificationScene>
        get() = _localScenes

    val BUILTIN_SCENES: List<NotificationScene>
        get() = _builtInScenes

    val CUSTOM_URL_SCENES: List<NotificationScene>
        get() = _customScenes

    /** Pure user-created scene (`local_*`), not an overlay of built-in/URL. */
    fun isLocalScene(id: String): Boolean = id.startsWith("local_")

    fun isCustomUrlScene(id: String): Boolean =
        _customScenes.any { it.id == id } || id.startsWith("custom_")

    fun isBuiltInScene(id: String): Boolean = _builtInScenes.any { it.id == id }

    /** Local store entry exists for this id (pure local or overlay). */
    fun hasLocalOverride(id: String): Boolean = _localScenes.any { it.id == id }

    /**
     * Replace in-memory local scenes (from DataStore).
     * @param notify when false, skip HA resubscribe / select-option refresh
     *   (use for silent restore; never for unsaved editor drafts).
     */
    fun setLocalScenes(scenes: List<NotificationScene>, notify: Boolean = true) {
        _localScenes = scenes
        if (notify) notifyReloaded()
    }

    /** Install a memory-only scene for overlay preview while editing. */
    fun setPreviewOverride(scene: NotificationScene?) {
        _previewOverride = scene
    }

    fun clearPreviewOverride() {
        _previewOverride = null
    }
    
    
    val ALL_SCENE_IDS: List<String>
        get() = _scenes.map { it.id }
    
    
    private fun scenesLanguage(): String = when {
        LocaleUtils.isChineseLocale() -> "zh"
        LocaleUtils.isGermanLocale() -> "de"
        else -> "en"
    }

    private fun scenesUrl(): String = when (scenesLanguage()) {
        "zh" -> SCENES_URL_ZH
        "de" -> SCENES_URL_DE
        else -> SCENES_URL_EN
    }

    private fun scenesCacheFile(): String = when (scenesLanguage()) {
        "zh" -> CACHE_FILE_ZH
        "de" -> CACHE_FILE_DE
        else -> CACHE_FILE_EN
    }
    
    fun loadFromAssets(context: Context, onComplete: (() -> Unit)? = null) {
        appContext = context.applicationContext
        val currentLanguage = scenesLanguage()
        if (isLoaded && loadedLanguage == currentLanguage) {
            onComplete?.invoke()
            return
        }
        if (loadedLanguage != currentLanguage) {
            isLoaded = false
            _builtInScenes = emptyList()
        }
        if (loadFromCache(context)) {
            isLoaded = true
            loadedLanguage = currentLanguage
            loadState = SceneLoadState.Success
            onComplete?.invoke()
            loadFromNetwork(null)
            return
        }
        loadFromNetwork(onComplete)
    }
    
    private fun loadFromCache(context: Context): Boolean {
        val cacheFile = context.getFileStreamPath(scenesCacheFile())
        if (!cacheFile.exists()) return false
        return try {
            val jsonString = cacheFile.readText()
            parseBuiltInJson(jsonString)
            Log.d(TAG, "Loaded scenes from cache")
            _builtInScenes.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from cache", e)
            false
        }
    }
    
    private fun saveToCache(context: Context, jsonString: String) {
        try {
            val cacheFile = scenesCacheFile()
            context.openFileOutput(cacheFile, Context.MODE_PRIVATE).use { output ->
                output.write(jsonString.toByteArray())
            }
            Log.d(TAG, "Saved scenes to cache: $cacheFile")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save to cache", e)
        }
    }
    
    private fun loadFromNetwork(onComplete: (() -> Unit)? = null) {
        loadState = SceneLoadState.Loading
        val currentLanguage = scenesLanguage()
        val directUrl = scenesUrl()
        Thread {
            var loaded = false
            for (url in sceneUrlCandidates(directUrl)) {
                var connection: java.net.HttpURLConnection? = null
                try {
                    Log.d(TAG, "Loading scenes from $url")
                    connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Ava)")
                    connection.instanceFollowRedirects = true

                    if (connection.responseCode == 200) {
                        val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                        parseBuiltInJson(jsonString)
                        appContext?.let { saveToCache(it, jsonString) }
                        isLoaded = true
                        loadedLanguage = currentLanguage
                        loadState = SceneLoadState.Success
                        loaded = true
                        Log.d(TAG, "Loaded scenes from network: ${currentLanguage.uppercase()}")
                        break
                    }
                    Log.e(TAG, "Failed to load scenes: HTTP ${connection.responseCode} for $url")
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading scenes from $url", e)
                } finally {
                    connection?.disconnect()
                }
            }
            if (!loaded) {
                loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_parse_failed)
            }
            onComplete?.invoke()
        }.start()
    }

    /** zh/ru prefer proxy mirrors first; others try GitHub direct first. */
    private fun sceneUrlCandidates(directUrl: String): List<String> =
        GithubProxyUrls.candidates(appContext, GithubProxyUrls.toDirectUrl(directUrl))
    
    
    private fun parseBuiltInJson(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val scenesArray = json.optJSONArray("scenes") ?: return
            
            val scenes = mutableListOf<NotificationScene>()
            for (i in 0 until scenesArray.length()) {
                val sceneJson = scenesArray.getJSONObject(i)
                scenes.add(NotificationScene.fromJson(sceneJson))
            }
            
            _builtInScenes = scenes
            notifyReloaded()
        } catch (e: Exception) {
            
        }
    }
    
    
    fun parseJson(jsonString: String) {
        parseBuiltInJson(jsonString)
    }
    
    
    fun loadCustomSceneFromUrl(
        url: String,
        onComplete: (() -> Unit)? = null,
        context: Context? = null,
    ) {
        context?.applicationContext?.let { appContext = it }
        val (generation, staleCallback) = beginCustomLoad()
        unregisterCustomNetworkCallback(staleCallback)

        if (url.isBlank()) {
            val changed = synchronized(customNetworkRetryLock) {
                val hadScenes = _customScenes.isNotEmpty() || loadState != SceneLoadState.Idle
                _customScenes = emptyList()
                loadState = SceneLoadState.Idle
                hadScenes
            }
            if (changed) notifyReloaded()
            onComplete?.invoke()
            return
        }

        // 开机时 Wi-Fi 经常还没有地址。先把这个 URL 上次成功的结果放出来，
        // 失败的请求不能把列表清掉。换了 URL 则不能继续显示上一份。
        val cached = loadCustomCache(url)
        if (cached != null) {
            if (publishCustomScenes(generation, cached)) {
                Log.d(TAG, "Restored ${cached.size} custom scenes from cache")
            }
        } else {
            val cleared = synchronized(customNetworkRetryLock) {
                if (generation != customLoadGeneration.get()) {
                    false
                } else {
                    val hadScenes = _customScenes.isNotEmpty()
                    _customScenes = emptyList()
                    loadState = SceneLoadState.Loading
                    hadScenes
                }
            }
            if (cleared) notifyReloaded()
        }

        Thread {
            when (fetchCustomScenesWithRetry(url, generation)) {
                CustomFetchResult.APPLIED -> onComplete?.invoke()
                CustomFetchResult.DONE -> Unit
                CustomFetchResult.RETRY -> scheduleCustomNetworkRetry(url, generation)
            }
        }.start()
    }

    private fun beginCustomLoad(): Pair<Int, ConnectivityManager.NetworkCallback?> =
        synchronized(customNetworkRetryLock) {
            customNetworkRetryQueued.set(false)
            val generation = customLoadGeneration.incrementAndGet()
            val callback = customNetworkRetry?.callback
            customNetworkRetry = null
            generation to callback
        }

    private fun publishCustomScenes(generation: Int, scenes: List<NotificationScene>): Boolean {
        val published = synchronized(customNetworkRetryLock) {
            if (generation != customLoadGeneration.get()) {
                false
            } else {
                _customScenes = scenes
                loadState = SceneLoadState.Success
                true
            }
        }
        if (published) notifyReloaded()
        return published
    }

    private fun saveCustomCache(url: String, jsonString: String, generation: Int) {
        if (generation != customLoadGeneration.get()) return
        val ctx = appContext ?: return
        try {
            val wrapper = JSONObject()
                .put("url", url)
                .put("payload", jsonString)
            ctx.openFileOutput(CACHE_FILE_CUSTOM, Context.MODE_PRIVATE).use { output ->
                output.write(wrapper.toString().toByteArray())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save custom scene cache", e)
        }
    }

    /** Cache is keyed by URL so a different link does not flash the previous pack. */
    private fun loadCustomCache(url: String): List<NotificationScene>? {
        val ctx = appContext ?: return null
        val cacheFile = ctx.getFileStreamPath(CACHE_FILE_CUSTOM)
        if (!cacheFile.exists()) return null
        return try {
            val wrapper = JSONObject(cacheFile.readText())
            if (wrapper.optString("url") != url) return null
            val payload = wrapper.optString("payload")
            if (payload.isBlank()) return null
            parseRemoteJson(payload, applyLoadState = false).ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read custom scene cache", e)
            null
        }
    }

    private fun fetchCustomScenesWithRetry(url: String, generation: Int): CustomFetchResult {
        val pausesMs = longArrayOf(2_000L, 5_000L)
        var last = CustomFetchResult.RETRY
        for (attempt in 1..3) {
            if (generation != customLoadGeneration.get()) return CustomFetchResult.DONE
            last = fetchCustomScenesOnce(url, generation, attempt)
            if (last != CustomFetchResult.RETRY) return last
            if (attempt <= pausesMs.size) {
                try {
                    Thread.sleep(pausesMs[attempt - 1])
                } catch (_: InterruptedException) {
                    return CustomFetchResult.RETRY
                }
            }
        }
        return last
    }

    private fun fetchCustomScenesOnce(
        url: String,
        generation: Int,
        attempt: Int,
    ): CustomFetchResult {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Ava)")

            val code = connection.responseCode
            if (code == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                if (generation != customLoadGeneration.get()) return CustomFetchResult.DONE
                val scenes = parseRemoteJson(jsonString, applyLoadState = false)
                if (scenes.isNotEmpty()) {
                    if (!publishCustomScenes(generation, scenes)) return CustomFetchResult.DONE
                    saveCustomCache(url, jsonString, generation)
                    return CustomFetchResult.APPLIED
                }
                // 门户页或其它 200 HTML 不是场景列表，等网络就绪后再试。
                // 合法的空 JSON 则停下来，已经显示的缓存保持不动。
                val trimmed = jsonString.trim().removePrefix("\uFEFF")
                if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
                    return CustomFetchResult.RETRY
                }
                synchronized(customNetworkRetryLock) {
                    if (generation == customLoadGeneration.get() && _customScenes.isEmpty()) {
                        loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_no_scenes)
                    }
                }
                return CustomFetchResult.DONE
            }
            val attemptNote = if (attempt == 0) "after network" else "attempt $attempt/3"
            Log.e(TAG, "Failed to load custom scenes: HTTP $code ($attemptNote)")
            if (code in 400..499) return CustomFetchResult.DONE
            return CustomFetchResult.RETRY
        } catch (e: Exception) {
            val attemptNote = if (attempt == 0) "after network" else "attempt $attempt/3"
            Log.e(TAG, "Error loading custom scenes from URL ($attemptNote)", e)
            return CustomFetchResult.RETRY
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Timed retries lose when Wi-Fi associates after the backoff window.
     * Wait until a network with internet (and, on M+, validated) shows up,
     * then fetch once more. A newer URL load cancels this watch.
     */
    private fun scheduleCustomNetworkRetry(url: String, generation: Int) {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // On M+, onAvailable often fires before DHCP/validation.
                // Wait for onCapabilitiesChanged so the retry is not burned.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    retryCustomScenesAfterNetwork(url, generation)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    return
                }
                retryCustomScenesAfterNetwork(url, generation)
            }
        }
        synchronized(customNetworkRetryLock) {
            if (generation != customLoadGeneration.get()) return
            if (customNetworkRetry != null) return
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
                customNetworkRetry = CustomSceneNetworkRetry(generation, callback)
                Log.d(TAG, "Waiting for network to refresh custom scenes")
            } catch (e: Exception) {
                Log.w(TAG, "Could not watch network for custom scenes", e)
            }
        }
    }

    private fun retryCustomScenesAfterNetwork(url: String, generation: Int) {
        if (generation != customLoadGeneration.get()) {
            unregisterCustomNetworkCallback(takeCustomNetworkCallback(generation))
            return
        }
        if (!customNetworkRetryInFlight.compareAndSet(false, true)) {
            // 同一次加载里，onAvailable 和 validated 会连着到。这次失败后再补一次。
            if (customNetworkRetryOwner.get() == generation) {
                customNetworkRetryQueued.set(true)
            }
            return
        }
        customNetworkRetryOwner.set(generation)
        Thread {
            var finished = false
            try {
                while (generation == customLoadGeneration.get()) {
                    customNetworkRetryQueued.set(false)
                    when (fetchCustomScenesOnce(url, generation, attempt = 0)) {
                        CustomFetchResult.APPLIED, CustomFetchResult.DONE -> {
                            finished = true
                            customNetworkRetryQueued.set(false)
                            unregisterCustomNetworkCallback(takeCustomNetworkCallback(generation))
                            return@Thread
                        }
                        CustomFetchResult.RETRY -> {
                            if (!customNetworkRetryQueued.get()) return@Thread
                        }
                    }
                }
            } finally {
                if (customNetworkRetryOwner.get() == generation) {
                    customNetworkRetryInFlight.set(false)
                    if (!finished &&
                        customNetworkRetryQueued.getAndSet(false) &&
                        generation == customLoadGeneration.get()
                    ) {
                        retryCustomScenesAfterNetwork(url, generation)
                    }
                }
            }
        }.start()
    }

    private fun takeCustomNetworkCallback(generation: Int): ConnectivityManager.NetworkCallback? =
        synchronized(customNetworkRetryLock) {
            val current = customNetworkRetry ?: return null
            if (current.generation != generation) return null
            customNetworkRetry = null
            current.callback
        }

    private fun unregisterCustomNetworkCallback(callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        val ctx = appContext ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregister custom scene network callback failed", e)
        }
    }
    
    
    private fun parseRemoteJson(
        jsonString: String,
        applyLoadState: Boolean = true,
    ): List<NotificationScene> {
        fun report(state: SceneLoadState) {
            if (applyLoadState) loadState = state
        }
        return try {
            var processedJson = jsonString.trim()
            if (processedJson.isEmpty()) {
                report(SceneLoadState.Error(com.example.ava.R.string.error_json_empty))
                return emptyList()
            }

            // Strip UTF-8 BOM
            if (processedJson.startsWith("\uFEFF")) {
                processedJson = processedJson.substring(1)
            }

            val jsonArray = extractJsonArray(processedJson)
            if (jsonArray == null) {
                report(SceneLoadState.Error(com.example.ava.R.string.error_json_not_array))
                return emptyList()
            }

            val scenes = mutableListOf<NotificationScene>()
            for (i in 0 until jsonArray.length()) {
                try {
                    val item = jsonArray.opt(i) ?: continue
                    val sceneJson = when (item) {
                        is JSONObject -> item
                        is String -> try { JSONObject(item) } catch (_: Exception) { continue }
                        else -> continue
                    }
                    if (validateSceneJson(sceneJson)) {
                        val originalId = sceneJson.optString("id", "")
                        val prefixedId = "custom_$originalId"
                        scenes.add(NotificationScene.fromJson(sceneJson).copy(id = prefixedId))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed scene at index $i: ${e.message}")
                }
            }
            if (scenes.isEmpty()) {
                report(SceneLoadState.Error(com.example.ava.R.string.error_json_no_scenes))
            } else {
                report(SceneLoadState.Success)
            }
            scenes
        } catch (e: Exception) {
            report(SceneLoadState.Error(com.example.ava.R.string.error_json_parse_failed, e.message))
            Log.e(TAG, "Error parsing remote JSON: ${e.message}", e)
            emptyList()
        }
    }

    private fun extractJsonArray(json: String): org.json.JSONArray? {
        // 1. Direct array: [...]
        if (json.startsWith("[")) {
            return try { org.json.JSONArray(json) } catch (_: Exception) { null }
        }
        // 2. Object with known keys: { "scenes": [...] } or { "data": [...] } or { "items": [...] }
        if (json.startsWith("{")) {
            return try {
                val obj = JSONObject(json)
                obj.optJSONArray("scenes")
                    ?: obj.optJSONArray("data")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("list")
                    ?: run {
                        // Fallback: find the first JSONArray value in the object
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val arr = obj.optJSONArray(keys.next())
                            if (arr != null && arr.length() > 0) return@run arr
                        }
                        // Single scene object wrapped: { "id": "...", "icon": "..." }
                        if (obj.has("id") && obj.has("icon")) {
                            return org.json.JSONArray().put(obj)
                        }
                        null
                    }
            } catch (_: Exception) { null }
        }
        return null
    }
    
    
    private fun validateSceneJson(json: JSONObject): Boolean {
        val requiredFields = listOf("id", "icon", "title")
        return requiredFields.all { json.has(it) && json.optString(it).isNotBlank() }
    }
    
    
    fun getSceneById(id: String): NotificationScene? {
        _previewOverride?.let { if (it.id == id) return it }
        return _scenes.find { it.id == id }
    }
    
    
    fun getSceneByTitle(title: String): NotificationScene? {
        
        val exactMatch = _scenes.find { it.title == title }
        if (exactMatch != null) return exactMatch
        
        
        if (title.startsWith("Custom: ")) {
            val originalTitle = title.substring(8).trim()
            return _customScenes.find { it.title == originalTitle }
        }
        
        return null
    }
    
    
    fun getSceneByIndex(index: Int): NotificationScene? {
        return _scenes.getOrNull(index)
    }
    
    
    val ALL_SCENE_TITLES: List<String>
        get() {
            val overlays = _localScenes.associateBy { it.id }
            val builtInTitles = _builtInScenes.map { (overlays[it.id] ?: it).title }
            val customTitles = _customScenes.map { scene ->
                "Custom: ${(overlays[scene.id] ?: scene).title}"
            }
            val pureLocalTitles = _localScenes
                .filter { entry ->
                    _builtInScenes.none { it.id == entry.id } &&
                        _customScenes.none { it.id == entry.id }
                }
                .map { it.title }
            return builtInTitles + customTitles + pureLocalTitles
        }
    
    
    val size: Int
        get() = _scenes.size
    
    
    fun reload(context: Context) {
        isLoaded = false
        loadFromAssets(context)
    }
    
    fun ensureLoaded(context: Context) {
        if (_builtInScenes.isEmpty()) {
            loadFromCache(context)
        }
        if (_builtInScenes.isEmpty() && !isLoaded) {
            loadFromAssets(context)
        }
    }
    
    
    val hasCustomScenes: Boolean
        get() = _customScenes.isNotEmpty()
    
    
    val customSceneCount: Int
        get() = _customScenes.size
}
