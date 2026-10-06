package com.example.ava.lyrics

import android.util.Log
import com.example.ava.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * LRCLIB lyrics lookup — [search](https://lrclib.net/docs) + scored match.
 *
 * Prefer [syncedLyrics]; skip instrumentals. Album is optional (Sendspin/HA
 * often omit it) and scoring-only — search uses track/artist, not album_name.
 */
class LrclibLyricsClient(
    private val httpClient: OkHttpClient = sharedClient,
) {
    suspend fun fetchLyrics(
        title: String,
        artist: String = "",
        durationMs: Long = 0L,
        album: String = "",
    ): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        try {
            val trimmedTitle = title.trim()
            val trimmedArtist = artist.trim()
            val trimmedAlbum = album.trim()
            // Artist assists precision; if that returns nothing usable, retry
            // title-only (stale HA artist must not hard-fail the lookup).
            // Search with bare title (no Live/Remix parens) for better recall;
            // scoring still uses the raw title so version penalties apply.
            // Album is scoring-only — do not pass album_name to /api/search.
            val searchTitle = QqLyricSongMatcher.bareTitle(trimmedTitle).ifBlank { trimmedTitle }
            var hits = search(searchTitle, trimmedArtist)
            var asSongs = hits.mapNotNull { hit -> toScorableSong(hit) }
            var ranked = QqLyricSongMatcher.rankCandidates(
                asSongs, title, artist, durationMs, album = trimmedAlbum,
            )
            if (ranked.isEmpty() && trimmedArtist.isNotBlank()) {
                Log.i(TAG, "LRCLIB artist-assisted miss for '$title' / '$artist'; retry title-only search")
                hits = search(searchTitle, artist = "")
                asSongs = hits.mapNotNull { hit -> toScorableSong(hit) }
                ranked = QqLyricSongMatcher.rankCandidates(
                    asSongs,
                    title,
                    artist,
                    durationMs,
                    album = trimmedAlbum,
                    artistMatchMode = QqLyricSongMatcher.ArtistMatchMode.RELAXED,
                )
            }
            if (asSongs.isEmpty()) {
                Log.i(TAG, "No synced LRCLIB hits for '$title' / '$artist'")
                return@withContext null
            }
            if (ranked.isEmpty()) {
                Log.i(TAG, "No confident LRCLIB match for '$title' / '$artist'")
                return@withContext null
            }

            var bestLrc: String? = null
            var bestDelta = Long.MAX_VALUE
            var fallbackLrc: String? = null
            for (song in ranked.take(MAX_LYRIC_CANDIDATES)) {
                ensureActive()
                val lrc = song.optString(KEY_SYNCED).takeIf { it.isNotBlank() } ?: continue
                val lines = LrcParser.parse(lrc)
                if (lines.none { !it.isInterlude } || LrcParser.isInstrumentalOnly(lines)) continue
                if (fallbackLrc == null) fallbackLrc = lrc
                if (durationMs > 0L && !LrcParser.fitsTrackDuration(lines, durationMs)) {
                    Log.i(
                        TAG,
                        "LRCLIB id=${song.optString("mid")} rejected by duration fit " +
                            "(track=${durationMs}ms last=${LrcParser.lastTimedMs(lines)}ms); trying next",
                    )
                    continue
                }
                val last = LrcParser.lastTimedMs(lines)
                val delta = if (durationMs > 0L) kotlin.math.abs(last - durationMs) else 0L
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestLrc = lrc
                    Log.i(
                        TAG,
                        "LRCLIB id=${song.optString("mid")} candidate delta=${delta}ms " +
                            "(track=${durationMs}ms last=${last}ms)",
                    )
                }
                if (durationMs <= 0L || delta <= 4_000L) {
                    return@withContext lrc
                }
            }
            if (bestLrc != null) return@withContext bestLrc
            if (durationMs > 0L) {
                Log.i(TAG, "No LRCLIB candidate fits duration for '$title' / '$artist'")
                return@withContext null
            }
            fallbackLrc
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB lyrics fetch failed for $title / $artist", e)
            null
        }
    }

    private suspend fun search(title: String, artist: String): List<JSONObject> {
        val urlBuilder = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("search")
            .addQueryParameter("track_name", title)
        if (artist.isNotBlank()) {
            urlBuilder.addQueryParameter("artist_name", artist)
        }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = httpClient.newCall(request)
        // Parent withTimeout(5s) cancels this coroutine — abort the HTTP call too.
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            coroutineContext.ensureActive()
            if (response.code == 429) {
                val retryAfter = response.header("Retry-After")
                Log.w(TAG, "LRCLIB rate limited (Retry-After=$retryAfter)")
                return emptyList()
            }
            if (!response.isSuccessful) return emptyList()
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            if (arr.length() == 0) return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    /**
     * Map an LRCLIB record into the shape [QqLyricSongMatcher] expects, keeping
     * [KEY_SYNCED] for the subsequent lyric pick.
     */
    private fun toScorableSong(hit: JSONObject): JSONObject? {
        if (hit.optBoolean("instrumental", false)) return null
        val synced = hit.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: return null
        val trackName = hit.optString("trackName").ifBlank { hit.optString("name") }
        if (trackName.isBlank()) return null
        val artistName = hit.optString("artistName")
        val albumName = hit.optString("albumName")
        val durationSec = hit.optDouble("duration", 0.0).toInt().coerceAtLeast(0)
        val id = hit.optLong("id", 0L).takeIf { it > 0L }?.toString()
            ?: "$trackName|$artistName|$durationSec"
        return JSONObject().apply {
            put("mid", id)
            put("name", trackName)
            put("interval", durationSec)
            put(
                "singer",
                JSONArray().apply {
                    // "A & B" / "A, B" → separate singers so dual English credits score.
                    val parts = QqLyricSongMatcher.artistTokens(artistName)
                    if (parts.isEmpty() && artistName.isNotBlank()) {
                        put(JSONObject().put("name", artistName))
                    } else {
                        for (name in parts) {
                            put(JSONObject().put("name", name))
                        }
                    }
                },
            )
            put("album", JSONObject().put("name", albumName))
            put(KEY_SYNCED, synced)
        }
    }

    companion object {
        private const val TAG = "LrclibLyricsClient"
        private const val BASE_URL = "https://lrclib.net/"
        private val USER_AGENT =
            "Ava/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
        private const val KEY_SYNCED = "_syncedLyrics"
        private const val MAX_LYRIC_CANDIDATES = 5

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
