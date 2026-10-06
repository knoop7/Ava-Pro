package com.example.ava.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.ava.mods.ModOverlayZOrderBridge
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.VoiceAccentColors
import com.example.ava.ui.applyQuickEntityOverlayViewSetup
import com.example.ava.ui.views.VoiceStateOverlayView
import com.example.ava.ui.views.WakeRippleView
class WakeRippleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var stateOverlayView: VoiceStateOverlayView? = null
    private var rippleView: WakeRippleView? = null
    private var overlayVisible = false
    private var sessionAccentColor = VoiceAccentColors.WAKE_WORD_1
    private var sessionWakeWordIndex = 0

    companion object {
        private const val TAG = "WakeRippleService"
        private const val EXTRA_COLOR = "color"
        private const val EXTRA_GAIN = "level_gain"
        private const val EXTRA_WAKE_INDEX = "wake_word_index"
        private const val EXTRA_OPACITY = "opacity_mul"

        @Volatile
        private var instance: WakeRippleService? = null

        @Volatile
        private var activeStateView: VoiceStateOverlayView? = null

        @Volatile
        private var previewActive = false

        private fun readPlayerSettings(context: Context): PlayerSettings {
            // Hot path (wake events on main thread): in-memory snapshot, no runBlocking IO.
            return try {
                PlayerSettingsStore(context.applicationContext.playerSettingsStore).getCached()
            } catch (_: Exception) {
                PlayerSettings()
            }
        }

        private fun isRippleEnabled(context: Context): Boolean =
            readPlayerSettings(context).enableVoiceRippleEffect &&
                // Quick Wake button turns paint their own status on the button.
                !VoiceSatelliteService.isQuickWakeSessionActive()

        private fun isEdgeGlowEnabled(context: Context): Boolean =
            readPlayerSettings(context).enableVoiceEdgeGlow &&
                !VoiceSatelliteService.isQuickWakeSessionActive()

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        fun feedAudioEnergy(level: Float) {
            val view = activeStateView ?: return
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                view.feedAudioEnergy(level)
            } else {
                view.post { view.feedAudioEnergy(level) }
            }
        }

        fun show(context: Context, color: Int = VoiceAccentColors.WAKE_WORD_1) {
            if (!isRippleEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW"
                putExtra(EXTRA_COLOR, color)
            }
            context.startService(intent)
        }

        fun showAt(context: Context, x: Float, y: Float, color: Int = VoiceAccentColors.WAKE_WORD_1) {
            if (!isRippleEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_AT"
                putExtra("x", x)
                putExtra("y", y)
                putExtra(EXTRA_COLOR, color)
            }
            context.startService(intent)
        }

        fun showWakeSession(context: Context, wakeColor: Int) {
            ScreensaverService.notifySmartAodInterrupt()
            QuickEntityOverlayService.notifySmartAodInterrupt()
            if (!isRippleEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_WAKE_SESSION"
                putExtra("wake_color", wakeColor)
            }
            context.startService(intent)
        }

        fun showSpeaking(context: Context) {
            if (!isEdgeGlowEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_SPEAKING"
            }
            context.startService(intent)
        }

        fun showSpeaking(context: Context, color: Int, wakeWordIndex: Int = 0) {
            if (!isEdgeGlowEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_SPEAKING"
                putExtra(EXTRA_COLOR, color)
                putExtra(EXTRA_WAKE_INDEX, wakeWordIndex)
            }
            context.startService(intent)
        }

        fun showListening(context: Context, color: Int, wakeWordIndex: Int = 0) {
            if (!isEdgeGlowEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_LISTENING"
                putExtra(EXTRA_COLOR, color)
                putExtra(EXTRA_WAKE_INDEX, wakeWordIndex)
            }
            context.startService(intent)
        }

        fun showProcessing(context: Context) {
            if (!isEdgeGlowEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_PROCESSING"
            }
            context.startService(intent)
        }

        fun showProcessing(context: Context, color: Int) {
            if (!isEdgeGlowEnabled(context)) return
            if (SatelliteSetupTipOverlayService.shouldYieldVoiceOverlays()) return
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_SHOW_PROCESSING"
                putExtra(EXTRA_COLOR, color)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            // Always allow hide so a mid-session toggle still tears the overlay down.
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_HIDE"
            }
            context.startService(intent)
        }

        /**
         * Settings slider: show listening glow so gain / opacity are visible.
         * If a real voice session is on screen, only retune those — don't hijack energy.
         */
        fun previewEdgeGlowLevel(
            context: Context,
            color: Int,
            gain: Float,
            opacityMul: Float = 1f,
        ) {
            val clamped = PlayerSettings.clampEdgeGlowLevelGain(gain)
            val opacityClamped = opacityMul.coerceIn(
                PlayerSettings.MIN_EDGE_GLOW_OPACITY_MUL,
                1f,
            )
            val svc = instance
            if (svc != null && !previewActive && svc.stateOverlayView?.isShowing() == true) {
                svc.stateOverlayView?.setLevelGain(clamped)
                svc.stateOverlayView?.setOpacityMul(opacityClamped)
                return
            }
            if (!isEdgeGlowEnabled(context) && !previewActive) return
            if (svc != null) {
                svc.runLevelPreview(color, clamped, opacityClamped)
                return
            }
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_PREVIEW_LEVEL"
                putExtra(EXTRA_COLOR, color)
                putExtra(EXTRA_GAIN, clamped)
                putExtra(EXTRA_OPACITY, opacityClamped)
            }
            context.startService(intent)
        }

        fun endEdgeGlowLevelPreview(context: Context) {
            if (!previewActive) return
            val svc = instance
            if (svc != null) {
                svc.endLevelPreview()
                return
            }
            val intent = Intent(context, WakeRippleService::class.java).apply {
                action = "ACTION_END_PREVIEW_LEVEL"
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_SHOW" -> {
                val color = intent.getIntExtra(EXTRA_COLOR, VoiceAccentColors.WAKE_WORD_1)
                rememberSessionAccent(color)
                showWakeRipple(color)
            }
            "ACTION_SHOW_AT" -> {
                val x = intent.getFloatExtra("x", -1f)
                val y = intent.getFloatExtra("y", -1f)
                val color = intent.getIntExtra(EXTRA_COLOR, VoiceAccentColors.WAKE_WORD_1)
                rememberSessionAccent(color)
                showWakeRippleAt(x, y, color)
            }
            "ACTION_SHOW_WAKE_SESSION" -> {
                val wakeColor = intent.getIntExtra("wake_color", VoiceAccentColors.WAKE_WORD_1)
                showWakeSession(wakeColor)
            }
            "ACTION_SHOW_SPEAKING" -> {
                val color = resolveAccentColor(intent)
                resolveWakeWordIndex(intent)
                clearLevelPreviewDrive()
                showSpeakingOverlay(color)
            }
            "ACTION_SHOW_LISTENING" -> {
                val color = resolveAccentColor(intent)
                resolveWakeWordIndex(intent)
                clearLevelPreviewDrive()
                showListeningOverlay(color)
            }
            "ACTION_SHOW_PROCESSING" -> {
                val color = resolveAccentColor(intent)
                clearLevelPreviewDrive()
                showProcessingOverlay(color)
            }
            "ACTION_PREVIEW_LEVEL" -> {
                val color = resolveAccentColor(intent)
                val gain = PlayerSettings.clampEdgeGlowLevelGain(
                    intent.getFloatExtra(EXTRA_GAIN, PlayerSettings.DEFAULT_EDGE_GLOW_LEVEL_GAIN)
                )
                val opacity = intent.getFloatExtra(EXTRA_OPACITY, 1f).coerceIn(
                    PlayerSettings.MIN_EDGE_GLOW_OPACITY_MUL,
                    1f,
                )
                runLevelPreview(color, gain, opacity)
            }
            "ACTION_END_PREVIEW_LEVEL" -> {
                endLevelPreview()
                return START_NOT_STICKY
            }
            "ACTION_HIDE" -> {
                previewActive = false
                hideOverlay()
                return START_NOT_STICKY
            }
        }
        return if (overlayVisible) START_STICKY else START_NOT_STICKY
    }

    private fun readDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = windowManager?.defaultDisplay ?: return metrics
        // getRealMetrics needs API 17+; minSdk 21 always has it — keep getMetrics fallback for safety.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
        }
        return metrics
    }

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
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            PlatformCapabilities.applyDisplayCutoutShortEdges(this)
            OverlayOrientation.apply(this)
        }
    }

    private fun ensureOverlay(): Boolean {
        if (overlayContainer != null) return true

        val metrics = readDisplayMetrics()
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)

        val childLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val stateView = VoiceStateOverlayView(applicationContext).apply {
            setDisplaySize(screenWidth, screenHeight)
            fitsSystemWindows = false
        }
        val wakeView = WakeRippleView(applicationContext).apply {
            setDisplaySize(screenWidth, screenHeight)
            fitsSystemWindows = false
        }
        val container = FrameLayout(applicationContext).apply {
            clipChildren = false
            addView(stateView, childLayoutParams)
            addView(wakeView, childLayoutParams)
            // Edge-to-edge under status bar / navigation bar (same as QuickEntity / FloatingWindow).
            applyQuickEntityOverlayViewSetup()
        }

        return try {
            val params = createLayoutParams()
            windowManager?.addView(container, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            overlayParams = params
            layoutOverlayChildren(container, stateView, wakeView, screenWidth, screenHeight)
            overlayContainer = container
            stateOverlayView = stateView
            activeStateView = stateView
            rippleView = wakeView
            overlayVisible = true
            requestModOverlayReassert()
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "add overlay failed", e)
            false
        }
    }

    private fun layoutOverlayChildren(
        container: FrameLayout,
        stateView: VoiceStateOverlayView,
        wakeView: WakeRippleView,
        screenWidth: Int,
        screenHeight: Int
    ) {
        container.layout(0, 0, screenWidth, screenHeight)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY)
        for (child in listOf(stateView, wakeView)) {
            child.measure(widthSpec, heightSpec)
            child.layout(0, 0, screenWidth, screenHeight)
        }
    }

    private fun rememberSessionAccent(color: Int) {
        sessionAccentColor = color
    }

    private fun rememberWakeWordIndex(index: Int) {
        sessionWakeWordIndex = index.coerceIn(0, 1)
    }

    private fun resolveAccentColor(intent: Intent): Int {
        return if (intent.hasExtra(EXTRA_COLOR)) {
            intent.getIntExtra(EXTRA_COLOR, sessionAccentColor).also { rememberSessionAccent(it) }
        } else {
            sessionAccentColor
        }
    }

    private fun resolveWakeWordIndex(intent: Intent): Int {
        return if (intent.hasExtra(EXTRA_WAKE_INDEX)) {
            intent.getIntExtra(EXTRA_WAKE_INDEX, sessionWakeWordIndex).also {
                rememberWakeWordIndex(it)
            }
        } else {
            sessionWakeWordIndex
        }
    }

    private fun applyStoredLevelGain() {
        val settings = readPlayerSettings(this)
        stateOverlayView?.setLevelGain(settings.edgeGlowLevelGainForWakeWord(sessionWakeWordIndex))
        stateOverlayView?.setOpacityMul(settings.edgeGlowOpacityMulForWakeWord(sessionWakeWordIndex))
    }

    private fun clearLevelPreviewDrive() {
        previewActive = false
        stateOverlayView?.setPreviewLevelDrive(false)
    }

    private fun runLevelPreview(color: Int, gain: Float, opacityMul: Float) {
        previewActive = true
        if (!ensureOverlay()) {
            previewActive = false
            return
        }
        rememberSessionAccent(color)
        stateOverlayView?.setLevelGain(gain)
        stateOverlayView?.setOpacityMul(opacityMul)
        stateOverlayView?.setPreviewLevelDrive(true)
        stateOverlayView?.showListening(color)
        overlayVisible = true
    }

    private fun endLevelPreview() {
        if (!previewActive) return
        stateOverlayView?.setPreviewLevelDrive(false)
        rippleView?.stopRipple()
        val stateView = stateOverlayView
        if (stateView != null && overlayContainer != null) {
            stateView.hidePreview {
                removeOverlayContainer()
                stopSelf()
            }
        } else {
            removeOverlayContainer()
            stopSelf()
        }
    }

    private fun showListeningOverlay(color: Int) {
        if (!ensureOverlay()) return
        rememberSessionAccent(color)
        applyStoredLevelGain()
        stateOverlayView?.showListening(color)
    }

    private fun showProcessingOverlay(color: Int) {
        if (!ensureOverlay()) return
        applyStoredLevelGain()
        stateOverlayView?.showProcessing(color)
    }

    private fun showSpeakingOverlay(color: Int) {
        if (!ensureOverlay()) return
        applyStoredLevelGain()
        stateOverlayView?.showSpeaking(color)
    }

    private fun showWakeRipple(color: Int) {
        if (!ensureOverlay()) return
        rippleView?.setWakeColor(color)
        rippleView?.startWakeBurst()
    }

    private fun showWakeRippleAt(x: Float, y: Float, color: Int) {
        if (!ensureOverlay()) return
        rippleView?.setWakeColor(color)
        if (x >= 0f && y >= 0f) {
            rippleView?.startRippleAt(x, y)
        } else {
            rippleView?.startWakeBurst()
        }
    }

    private fun showWakeSession(wakeColor: Int) {
        if (!ensureOverlay()) return
        rememberSessionAccent(wakeColor)
        rippleView?.setWakeColor(wakeColor)
        rippleView?.startWakeBurst()
    }

    private fun removeOverlayContainer() {
        overlayContainer?.let { container ->
            try {
                windowManager?.removeView(container)
            } catch (_: Exception) {
            }
        }
        overlayContainer = null
        overlayParams = null
        stateOverlayView = null
        activeStateView = null
        rippleView = null
        overlayVisible = false
        previewActive = false
        sessionAccentColor = VoiceAccentColors.WAKE_WORD_1
        sessionWakeWordIndex = 0
    }

    private fun hideOverlayViewImmediate() {
        rippleView?.stopRipple()
        stateOverlayView?.hideImmediate()
        removeOverlayContainer()
    }

    private fun hideOverlay() {
        rippleView?.stopRipple()
        val stateView = stateOverlayView
        if (stateView != null && overlayContainer != null) {
            stateView.hide {
                removeOverlayContainer()
                stopSelf()
            }
        } else {
            removeOverlayContainer()
            stopSelf()
        }
    }

    /**
     * Wake ripple is often the newest overlay window added during a voice session.
     * Reassert only top-tier mod overlays (screen tint, etc.) — not media layers that
     * intentionally sit below the wake animation ([overlay_below_voice]).
     */
    private fun requestModOverlayReassert() {
        ModOverlayZOrderBridge.reassertTopOverlays(applicationContext)
    }

    override fun onDestroy() {
        if (overlayVisible) {
            hideOverlayViewImmediate()
        }
        instance = null
        super.onDestroy()
    }

    private fun bringToFront() {
        if (!overlayVisible) return
        OverlayZOrderCoordinator.bringToFront(windowManager, overlayContainer, overlayParams, TAG)
    }
}
