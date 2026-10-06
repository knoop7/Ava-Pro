package com.example.ava.update

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.example.ava.net.GithubProxyUrls
import com.example.ava.utils.AmbientBitmapBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Masthead cover for the software-update screen: first image in a GitHub
 * release body, downsampled for the sharp layer plus a tiny software-blurred
 * ambient layer ([AmbientBitmapBlur] — same treatment as music covers).
 */
object ReleaseCoverArt {
    private const val TAG = "ReleaseCoverArt"

    /** Release banners are ~2600px wide; cap the sharp layer for wall-panel RAM. */
    private const val MAX_SHARP_WIDTH = 1280

    /** Keep covers for 3 calendar days (wall-clock); then refresh / delete. */
    private const val CACHE_TTL_MS = 3L * 24 * 60 * 60 * 1000

    /** How often the age sweeper re-checks [filesDir]/tmp (not Android cacheDir). */
    private const val CLEANUP_INTERVAL_MS = 12L * 60 * 60 * 1000

    /** App-owned temp dir — survives process death; OS won't wipe it like cacheDir. */
    private const val DISK_DIR = "tmp/release_covers"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    class Art(val sharp: Bitmap, val ambient: Bitmap?) {
        val ratio: Float get() = sharp.width.toFloat() / sharp.height.toFloat()
    }

    // ponytail: tiny per-process cache keyed by URL; 3 entries covers flipping
    // between a couple of releases without holding every banner in RAM.
    private val cache = LruCache<String, Art>(3)

    /**
     * Process-sticky race winner (same idea as ModManager). First cover download
     * races all mirrors; later ones reuse the winner so we don't burn 4× bandwidth.
     */
    @Volatile
    private var preferredProxyPrefix: String? = null

    @Volatile
    private var cleanupStarted = false

    /**
     * First image in a release body. GitHub uploads render as HTML
     * `<img src="…">` (0.6.8 does); Markdown `![](…)` is the fallback.
     * Whichever appears first in the text wins.
     */
    fun extractCoverUrl(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val html = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(body)
        val md = Regex("!\\[[^\\]]*]\\((https?://[^)\\s]+)").find(body)
        val first = listOfNotNull(html, md).minByOrNull { it.range.first } ?: return null
        return first.groupValues[1].takeIf { it.startsWith("http") }
    }

    // Own scope + in-flight dedupe: rapid version flipping and prefetch never
    // download the same URL twice, and a load survives the caller's
    // recomposition/cancellation so the result still lands in the cache.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = HashMap<String, Deferred<Art?>>()

    /**
     * Memory → app temp disk (≤3 days by wall clock) → network. A successful
     * download refreshes the file; a failed one falls back to a stale file so
     * the header still shows something offline.
     */
    suspend fun load(context: Context, url: String): Art? {
        cache.get(url)?.let { return it }
        return loadTask(context.applicationContext, url).await()
    }

    /** Warm memory + disk for [urls] in the background, in parallel. */
    fun prefetch(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        val app = context.applicationContext
        for (url in urls.distinct()) {
            if (cache.get(url) == null) {
                ioScope.launch { loadTask(app, url).await() }
            }
        }
    }

    private fun loadTask(context: Context, url: String): Deferred<Art?> =
        synchronized(inflight) {
            inflight.getOrPut(url) {
                ioScope.async {
                    try {
                        ensureCleanupScheduled(context)
                        doLoad(context, url)
                    } finally {
                        synchronized(inflight) { inflight.remove(url) }
                    }
                }
            }
        }

    private fun doLoad(context: Context, url: String): Art? {
        cache.get(url)?.let { return it }
        val file = diskCacheFile(context, url)
        val hasFile = file.isFile && file.length() > 0
        val fresh = hasFile &&
            System.currentTimeMillis() - file.lastModified() <= CACHE_TTL_MS
        // Serve a cached file immediately (even if older than TTL) so the
        // masthead is not blocked on a full GitHub/mirror download.
        if (hasFile) {
            decodeCover(file)?.let { art ->
                cache.put(url, art)
                if (!fresh) {
                    ioScope.launch { refreshCover(context, url, file) }
                }
                return art
            }
            file.delete()
        }
        downloadToFile(context, url, file)
        if (!file.isFile || file.length() == 0L) return null
        val art = decodeCover(file) ?: run {
            file.delete()
            return null
        }
        cache.put(url, art)
        return art
    }

    private fun decodeCover(file: File): Art? {
        val sharp = runCatching { decodeDownsampled(file) }.getOrNull() ?: return null
        return Art(sharp, AmbientBitmapBlur.create(sharp, maxEdgePx = 96, radius = 20))
    }

    /** Background revalidate — failure keeps the stale file already on screen. */
    private fun refreshCover(context: Context, url: String, dest: File) {
        downloadToFile(context, url, dest)
        if (!dest.isFile || dest.length() == 0L) return
        decodeCover(dest)?.let { cache.put(url, it) }
    }

    /** Private app files under filesDir/tmp — not Android's clearable cacheDir. */
    private fun coverDir(context: Context): File =
        File(context.filesDir, DISK_DIR).apply { mkdirs() }

    private fun diskCacheFile(context: Context, url: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(coverDir(context), "$key.img")
    }

