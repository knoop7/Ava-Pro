package com.example.ava.lyrics

enum class LyricSource {
    /** [AmllLyricsClient] community TTML word-sync. */
    AMLL,
    /** Music Assistant `metadata/get_track_lyrics` (synced LRC). */
    MASS_API,
    QQ,
    LRCLIB,
}

data class LyricsLoadResult(
    val lines: List<LrcLine>,
    val source: LyricSource?,
)
