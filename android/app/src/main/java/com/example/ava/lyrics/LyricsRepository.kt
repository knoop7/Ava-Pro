package com.example.ava.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest

object LyricsRepository {
    private const val TAG = "LyricsRepository"
    private val lrclibClient = LrclibLyricsClient()
    private val qqClient = QqMusicLyricsClient()
    private val amllClient = AmllLyricsClient()
    private val cache = mutableMapOf<String, CachedLyrics>()
    private val wordCache = mutableMapOf<String, CachedLyrics>()
    /** Keys that already rematched for a duration bucket but still do not fit. */
    private val acceptedMismatchKeys = mutableSetOf<String>()
    private val mutex = Mutex()

    private data class CachedLyrics(
        val lines: List<LrcLine>,
        val source: LyricSource,
    )

    private data class RawLyricsPick(
        val raw: String,
        val source: LyricSource,
    )

    /**
     * Load timed lyrics for [title] / optional [artist] / [album].
     *
     * When [preferWordSync] is true: AMLL word-sync first; on miss try Music
     * Assistant synced LRC (when Mass API is connected); then LRCLIB∥QQ.
     * Mass API off → skip MA silently (QQ/LRCLIB remain the offline path).
     *
     * Line-level (no word-sync): MA first when connected, then LRCLIB∥QQ.
     * QQ stays behind MA so provider lyrics win over search scrapes.
     */
    suspend fun load(
        title: String,
        artist: String = "",
        durationMs: Long = 0L,
        album: String = "",
        /** When true, try AMLL word-sync TTML (QQ mid) before line-level LRCLIB∥QQ. */
        preferWordSync: Boolean = false,
        /** Bypass word-sync cache — re-hit network (karaoke on / lyrics wall opened). */
        forceWordSync: Boolean = false,
        /**
         * After a word-sync miss, return cached line-level lyrics without LRCLIB∥QQ
         * race — keeps lyrics that were already on screen during upgrade retries.
         */
        preserveLineCache: Boolean = false,
        context: Context? = null,
    ): LyricsLoadResult {
        val key = cacheKey(title, artist, album)
        if (key.isBlank()) return LyricsLoadResult(emptyList(), null)
        // Hard no-throw contract: callers run inside Compose LaunchedEffect,
        // where any escaped exception kills the whole app.
        return try {
            if (preferWordSync) {
                if (!forceWordSync) {
                    peekWordCache(key, durationMs)?.let { return it }
                }
                coroutineScope {
                    // Speculative line-level (MA → LRCLIB∥QQ) races AMLL from t=0
                    // so an AMLL miss costs little extra. Skip when line cache is fresh.
                    val lineDef =
                        if (!preserveLineCache && !hasFreshLineCache(key, durationMs)) {
                            async {
                                loadLineLevel(
                                    title, artist, durationMs, album, key,
                                    context = context,
                                )
                            }
                        } else {
                            null
                        }
                    val word = fetchWordSyncedNetwork(title, artist, durationMs, album, key, context)
                    if (word != null) {
                        lineDef?.cancel()
                        return@coroutineScope word
                    }
                    if (preserveLineCache) {
                        lineDef?.cancel()
                        return@coroutineScope loadLineLevel(
                            title, artist, durationMs, album, key,
                            allowNetwork = false,
                            context = context,
                        )
                    }
                    val fromLine = try {
                        lineDef?.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Prefetched line-level await error for $key", e)
                        null
                    }
                    fromLine?.takeIf { it.lines.isNotEmpty() }
                        ?: loadLineLevel(
                            title, artist, durationMs, album, key,
                            context = context,
                        )
                }
            } else {
                loadLineLevel(title, artist, durationMs, album, key, context = context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics load failed for $key", e)
            LyricsLoadResult(emptyList(), null)
        }
    }

    /**
     * Best-effort in-memory hit for instant UI paint (FAB expand / remount).
     * Uses [Mutex.tryLock] so the Compose frame never blocks on a writer.
     */
    fun peekCached(
        title: String,
        artist: String = "",
        album: String = "",
        durationMs: Long = 0L,
        preferWordSync: Boolean = false,
    ): LyricsLoadResult? {
        val key = cacheKey(title, artist, album)
        if (key.isBlank()) return null
        if (!mutex.tryLock()) return null
        try {
            if (preferWordSync) {
                wordCache[wordCacheKey(key)]?.takeIf { it.lines.isNotEmpty() }?.let { cached ->
                    val mismatch = mismatchKey(wordCacheKey(key), durationMs)
                    if (
                        durationMs <= 0L ||
                        LrcParser.fitsTrackDuration(cached.lines, durationMs) ||
                        mismatch in acceptedMismatchKeys
                    ) {
                        return LyricsLoadResult(cached.lines, cached.source)
                    }
                }
            }
            cache[key]?.takeIf { it.lines.isNotEmpty() }?.let { cached ->
                val mismatch = mismatchKey(key, durationMs)
                if (
                    durationMs <= 0L ||
                    LrcParser.fitsTrackDuration(cached.lines, durationMs) ||
                    mismatch in acceptedMismatchKeys
                ) {
                    return LyricsLoadResult(cached.lines, cached.source)
                }
            }
            val titleOnly = cacheKey(title, "", "")
            if (titleOnly != key) {
                cache[titleOnly]?.takeIf { it.lines.isNotEmpty() }?.let {
                    return LyricsLoadResult(it.lines, it.source)
                }
            }
        } finally {
            mutex.unlock()
        }
        return null
    }

    /**
     * Warm memory (and MA disk when applicable) while the FAB is collapsed so
     * expanding the full player can paint without waiting on the network.
     */
    suspend fun prefetch(
        title: String,
        artist: String = "",
        durationMs: Long = 0L,
        album: String = "",
        preferWordSync: Boolean = false,
        context: Context? = null,
    ) {
        if (title.isBlank()) return
        if (peekCached(title, artist, album, durationMs, preferWordSync)?.lines?.isNotEmpty() == true) {
            return
        }
        load(
            title = title,
            artist = artist,
            durationMs = durationMs,
            album = album,
            preferWordSync = preferWordSync,
            forceWordSync = false,
            preserveLineCache = false,
            context = context,
        )
    }

    /**
     * When Mass API is connected: pull synced LRC for prev/next [radius] queue
     * tracks into memory + [context.cacheDir]/[MASS_DISK_DIR]. No-op offline.
     */
    suspend fun prefetchMassNeighborLyrics(context: Context, radius: Int = 2) {
        val manager = com.example.ava.massapi.MassApiManager.get() ?: return
        val neighbors = manager.neighborQueueItems(radius)
        if (neighbors.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (item in neighbors) {
                val title = item.title.trim()
                if (title.isEmpty()) continue
                val durationMs = (item.durationSec * 1000.0).toLong().coerceAtLeast(0L)
                val key = cacheKey(title, item.artist, "")
                if (key.isBlank()) continue
                if (hasFreshLineCache(key, durationMs)) continue
                val fromDisk = hydrateMassDiskIntoMemory(context, key, durationMs)
                if (fromDisk != null) continue
                if (item.mediaItemId.isBlank() || item.provider.isBlank()) continue
                val raw = try {
                    manager.fetchSyncedLrcForItem(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Neighbor MA lyrics fetch failed for $key", e)
                    null
                } ?: continue
                val lines = usableLinesOrNull(raw) ?: continue
                putLineCache(key, lines, LyricSource.MASS_API, durationMs)
                writeMassDiskLrc(context, key, raw)
                Log.i(TAG, "Prefetched MA neighbor lyrics for $key (${lines.size} lines)")
            }
            trimMassDiskDir(context)
        }
    }

    private suspend fun peekWordCache(key: String, durationMs: Long): LyricsLoadResult? {
        val wordKey = wordCacheKey(key)
        val mismatchKey = mismatchKey(wordKey, durationMs)
        mutex.withLock {
            wordCache[wordKey]?.let { cached ->
                if (cached.lines.isEmpty()) {
                    wordCache.remove(wordKey)
                } else if (
                    durationMs <= 0L ||
                    LrcParser.fitsTrackDuration(cached.lines, durationMs) ||
                    mismatchKey in acceptedMismatchKeys
                ) {
                    return LyricsLoadResult(cached.lines, cached.source)
                }
            }
        }
        return null
    }

    /** True when the line-level cache would already satisfy this track (no refetch needed). */
    private suspend fun hasFreshLineCache(key: String, durationMs: Long): Boolean {
        val mismatchKey = mismatchKey(key, durationMs)
        mutex.withLock {
            val cached = cache[key] ?: return false
            return cached.lines.isNotEmpty() && (
                durationMs <= 0L ||
                    LrcParser.fitsTrackDuration(cached.lines, durationMs) ||
                    mismatchKey in acceptedMismatchKeys
                )
        }
    }

    private suspend fun fetchWordSyncedNetwork(
        title: String,
        artist: String,
        durationMs: Long,
        album: String,
        key: String,
        context: Context? = null,
    ): LyricsLoadResult? {
        val wordKey = wordCacheKey(key)
        val mismatchKey = mismatchKey(wordKey, durationMs)
        val mid = try {
            withTimeout(QQ_MID_TIMEOUT_MS) {
                qqClient.resolveBestMid(title, artist, durationMs, album)
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "QQ mid resolve timed out for word-sync $key")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "QQ mid resolve error for word-sync $key", e)
            null
        } ?: return null

        val lines = try {
            withTimeout(AMLL_TIMEOUT_MS) {
                amllClient.fetchWordSyncedByQqMid(mid, durationMs, context)
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "AMLL timed out for qq mid=$mid ($key)")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AMLL error for qq mid=$mid ($key)", e)
            null
        }
        if (lines == null) {
            Log.i(TAG, "Word-sync miss for $key (qq mid=$mid); falling back to line-level")
            return null
        }

        mutex.withLock {
            wordCache[wordKey] = CachedLyrics(lines, LyricSource.AMLL)
            trimWordCacheLocked()
            if (durationMs > 0L && !LrcParser.fitsTrackDuration(lines, durationMs)) {
                acceptedMismatchKeys.add(mismatchKey)
                if (acceptedMismatchKeys.size > 64) {
                    acceptedMismatchKeys.remove(acceptedMismatchKeys.first())
                }
            }
        }
        return LyricsLoadResult(lines, LyricSource.AMLL)
    }

    private suspend fun loadLineLevel(
        title: String,
        artist: String,
        durationMs: Long,
        album: String,
        key: String,
        allowNetwork: Boolean = true,
        context: Context? = null,
    ): LyricsLoadResult {
        val titleOnlyKey = cacheKey(title, "", "")
        val mismatchKey = mismatchKey(key, durationMs)

        var lastGood: List<LrcLine>? = null
        var lastGoodSource: LyricSource? = null
        mutex.withLock {
            cache[key]?.let { cached ->
                if (cached.lines.isEmpty()) {
                    cache.remove(key)
                } else if (
                    durationMs <= 0L ||
                    LrcParser.fitsTrackDuration(cached.lines, durationMs) ||
                    mismatchKey in acceptedMismatchKeys
                ) {
                    return LyricsLoadResult(cached.lines, cached.source)
                } else {
                    Log.i(
                        TAG,
                        "Cached lyrics fail duration check for $key " +
                            "(track=${durationMs}ms last=${cached.lines.maxOf { it.timeMs }}ms); rematch",
                    )
                    lastGood = cached.lines
                    lastGoodSource = cached.source
                }
            }
            if (lastGood == null && titleOnlyKey != key) {
                cache[titleOnlyKey]?.takeIf { it.lines.isNotEmpty() }?.let {
                    lastGood = it.lines
                    lastGoodSource = it.source
                }
            }
        }

        // FAB remount / skip: MA temp-disk hit before another network round-trip.
        if (context != null) {
            hydrateMassDiskIntoMemory(context, key, durationMs)?.let { return it }
            if (titleOnlyKey != key) {
                hydrateMassDiskIntoMemory(context, titleOnlyKey, durationMs)?.let { return it }
            }
        }

        if (!allowNetwork) {
            return LyricsLoadResult(lastGood.orEmpty(), lastGoodSource)
        }

        // Mass API synced LRC before QQ/LRCLIB search (massdroid path). Skip when offline.
        val fromMass = fetchMassApiSynced(title, artist, durationMs, key, context)
        if (fromMass != null) {
            return fromMass
        }

        val pick = fetchRawParallel(title, artist, durationMs, album, key)
        if (pick == null) {
            return LyricsLoadResult(lastGood.orEmpty(), lastGoodSource)
        }
        // Keep blank timed stamps — they mark interludes for karaoke span.
        val parsed = LrcParser.parse(pick.raw)
        val lines = when {
            parsed.none { !it.isInterlude } -> emptyList()
            LrcParser.isInstrumentalOnly(parsed) -> emptyList()
            else -> parsed
        }
        if (lines.isEmpty()) {
            Log.i(TAG, "No usable timed lyrics for $key after LRCLIB∥QQ")
            return LyricsLoadResult(lastGood.orEmpty(), lastGoodSource)
        }

        putLineCache(key, lines, pick.source, durationMs)
        return LyricsLoadResult(lines, pick.source)
    }

    /**
     * Music Assistant lyrics when the side-channel is connected. Never throws;
     * never blocks the QQ/LRCLIB path longer than [MASS_API_TIMEOUT_MS].
     */
    private suspend fun fetchMassApiSynced(
        title: String,
        artist: String,
        durationMs: Long,
        key: String,
        context: Context? = null,
    ): LyricsLoadResult? {
        val manager = com.example.ava.massapi.MassApiManager.get() ?: return null
        val raw = try {
            withTimeout(MASS_API_TIMEOUT_MS) {
                manager.fetchSyncedLrcForTrack(title, artist)
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Mass API lyrics timed out for $key")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Mass API lyrics error for $key", e)
            null
        } ?: return null

        val lines = usableLinesOrNull(raw) ?: run {
            Log.i(TAG, "Mass API returned no usable timed lyrics for $key")
            return null
        }
        if (durationMs > 0L && !LrcParser.fitsTrackDuration(lines, durationMs)) {
            Log.i(
                TAG,
                "Mass API lyrics duration mismatch for $key " +
                    "(track=${durationMs}ms last=${LrcParser.lastTimedMs(lines)}ms); still accepting",
            )
        }
        putLineCache(key, lines, LyricSource.MASS_API, durationMs)
        if (context != null) {
            withContext(Dispatchers.IO) {
                writeMassDiskLrc(context, key, raw)
                trimMassDiskDir(context)
            }
        }
        Log.i(TAG, "Mass API lyrics hit for $key (${lines.size} lines)")
        return LyricsLoadResult(lines, LyricSource.MASS_API)
    }

    /**
     * Fetch LRCLIB + QQ in parallel, then pick by duration closeness.
     * Never cancel the slower source on a loose first-fit — that preferred
     * network speed over identity.
     */
    private suspend fun fetchRawParallel(
        title: String,
        artist: String,
        durationMs: Long,
        album: String,
        key: String,
    ): RawLyricsPick? = coroutineScope {
        val lrclibDef = async { fetchLrclib(title, artist, durationMs, album, key) }
        val qqDef = async { fetchQq(title, artist, durationMs, album, key) }

        val fromLrclib = try {
            lrclibDef.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB await error for $key", e)
            null
        }
        val fromQq = try {
            qqDef.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "QQ await error for $key", e)
            null
        }

        if (fromQq == null && fromLrclib == null) {
            Log.i(TAG, "Both sources miss for $key; retrying both once")
            delay(FETCH_RETRY_DELAY_MS)
            val retryLrclib = async { fetchLrclib(title, artist, durationMs, album, key) }
            val retryQq = async { fetchQq(title, artist, durationMs, album, key) }
            val retriedLrclib = try {
                retryLrclib.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val retriedQq = try {
                retryQq.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            return@coroutineScope pickBestRaw(retriedLrclib, retriedQq, durationMs, key)
        }

        pickBestRaw(fromLrclib, fromQq, durationMs, key)
    }

    private fun pickBestRaw(
        fromLrclib: String?,
        fromQq: String?,
        durationMs: Long,
        key: String,
    ): RawLyricsPick? {
        val lrclibLines = fromLrclib?.let { usableLinesOrNull(it) }
        val qqLines = fromQq?.let { usableLinesOrNull(it) }
        val lrclibFits = lrclibLines != null &&
            (durationMs <= 0L || LrcParser.fitsTrackDuration(lrclibLines, durationMs))
        val qqFits = qqLines != null &&
            (durationMs <= 0L || LrcParser.fitsTrackDuration(qqLines, durationMs))

        if (qqFits && lrclibFits && lrclibLines != null && qqLines != null && durationMs > 0L) {
            val qqDelta = kotlin.math.abs(LrcParser.lastTimedMs(qqLines) - durationMs)
            val lrclibDelta = kotlin.math.abs(LrcParser.lastTimedMs(lrclibLines) - durationMs)
            // LRCLIB is the preferred line-level source; QQ must beat it clearly.
            return if (qqDelta + DURATION_TIE_PREFER_MS < lrclibDelta) {
                Log.i(
                    TAG,
                    "Lyrics from QQ (tighter duration ${qqDelta}ms < ${lrclibDelta}ms) for $key",
                )
                fromQq?.let { RawLyricsPick(it, LyricSource.QQ) }
            } else {
                Log.i(
                    TAG,
                    "Lyrics from LRCLIB (duration delta ${lrclibDelta}ms vs QQ ${qqDelta}ms) for $key",
                )
                fromLrclib?.let { RawLyricsPick(it, LyricSource.LRCLIB) }
            }
        }

        return when {
            lrclibFits -> {
                Log.i(TAG, "Lyrics from LRCLIB for $key")
                fromLrclib?.let { RawLyricsPick(it, LyricSource.LRCLIB) }
            }
            qqFits -> {
                Log.i(TAG, "Lyrics from QQ for $key")
                fromQq?.let { RawLyricsPick(it, LyricSource.QQ) }
            }
            durationMs <= 0L && lrclibLines != null -> {
                Log.i(TAG, "Lyrics from LRCLIB (soft, no duration) for $key")
                fromLrclib?.let { RawLyricsPick(it, LyricSource.LRCLIB) }
            }
            durationMs <= 0L && qqLines != null -> {
                Log.i(TAG, "Lyrics from QQ (soft, no duration) for $key")
                fromQq?.let { RawLyricsPick(it, LyricSource.QQ) }
            }
            else -> {
                Log.i(TAG, "No lyrics from LRCLIB∥QQ for $key")
                null
            }
        }
    }

    private suspend fun fetchLrclib(
        title: String,
        artist: String,
        durationMs: Long,
        album: String,
        key: String,
    ): String? {
        return try {
            withTimeout(LRCLIB_TIMEOUT_MS) {
                lrclibClient.fetchLyrics(title, artist, durationMs, album)
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "LRCLIB timed out after ${LRCLIB_TIMEOUT_MS}ms for $key")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB error for $key", e)
            null
        }
    }

    private suspend fun fetchQq(
        title: String,
        artist: String,
        durationMs: Long,
        album: String,
        key: String,
    ): String? {
        return try {
            withTimeout(QQ_TIMEOUT_MS) {
                qqClient.fetchLyrics(title, artist, durationMs, album)
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "QQ timed out after ${QQ_TIMEOUT_MS}ms for $key")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "QQ error for $key", e)
            null
        }
    }

    private fun usableLinesOrNull(raw: String): List<LrcLine>? {
        val lines = LrcParser.parse(raw)
        if (lines.none { !it.isInterlude }) return null
        if (LrcParser.isInstrumentalOnly(lines)) return null
        return lines
    }

    suspend fun clear() {
        mutex.withLock {
            cache.clear()
            wordCache.clear()
            acceptedMismatchKeys.clear()
        }
    }

    private suspend fun putLineCache(
        key: String,
        lines: List<LrcLine>,
        source: LyricSource,
        durationMs: Long,
    ) {
        val mismatchKey = mismatchKey(key, durationMs)
        mutex.withLock {
            cache[key] = CachedLyrics(lines, source)
            if (durationMs > 0L && !LrcParser.fitsTrackDuration(lines, durationMs)) {
                acceptedMismatchKeys.add(mismatchKey)
                if (acceptedMismatchKeys.size > 64) {
                    acceptedMismatchKeys.remove(acceptedMismatchKeys.first())
                }
            }
            while (cache.size > MEMORY_LINE_CACHE_MAX) {
                cache.remove(cache.keys.first())
            }
        }
    }

    private suspend fun hydrateMassDiskIntoMemory(
        context: Context,
        key: String,
        durationMs: Long,
    ): LyricsLoadResult? {
        val raw = withContext(Dispatchers.IO) { readMassDiskLrc(context, key) } ?: return null
        val lines = usableLinesOrNull(raw) ?: return null
        putLineCache(key, lines, LyricSource.MASS_API, durationMs)
        Log.i(TAG, "MA disk lyrics hit for $key (${lines.size} lines)")
        return LyricsLoadResult(lines, LyricSource.MASS_API)
    }

    private fun massDiskDir(context: Context): File =
        File(context.cacheDir, MASS_DISK_DIR).also { it.mkdirs() }

    private fun massDiskFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
            .take(40)
        return File(massDiskDir(context), "$digest.lrc")
    }

    private fun readMassDiskLrc(context: Context, key: String): String? {
        return try {
            val file = massDiskFile(context, key)
            if (!file.isFile || file.length() <= 0L) return null
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "MA disk read failed for $key", e)
            null
        }
    }

