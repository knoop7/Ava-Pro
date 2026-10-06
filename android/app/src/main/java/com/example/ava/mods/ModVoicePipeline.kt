package com.example.ava.mods

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * Optional voice-pipeline hook bridge for mods and documented global broadcasts.
 *
 * **Zero cost when unused:** no enabled mod sets [ModManifest.voicePipeline], and no external
 * app registers a receiver for [ACTION_VOICE_PIPELINE] — [isActive] stays false and
 * [dispatch] returns immediately (no Bundle, no ClassLoader, no broadcast).
 *
 * Mod opt-in (both required):
 * 1. `"voice_pipeline": true` in manifest.json
 * 2. Manager implements `onVoicePipelineEvent(Context, String, Bundle)`
 */
object ModVoicePipeline {
    private const val TAG = "ModVoicePipeline"

    const val ACTION_VOICE_PIPELINE = "com.example.ava.VOICE_PIPELINE_EVENT"
    const val EXTRA_EVENT = "event"

    object Events {
        const val WAKE_DETECTED = "wake_detected"
        /** A conversation-engine mod claimed this wake; HA pipeline will not start. */
        const val WAKE_CLAIMED = "wake_claimed"
        const val LISTENING_STARTED = "listening_started"
        const val RUN_START = "run_start"
        const val STT_VAD_START = "stt_vad_start"
        const val STT_VAD_END = "stt_vad_end"
        const val STT_END = "stt_end"
        const val PROCESSING_STARTED = "processing_started"
        const val RESPONDING = "responding"
        const val TTS_START = "tts_start"
        const val TTS_PLAYBACK_STARTED = "tts_playback_started"
        const val TTS_FINISHED = "tts_finished"
        const val RUN_END = "run_end"
        const val PIPELINE_ERROR = "pipeline_error"
        const val SESSION_ENDED = "session_ended"
    }

    object Extras {
        const val WAKE_WORD = "wake_word"
        const val WAKE_WORD_ID = "wake_word_id"
        const val WAKE_CONFIDENCE = "wake_confidence"
        const val SYNTHETIC_WAKE = "synthetic_wake"
        const val STT_TEXT = "stt_text"
        const val TTS_TEXT = "tts_text"
        const val ERROR_CODE = "error_code"
        const val ERROR_MESSAGE = "error_message"
        const val ACCENT_COLOR = "accent_color"
        const val CONTINUE_CONVERSATION = "continue_conversation"
    }

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedActive = false

    @Volatile
    private var cachedModOptIn = false

    @Volatile
    private var cachedBroadcastListeners = false

    private var cachedSubscribers: List<Subscriber>? = null

    private val dispatchExecutor by lazy { Executors.newSingleThreadExecutor { r ->
        Thread(r, "ModVoicePipeline").apply { isDaemon = true }
    } }

    fun invalidateCache() {
        cachedGeneration = -1
        cachedSubscribers = null
    }

    /** Fast path for hot call sites — no allocations when inactive. */
    fun isActive(context: Context): Boolean {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation == cachedGeneration) {
            return cachedActive
        }
        return refreshActivitySnapshot(context, generation)
    }

    fun dispatch(context: Context, event: String, extras: Bundle? = null) {
        if (!isActive(context)) return
        val appContext = context.applicationContext
        dispatchExecutor.execute {
            runCatching {
                dispatchOnWorker(appContext, event, extras)
            }.onFailure {
                Log.w(TAG, "dispatch failed for event=$event", it)
            }
        }
    }

    private fun refreshActivitySnapshot(context: Context, generation: Int): Boolean {
        val modManager = ModManager.getInstance(context)
        val modOptIn = modManager.getEnabledManifests().any { manifest ->
            manifest.voicePipeline && !manifest.manager.isNullOrBlank()
        }
        val broadcastListeners = hasExternalBroadcastReceivers(context)
        val active = modOptIn || broadcastListeners

        cachedGeneration = generation
        cachedModOptIn = modOptIn
        cachedBroadcastListeners = broadcastListeners
        cachedActive = active
        cachedSubscribers = null

        if (!active) {
            Log.d(TAG, "voice pipeline API inactive (no opt-in mods, no broadcast listeners)")
        }
        return active
    }

    private fun dispatchOnWorker(context: Context, event: String, extras: Bundle?) {
        val payload = extras?.let { Bundle(it) } ?: Bundle()
        payload.putString(EXTRA_EVENT, event)

        if (cachedModOptIn) {
            for (subscriber in subscribers(context)) {
                runCatching {
                    subscriber.method.invoke(subscriber.instance, context, event, payload)
                }.onFailure {
                    Log.w(TAG, "mod ${subscriber.modId} onVoicePipelineEvent failed for $event", it)
                }
            }
        }

        if (cachedBroadcastListeners) {
            context.sendBroadcast(
                Intent(ACTION_VOICE_PIPELINE).apply {
                    putExtras(payload)
                },
            )
        }
    }

    private fun subscribers(context: Context): List<Subscriber> {
        cachedSubscribers?.let { return it }

        val modManager = ModManager.getInstance(context)
        val built = modManager.getEnabledManifests().mapNotNull { manifest ->
            if (!manifest.voicePipeline || manifest.manager.isNullOrBlank()) return@mapNotNull null
            val classLoader = modManager.getModClassLoader(manifest.id)
            runCatching {
                val managerClass = classLoader.loadClass(manifest.manager!!)
                val method = managerClass.getMethod(
                    "onVoicePipelineEvent",
                    Context::class.java,
                    String::class.java,
                    Bundle::class.java,
                )
                val instance = managerClass.getMethod("getInstance", Context::class.java)
                    .invoke(null, context)
                    ?: return@runCatching null
                Subscriber(manifest.id, instance, method)
            }.onFailure {
                Log.w(TAG, "Failed to bind voice pipeline mod: ${manifest.id}", it)
            }.getOrNull()
        }

        cachedSubscribers = built
        return built
    }

    private fun hasExternalBroadcastReceivers(context: Context): Boolean {
        val intent = Intent(ACTION_VOICE_PIPELINE)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryBroadcastReceivers(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return matches.any { it.activityInfo.packageName != context.packageName }
    }

    private data class Subscriber(
        val modId: String,
        val instance: Any,
        val method: Method,
    )
}