    /**
     * One process-wide sweeper: drop covers whose lastModified is older than
     * 3 days vs [System.currentTimeMillis], then sleep and repeat.
     */
    private fun ensureCleanupScheduled(context: Context) {
        if (cleanupStarted) return
        synchronized(this) {
            if (cleanupStarted) return
            cleanupStarted = true
        }
        val app = context.applicationContext
        ioScope.launch {
            migrateFromSystemCache(app)
            while (true) {
                pruneExpiredCovers(app)
                delay(CLEANUP_INTERVAL_MS)
            }
        }
    }

    /** One-shot move from the old cacheDir location (OS-evictable). */
    private fun migrateFromSystemCache(context: Context) {
        val legacy = File(context.cacheDir, "release_covers")
        if (!legacy.isDirectory) return
        val destDir = coverDir(context)
        legacy.listFiles()?.forEach { src ->
            if (!src.isFile || !src.name.endsWith(".img")) return@forEach
            val dest = File(destDir, src.name)
            if (!dest.exists()) {
                runCatching { src.copyTo(dest, overwrite = false) }
            }
            src.delete()
        }
        legacy.delete()
    }

    /** Delete covers (and stray .tmp) older than [CACHE_TTL_MS] by wall clock. */
    private fun pruneExpiredCovers(context: Context) {
        val now = System.currentTimeMillis()
        val dir = coverDir(context)
        val files = dir.listFiles() ?: return
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            val age = now - file.lastModified()
            val expired = age > CACHE_TTL_MS ||
                (file.name.endsWith(".tmp") && age > 60_000L)
            if (expired && file.delete()) removed++
        }
        if (removed > 0) {
            Log.d(TAG, "Pruned $removed expired cover file(s) from $DISK_DIR")
        }
    }

    /**
     * zh/ru: race all mirrors on the first miss (first HTTP 200 wins + sticks),
     * then sticky sequential for later covers. Others: official GitHub only.
     * Matches [com.example.ava.mods.ModManager] proxy race behaviour.
     */
    private fun downloadToFile(context: Context, url: String, dest: File) {
        val direct = GithubProxyUrls.toDirectUrl(url)
        val preferProxy = GithubProxyUrls.shouldPreferProxy(context)
        if (!GithubProxyUrls.isGithubHosted(direct)) {
            copyUrlToFile(direct, dest)
            return
        }

        if (preferProxy && preferredProxyPrefix == null) {
            raceProxyConnection(direct)?.let { conn ->
                if (copyConnectionToFile(conn, dest)) return
            }
        }

        for (candidate in GithubProxyUrls.candidates(context, direct, preferredProxyPrefix)) {
            if (copyUrlToFile(candidate, dest)) {
                rememberWinningPrefix(candidate)
                return
            }
            val preferred = preferredProxyPrefix
            if (preferred != null && candidate.startsWith(preferred)) {
                preferredProxyPrefix = null
            }
        }
        // All candidates failed — an existing stale file stays as offline fallback.
    }

    private fun raceProxyConnection(directUrl: String): HttpURLConnection? {
        val done = AtomicBoolean(false)
        val winnerRef = AtomicReference<HttpURLConnection?>(null)
        val allConns = ConcurrentLinkedQueue<HttpURLConnection>()
        val latch = CountDownLatch(1)

        for (prefix in GithubProxyUrls.PROXY_PREFIXES) {
            thread(name = "cover-proxy-race", isDaemon = true) {
                if (done.get()) return@thread
                var conn: HttpURLConnection? = null
                try {
                    conn = openConnection("$prefix$directUrl").also { allConns.add(it) }
                    val code = conn.responseCode
                    if (code in 200..299 && done.compareAndSet(false, true)) {
                        preferredProxyPrefix = prefix
                        winnerRef.set(conn)
                        latch.countDown()
                        Log.d(TAG, "Cover proxy race won by $prefix")
                    } else if (code !in 200..299) {
                        Log.d(TAG, "Cover proxy race HTTP $code from $prefix")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cover proxy race miss $prefix: ${e.message}")
                    if (conn != null) allConns.add(conn)
                }
            }
        }

        latch.await((CONNECT_TIMEOUT_MS + 2_000).toLong(), TimeUnit.MILLISECONDS)
        val winner = winnerRef.get()
        for (conn in allConns) {
            if (conn !== winner) {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        if (winner == null) {
            Log.w(TAG, "Cover proxy race produced no winner")
        }
        return winner
    }

    private fun rememberWinningPrefix(proxiedUrl: String) {
        for (prefix in GithubProxyUrls.PROXY_PREFIXES) {
            if (proxiedUrl.startsWith(prefix)) {
                preferredProxyPrefix = prefix
                return
            }
        }
    }

    private fun copyUrlToFile(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(url)
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Cover fetch HTTP $code: $url")
                false
            } else {
                copyConnectionToFile(conn, dest).also { conn = null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cover fetch failed: $url (${e.message})")
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyConnectionToFile(connection: HttpURLConnection, dest: File): Boolean {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        return try {
            BufferedInputStream(connection.inputStream).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            require(tmp.length() > 0) { "empty download" }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cover body copy failed (${e.message})")
            tmp.delete()
            false
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Two-pass decode (bounds → inSampleSize), same approach as VinylCoverService. */
    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SHARP_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return file.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Ava-ReleaseCover")
        }
}
