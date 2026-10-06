package com.example.ava.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Listens for hardware / system STREAM_MUSIC volume changes on API 21–36.
 *
 * Android has no public volume-change API. This monitor combines:
 * 1. [Settings.System.CONTENT_URI] ContentObserver (most reliable across OEMs)
 * 2. `volume_music` system setting ContentObserver (some devices)
 * 3. [VOLUME_CHANGED_ACTION] broadcast (hardware keys on many builds)
 *
 * Portal/A64 devices also call [com.example.ava.services.VoiceSatelliteService.notifyHardwareMusicVolumeChanged]
 * directly from [com.example.ava.services.VolumeControlService] because keys may not reach this monitor.
 */
class DeviceMusicVolumeMonitor(
    context: Context,
    private val onMusicVolumeChanged: (normalizedLevel: Float) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val contentResolver = appContext.contentResolver

    private var systemSettingsObserver: ContentObserver? = null
    private var volumeBroadcastReceiver: BroadcastReceiver? = null
    private var started = false

    @Volatile
    private var suppressCallbacks = 0
    /** Time-based suppress for programmatic STREAM_MUSIC writes (more reliable than count alone). */
    @Volatile
    private var suppressUntilElapsedRealtime = 0L
    private var lastDispatchedLevel: Float? = null
    private var pendingDispatch: Runnable? = null

    fun start() {
        if (started) return
        started = true
        lastDispatchedLevel = currentNormalizedLevel()

        val observer = object : ContentObserver(handler) {
            override fun deliverSelfNotifications(): Boolean = false

            override fun onChange(selfChange: Boolean) {
                scheduleDispatch("content_observer")
            }
        }
        systemSettingsObserver = observer
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("volume_music"),
            false,
            observer,
        )

        volumeBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != VOLUME_CHANGED_ACTION) return
                val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.USE_DEFAULT_STREAM_TYPE)
                val aliasType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE_ALIAS, streamType)
                if (!isMusicRelatedStream(streamType) && !isMusicRelatedStream(aliasType)) return
                scheduleDispatch("broadcast")
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                volumeBroadcastReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(volumeBroadcastReceiver, filter)
        }
        Log.d(TAG, "Started device music volume monitor")
    }

    fun stop() {
        if (!started) return
        started = false
        pendingDispatch?.let(handler::removeCallbacks)
        pendingDispatch = null
        systemSettingsObserver?.let { contentResolver.unregisterContentObserver(it) }
        systemSettingsObserver = null
        volumeBroadcastReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        volumeBroadcastReceiver = null
        lastDispatchedLevel = null
        Log.d(TAG, "Stopped device music volume monitor")
    }

    fun currentNormalizedLevel(): Float = readNormalizedLevel(appContext)

    /** Skip the next [count] volume callbacks (e.g. when app sets STREAM_MUSIC programmatically). */
    fun suppressNextCallbacks(count: Int) {
        if (count > 0) suppressCallbacks += count
    }

    /** Ignore observer/broadcast echoes for [durationMs] after a programmatic setStreamVolume. */
    fun suppressForMs(durationMs: Long) {
        if (durationMs <= 0L) return
        val until = android.os.SystemClock.elapsedRealtime() + durationMs
        if (until > suppressUntilElapsedRealtime) {
            suppressUntilElapsedRealtime = until
        }
    }

    private fun scheduleDispatch(source: String) {
        pendingDispatch?.let(handler::removeCallbacks)
        pendingDispatch = Runnable {
            pendingDispatch = null
            if (android.os.SystemClock.elapsedRealtime() < suppressUntilElapsedRealtime) {
                Log.v(TAG, "Suppressed volume callback from $source (time window)")
                return@Runnable
            }
            if (suppressCallbacks > 0) {
                suppressCallbacks--
                Log.v(TAG, "Suppressed volume callback from $source")
                return@Runnable
            }
            val level = currentNormalizedLevel()
            val previous = lastDispatchedLevel
            if (previous != null && kotlin.math.abs(previous - level) < LEVEL_EPSILON) {
                return@Runnable
            }
            lastDispatchedLevel = level
            Log.d(TAG, "Device music volume changed to ${(level * 100).toInt()}% ($source)")
            onMusicVolumeChanged(level)
        }
        handler.post(pendingDispatch!!)
    }

    private fun isMusicRelatedStream(streamType: Int): Boolean {
        return when (streamType) {
            AudioManager.STREAM_MUSIC,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "DeviceMusicVolumeMonitor"
        private const val LEVEL_EPSILON = 0.001f

        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_TYPE_ALIAS = "android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS"

        fun readNormalizedLevel(context: Context): Float {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVol <= 0) return 0f
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            return (curVol.toFloat() / maxVol).coerceIn(0f, 1f)
        }

        /** Snap [level] to a STREAM_MUSIC step and write it. Returns the actual normalized level. */
        fun writeNormalizedLevel(context: Context, level: Float): Float {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVol <= 0) return 0f
            val target = ((level.coerceIn(0f, 1f) * maxVol) + 0.5f).toInt().coerceIn(0, maxVol)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            return (target.toFloat() / maxVol).coerceIn(0f, 1f)
        }
    }
}
