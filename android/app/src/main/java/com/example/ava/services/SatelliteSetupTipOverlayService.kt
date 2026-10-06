package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.ui.applyQuickEntityOverlayViewSetup
import com.example.ava.ui.applyQuickEntityOverlayWindowFlags
import com.example.ava.ui.components.SatelliteSetupTipKind
import com.example.ava.ui.components.SatelliteSetupTipOverlayContent

/**
 * Full-screen teaching tip:
 * - [SatelliteSetupTipKind.WakeConfig]: HA satellite wake-word wizard intercept
 * - [SatelliteSetupTipKind.HaServiceCalls]: ESPHome "allow HA actions" not enabled
 * - [SatelliteSetupTipKind.ConversationNoIntent]: HA default Assist `no_intent` stock reply
 * - [SatelliteSetupTipKind.PipelineConfig]: repeated permanent STT/TTS/agent pipeline errors
 */
class SatelliteSetupTipOverlayService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val viewModelStoreImpl = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = viewModelStoreImpl

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    /** Read from background threads via [isVisible]. */
    @Volatile
    private var isShowing = false
    private var contentVisible by mutableStateOf(false)
    private var tipKind by mutableStateOf(SatelliteSetupTipKind.WakeConfig)
    private var fadeGeneration = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayView()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        composeView = ComposeView(this).apply {
            applyQuickEntityOverlayViewSetup()
            setViewTreeLifecycleOwner(this@SatelliteSetupTipOverlayService)
            setViewTreeViewModelStoreOwner(this@SatelliteSetupTipOverlayService)
            setViewTreeSavedStateRegistryOwner(this@SatelliteSetupTipOverlayService)
            alpha = 0f
            setContent {
                if (contentVisible) {
                    SatelliteSetupTipOverlayContent(
                        tipKind = tipKind,
                        onGotIt = { hideOverlay() },
                    )
                }
            }
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            PlatformCapabilities.overlayWindowType(),
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            applyQuickEntityOverlayWindowFlags(notFocusable = false)
        }

        try {
            windowManager?.addView(composeView, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
            composeView?.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add satellite setup tip overlay", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayoutOverlay()
    }

    private fun relayoutOverlay() {
        val view = composeView ?: return
        val params = windowParams ?: return
        if (!view.isAttachedToWindow) return
        try {
            params.applyQuickEntityOverlayWindowFlags(notFocusable = false)
            windowManager?.updateViewLayout(view, params)
            androidx.core.view.ViewCompat.requestApplyInsets(view)
            view.requestLayout()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relayout satellite setup tip overlay", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                // Never swap content under a tip that is already on screen.
                if (!isShowing) tipKind = tipKindFromExtra(intent.getStringExtra(EXTRA_TIP_KIND))
                showOverlay()
            }
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (isShowing) return
        if (!PlatformCapabilities.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show tip: overlay permission missing")
            endYieldVoiceOverlays()
            stopSelf()
            return
        }
        // Tip owns the screen — drop Esper sphere / captions / wake ripple first.
        beginYieldVoiceOverlays(this)
        contentVisible = true
        isShowing = true
        when (tipKind) {
            SatelliteSetupTipKind.HaServiceCalls -> haServiceTipShownThisProcess = true
            SatelliteSetupTipKind.ConversationNoIntent -> {
                conversationNoIntentTipShownThisProcess = true
                // Lifetime once: consume as soon as the tip is on screen.
                markConversationNoIntentTipConsumed(this)
            }
            SatelliteSetupTipKind.WakeConfig,
            SatelliteSetupTipKind.PipelineConfig -> Unit
        }
        OverlayZOrderCoordinator.bringToFront(windowManager, composeView, windowParams, TAG)
        fadeIn()
    }

    private fun hideOverlay() {
        if (!isShowing) {
            endYieldVoiceOverlays()
            stopSelf()
            return
        }
        when (tipKind) {
            // Lifetime once.
            SatelliteSetupTipKind.ConversationNoIntent -> markConversationNoIntentTipConsumed(this)
            // Same tip may return after another 3-error streak.
            SatelliteSetupTipKind.PipelineConfig ->
                com.example.ava.esphome.voicesatellite.HaPipelineConfigErrorTracker.onTipDismissed()
            else -> Unit
        }
        isShowing = false
        fadeOut {
            contentVisible = false
            endYieldVoiceOverlays()
            stopSelf()
        }
    }

    private fun fadeIn() {
        val view = composeView ?: return
        val gen = ++fadeGeneration
        view.animate().cancel()
        view.animate().setListener(null)
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (gen != fadeGeneration) return
                    view.animate().setListener(null)
                    view.alpha = 1f
                }
            })
            .start()
    }

    private fun fadeOut(onEnd: () -> Unit) {
        val view = composeView
        if (view == null) {
            onEnd()
            return
        }
        val gen = ++fadeGeneration
        view.animate().cancel()
        view.animate().setListener(null)
        view.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    if (canceled || gen != fadeGeneration) return
                    view.visibility = View.GONE
                    view.alpha = 1f
                    onEnd()
                }
            })
            .start()
    }

    override fun onDestroy() {
        fadeGeneration++
        composeView?.animate()?.cancel()
        composeView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove tip overlay", e)
            }
        }
        composeView = null
        viewModelStoreImpl.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        instance = null
        endYieldVoiceOverlays()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SatelliteSetupTip"
        private const val FADE_MS = 160L
        const val ACTION_SHOW = "com.example.ava.action.SHOW_SATELLITE_SETUP_TIP"
        const val ACTION_HIDE = "com.example.ava.action.HIDE_SATELLITE_SETUP_TIP"
        const val EXTRA_TIP_KIND = "tip_kind"

        @Volatile
        private var instance: SatelliteSetupTipOverlayService? = null

        /**
         * Armed from [showKind] until tip fade-out / destroy so Esper captions cannot race
         * ahead of [isShowing] (startService is async).
         */
        @Volatile
        private var yieldVoiceOverlays = false

        /** Once the HA-service tip has been put on screen, do not re-prompt this process. */
        @Volatile
        private var haServiceTipShownThisProcess = false

        /** Once the Assist no_intent tip has been put on screen, do not re-prompt this process. */
        @Volatile
        private var conversationNoIntentTipShownThisProcess = false

        private const val TIP_PREFS = "ava_prefs"
        /** Lifetime dismiss / consume flag for the Assist no_intent teaching tip. */
        private const val KEY_CONVERSATION_NO_INTENT_TIP_CONSUMED =
            "satellite_setup_tip_conversation_no_intent_consumed"

        fun isVisible(): Boolean = instance?.isShowing == true

        /** True while a teaching tip is pending/visible — Esper sphere & wake ripple must stand down. */
        fun shouldYieldVoiceOverlays(): Boolean =
            yieldVoiceOverlays || isVisible()

        fun bringToFrontIfVisible() {
            val svc = instance ?: return
            if (!svc.isShowing) return
            OverlayZOrderCoordinator.bringToFront(
                svc.windowManager,
                svc.composeView,
                svc.windowParams,
                TAG,
            )
        }

        private fun beginYieldVoiceOverlays(context: Context) {
            yieldVoiceOverlays = true
            try {
                FloatingWindowService.hide(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide floating window for tip", e)
            }
            try {
                WakeRippleService.hide(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide wake ripple for tip", e)
            }
        }

        private fun endYieldVoiceOverlays() {
            yieldVoiceOverlays = false
        }

        private fun isConversationNoIntentTipConsumed(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(TIP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CONVERSATION_NO_INTENT_TIP_CONSUMED, false)

        private fun markConversationNoIntentTipConsumed(context: Context) {
            conversationNoIntentTipShownThisProcess = true
            context.applicationContext
                .getSharedPreferences(TIP_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CONVERSATION_NO_INTENT_TIP_CONSUMED, true)
                .apply()
        }

        private fun tipKindFromExtra(raw: String?): SatelliteSetupTipKind =
            when (raw) {
                SatelliteSetupTipKind.HaServiceCalls.name -> SatelliteSetupTipKind.HaServiceCalls
                SatelliteSetupTipKind.ConversationNoIntent.name ->
                    SatelliteSetupTipKind.ConversationNoIntent
                SatelliteSetupTipKind.PipelineConfig.name -> SatelliteSetupTipKind.PipelineConfig
                else -> SatelliteSetupTipKind.WakeConfig
            }

        /** Show on every HA config intercept. Skips only if already on screen. */
        fun maybeShowOnConfigIntercept(context: Context) {
            showKind(context, SatelliteSetupTipKind.WakeConfig, skipIfVisible = true)
        }

        /**
         * One-shot per process while HA silently rejects service calls.
         * Cleared when any action response later proves the gate is open.
         */
        fun maybeShowHaServiceCallsTip(context: Context) {
            if (haServiceTipShownThisProcess) {
                Log.d(TAG, "Skip HA service tip: already shown this process")
                return
            }
            showKind(context, SatelliteSetupTipKind.HaServiceCalls, skipIfVisible = true)
        }

        fun clearHaServiceTipShown() {
            haServiceTipShownThisProcess = false
        }

        /**
         * Lifetime once: when HA default Assist returns a stock `no_intent` reply.
         * Softly points at other conversation agents; ha_claw is only an optional path.
         * Consumed on first show / "Got it" so it never reappears.
         */
        fun maybeShowConversationNoIntentTip(context: Context) {
            if (conversationNoIntentTipShownThisProcess ||
                isConversationNoIntentTipConsumed(context)
            ) {
                Log.d(TAG, "Skip conversation no_intent tip: already shown/dismissed")
                return
            }
            // Another tip on screen — leave lifetime flag alone so we can try later.
            if (isVisible()) {
                Log.d(TAG, "Skip conversation no_intent tip: another tip visible")
                return
            }
            if (!PlatformCapabilities.canDrawOverlays(context)) {
                Log.d(TAG, "Skip conversation no_intent tip: no overlay permission")
                return
            }
            // Arm immediately so rapid duplicate TTS/INTENT paths cannot queue a second show.
            conversationNoIntentTipShownThisProcess = true
            showKind(context, SatelliteSetupTipKind.ConversationNoIntent, skipIfVisible = true)
        }

        /**
         * After 3 consecutive upstream misses (HA line / subscribe / timeout / config).
         * Not lifetime-once: "Got it" clears the streak; the same tip can return later.
         */
        fun maybeShowPipelineConfigTip(context: Context) {
            if (isVisible()) {
                Log.d(TAG, "Skip pipeline config tip: another tip visible")
                return
            }
            if (!PlatformCapabilities.canDrawOverlays(context)) {
                Log.d(TAG, "Skip pipeline config tip: no overlay permission")
                return
            }
            com.example.ava.esphome.voicesatellite.HaPipelineConfigErrorTracker.onTipShown()
            showKind(context, SatelliteSetupTipKind.PipelineConfig, skipIfVisible = true)
        }

        fun show(context: Context) {
            showKind(context, SatelliteSetupTipKind.WakeConfig, skipIfVisible = false)
        }

        private fun showKind(
            context: Context,
            kind: SatelliteSetupTipKind,
            skipIfVisible: Boolean,
        ) {
            if (skipIfVisible && isVisible()) return
            if (!PlatformCapabilities.canDrawOverlays(context)) {
                Log.d(TAG, "Skip tip: no overlay permission kind=$kind")
                return
            }
            // Arm before startService so TTS caption racing the same utterance cannot pop Esper.
            beginYieldVoiceOverlays(context)
            val intent = Intent(context, SatelliteSetupTipOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_TIP_KIND, kind.name)
            }
            // Probe timeout can land while the app is being torn down.
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start tip overlay kind=$kind", e)
                if (!isVisible()) endYieldVoiceOverlays()
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, SatelliteSetupTipOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }
}
