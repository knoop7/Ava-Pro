package com.example.ava.mods

import android.content.Context
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import com.example.ava.audio.PlaybackReferenceBus
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional far-end reference bridge for mods whose playback cannot tee PCM directly
 * (e.g. DLNA [android.media.MediaPlayer]).
 *
 * **Zero cost when unused:** no enabled mod sets [ModManifest.playbackReference], and
 * [PlaybackReferenceBus.active] is false — every entry point returns immediately.
 *
 * Mod opt-in (both required):
 * 1. `"playback_reference": true` in manifest.json
 * 2. Manager implements:
 *    - `boolean isPlaybackReferenceActive(Context)`
 *    - `int getPlaybackAudioSessionId(Context)` — non-zero while playing
 *
 * Optional direct PCM feed (mods with ExoPlayer/AudioTrack taps):
 * - [feedPcm16]
 */
object ModPlaybackReference {
    private const val TAG = "ModPlaybackReference"

    private const val CAPTURE_POLL_MS = 80L
    private const val SESSION_CAPTURE_AVAILABLE = false

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedOptIn = false

    private var cachedHooks: List<Hook>? = null

    private val feedWriters = HashMap<String, PlaybackReferenceBus.Writer>()

    private val monitorRunning = AtomicBoolean(false)
    private val busActive = AtomicBoolean(false)

    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ModPlaybackReference").apply { isDaemon = true }
        }
    }

    private var captureRecord: AudioRecord? = null
    private var captureSessionId = 0
    private var captureWriter: PlaybackReferenceBus.Writer? = null
    private val captureBuffer = ByteArray(4096)

    fun invalidateCache() {
        cachedGeneration = -1
        cachedHooks = null
    }

    /** Called when the mic loop toggles [PlaybackReferenceBus.active]. */
    fun onBusActiveChanged(context: Context, active: Boolean) {
        busActive.set(active)
        if (!active) {
            executor.execute { releaseCaptureLocked() }
            synchronized(feedWriters) { feedWriters.clear() }
            return
        }
        // Session-scoped AudioPlaybackCapture is not implemented (needs MediaProjection).
        // [ensureCaptureLocked] always fails, so a monitor thread would reflect-poll
        // every 80ms for the life of the mic — AirPlay/DLNA opt-in made that the idle
        // default. Mods that can tee PCM already use [feedPcm16].
        if (!SESSION_CAPTURE_AVAILABLE) return
        if (!isOptIn(context)) return
        if (!monitorRunning.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                monitorLoop(appContext)
            } finally {
                monitorRunning.set(false)
                releaseCaptureLocked()
            }
        }
    }

    /**
     * Direct PCM feed for mods that can tap their own playback (ExoPlayer tee, AudioTrack).
     * Gated: bus active, mod enabled + manifest opt-in, modId must match an installed mod.
     */
    fun feedPcm16(
        context: Context,
        modId: String,
        pcm: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        if (!PlaybackReferenceBus.active || length < 2) return
        if (!isModFeedAllowed(context, modId)) return
        val writer = synchronized(feedWriters) {
            feedWriters.getOrPut(modId) {
                PlaybackReferenceBus.createWriter(sampleRate, channels)
            }
        }
        writer.write(pcm, offset, length)
    }

    private fun isOptIn(context: Context): Boolean {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation == cachedGeneration) {
            return cachedOptIn
        }
        refreshCache(context, generation)
        return cachedOptIn
    }

    private fun isModFeedAllowed(context: Context, modId: String): Boolean {
        if (!isOptIn(context)) return false
        val modManager = ModManager.getInstance(context)
        val manifest = modManager.getEnabledManifests().firstOrNull { it.id == modId } ?: return false
        return manifest.playbackReference && !manifest.manager.isNullOrBlank()
    }

    private fun refreshCache(context: Context, generation: Int) {
        val modManager = ModManager.getInstance(context)
        val hooks = buildHooks(context, modManager)
        cachedGeneration = generation
        cachedOptIn = hooks.isNotEmpty()
        cachedHooks = hooks
    }

    private fun hooks(context: Context): List<Hook> {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation != cachedGeneration) {
            refreshCache(context, generation)
        }
        return cachedHooks.orEmpty()
    }

    private fun buildHooks(context: Context, modManager: ModManager): List<Hook> {
        return modManager.getEnabledManifests().mapNotNull { manifest ->
            if (!manifest.playbackReference || manifest.manager.isNullOrBlank()) {
                return@mapNotNull null
            }
            val classLoader = modManager.getModClassLoader(manifest.id)
            runCatching {
                val managerClass = classLoader.loadClass(manifest.manager!!)
                val activeMethod = managerClass.getMethod(
                    "isPlaybackReferenceActive",
                    Context::class.java,
                )
                val sessionMethod = managerClass.getMethod(
                    "getPlaybackAudioSessionId",
                    Context::class.java,
                )
                val instance = managerClass.getMethod("getInstance", Context::class.java)
                    .invoke(null, context)
                    ?: return@runCatching null
                Hook(manifest.id, instance, activeMethod, sessionMethod)
            }.onFailure {
                Log.w(TAG, "playback_reference hooks missing for mod ${manifest.id}", it)
            }.getOrNull()
        }
    }

    private fun monitorLoop(context: Context) {
        while (busActive.get() && PlaybackReferenceBus.active) {
            if (!isOptIn(context)) {
                releaseCaptureLocked()
                Thread.sleep(CAPTURE_POLL_MS)
                continue
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                releaseCaptureLocked()
                Thread.sleep(CAPTURE_POLL_MS)
                continue
            }
            val hook = hooks(context).firstOrNull { candidate ->
                runCatching {
                    candidate.isActive.invoke(candidate.instance, context) as? Boolean
                }.getOrNull() == true
            }
            if (hook == null) {
                releaseCaptureLocked()
                Thread.sleep(CAPTURE_POLL_MS)
                continue
            }
            val sessionId = runCatching {
                hook.sessionId.invoke(hook.instance, context) as? Int
            }.getOrNull() ?: 0
            if (sessionId <= 0) {
                releaseCaptureLocked()
                Thread.sleep(CAPTURE_POLL_MS)
                continue
            }
            if (!ensureCaptureLocked(context, sessionId)) {
                Thread.sleep(CAPTURE_POLL_MS)
                continue
            }
            val record = captureRecord ?: continue
            val read = try {
                record.read(captureBuffer, 0, captureBuffer.size)
            } catch (e: Exception) {
                Log.w(TAG, "capture read failed", e)
                releaseCaptureLocked()
                continue
            }
            if (read > 0) {
                captureWriter?.write(captureBuffer, 0, read)
            } else if (read < 0) {
                Log.w(TAG, "capture read error $read, restarting")
                releaseCaptureLocked()
            }
        }
        releaseCaptureLocked()
    }

    /**
     * Session-scoped AudioPlaybackCapture is not available: the platform API requires
     * [android.media.projection.MediaProjection] and has no per-session filter.
     * Mods that can tap PCM should use [feedPcm16] instead.
     */
    private fun ensureCaptureLocked(@Suppress("UNUSED_PARAMETER") context: Context, sessionId: Int): Boolean {
        if (captureRecord != null && captureSessionId == sessionId) {
            return captureRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }
        releaseCaptureLocked()
        return false
    }

    private fun releaseCaptureLocked() {
        captureWriter = null
        captureSessionId = 0
        captureRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        captureRecord = null
    }

    private data class Hook(
        val modId: String,
        val instance: Any,
        val isActive: Method,
        val sessionId: Method,
    )
}
