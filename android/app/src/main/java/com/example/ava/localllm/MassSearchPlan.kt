package com.example.ava.localllm

import com.example.ava.massapi.MassSearchItem

/**
 * Claw-style host plan for Music Assistant: search the title (or artist)
 * as its own field. Do not mash "artist title" into one query.
 */
data class MassSearchPlan(
    val searchName: String,
    val types: List<String>,
    val title: String,
    val artist: String,
    val album: String,
    val fallbackTypes: List<String> = emptyList(),
) {
    val artistOnly: Boolean
        get() = title.isBlank() && album.isBlank() && artist.isNotBlank()

    val includeLibraryPlaylists: Boolean
        get() = "playlist" in types

    companion object {
        fun of(
            title: String,
            artist: String = "",
            album: String = "",
            kind: String = "any",
        ): MassSearchPlan {
            val rawTitle = title.trim()
            val rawArtist = artist.trim()
            val rawAlbum = album.trim()
            val kindN = kind.trim().ifEmpty { "any" }
            val titleU = HaPlayGuard.unwrapMusicQuery(rawTitle)
            val artistU = HaPlayGuard.unwrapMusicQuery(rawArtist)
            val albumU = HaPlayGuard.unwrapMusicQuery(rawAlbum)
            val catalog = HaPlayGuard.looksLikeArtistCatalog(rawTitle) ||
                HaPlayGuard.looksLikeArtistCatalog(rawArtist)
            val artistOnly = (titleU.isEmpty() && albumU.isEmpty() && artistU.isNotEmpty()) ||
                (catalog && artistU.isEmpty() && albumU.isEmpty() && titleU.isNotEmpty())
            val playlistCue = kindN == "playlist" ||
                HaPlayGuard.looksLikePlaylist(rawTitle) ||
                HaPlayGuard.looksLikePlaylist(rawArtist)
            val searchName = when {
                kindN == "artist" || (artistOnly && artistU.isNotEmpty()) -> artistU.ifBlank { titleU }
                artistOnly -> titleU
                kindN == "album" -> albumU.ifBlank { titleU }
                playlistCue && titleU.isNotEmpty() -> titleU
                titleU.isNotEmpty() -> titleU
                albumU.isNotEmpty() -> albumU
                else -> artistU
            }
            val types = when {
                kindN == "artist" || artistOnly -> listOf("artist")
                kindN == "playlist" || (kindN == "any" && playlistCue && artistU.isEmpty()) ->
                    listOf("playlist")
                kindN == "album" -> listOf("album")
                kindN == "track" -> listOf("track")
                kindN == "radio" -> listOf("radio")
                artistU.isEmpty() && albumU.isEmpty() ->
                    listOf("track", "album", "playlist", "artist")
                else -> listOf("track", "album", "playlist")
            }
            val fallback = if (types == listOf("artist")) listOf("track", "album") else emptyList()
            return MassSearchPlan(
                searchName = searchName,
                types = types,
                title = if (artistOnly && artistU.isEmpty()) "" else titleU,
                artist = if (artistOnly && artistU.isEmpty()) titleU else artistU,
                album = albumU,
                fallbackTypes = fallback,
            )
        }

        /**
         * A bare artist name should play the artist, not the first track that
         * merely credits them. An exact unique track title still wins.
         */
        fun prefer(hits: List<MassSearchItem>, plan: MassSearchPlan): List<MassSearchItem> {
            if (hits.size <= 1) return hits
            val want = DeviceIndex.normalize(plan.searchName)
            val exactName = hits.filter { DeviceIndex.normalize(it.name) == want }
            val exactTracks = exactName.filter { it.isTrack }
            if (exactTracks.size == 1) return exactTracks
            if (exactTracks.size > 1) return exactTracks
            val artists = hits.filter { it.mediaType == "artist" }
            val artistExact = artists.filter { DeviceIndex.normalize(it.name) == want }
            if (artistExact.size == 1 && exactTracks.isEmpty()) return artistExact
            if (artists.size == 1 && exactTracks.isEmpty() &&
                (plan.artistOnly || DeviceIndex.normalize(artists.first().name) == want)
            ) {
                return artists
            }
            if (exactName.size == 1) return exactName
            if (exactName.size > 1) return exactName
            return hits
        }
    }
}
