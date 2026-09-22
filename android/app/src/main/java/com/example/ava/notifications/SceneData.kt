package com.example.ava.notifications

import android.content.Context
import android.util.Log
import androidx.annotation.ColorInt
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.ava.utils.LocaleUtils


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
    val animation: String       
) {
    
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
                animation = json.optString("animation", "")
            )
        }
    }
}


object NotificationScenes {
    private const val TAG = "NotificationScenes"
    private const val SCENES_URL_ZH = "https://ghfast.top/https://raw.githubusercontent.com/knoop7/Ava/refs/heads/master/scenes_zh.json"
    private const val SCENES_URL_EN = "https://ghfast.top/https://raw.githubusercontent.com/knoop7/Ava/refs/heads/master/scenes_en.json"
    private const val CACHE_FILE_ZH = "scenes_zh_cache.json"
    private const val CACHE_FILE_EN = "scenes_en_cache.json"
    private const val CACHE_FILE_CUSTOM = "scenes_custom_cache.json"
    
    private var _builtInScenes: List<NotificationScene> = emptyList()
    private var _customScenes: List<NotificationScene> = emptyList()
    private var isLoaded = false
    private var appContext: Context? = null
    private var loadedLanguage: Boolean? = null
    
    
    sealed class SceneLoadState {
        object Idle : SceneLoadState()
        object Loading : SceneLoadState()
        object Success : SceneLoadState()
        data class Error(val resId: Int, val detail: String? = null) : SceneLoadState()
    }

    
    var refreshCount = androidx.compose.runtime.mutableStateOf(0)
        private set
        
    
    var loadState: SceneLoadState = SceneLoadState.Idle
        private set
    
    
    private val _scenes: List<NotificationScene>
        get() = _builtInScenes + _customScenes
    
    val ALL_SCENES: List<NotificationScene>
        get() = _scenes
    
    
    val ALL_SCENE_IDS: List<String>
        get() = _scenes.map { it.id }
    
    
    private fun isChinese(): Boolean = LocaleUtils.isChineseLocale()
    
    fun loadFromAssets(context: Context, onComplete: (() -> Unit)? = null) {
        appContext = context.applicationContext
        val currentLanguage = isChinese()
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
        val cacheFile = context.getFileStreamPath(if (isChinese()) CACHE_FILE_ZH else CACHE_FILE_EN)
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
            val cacheFile = if (isChinese()) CACHE_FILE_ZH else CACHE_FILE_EN
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
        val currentLanguage = isChinese()
        val url = if (currentLanguage) SCENES_URL_ZH else SCENES_URL_EN
        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Ava)")
                
                if (connection.responseCode == 200) {
                    val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                    parseBuiltInJson(jsonString)
                    appContext?.let { saveToCache(it, jsonString) }
                    isLoaded = true
                    loadedLanguage = currentLanguage
                    loadState = SceneLoadState.Success
                    Log.d(TAG, "Loaded scenes from network: ${if (currentLanguage) "ZH" else "EN"}")
                } else {
                    Log.e(TAG, "Failed to load scenes: HTTP ${connection.responseCode}")
                    loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_parse_failed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading scenes from network", e)
                loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_parse_failed, e.message)
            } finally {
                connection?.disconnect()
                onComplete?.invoke()
            }
        }.start()
    }
    
    
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
            refreshCount.value++
        } catch (e: Exception) {
            
        }
    }
    
    
    fun parseJson(jsonString: String) {
        parseBuiltInJson(jsonString)
    }
    
    
    private fun saveCustomCache(jsonString: String) {
        val ctx = appContext ?: return
        try {
            ctx.openFileOutput(CACHE_FILE_CUSTOM, Context.MODE_PRIVATE).use {
                it.write(jsonString.toByteArray())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save custom scene cache", e)
        }
    }

    private fun loadCustomCache(): List<NotificationScene>? {
        val ctx = appContext ?: return null
        val cacheFile = ctx.getFileStreamPath(CACHE_FILE_CUSTOM)
        if (!cacheFile.exists()) return null
        return try {
            parseRemoteJson(cacheFile.readText()).ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read custom scene cache", e)
            null
        }
    }

    fun loadCustomSceneFromUrl(
        url: String,
        onComplete: (() -> Unit)? = null
    ) {
        if (url.isBlank()) {
            _customScenes = emptyList()
            onComplete?.invoke()
            return
        }

        // Serve the last known-good custom scenes immediately, before any
        // network attempt. This is what actually fixes empty scene lists
        // right after boot: the UI has scenes to show from the very first
        // frame, independent of whether Wi-Fi has an IP yet. The network
        // fetch below only refreshes this on success; it never blocks or
        // clears what's already showing.
        loadCustomCache()?.let {
            _customScenes = it
            refreshCount.value++
        }

        Thread {
            val maxAttempts = 3
            var attempt = 0
            var delayMs = 2000L

            while (attempt < maxAttempts) {
                attempt++
                var connection: java.net.HttpURLConnection? = null
                try {
                    connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Ava)")

                    if (connection.responseCode == 200) {
                        val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                        val scenes = parseRemoteJson(jsonString)
                        if (scenes.isNotEmpty()) {
                            _customScenes = scenes
                            saveCustomCache(jsonString)
                            refreshCount.value++

                            onComplete?.invoke()
                        }
                        return@Thread
                    } else {
                        Log.e(TAG, "Failed to load custom scenes: HTTP ${connection.responseCode} (attempt $attempt/$maxAttempts)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading custom scenes from URL (attempt $attempt/$maxAttempts)", e)
                } finally {
                    connection?.disconnect()
                }

                if (attempt < maxAttempts) {
                    Thread.sleep(delayMs)
                    delayMs *= 2
                }
            }
        }.start()
    }
    
    
    private fun parseRemoteJson(jsonString: String): List<NotificationScene> {
        return try {
            val processedJson = jsonString.trim()
            if (processedJson.isEmpty()) {
                loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_empty)
                return emptyList()
            }
            
            if (!processedJson.startsWith("[")) {
                loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_not_array)
                return emptyList()
            }
            
            val jsonArray = org.json.JSONArray(processedJson)
            val scenes = mutableListOf<NotificationScene>()
            for (i in 0 until jsonArray.length()) {
                val sceneJson = jsonArray.getJSONObject(i)
                if (validateSceneJson(sceneJson)) {
                    val originalId = sceneJson.optString("id", "")
                    val prefixedId = "custom_$originalId"
                    scenes.add(NotificationScene.fromJson(sceneJson).copy(id = prefixedId))
                }
            }
            if (scenes.isEmpty()) {
                loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_no_scenes)
            } else {
                loadState = SceneLoadState.Success
            }
            scenes
        } catch (e: Exception) {
            loadState = SceneLoadState.Error(com.example.ava.R.string.error_json_parse_failed, e.message)
            Log.e(TAG, "Error parsing remote JSON: ${e.message}", e)
            emptyList()
        }
    }
    
    
    private fun validateSceneJson(json: JSONObject): Boolean {
        val requiredFields = listOf("id", "icon", "title")
        return requiredFields.all { json.has(it) && json.optString(it).isNotBlank() }
    }
    
    
    fun getSceneById(id: String): NotificationScene? {
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
            val builtInTitles = _builtInScenes.map { it.title }
            val customTitles = _customScenes.map { scene ->
                "Custom: ${scene.title}"
            }
            return builtInTitles + customTitles
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
