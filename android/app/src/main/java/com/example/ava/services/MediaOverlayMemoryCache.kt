package com.example.ava.services

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide snapshot of the latest media overlay fields.
 * Survives [VinylCoverService] recreate / intent races so the mini FAB
 * always knows title + whether we are playing without waiting on upstream.
 */
object MediaOverlayMemoryCache {
    data class Snapshot(
        val coverUrl: String? = null,
        val songTitle: String = "",
        val artistName: String = "",
        val albumName: String = "",
        val isPlaying: Boolean = false,
        val currentTimeMs: Long = 0L,
        val totalTimeMs: Long = 0L,
        /**
         * An explicit playhead write landed here, including a deliberate 0.
         * Default [currentTimeMs] is 0 with this false — that is "never seated",
         * not "song starts at 0:00". FAB expand / manager rebind must not
         * treat the default as a hard seat.
         */
        val progressSeated: Boolean = false,
        val isSendspinSource: Boolean = false,
        val lyricAudibleLagMs: Long = 0L,
        /** Soft reference — may be recycled; callers must check. */
        val coverBitmap: Bitmap? = null,
        val updatedAtMs: Long = 0L,
    ) {
        fun hasDisplayableContent(): Boolean =
            songTitle.isNotEmpty() ||
                artistName.isNotEmpty() ||
                !coverUrl.isNullOrEmpty() ||
                (coverBitmap != null && !coverBitmap.isRecycled)
    }

    private val snapshot = AtomicReference(Snapshot())

    fun get(): Snapshot = snapshot.get()

    fun clear() {
        snapshot.set(Snapshot(updatedAtMs = System.currentTimeMillis()))
    }

    /**
     * Hard replace (no sticky merge). Used after MA queue clear / unsync wipe so
     * a null cover or blank artist cannot resurrect the previous track from [putFull].
     */
    fun replaceAll(snapshot: Snapshot) {
        this.snapshot.set(snapshot.copy(updatedAtMs = System.currentTimeMillis()))
    }

    fun updatePlayback(isPlaying: Boolean) {
        snapshot.updateAndGet { prev ->
            prev.copy(isPlaying = isPlaying, updatedAtMs = System.currentTimeMillis())
        }
    }

