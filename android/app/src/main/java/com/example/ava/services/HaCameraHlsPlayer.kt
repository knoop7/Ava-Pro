package com.example.ava.services

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.example.ava.homeassistant.HaMediaAuth
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Plays a Home Assistant `camera/stream` HLS playlist onto a hidden
 * [TextureView], then copies frames for the canvas tile.
 *
 * Do not route video into [android.media.ImageReader]: HA streams often
 * produce YUV (`0x7fa30c06`) while an RGBA ImageReader is `0x1`, and
 * `acquireLatestImage` then kills the grab thread. TextureView accepts
 * the decoder's format; [TextureView.getBitmap] copies into ARGB.
 *
 * Grab size / interval drop automatically under CPU or memory pressure.
 * Any grab failure is swallowed; repeated pressure aborts HLS so the
 * caller can fall back to MJPEG.
 */
@OptIn(UnstableApi::class)
internal class HaCameraHlsPlayer {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var isPlaying: Boolean = false
        private set

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var host: ViewGroup? = null
    private var appContext: Context? = null
    private var resumePlay: ((Boolean) -> Unit)? = null
    private var lastGrabMs = 0L
    private var firstFrameWatch: Runnable? = null
    private var grabScratch: Bitmap? = null
    private var lastPublished: Bitmap? = null
    private val framePool = CameraFramePool()
    private val blitCanvas = Canvas()
    private var tierIndex = 0
    private var slowStreak = 0
    private var failStreak = 0
    private var okStreak = 0
    private val grabbing = AtomicBoolean(false)

    suspend fun play(
        context: Context,
        surfaceHost: ViewGroup?,
        url: String,
        onFrame: (Bitmap) -> Unit,
    ): Boolean {
        stop()
        if (surfaceHost == null) {
            Log.w(TAG, "hls skip: overlay host not attached")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            mainHandler.post {
                if (!cont.isActive) return@post
                val done: (Boolean) -> Unit = { ok ->
                    if (cont.isActive) {
                        try {
                            cont.resume(ok)
                        } catch (_: Throwable) {
                        }
                    }
                }
                try {
                    startOnMain(context.applicationContext, surfaceHost, url, onFrame, done)
                } catch (t: Throwable) {
                    Log.w(TAG, "hls start failed", t)
                    resumePlay = null
                    releaseOnMain()
                    done(false)
                }
            }
            cont.invokeOnCancellation { stop() }
        }
    }

