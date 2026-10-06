package com.example.ava.massapi

/** Thin MA models for the overlay rail (JSONObject-parsed, not kotlinx serialization). */
data class MassPlayer(
    val playerId: String,
    val displayName: String,
    val volumeLevel: Int = 0,
    val groupVolume: Int? = null,
    val syncedTo: String? = null,
    val groupChilds: List<String> = emptyList(),
    val available: Boolean = true,
    val enabled: Boolean = true,
    val state: String = "idle",
    val trackTitle: String = "",
    val trackArtist: String = "",
    val activeSource: String? = null,
)

data class MassQueueItem(
    val queueItemId: String,
    val title: String,
    val artist: String = "",
    val durationSec: Double = 0.0,
    val isCurrent: Boolean = false,
    /** `media_item.item_id` — required for [MassApiClient.fetchTrackLyrics]. */
    val mediaItemId: String = "",
    /** `media_item.provider` domain / instance. */
    val provider: String = "",
    val uri: String = "",
    /** Absolute HTTP(S) cover URL when resolvable from MA image / imageproxy. */
    val imageUrl: String = "",
    /** MA `media_item.favorite` when the queue payload includes it. */
    val favorite: Boolean = false,
    /** `media_item.media_type` — usually track; used for favorite remove. */
    val mediaType: String = "track",
    /**
     * MA library `item_id` once resolved (after favorite add / item_by_uri).
     * Required by `music/favorites/remove_item`.
     */
    val libraryItemId: String = "",
) {
    /** URI for `music/favorites/add_item` — prefer media uri, else provider://track/id. */
    fun favoriteItemUri(): String {
        if (uri.isNotBlank()) return uri
        if (mediaItemId.isBlank() || provider.isBlank()) return ""
        val kind = mediaType.ifBlank { "track" }
        return "$provider://$kind/$mediaItemId"
    }
}

data class MassPlaylist(
    val itemId: String,
    val name: String,
    val provider: String = "",
    val uri: String = "",
    val trackCount: Int? = null,
    /** Only filled after opening the detail page — list rows stay cover-free. */
    val description: String = "",
    /** Absolute cover URL from detail fetch; never hydrated on the library list. */
    val imageUrl: String = "",
)

/** One row inside a playlist detail (not a queue item). */
data class MassPlaylistTrack(
    val itemId: String,
    val name: String,
    val artist: String = "",
    val uri: String = "",
    val durationSec: Double = 0.0,
) {
    val stableKey: String
        get() = uri.ifBlank { itemId }
}

/**
 * One `music/search` hit. MA returns results grouped into one array per media type;
 * this flattens the buckets the rail can actually act on so a narrow list can mix them,
 * keeping [mediaType] so the row can badge it and the tap can pick play-now vs replace.
 *
 * [uri] is never blank: a hit with no URI cannot be handed to `player_queues/play_media`,
 * and a row that cannot play is worse than no row.
 */
data class MassSearchItem(
    val uri: String,
    val name: String,
    val mediaType: String,
    val artist: String = "",
    /** Track hits: parent album name. Album hits leave this blank — [name] is the album. */
    val album: String = "",
    val provider: String = "",
    /** Absolute HTTP(S) cover URL when resolvable from MA image / imageproxy. */
    val imageUrl: String = "",
    val durationSec: Double = 0.0,
    /** Playlists / albums only. */
    val trackCount: Int? = null,
) {
    val stableKey: String
        get() = uri

    /** Tracks inject into the live queue; containers replace it. */
    val isTrack: Boolean
        get() = mediaType == "track"
}