    fun updateProgress(currentTimeMs: Long? = null, totalTimeMs: Long? = null, lyricAudibleLagMs: Long? = null) {
        snapshot.updateAndGet { prev ->
            prev.copy(
                currentTimeMs = currentTimeMs ?: prev.currentTimeMs,
                progressSeated = if (currentTimeMs != null) true else prev.progressSeated,
                totalTimeMs = when {
                    totalTimeMs == null -> prev.totalTimeMs
                    totalTimeMs > 0L -> totalTimeMs
                    else -> prev.totalTimeMs
                },
                lyricAudibleLagMs = lyricAudibleLagMs ?: prev.lyricAudibleLagMs,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun updateMetadata(
        songTitle: String? = null,
        artistName: String? = null,
        albumName: String? = null,
        isPlaying: Boolean? = null,
        currentTimeMs: Long? = null,
        totalTimeMs: Long? = null,
    ) {
        snapshot.updateAndGet { prev ->
            val merged = mergeIdentity(prev, songTitle, artistName, albumName)
            val titleChanged =
                merged.songTitle != prev.songTitle && prev.songTitle.isNotEmpty()
            prev.copy(
                songTitle = merged.songTitle,
                artistName = merged.artistName,
                albumName = merged.albumName,
                isPlaying = isPlaying ?: prev.isPlaying,
                currentTimeMs = mergeCurrentTimeMs(prev.currentTimeMs, currentTimeMs, titleChanged),
                progressSeated = mergeProgressSeated(prev.progressSeated, currentTimeMs, titleChanged),
                totalTimeMs = mergeTotalTimeMs(prev.totalTimeMs, totalTimeMs, titleChanged),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun setCoverBitmap(bitmap: Bitmap?) {
        snapshot.updateAndGet { prev ->
            val recycledPrev = prev.coverBitmap
            if (recycledPrev != null && recycledPrev !== bitmap && !recycledPrev.isRecycled) {
                // Don't recycle here — VinylCoverService owns UI bitmap lifetime.
            }
            prev.copy(
                coverUrl = if (bitmap != null && !bitmap.isRecycled) "sendspin:binary" else null,
                coverBitmap = bitmap?.takeIf { !it.isRecycled },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun updateCover(coverUrl: String?, coverBitmap: Bitmap? = null) {
        snapshot.updateAndGet { prev ->
            prev.copy(
                coverUrl = coverUrl?.takeIf { it.isNotEmpty() } ?: prev.coverUrl,
                coverBitmap = when {
                    coverBitmap != null && !coverBitmap.isRecycled -> coverBitmap
                    else -> prev.coverBitmap
                },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun putFull(
        coverUrl: String? = null,
        songTitle: String? = null,
        artistName: String? = null,
        albumName: String? = null,
        isPlaying: Boolean? = null,
        currentTimeMs: Long? = null,
        totalTimeMs: Long? = null,
        isSendspinSource: Boolean? = null,
        lyricAudibleLagMs: Long? = null,
        coverBitmap: Bitmap? = null,
    ) {
        snapshot.updateAndGet { prev ->
            val merged = mergeIdentity(prev, songTitle, artistName, albumName)
            val titleChanged =
                merged.songTitle != prev.songTitle && prev.songTitle.isNotEmpty()
            prev.copy(
                coverUrl = coverUrl?.takeIf { it.isNotEmpty() } ?: prev.coverUrl,
                songTitle = merged.songTitle,
                artistName = merged.artistName,
                albumName = merged.albumName,
                isPlaying = isPlaying ?: prev.isPlaying,
                currentTimeMs = mergeCurrentTimeMs(prev.currentTimeMs, currentTimeMs, titleChanged),
                progressSeated = mergeProgressSeated(prev.progressSeated, currentTimeMs, titleChanged),
                totalTimeMs = mergeTotalTimeMs(prev.totalTimeMs, totalTimeMs, titleChanged),
                isSendspinSource = isSendspinSource ?: prev.isSendspinSource,
                lyricAudibleLagMs = lyricAudibleLagMs ?: prev.lyricAudibleLagMs,
                coverBitmap = when {
                    coverBitmap != null && !coverBitmap.isRecycled -> coverBitmap
                    else -> prev.coverBitmap
                },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Sticky merge for pause/partial packets, but **never** keep the previous
     * track's artist/album when the title advances and the new source omits them
     * (otherwise lyrics search runs as "new title + old artist").
     *
     * - Title change + missing/blank artist → clear artist (same for album).
     * - Explicit `""` clears even without a title change.
     * - `null` on the same title keeps the previous value (partial update).
     */
    private fun mergeIdentity(
        prev: Snapshot,
        songTitle: String?,
        artistName: String?,
        albumName: String?,
    ): Snapshot {
        val nextTitle = songTitle?.takeIf { it.isNotEmpty() } ?: prev.songTitle
        val titleChanged =
            !songTitle.isNullOrEmpty() &&
                songTitle != prev.songTitle
        val nextArtist = when {
            titleChanged -> artistName?.takeIf { it.isNotEmpty() }.orEmpty()
            artistName != null -> artistName
            else -> prev.artistName
        }
        val nextAlbum = when {
            titleChanged -> albumName?.takeIf { it.isNotEmpty() }.orEmpty()
            albumName != null -> albumName
            else -> prev.albumName
        }
        return prev.copy(
            songTitle = nextTitle,
            artistName = nextArtist,
            albumName = nextAlbum,
        )
    }

    /**
     * Keep the last seated second on partial packets. A title replace that
     * omits playhead must not keep the previous song's time — that is how
     * FAB expand resurrected 2:25 after a queued start at 0:00.
     */
    private fun mergeCurrentTimeMs(
        previousMs: Long,
        incomingMs: Long?,
        titleChanged: Boolean,
    ): Long = when {
        incomingMs != null -> incomingMs.coerceAtLeast(0L)
        titleChanged -> 0L
        else -> previousMs
    }

    private fun mergeProgressSeated(
        previous: Boolean,
        incomingMs: Long?,
        titleChanged: Boolean,
    ): Boolean = incomingMs != null || titleChanged || previous

    /** Ignore upstream 0 duration on pause/partial packets; clear only on track change. */
    private fun mergeTotalTimeMs(
        previousMs: Long,
        incomingMs: Long?,
        titleChanged: Boolean,
    ): Long = when {
        incomingMs != null && incomingMs > 0L -> incomingMs
        titleChanged -> 0L
        else -> previousMs
    }
}
