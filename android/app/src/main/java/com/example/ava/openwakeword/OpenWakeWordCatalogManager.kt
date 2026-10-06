package com.example.ava.openwakeword

import android.content.Context
import android.util.Log
import com.example.ava.net.GithubProxyUrls
import com.example.ava.vswakeword.VsWakeWordCatalogEntry
import com.example.ava.wakewordlibrary.WakeWordLibraryManager
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Remote catalog for openWakeWord models.
 *
 * Two catalogs, kept separate:
 * 1. `index.json` — curated Ava catalog. Wake-word 1/2 picker only.
 * 2. `open_community.json` — community models. Wake-word library only.
 *
 * zh / ru locales try GitHub proxy mirrors first; others go direct.
 * Catalog fetch races all candidate URLs concurrently — first success wins
 * and that mirror is sticky for subsequent model downloads.
 */
class OpenWakeWordCatalogManager(
    context: Context,
    private val library: WakeWordLibraryManager = WakeWordLibraryManager.getInstance(context),
) {
    private val appContext = context.applicationContext

    @Volatile
    private var winnerPrefix: String? = null

    fun refresh(): OpenWakeWordCatalog {
        val curated = fetchTextRace(INDEX_URL)?.let(::parseIndex).orEmpty()
        val curatedIds = curated.map { OpenWakeWordSelectorPolicy.catalogIdKey(it.id) }.toSet()

        val community = fetchTextRace(COMMUNITY_URL)?.let(::parseCommunity)
            ?.filter { OpenWakeWordSelectorPolicy.catalogIdKey(it.id) !in curatedIds }
            .orEmpty()

        return OpenWakeWordCatalog(curated = curated, community = community)
    }

    fun download(
        entry: VsWakeWordCatalogEntry,
        onPercent: (Int) -> Unit = {},
    ): Boolean {
        val bytes = fetchBytes(entry.onnx, onPercent) ?: return false
        if (!looksLikeOnnx(bytes)) {
            Log.e(TAG, "download rejected (not onnx) id=${entry.id} url=${entry.onnx}")
            return false
        }
        if (entry.sha256.isNotBlank() && sha256(bytes) != entry.sha256.lowercase()) {
            Log.e(TAG, "download sha256 mismatch id=${entry.id}")
            return false
        }
        return library.installOpenOnnx(
            id = entry.id,
            displayName = entry.name,
            onnxBytes = bytes,
            author = entry.author,
            website = entry.website.ifBlank { REPO_TREE },
            license = entry.license,
            sourceUrl = entry.onnx,
            threshold = entry.threshold,
            requiredHits = entry.requiredHits,
            cooldownMs = entry.cooldownMs,
        )
    }

    // ---- Curated index.json ----

    private fun parseIndex(body: String): List<VsWakeWordCatalogEntry> {
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val models = root.getAsJsonArray("models") ?: return@runCatching emptyList()
            models.mapNotNull { el ->
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString?.trim().orEmpty()
                val file = obj.get("file")?.asString?.trim().orEmpty()
                val bucket = obj.get("bucket")?.asString?.trim().orEmpty().ifBlank { "models" }
                if (id.isEmpty() || file.isEmpty()) return@mapNotNull null
                val runtime = obj.get("openwakeword")?.takeIf { it.isJsonObject }?.asJsonObject
                val sourceUrl = obj.get("source_url")?.asString?.trim().orEmpty()
                VsWakeWordCatalogEntry(
                    id = id,
                    name = OpenWakeWordModel.correctedDisplayName(
                        id,
                        obj.get("spoken")?.asString?.trim().orEmpty().ifBlank { displayName(id) },
                    ),
                    json = "",
                    onnx = sourceUrl.ifBlank { "$RAW_ROOT/$bucket/$id/$file" },
                    author = obj.get("author")?.asString.orEmpty(),
                    website = sourceUrl.ifBlank { REPO_TREE },
                    license = obj.get("license")?.asString.orEmpty(),
                    sha256 = obj.get("sha256")?.asString.orEmpty(),
                    threshold = runtime?.get("threshold")?.takeIf { it.isJsonPrimitive }?.asFloat
                        ?: 0.5f,
                    requiredHits = runtime?.get("required_hits")?.takeIf { it.isJsonPrimitive }?.asInt
                        ?: 1,
                    cooldownMs = runtime?.get("cooldown_ms")?.takeIf { it.isJsonPrimitive }?.asInt
                        ?: 2000,
                )
            }
        }.onFailure { Log.e(TAG, "parse openwakeword index failed", it) }.getOrDefault(emptyList())
    }

    // ---- Community open_community.json ----

    private fun parseCommunity(body: String): List<VsWakeWordCatalogEntry> {
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val models = root.getAsJsonArray("models") ?: return@runCatching emptyList()
            models.mapNotNull { el ->
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString?.trim().orEmpty()
                val file = obj.get("file")?.asString?.trim().orEmpty()
                if (id.isEmpty() || file.isEmpty()) return@mapNotNull null
                val downloadUrl = obj.get("download_url")?.asString?.trim().orEmpty()
                val runtime = obj.get("openwakeword")?.takeIf { it.isJsonObject }?.asJsonObject
                VsWakeWordCatalogEntry(
                    id = id,
                    name = obj.get("spoken")?.asString?.trim().orEmpty().ifBlank { displayName(id) },
                    json = "",
                    onnx = downloadUrl.ifBlank { "$COMMUNITY_RAW_ROOT/$file" },
                    author = obj.get("author")?.asString.orEmpty(),
                    website = obj.get("source_url")?.asString.orEmpty().ifBlank { REPO_TREE },
                    license = obj.get("license")?.asString.orEmpty(),
                    sha256 = obj.get("sha256")?.asString.orEmpty(),
                    threshold = runtime?.get("threshold")?.takeIf { it.isJsonPrimitive }?.asFloat
                        ?: 0.5f,
                    requiredHits = runtime?.get("required_hits")?.takeIf { it.isJsonPrimitive }?.asInt
                        ?: 1,
                    cooldownMs = runtime?.get("cooldown_ms")?.takeIf { it.isJsonPrimitive }?.asInt
                        ?: 2000,
                )
            }.sortedBy { it.name.lowercase() }
        }.onFailure { Log.e(TAG, "parse community catalog failed", it) }
            .getOrDefault(emptyList())
    }

    // ── Race fetch (catalog JSON — small, fire all mirrors concurrently) ──

    private fun fetchTextRace(directUrl: String): String? {
        val urls = GithubProxyUrls.candidates(appContext, directUrl, winnerPrefix)
        if (urls.isEmpty()) return null
        if (urls.size == 1) return fetchTextOnce(urls[0])?.also { rememberWinner(urls[0]) }

        val pool = Executors.newFixedThreadPool(urls.size.coerceAtMost(5))
        val cs = ExecutorCompletionService<Pair<String, String?>>(pool)
        try {
            urls.forEach { url ->
                cs.submit(Callable { url to fetchTextOnce(url) })
            }
            repeat(urls.size) {
                val (url, text) = cs.poll(15, TimeUnit.SECONDS)?.get() ?: return@repeat
                if (text != null) {
                    rememberWinner(url)
                    return text
                }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    // ── Sequential fetch (model binary — large, use sticky winner) ──

    private fun fetchBytes(directUrl: String, onPercent: (Int) -> Unit): ByteArray? {
        for (url in GithubProxyUrls.candidates(appContext, directUrl, winnerPrefix)) {
            val bytes = fetchBytesOnce(url, onPercent)
            if (bytes != null) {
                rememberWinner(url)
                return bytes
            }
        }
        return null
    }

    // ── Low-level helpers ──

    private fun fetchTextOnce(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openGet(urlString)
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlString")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed $urlString: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchBytesOnce(urlString: String, onPercent: (Int) -> Unit): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openGet(urlString)
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlString")
                return null
            }
            val total = conn.contentLength
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(if (total > 0) total else 64 * 1024)
                val buf = ByteArray(16 * 1024)
                var read = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) onPercent(((read * 100L) / total).toInt().coerceIn(0, 100))
                }
                onPercent(100)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download failed $urlString: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun openGet(urlString: String): HttpURLConnection {
        return (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, application/octet-stream, */*")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Ava)")
        }
    }

    private fun rememberWinner(url: String) {
        winnerPrefix = GithubProxyUrls.PROXY_PREFIXES.firstOrNull { url.startsWith(it) }
    }

    companion object {
        private const val TAG = "OpenWakeWordCatalog"

        private const val REPO_TREE = "https://github.com/knoop7/Ava/tree/master/openwakeword"
        private const val RAW_ROOT =
            "https://raw.githubusercontent.com/knoop7/Ava/master/openwakeword"
        private const val INDEX_URL = "$RAW_ROOT/index.json"
        private const val COMMUNITY_URL = "$RAW_ROOT/open_community.json"
        private const val COMMUNITY_RAW_ROOT = RAW_ROOT

        fun isCatalogSourceUrl(url: String): Boolean =
            url.startsWith(RAW_ROOT) ||
                url.contains("/knoop7/Ava/", ignoreCase = true) ||
                url.contains("/fwartner/home-assistant-wakewords-collection/", ignoreCase = true)

        fun isAvaCuratedSource(url: String): Boolean =
            OpenWakeWordSelectorPolicy.isAvaCuratedSource(url)

        fun slug(raw: String): String {
            val s = raw.trim()
                .replace(Regex("\\s+"), "_")
                .replace(Regex("[^A-Za-z0-9._-]"), "")
                .lowercase()
            return s.ifBlank { "wakeword" }
        }

        fun displayName(folder: String): String {
            if (folder.contains(' ')) return folder
            return folder.split('_').filter { it.isNotBlank() }.joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }.ifBlank { folder }
        }

        fun looksLikeOnnx(bytes: ByteArray): Boolean {
            if (bytes.size < 64) return false
            if (bytes[0] == '<'.code.toByte()) return false
            val head = bytes.copyOfRange(0, 64.coerceAtMost(bytes.size)).toString(Charsets.ISO_8859_1)
            if (head.contains("<html", ignoreCase = true)) return false
            return true
        }

        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
