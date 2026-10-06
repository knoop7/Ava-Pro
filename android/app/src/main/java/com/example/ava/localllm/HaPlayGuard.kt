package com.example.ava.localllm

import com.example.ava.esphome.voicesatellite.HaAssistMissDetector

/**
 * Host gate for song search. Needle and leftover tokens must not turn a device
 * miss ("抱歉，找不到…设备") into a Music Assistant first-hit play.
 */
object HaPlayGuard {

    fun isAssistMiss(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return HaAssistMissDetector.shouldHandoff(t)
    }

    fun hasStrongPlayCue(spoken: String): Boolean {
        val n = DeviceIndex.normalize(spoken)
        return STRONG.any { n.contains(it) }
    }

    /** `播放` / English word `play`, not the letters inside `display`. */
    fun hasWeakPlayCue(spoken: String): Boolean {
        val n = DeviceIndex.normalize(spoken)
        if (n.contains("播放")) return true
        return PLAY_WORD.containsMatchIn(n)
    }

    fun maySearchMedia(spoken: String): Boolean =
        hasStrongPlayCue(spoken) || hasWeakPlayCue(spoken)

    fun hasAllCue(spoken: String): Boolean {
        val n = DeviceIndex.normalize(spoken)
        return ALL.any { n.contains(it) }
    }

    /**
     * Spoken title the host may search. Null = do not play anything.
     */
    fun songQuery(spoken: String, rawQuery: String, index: DeviceIndex): String? {
        if (isAssistMiss(spoken) || isAssistMiss(rawQuery)) return null
        if (spoken.isNotBlank() && !maySearchMedia(spoken)) return null
        val q = DeviceIndex.normalize(rawQuery)
        if (q.length < MIN_QUERY) return null
        if (index.resolve(rawQuery) != null || index.resolve(q) != null) return null
        val domain = HaDomainActions.hintDomain(q)
        val stripped = HaDomainActions.stripDomainCues(q)
        if (stripped.length < MIN_QUERY) return null
        if (domain != null && index.discover(nameContains = stripped, domain = domain, limit = 4).isNotEmpty()) {
            return null
        }
        if (domain != null && !hasStrongPlayCue(spoken)) return null
        return rawQuery.trim()
    }

    /**
     * Strip play/playlist filler so "放首晴天" and "喜欢你歌单" search the
     * real name. Host-only; not taught to the model.
     */
    fun unwrapMusicQuery(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        t = LEAD_POLITE.replace(t, "")
        t = LEAD_PLAY.replace(t, "")
        t = LEAD_TYPE.replace(t, "")
        t = TAIL_TYPE.replace(t, "")
        return DeviceIndex.normalize(t)
    }

    fun looksLikePlaylist(spoken: String): Boolean {
        val n = DeviceIndex.normalize(spoken)
        return PLAYLIST_CUES.any { n.contains(it) }
    }

    fun looksLikeArtistCatalog(spoken: String): Boolean {
        val n = DeviceIndex.normalize(spoken)
        return ARTIST_CUES.any { n.contains(it) }
    }

    /** MASS / library hit must actually mention the spoken title. */
    fun titleMatches(query: String, title: String, artist: String = ""): Boolean {
        val q = unwrapMusicQuery(query).ifBlank { DeviceIndex.normalize(query) }
        if (q.length < MIN_QUERY) return false
        val hay = DeviceIndex.normalize("$title $artist")
        if (hay.contains(q)) return true
        val parts = q.split(SPLIT).map { it.trim() }.filter { it.length >= MIN_QUERY }
        if (parts.isNotEmpty() && parts.all { hay.contains(it) }) return true
        val titleN = DeviceIndex.normalize(title)
        val artistN = DeviceIndex.normalize(artist)
        return HaSpokenHear.close(q, titleN) ||
            (artistN.isNotEmpty() && HaSpokenHear.close(q, artistN))
    }

    private const val MIN_QUERY = 2
    private val SPLIT = Regex("[的和与及\\s]+")
    private val PLAY_WORD = Regex("(^|\\s)play(\\s|$)")
    private val LEAD_POLITE = Regex("^(请|帮我|麻烦)+")
    private val LEAD_PLAY = Regex(
        "^(我想听|播放歌曲|播放音乐|play the song|play song|放一首|来一首|听一下|听首|放首|来首|点播|放歌|播放|play(?!list))\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val LEAD_TYPE = Regex(
        "^(播放列表|playlist|歌单)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val TAIL_TYPE = Regex(
        "(播放列表|playlist|歌单|这首歌|那首歌|的歌曲|的音乐|的歌|专辑|album|radio)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val PLAYLIST_CUES = listOf("歌单", "播放列表", "playlist")
    private val ARTIST_CUES = listOf("的歌曲", "的音乐", "的歌", "radio")

    private val STRONG = listOf(
        "play the song", "play song", "放一首", "来一首", "播放歌曲", "播放音乐",
        "放歌", "点播", "我想听",
    )
    private val ALL = listOf("所有", "全部", "每一个", "all the", "all lights", "all switches")
}