    private fun writeMassDiskLrc(context: Context, key: String, raw: String) {
        if (raw.isBlank()) return
        try {
            massDiskFile(context, key).writeText(raw, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "MA disk write failed for $key", e)
        }
    }

    /** Keep the temp dir bounded — drop oldest files beyond [MASS_DISK_MAX_FILES]. */
    private fun trimMassDiskDir(context: Context) {
        try {
            val files = massDiskDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".lrc") }
                ?: return
            if (files.size <= MASS_DISK_MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MASS_DISK_MAX_FILES)
                .forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "MA disk trim failed", e)
        }
    }

    private fun wordCacheKey(lineKey: String): String = "word|$lineKey"

    private fun trimWordCacheLocked() {
        while (wordCache.size > 24) {
            val oldest = wordCache.keys.first()
            wordCache.remove(oldest)
        }
    }

    private fun cacheKey(title: String, artist: String, album: String): String {
        val t = title.trim().lowercase()
        if (t.isBlank()) return ""
        val a = artist.trim().lowercase()
        val al = album.trim().lowercase()
        return if (al.isEmpty()) "$t|$a" else "$t|$a|$al"
    }

    /** ~5s buckets so tiny duration jitter does not re-trigger rematch. */
    private fun mismatchKey(cacheKey: String, durationMs: Long): String =
        "$cacheKey@${(durationMs / 5_000L).coerceAtLeast(0L)}"

    private const val FETCH_RETRY_DELAY_MS = 450L
    /**
     * When both sources fit, QQ must beat LRCLIB by at least this much in
     * |last−duration| to overturn the LRCLIB preference.
     */
    private const val DURATION_TIE_PREFER_MS = 2_000L
    private const val LRCLIB_TIMEOUT_MS = 3_500L
    private const val QQ_TIMEOUT_MS = 8_000L
    private const val QQ_MID_TIMEOUT_MS = 6_000L
    private const val AMLL_TIMEOUT_MS = 10_000L
    /** massdroid uses 6s; keep the same budget so QQ/LRCLIB still run soon after a miss. */
    private const val MASS_API_TIMEOUT_MS = 6_000L
    /** Current + neighbors (±2) + a little headroom for album rematch keys. */
    private const val MEMORY_LINE_CACHE_MAX = 48
    private const val MASS_DISK_DIR = "mass_lyrics_prefetch"
    private const val MASS_DISK_MAX_FILES = 24
}
