package com.example.ava.lyrics

import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * QQ Music lyrics lookup — search + scored match + lyric fetch.
 *
 * Artist may be blank: title-only search still works for distinctive titles
 * (common for English tracks). Matching uses scored ranking instead of
 * loose OR / first-result fallback.
 */
class QqMusicLyricsClient(
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
            val ranked = rankMatchedSongs(title, artist, durationMs, album)
            if (ranked.isEmpty()) {
                Log.i(TAG, "No confident lyric match for '$title' / '$artist' / '$album'")
                return@withContext null
            }
            // Among duration-fitting candidates, prefer closest lyric end — not
            // only first mid that clears the loose span gate.
            var bestLrc: String? = null
            var bestDelta = Long.MAX_VALUE
            var fallbackLrc: String? = null
            for (song in ranked.take(MAX_LYRIC_CANDIDATES)) {
                val mid = song.optString("mid").takeIf { it.isNotBlank() } ?: continue
                val lrc = fetchLyricsForMid(mid) ?: continue
                val lines = LrcParser.parse(lrc)
                if (lines.none { !it.isInterlude } || LrcParser.isInstrumentalOnly(lines)) continue
                if (fallbackLrc == null) fallbackLrc = lrc
                if (durationMs > 0L && !LrcParser.fitsTrackDuration(lines, durationMs)) {
                    Log.i(
                        TAG,
                        "Lyric mid=$mid rejected by duration fit " +
                            "(track=${durationMs}ms last=${LrcParser.lastTimedMs(lines)}ms); trying next",
                    )
                    continue
                }
                val last = LrcParser.lastTimedMs(lines)
                val delta = if (durationMs > 0L) {
                    kotlin.math.abs(last - durationMs)
                } else {
                    0L
                }
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestLrc = lrc
                    Log.i(
                        TAG,
                        "Lyric mid=$mid candidate delta=${delta}ms " +
                            "(track=${durationMs}ms last=${last}ms)",
                    )
                }
                // Tight enough — no need to download more mids.
                if (durationMs <= 0L || delta <= TIGHT_LYRIC_DURATION_DELTA_MS) {
                    return@withContext lrc
                }
            }
            if (bestLrc != null) return@withContext bestLrc
            if (durationMs > 0L) {
                Log.i(TAG, "No QQ lyric candidate fits duration for '$title' / '$artist'")
                return@withContext null
            }
            fallbackLrc
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "QQ lyrics fetch failed for $title / $artist", e)
            null
        }
    }

    /**
     * Best QQ [mid] for [title]/[artist]/[album] without downloading LRC —
     * used to probe AMLL TTML. Prefers a mid whose reported interval agrees
     * with [durationMs] so word-sync does not latch onto the wrong cut.
     */
    suspend fun resolveBestMid(
        title: String,
        artist: String = "",
        durationMs: Long = 0L,
        album: String = "",
    ): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        try {
            val ranked = rankMatchedSongs(title, artist, durationMs, album)
            if (ranked.isEmpty()) return@withContext null
            val preferred = ranked.firstOrNull { song ->
                QqLyricSongMatcher.isIntervalPlausible(song, durationMs)
            } ?: ranked.first()
            preferred.optString("mid").takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "QQ mid resolve failed for $title / $artist", e)
            null
        }
    }

    private fun rankMatchedSongs(
        title: String,
        artist: String,
        durationMs: Long,
        album: String = "",
    ): List<JSONObject> {
        val bareTitle = QqLyricSongMatcher.bareTitle(title)
        val trimmedArtist = artist.trim()
        val trimmedAlbum = album.trim()
        // Search without parenthetical version noise; scoring still sees raw title.
        // Album stays out of the search string (it kills QQ recall, e.g. "神选+赫鬼")
        // and only participates in [QqLyricSongMatcher] ranking.
        val searchTitle = bareTitle.ifBlank { title.trim() }
        val query = if (trimmedArtist.isNotBlank()) {
            "$searchTitle $trimmedArtist"
        } else {
            searchTitle
        }
        var songs = searchSongs(query)
        var ranked = QqLyricSongMatcher.rankCandidates(
            songs, title, artist, durationMs, album = trimmedAlbum,
        )
        if (ranked.isEmpty() && trimmedArtist.isNotBlank()) {
            Log.i(TAG, "QQ artist-assisted miss for '$title' / '$artist'; retry title-only search")
            songs = searchSongs(searchTitle)
            ranked = QqLyricSongMatcher.rankCandidates(
                songs,
                title,
                artist,
                durationMs = durationMs,
                album = trimmedAlbum,
                artistMatchMode = QqLyricSongMatcher.ArtistMatchMode.RELAXED,
            )
        }
        return ranked
    }

    private fun searchSongs(query: String): List<JSONObject> {
        val payload = JSONObject().apply {
            put(
                "req_1",
                JSONObject().apply {
                    put("method", "DoSearchForQQMusicDesktop")
                    put("module", "music.search.SearchCgiService")
                    put(
                        "param",
                        JSONObject().apply {
                            put("query", query)
                            put("search_type", 0)
                            put("num_per_page", 10)
                            put("page_num", 1)
                        },
                    )
                },
            )
        }
        val body = postJson(payload) ?: return emptyList()
        val songs = body
            .optJSONObject("req_1")
            ?.optJSONObject("data")
            ?.optJSONObject("body")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return emptyList()
        if (songs.length() == 0) return emptyList()
        return (0 until songs.length()).map { songs.getJSONObject(it) }
    }

    private fun fetchLyricsForMid(songMid: String): String? {
        val payload = JSONObject().apply {
            put(
                "req_1",
                JSONObject().apply {
                    put("method", "GetPlayLyricInfo")
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put(
                        "param",
                        JSONObject().apply {
                            put("songMID", songMid)
                            put("format", "json")
                        },
                    )
                },
            )
        }
        val body = postJson(payload) ?: return null
        val lyricB64 = body
            .optJSONObject("req_1")
            ?.optJSONObject("data")
            ?.optString("lyric")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return String(Base64.decode(lyricB64, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun postJson(payload: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(QQ_API_URL)
            .headers(QQ_HEADERS)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return null
            return JSONObject(text)
        }
    }

    companion object {
        private const val TAG = "QqMusicLyricsClient"
        private const val QQ_API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val QQ_HEADERS = okhttp3.Headers.headersOf(
            "Referer", "https://y.qq.com",
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        )

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        private const val MAX_LYRIC_CANDIDATES = 5
        /** Stop scanning further mids once lyric end is this close to track length. */
        private const val TIGHT_LYRIC_DURATION_DELTA_MS = 4_000L
    }
}

/**
 * Pure scoring matcher for QQ search hits. Safe to unit-test without network.
 */
object QqLyricSongMatcher {
    private val PAREN_CONTENT = Regex("""[\(\[（]([^\)\]）]*)[\)\]）]""")
    private val MULTI_SPACE = Regex("""\s+""")
    private val FEAT_SPLIT = Regex("""\b(?:feat\.?|ft\.?|featuring)\b""", RegexOption.IGNORE_CASE)
    /**
     * Split collaborative credits before normalize (which turns `&` / `,` into spaces).
     * Bare `和` is NOT a splitter — it false-splits names like "平和". Prefer
     * punctuation / spaced joiners (`A 和 B`, `A/B`, `A、B`).
     */
    private val ARTIST_SPLIT = Regex(
        """\s*[/,&|;＋+]\s*|[、，/]|\s+(?:feat\.?|ft\.?|featuring|vs\.?|x|with|and|和)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private data class Penalty(val token: String, val weight: Int)

    private val VERSION_PENALTIES = listOf(
        Penalty("live", 30),
        Penalty("演唱会", 30),
        Penalty("remix", 25),
        Penalty("cover", 35),
        Penalty("翻唱", 35),
        Penalty("karaoke", 40),
        Penalty("伴奏", 40),
        Penalty("acoustic", 15),
        Penalty("instrumental", 40),
        Penalty("纯音乐", 40),
        Penalty("tabata", 40),
        Penalty("sped up", 25),
        Penalty("slowed", 25),
        Penalty("nightcore", 30),
        Penalty("soundtrack", 20),
        Penalty("motion picture", 20),
        Penalty("原唱：", 35),
        // Preview / short-clip cuts — stripParens otherwise makes these look
        // like exact title matches ("排尾后巷 (30秒片段)" → "排尾后巷").
        Penalty("片段", 40),
        Penalty("30秒", 45),
        Penalty("试听", 40),
        Penalty("预览", 40),
        Penalty("preview", 40),
        Penalty("snippet", 40),
    )

    /** Minimum total score to accept a hit (title + artist + duration affinity). */
    private const val MIN_ACCEPT_SCORE = 80
    /** Exact / near-exact title floor (soft accept only when artist is OK). */
    private const val EXACT_TITLE_SCORE = 95
    /**
     * Substring title hits need enough length overlap; short names inside long
     * titles ("love" ⊂ "love story") must not clear the gate alone.
     */
    private const val MIN_TITLE_CONTAIN_RATIO = 0.55

    /** Artist given but unmatched: strong penalty (covers no longer ride exact title). */
    private const val ARTIST_MISS_PENALTY = 35

    /**
     * Relaxed retry without artist hit: needs exact title + tight duration
     * (and album agreement when album is known).
     * Floor accounts for [ARTIST_MISS_PENALTY] already subtracted in [score]
     * (exact title 100 + dur 25 − artist miss 35 ≈ 90).
     */
    private const val RELAXED_NO_ARTIST_MIN_SCORE = 88
    /** RELAXED must earn the top durationAffinity band (≤5s). */
    private const val RELAXED_MIN_DURATION_POINTS = 25
    /** Album given but unmatched: nudge away from same-title other albums. */
    private const val ALBUM_MISS_PENALTY = 18

    enum class ArtistMatchMode {
        STRICT,
        RELAXED,
    }

    fun findBestMatch(
        songs: List<JSONObject>,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        album: String = "",
    ): JSONObject? = rankCandidates(songs, title, artist, durationMs, album = album).firstOrNull()

    /** Title with parentheticals stripped — for search queries only. */
    fun bareTitle(title: String): String =
        MULTI_SPACE.replace(stripParens(title).trim(), " ")

    /**
     * Whether the candidate's reported length is close enough to [durationMs]
     * to trust for AMLL mid selection. Unknown interval → true (cannot reject).
     */
    fun isIntervalPlausible(song: JSONObject, durationMs: Long): Boolean {
        if (durationMs < 30_000L) return true
        val intervalSec = song.optInt("interval", 0)
        if (intervalSec <= 0) return true
        return kotlin.math.abs(intervalSec * 1_000L - durationMs) <= 12_000L
    }

    /**
     * Ranked search hits for lyric fetch + duration rematch.
     * Higher score first; stable by original QQ order on ties.
     *
     * Precision-first: when [artist] is non-blank it must score a hit; bare
     * title containment no longer auto-accepts. Stale artist is handled by
     * callers retrying title-only search with [ArtistMatchMode.RELAXED]
     * (exact title + tight duration; album helps when present).
     */
    fun rankCandidates(
        songs: List<JSONObject>,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        artistMatchMode: ArtistMatchMode = ArtistMatchMode.STRICT,
        album: String = "",
    ): List<JSONObject> {
        if (songs.isEmpty() || title.isBlank()) return emptyList()
        data class Ranked(val score: Int, val index: Int, val song: JSONObject)

        val requireArtist = artist.isNotBlank()
        val queryAlbum = album.trim()
        val ranked = ArrayList<Ranked>(songs.size)
        songs.forEachIndexed { index, song ->
            val singers = singersOf(song)
            val songAlbum = song.optJSONObject("album")?.optString("name").orEmpty()
            val titleHit = titleScore(title, song.optString("name"))
            val artPoints = if (requireArtist) artistScore(artist, singers) else 65
            val albPoints = if (queryAlbum.isNotBlank()) albumScore(queryAlbum, songAlbum) else 0
            if (artistMatchMode == ArtistMatchMode.STRICT && requireArtist && artPoints <= 0) {
                return@forEachIndexed
            }
            val sc = score(song, title, artist, durationMs, queryAlbum)
            val durPts = durationAffinity(song, durationMs)
            val accept = when {
                sc >= MIN_ACCEPT_SCORE && (!requireArtist || artPoints > 0) -> true
                requireArtist &&
                    artPoints >= 50 &&
                    titleHit >= EXACT_TITLE_SCORE &&
                    sc >= MIN_ACCEPT_SCORE - 5 ->
                    true
                // Wrong-artist same-title: only when duration is tight AND
                // (no album known, or album also agrees). Prevents covers.
                artistMatchMode == ArtistMatchMode.RELAXED &&
                    requireArtist &&
                    artPoints <= 0 &&
                    titleHit >= EXACT_TITLE_SCORE &&
                    durPts >= RELAXED_MIN_DURATION_POINTS &&
                    sc >= RELAXED_NO_ARTIST_MIN_SCORE &&
                    (queryAlbum.isBlank() || albPoints >= 40) ->
                    true
                else -> false
            }
            if (accept) ranked.add(Ranked(sc, index, song))
        }
        if (ranked.isEmpty()) return emptyList()
        return ranked
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.index })
            .map { it.song }
            .distinctBy { it.optString("mid") }
    }

    fun score(
        song: JSONObject,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        album: String = "",
    ): Int {
        val songName = song.optString("name")
        val singers = singersOf(song)
        val songAlbum = song.optJSONObject("album")?.optString("name").orEmpty()
        val queryAlbum = album.trim()

        val titleScore = titleScore(title, songName)
        if (titleScore <= 0) return Int.MIN_VALUE / 4

        var total = titleScore
        total += artistScore(artist, singers)
        total += albumScore(queryAlbum, songAlbum)
        if (queryAlbum.isNotBlank() && albumScore(queryAlbum, songAlbum) == 0) {
            total -= ALBUM_MISS_PENALTY
        }
        total -= versionPenalty("$title $artist $queryAlbum", songName, songAlbum, singers)
        total -= parenMismatchPenalty(title, songName)
        // Without artist, prefer singer script that matches the title script
        // (avoids "Hey Jude" → a "孙燕姿" cover when Beatles is also in the list).
        if (artist.isBlank()) {
            total += scriptAffinity(title, singers.firstOrNull().orEmpty())
        }

        // Title-only: prefer exact / near-exact; weak containment alone is not enough.
        if (artist.isBlank() && titleScore < EXACT_TITLE_SCORE && total < MIN_ACCEPT_SCORE) {
            return Int.MIN_VALUE / 4
        }
        // Artist given but unmatched: hard-reject in rankCandidates; keep a
        // ranking penalty here for any direct score() callers.
        if (artist.isNotBlank() && artistScore(artist, singers) == 0) {
            total -= ARTIST_MISS_PENALTY
        }
        total += durationAffinity(song, durationMs)
        return total
    }

    /** Prefer hits whose reported interval is close to the playing track. */
    private fun durationAffinity(song: JSONObject, durationMs: Long): Int {
        if (durationMs < 30_000L) return 0
        val intervalSec = song.optInt("interval", 0)
        if (intervalSec <= 0) return 0
        val delta = kotlin.math.abs(intervalSec * 1_000L - durationMs)
        return when {
            delta <= 5_000L -> 25
            delta <= 12_000L -> 12
            delta <= 20_000L -> -8
            delta <= 35_000L -> -25
            // 30s preview vs ~3min full track, etc.
            delta <= 90_000L -> -50
            else -> -70
        }
    }

    /** Album agreement — important for same-title different-release disambiguation. */
    private fun albumScore(queryAlbum: String, songAlbum: String): Int {
        if (queryAlbum.isBlank() || songAlbum.isBlank()) return 0
        val qa = normalize(stripParens(queryAlbum))
        val sa = normalize(stripParens(songAlbum))
        if (qa.isEmpty() || sa.isEmpty()) return 0
        if (qa == sa) return 50
        if (qa in sa || sa in qa) {
            val ratio = minOf(qa.length, sa.length).toDouble() / maxOf(qa.length, sa.length)
            if (ratio < 0.45) return 0
            return (30 + (20 * ratio)).toInt()
        }
        val qaToks = qa.split(' ').filter { it.length >= 2 }.toSet()
        val saToks = sa.split(' ').filter { it.length >= 2 }.toSet()
        if (qaToks.isEmpty()) return 0
        val overlap = qaToks.count { it in saToks }.toDouble() / qaToks.size
        if (overlap >= 0.7) return (20 + (15 * overlap)).toInt()
        return 0
    }

    /**
     * Soft script affinity when artist is unknown.
     * Latin title + CJK-only singer → cover/localization penalty.
     * CJK title + Latin-only singer → lighter penalty (e.g. "夜曲" piano vs "周杰伦").
     */
    private fun scriptAffinity(title: String, primarySinger: String): Int {
        if (primarySinger.isBlank()) return 0
        val titleLatin = isMostlyLatin(title)
        val titleCjk = isMostlyCjk(title)
        val singerLatin = isMostlyLatin(primarySinger)
        val singerCjk = isMostlyCjk(primarySinger)
        return when {
            titleLatin && singerCjk && !singerLatin -> -25
            titleCjk && singerLatin && !singerCjk -> -12
            else -> 0
        }
    }

    private fun isMostlyLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val latin = letters.count { it.code in 0x0041..0x024F || it.code in 0x1E00..0x1EFF }
        return latin * 2 >= letters.length
    }

    private fun isMostlyCjk(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val cjk = letters.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        return cjk * 2 >= letters.length
    }

    private fun titleScore(queryTitle: String, songName: String): Int {
        val qt = normalize(stripParens(queryTitle))
        val sn = normalize(stripParens(songName))
        if (qt.isEmpty() || sn.isEmpty()) return 0

        val qtRaw = normalize(queryTitle)
        val snRaw = normalize(songName)
        if (qtRaw.isNotEmpty() && qtRaw == snRaw) return 100
        if (qt == sn) return 100

        val qtBase = FEAT_SPLIT.split(qt, limit = 2).first().trim()
        val snBase = FEAT_SPLIT.split(sn, limit = 2).first().trim()
        if (qtBase.isNotEmpty() && qtBase == snBase) return 95

        if (qt in sn || sn in qt) {
            val ratio = minOf(qt.length, sn.length).toDouble() / maxOf(qt.length, sn.length)
            if (ratio >= MIN_TITLE_CONTAIN_RATIO) {
                return (70 + (25 * ratio)).toInt()
            }
            // Bilingual QQ titles often append a Latin transliteration
            // ("排尾后巷 Paiwei Back Alley"). Length ratio then collapses below
            // MIN_TITLE_CONTAIN_RATIO even though the CJK primary title matches.
            // Restrict to CJK queries so short Latin tokens ("love" ⊂ "love story")
            // stay gated by the ratio.
            if (qt in sn && isMostlyCjk(qt) && cjkPrimaryTitleMatches(qt, sn)) {
                return 92
            }
            return 0
        }

        val qtToks = qt.split(' ').filter { it.isNotBlank() }.toSet()
        val snToks = sn.split(' ').filter { it.isNotBlank() }.toSet()
        if (qtToks.isEmpty()) return 0
        val overlap = qtToks.count { it in snToks }.toDouble() / qtToks.size
        // Token overlap alone stays below MIN_ACCEPT (80); needs artist / duration.
        if (overlap >= 0.85) return (55 + (20 * overlap)).toInt()
        return 0
    }

    /**
     * True when [qt] is the song's primary CJK title: leading space-token,
     * or an exact contiguous CJK run (not a mid-string fragment).
     */
    private fun cjkPrimaryTitleMatches(qt: String, sn: String): Boolean {
        if (sn == qt || sn.startsWith("$qt ")) return true
        val firstTok = sn.split(' ').firstOrNull().orEmpty()
        if (firstTok == qt) return true
        return cjkRuns(sn).any { it == qt }
    }

    /** Contiguous CJK letter runs inside a normalized title. */
    private fun cjkRuns(normalized: String): List<String> {
        val runs = ArrayList<String>(2)
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                runs.add(cur.toString())
                cur.clear()
            }
        }
        for (ch in normalized) {
            val isCjk = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF
            if (isCjk) cur.append(ch) else flush()
        }
        flush()
        return runs
    }

    private fun artistScore(queryArtist: String, singers: List<String>): Int {
        if (queryArtist.isBlank() || singers.isEmpty()) return 0
        val queryTokens = artistTokens(queryArtist)
        if (queryTokens.isEmpty()) return 0
        // Flatten collab credits on both sides ("A & B" / "A, B").
        val singerTokens = singers.flatMap { artistTokens(it) }.distinct()
        if (singerTokens.isEmpty()) return 0

        var matchedParts = 0
        var bestSingle = 0
        for (token in queryTokens) {
            var tokenBest = 0
            for (sn in singerTokens) {
                when {
                    token == sn -> tokenBest = maxOf(tokenBest, 50)
                    tokenLongEnoughForSubstring(token) &&
                        (token in sn || sn in token) ->
                        tokenBest = maxOf(tokenBest, 40)
                }
            }
            if (tokenBest > 0) matchedParts++
            bestSingle = maxOf(bestSingle, tokenBest)
        }
        if (bestSingle <= 0) return 0
        // Two+ English names both hitting → prefer the collab / correct credit.
        val multiBonus = when {
            queryTokens.size >= 2 && matchedParts >= 2 -> 15
            singerTokens.size >= 2 && matchedParts >= 1 && queryTokens.size == 1 -> 5
            else -> 0
        }
        return (bestSingle + multiBonus).coerceAtMost(65)
    }

    private fun tokenLongEnoughForSubstring(token: String): Boolean {
        val letters = token.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val cjk = letters.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        // CJK: require 3+ so short surnames ("王") / two-char fragments do not
        // false-hit inside longer credits. Exact token equality still scores.
        if (cjk > 0) return letters.length >= 3
        return letters.length >= 3
    }

    private fun versionPenalty(
        query: String,
        songName: String,
        album: String,
        singers: List<String>,
    ): Int {
        val blob = "$songName $album ${singers.joinToString(" ")}".lowercase()
        val q = query.lowercase()
        var pen = 0
        for ((token, weight) in VERSION_PENALTIES) {
            if (token in q) continue
            if (containsVersionToken(blob, token)) pen += weight
        }
        return pen
    }

    /**
     * CJK tokens: plain substring. ASCII tokens: word-ish boundaries so
     * "live" does not false-hit inside "Alive" / "Believe".
     */
    private fun containsVersionToken(blob: String, token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.any { it.code > 0x7F }) return token in blob
        var start = 0
        while (true) {
            val idx = blob.indexOf(token, start)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !blob[idx - 1].isLetterOrDigit()
            val end = idx + token.length
            val afterOk = end >= blob.length || !blob[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            start = idx + 1
        }
    }

    /** Prefer bare titles over "Title (Remix/too/…)" when the query has no parens. */
    private fun parenMismatchPenalty(queryTitle: String, songName: String): Int {
        val queryParens = PAREN_CONTENT.findAll(queryTitle).map { it.groupValues[1].lowercase() }.toList()
        val songParens = PAREN_CONTENT.findAll(songName).map { it.groupValues[1].lowercase() }.toList()
        if (songParens.isEmpty()) return 0
        var pen = 0
        for (extra in songParens) {
            if (extra.isBlank()) continue
            val covered = queryParens.any { it == extra || it in extra || extra in it } ||
                extra in queryTitle.lowercase()
            if (!covered) {
                pen += when {
                    extra.contains("feat") || extra.contains("ft.") -> 5
                    else -> 18
                }
            }
        }
        return pen
    }

    private fun singersOf(song: JSONObject): List<String> {
        val singers = song.optJSONArray("singer") ?: return emptyList()
        return (0 until singers.length())
            .mapNotNull { index ->
                singers.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
            // QQ sometimes packs "A/B" into one singer slot.
            .flatMap { name ->
                val tokens = artistTokens(name)
                if (tokens.size <= 1) listOf(name) else tokens
            }
    }

    /**
     * Individual artist names from a credit string.
     * Split first, then normalize — otherwise `&` / `,` become spaces and dual
     * English names collapse into one token.
     */
    fun artistTokens(artist: String): List<String> {
        val stripped = stripParens(artist).trim()
        if (stripped.isEmpty()) return emptyList()
        val parts = ARTIST_SPLIT.split(stripped)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            val whole = normalize(stripped)
            return if (whole.isEmpty()) emptyList() else listOf(whole)
        }
        return parts.distinct()
    }

    private fun stripParens(text: String): String =
        PAREN_CONTENT.replace(text, " ")

    private fun normalize(text: String): String {
        // Avoid \p{L}/\p{N} property classes — filter via Char APIs for Android safety.
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch.isLetterOrDigit() || ch.isWhitespace() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return MULTI_SPACE.replace(sb.toString().trim(), " ")
            .let { foldMatchChars(it) }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Lazy singleton: [android.icu.text.Transliterator.getInstance] is expensive
     * and was being re-created for every normalize call (dozens per search).
     * Null when the ICU transliterator is unavailable on this device — matching
     * then proceeds without Traditional→Simplified folding instead of throwing.
     */
    private val traditionalToSimplified: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                android.icu.text.Transliterator.getInstance("Traditional-Simplified")
            } catch (t: Throwable) {
                Log.w("QqLyricSongMatcher", "Traditional-Simplified transliterator unavailable", t)
                null
            }
        } else {
            null
        }
    }

    private fun foldMatchChars(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFD)
        s = s.replace(COMBINING_MARKS, "")
        val translit = traditionalToSimplified
        if (translit != null) {
            // ICU transliterators are not documented thread-safe; searches can
            // race from parallel lyric fetches.
            synchronized(translit) {
                s = translit.transliterate(s)
            }
        }
        return s
    }
}
