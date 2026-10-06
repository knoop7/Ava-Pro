package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.ui.VoiceAccentColors
import com.example.ava.ui.views.ChorusYieldGlowView

/**
 * Light dim for 一呼百应 losers, plus three static dots at the bottom center.
 * Dot color is the user's wake-word accent. Level only changes dot size.
 * Fade in / fade out only. Touches pass through.
 */
class ChorusWakeBlurService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var glowView: ChorusYieldGlowView? = null
    private var overlayVisible = false
    private var fadingOut = false
    private var fadeAnimator: ValueAnimator? = null
    private var accentColor = VoiceAccentColors.WAKE_WORD_1
    private var pendingHide: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { fadeOutOverlay() }

    companion object {
        private const val TAG = "ChorusWakeBlur"
        private const val ACTION_SHOW = "ACTION_SHOW"
        private const val ACTION_FADE_OUT = "ACTION_FADE_OUT"
        private const val ACTION_HIDE_IMMEDIATE = "ACTION_HIDE_IMMEDIATE"
        private const val EXTRA_ACCENT = "accent_color"
        private const val AUTO_HIDE_MS = 90_000L
        private const val FADE_IN_MS = 680L
        private const val FADE_OUT_MS = 720L
        private const val DIM_COLOR = 0x470A0C10.toInt()

        @Volatile
        private var instance: ChorusWakeBlurService? = null

        fun show(context: Context, accentColor: Int = VoiceAccentColors.WAKE_WORD_1) {
            if (!PlatformCapabilities.canDrawOverlays(context)) {
                Log.e(TAG, "cannot show chorus dim: overlay permission denied")
                return
            }
            if (instance?.overlayVisible == true) return
            try {
                context.startService(
                    Intent(context, ChorusWakeBlurService::class.java)
                        .setAction(ACTION_SHOW)
                        .putExtra(EXTRA_ACCENT, accentColor),
                )
            } catch (e: Exception) {
                Log.e(TAG, "cannot start chorus dim service: ${e.message}")
            }
        }

        fun fadeOut(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.mainHandler.post { svc.fadeOutOverlay() }
                return
            }
            try {
                context.startService(
                    Intent(context, ChorusWakeBlurService::class.java).setAction(ACTION_FADE_OUT),
                )
            } catch (e: Exception) {
                Log.w(TAG, "fadeOut start failed: ${e.message}")
            }
        }

        fun hide(context: Context) {
            fadeOut(context)
        }

        fun hideImmediate(context: Context) {
            val svc = instance ?: return
            svc.mainHandler.post { svc.hideOverlayImmediate() }
        }

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        /**
         * A yielding device heard a fresh peer claim (the user re-woke the winner).
         * Restart the dim in place: cancel any teardown in flight, reset the auto-hide
         * clock, fade back to full. No-op while the overlay is not up, so claims heard
         * mid-arbitration (before this device has actually lost) cannot dim the screen.
         */
        fun restartForPeerWake() {
            val svc = instance ?: return
            svc.mainHandler.post {
                if (svc.overlayContainer != null) {
                    Log.d(TAG, "peer re-wake: restarting chorus dim")
                    svc.showOverlay()
                }
            }
        }

        fun feedAudioEnergy(level: Float) {
            val svc = instance ?: return
            if (!svc.overlayVisible || svc.fadingOut) return
            val glow = svc.glowView ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                glow.feedLevel(level)
            } else {
                glow.post { glow.feedLevel(level) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_ACCENT, accentColor)?.let { color ->
            accentColor = color
            glowView?.setAccentColor(color)
        }
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_FADE_OUT -> fadeOutOverlay()
            ACTION_HIDE_IMMEDIATE -> hideOverlayImmediate()
        }
        return if (overlayVisible) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideRunnable)
        hideOverlayImmediate()
        instance = null
        super.onDestroy()
    }

    private fun showOverlay() {
        fadingOut = false
        cancelPendingHide()
        val alreadyAttached = overlayContainer != null
        if (!ensureOverlay()) return
        glowView?.setAccentColor(accentColor)
        Log.d(TAG, "showing chorus dim + bottom dots")
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, AUTO_HIDE_MS)
        if (alreadyAttached) {
            OverlayZOrderCoordinator.bringToFront(windowManager, overlayContainer, overlayParams, TAG)
        }
        fadeIn()
    }

    private fun fadeOutOverlay() {
        if (fadingOut) return
        if (overlayContainer == null) {
            hideOverlayImmediate()
            return
        }
        fadingOut = true
        mainHandler.removeCallbacks(hideRunnable)
        val container = overlayContainer ?: run {
            hideOverlayImmediate()
            return
        }
        cancelFadeAnimator()
        val from = container.alpha.coerceIn(0f, 1f)
        fadeAnimator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = FADE_OUT_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { container.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.alpha = 0f
                    container.visibility = View.INVISIBLE
                    // Posted, not inline: removing the window inside the animation
                    // callback can re-enter WindowManager. Tracked so a re-show
                    // landing in this gap does not get torn down by a stale hide.
                    val hide = Runnable { hideOverlayImmediate() }
                    pendingHide = hide
                    mainHandler.post(hide)
                }
            })
            start()
        }
    }

    private fun hideOverlayImmediate() {
        fadingOut = false
        mainHandler.removeCallbacks(hideRunnable)
        cancelPendingHide()
        cancelFadeAnimator()
        removeOverlayContainer()
        stopSelf()
    }

    private fun fadeIn() {
        val container = overlayContainer ?: return
        cancelFadeAnimator()
        container.visibility = View.VISIBLE
        // A restart can land mid fade-out: resume from the current alpha instead of
        // blinking to 0. A fresh overlay starts at 0 (set in ensureOverlay).
        fadeAnimator = ValueAnimator.ofFloat(container.alpha.coerceIn(0f, 1f), 1f).apply {
            duration = FADE_IN_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { container.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun cancelPendingHide() {
        pendingHide?.let { mainHandler.removeCallbacks(it) }
        pendingHide = null
    }

    private fun cancelFadeAnimator() {
        fadeAnimator?.removeAllListeners()
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    private fun ensureOverlay(): Boolean {
        if (overlayContainer != null) return true
        val dim = View(applicationContext).apply {
            setBackgroundColor(DIM_COLOR)
            isClickable = false
            isFocusable = false
        }
        val glow = ChorusYieldGlowView(applicationContext).apply {
            setAccentColor(accentColor)
        }
        val container = FrameLayout(applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            fitsSystemWindows = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            alpha = 0f
            addView(dim, matchParent())
            addView(glow, matchParent())
        }
        return try {
            val params = createLayoutParams()
            windowManager?.addView(container, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            overlayParams = params
            overlayContainer = container
            glowView = glow
            overlayVisible = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add chorus dim overlay", e)
            false
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = PlatformCapabilities.overlayWindowType()
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            @Suppress("DEPRECATION")
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            PlatformCapabilities.applyDisplayCutoutShortEdges(this)
            OverlayOrientation.apply(this)
        }
    }

    private fun removeOverlayContainer() {
        val container = overlayContainer
        if (container != null) {
            try {
                windowManager?.removeView(container)
            } catch (_: Exception) {
            }
        }
        overlayContainer = null
        overlayParams = null
        glowView = null
        overlayVisible = false
    }

    private fun bringToFront() {
        if (!overlayVisible || fadingOut) return
        OverlayZOrderCoordinator.bringToFront(windowManager, overlayContainer, overlayParams, TAG)
    }
}
