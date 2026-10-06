package com.example.ava.microwakeword

import android.content.Context
import android.util.Log
import com.example.ava.net.GithubProxyUrls
import com.example.ava.wakewordlibrary.WakeWordLibraryManager
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class MicroWakeWordCatalogEntry(
    val id: String,
    val name: String,
    val author: String,
    val tfliteUrl: String,
    val sourceUrl: String,
    val license: String = "",
    val sha256: String = "",
    val probabilityCutoff: Float = 0.85f,
    val slidingWindowSize: Int = 5,
    val featureStepSize: Int = 10,
    val tensorArenaSize: Int = 22860,
)

/**
 * Remote catalog for community microWakeWord models.
 *
 * Reads `micro_community.json` hosted at `knoop7/Ava/microwakeword/`.
 * zh / ru locales try GitHub proxy mirrors first; others go direct.
 * Catalog fetch races all candidate URLs concurrently — first success wins
 * and that mirror is sticky for subsequent model downloads.
 */
class MicroWakeWordCatalogManager(
    context: Context,
    private val library: WakeWordLibraryManager = WakeWordLibraryManager.getInstance(context),
) {
    private val appContext = context.applicationContext

    @Volatile
    private var winnerPrefix: String? = null

    fun refresh(): List<MicroWakeWordCatalogEntry> {
        return fetchTextRace(CATALOG_URL)?.let(::parseCatalog).orEmpty()
    }

    fun download(
        entry: MicroWakeWordCatalogEntry,
        onPercent: (Int) -> Unit = {},
    ): Boolean {
        val tfliteBytes = fetchBytes(entry.tfliteUrl, onPercent) ?: return false
        if (tfliteBytes.size < 64) {
            Log.e(TAG, "download rejected (too small) id=${entry.id}")
            return false
        }
        if (looksLikeHtml(tfliteBytes)) {
            Log.e(TAG, "download rejected (html) id=${entry.id}")
            return false
        }
        if (entry.sha256.isNotBlank() && sha256(tfliteBytes) != entry.sha256.lowercase()) {
            Log.e(TAG, "download sha256 mismatch id=${entry.id}")
            return false
        }
        return library.installMicroFromCatalog(
            id = entry.id,
            displayName = entry.name,
            tfliteBytes = tfliteBytes,
            author = entry.author,
            sourceUrl = entry.sourceUrl,
            license = entry.license,
            probabilityCutoff = entry.probabilityCutoff,
            slidingWindowSize = entry.slidingWindowSize,
            featureStepSize = entry.featureStepSize,
            tensorArenaSize = entry.tensorArenaSize,
        )
    }

    // ---- JSON parsing ----

    private fun parseCatalog(body: String): List<MicroWakeWordCatalogEntry> {
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val downloadBase = root.get("download_base")?.asString?.trimEnd('/').orEmpty()
            val models = root.getAsJsonArray("models") ?: return@runCatching emptyList()
            models.mapNotNull { el ->
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString?.trim().orEmpty()
                val file = obj.get("file")?.asString?.trim().orEmpty()
                if (id.isEmpty() || file.isEmpty()) return@mapNotNull null
                val micro = obj.get("micro")?.takeIf { it.isJsonObject }?.asJsonObject
                MicroWakeWordCatalogEntry(
                    id = id,
                    name = obj.get("spoken")?.asString?.trim().orEmpty().ifBlank { displayName(id) },
                    author = obj.get("author")?.asString.orEmpty(),
                    tfliteUrl = if (downloadBase.isNotEmpty()) "$downloadBase/$file"
                    else obj.get("download_url")?.asString.orEmpty().ifBlank { "$RAW_ROOT/$file" },
                    sourceUrl = obj.get("source_url")?.asString.orEmpty().ifBlank { REPO_TREE },
                    license = obj.get("license")?.asString.orEmpty(),
                    sha256 = obj.get("sha256")?.asString.orEmpty(),
                    probabilityCutoff = micro?.get("probability_cutoff")
                        ?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0.85f,
                    slidingWindowSize = micro?.get("sliding_window_size")
                        ?.takeIf { it.isJsonPrimitive }?.asInt ?: 5,
                    featureStepSize = micro?.get("feature_step_size")
                        ?.takeIf { it.isJsonPrimitive }?.asInt ?: 10,
                    tensorArenaSize = micro?.get("tensor_arena_size")
                        ?.takeIf { it.isJsonPrimitive }?.asInt ?: 22860,
                )
            }
        }.onFailure { Log.e(TAG, "parse micro catalog failed", it) }
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
        private const val TAG = "MicroWakeWordCatalog"

        private const val REPO_TREE =
            "https://github.com/knoop7/Ava/tree/master/microwakeword"
        private const val RAW_ROOT =
            "https://raw.githubusercontent.com/knoop7/Ava/master/microwakeword"
        private const val CATALOG_URL = "$RAW_ROOT/micro_community.json"

        fun isCatalogSourceUrl(url: String): Boolean =
            url.contains("/knoop7/Ava/", ignoreCase = true) ||
                url.contains("/TaterTotterson/microWakeWords/", ignoreCase = true)

        fun displayName(folder: String): String {
            if (folder.contains(' ')) return folder
            return folder.split('_').filter { it.isNotBlank() }.joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }.ifBlank { folder }
        }

        private fun looksLikeHtml(bytes: ByteArray): Boolean {
            if (bytes.size < 16) return false
            val head = bytes.copyOfRange(0, 64.coerceAtMost(bytes.size))
                .toString(Charsets.ISO_8859_1)
            return head.contains("<html", ignoreCase = true) ||
                head.contains("<!DOCTYPE", ignoreCase = true)
        }

        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
