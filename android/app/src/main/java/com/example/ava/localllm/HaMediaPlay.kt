package com.example.ava.localllm

import android.util.Log
import com.example.ava.homeassistant.entity.HaEntitySummary
import com.example.ava.massapi.MassApiClient
import com.example.ava.massapi.MassApiManager
import com.example.ava.massapi.MassSearchItem

/**
 * Host-side song search. The model only emits a spoken title; Music Assistant
 * plays a hit only when the title/artist actually overlaps that title.
 * Unverified HA `play_media` / first-search-hit is never used.
 */
object HaMediaPlay {

    suspend fun play(
        query: String,
        entity: HaEntitySummary,
        artist: String = "",
        mediaClass: String = "",
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
    ): Boolean {
        val plan = MassSearchPlan.of(query, artist, album = "", kind = mediaClass.ifBlank { "any" })
        if (plan.searchName.length < 2) return false
        if (playViaMass(plan)) return true
        Log.i(TAG, "no matching title for '${plan.searchName}' player=${entity.entityId.ifBlank { "-" }}")
        return false
    }

    private suspend fun playViaMass(plan: MassSearchPlan): Boolean {
        val mass = MassApiManager.get() ?: return false
        if (mass.connectionState.value !is MassApiClient.ConnectionState.Connected) return false
        fun take(hits: List<MassSearchItem>) = hits.filter { hit ->
            val record = hit.album.ifBlank { if (hit.mediaType == "album") hit.name else "" }
            if (plan.title.isNotBlank() && hit.mediaType != "artist" &&
                !HaPlayGuard.titleMatches(plan.title, hit.name, "${hit.artist} $record")
            ) {
                return@filter false
            }
            if (plan.artist.isNotBlank()) {
                val hay = if (hit.mediaType == "artist") hit.name else hit.artist
                if (!HaPlayGuard.titleMatches(plan.artist, hay, hit.name)) return@filter false
            }
            true
        }
        val first = runCatching { mass.searchNow(plan.searchName, plan.types) }
            .onFailure { Log.w(TAG, "mass search failed", it) }
            .getOrDefault(emptyList())
        var matched = take(first)
        if (matched.isEmpty() && plan.fallbackTypes.isNotEmpty()) {
            matched = take(
                runCatching { mass.searchNow(plan.searchName, plan.fallbackTypes) }
                    .getOrDefault(emptyList()),
            )
        }
        val picked = MassSearchPlan.prefer(matched, plan)
        val hit = picked.singleOrNull() ?: return false
        return runCatching {
            if (hit.isTrack) mass.playTrackUri(hit.uri) else mass.playPlaylist(hit.uri)
        }.getOrDefault(false)
    }

    private const val TAG = "HaMediaPlay"
}
