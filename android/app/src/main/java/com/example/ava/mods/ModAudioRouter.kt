package com.example.ava.mods

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.example.ava.audio.PlaybackReferenceBus

/**
 * Optional audio-routing extension point — the "splitter".
 *
 * **Zero cost when unused:** no enabled mod sets [ModManifest.audioRouter], so [isActive]
 * is false and every caller keeps the default `USAGE_MEDIA` / STREAM_MUSIC path. When a
 * router mod is enabled, the host emits voice-reply (TTS) playback on the mod-chosen
 * [AudioAttributes] usage instead of media, so a mod that controls each usage's device
 * volume independently (e.g. via root `setVolumeIndexForAttributes`) can expose three
 * genuinely separate volume controls (media / TTS / alerts) to Home Assistant.
 *
 * Division of labour:
 *  - **Host** (this bridge): routes the TTS AudioTracks to [ttsAudioUsage] and skips its own
 *    STREAM_MUSIC voice-reply overlay so the two do not fight. The AEC far-end tee
 *    ([PlaybackReferenceBus]) is independent of usage and keeps working.
 *  - **Mod**: owns per-channel volume + Home Assistant entities, and calls [noteLevelChange]
 *    after each routed-channel volume write so the software AEC re-adapts.
 *
 * Mod opt-in (manifest `"audio_router": true` + manager methods):
 *  - `int getTtsAudioUsage(Context)` — an [AudioAttributes] `USAGE_*` for voice replies.
 *    `0` / missing keeps the default media usage (host still skips its overlay).
 */
object ModAudioRouter {
    private const val TAG = "ModAudioRouter"

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedActive = false

    @Volatile
    private var cachedTtsUsage = AudioAttributes.USAGE_MEDIA

    fun invalidateCache() {
        cachedGeneration = -1
    }

    /** True while an enabled mod owns audio routing. Cheap, hot-path safe. */
    fun isActive(context: Context): Boolean {
        refreshIfNeeded(context)
        return cachedActive
    }

    /**
     * [AudioAttributes] `USAGE_*` the host should tag voice-reply (TTS) playback with.
     * Returns [AudioAttributes.USAGE_MEDIA] when no router mod is active.
     */
    fun ttsAudioUsage(context: Context): Int {
        refreshIfNeeded(context)
        return cachedTtsUsage
    }

    /**
     * Last resolved TTS usage without a [Context], for playback paths that build an
     * AudioTrack off the main service (e.g. streaming PCM TTS). The cache is warmed by
     * [ttsAudioUsage] at voice-satellite start; defaults to [AudioAttributes.USAGE_MEDIA]
     * until then, so the worst case is a media-usage track, never a crash.
     */
    fun cachedTtsAudioUsage(): Int = cachedTtsUsage

    /**
     * AEC passthrough: a routed-channel volume write happens *after* the PCM tee, so
     * the far-end reference cannot see it. Mods call this so AEC3 re-adapts instead of
     * leaking echo, exactly as the host's own STREAM_MUSIC overlay does.
     */
    fun noteLevelChange() {
        PlaybackReferenceBus.noteLevelChange()
    }

    private fun refreshIfNeeded(context: Context) {
        val modManager = ModManager.getInstance(context)
        val generation = modManager.registryGeneration
        if (generation == cachedGeneration) return

        var active = false
        var usage = AudioAttributes.USAGE_MEDIA
        val manifest = modManager.getEnabledManifests().firstOrNull {
            it.audioRouter && !it.manager.isNullOrBlank()
        }
        if (manifest != null) {
            runCatching {
                val loader = modManager.getModClassLoader(manifest.id)
                val managerClass = loader.loadClass(manifest.manager!!)
                val instance = managerClass
                    .getMethod("getInstance", Context::class.java)
                    .invoke(null, context)
                if (instance != null) {
                    active = true
                    val routed = runCatching {
                        managerClass
                            .getMethod("getTtsAudioUsage", Context::class.java)
                            .invoke(instance, context) as? Int
                    }.getOrNull()
                    if (routed != null && routed > 0) {
                        usage = routed
                    }
                }
            }.onFailure {
                Log.w(TAG, "audio_router bind failed for ${manifest.id}", it)
            }
        }

        cachedActive = active
        cachedTtsUsage = usage
        cachedGeneration = generation
    }
}
