package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.applyQuickEntityOverlayViewSetup
import com.example.ava.ui.applyQuickEntityOverlayWindowFlags
import com.example.ava.ui.components.VoiceMessageOverlayContent
import com.example.ava.utils.TouchSoundHelper
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceHaController
import com.example.ava.voice.AvaVoiceSessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceMessageOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val viewModelStoreImpl = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = viewModelStoreImpl

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayHost: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isFeatureEnabled = false
    private var isOverlayVisible = false
    private var isSuppressedByCall = false
    private var overlayHideGeneration = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.VOICE_MESSAGE,
            showing = { shouldShowOverlay() },
            host = { overlayHost },
            apply = { frame -> applyLayerSplit(frame) },
        )
        createOverlayView()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun createOverlayView() {
        val content = ComposeView(this).apply {
            applyQuickEntityOverlayViewSetup()
            setViewTreeLifecycleOwner(this@VoiceMessageOverlayService)
            setViewTreeViewModelStoreOwner(this@VoiceMessageOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VoiceMessageOverlayService)

            setContent {
                val devices by AvaVoiceDiscovery.devices.collectAsState()
                val settings by playerSettingsStore.data.collectAsState(initial = null)
                // Hide peers that explicitly advertise voiceMessaging=0 (master off).
                // Legacy beacons without the field stay listed for compatibility.
                val voiceDevices = remember(devices) {
                    devices.filter { it.offersVoiceMessaging }
                }
                VoiceMessageOverlayContent(
                    devices = voiceDevices,
                    localDeviceId = AvaVoiceDiscovery.localId(),
                    localDeviceName = AvaVoiceDiscovery.localName(),
                    voiceMessageDelayMinutes = settings?.voiceMessageDelayMinutes ?: 0,
                    enableVoiceOverlayIntercom = settings?.enableVoiceOverlayIntercom ?: true,
                    enableVoiceOverlayCall = settings?.enableVoiceOverlayCall ?: true,
                    onDismiss = {
                        TouchSoundHelper.playClick(this@VoiceMessageOverlayService)
                        serviceScope.launch {
                            playerSettingsStore.updateData {
                                it.copy(enableVoiceMessageOverlayVisible = false)
                            }
                        }
                    }
                )
            }
        }
        composeView = content

        val host = FrameLayout(this).apply {
            // WindowRecomposer looks for owners on the WM root, not the ComposeView
            // child. A GONE host without these tags crashes on first attach.
            setViewTreeLifecycleOwner(this@VoiceMessageOverlayService)
            setViewTreeViewModelStoreOwner(this@VoiceMessageOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VoiceMessageOverlayService)
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            visibility = View.GONE
        }
        overlayHost = host

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            com.example.ava.platform.PlatformCapabilities.overlayWindowType(),
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            applyQuickEntityOverlayWindowFlags(notFocusable = false)
        }

        try {
            windowManager?.addView(host, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add voice overlay", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayoutOverlay()
    }

    /**
     * Keep the same [ComposeView] across orientation changes.
     * Remove+recreate would dispose call UI state and hang up via DisposableEffect.
     */
    private fun relayoutOverlay() {
        val view = overlayHost ?: return
        val params = windowParams ?: return
        if (!view.isAttachedToWindow) return
        if (OverlayLayerSplit.isPaneView(view)) {
            OverlayLayerSplit.sync()
            androidx.core.view.ViewCompat.requestApplyInsets(view)
            return
        }
        try {
            params.applyQuickEntityOverlayWindowFlags(notFocusable = false)
            windowManager?.updateViewLayout(view, params)
            androidx.core.view.ViewCompat.requestApplyInsets(view)
            view.requestLayout()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relayout voice overlay", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> applyState(visible = true, sendEnabled = true)
            ACTION_HIDE -> applyState(visible = false, sendEnabled = false)
            ACTION_SET_CALL_SUPPRESSED -> applyCallSuppressed(
                intent.getBooleanExtra(EXTRA_SUPPRESSED, false)
            )
            ACTION_SET_VISIBLE -> {
                val visible = intent.getBooleanExtra(EXTRA_VISIBLE, true)
                applyState(visible = visible, sendEnabled = true)
            }
            ACTION_TOGGLE -> {
                if (isFeatureEnabled) {
                    val nextVisible = !isOverlayVisible
                    applyState(visible = nextVisible, sendEnabled = true)
                    serviceScope.launch {
                        playerSettingsStore.updateData {
                            it.copy(enableVoiceMessageOverlayVisible = nextVisible)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun applyState(visible: Boolean, sendEnabled: Boolean) {
        if (!visible) OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        val wasVisible = isOverlayVisible
        isFeatureEnabled = sendEnabled
        isOverlayVisible = visible
        // UDP lifecycle is owned by VoiceSatelliteService.syncVoiceMessageServices — not here.
        updateOverlayVisibility()

        if (wasVisible && !visible && AvaVoiceHaController.hasActiveSession()) {
            serviceScope.launch {
                AvaVoiceHaController.hangUp(applicationContext)
            }
        }
    }

    /** Hide overlay without re-enabling the feature flag (used when tearing down HA sessions). */
    private fun setOverlayVisibleOnly(visible: Boolean) {
        isOverlayVisible = visible
        updateOverlayVisibility()
    }

    private fun applyCallSuppressed(suppressed: Boolean) {
        isSuppressedByCall = suppressed
        updateOverlayVisibility()
    }

    private fun shouldShowOverlay(): Boolean =
        isFeatureEnabled && isOverlayVisible && !isSuppressedByCall

    private fun bringToFront() {
        val view = overlayHost ?: return
        val params = windowParams ?: return
        if (!view.isAttachedToWindow) return
        // Size the pane first. A later restack would pull this window over the one opened earlier.
        OverlayLayerSplit.sync()
        // Never remove+add this window. It is focusable Compose: detaching it
        // disposes the call, and doing that while the browser window is also
        // in WindowManager deadlocks the two focused overlays on the main thread.
        if (OverlayLayerSplit.isPaneView(view) || WebViewService.isAnyBrowserOverlayActive()) return
        try {
            if (AvaVoiceSessionHub.hasLiveCallSessions()) return
            OverlayZOrderCoordinator.bringToFront(windowManager, view, params, TAG)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring voice overlay to front", e)
        }
    }

    private fun updateOverlayVisibility() {
        val shouldShow = shouldShowOverlay()
        val view = overlayHost ?: return
        if (shouldShow) {
            OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.VOICE_MESSAGE)
            val becomingVisible = view.visibility != View.VISIBLE || view.alpha < 0.99f
            if (becomingVisible) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            OverlayLayerSplit.sync()
            if (becomingVisible) {
                bringToFront()
                fadeInOverlay(view)
            }
        } else {
            OverlayLayerSplit.sync()
            fadeOutOverlay(view)
        }
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = overlayHost ?: return
        val params = windowParams ?: return
        if (frame == null && !shouldShowOverlay() && host.visibility == View.VISIBLE) return
        if (frame != null) {
            // Focusable overlay would otherwise eat touches on the other pane.
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.VOICE_MESSAGE,
            windowManager,
            host,
            params,
            frame,
        )
    }

    private fun fadeInOverlay(view: View) {
        if (view.visibility == View.VISIBLE && view.alpha >= 0.99f) return
        overlayHideGeneration++
        view.animate().cancel()
        view.animate().setListener(null)
        view.alpha = 0f
        view.visibility = View.VISIBLE
        OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.VOICE_MESSAGE) {
            if (view.visibility != View.VISIBLE) return@fadeWhenPaneReady
            view.animate()
                .alpha(1f)
                .setDuration(FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun fadeOutOverlay(view: View) {
        val gen = ++overlayHideGeneration
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
                    if (gen == overlayHideGeneration && !shouldShowOverlay()) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.animate().setListener(null)
                    }
                }
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    if (canceled || gen != overlayHideGeneration) return
                    view.visibility = View.GONE
                    view.alpha = 1f
                }
            })
            .start()
    }

    override fun onDestroy() {
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.VOICE_MESSAGE)
        super.onDestroy()
        if (AvaVoiceHaController.hasActiveSession()) {
            // Never runBlocking a network hang-up on the main thread in onDestroy —
            // a slow peer would ANR. hangUp is idempotent; async completion is fine.
            AvaVoiceHaController.hangUpAsync(applicationContext)
        }
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try {
            overlayHost?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove voice overlay", e)
        }
        overlayHost = null
        composeView = null
        instance = null
    }

    companion object {
        private const val TAG = "VoiceMessageOverlay"
        private const val FADE_MS = 230L
        private var instance: VoiceMessageOverlayService? = null

        const val ACTION_SHOW = "com.example.ava.SHOW_VOICE_MESSAGE"
        const val ACTION_HIDE = "com.example.ava.HIDE_VOICE_MESSAGE"
        const val ACTION_TOGGLE = "com.example.ava.TOGGLE_VOICE_MESSAGE"
        const val ACTION_SET_VISIBLE = "com.example.ava.SET_VOICE_MESSAGE_VISIBLE"
        const val ACTION_SET_CALL_SUPPRESSED = "com.example.ava.SET_VOICE_MESSAGE_CALL_SUPPRESSED"
        const val EXTRA_VISIBLE = "visible"
        const val EXTRA_SUPPRESSED = "suppressed"

        fun isRunning(): Boolean = instance != null

        fun isOverlayShowing(): Boolean = instance?.let { it.isFeatureEnabled && it.isOverlayVisible } == true

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        fun show(context: Context) {
            val svc = instance
            if (svc != null && svc.isFeatureEnabled && svc.isOverlayVisible) return
            val intent = Intent(context, VoiceMessageOverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, VoiceMessageOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun toggle(context: Context) {
            val intent = Intent(context, VoiceMessageOverlayService::class.java).apply {
                action = ACTION_TOGGLE
            }
            context.startService(intent)
        }

        fun setVisible(context: Context, visible: Boolean) {
            val intent = Intent(context, VoiceMessageOverlayService::class.java).apply {
                action = ACTION_SET_VISIBLE
                putExtra(EXTRA_VISIBLE, visible)
            }
            context.startService(intent)
        }

        fun setCallSuppressed(context: Context, suppressed: Boolean) {
            if (instance == null) return
            val intent = Intent(context, VoiceMessageOverlayService::class.java).apply {
                action = ACTION_SET_CALL_SUPPRESSED
                putExtra(EXTRA_SUPPRESSED, suppressed)
            }
            context.startService(intent)
        }

        fun showHaSession(context: Context) {
            setCallSuppressed(context, false)
            show(context)
        }

        fun dismissHaSession(context: Context) {
            val service = instance
            if (service != null) {
                service.isSuppressedByCall = false
                service.setOverlayVisibleOnly(false)
            }
            // No running service — nothing on screen to dismiss. Do NOT
            // startService here just to hide: async hang-up from onDestroy
            // would otherwise resurrect the service right after teardown.
        }

        fun ensureEnabled(context: Context, visible: Boolean) {
            if (visible) {
                show(context)
            } else {
                val intent = Intent(context, VoiceMessageOverlayService::class.java).apply {
                    action = ACTION_SET_VISIBLE
                    putExtra(EXTRA_VISIBLE, false)
                }
                context.startService(intent)
            }
        }
    }
}