    fun stop() {
        isPlaying = false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseOnMain()
        } else {
            mainHandler.post { releaseOnMain() }
        }
    }

    private fun startOnMain(
        context: Context,
        surfaceHost: ViewGroup,
        url: String,
        onFrame: (Bitmap) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        releaseOnMain()
        resumePlay = onDone
        host = surfaceHost
        appContext = context
        resetPressure()
        val tv = TextureView(context).apply {
            isOpaque = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        textureView = tv
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                try {
                    grabFrame(tv, onFrame)
                } catch (t: Throwable) {
                    noteFail("updated", t)
                }
            }
        }
        surfaceHost.addView(
            tv,
            0,
            FrameLayout.LayoutParams(2, 2),
        )

        val headers = linkedMapOf<String, String>()
        HaMediaAuth.bearerHeader()?.let { headers["Authorization"] = it }
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
        val source = HlsMediaSource.Factory(http)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(url))
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_000, 5_000, 250, 500)
                    .build(),
            )
            .build()
        player = exo
        exo.volume = 0f
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        exo.setVideoTextureView(tv)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "hls error ${error.errorCodeName}: ${error.message}")
                finish(false)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finish(true)
            }
        })
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        isPlaying = true
        armFirstFrameWatch()
        Log.i(TAG, "hls playing")
    }

    private fun armFirstFrameWatch() {
        firstFrameWatch?.let { mainHandler.removeCallbacks(it) }
        val watch = Runnable {
            if (isPlaying && okStreak == 0) {
                abortPressure("no-frame")
            }
        }
        firstFrameWatch = watch
        mainHandler.postDelayed(watch, FIRST_FRAME_MS)
    }

    private fun grabFrame(tv: TextureView, onFrame: (Bitmap) -> Unit) {
        if (!isPlaying || !tv.isAvailable) return
        if (memoryPressure()) {
            dropTier("memory")
            if (tierIndex >= TIERS.lastIndex && failStreak >= FAIL_ABORT) {
                abortPressure("memory")
                return
            }
        }
        val tier = TIERS[tierIndex.coerceIn(0, TIERS.lastIndex)]
        val now = SystemClock.uptimeMillis()
        if (now - lastGrabMs < tier.intervalMs) return
        if (!grabbing.compareAndSet(false, true)) return
        lastGrabMs = now
        var published: Bitmap? = null
        try {
            val started = SystemClock.uptimeMillis()
            val scratch = scratchArgb(tier.width, tier.height) ?: return
            val grabbed = tv.getBitmap(scratch)
            val elapsed = SystemClock.uptimeMillis() - started
            if (grabbed == null || grabbed.isRecycled) return
            val compressed = framePool.acquire(tier.width, tier.height, listOf(lastPublished)) ?: return
            blitCanvas.setBitmap(compressed)
            blitCanvas.drawBitmap(grabbed, 0f, 0f, null)
            blitCanvas.setBitmap(null)
            published = compressed
            if (elapsed >= SLOW_GRAB_MS) {
                slowStreak++
                if (slowStreak >= SLOW_DROP) dropTier("slow ${elapsed}ms")
            } else {
                slowStreak = 0
            }
            if (isPlaying) {
                lastPublished = published
                onFrame(published)
                published = null
                noteOk()
            }
        } catch (t: Throwable) {
            noteFail("grab", t)
        } finally {
            grabbing.set(false)
        }
    }

    private fun scratchArgb(width: Int, height: Int): Bitmap? {
        val existing = grabScratch
        if (existing != null &&
            !existing.isRecycled &&
            existing.width == width &&
            existing.height == height
        ) {
            return existing
        }
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { created ->
                if (existing != null && !existing.isRecycled) {
                    try {
                        existing.recycle()
                    } catch (_: Throwable) {
                    }
                }
                grabScratch = created
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun noteOk() {
        failStreak = 0
        okStreak++
        if (okStreak == 1) {
            firstFrameWatch?.let { mainHandler.removeCallbacks(it) }
            firstFrameWatch = null
        }
        if (okStreak >= OK_PROMOTE && tierIndex > 0) {
            tierIndex--
            okStreak = 0
            Log.i(TAG, "hls pressure ease tier=${tierLabel()}")
        }
    }

    private fun noteFail(where: String, error: Throwable) {
        failStreak++
        okStreak = 0
        Log.w(TAG, "hls $where failed streak=$failStreak: ${error.message}")
        if (error is OutOfMemoryError || failStreak == 1) {
            dropTier(where)
        }
        if (failStreak >= FAIL_ABORT || error is OutOfMemoryError) {
            abortPressure(where)
        }
    }

    private fun dropTier(reason: String) {
        if (tierIndex >= TIERS.lastIndex) return
        tierIndex++
        slowStreak = 0
        okStreak = 0
        Log.w(TAG, "hls pressure drop $reason -> ${tierLabel()}")
    }

    private fun abortPressure(reason: String) {
        if (!isPlaying) return
        Log.w(TAG, "hls pressure abort $reason tier=${tierLabel()}, fallback")
        finish(false)
    }

    private fun memoryPressure(): Boolean {
        return try {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            if (used > (rt.maxMemory() * HEAP_PRESSURE_RATIO).toLong()) return true
            val am = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.lowMemory || info.availMem < LOW_AVAIL_BYTES
        } catch (_: Throwable) {
            false
        }
    }

    private fun resetPressure() {
        tierIndex = 0
        slowStreak = 0
        failStreak = 0
        okStreak = 0
        lastGrabMs = 0L
        grabbing.set(false)
        firstFrameWatch?.let { mainHandler.removeCallbacks(it) }
        firstFrameWatch = null
    }

    private fun tierLabel(): String {
        val tier = TIERS[tierIndex.coerceIn(0, TIERS.lastIndex)]
        return "${tier.width}x${tier.height}@${tier.intervalMs}ms"
    }

    private fun finish(ok: Boolean) {
        val cb = resumePlay
        resumePlay = null
        isPlaying = false
        releaseOnMain()
        try {
            cb?.invoke(ok)
        } catch (t: Throwable) {
            Log.w(TAG, "hls finish callback failed", t)
        }
    }

    private fun releaseOnMain() {
        val cb = resumePlay
        resumePlay = null
        isPlaying = false
        try {
            player?.clearVideoTextureView(textureView)
        } catch (_: Throwable) {
        }
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        val tv = textureView
        textureView = null
        if (tv != null) {
            try {
                tv.surfaceTextureListener = null
                (tv.parent as? ViewGroup)?.removeView(tv)
            } catch (_: Throwable) {
            }
        }
        host = null
        appContext = null
        grabbing.set(false)
        firstFrameWatch?.let { mainHandler.removeCallbacks(it) }
        firstFrameWatch = null
        val scratch = grabScratch
        grabScratch = null
        if (scratch != null && !scratch.isRecycled) {
            try {
                scratch.recycle()
            } catch (_: Throwable) {
            }
        }
        framePool.release(listOf(lastPublished))
        lastPublished = null
        if (cb != null) {
            try {
                cb(true)
            } catch (_: Throwable) {
            }
        }
    }

    private data class GrabTier(val width: Int, val height: Int, val intervalMs: Long)

    companion object {
        private const val TAG = "HaCameraHls"
        private const val SURFACE_WIDTH = 960
        private const val SURFACE_HEIGHT = 540
        private const val FIRST_FRAME_MS = 1500L
        private const val SLOW_GRAB_MS = 40L
        private const val SLOW_DROP = 3
        private const val FAIL_ABORT = 6
        private const val OK_PROMOTE = 40
        private const val HEAP_PRESSURE_RATIO = 0.85
        private const val LOW_AVAIL_BYTES = 48L * 1024L * 1024L
        private val TIERS = listOf(
            GrabTier(SURFACE_WIDTH, SURFACE_HEIGHT, 66L),
            GrabTier(SURFACE_WIDTH, SURFACE_HEIGHT, 90L),
            GrabTier(SURFACE_WIDTH, SURFACE_HEIGHT, 120L),
            GrabTier(SURFACE_WIDTH, SURFACE_HEIGHT, 160L),
        )
    }
}
