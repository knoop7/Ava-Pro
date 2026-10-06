package com.example.ava.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import com.example.ava.R
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.OverlayOrientation
import com.example.ava.services.OverlayZOrderCoordinator
import kotlin.math.max

/**
 * Snapshot window that hides the blank frame of a [WindowManager] remove+add restack.
 *
 * Same idea as [com.example.ava.services.QuickWakeFabService] / pinned 飞书:
 * draw the live view, float a pixel-identical not-touchable copy on top, restack
 * the real window underneath, drop the fake once the real one has painted.
 *
 * Ops are queued and last-write-wins per view so a coordinator pass
 * (clock → weather → vinyl → chrome) keeps its z-order. Enter/exit fades stay
 * on the real window — this only covers the restack hole.
 */
object OverlayRaiseCover {
    private const val TAG = "OverlayRaiseCover"
    private const val COVER_MAX_MS = 600L
    private const val QUEUE_CAP = 24
    /** Snapshot + extra overlay window: skip when opens are already bursting. */
    private const val COVER_COOLDOWN_MS = 180L
    private const val STORM_WINDOW_MS = 800L
    private const val STORM_COVER_CAP = 4

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Op>()
    private val idleRunnables = ArrayList<Runnable>()
    private var running: Op? = null
    private var lastCoverAt = 0L
    private var stormWindowStart = 0L
    private var stormCoverCount = 0

    private data class Op(
        val wm: WindowManager,
        val view: View,
        val params: WindowManager.LayoutParams,
        val restack: () -> Boolean,
        val onDone: ((Boolean) -> Unit)?,
    )

    /**
     * Run [restack] under a snapshot cover when the view is visibly painted.
     * Falls back to an immediate restack when a snapshot is useless (GONE,
     * zero size, hardware surface that [View.draw] cannot see).
     */
    fun run(
        windowManager: WindowManager?,
        view: View?,
        params: WindowManager.LayoutParams?,
        restack: () -> Boolean,
        onDone: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (windowManager == null || view == null || params == null || !view.isAttachedToWindow) {
            onDone?.invoke(false)
            return false
        }
        if (!shouldAttemptMask(view) || shouldSkipCover()) {
            val ok = restack()
            onDone?.invoke(ok)
            return ok
        }
        val op = Op(windowManager, view, params, restack, onDone)
        queue.removeAll { it.view === view }
        if (queue.size >= QUEUE_CAP) {
            val dropped = queue.removeFirst()
            val ok = dropped.restack()
            dropped.onDone?.invoke(ok)
        }
        queue.addLast(op)
        pump()
        return true
    }

    /** After every queued restack finishes — used so the mic climbs last. */
    fun whenIdle(runnable: Runnable) {
        if (idleRunnables.none { it === runnable }) {
            idleRunnables.add(runnable)
        }
        if (running == null && queue.isEmpty()) {
            mainHandler.post {
                if (running == null && queue.isEmpty()) drainIdle()
            }
        }
    }

    /** Drop a pending idle callback (overlay hide — a late restack only blanks). */
    fun cancelIdle(runnable: Runnable) {
        idleRunnables.removeAll { it === runnable }
    }

    private fun pump() {
        if (running != null) return
        val op = queue.removeFirstOrNull() ?: run {
            drainIdle()
            return
        }
        running = op
        val cover = addCover(op)
        if (cover == null) {
            finish(op, op.restack())
            return
        }
        val timeout = Runnable {
            if (running !== op) return@Runnable
            val ok = if (op.view.isAttachedToWindow) op.restack() else false
            dropCover(op.wm, cover)
            finish(op, ok)
        }
        mainHandler.postDelayed(timeout, COVER_MAX_MS)
        cover.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    cover.viewTreeObserver.removeOnPreDrawListener(this)
                    cover.post {
                        if (running !== op) return@post
                        mainHandler.removeCallbacks(timeout)
                        val live = op.view
                        if (!live.isAttachedToWindow) {
                            dropCover(op.wm, cover)
                            finish(op, false)
                            return@post
                        }
                        val ok = op.restack()
                        if (!ok || !live.isAttachedToWindow) {
                            dropCover(op.wm, cover)
                            finish(op, ok)
                            return@post
                        }
                        live.viewTreeObserver.addOnPreDrawListener(
                            object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    live.viewTreeObserver.removeOnPreDrawListener(this)
                                    live.post {
                                        dropCover(op.wm, cover)
                                        finish(op, ok)
                                    }
                                    return true
                                }
                            },
                        )
                    }
                    return true
                }
            },
        )
    }

    private fun finish(op: Op, ok: Boolean) {
        if (running === op) running = null
        op.onDone?.invoke(ok)
        pump()
    }

    private fun drainIdle() {
        if (running != null || queue.isNotEmpty() || idleRunnables.isEmpty()) return
        val pending = ArrayList(idleRunnables)
        idleRunnables.clear()
        for (r in pending) {
            try {
                r.run()
            } catch (e: Exception) {
                Log.w(TAG, "idle callback failed", e)
            }
        }
    }

    /**
     * Frequent overlay opens queue a snapshot window per restack. That extra
     * TYPE_APPLICATION_OVERLAY is what knocks a moving FAB out of WM.
     */
    private fun shouldSkipCover(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastCoverAt < COVER_COOLDOWN_MS) return true
        if (now - stormWindowStart > STORM_WINDOW_MS) {
            stormWindowStart = now
            stormCoverCount = 0
        }
        if (stormCoverCount >= STORM_COVER_CAP) return true
        lastCoverAt = now
        stormCoverCount++
        return false
    }

    private fun shouldAttemptMask(view: View): Boolean {
        return view.visibility == View.VISIBLE &&
            view.alpha > 0.02f &&
            view.width > 0 &&
            view.height > 0 &&
            view.isShown
    }

    private fun addCover(op: Op): ImageView? {
        val view = op.view
        val params = op.params
        val shot = snapshot(view) ?: return null
        val cover = ImageView(view.context).apply {
            setImageBitmap(shot)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val width = if (params.width > 0) params.width else view.width
        val height = if (params.height > 0) params.height else view.height
        val coverLp = WindowManager.LayoutParams(
            width,
            height,
            params.type,
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = params.gravity
            x = params.x
            y = params.y
            windowAnimations = R.style.AppWindowNoAnimation
            PlatformCapabilities.applyDisplayCutoutShortEdges(this)
            OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(this)
            OverlayOrientation.apply(this)
        }
        return try {
            op.wm.addView(cover, coverLp)
            cover
        } catch (e: Exception) {
            Log.w(TAG, "raise cover failed", e)
            shot.recycle()
            null
        }
    }

    private fun dropCover(wm: WindowManager, cover: ImageView) {
        val bitmap = (cover.drawable as? BitmapDrawable)?.bitmap
        runCatching { if (cover.isAttachedToWindow) wm.removeView(cover) }
        cover.setImageDrawable(null)
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun snapshot(view: View): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }.getOrNull()?.takeIf { !isEmptySnapshot(it) }
    }

    /**
     * Reject only a fully empty buffer (WebView / TextureView often draw nothing).
     * A mic disc, STT bubble or Esper sphere on a large transparent plate is a
     * few percent opaque — that still has to cover, or the raise hole is visible.
     */
    private fun isEmptySnapshot(bmp: Bitmap): Boolean {
        val stepX = max(1, bmp.width / 16)
        val stepY = max(1, bmp.height / 16)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (Color.alpha(bmp.getPixel(x, y)) > 8) return false
                x += stepX
            }
            y += stepY
        }
        bmp.recycle()
        return true
    }
}
