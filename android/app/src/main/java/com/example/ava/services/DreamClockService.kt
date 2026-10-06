package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar
import java.util.Locale
import com.example.ava.R
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.ui.FrostedGlassHost
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import com.example.ava.utils.AmbientBitmapBlur
import androidx.core.graphics.ColorUtils
import com.example.ava.settings.DreamClockFace
import com.example.ava.settings.DreamClockFlipBackdrop
import com.example.ava.settings.DreamClockFlipCardColors
import com.example.ava.settings.DreamClockFlipFont
import com.example.ava.settings.DreamClockFlipImage
import com.example.ava.settings.DreamClockFlipStyle
import com.example.ava.settings.DreamClockSeason
import com.example.ava.ui.haptic.OverlayHaptics
import com.example.ava.ui.haptic.travelTickChanged
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.weather.WeatherData
import com.example.ava.weather.WeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin


class DreamClockService : Service() {

    private var windowManager: WindowManager? = null
    private var clockView: DreamClockView? = null
    private var frostedHost: FrostedGlassHost? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isClockEnabled = false
    private var isClockVisible = false
    private var clockHideGeneration = 0
    @Volatile private var isServiceRunning = true

    override fun onBind(intent: Intent?): IBinder? = null

    private val weatherListener: (WeatherData) -> Unit = { weather ->
        handler.post {
            clockView?.updateWeather(weather.temperature, weather.condition)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.DREAM_CLOCK,
            showing = { isOverlayShowing() },
            host = { frostedHost ?: clockView },
            apply = { frame -> applyLayerSplit(frame) },
        )
        LiquidGlass.ensureLoaded(this)
        WeatherService.addWeatherListener(weatherListener)
        // When the user flips the Liquid Glass switch while the clock is already showing,
        // immediately clear any stale RenderEffect so the blur disappears in real time.
        serviceScope.launch {
            SettingsStyleSession.liquidGlassEnabled.collect { enabled ->
                if (!enabled) {
                    val target = frostedHost ?: clockView ?: return@collect
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        target.setRenderEffect(null)
                    }
                    frostedHost?.setFrostedGlassVisible(false)
                }
            }
        }
        serviceScope.launch {
            playerSettingsStore.data.collectLatest { settings ->
                applyClockSettings(settings)
            }
        }
    }
    
    private fun bringToFront() {
        if (!isClockEnabled || !isClockVisible) return
        OverlayLayerSplit.sync()
        // AOD cover is a child of this window. remove+add recreates the surface
        // and punches a full-bright hole; keep the plate and only raise the FAB.
        val skipRestack = clockView?.isSmartAodCovering() == true
        val root = frostedHost ?: clockView
        if (!skipRestack && !OverlayLayerSplit.isPaneView(root)) {
            OverlayZOrderCoordinator.bringToFront(windowManager, root, windowParams, TAG)
        }
        OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
    }
    
    // Held as a field so the chain can be cancelled; scheduling an anonymous lambda
    // leaves nothing for removeCallbacks to match.
    private val weatherRunnable = Runnable { fetchWeather() }

    private fun fetchWeather() {
        serviceScope.launch {
            try {
                val settings = playerSettingsStore.data.first()
                val weatherEntity = settings.haWeatherEntity
                if (weatherEntity.isBlank()) {
                    return@launch
                }
                val weather = WeatherService.getCachedWeather()
                if (weather != null) {
                    clockView?.updateWeather(weather.temperature, weather.condition)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch weather: ${e.message}")
            }
        }

        scheduleWeatherFetch(60 * 1000L)
    }

    private fun scheduleWeatherFetch(delayMs: Long) {
        handler.removeCallbacks(weatherRunnable)
        if (!isServiceRunning || !isClockVisible) return
        handler.postDelayed(weatherRunnable, delayMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createClockView() {
        val displayMetrics = resources.displayMetrics
        val clockSize = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels) + 25

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        
        clockView = DreamClockView(this, clockSize).apply {
            onFacePicked = { face ->
                serviceScope.launch {
                    playerSettingsStore.updateData { it.copy(dreamClockFace = face.storageKey) }
                }
            }
            onFlipStylePicked = { style ->
                serviceScope.launch {
                    playerSettingsStore.updateData { it.copy(dreamClockFlipStyle = style.storageKey) }
                }
            }
            onFlipFontPicked = { font ->
                serviceScope.launch {
                    playerSettingsStore.updateData { it.copy(dreamClockFlipFont = font.storageKey) }
                }
            }
            onFlipSecondsTapped = { show ->
                serviceScope.launch {
                    playerSettingsStore.updateData { it.copy(dreamClockFlipShowSeconds = show) }
                }
            }
            onFlipTwelveHourTapped = { on ->
                serviceScope.launch {
                    playerSettingsStore.updateData { it.copy(dreamClockFlip12Hour = on) }
                }
            }
            onTimerHaCall = { service, extra ->
                serviceScope.launch {
                    val entityId = playerSettingsStore.data.first().dreamClockTimerEntityId.trim()
                    if (entityId.startsWith("timer.")) {
                        VoiceSatelliteService.getInstance()?.callHaService(service, entityId, extra)
                    }
                }
            }
            setOnTouchListener { _, event -> handleOverlayTouch(event) }
        }
        // Face must be the stored one before the first paint. The default is FILL;
        // an async apply after addView flashes 满幅 / 翻页 over 机械 (or the reverse).
        runCatching {
            applyClockSettings(PlayerSettingsStore(playerSettingsStore).getCached())
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    // Service windows are software-rendered unless this is set; the
                    // gradient backdrops and RenderEffect blur both need the GPU.
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // CENTER + SHORT_EDGES + NO_LIMITS shifts MATCH_PARENT when a punch-hole
            // makes the frame asymmetric (same bug VinylCoverService documents).
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            OverlayOrientation.apply(this)
        }

        val host = FrostedGlassHost(this)
        clockView?.let { host.addContentView(it) }
        DashboardOverlayChrome.attach(host, DashboardOverlayChrome.Kind.DREAM_CLOCK)
        frostedHost = host

        try {
            windowManager?.addView(host, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
            host.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create clock window", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                Log.d(TAG, "ACTION_SHOW received, canDrawOverlays=${com.example.ava.platform.PlatformCapabilities.canDrawOverlays(this)}")
                ensureShown()
                Log.d(TAG, "Clock shown, clockView=${clockView != null}, visibility=${(frostedHost ?: clockView)?.visibility}")
            }
            ACTION_VOICE_TIMER -> {
                if (!ensureShown()) return
                val op = intent.getStringExtra(EXTRA_TIMER_OP).orEmpty()
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                if (op == "start" && durationMs > 0L) {
                    serviceScope.launch {
                        playerSettingsStore.updateData {
                            it.copy(
                                enableDreamClockVisible = true,
                                dreamClockFace = DreamClockFace.FLIP.storageKey,
                            )
                        }
                    }
                }
                handler.post {
                    when (op) {
                        "start" -> if (durationMs > 0L) clockView?.voiceStartTimer(durationMs)
                        "pause" -> clockView?.voicePauseTimer()
                        "resume" -> clockView?.voiceResumeTimer()
                        "cancel" -> clockView?.voiceCancelTimer()
                    }
                }
            }
            ACTION_HIDE -> {
                isClockEnabled = false
                hideClock()
                DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.DREAM_CLOCK)
            }
            ACTION_TOGGLE -> {
                if (isClockEnabled) {
                    toggleClock()
                }
            }
            ACTION_SET_VISIBLE -> {
                val visible = intent.getBooleanExtra(EXTRA_VISIBLE, true)
                val hostVisible = (frostedHost ?: clockView)?.visibility == View.VISIBLE
                if (visible && isClockEnabled && !isClockVisible) {
                    DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.DREAM_CLOCK)
                    showClock()
                } else if (!visible && (isClockVisible || hostVisible)) {
                    hideClock()
                    DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.DREAM_CLOCK)
                }
            }
            "com.example.ava.ACTION_BRING_TO_FRONT" -> {
                bringToFront()
            }
        }
    }
    
    /** @return false when overlay permission is missing. */
    private fun ensureShown(): Boolean {
        if (!com.example.ava.platform.PlatformCapabilities.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays, returning")
            return false
        }
        if (clockView == null) {
            Log.d(TAG, "Creating clock view")
            createClockView()
        }
        val alreadyShown = isClockEnabled && isClockVisible &&
            (frostedHost ?: clockView)?.visibility == View.VISIBLE
        isClockEnabled = true
        isClockVisible = true
        DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.DREAM_CLOCK)
        if (!alreadyShown) {
            showClock()
        }
        return true
    }

    private fun applyClockSettings(settings: PlayerSettings) {
        val view = clockView ?: return
        view.applyFace(
            DreamClockFace.fromStored(settings.dreamClockFace),
            DreamClockFlipStyle.fromStored(settings.dreamClockFlipStyle),
            DreamClockSeason.fromStored(settings.dreamClockSeason),
            settings.dreamClockFlipShowSeconds,
            DreamClockFlipFont.fromStored(settings.dreamClockFlipFont),
            settings.dreamClockFlip12Hour,
        )
        view.applySettingsLock(settings.dreamClockSettingsLocked)
        view.timerEntityBound = settings.dreamClockTimerEntityId.trim().startsWith("timer.")
        view.timerSoundEnabled = settings.dreamClockTimerSoundEnabled
        view.timerSoundUri = settings.dreamClockTimerSound
        view.applySmartAod(
            settings.dreamClockSmartAodEnabled,
            settings.dreamClockSmartAodTimeoutSeconds,
            settings.dreamClockSmartAodMaskPercent,
        )
        DashboardOverlayChrome.syncDreamClockLock(settings.dreamClockSettingsLocked)
    }

    private fun showClock() {
        isClockVisible = true
        OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.DREAM_CLOCK)
        // The two clocks replace each other. Drop the simple clock out of the
        // pair first, then size this window, then play the entrance.
        ScreensaverService.retireFromSplit()
        val host = frostedHost ?: clockView
        var waits = 0
        val open = object : Runnable {
            override fun run() {
                if (!isClockVisible) return
                if (host != null && host.isAttachedToWindow && host.width <= 1 && waits < 4) {
                    waits += 1
                    host.post(this)
                    return
                }
                OverlayLayerSplit.sync()
                clockView?.setShowClock(true)
                clockView?.startClock()
                scheduleWeatherFetch(1000)
                clockView?.clearAod(animated = false)
                OverlayLayerSplit.runAfterColdStart {
                    if (!isClockVisible) return@runAfterColdStart
                    OverlayLayerSplit.sync()
                    host?.let { fadeInClock(it) }
                    clockView?.revealChromeOnColdStart()
                    clockView?.playEntrance()
                    clockView?.scheduleAodEnter()
                    ScreensaverService.setVisible(this@DreamClockService, false)
                }
            }
        }
        if (host != null && host.isAttachedToWindow && host.width <= 1) {
            host.post(open)
        } else {
            open.run()
        }
    }
    
    private fun hideClock() {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        clockView?.clearAod(animated = false)
        clockView?.stopClock()
        isClockVisible = false
        OverlayLayerSplit.sync()
        handler.removeCallbacks(weatherRunnable)
        (frostedHost ?: clockView)?.let { fadeOutClock(it) }
    }

    private var blurAnimator: android.animation.ValueAnimator? = null

    /** Blur radius max→0 alongside the alpha fade (StandBy's soft reveal).
     *  API 31+: hardware-accelerated RenderEffect.
     *  API 21-30: falls back to BlurView through [FrostedGlassHost].
     *  Radius follows the Liquid Glass intensity; skipped when Liquid Glass is off —
     *  but any stale RenderEffect from a previous glass-on session is always cleared. */
    private fun animateBlur(v: View, from: Float, to: Float, durationMs: Long) {
        blurAnimator?.cancel()
        if (!LiquidGlass.enabled) {
            // Clear any RenderEffect left from when glass was on, so the view
            // doesn't stay blurry after the user turns the toggle off.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setRenderEffect(null)
            }
            frostedHost?.setFrostedGlassVisible(false)
            return
        }
        // Callers pass the legacy 32px endpoint; rescale to the user's intensity.
        val maxRadius = LiquidGlass.revealBlurRadiusPx()
        val scale = maxRadius / 32f
        @Suppress("NAME_SHADOWING") val from = from * scale
        @Suppress("NAME_SHADOWING") val to = to * scale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
                duration = durationMs
                addUpdateListener { anim ->
                    val radius = anim.animatedValue as Float
                    if (radius < 0.5f) {
                        v.setRenderEffect(null)
                    } else {
                        v.setRenderEffect(
                            android.graphics.RenderEffect.createBlurEffect(
                                radius, radius, android.graphics.Shader.TileMode.CLAMP
                            )
                        )
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (to < 0.5f) v.setRenderEffect(null)
                    }
                })
                start()
            }
            return
        }
        val host = frostedHost ?: return
        blurAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val radius = anim.animatedValue as Float
                if (radius < 0.5f) {
                    host.setFrostedGlassVisible(false)
                } else {
                    host.setFrostedGlassVisible(
                        visible = true,
                        blurRadius = radius.coerceIn(1f, LiquidGlass.viewBlurRadiusPx().coerceAtLeast(1f)),
                        tintColor = 0x00000000,
                    )
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (to < 0.5f) host.setFrostedGlassVisible(false)
                }
            })
            start()
        }
    }

    private fun fadeInClock(v: View) {
        clockHideGeneration++
        v.animate().cancel()
        v.animate().setListener(null)
        v.alpha = 0f
        v.visibility = View.VISIBLE
        OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.DREAM_CLOCK) {
            if (!isClockVisible || v.visibility != View.VISIBLE) return@fadeWhenPaneReady
            animateBlur(v, 32f, 0f, 380)
            v.animate().alpha(1f).setDuration(230)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun fadeOutClock(v: View) {
        val gen = ++clockHideGeneration
        v.animate().cancel()
        v.animate().setListener(null)
        animateBlur(v, 0f, 32f, 380)
        v.animate().alpha(0f).setDuration(230)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                    if (gen == clockHideGeneration && !isClockVisible) {
                        v.visibility = View.GONE
                        v.alpha = 1f
                        v.animate().setListener(null)
                    }
                }
                override fun onAnimationEnd(animation: Animator) {
                    v.animate().setListener(null)
                    if (canceled || gen != clockHideGeneration) return
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
            })
            .start()
    }
    
    
    private fun toggleClock() {
        toggleClockDisplay()
    }
    
    
    private fun switchToWeather() {
        
        serviceScope.launch {
            try {
                val settings = playerSettingsStore.data.first()
                
                if (settings.enableWeatherOverlay) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        hide(this@DreamClockService)
                        WeatherOverlayService.show(this@DreamClockService)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check weather settings: ${e.message}")
            }
        }
    }
    
    private fun toggleClockDisplay() {
        if (!isClockEnabled) return
        
        if (isClockVisible) {
            hideClock()
            serviceScope.launch {
                playerSettingsStore.updateData { it.copy(enableDreamClockVisible = false) }
            }
        } else {
            showClock()
            serviceScope.launch {
                playerSettingsStore.updateData { it.copy(enableDreamClockVisible = true) }
            }
        }
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = frostedHost ?: clockView ?: return
        // Exit fade is still up. Expanding it back to full screen covers the entrance.
        if (frame == null && !isClockVisible && host.visibility == View.VISIBLE) return
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.DREAM_CLOCK,
            windowManager,
            host,
            windowParams,
            frame,
        )
    }

    override fun onDestroy() {
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.DREAM_CLOCK)
        super.onDestroy()
        DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.DREAM_CLOCK)
        isServiceRunning = false
        WeatherService.removeWeatherListener(weatherListener)
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        clockView?.stopClock()
        frostedHost?.release()
        try {
            val root = frostedHost ?: clockView
            root?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove clock window", e)
        }
        frostedHost = null
        clockView = null
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "DreamClockService"
        private var instance: DreamClockService? = null
        
        const val ACTION_SHOW = "com.example.ava.SHOW_CLOCK"
        const val ACTION_HIDE = "com.example.ava.HIDE_CLOCK"
        const val ACTION_TOGGLE = "com.example.ava.TOGGLE_CLOCK"
        const val ACTION_VOICE_TIMER = "com.example.ava.VOICE_TIMER"
        const val EXTRA_TIMER_OP = "timer_op"
        const val EXTRA_DURATION_MS = "duration_ms"

        data class TimerSnapshot(
            val remainingMs: Long = 0L,
            val running: Boolean = false,
            val paused: Boolean = false,
            val ringing: Boolean = false,
        )

        fun timerEnabled(app: Context): Boolean =
            runCatching {
                com.example.ava.settings.PlayerSettingsStore(app.applicationContext.playerSettingsStore)
                    .getCached().enableDreamClock
            }.getOrDefault(false)

        fun timerSnapshot(): TimerSnapshot {
            val view = instance?.clockView
            return TimerSnapshot(
                remainingMs = view?.voiceRemainingMs() ?: 0L,
                running = view?.voiceIsRunning() == true,
                paused = view?.voiceIsPaused() == true,
                ringing = view?.voiceIsFinishedHold() == true,
            )
        }

        fun voiceTimer(app: Context, op: String, durationMs: Long = 0L) {
            val intent = Intent(app, DreamClockService::class.java).apply {
                action = ACTION_VOICE_TIMER
                putExtra(EXTRA_TIMER_OP, op)
                if (durationMs > 0L) putExtra(EXTRA_DURATION_MS, durationMs)
            }
            app.startService(intent)
        }

        fun bringToFrontStatic() {
            instance?.bringToFront()
        }

        fun retireFromSplit() {
            instance?.isClockVisible = false
        }

        fun isOverlayShowing(): Boolean = instance?.let { it.isClockEnabled && it.isClockVisible } == true

        fun isSmartAodCovering(): Boolean =
            isOverlayShowing() && instance?.clockView?.isSmartAodCovering() == true

        fun setSettingsLocked(locked: Boolean) {
            instance?.clockView?.applySettingsLock(locked)
        }

        /** HA `timer.*` state push: active / paused / idle. */
        fun updateTimerState(state: String) {
            instance?.handler?.post { instance?.clockView?.onHaTimerState(state) }
        }

        /** HA `finishes_at` push (ISO-8601 or blank/"None" when idle/paused). */
        fun updateTimerFinishesAt(iso: String) {
            instance?.handler?.post { instance?.clockView?.onHaTimerFinishesAt(iso) }
        }

        /** HA `remaining` push ("H:MM:SS"); used to freeze the display while paused. */
        fun updateTimerRemaining(remaining: String) {
            instance?.handler?.post { instance?.clockView?.onHaTimerRemaining(remaining) }
        }

        fun show(context: Context) {
            val intent = Intent(context, DreamClockService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, DreamClockService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
        
        fun toggle(context: Context) {
            val intent = Intent(context, DreamClockService::class.java).apply {
                action = ACTION_TOGGLE
            }
            context.startService(intent)
        }
        
        const val ACTION_SET_VISIBLE = "com.example.ava.SET_CLOCK_VISIBLE"
        const val EXTRA_VISIBLE = "visible"
        
        fun setVisible(context: Context, visible: Boolean) {
            if (!visible && instance == null) return
            val intent = Intent(context, DreamClockService::class.java).apply {
                action = ACTION_SET_VISIBLE
                putExtra(EXTRA_VISIBLE, visible)
            }
            context.startService(intent)
        }
    }
}


class DreamClockView(context: Context, private val clockSize: Int) : View(context) {
    
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var showClockFace = true

    /** Navigation-bar + gesture-zone height so the picker never hides behind it. */
    private var safeBottomPx = 0

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        val nav = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        // Add a small visual gap (8dp) so chips don't press right against the inset edge.
        val gap = (8f * resources.displayMetrics.density).toInt()
        val new = nav + gap
        if (safeBottomPx != new) {
            safeBottomPx = new
            invalidate()
        }
        return super.onApplyWindowInsets(insets)
    }

    private var weatherTemp = 21
    private var weatherCondition = "sunny"
    
    init {
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }
    
    fun updateWeather(temp: Int, condition: String) {
        weatherTemp = temp
        weatherCondition = condition
        invalidate()
    }
    
    
    private val bgDeep = Color.parseColor("#020205")
    private val bgNebula = Color.parseColor("#151530")
    private val accentGlow = Color.parseColor("#a29bfe")  
    private val accentGold = Color.parseColor("#ffeaa7")  
    private val metalLight = Color.WHITE
    private val metalDark = Color.parseColor("#888899")
    private val lumeColor = Color.parseColor("#ccffcc")   
    
    fun setShowClock(show: Boolean) {
        showClockFace = show
        invalidate()
    }

    var onFacePicked: ((DreamClockFace) -> Unit)? = null
    var onFlipStylePicked: ((DreamClockFlipStyle) -> Unit)? = null

    /**
     * Rapid swipes persist every intermediate theme, and each write is pushed to HA as a
     * select state change. Allow the first [STYLE_BURST_FREE] changes through, then hold
     * further ones until the hand has been still for [STYLE_SETTLE_MS] and send only the
     * final theme. The burst window resets after [STYLE_BURST_RESET_MS] of quiet.
     */
    private var styleBurstCount = 0
    private var styleBurstLastAt = 0L
    private var stylePending: DreamClockFlipStyle? = null
    private val styleCommitRunnable = Runnable {
        stylePending?.let { onFlipStylePicked?.invoke(it) }
        stylePending = null
    }

    private fun commitStyle(style: DreamClockFlipStyle) {
        if (!overlayThemeGesturesAllowed()) return
        OverlayHaptics.click(context, this)
        val now = SystemClock.elapsedRealtime()
        if (now - styleBurstLastAt > STYLE_BURST_RESET_MS) styleBurstCount = 0
        styleBurstLastAt = now
        styleBurstCount++
        handler.removeCallbacks(styleCommitRunnable)
        if (styleBurstCount <= STYLE_BURST_FREE) {
            stylePending = null
            onFlipStylePicked?.invoke(style)
        } else {
            stylePending = style
            handler.postDelayed(styleCommitRunnable, STYLE_SETTLE_MS)
        }
    }
    var onFlipFontPicked: ((DreamClockFlipFont) -> Unit)? = null
    var onFlipSecondsTapped: ((Boolean) -> Unit)? = null
    var onFlipTwelveHourTapped: ((Boolean) -> Unit)? = null
    /** (service, serviceData) → HA call on the bound timer entity. */
    var onTimerHaCall: ((String, Map<String, Any?>) -> Unit)? = null
    /** Ring [timerSoundUri] when the flip countdown hits zero. */
    var timerSoundEnabled = true
    var timerSoundUri = "asset:///sounds/timer_finished.wav"

    // Smart AOD — same contract as QuickEntityOverlayService: idle → fade to black at
    // [aodMaskPercent]; ≤85% taps pass through, >85% first tap only wakes.
    private var aodEnabled = false
    private var aodTimeoutMs = 60_000L
    private var aodMaskPercent = 100
    private var aodCovering = false

    fun isSmartAodCovering(): Boolean = aodCovering
    private var aodSwallowGesture = false
    private var aodAlpha = 0f
    private var aodFrom = 0f
    private var aodTo = 0f
    private var aodAnimAt = 0L
    private val aodFadeMs = 380f
    private val enterAodRunnable = Runnable { enterAod() }

    fun applySmartAod(enabled: Boolean, timeoutSeconds: Int, maskPercent: Int) {
        val wasEnabled = aodEnabled
        aodEnabled = enabled
        aodTimeoutMs = timeoutSeconds.coerceIn(10, 3600) * 1000L
        aodMaskPercent = maskPercent.coerceIn(5, 100)
        if (!aodEnabled) {
            if (wasEnabled) clearAod(animated = true) else clearAod(animated = false)
        } else {
            if (aodCovering) {
                startAodFade(aodMaskPercent / 100f)
                onAodCovering()
            }
            scheduleAodEnter()
        }
    }

    fun scheduleAodEnter() {
        handler.removeCallbacks(enterAodRunnable)
        if (!aodEnabled || !showClockFace) return
        if (NotificationOverlayService.isOverlayVisible()) return
        if (VoiceSatelliteService.getInstance()?.isVoiceAssistantPipelineActive() == true) return
        handler.postDelayed(enterAodRunnable, aodTimeoutMs)
    }

    private fun enterAod() {
        if (!aodEnabled || !showClockFace) return
        if (NotificationOverlayService.isOverlayVisible() ||
            VoiceSatelliteService.getInstance()?.isVoiceAssistantPipelineActive() == true
        ) {
            scheduleAodEnter()
            return
        }
        aodCovering = true
        OverlayZOrderCoordinator.syncFabForAod()
        // Cover owns the plate — «返回» and the settings FAB must not sit on AOD.
        onAodCovering()
        startAodFade(aodMaskPercent / 100f)
    }

    /** True while the AOD plate is up or still fading — overlay chrome must not fight it. */
    private fun aodOwnsPlate(): Boolean = aodCovering || aodAlpha > 0.01f

    /**
     * AOD just took the plate. Hide «返回» and snap the settings FAB / sheet off
     * immediately — a fade would keep fighting the cover and can get stuck in it.
     */
    private fun onAodCovering() {
        DashboardOverlayChrome.hideIfTop(DashboardOverlayChrome.Kind.DREAM_CLOCK)
        hideOverlayChromeForAod()
    }

    private fun hideOverlayChromeForAod() {
        pickerOpen = false
        pickerClosing = false
        handler.removeCallbacks(hideChromeRunnable)
        chromeAlpha = 0f
        chromeFrom = 0f
        chromeTo = 0f
        pagerAnimating = false
        pagerCommitFace = null
        pagerOffset = 0f
        colorAnimating = false
        colorCommitStyle = null
        colorOffset = 0f
        stripDragging = false
        stripFlinging = false
        stripVx = 0f
        settingsButtonRect.setEmpty()
        timerPauseRect.setEmpty()
        timerCancelRect.setEmpty()
    }

    /**
     * Fade the cover out (if up) and restart the idle wait.
     * Does not surface «返回» — an AOD tap only wakes; the strip is long-press after that.
     */
    fun interruptAod() {
        handler.removeCallbacks(enterAodRunnable)
        val waking = aodCovering || aodAlpha > 0.01f
        if (waking) {
            aodCovering = false
            OverlayZOrderCoordinator.syncFabForAod()
            startAodFade(0f)
        }
        scheduleAodEnter()
    }

    fun clearAod(animated: Boolean) {
        handler.removeCallbacks(enterAodRunnable)
        aodCovering = false
        OverlayZOrderCoordinator.syncFabForAod()
        if (animated) startAodFade(0f) else { aodAlpha = 0f; aodTo = 0f; aodFrom = 0f }
        invalidate()
    }

    private fun startAodFade(target: Float) {
        aodFrom = aodAlpha
        aodTo = target
        aodAnimAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    private fun tickAod() {
        if (aodAlpha == aodTo) return
        val t = ((SystemClock.elapsedRealtime() - aodAnimAt) / aodFadeMs).coerceIn(0f, 1f)
        aodAlpha = aodFrom + (aodTo - aodFrom) * t
        if (t >= 1f) aodAlpha = aodTo
    }

    /** True while the deep cover is up: the first touch must only wake, not act. */
    private fun aodBlocksTouch(): Boolean =
        aodCovering && aodMaskPercent > AOD_TAP_THROUGH_MAX_PERCENT
    /** True when a `timer.*` entity is bound; custom duration is HA-only. */
    var timerEntityBound = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private var settingsLocked = false

    /** Overlay settings FAB + sheet. Mechanical is settings-app only. Lock does not hide this. */
    private fun overlaySettingsAllowed(): Boolean = face != DreamClockFace.MECHANICAL

    /** Swipe faces / themes, theme strip, 秒 / 十二. Lock: only the sheet chips can change these. */
    private fun overlayThemeGesturesAllowed(): Boolean =
        overlaySettingsAllowed() && !settingsLocked

    private fun suppressOverlayChrome() {
        closePicker()
        handler.removeCallbacks(hideChromeRunnable)
        startChromeFade(0f)
        pagerAnimating = false
        pagerCommitFace = null
        pagerOffset = 0f
        colorAnimating = false
        colorCommitStyle = null
        colorOffset = 0f
        settingsButtonRect.setEmpty()
    }

    private val timerHits = ArrayList<Pair<RectF, TimerAction>>()
    private var customEditorOpen = false
    /** Last stepper the custom editor nudged; drives the short wave pulse. */
    private var stepperNudgeEdit: CustomEdit? = null
    private var stepperNudgeAt = 0L
    private val stepperNudgeMs = 160f
    private var capturingEditorBackdrop = false
    private var editorBlurBitmap: Bitmap? = null
    private val editorBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private var editorCardGlass: LiquidGlassDrawable? = null
    private var customH = 0
    private var customM = 5
    private var customS = 0
    private val customHits = ArrayList<Pair<RectF, CustomEdit>>()
    /**
     * Countdown reached zero. The board holds 00:00:00 and the alarm loops until the
     * user taps the centered stop; afterwards it keeps 00:00:00 until dismissed with ✕.
     */
    private var timerFinishedHold = false
    /** ElapsedRealtime when the centered stop popped in; 0 if not holding. */
    private var stopAlarmAppearAt = 0L
    private val stopAlarmAppearMs = 620f
    private val stopAlarmRect = RectF()
    private val timerPauseRect = RectF()
    private val timerCancelRect = RectF()

    private enum class TimerAction { M5, M10, M25, CUSTOM, CANCEL, PAUSE_RESUME }
    private enum class CustomEdit { H_UP, H_DOWN, M_UP, M_DOWN, S_UP, S_DOWN, START, CLOSE }

    private var face: DreamClockFace = DreamClockFace.FILL

    private fun publishFace(next: DreamClockFace) {
        face = next
        DashboardOverlayChrome.syncDreamClockFace(next)
    }
    // StandBy retro flip digits render with League Gothic (wi/b + x9.c.f34353h).
    // Ships as a digits-only subset (2 KB); flip cards never draw anything else.
    private val flipTypeface: Typeface =
        ResourcesCompat.getFont(context, R.font.league_gothic)
            ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val flipBoard = DreamClockFlipBoard(resources.displayMetrics.density, flipTypeface)
    @Suppress("unused")
    private val seasonLayerRemoved = Unit
    private val settingsButtonRect = RectF()
    private var chromeAlpha = 0f
    private var chromeFrom = 0f
    private var chromeTo = 0f
    private var chromeAnimAt = 0L
    private val hideChromeRunnable = Runnable {
        closePicker()
        startChromeFade(0f)
    }
    /** Idle timeout for settings chrome / sheet; every screen touch re-arms via [revealChrome]. */
    private val chromeHideMs = 10_000L
    private val AOD_TAP_THROUGH_MAX_PERCENT = 85
    private val STYLE_BURST_FREE = 2
    private val STYLE_SETTLE_MS = 700L
    private val STYLE_BURST_RESET_MS = 2_000L
    /** Page corner radius at full swipe distance; grows linearly with the finger from 0. */
    private val PAGE_CORNER_MAX_DP = 36f
    private val chromeFadeMs = 420f
    private val chromeHideFadeMs = 720f
    private val chromeRevealHoldMs = 2_000L
    // Long-press owns only the top «返回» strip; the bottom-right settings
    // button reveals on plain tap and hides on its own 5s clock.
    private val chromeRevealRunnable = Runnable {
        if (DashboardOverlayChrome.isShown(DashboardOverlayChrome.Kind.DREAM_CLOCK)) {
            DashboardOverlayChrome.keepVisibleIfShown(DashboardOverlayChrome.Kind.DREAM_CLOCK)
            DashboardOverlayChrome.bringToFront()
        } else {
            DashboardOverlayChrome.reveal(DashboardOverlayChrome.Kind.DREAM_CLOCK)
        }
    }
    private var pickerOpen = false
    private var pickerOpenAt = 0L
    private var pickerCloseAt = 0L
    private var pickerClosing = false
    private val pickerAnimMs = 680f
    private val pickerCloseMs = 640f
    /** Eased 0→1 clock lift while the sheet is up; animates both directions. */
    private var pickerLiftT = 0f
    private var pickerScrollX = 0f
    private var pickerDownX = 0f
    private var pickerDownY = 0f
    private var pickerMoved = false
    private var touchDownAt = 0L
    private var dragLock = DragLock.NONE
    private var pagerOffset = 0f
    private var pagerFrom = 0f
    private var pagerTo = 0f
    private var pagerAnimAt = 0L
    private var pagerAnimating = false
    private var pagerCommitFace: DreamClockFace? = null
    private val pagerAnimMs = 480f
    /**
     * Settings-chip FILL↔FLIP handoff. Outgoing face folds away while the incoming
     * face rises in — short, one-beat, not a pager swipe.
     */
    private var faceHandoffFrom: DreamClockFace? = null
    private var faceHandoffAt = 0L
    private var faceHandoffT = 1f
    private val faceHandoffMs = 280f
    private var colorOffset = 0f
    private var colorFrom = 0f
    private var colorTo = 0f
    private var colorAnimAt = 0L
    private var colorAnimating = false
    private var colorCommitStyle: DreamClockFlipStyle? = null
    private val colorAnimMs = 140f
    private val pickerFaceHits = ArrayList<Pair<RectF, DreamClockFace>>()
    private val pickerStyleHits = ArrayList<Pair<RectF, DreamClockFlipStyle>>()
    private val pickerFontHits = ArrayList<Pair<RectF, DreamClockFlipFont>>()
    private val secondsToggleRect = RectF()
    private val twelveHourToggleRect = RectF()
    private val fontToggleRect = RectF()
    private var fontRowExpanded = false
    /** 0→1 eased reveal of the font chip row; drives row height and chip alpha together. */
    private var fontExpandT = 0f
    private var fontExpandFrom = 0f
    private var fontExpandAt = 0L
    private val fontExpandMs = 460f
    /**
     * 0→1 reveal of FLIP-only sheet chrome (timer row, A pill, sec/12h). Animates when
     * switching between pointer faces and FLIP so the floating card morphs instead of jumps.
     */
    private var flipChromeT = 0f
    private var flipChromeFrom = 0f
    private var flipChromeTarget = 0f
    private var flipChromeAt = 0L
    private val flipChromeMs = 520f

    private fun setFontRowExpanded(expanded: Boolean) {
        if (fontRowExpanded == expanded) return
        fontRowExpanded = expanded
        fontExpandFrom = fontExpandT
        fontExpandAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    /** Soft quintic ease-in-out — slower middle feel than the simple smoothstep. */
    private fun easeSoft(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            16f * x * x * x * x * x
        } else {
            val u = 1f - x
            1f - 16f * u * u * u * u * u
        }
    }

    /** Snappy ease-out for the settings-chip face handoff. */
    private fun easeOutCubic(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    /** Advance the font-row reveal; returns true while still animating. */
    private fun tickFontExpand(): Boolean {
        val target = if (fontRowExpanded) 1f else 0f
        if (fontExpandT == target) return false
        val t = ((SystemClock.elapsedRealtime() - fontExpandAt) / fontExpandMs).coerceIn(0f, 1f)
        fontExpandT = fontExpandFrom + (target - fontExpandFrom) * easeSoft(t)
        if (t >= 1f) fontExpandT = target
        return fontExpandT != target
    }

    private fun syncFlipChromeTarget() {
        val target = if (face == DreamClockFace.FLIP) 1f else 0f
        if (target == flipChromeTarget) return
        flipChromeTarget = target
        flipChromeFrom = flipChromeT
        flipChromeAt = SystemClock.elapsedRealtime()
        if (target < 0.5f && fontRowExpanded) {
            setFontRowExpanded(false)
        }
    }

    /** Advance FLIP extras morph; returns true while still animating. */
    private fun tickFlipChrome(): Boolean {
        syncFlipChromeTarget()
        if (flipChromeT == flipChromeTarget) return false
        val t = ((SystemClock.elapsedRealtime() - flipChromeAt) / flipChromeMs).coerceIn(0f, 1f)
        flipChromeT = flipChromeFrom + (flipChromeTarget - flipChromeFrom) * easeSoft(t)
        if (t >= 1f) flipChromeT = flipChromeTarget
        return flipChromeT != flipChromeTarget
    }

    /** Snap FLIP chrome when the picker isn't up (no need to morph off-screen). */
    private fun snapFlipChromeToFace() {
        flipChromeT = if (face == DreamClockFace.FLIP) 1f else 0f
        flipChromeTarget = flipChromeT
        flipChromeFrom = flipChromeT
        if (face != DreamClockFace.FLIP) {
            fontRowExpanded = false
            fontExpandT = 0f
        }
    }
    private val styleRowRect = RectF()
    private val pickerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeFadeXfer = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    private val pickerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fillTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val fillNumeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    }
    private val fillWeekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF44336.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val fillDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val fillHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillSecondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private fun resolveFlipFont(font: DreamClockFlipFont): Typeface = when (font) {
        DreamClockFlipFont.LEAGUE_GOTHIC -> flipTypeface
        DreamClockFlipFont.SYSTEM -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        DreamClockFlipFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        DreamClockFlipFont.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        DreamClockFlipFont.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var flipFont: DreamClockFlipFont = DreamClockFlipFont.LEAGUE_GOTHIC
    private var season: DreamClockSeason = DreamClockSeason.NONE

    fun applyFace(
        nextFace: DreamClockFace,
        nextStyle: DreamClockFlipStyle,
        nextSeason: DreamClockSeason,
        showSeconds: Boolean,
        font: DreamClockFlipFont = DreamClockFlipFont.LEAGUE_GOTHIC,
        twelveHour: Boolean = false,
    ) {
        flipBoard.showSeconds = showSeconds
        flipBoard.twelveHour = twelveHour
        flipFont = font
        season = nextSeason
        flipBoard.setDigitTypeface(resolveFlipFont(font))
        if (colorAnimating || abs(colorOffset) > 0.01f) {
            invalidate()
            return
        }
        if (nextStyle != flipBoard.style) {
            // Remote / settings-driven theme change (no swipe): dissolve instead of snapping.
            if (width > 0 && isShown) {
                styleFadeFrom = flipBoard.style
                styleFadeAt = SystemClock.elapsedRealtime()
            }
            flipBoard.style = nextStyle
        }
        if (pagerAnimating || abs(pagerOffset) > 0.01f || faceHandoffFrom != null) {
            invalidate()
            return
        }
        val prevFace = face
        // Mechanical is settings-only. A leftover HA countdown must not steal
        // cold start onto FLIP.
        val resolved = when {
            nextFace == DreamClockFace.MECHANICAL -> nextFace
            flipBoard.isCountingDown -> DreamClockFace.FLIP
            else -> nextFace
        }
        publishFace(resolved)
        if (face == DreamClockFace.MECHANICAL) {
            suppressOverlayChrome()
        } else if (pickerOpen || pickerClosing) {
            if (prevFace == DreamClockFace.FLIP && face != DreamClockFace.FLIP) {
                setFontRowExpanded(false)
            }
            syncFlipChromeTarget()
        } else {
            snapFlipChromeToFace()
        }
        invalidate()
    }

    /** Outgoing theme for the dissolve started by [applyFace]; null when idle. */
    private var styleFadeFrom: DreamClockFlipStyle? = null
    private var styleFadeAt = 0L
    private val styleFadeMs = 320f

    /** 1→0 opacity of the outgoing theme, or null when no dissolve is running. */
    private fun styleFadeOutAlpha(): Float? {
        if (styleFadeFrom == null) return null
        val t = ((SystemClock.elapsedRealtime() - styleFadeAt) / styleFadeMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            styleFadeFrom = null
            return null
        }
        val eased = 1f - (1f - t) * (1f - t)
        return 1f - eased
    }

    // ---- HA timer sync -------------------------------------------------------------
    // ESPHome pushes `state`, `remaining`, `finishes_at` as separate messages in no
    // guaranteed order, so each handler only caches its value and then calls
    // [resyncHaCountdown], which reasons over the full snapshot.
    //
    // Clock skew: `finishes_at` is HA's wall clock; the device clock may differ by
    // seconds. The device-side anchor for a run is `haStartLocalMs + remaining`, where
    // haStartLocalMs is our best estimate of *when HA started the timer, in device
    // time*. Then skew = finishes_at − anchor, and every later finishes_at is corrected
    // by that skew. haStartLocalMs comes from (best first):
    //   • our own timer.start send time + half the round trip (direct measurement)
    //   • receipt time of a fresh `remaining` push (HA start ≈ push receipt − ~latency)

    private var haTimerState = "idle"
    private var haFinishesAtMs: Long? = null
    private var haRemainingMs: Long? = null
    private var haRemainingAtMs = 0L          // device epoch when `remaining` was received
    private var haActiveSinceMs = 0L          // elapsedRealtime of the last active transition
    private var haAnchorTargetMs: Long? = null
    private var haClockSkewMs = 0L
    /** Device epoch when *we* sent timer.start; 0 if the run was started elsewhere. */
    private var localStartSentAtMs = 0L

    private val HA_FRESH_WINDOW_MS = 4_000L

    fun onHaTimerState(state: String) {
        val prev = haTimerState
        haTimerState = state.lowercase()
        when (haTimerState) {
            "idle" -> {
                haFinishesAtMs = null
                haRemainingMs = null
                haAnchorTargetMs = null
                localStartSentAtMs = 0L
                if (prev == "active" && flipBoard.isCountingDown && flipBoard.countdownRemainingMs() <= 1_500L) {
                    // Idle with (near) zero left = HA ran it out → hold 00:00:00 and ring.
                    enterFinishedHold()
                } else if (!timerFinishedHold) {
                    // Idle with time left = remote cancel.
                    flipBoard.clearCountdown()
                }
            }
            "active" -> {
                haActiveSinceMs = SystemClock.elapsedRealtime()
                haAnchorTargetMs = null
                val wasCounting = flipBoard.isCountingDown
                resyncHaCountdown()
                // Remote start from the clock: all three cards flip in together.
                if (!wasCounting && flipBoard.isCountingDown) flipBoard.beginEntrance()
            }
            "paused" -> {
                haAnchorTargetMs = null
                val rem = haRemainingMs
                if (rem != null) flipBoard.setPausedRemaining(rem) else flipBoard.pauseCountdown()
            }
        }
        invalidate()
    }

    fun onHaTimerFinishesAt(iso: String) {
        if (iso.isBlank() || iso.equals("none", ignoreCase = true)) {
            haFinishesAtMs = null
            return
        }
        haFinishesAtMs = try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            return
        }
        resyncHaCountdown()
        invalidate()
    }

    fun onHaTimerRemaining(remaining: String) {
        val ms = parseHaDurationMs(remaining) ?: return
        haRemainingMs = ms
        haRemainingAtMs = System.currentTimeMillis()
        if (haTimerState == "paused") {
            flipBoard.setPausedRemaining(ms)
        } else {
            resyncHaCountdown()
        }
        invalidate()
    }

    private fun resyncHaCountdown() {
        if (haTimerState != "active") return
        val now = System.currentTimeMillis()
        val finishesAt = haFinishesAtMs

        if (haAnchorTargetMs == null) {
            val rem = haRemainingMs
            // `remaining` is only trustworthy right after a (re)start: HA freezes it there,
            // so a replay minutes later (reconnect) would be badly stale.
            val remFresh = rem != null &&
                now - haRemainingAtMs < HA_FRESH_WINDOW_MS &&
                SystemClock.elapsedRealtime() - haActiveSinceMs < HA_FRESH_WINDOW_MS
            if (remFresh) {
                val haStartLocal = if (localStartSentAtMs != 0L && now - localStartSentAtMs < 8_000L) {
                    // We initiated this run: HA acted ~half a round trip after we sent it.
                    localStartSentAtMs + (haRemainingAtMs - localStartSentAtMs) / 2
                } else {
                    haRemainingAtMs
                }
                val anchor = haStartLocal + rem!!
                haAnchorTargetMs = anchor
                flipBoard.setCountdownTarget(anchor)
                if (finishesAt != null) haClockSkewMs = finishesAt - anchor
                return
            }
        }

        val anchor = haAnchorTargetMs
        if (anchor != null) {
            if (finishesAt != null) haClockSkewMs = finishesAt - anchor
            return
        }
        // No fresh anchor (reconnect mid-run, or remaining never arrived): trust
        // finishes_at corrected by whatever skew we last learned.
        if (finishesAt != null) {
            flipBoard.setCountdownTarget(finishesAt - haClockSkewMs)
        } else {
            flipBoard.resumeCountdown()
        }
    }

    private fun ringTimerFinished() {
        if (!timerSoundEnabled || timerSoundUri.isBlank()) return
        interruptAod()
        VoiceSatelliteService.getInstance()?.triggerDreamClockTimerFinished(timerSoundUri)
    }

    private fun parseHaDurationMs(text: String): Long? {
        val parts = text.split(":")
        return try {
            when (parts.size) {
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].substringBefore('.').toLong()) * 1000
                2 -> (parts[0].toLong() * 60 + parts[1].substringBefore('.').toLong()) * 1000
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun haDuration(h: Int, m: Int, s: Int): String = "%02d:%02d:%02d".format(h, m, s)

    private fun runTimerAction(action: TimerAction) {
        OverlayHaptics.click(context, this)
        when (action) {
            TimerAction.M5 -> startTimer(5 * 60_000L, 0, 5, 0)
            TimerAction.M10 -> startTimer(10 * 60_000L, 0, 10, 0)
            TimerAction.M25 -> startTimer(25 * 60_000L, 0, 25, 0)
            TimerAction.CUSTOM -> openCustomEditor()
            TimerAction.CANCEL -> dismissTimer()
            TimerAction.PAUSE_RESUME -> {
                if (flipBoard.pausedRemainingMs != null) {
                    flipBoard.resumeCountdown()
                    if (timerEntityBound) {
                        localStartSentAtMs = System.currentTimeMillis()
                        onTimerHaCall?.invoke("timer.start", emptyMap())
                    }
                } else if (flipBoard.countdownTargetMs != null) {
                    flipBoard.pauseCountdown()
                    if (timerEntityBound) onTimerHaCall?.invoke("timer.pause", emptyMap())
                }
            }
        }
        invalidate()
    }

    private fun enterFinishedHold() {
        if (timerFinishedHold) return
        timerFinishedHold = true
        stopAlarmAppearAt = SystemClock.elapsedRealtime()
        flipBoard.setPausedRemaining(0L)
        ringTimerFinished()
        revealChrome()
        invalidate()
    }

    /** Centered ■ while ringing: silence and return to the clock in one step. */
    private fun stopAlarm() {
        VoiceSatelliteService.getInstance()?.stopTimerSound()
        timerFinishedHold = false
        stopAlarmAppearAt = 0L
        flipBoard.clearCountdown()
        // All three cards flip from blank to the live time on the same frame.
        flipBoard.beginEntrance()
        revealChrome()
        invalidate()
    }

    /** ✕ during a countdown: cancel and flip back to the clock. */
    private fun dismissTimer() {
        VoiceSatelliteService.getInstance()?.stopTimerSound()
        timerFinishedHold = false
        stopAlarmAppearAt = 0L
        flipBoard.clearCountdown()
        flipBoard.beginEntrance()
        if (timerEntityBound) onTimerHaCall?.invoke("timer.cancel", emptyMap())
        invalidate()
    }

    fun voiceRemainingMs(): Long = flipBoard.countdownRemainingMs()
    fun voiceIsRunning(): Boolean = flipBoard.countdownTargetMs != null
    fun voiceIsPaused(): Boolean = flipBoard.pausedRemainingMs != null
    fun voiceIsFinishedHold(): Boolean = timerFinishedHold

    fun voiceStartTimer(durationMs: Long) {
        val total = durationMs.coerceIn(1_000L, 24 * 3_600_000L)
        val totalSec = (total / 1000L).toInt()
        ensureFlipFace()
        interruptAod()
        startTimer(total, totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
        revealChrome()
        invalidate()
    }

    fun voicePauseTimer() {
        if (flipBoard.countdownTargetMs == null) return
        flipBoard.pauseCountdown()
        if (timerEntityBound) onTimerHaCall?.invoke("timer.pause", emptyMap())
        invalidate()
    }

    fun voiceResumeTimer() {
        if (flipBoard.pausedRemainingMs == null) return
        flipBoard.resumeCountdown()
        if (timerEntityBound) {
            localStartSentAtMs = System.currentTimeMillis()
            onTimerHaCall?.invoke("timer.start", emptyMap())
        }
        invalidate()
    }

    fun voiceCancelTimer() {
        dismissTimer()
        revealChrome()
        invalidate()
    }

    private fun ensureFlipFace() {
        if (face == DreamClockFace.FLIP) return
        applyFace(
            DreamClockFace.FLIP,
            flipBoard.style,
            season,
            flipBoard.showSeconds,
            flipFont,
            flipBoard.twelveHour,
        )
        onFacePicked?.invoke(DreamClockFace.FLIP)
    }

    private fun startTimer(durationMs: Long, h: Int, m: Int, s: Int) {
        timerFinishedHold = false
        stopAlarmAppearAt = 0L
        val wasCounting = flipBoard.isCountingDown
        flipBoard.startCountdown(durationMs)
        if (!wasCounting) flipBoard.beginEntrance()
        if (timerEntityBound) {
            localStartSentAtMs = System.currentTimeMillis()
            onTimerHaCall?.invoke("timer.start", mapOf("duration" to haDuration(h, m, s)))
        }
        customEditorOpen = false
        recycleEditorBlur()
        closePicker()
    }

    private fun runCustomEdit(edit: CustomEdit) {
        when (edit) {
            CustomEdit.H_UP -> customH = (customH + 1) % 24
            CustomEdit.H_DOWN -> customH = (customH + 23) % 24
            CustomEdit.M_UP -> customM = (customM + 1) % 60
            CustomEdit.M_DOWN -> customM = (customM + 59) % 60
            CustomEdit.S_UP -> customS = (customS + 1) % 60
            CustomEdit.S_DOWN -> customS = (customS + 59) % 60
            CustomEdit.START -> {
                val total = (customH * 3600L + customM * 60L + customS) * 1000L
                if (total > 0L) {
                    OverlayHaptics.click(context, this)
                    startTimer(total, customH, customM, customS)
                }
            }
            CustomEdit.CLOSE -> {
                OverlayHaptics.click(context, this)
                dismissCustomEditor()
            }
        }
        if (edit != CustomEdit.START && edit != CustomEdit.CLOSE) {
            stepperNudgeEdit = edit
            stepperNudgeAt = SystemClock.elapsedRealtime()
            OverlayHaptics.tick(context, this)
        }
        revealChrome()
        invalidate()
    }

    private fun openCustomEditor() {
        pickerOpen = false
        pickerClosing = false
        handler.removeCallbacks(hideChromeRunnable)
        chromeAlpha = 0f
        chromeFrom = 0f
        chromeTo = 0f
        settingsButtonRect.setEmpty()
        customEditorOpen = true
        recycleEditorBlur()
        post { captureCustomEditorBackdrop() }
        invalidate()
    }

    private fun dismissCustomEditor() {
        customEditorOpen = false
        stepperNudgeEdit = null
        recycleEditorBlur()
        invalidate()
    }

    private fun recycleEditorBlur() {
        val bmp = editorBlurBitmap
        editorBlurBitmap = null
        if (bmp != null && !bmp.isRecycled) bmp.recycle()
    }

    private fun captureCustomEditorBackdrop() {
        if (!customEditorOpen || capturingEditorBackdrop) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val maxEdge = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 720 else 360
        val scale = (maxEdge.toFloat() / max(w, h)).coerceAtMost(1f)
        val bw = max(1, (w * scale).toInt())
        val bh = max(1, (h * scale).toInt())
        val raw = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return
        }
        capturingEditorBackdrop = true
        try {
            val c = Canvas(raw)
            if (scale != 1f) c.scale(scale, scale)
            draw(c)
        } catch (_: Exception) {
            if (!raw.isRecycled) raw.recycle()
            capturingEditorBackdrop = false
            return
        }
        capturingEditorBackdrop = false
        recycleEditorBlur()
        val spec = LiquidGlass.fakeBlurSpec()
        editorBlurBitmap = if (spec != null) {
            AmbientBitmapBlur.create(raw, spec.maxEdgePx, spec.radius)
        } else {
            null
        }
        if (editorBlurBitmap !== raw && !raw.isRecycled) raw.recycle()
        invalidate()
    }

    fun revealChromeOnColdStart() {
        // 30s first-show (screensaver / QE). enterAod hideIfTop if Smart AOD covers later.
        DashboardOverlayChrome.revealOnShow(DashboardOverlayChrome.Kind.DREAM_CLOCK)
        if (overlaySettingsAllowed()) revealChrome()
    }

    /** Cold-start entrance: flip cards tumble in together. */
    fun playEntrance() {
        if (face == DreamClockFace.FLIP) {
            flipBoard.beginEntrance()
            invalidate()
        }
    }

    fun applySettingsLock(locked: Boolean) {
        if (settingsLocked == locked) return
        settingsLocked = locked
        if (locked) {
            pagerAnimating = false
            pagerCommitFace = null
            pagerOffset = 0f
            colorAnimating = false
            colorCommitStyle = null
            colorOffset = 0f
            stripDragging = false
            stripFlinging = false
            stripVx = 0f
            dragLock = DragLock.NONE
            cancelFaceHandoff()
            setFontRowExpanded(false)
        }
        invalidate()
    }

    fun handleOverlayTouch(event: MotionEvent): Boolean {
        var wakingAod = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wakingAod = aodCovering || aodAlpha > 0.01f
                if (aodBlocksTouch()) {
                    // Deep cover: this gesture only wakes the clock (Quick Entity parity).
                    aodSwallowGesture = true
                    interruptAod()
                    return true
                }
                // Light cover / no cover: wake and let the touch act normally.
                interruptAod()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (aodSwallowGesture) {
                    aodSwallowGesture = false
                    return true
                }
            }
            else -> if (aodSwallowGesture) return true
        }
        if (customEditorOpen) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val hit = customHits.firstOrNull { it.first.contains(event.x, event.y) }
                if (hit != null) runCustomEdit(hit.second)
            }
            return true
        }
        val shortest = min(width, height).toFloat()
        val triggerPx = shortest * 0.10f
        val maxVerticalPx = shortest * 0.08f
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val chromeUp = chromeAlpha > 0.2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pickerDownX = event.x
                pickerDownY = event.y
                pickerMoved = false
                touchDownAt = event.eventTime
                dragLock = DragLock.NONE
                DashboardOverlayChrome.keepVisibleIfShown(DashboardOverlayChrome.Kind.DREAM_CLOCK)
                handler.removeCallbacks(chromeRevealRunnable)
                // Any contact while settings chrome/sheet is up pauses auto-hide;
                // UP/CANCEL re-arms the full 10s idle window.
                if (pickerOpen || chromeUp) {
                    handler.removeCallbacks(hideChromeRunnable)
                }
                val onCornerButton = chromeUp && (
                    (overlaySettingsAllowed() && settingsButtonRect.contains(event.x, event.y)) ||
                        (!timerPauseRect.isEmpty && timerPauseRect.contains(event.x, event.y)) ||
                        (!timerCancelRect.isEmpty && timerCancelRect.contains(event.x, event.y))
                    )
                val onStopAlarm = !stopAlarmRect.isEmpty && stopAlarmRect.contains(event.x, event.y)
                if (pickerOpen || onCornerButton || onStopAlarm) {
                    dragLock = DragLock.PICKER
                    stripDragging = pickerOpen && overlayThemeGesturesAllowed() &&
                        styleRowRect.contains(event.x, event.y)
                    if (stripDragging) {
                        stripFlinging = false
                        stripVx = 0f
                        stripLastMoveT = event.eventTime
                    }
                    return true
                }
                if (!pickerOpen && !wakingAod) {
                    handler.postDelayed(chromeRevealRunnable, chromeRevealHoldMs)
                }
                if (overlayThemeGesturesAllowed() && face == DreamClockFace.FLIP) {
                    flipBoard.handleSwipe(event, width, triggerPx, maxVerticalPx)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - pickerDownX
                val dy = event.y - pickerDownY
                if (abs(dx) > slop || abs(dy) > slop) {
                    pickerMoved = true
                    handler.removeCallbacks(chromeRevealRunnable)
                }
                if (dragLock == DragLock.PICKER) {
                    if (pickerOpen && stripDragging) {
                        val prevScroll = pickerScrollX
                        val stepX = event.x - pickerDownX
                        pickerScrollX = (pickerScrollX - stepX).coerceIn(0f, pickerMaxScroll())
                        val density = resources.displayMetrics.density
                        val step = (DreamClockFlipBoard.CHIP_WIDTH_DP + DreamClockFlipBoard.CHIP_GAP_DP) * density
                        if (travelTickChanged(prevScroll, pickerScrollX, step)) {
                            OverlayHaptics.tick(context, this)
                        }
                        // Smoothed finger velocity (px/s) for the release fling.
                        val dt = (event.eventTime - stripLastMoveT).coerceAtLeast(1L) / 1000f
                        val instVx = -stepX / dt
                        stripVx = if (stripVx == 0f) instVx else stripVx * 0.6f + instVx * 0.4f
                        stripLastMoveT = event.eventTime
                        pickerDownX = event.x
                        invalidate()
                    }
                    return true
                }
                if (dragLock == DragLock.NONE && pickerMoved && overlayThemeGesturesAllowed()) {
                    dragLock = if (abs(dy) > abs(dx)) DragLock.VERTICAL else DragLock.HORIZONTAL
                    closePicker()
                    if (chromeAlpha > 0f) startChromeFade(0f)
                }
                if (dragLock == DragLock.VERTICAL && height > 0) {
                    pagerAnimating = false
                    pagerCommitFace = null
                    pagerOffset = (dy / height).coerceIn(-1f, 1f)
                    invalidate()
                } else if (dragLock == DragLock.HORIZONTAL && width > 0) {
                    colorAnimating = false
                    colorCommitStyle = null
                    colorOffset = (dx / width).coerceIn(-1f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(chromeRevealRunnable)
                if (dragLock == DragLock.VERTICAL) settlePager(0f)
                if (dragLock == DragLock.HORIZONTAL) settleColor(0f)
                stripDragging = false
                dragLock = DragLock.NONE
                // Finger left without UP — still count as a touch for the idle clock.
                if (pickerOpen || chromeAlpha > 0.2f) revealChrome()
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(chromeRevealRunnable)
                if (dragLock == DragLock.VERTICAL) {
                    val dt = (event.eventTime - touchDownAt).coerceAtLeast(1L)
                    val vy = (event.y - pickerDownY) / dt * 1000f
                    settlePager(vy)
                    dragLock = DragLock.NONE
                    return true
                }
                if (dragLock == DragLock.HORIZONTAL) {
                    val dt = (event.eventTime - touchDownAt).coerceAtLeast(1L)
                    val vx = (event.x - pickerDownX) / dt * 1000f
                    settleColor(vx)
                    dragLock = DragLock.NONE
                    return true
                }
                if (!pickerMoved && !pickerOpen) {
                    if (!stopAlarmRect.isEmpty && stopAlarmRect.contains(event.x, event.y)) {
                        stopAlarm()
                        dragLock = DragLock.NONE
                        return true
                    }
                    if (chromeUp && !timerCancelRect.isEmpty && timerCancelRect.contains(event.x, event.y)) {
                        dismissTimer()
                        dragLock = DragLock.NONE
                        return true
                    }
                    if (chromeUp && !timerPauseRect.isEmpty && timerPauseRect.contains(event.x, event.y)) {
                        runTimerAction(TimerAction.PAUSE_RESUME)
                        revealChrome()
                        dragLock = DragLock.NONE
                        return true
                    }
                }
                if (stripDragging) {
                    stripDragging = false
                    if (pickerMoved) {
                        // Big swipe → fling with decay; small drag → stays exactly where released.
                        if (abs(stripVx) > STRIP_FLING_MIN_VX) {
                            stripFlinging = true
                            stripFlingLastT = SystemClock.elapsedRealtime()
                            // Hold the sheet open while it coasts; tickStrip re-arms on stop.
                            handler.removeCallbacks(hideChromeRunnable)
                            startChromeFade(1f)
                        } else {
                            revealChrome()
                        }
                        invalidate()
                        dragLock = DragLock.NONE
                        return true
                    }
                }
                if (!pickerMoved && overlaySettingsAllowed()) revealChrome()
                if (chromeUp && overlaySettingsAllowed() && settingsButtonRect.contains(event.x, event.y) && !pickerMoved) {
                    if (pickerOpen) {
                        closePicker()
                        revealChrome()
                    } else {
                        openPicker()
                    }
                    invalidate()
                    dragLock = DragLock.NONE
                    return true
                }
                if (pickerOpen) {
                    revealChrome()
                    if (!pickerMoved) {
                        for (hit in pickerFaceHits) {
                            if (hit.first.contains(event.x, event.y)) {
                                val next = hit.second
                                if (faceHandoffFrom != null) {
                                    invalidate()
                                    dragLock = DragLock.NONE
                                    return true
                                }
                                if (next != face) {
                                    OverlayHaptics.click(context, this)
                                    startFaceHandoff(next)
                                }
                                onFacePicked?.invoke(next)
                                invalidate()
                                dragLock = DragLock.NONE
                                return true
                            }
                        }
                        for (hit in pickerStyleHits) {
                            if (!overlayThemeGesturesAllowed()) break
                            if (hit.first.contains(event.x, event.y)) {
                                commitStyle(hit.second)
                                invalidate()
                                dragLock = DragLock.NONE
                                return true
                            }
                        }
                        if (!fontToggleRect.isEmpty && fontToggleRect.contains(event.x, event.y)) {
                            setFontRowExpanded(!fontRowExpanded)
                            revealChrome()
                            invalidate()
                            dragLock = DragLock.NONE
                            return true
                        }
                        for (hit in pickerFontHits) {
                            if (hit.first.contains(event.x, event.y)) {
                                OverlayHaptics.click(context, this)
                                flipFont = hit.second
                                flipBoard.setDigitTypeface(resolveFlipFont(hit.second))
                                onFlipFontPicked?.invoke(hit.second)
                                setFontRowExpanded(false)
                                invalidate()
                                dragLock = DragLock.NONE
                                return true
                            }
                        }
                        for (hit in timerHits) {
                            if (hit.first.contains(event.x, event.y)) {
                                runTimerAction(hit.second)
                                dragLock = DragLock.NONE
                                return true
                            }
                        }
                        if (!twelveHourToggleRect.isEmpty && twelveHourToggleRect.contains(event.x, event.y)) {
                            val on = !flipBoard.twelveHour
                            flipBoard.twelveHour = on
                            flipBoard.beginEntrance()
                            onFlipTwelveHourTapped?.invoke(on)
                            revealChrome()
                            invalidate()
                            dragLock = DragLock.NONE
                            return true
                        }
                        if (!secondsToggleRect.isEmpty && secondsToggleRect.contains(event.x, event.y)) {
                            val newVal = !flipBoard.showSeconds
                            flipBoard.showSeconds = newVal
                            onFlipSecondsTapped?.invoke(newVal)
                            invalidate()
                            dragLock = DragLock.NONE
                            return true
                        }
                        closePicker()
                        invalidate()
                    }
                    dragLock = DragLock.NONE
                    return true
                }
                if (dragLock != DragLock.VERTICAL && overlayThemeGesturesAllowed() && face == DreamClockFace.FLIP) {
                    when (flipBoard.handleSwipe(event, width, triggerPx, maxVerticalPx)) {
                        DreamClockFlipBoard.FlipSwipe.NEXT -> {
                            commitStyle(flipBoard.cycleNext())
                            invalidate()
                            dragLock = DragLock.NONE
                            return true
                        }
                        DreamClockFlipBoard.FlipSwipe.PREVIOUS -> {
                            commitStyle(flipBoard.cyclePrevious())
                            invalidate()
                            dragLock = DragLock.NONE
                            return true
                        }
                        null -> Unit
                    }
                }
                dragLock = DragLock.NONE
                return true
            }
        }
        return true
    }

    private fun settlePager(vy: Float) {
        if (!overlayThemeGesturesAllowed()) {
            pagerOffset = 0f
            pagerAnimating = false
            pagerCommitFace = null
            invalidate()
            return
        }
        if (abs(pagerOffset) < 0.02f) {
            pagerOffset = 0f
            invalidate()
            return
        }
        // StandBy: VerticalPager with snapPositionalThreshold = 0.1f (zc/a + vn.f.L).
        val flick = abs(vy) > 800f && abs(pagerOffset) > 0.04f
        val commit = flick || abs(pagerOffset) >= 0.10f
        if (!commit) {
            startPagerAnim(0f, null)
            return
        }
        val target = if (pagerOffset > 0f) 1f else -1f
        val next = if (pagerOffset > 0f) face.previousSwitchable() else face.nextSwitchable()
        OverlayHaptics.click(context, this)
        startPagerAnim(target, next)
    }

    private fun startPagerAnim(to: Float, commitFace: DreamClockFace?) {
        cancelFaceHandoff()
        pagerFrom = pagerOffset
        pagerTo = to
        pagerAnimAt = SystemClock.elapsedRealtime()
        pagerAnimating = true
        pagerCommitFace = commitFace
        invalidate()
    }

    private fun cancelFaceHandoff() {
        faceHandoffFrom = null
        faceHandoffT = 1f
    }

    /**
     * Chip-driven FILL↔FLIP: persist immediately so the sheet chips/chrome morph
     * with the clock, then play a short fold-and-rise behind the card.
     */
    private fun startFaceHandoff(next: DreamClockFace) {
        if (next == face) return
        if (faceHandoffFrom != null) return
        val prev = face
        if (pagerAnimating || abs(pagerOffset) > 0.01f) {
            publishFace(next)
            if (prev == DreamClockFace.FLIP && next != DreamClockFace.FLIP) {
                setFontRowExpanded(false)
            }
            if (pickerOpen || pickerClosing) syncFlipChromeTarget() else snapFlipChromeToFace()
            if (next == DreamClockFace.FLIP) flipBoard.beginEntrance()
            return
        }
        faceHandoffFrom = prev
        faceHandoffT = 0f
        faceHandoffAt = SystemClock.elapsedRealtime()
        publishFace(next)
        if (prev == DreamClockFace.FLIP && next != DreamClockFace.FLIP) {
            setFontRowExpanded(false)
        }
        if (pickerOpen || pickerClosing) syncFlipChromeTarget() else snapFlipChromeToFace()
        if (next == DreamClockFace.FLIP) flipBoard.beginEntrance()
        invalidate()
    }

    private fun tickFaceHandoff(): Boolean {
        if (faceHandoffFrom == null) return false
        val t = ((SystemClock.elapsedRealtime() - faceHandoffAt) / faceHandoffMs).coerceIn(0f, 1f)
        faceHandoffT = easeOutCubic(t)
        if (t >= 1f) {
            cancelFaceHandoff()
            return false
        }
        return true
    }

    private fun drawFaceHandoff(canvas: Canvas) {
        val from = faceHandoffFrom ?: return
        val t = faceHandoffT
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val outA = (1f - t).coerceIn(0f, 1f)
        val inA = t.coerceIn(0f, 1f)
        val outS = 1f - 0.10f * t
        val inS = 0.90f + 0.10f * t
        val outY = h * 0.032f * t
        val inY = h * -0.026f * (1f - t)

        canvas.save()
        canvas.translate(0f, outY)
        canvas.scale(outS, outS, cx, cy)
        val outLayer = canvas.saveLayerAlpha(0f, 0f, w, h, (outA * 255f).toInt())
        drawFacePage(canvas, from)
        canvas.restoreToCount(outLayer)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, inY)
        canvas.scale(inS, inS, cx, cy)
        val inLayer = canvas.saveLayerAlpha(0f, 0f, w, h, (inA * 255f).toInt())
        drawFacePage(canvas, face)
        canvas.restoreToCount(inLayer)
        canvas.restore()
    }

    private fun tickPager() {
        if (!pagerAnimating) return
        val t = ((SystemClock.elapsedRealtime() - pagerAnimAt) / pagerAnimMs).coerceIn(0f, 1f)
        // Soft quintic settle — matches the sheet morph cadence.
        val eased = easeSoft(t)
        pagerOffset = pagerFrom + (pagerTo - pagerFrom) * eased
        if (t >= 1f) {
            pagerAnimating = false
            pagerCommitFace?.let { next ->
                val prev = face
                publishFace(next)
                onFacePicked?.invoke(next)
                if (next == DreamClockFace.FLIP) flipBoard.beginEntrance()
                if (pickerOpen || pickerClosing) {
                    if (prev == DreamClockFace.FLIP && next != DreamClockFace.FLIP) {
                        setFontRowExpanded(false)
                    }
                    syncFlipChromeTarget()
                } else {
                    snapFlipChromeToFace()
                }
            }
            pagerCommitFace = null
            pagerOffset = 0f
        }
    }

    private fun settleColor(vx: Float) {
        if (!overlayThemeGesturesAllowed()) {
            colorOffset = 0f
            colorAnimating = false
            colorCommitStyle = null
            invalidate()
            return
        }
        if (abs(colorOffset) < 0.02f) {
            colorOffset = 0f
            invalidate()
            return
        }
        val flick = abs(vx) > 350f && abs(colorOffset) > 0.02f
        val commit = flick || abs(colorOffset) >= 0.08f
        if (!commit) {
            startColorAnim(0f, null)
            return
        }
        val target = if (colorOffset > 0f) 1f else -1f
        val next = if (colorOffset > 0f) flipBoard.style.previous() else flipBoard.style.next()
        startColorAnim(target, next)
    }

    private fun startColorAnim(to: Float, commitStyle: DreamClockFlipStyle?) {
        colorFrom = colorOffset
        colorTo = to
        colorAnimAt = SystemClock.elapsedRealtime()
        colorAnimating = true
        colorCommitStyle = commitStyle
        invalidate()
    }

    private fun tickColorPager() {
        if (!colorAnimating) return
        val t = ((SystemClock.elapsedRealtime() - colorAnimAt) / colorAnimMs).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        colorOffset = colorFrom + (colorTo - colorFrom) * eased
        if (t >= 1f) {
            colorAnimating = false
            colorCommitStyle?.let { next ->
                flipBoard.style = next
                commitStyle(next)
            }
            colorCommitStyle = null
            colorOffset = 0f
        }
    }

    private fun revealChrome() {
        if (aodOwnsPlate()) return
        handler.removeCallbacks(hideChromeRunnable)
        startChromeFade(1f)
        handler.postDelayed(hideChromeRunnable, chromeHideMs)
    }

    private fun startChromeFade(target: Float) {
        chromeFrom = chromeAlpha
        chromeTo = target
        chromeAnimAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    /** Smoothstep-style ease-in-out: gentle start, gentle landing. */
    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

    private fun tickChromeFade() {
        // Hide is slower than reveal so the controls seem to settle away rather than snap.
        val dur = if (chromeTo < chromeFrom) chromeHideFadeMs else chromeFadeMs
        val t = ((SystemClock.elapsedRealtime() - chromeAnimAt) / dur).coerceIn(0f, 1f)
        chromeAlpha = chromeFrom + (chromeTo - chromeFrom) * easeInOut(t)
    }

    /** 0→1 card-slide progress for the settings sheet, eased both ways. */
    private fun pickerAnimT(): Float {
        if (pickerClosing) {
            val t = ((SystemClock.elapsedRealtime() - pickerCloseAt) / pickerCloseMs).coerceIn(0f, 1f)
            if (t >= 1f) {
                pickerClosing = false
                return 0f
            }
            return 1f - easeInOut(t)
        }
        if (!pickerOpen) return 0f
        val t = ((SystemClock.elapsedRealtime() - pickerOpenAt) / pickerAnimMs).coerceIn(0f, 1f)
        // Ease-out with a soft start: quintic tail settles without overshoot.
        val u = 1f - t
        return 1f - u * u * u * u * u
    }

    private fun closePicker() {
        if (!pickerOpen && !pickerClosing) return
        pickerOpen = false
        pickerClosing = true
        pickerCloseAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    private fun openPicker() {
        if (!overlaySettingsAllowed() || aodOwnsPlate()) return
        pickerOpen = true
        fontRowExpanded = false
        fontExpandT = 0f
        snapFlipChromeToFace()
        stripFlinging = false
        stripVx = 0f
        pickerOpenAt = SystemClock.elapsedRealtime()
        val density = resources.displayMetrics.density
        val chipW = DreamClockFlipBoard.CHIP_WIDTH_DP * density
        val gap = DreamClockFlipBoard.CHIP_GAP_DP * density
        val index = flipBoard.style.ordinal
        val target = index * (chipW + gap) - (stripViewportWidth() - chipW) / 2f
        pickerScrollX = target.coerceIn(0f, pickerMaxScroll())
        revealChrome()
        invalidate()
    }

    /** Visible width of the theme strip — the A pill takes the right end as FLIP chrome reveals. */
    private fun stripViewportWidth(): Float {
        val density = resources.displayMetrics.density
        val rowPad = sheetInnerPad()
        val contentW = width - sheetInsetH() * 2f - rowPad * 2f
        val pillReserve = (FONT_PILL_W_DP * density + rowPad) * flipChromeT
        return contentW - pillReserve
    }

    // Theme strip kinetics: finger-tracked drag, then a free-running decaying fling on
    // release. No snapping — the strip stops wherever the momentum dies.
    private var stripDragging = false
    private var stripVx = 0f
    private var stripLastMoveT = 0L
    private var stripFlinging = false
    private var stripFlingLastT = 0L
    private val STRIP_FLING_MIN_VX = 600f
    private val STRIP_FLING_DECAY = 3.2f
    private val STRIP_FLING_STOP_VX = 40f

    /** Advance the fling. Returns true while the strip is still moving. */
    private fun tickStrip(): Boolean {
        if (!stripFlinging) return false
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - stripFlingLastT).coerceAtLeast(1L) / 1000f).coerceAtMost(0.05f)
        stripFlingLastT = now
        val max = pickerMaxScroll()
        val prevScroll = pickerScrollX
        pickerScrollX += stripVx * dt
        var stopped = false
        if (pickerScrollX <= 0f || pickerScrollX >= max) {
            pickerScrollX = pickerScrollX.coerceIn(0f, max)
            stopped = true
        } else {
            // Pure exponential decay — it just runs out of steam wherever it is.
            stripVx *= kotlin.math.exp(-STRIP_FLING_DECAY * dt)
            if (abs(stripVx) < STRIP_FLING_STOP_VX) stopped = true
        }
        val density = resources.displayMetrics.density
        val step = (DreamClockFlipBoard.CHIP_WIDTH_DP + DreamClockFlipBoard.CHIP_GAP_DP) * density
        if (travelTickChanged(prevScroll, pickerScrollX, step)) {
            OverlayHaptics.tick(context, this)
        }
        if (stopped) {
            stripFlinging = false
            stripVx = 0f
            // The sheet's idle clock only starts once the strip has actually come to rest.
            revealChrome()
            return false
        }
        return true
    }

    private fun pickerMaxScroll(): Float {
        val density = resources.displayMetrics.density
        val chipW = DreamClockFlipBoard.CHIP_WIDTH_DP * density
        val gap = DreamClockFlipBoard.CHIP_GAP_DP * density
        val count = DreamClockFlipStyle.entries.size
        val content = count * chipW + (count - 1) * gap
        return (content - stripViewportWidth()).coerceAtLeast(0f)
    }
    
    
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brandMainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brandSubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourHandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minHandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secHandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lumePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dateWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    init {
        
        tickPaint.apply {
            color = Color.argb(64, 255, 255, 255)
            strokeWidth = 1f
            strokeCap = Paint.Cap.ROUND
        }
        majorTickPaint.apply {
            color = metalLight
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(8f, 0f, 0f, Color.argb(77, 255, 255, 255))
        }
        
        numeralPaint.apply {
            color = metalLight
            textSize = clockSize * 0.08f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setShadowLayer(15f, 0f, 2f, Color.argb(102, 255, 255, 255))
        }
        
        brandMainPaint.apply {
            color = metalLight
            textSize = clockSize * 0.035f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.15f
            setShadowLayer(4f, 0f, 2f, Color.argb(204, 0, 0, 0))
        }
        
        brandSubPaint.apply {
            color = accentGlow
            textSize = clockSize * 0.02f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.1f
        }
        
        datePaint.apply {
            color = metalLight
            textSize = clockSize * 0.04f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        
        tempPaint.apply {
            color = Color.argb(230, 255, 255, 255)
            textSize = clockSize * 0.04f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        
        hourHandPaint.apply {
            color = metalLight
            style = Paint.Style.FILL
        }
        
        minHandPaint.apply {
            color = metalLight
            style = Paint.Style.FILL
        }
        
        secHandPaint.apply {
            color = accentGlow
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(4f, 0f, 0f, accentGlow)
        }
        
        lumePaint.apply {
            color = lumeColor
            style = Paint.Style.FILL
            setShadowLayer(4f, 0f, 0f, Color.argb(102, 204, 255, 204))
        }
        
        centerPaint.apply {
            style = Paint.Style.FILL
        }
        
        dateWindowPaint.apply {
            color = Color.parseColor("#151515")
            style = Paint.Style.FILL
        }
        
        moonPaint.apply {
            style = Paint.Style.FILL
        }
    }
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            // Local-only countdown reached zero: hold 00:00:00 and ring until stopped.
            if (!timerEntityBound && flipBoard.countdownFinished) enterFinishedHold()
            invalidate()
            if (isRunning) {
                val fading = chromeAlpha != chromeTo
                val paging = pagerAnimating || abs(pagerOffset) > 0.01f || colorAnimating || abs(colorOffset) > 0.01f ||
                    faceHandoffFrom != null
                val sheetSliding = (pickerOpen && pickerAnimT() < 1f) || pickerClosing
                val lifting = abs(pickerLiftT - if (pickerOpen) 1f else 0f) > 0.01f
                val aodFading = aodAlpha != aodTo
                val flippingChrome = abs(flipChromeT - flipChromeTarget) > 0.01f ||
                    abs(fontExpandT - if (fontRowExpanded) 1f else 0f) > 0.01f
                val stepping = stepperNudgeEdit != null
                val interval = if (face == DreamClockFace.FLIP || fading || paging || sheetSliding || lifting || aodFading || flippingChrome || timerFinishedHold || stepping) 16L else 67L
                handler.postDelayed(this, interval)
            }
        }
    }
    
    fun startClock() {
        isRunning = true
        handler.post(updateRunnable)
    }
    
    fun stopClock() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(hideChromeRunnable)
        handler.removeCallbacks(chromeRevealRunnable)
        handler.removeCallbacks(enterAodRunnable)
        recycleEditorBlur()
        customEditorOpen = false
        // Flush a held theme so hide/stop never loses the user's last pick.
        if (stylePending != null) {
            handler.removeCallbacks(styleCommitRunnable)
            styleCommitRunnable.run()
        }
    }
    
    /** Sheet height incl. font + timer rows (FLIP only); shared by picker, lift, and button. */
    private fun sheetHeightPx(): Float {
        val density = resources.displayMetrics.density
        val pad = sheetInnerPad()
        val portrait = width < height
        val chipH = (if (portrait) 56f else DreamClockFlipBoard.CHIP_HEIGHT_DP) * density
        val faceH = (if (portrait) 48f else 52f) * density
        val flip = flipChromeT
        val themeUi = overlayThemeGesturesAllowed()
        val fontH = if (themeUi) {
            ((if (portrait) 40f else 44f) * density + pad) * fontExpandT * flip
        } else {
            0f
        }
        val timerH = (timerRowDp() * density + pad) * flip
        val chipGap = (if (portrait) 8f else 6f) * density
        val extrasRow = if (themeUi && portrait) (faceH + chipGap) * flip else 0f
        val themeStrip = if (themeUi) chipH + pad else 0f
        return pad + themeStrip + fontH + timerH + extrasRow + faceH + pad + sheetInsetBottom()
    }

    private fun timerRowDp(): Float = 64f

    private val FONT_PILL_W_DP = 48f
    /** Floating settings card: inset from the screen edges. */
    private val SHEET_MARGIN_H_DP = 20f
    private val SHEET_MARGIN_BOTTOM_DP = 10f
    /** Content inset inside the floating card (top / bottom / left / right). */
    private val SHEET_INNER_PAD_DP = 24f

    private fun sheetInsetH(): Float {
        val density = resources.displayMetrics.density
        val marginDp = if (width > 0 && width < height) 12f else SHEET_MARGIN_H_DP
        return marginDp * density
    }

    private fun sheetInnerPad(): Float = SHEET_INNER_PAD_DP * resources.displayMetrics.density

    /** Bottom gap = 10dp above the safe inset (gesture / nav bar). */
    private fun sheetInsetBottom(): Float =
        SHEET_MARGIN_BOTTOM_DP * resources.displayMetrics.density + safeBottomPx

    private fun fitPickerLabel(text: String, maxWidth: Float, maxSize: Float, minSize: Float): Float {
        var size = maxSize
        pickerTextPaint.textSize = size
        while (size > minSize && pickerTextPaint.measureText(text) > maxWidth) {
            size -= 1f
            pickerTextPaint.textSize = size
        }
        return size
    }

    private fun faceChipLabel(item: DreamClockFace): String = when (item) {
        DreamClockFace.MECHANICAL -> context.getString(R.string.settings_dream_clock_face_mechanical_chip)
        DreamClockFace.FILL -> context.getString(R.string.settings_dream_clock_face_fill_chip)
        DreamClockFace.FLIP -> context.getString(R.string.settings_dream_clock_face_flip_chip)
    }

    private fun drawFaceChip(
        canvas: Canvas,
        item: DreamClockFace,
        rect: RectF,
        slideY: Float,
        density: Float,
        labelMax: Float,
        labelMin: Float,
    ) {
        pickerFaceHits.add(RectF(rect.left, rect.top + slideY, rect.right, rect.bottom + slideY) to item)
        pickerPaint.color = if (item == face) Color.WHITE else Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, pickerPaint)
        val label = faceChipLabel(item)
        pickerTextPaint.color = if (item == face) Color.BLACK else Color.WHITE
        fitPickerLabel(label, rect.width() - 12f * density, labelMax, labelMin)
        val clip = canvas.save()
        canvas.clipRect(rect)
        canvas.drawText(label, rect.centerX(), rect.centerY() + pickerTextPaint.textSize * 0.35f, pickerTextPaint)
        canvas.restoreToCount(clip)
    }

    private fun drawSecHourChips(
        canvas: Canvas,
        secRect: RectF,
        hourRect: RectF,
        slideY: Float,
        density: Float,
        interactive: Boolean,
        flipT: Float,
    ) {
        val extrasAlpha = (flipT * 255f).toInt().coerceIn(0, 255)
        val layer = canvas.saveLayerAlpha(
            min(secRect.left, hourRect.left),
            min(secRect.top, hourRect.top),
            max(secRect.right, hourRect.right),
            max(secRect.bottom, hourRect.bottom),
            extrasAlpha,
        )
        if (interactive) {
            secondsToggleRect.set(secRect.left, secRect.top + slideY, secRect.right, secRect.bottom + slideY)
            twelveHourToggleRect.set(hourRect.left, hourRect.top + slideY, hourRect.right, hourRect.bottom + slideY)
        }
        val secOn = flipBoard.showSeconds
        pickerPaint.color = if (secOn) Color.WHITE else Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(secRect, 12f * density, 12f * density, pickerPaint)
        pickerTextPaint.color = if (secOn) Color.BLACK else Color.WHITE
        val secLabel = context.getString(R.string.settings_dream_clock_show_seconds_short)
        fitPickerLabel(secLabel, secRect.width() - 10f * density, 14f * density, 11f * density)
        val secClip = canvas.save()
        canvas.clipRect(secRect)
        canvas.drawText(secLabel, secRect.centerX(), secRect.centerY() + pickerTextPaint.textSize * 0.35f, pickerTextPaint)
        canvas.restoreToCount(secClip)

        val twelve = flipBoard.twelveHour
        pickerPaint.color = if (twelve) Color.WHITE else Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(hourRect, 12f * density, 12f * density, pickerPaint)
        pickerTextPaint.color = if (twelve) Color.BLACK else Color.WHITE
        val hourLabel = if (twelve) "12h" else "24h"
        fitPickerLabel(hourLabel, hourRect.width() - 10f * density, 14f * density, 11f * density)
        val hourClip = canvas.save()
        canvas.clipRect(hourRect)
        canvas.drawText(hourLabel, hourRect.centerX(), hourRect.centerY() + pickerTextPaint.textSize * 0.35f, pickerTextPaint)
        canvas.restoreToCount(hourClip)
        canvas.restoreToCount(layer)
    }

    /** Cards reflow into the space above the sheet, then nudge a touch more. */
    private fun clockLayoutHeight(): Int {
        val reserved = pickerLiftT * min(sheetHeightPx() * 0.45f, height * 0.18f)
        return (height - reserved).toInt().coerceIn((height * 0.78f).toInt(), height)
    }

    /** Extra upward shift so the clock sits clearly above the sheet. */
    private fun clockNudgePx(): Float {
        val density = resources.displayMetrics.density
        return pickerLiftT * (16f * density)
    }


    private var liftAnimAt = 0L
    private var liftFrom = 0f
    private var liftTo = 0f
    private val liftDurationMs = 400f

    private fun tickPickerLift() {
        val target = if (pickerOpen) 1f else 0f
        if (liftTo != target) {
            liftFrom = pickerLiftT
            liftTo = target
            liftAnimAt = SystemClock.elapsedRealtime()
        }
        val elapsed = (SystemClock.elapsedRealtime() - liftAnimAt) / liftDurationMs
        val t = elapsed.coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        pickerLiftT = liftFrom + (liftTo - liftFrom) * eased
        if (t < 1f) invalidate()
    }

    private val windowClipPath = android.graphics.Path()
    private val windowCornerPx by lazy { 16f * resources.displayMetrics.density }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.BLACK)
        windowClipPath.reset()
        windowClipPath.addRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            windowCornerPx, windowCornerPx,
            android.graphics.Path.Direction.CW,
        )
        canvas.clipPath(windowClipPath)
        canvas.drawColor(Color.BLACK)
        if (!showClockFace) return

        tickPager()
        tickColorPager()
        tickPickerLift()
        val handingOff = tickFaceHandoff()
        val nudge = clockNudgePx()
        val lifted = pickerLiftT > 0.01f
        if (lifted) {
            canvas.save()
            canvas.translate(0f, -nudge)
        }
        val pageY = pagerOffset * height
        if (handingOff || faceHandoffFrom != null) {
            drawFaceHandoff(canvas)
        } else if (abs(pageY) < 1f) {
            drawFacePage(canvas, face)
        } else {
            val incoming = if (pagerOffset > 0f) face.previousSwitchable() else face.nextSwitchable()
            drawPagedFace(canvas, face, pageY)
            drawPagedFace(canvas, incoming, pageY - sign(pagerOffset) * height)
        }
        if (lifted) canvas.restore()
        tickChromeFade()
        val showPicker = pickerOpen || pickerClosing
        if (pickerOpen && tickStrip()) invalidate()
        timerPauseRect.setEmpty()
        timerCancelRect.setEmpty()
        if (aodOwnsPlate()) {
            settingsButtonRect.setEmpty()
        } else if (!customEditorOpen && (chromeAlpha > 0.01f || showPicker)) {
            val effectiveAlpha = if (showPicker) 1f else chromeAlpha
            val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (effectiveAlpha * 255).toInt())
            // Button first so it tucks under the rising sheet, then the sheet on top.
            if (overlaySettingsAllowed()) drawSettingsButton(canvas) else settingsButtonRect.setEmpty()
            if (showPicker) {
                drawPicker(canvas)
            } else if (face == DreamClockFace.FLIP && flipBoard.isCountingDown && !timerFinishedHold) {
                // Corner ⏸/✕ only while actually counting; at 00:00:00 the centered ■ owns exit.
                drawTimerCornerButtons(canvas)
            }
            canvas.restoreToCount(save)
        }
        stopAlarmRect.setEmpty()
        if (timerFinishedHold) drawStopAlarmButton(canvas)
        if (customEditorOpen && !capturingEditorBackdrop) drawCustomEditor(canvas)
        tickAod()
        if (aodAlpha > 0.005f) {
            canvas.drawColor(Color.argb((aodAlpha * 255f).toInt(), 0, 0, 0))
        }
    }

    private val hSwipeClipPath = android.graphics.Path()

    private fun applyHorizontalDepth(canvas: Canvas, w: Float, h: Float, dist: Float) {
        val density = resources.displayMetrics.density
        val fV = 1f - dist
        val scale = 0.92f + 0.08f * fV
        val alpha = 0.40f + 0.60f * fV
        val cornerPx = PAGE_CORNER_MAX_DP * density * dist
        canvas.scale(scale, scale, w / 2f, h / 2f)
        hSwipeClipPath.reset()
        hSwipeClipPath.addRoundRect(0f, 0f, w, h, cornerPx, cornerPx, android.graphics.Path.Direction.CW)
        canvas.clipPath(hSwipeClipPath)
        canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
    }

    private fun drawPagedFill(canvas: Canvas, painted: DreamClockFlipStyle, left: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dist = abs(left / w).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(left, 0f)
        applyHorizontalDepth(canvas, w, h, dist)
        drawFillFace(canvas, painted)
        canvas.restore()
        canvas.restore()
    }

    private fun drawPagedColor(canvas: Canvas, painted: DreamClockFlipStyle, left: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dist = abs(left / w).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(left, 0f)
        applyHorizontalDepth(canvas, w, h, dist)
        flipBoard.draw(canvas, width, height, painted, animate = false, layoutHeight = clockLayoutHeight())
        canvas.restore()
        canvas.restore()
    }

    private val pagerClipPath = android.graphics.Path()

    private fun drawPagedFace(canvas: Canvas, which: DreamClockFace, top: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        val dist = abs(top / h).coerceIn(0f, 1f)
        val fV = 1f - dist
        val scale = 0.82f + 0.18f * fV
        val alpha = (0.25f + 0.75f * fV).coerceIn(0f, 1f)
        val cornerPx = PAGE_CORNER_MAX_DP * density * dist

        canvas.save()
        canvas.translate(0f, top)
        canvas.scale(scale, scale, w / 2f, h / 2f)
        pagerClipPath.reset()
        pagerClipPath.addRoundRect(0f, 0f, w, h, cornerPx, cornerPx, android.graphics.Path.Direction.CW)
        canvas.clipPath(pagerClipPath)
        val savedAlpha = canvas.saveLayerAlpha(0f, 0f, w, h, (alpha * 255f).toInt())
        drawFacePage(canvas, which)
        canvas.restoreToCount(savedAlpha)
        canvas.restore()
    }

    private fun drawFacePage(canvas: Canvas, which: DreamClockFace) {
        when (which) {
            DreamClockFace.MECHANICAL -> {
                canvas.drawColor(Color.BLACK)
                drawMechanicalFace(canvas)
            }
            DreamClockFace.FILL -> {
                val shift = colorOffset * width
                if (abs(shift) < 1f) {
                    drawFillFace(canvas, flipBoard.style)
                    val outAlpha = styleFadeOutAlpha()
                    val outStyle = styleFadeFrom
                    if (outAlpha != null && outStyle != null) {
                        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (outAlpha * 255f).toInt())
                        drawFillFace(canvas, outStyle)
                        canvas.restoreToCount(save)
                        invalidate()
                    }
                } else {
                    val incoming = if (colorOffset > 0f) flipBoard.style.previous() else flipBoard.style.next()
                    drawPagedFill(canvas, flipBoard.style, shift)
                    drawPagedFill(canvas, incoming, shift - sign(colorOffset) * width)
                }
            }
            DreamClockFace.FLIP -> {
                val shift = colorOffset * width
                val layoutH = clockLayoutHeight()
                if (abs(shift) < 1f) {
                    flipBoard.draw(canvas, width, height, layoutHeight = layoutH)
                    val outAlpha = styleFadeOutAlpha()
                    val outStyle = styleFadeFrom
                    if (outAlpha != null && outStyle != null) {
                        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (outAlpha * 255f).toInt())
                        flipBoard.draw(canvas, width, height, painted = outStyle, animate = false, layoutHeight = layoutH)
                        canvas.restoreToCount(save)
                        invalidate()
                    }
                } else {
                    val incoming = if (colorOffset > 0f) flipBoard.style.previous() else flipBoard.style.next()
                    drawPagedColor(canvas, flipBoard.style, shift)
                    drawPagedColor(canvas, incoming, shift - sign(colorOffset) * width)
                }
            }
        }
    }

    private var paintSpan = -1

    private fun fitDialPaints(span: Int) {
        if (span <= 1 || span == paintSpan) return
        paintSpan = span
        numeralPaint.textSize = span * 0.08f
        brandMainPaint.textSize = span * 0.035f
        brandSubPaint.textSize = span * 0.02f
        datePaint.textSize = span * 0.04f
        tempPaint.textSize = span * 0.04f
    }

    private fun drawMechanicalFace(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val span = min(width, height)
        val basis = if (span > 1) span else clockSize
        fitDialPaints(basis)
        val radius = basis / 2f * 0.95f
        
        
        drawDialBackground(canvas, centerX, centerY, radius)
        
        
        drawMinuteTrack(canvas, centerX, centerY, radius)
        
        
        drawTicks(canvas, centerX, centerY, radius)
        
        
        drawNumerals(canvas, centerX, centerY, radius)
        
        
        drawBrand(canvas, centerX, centerY, radius)
        
        
        drawWeatherWithIcon(canvas, centerX, centerY, radius)
        
        
        drawDateWindow(canvas, centerX, centerY, radius)
        
        
        drawHands(canvas, centerX, centerY, radius)
        
        
        drawPinion(canvas, centerX, centerY, radius)
        
        
        drawCrystal(canvas, centerX, centerY, radius)
    }
    
    private fun drawDialBackground(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val bgGradient = android.graphics.RadialGradient(
            cx, cy - radius * 0.15f, radius,
            intArrayOf(bgNebula, bgDeep, Color.BLACK),
            floatArrayOf(0f, 0.7f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        bgPaint.shader = bgGradient
        canvas.drawCircle(cx, cy, radius, bgPaint)
        bgPaint.shader = null

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isDay = hour in 6..18
        val glowColor = if (isDay)
            intArrayOf(Color.argb(30, 255, 80, 80), Color.argb(15, 255, 100, 100), Color.TRANSPARENT)
        else
            intArrayOf(Color.argb(25, 162, 155, 254), Color.argb(12, 180, 170, 255), Color.TRANSPARENT)
        val glowRadius = radius * 0.85f
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                cx, cy, glowRadius,
                glowColor,
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            maskFilter = android.graphics.BlurMaskFilter(120f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)
    }
    
    private fun drawMinuteTrack(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(10, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }
        canvas.drawCircle(cx, cy, radius * 0.94f, trackPaint)
    }
    
    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for (i in 0 until 60) {
            
            if (i % 15 == 0) continue  
            if (i % 15 == 1 || i % 15 == 14) continue
            if (i % 15 == 2 || i % 15 == 13) continue
            if (i % 15 == 3 || i % 15 == 12) continue  
            
            val angle = Math.toRadians((i * 6 - 90).toDouble())
            val isMajor = i % 5 == 0
            val outerR = radius * 0.94f
            val innerR = if (isMajor) radius * 0.91f else radius * 0.925f
            val paint = if (isMajor) majorTickPaint else tickPaint
            
            val startX = cx + (outerR * Math.cos(angle)).toFloat()
            val startY = cy + (outerR * Math.sin(angle)).toFloat()
            val endX = cx + (innerR * Math.cos(angle)).toFloat()
            val endY = cy + (innerR * Math.sin(angle)).toFloat()
            
            canvas.drawLine(startX, startY, endX, endY, paint)
        }
    }
    
    private fun drawNumerals(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val d = resources.displayMetrics.density
        val offset = numeralPaint.textSize * 0.35f
        val pullIn = 39f * d
        val lift = 5f * d
        val out = 3f * d
        canvas.drawText("12", cx, cy - radius * 0.90f + offset + pullIn - lift - out, numeralPaint)
        canvas.drawText("6", cx, cy + radius * 0.88f + offset - pullIn - lift + out, numeralPaint)
        canvas.drawText("3", cx + radius * 0.82f, cy + offset - numeralPaint.textSize * 0.08f, numeralPaint)
        canvas.drawText("9", cx - radius * 0.82f, cy + offset - numeralPaint.textSize * 0.1f, numeralPaint)
    }
    
    private fun drawBrand(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isDay = hour in 6..18
        
        val d = resources.displayMetrics.density
        val brandY = cy - radius * 0.52f + 7f * d
        canvas.drawText("BONJOUR", cx, brandY, brandMainPaint)

        val subColor = if (isDay) Color.parseColor("#ff4444") else accentGlow
        brandSubPaint.color = subColor
        canvas.drawText(
            "Chronometer",
            cx,
            brandY + brandMainPaint.textSize + 8f * d,
            brandSubPaint,
        )
        
        
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 255, 255)
            textSize = radius * 0.035f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.15f
        }
        canvas.drawText("CENTER", cx, cy + radius * 0.15f, centerPaint)
    }
    
    
    private fun drawWeatherWithIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        try {
            val weatherY = cy + radius * 0.45f
            
            
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val isDay = hour in 6..18
            
            
            val isSmallScreen = clockSize <= 350
            
            
            val iconSize = if (isSmallScreen) (radius * 0.12f).toInt() else (radius * 0.10f).toInt()
            
            
            val iconResId = when (weatherCondition) {
                "sunny" -> if (isDay) R.drawable.mdi_weather_sunny else R.drawable.mdi_weather_night
                "cloudy" -> if (isDay) R.drawable.mdi_weather_partly_cloudy else R.drawable.mdi_weather_cloudy
                "light_rain", "rainy" -> R.drawable.mdi_weather_rainy
                "heavy_rain" -> R.drawable.mdi_weather_pouring
                "light_snow", "snowy" -> R.drawable.mdi_weather_snowy
                "heavy_snow" -> R.drawable.mdi_weather_snowy_heavy
                "fog" -> R.drawable.mdi_weather_fog
                "haze" -> R.drawable.mdi_weather_hazy
                "wind" -> R.drawable.mdi_weather_windy
                "sandstorm" -> R.drawable.mdi_weather_dust
                else -> if (isDay) R.drawable.mdi_weather_sunny else R.drawable.mdi_weather_night
            }
            
            
            val drawable = ContextCompat.getDrawable(context, iconResId)
            drawable?.let {
                val iconLeft = (cx - iconSize / 2).toInt()
                val iconTop = (weatherY - iconSize / 2).toInt()
                it.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                it.draw(canvas)
            }
            
        } catch (e: Exception) {
            
            drawWeather(canvas, cx, cy, radius)
        }
    }
    
    
    private enum class WeatherType { SUNNY, CLOUDY, RAINY, SNOWY, NIGHT_CLEAR, NIGHT_CLOUDY }

    private enum class DragLock { NONE, VERTICAL, HORIZONTAL, PICKER }
    
    private fun drawWeather(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val weatherY = cy + radius * 0.45f
        
        
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isDay = hour in 6..18
        
        
        val iconX = cx - radius * 0.08f
        val iconY = weatherY
        val iconSize = radius * 0.028f
        
        
        val weather = when (weatherCondition) {
            "sunny" -> if (isDay) WeatherType.SUNNY else WeatherType.NIGHT_CLEAR
            "cloudy" -> if (isDay) WeatherType.CLOUDY else WeatherType.NIGHT_CLOUDY
            "rainy" -> WeatherType.RAINY
            "snowy" -> WeatherType.SNOWY
            else -> if (isDay) WeatherType.SUNNY else WeatherType.NIGHT_CLEAR
        }
        
        drawWeatherIcon(canvas, iconX, iconY, iconSize, weather)
        
        
        val tempTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textSize = radius * 0.055f
            textAlign = Paint.Align.LEFT
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC)
            letterSpacing = 0.02f
            setShadowLayer(2f, 0f, 1f, Color.argb(180, 0, 0, 0))
        }
        canvas.drawText("${weatherTemp}°C", iconX + radius * 0.08f, weatherY + tempTextPaint.textSize * 0.35f, tempTextPaint)
    }
    
    private fun drawWeatherIcon(canvas: Canvas, x: Float, y: Float, size: Float, type: WeatherType) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        when (type) {
            WeatherType.SUNNY -> {
                
                paint.color = Color.parseColor("#ffd700")
                paint.style = Paint.Style.FILL
                paint.setShadowLayer(size, 0f, 0f, Color.parseColor("#ffd700"))
                canvas.drawCircle(x, y, size, paint)
                
                paint.strokeWidth = 1.5f
                paint.style = Paint.Style.STROKE
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val x1 = x + (size * 1.4f * Math.cos(angle)).toFloat()
                    val y1 = y + (size * 1.4f * Math.sin(angle)).toFloat()
                    val x2 = x + (size * 2f * Math.cos(angle)).toFloat()
                    val y2 = y + (size * 2f * Math.sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            }
            WeatherType.CLOUDY -> {
                
                paint.color = Color.parseColor("#cccccc")
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x - size * 0.5f, y, size * 0.7f, paint)
                canvas.drawCircle(x + size * 0.3f, y - size * 0.2f, size * 0.8f, paint)
                canvas.drawCircle(x + size * 0.8f, y + size * 0.1f, size * 0.6f, paint)
            }
            WeatherType.RAINY -> {
                
                paint.color = Color.parseColor("#888888")
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x - size * 0.3f, y - size * 0.3f, size * 0.5f, paint)
                canvas.drawCircle(x + size * 0.3f, y - size * 0.4f, size * 0.6f, paint)
                
                
                paint.color = Color.parseColor("#6699ff")
                paint.strokeWidth = 1.5f
                paint.style = Paint.Style.STROKE
                canvas.drawLine(x - size * 0.4f, y + size * 0.3f, x - size * 0.5f, y + size * 0.8f, paint)
                canvas.drawLine(x, y + size * 0.4f, x - size * 0.1f, y + size * 0.9f, paint)
                canvas.drawLine(x + size * 0.4f, y + size * 0.3f, x + size * 0.3f, y + size * 0.8f, paint)
            }
            WeatherType.SNOWY -> {
                
                paint.color = Color.parseColor("#aaaaaa")
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x - size * 0.3f, y - size * 0.3f, size * 0.5f, paint)
                canvas.drawCircle(x + size * 0.3f, y - size * 0.4f, size * 0.6f, paint)
                
                
                paint.color = Color.WHITE
                canvas.drawCircle(x - size * 0.4f, y + size * 0.5f, size * 0.15f, paint)
                canvas.drawCircle(x, y + size * 0.7f, size * 0.15f, paint)
                canvas.drawCircle(x + size * 0.4f, y + size * 0.5f, size * 0.15f, paint)
            }
            WeatherType.NIGHT_CLEAR -> {
                
                paint.color = Color.parseColor("#e0e0e0")
                paint.style = Paint.Style.FILL
                paint.setShadowLayer(size * 0.5f, 0f, 0f, Color.argb(128, 255, 255, 255))
                canvas.drawCircle(x, y, size, paint)
                paint.color = Color.parseColor("#101030")
                canvas.drawCircle(x + size * 0.4f, y - size * 0.2f, size * 0.7f, paint)
            }
            WeatherType.NIGHT_CLOUDY -> {
                
                paint.color = Color.parseColor("#888888")
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x + size * 0.5f, y + size * 0.2f, size * 0.6f, paint)
                
                paint.color = Color.parseColor("#cccccc")
                canvas.drawCircle(x - size * 0.3f, y - size * 0.2f, size * 0.7f, paint)
            }
        }
        paint.clearShadowLayer()
    }
    
    private fun drawDateWindow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        
        val dateX = cx + radius * 0.45f
        val dateY = cy + radius * 0.25f
        val windowRadius = radius * 0.075f
        
        
        canvas.drawCircle(dateX, dateY, windowRadius, dateWindowPaint)
        
        
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#444444")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawCircle(dateX, dateY, windowRadius, borderPaint)
        
        
        val calendar = Calendar.getInstance()
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        canvas.drawText(day.toString(), dateX, dateY + datePaint.textSize * 0.35f, datePaint)
    }
    
    private fun drawHands(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val millis = calendar.get(Calendar.MILLISECOND)
        
        
        val sDeg = second * 6f + millis * 0.006f
        val mDeg = minute * 6f + second * 0.1f
        val hDeg = hour * 30f + minute * 0.5f
        
        
        drawTauffeeHand(canvas, cx, cy, hDeg, radius * 0.4f, radius * 0.03f, true)
        
        
        drawTauffeeHand(canvas, cx, cy, mDeg, radius * 0.65f, radius * 0.02f, true)
        
        
        drawSecondHand(canvas, cx, cy, sDeg, radius)
    }
    
    private fun drawTauffeeHand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, width: Float, hasLume: Boolean) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        
        
        val path = android.graphics.Path().apply {
            moveTo(cx, cy - length)  
            lineTo(cx + width / 2, cy - length * 0.12f)  
            lineTo(cx + width / 2, cy)  
            lineTo(cx - width / 2, cy)  
            lineTo(cx - width / 2, cy - length * 0.12f)  
            close()
        }
        
        
        hourHandPaint.shader = android.graphics.LinearGradient(
            cx - width / 2, cy, cx + width / 2, cy,
            intArrayOf(Color.parseColor("#d8d8d8"), Color.parseColor("#fbfbfb")),
            floatArrayOf(0.5f, 0.5f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, hourHandPaint)
        hourHandPaint.shader = null
        
        
        if (hasLume) {
            val lumeRect = android.graphics.RectF(
                cx - 1f, cy - length * 0.9f,
                cx + 1f, cy - length * 0.15f
            )
            canvas.drawRect(lumeRect, lumePaint)
        }
        
        canvas.restore()
    }
    
    private fun drawSecondHand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, radius: Float) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        
        val length = radius * 0.7f
        val tailLength = radius * 0.18f
        
        
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isDay = hour in 6..18
        val secColor = if (isDay) Color.parseColor("#ff4444") else accentGlow
        
        
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secColor
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            setShadowLayer(4f, 0f, 0f, secColor)
        }
        canvas.drawLine(cx, cy + tailLength, cx, cy - length, secPaint)
        
        
        val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secColor
            style = Paint.Style.FILL
            setShadowLayer(4f, 0f, 0f, secColor)
        }
        val counterRect = android.graphics.RectF(
            cx - 3f, cy + tailLength * 0.3f,
            cx + 3f, cy + tailLength * 0.9f
        )
        canvas.drawRoundRect(counterRect, 2f, 2f, counterPaint)
        
        canvas.restore()
    }
    
    private fun drawPinion(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val pinionRadius = radius * 0.02f
        
        
        centerPaint.shader = android.graphics.RadialGradient(
            cx - pinionRadius * 0.3f, cy - pinionRadius * 0.3f, pinionRadius,
            intArrayOf(metalLight, Color.parseColor("#444444")),
            null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, pinionRadius, centerPaint)
        centerPaint.shader = null
        
        
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawCircle(cx, cy, pinionRadius, borderPaint)
    }
    
    private fun drawCrystal(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        
        val crystalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.argb(5, 255, 255, 255)),
                floatArrayOf(0.7f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, crystalPaint)
        
    }

    /** Representative backdrop color used only to pick a readable ink. */
    private fun backdropBase(style: DreamClockFlipStyle): Int = when (val b = style.backdrop) {
        is DreamClockFlipBackdrop.Solid -> b.color
        is DreamClockFlipBackdrop.Linear -> ColorUtils.blendARGB(b.start, b.end, 0.5f)
        is DreamClockFlipBackdrop.Image -> when (b.image) {
            DreamClockFlipImage.ONE, DreamClockFlipImage.FIVE -> Color.BLACK
            DreamClockFlipImage.TWO -> 0xFF153F32.toInt()
            DreamClockFlipImage.THREE -> 0xFFFEFFFE.toInt()
            DreamClockFlipImage.FOUR -> 0xFF01002E.toInt()
        }
    }

    private fun luminanceGap(a: Int, b: Int): Double =
        abs(ColorUtils.calculateLuminance(a) - ColorUtils.calculateLuminance(b))

    /** Card color when it reads against the backdrop, otherwise the digit color. */
    private fun readableInk(colors: DreamClockFlipCardColors, base: Int): Int =
        if (luminanceGap(colors.card, base) >= luminanceGap(colors.text, base)) colors.card else colors.text

    private fun analogInk(style: DreamClockFlipStyle): Int =
        readableInk(style.hours, backdropBase(style))

    /** Minute hand: theme minutes slot when it differs, else a soft shade of the hour ink. */
    private fun analogMinuteInk(style: DreamClockFlipStyle, ink: Int): Int {
        val candidate = readableInk(style.minutes, backdropBase(style))
        return if (candidate != ink) candidate else ColorUtils.blendARGB(ink, 0xFF9E9E9E.toInt(), 0.35f)
    }

    private fun analogAccent(style: DreamClockFlipStyle, ink: Int): Int {
        if (style == DreamClockFlipStyle.BLACK || style == DreamClockFlipStyle.WHITE) {
            return 0xFFF44336.toInt()
        }
        val candidate = readableInk(style.seconds, backdropBase(style))
        return if (candidate != ink) candidate else 0xFFF44336.toInt()
    }

    private fun drawFillFace(canvas: Canvas, painted: DreamClockFlipStyle) {
        flipBoard.paintBackdrop(canvas, 0f, 0f, width.toFloat(), height.toFloat(), painted)
        val ink = analogInk(painted)
        val minuteInk = analogMinuteInk(painted, ink)
        val accent = analogAccent(painted, ink)
        fillTickPaint.color = ink
        fillNumeralPaint.color = ink
        fillWeekdayPaint.color = accent
        fillDayPaint.color = ink
        fillHandPaint.color = ink
        fillSecondPaint.color = accent
        val pad = 12f * resources.displayMetrics.density
        val innerW = width - pad * 2f
        val innerH = height - pad * 2f
        val cx = pad + innerW / 2f
        val cy = pad + innerH / 2f
        val handRadius = min(innerW, innerH) / 2f
        drawFillTicks(canvas, cx, cy, innerW / 2f, innerH / 2f)
        drawFillNumerals(canvas, cx, cy, innerW / 2f, innerH / 2f, handRadius)
        drawFillDate(canvas, cx, cy, innerW / 2f, handRadius)
        drawFillHands(canvas, cx, cy, handRadius, ink, minuteInk, accent)
    }

    private fun drawFillHands(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        ink: Int,
        minuteInk: Int,
        accent: Int,
    ) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val millis = calendar.get(Calendar.MILLISECOND)
        val sDeg = second * 6f + millis * 0.006f
        val mDeg = minute * 6f + second * 0.1f
        val hDeg = hour * 30f + minute * 0.5f
        fillHandPaint.color = ink
        drawFillTaperedHand(canvas, cx, cy, hDeg, radius * 0.48f, radius * 0.044f)
        fillHandPaint.color = minuteInk
        drawFillTaperedHand(canvas, cx, cy, mDeg, radius * 0.74f, radius * 0.030f)
        canvas.save()
        canvas.rotate(sDeg, cx, cy)
        fillSecondPaint.color = accent
        fillSecondPaint.strokeWidth = radius * 0.016f
        canvas.drawLine(cx, cy + radius * 0.18f, cx, cy - radius * 0.80f, fillSecondPaint)
        fillHandPaint.color = accent
        canvas.drawCircle(cx, cy, radius * 0.026f, fillHandPaint)
        canvas.restore()
    }

    private val fillHandPath = android.graphics.Path()

    private fun drawFillTaperedHand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        degrees: Float,
        length: Float,
        width: Float,
    ) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        fillHandPath.reset()
        fillHandPath.moveTo(cx, cy - length)
        fillHandPath.lineTo(cx + width / 2f, cy - length * 0.12f)
        fillHandPath.lineTo(cx + width / 2f, cy)
        fillHandPath.lineTo(cx - width / 2f, cy)
        fillHandPath.lineTo(cx - width / 2f, cy - length * 0.12f)
        fillHandPath.close()
        canvas.drawPath(fillHandPath, fillHandPaint)
        canvas.restore()
    }

    private fun drawFillTicks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
    ) {
        val square = fillFaceIsSquare()
        val portrait = !square &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val tickFrac = if (square || portrait) 0.06f else 0.03f
        val hourFrac = tickFrac * 3f
        val size = min(width, height).toFloat()
        val minuteStroke = size * 0.012f
        val hourStroke = minuteStroke * 2f
        val hourAlpha = if (square) 96 else 128
        val minuteAlpha = if (square) 58 else 77
        val hTick: Float
        val mTick: Float
        val sTick: Float
        if (square) {
            val calendar = Calendar.getInstance()
            val minute = calendar.get(Calendar.MINUTE)
            val second = calendar.get(Calendar.SECOND)
            val millis = calendar.get(Calendar.MILLISECOND)
            hTick = calendar.get(Calendar.HOUR) * 5f + minute / 12f
            mTick = minute + second / 60f
            sTick = second + millis / 1000f
        } else {
            hTick = -1f
            mTick = -1f
            sTick = -1f
        }
        for (i in 0 until 60) {
            val isHour = i % 5 == 0
            val isCardinal = i % 15 == 0
            val tickLen = width * if (!isHour || isCardinal) tickFrac else hourFrac
            val radians = Math.toRadians(i * 6.0)
            val cos = cos(radians)
            val sin = sin(radians)
            val t = min(abs(halfW / cos), abs(halfH / sin))
            val outerX = (cx + cos * t).toFloat()
            val outerY = (cy + sin * t).toFloat()
            val innerX = (outerX - cos * tickLen).toFloat()
            val innerY = (outerY - sin * tickLen).toFloat()
            fillTickPaint.strokeWidth = if (isHour) hourStroke else minuteStroke
            var alpha = if (isHour) hourAlpha else minuteAlpha
            if (square) {
                val clockTick = (i + 15) % 60
                if (fillTickNearHand(clockTick, hTick) ||
                    fillTickNearHand(clockTick, mTick) ||
                    fillTickNearHand(clockTick, sTick)
                ) {
                    alpha = (alpha * 0.62f).toInt()
                }
            }
            fillTickPaint.alpha = alpha
            canvas.drawLine(innerX, innerY, outerX, outerY, fillTickPaint)
        }
    }

    /** Tick index from 12 o'clock, 0–60. Hands occupy about ±15°. */
    private fun fillTickNearHand(clockTick: Int, handTick: Float): Boolean {
        var d = abs(clockTick - handTick)
        if (d > 30f) d = 60f - d
        return d <= 2.5f
    }

    /** Overlay square band: no spare long side, so landscape 3/9 + date must not apply. */
    private fun fillFaceIsSquare(): Boolean {
        val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
        return ratio in 0.9f..1.1f
    }

    private fun drawFillNumerals(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        handRadius: Float,
    ) {
        val savedSize = fillNumeralPaint.textSize
        val savedTypeface = fillNumeralPaint.typeface
        val scale = (min(width, height) + (max(width, height) - min(width, height)) * 0.35f) / 2f
        fillNumeralPaint.textSize = scale * 0.18f
        fillNumeralPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        val d = resources.displayMetrics.density
        val square = fillFaceIsSquare()
        val landscape = !square && width >= height
        val pos = 0.85f
        val vOff = fillNumeralPaint.textSize * 0.35f
        val pull12_6 = if (landscape) 39f * d else 5f * d
        val pull3_9 = if (!square && !landscape) 34f * d else 5f * d
        canvas.drawText("12", cx, cy - halfH * pos + vOff + pull12_6, fillNumeralPaint)
        canvas.drawText("6", cx, cy + halfH * pos + vOff - pull12_6, fillNumeralPaint)
        canvas.drawText("3", cx + halfW * pos - pull3_9, cy + vOff, fillNumeralPaint)
        canvas.drawText("9", cx - halfW * pos + pull3_9, cy + vOff, fillNumeralPaint)
        fillNumeralPaint.textSize = savedSize
        fillNumeralPaint.typeface = savedTypeface
    }

    private fun drawFillDate(canvas: Canvas, cx: Float, cy: Float, halfW: Float, handRadius: Float) {
        val calendar = Calendar.getInstance()
        val weekday = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
            ?.uppercase(Locale.getDefault())
            ?: ""
        val day = calendar.get(Calendar.DAY_OF_MONTH).toString()
        val dateScale = (min(width, height) + (max(width, height) - min(width, height)) * 0.35f) / 2f
        fillWeekdayPaint.textSize = dateScale * 0.105f
        fillDayPaint.textSize = dateScale * 0.145f
        val landscape = !fillFaceIsSquare() && width >= height
        if (landscape) {
            val dateX = cx + halfW * 0.42f
            canvas.drawText(weekday, dateX, cy - fillDayPaint.textSize * 0.15f, fillWeekdayPaint)
            canvas.drawText(day, dateX, cy + fillDayPaint.textSize * 0.85f, fillDayPaint)
        } else {
            val dateY = cy + handRadius * 0.38f
            canvas.drawText(weekday, cx, dateY, fillWeekdayPaint)
            canvas.drawText(day, cx, dateY + fillDayPaint.textSize * 0.95f, fillDayPaint)
        }
    }

    private val sheetGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheetRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheetRect = RectF()

    /**
     * Floating settings card backdrop. Liquid Glass on: translucent rounded slab with a top
     * sheen and specular rim. Off: solid dark rounded card. Inset 20dp L/R, 10dp from bottom.
     */
    private fun drawSheetBackdrop(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        t: Float,
    ) {
        val density = resources.displayMetrics.density
        val corner = 28f * density
        sheetRect.set(left, top, right, bottom)
        if (!LiquidGlass.enabled) {
            pickerPaint.color = Color.argb((180 * t).toInt().coerceAtLeast(70), 0, 0, 0)
            canvas.drawRoundRect(sheetRect, corner, corner, pickerPaint)
            return
        }
        val k = LiquidGlass.intensity
        val bodyAlpha = (0.42f + 0.22f * (1f - k)) * t
        sheetGlassPaint.shader = null
        sheetGlassPaint.color = Color.argb((bodyAlpha * 255).toInt().coerceAtLeast(40), 12, 14, 20)
        canvas.drawRoundRect(sheetRect, corner, corner, sheetGlassPaint)

        val sheenAlpha = (0.08f + 0.14f * k) * t
        sheetGlassPaint.shader = android.graphics.LinearGradient(
            left, top, left, top + (bottom - top) * 0.45f,
            Color.argb((sheenAlpha * 255).toInt(), 255, 255, 255),
            Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(sheetRect, corner, corner, sheetGlassPaint)
        sheetGlassPaint.shader = null

        sheetRimPaint.strokeWidth = 1.25f * density
        val rimHi = (0.35f + 0.35f * k) * t
        val rimLo = (0.06f + 0.08f * k) * t
        sheetRimPaint.shader = android.graphics.LinearGradient(
            left, top, right, top,
            intArrayOf(
                Color.argb((rimLo * 255).toInt(), 255, 255, 255),
                Color.argb((rimHi * 255).toInt(), 255, 255, 255),
                Color.argb((rimLo * 255).toInt(), 255, 255, 255),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val inset = sheetRimPaint.strokeWidth / 2f
        sheetRect.inset(inset, inset)
        canvas.drawRoundRect(sheetRect, corner - inset, corner - inset, sheetRimPaint)
        sheetRimPaint.shader = null
    }

    private fun drawPicker(canvas: Canvas) {
        pickerFaceHits.clear()
        pickerStyleHits.clear()
        pickerFontHits.clear()
        val density = resources.displayMetrics.density
        val pad = sheetInnerPad()
        val rowPad = pad
        val sheetLeft = sheetInsetH()
        val sheetRight = width - sheetLeft
        val contentLeft = sheetLeft + rowPad
        val contentRight = sheetRight - rowPad
        val contentW = contentRight - contentLeft
        val portrait = width < height
        val faceH = (if (portrait) 48f else 52f) * density
        val fontH = (if (portrait) 40f else 44f) * density
        val chipGap = (if (portrait) 8f else 6f) * density
        val chipW = DreamClockFlipBoard.CHIP_WIDTH_DP * density
        val chipH = (if (portrait) 56f else DreamClockFlipBoard.CHIP_HEIGHT_DP) * density
        val styleGap = DreamClockFlipBoard.CHIP_GAP_DP * density
        val faces = DreamClockFace.switchable
        val flipT = flipChromeT
        val themeUi = overlayThemeGesturesAllowed()
        val showFlipTimer = flipT > 0.01f
        val showFlipExtras = showFlipTimer && themeUi
        val interactiveFlip = face == DreamClockFace.FLIP && flipT > 0.85f
        val timerH = timerRowDp() * density
        // Font chips + FLIP chrome morph with soft easing (no hard jumps between faces).
        if (tickFontExpand() || tickFlipChrome()) invalidate()
        val fontRowH = if (themeUi) (fontH + pad) * fontExpandT * flipT else 0f
        val timerProp = (pad + timerH) * flipT
        // Portrait 2×2: vertical gap == horizontal chipGap so all four cells share even spacing.
        val extrasRowH = if (themeUi && portrait) (chipGap + faceH) * flipT else 0f
        val themeStripH = if (themeUi) pad + chipH else 0f
        // Bottom-up: face → [extras] → timer → [font chips] → theme strip.
        val sheetBottom = height - sheetInsetBottom()
        val faceY = sheetBottom - pad - faceH
        val extrasY = faceY - extrasRowH
        val timerY = extrasY - timerProp
        val fontY = timerY - fontRowH
        val rowTop = timerY - fontRowH - themeStripH
        val sheetTop = rowTop - pad
        val t = pickerAnimT()
        val slideY = (1f - t) * (height - sheetTop)
        if (t < 1f) invalidate()
        canvas.save()
        canvas.translate(0f, slideY)
        drawSheetBackdrop(canvas, sheetLeft, sheetTop, sheetRight, sheetBottom, t)

        secondsToggleRect.setEmpty()
        twelveHourToggleRect.setEmpty()
        val faceLabelMax = if (portrait) 15f * density else 16f * density
        val faceLabelMin = 11f * density
        // Equal-width chips: landscape FLIP = 4-up; portrait FLIP = 2×2 with equal gaps.
        val faceCount = faces.size
        val extrasCount = if (showFlipExtras) 2 else 0
        if (portrait || !showFlipExtras) {
            val colW = (contentW - chipGap * (faceCount - 1).coerceAtLeast(0)) / faceCount.coerceAtLeast(1)
            var x = contentLeft
            faces.forEach { item ->
                drawFaceChip(
                    canvas, item, RectF(x, faceY, x + colW, faceY + faceH),
                    slideY, density, faceLabelMax, faceLabelMin,
                )
                x += colW + chipGap
            }
            if (showFlipExtras) {
                // Tops of extras sit at extrasY; bottoms clear faceY by exactly chipGap.
                val exTop = extrasY
                val halfW = (contentW - chipGap) / 2f
                drawSecHourChips(
                    canvas,
                    RectF(contentLeft, exTop, contentLeft + halfW, exTop + faceH),
                    RectF(contentLeft + halfW + chipGap, exTop, contentRight, exTop + faceH),
                    slideY, density, interactiveFlip, flipT,
                )
            }
        } else {
            // Landscape FLIP: one row, four equal cells — never lets long labels steal width.
            val cell = (contentW - chipGap * (faceCount + extrasCount - 1)) / (faceCount + extrasCount)
            var x = contentLeft
            faces.forEach { item ->
                drawFaceChip(
                    canvas, item, RectF(x, faceY, x + cell, faceY + faceH),
                    slideY, density, faceLabelMax, faceLabelMin,
                )
                x += cell + chipGap
            }
            drawSecHourChips(
                canvas,
                RectF(x, faceY, x + cell, faceY + faceH),
                RectF(x + cell + chipGap, faceY, x + cell * 2f + chipGap, faceY + faceH),
                slideY, density, interactiveFlip, flipT,
            )
        }

        if (showFlipExtras && fontExpandT > 0.01f) {
            val savedTypeface = pickerTextPaint.typeface
            val fonts = DreamClockFlipFont.entries
            val fontW = (contentW - chipGap * (fonts.size - 1)) / fonts.size
            val rowAlpha = (fontExpandT * flipT * 255f).toInt().coerceIn(0, 255)
            // Chips slide down out of the theme strip and fade in as the row opens.
            val rowLayer = canvas.saveLayerAlpha(sheetLeft, fontY, sheetRight, fontY + fontRowH, rowAlpha)
            canvas.clipRect(sheetLeft, fontY, sheetRight, fontY + fontRowH)
            val chipTop = fontY + fontRowH - pad - fontH
            // Slight upward drift as the row opens — softer than a hard pop.
            val drift = (1f - easeSoft(fontExpandT)) * (10f * density)
            var fx = contentLeft
            fonts.forEach { item ->
                val rect = RectF(fx, chipTop + drift, fx + fontW, chipTop + fontH + drift)
                if (fontExpandT >= 0.99f && flipT >= 0.99f) {
                    pickerFontHits.add(RectF(rect.left, rect.top + slideY, rect.right, rect.bottom + slideY) to item)
                }
                val selected = item == flipFont
                pickerPaint.color = if (selected) Color.WHITE else Color.argb(70, 255, 255, 255)
                canvas.drawRoundRect(rect, 10f * density, 10f * density, pickerPaint)
                pickerTextPaint.color = if (selected) Color.BLACK else Color.WHITE
                pickerTextPaint.typeface = resolveFlipFont(item)
                val preview = "12:34"
                fitPickerLabel(preview, fontW - 8f * density, fontH * 0.48f, fontH * 0.28f)
                val baseline = rect.centerY() - (pickerTextPaint.ascent() + pickerTextPaint.descent()) / 2f
                canvas.drawText(preview, rect.centerX(), baseline, pickerTextPaint)
                fx += fontW + chipGap
            }
            canvas.restoreToCount(rowLayer)
            pickerTextPaint.typeface = savedTypeface
        }

        timerHits.clear()
        if (showFlipTimer) {
            val timerLayer = canvas.saveLayerAlpha(
                sheetLeft, timerY, sheetRight, timerY + timerH + pad, (flipT * 255f).toInt(),
            )
            drawTimerRow(canvas, contentLeft, contentW, timerY + pad * (1f - flipT) * 0.35f, timerH, chipGap, density, slideY)
            canvas.restoreToCount(timerLayer)
            if (!interactiveFlip) timerHits.clear()
        }

        fontToggleRect.setEmpty()
        styleRowRect.setEmpty()
        if (themeUi) {
            // A toggle occupies the right end of the theme strip as FLIP chrome reveals.
            val fontPillW = FONT_PILL_W_DP * density
            val stripLeft = contentLeft
            val stripRight = contentRight - (fontPillW + rowPad) * flipT

            // styleRowRect is used for ACTION_MOVE scroll detection — store screen-space
            styleRowRect.set(stripLeft, rowTop + slideY, stripRight, rowTop + chipH + slideY)

            val rowSaveCount = canvas.saveLayer(stripLeft, rowTop, stripRight, rowTop + chipH, null)
            DreamClockFlipStyle.entries.forEachIndexed { index, style ->
                val left = stripLeft + index * (chipW + styleGap) - pickerScrollX
                val right = left + chipW
                if (right < stripLeft || left > stripRight) return@forEachIndexed
                val rect = RectF(left, rowTop, right, rowTop + chipH)
                pickerStyleHits.add(RectF(rect.left, rect.top + slideY, min(rect.right, stripRight), rect.bottom + slideY) to style)
                flipBoard.drawStyleChip(canvas, rect, style, style == flipBoard.style)
            }
            val fadeW = 36f * density
            edgeFadePaint.shader = android.graphics.LinearGradient(
                stripLeft, 0f, stripLeft + fadeW, 0f,
                Color.TRANSPARENT, Color.BLACK,
                android.graphics.Shader.TileMode.CLAMP,
            )
            edgeFadePaint.xfermode = edgeFadeXfer
            canvas.drawRect(stripLeft, rowTop, stripLeft + fadeW, rowTop + chipH, edgeFadePaint)
            edgeFadePaint.shader = android.graphics.LinearGradient(
                stripRight - fadeW, 0f, stripRight, 0f,
                Color.BLACK, Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(stripRight - fadeW, rowTop, stripRight, rowTop + chipH, edgeFadePaint)
            edgeFadePaint.xfermode = null
            edgeFadePaint.shader = null
            canvas.restoreToCount(rowSaveCount)

            if (showFlipExtras) {
                val pillLeft = contentRight - fontPillW
                val rect = RectF(pillLeft, rowTop, pillLeft + fontPillW, rowTop + chipH)
                if (interactiveFlip) {
                    fontToggleRect.set(rect.left, rect.top + slideY, rect.right, rect.bottom + slideY)
                }
                val pillLayer = canvas.saveLayerAlpha(
                    rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f, (flipT * 255f).toInt(),
                )
                drawFontPillButton(canvas, rect, density, fontRowExpanded)
                canvas.restoreToCount(pillLayer)
            }
        }
        canvas.restore()
    }

    /**
     * FlipFlow-style timer strip: [5m] [10m] [25m] [自定义] [关闭]. Custom only when a
     * HA `timer.*` entity is bound; local-only mode keeps the three presets.
     * While a countdown runs the strip collapses to [⏸/▶] [关闭].
     */
    private fun drawTimerRow(
        canvas: Canvas,
        contentLeft: Float,
        contentW: Float,
        top: Float,
        rowH: Float,
        gap: Float,
        density: Float,
        slideY: Float,
    ) {
        val running = flipBoard.isCountingDown
        val items: List<Pair<TimerAction, String>> = if (running) {
            val pausedNow = flipBoard.pausedRemainingMs != null
            buildList {
                if (!timerFinishedHold) {
                    add(
                        TimerAction.PAUSE_RESUME to context.getString(
                            if (pausedNow) R.string.dream_clock_timer_resume_chip else R.string.dream_clock_timer_pause_chip
                        )
                    )
                }
                add(TimerAction.CANCEL to context.getString(R.string.dream_clock_timer_cancel_chip))
            }
        } else {
            buildList {
                add(TimerAction.M5 to "5m")
                add(TimerAction.M10 to "10m")
                add(TimerAction.M25 to "25m")
                if (timerEntityBound) add(TimerAction.CUSTOM to context.getString(R.string.dream_clock_timer_custom_chip))
            }
        }
        val n = items.size
        val chipW = (contentW - gap * (n - 1)) / n
        var x = contentLeft
        val portrait = width < height
        val labelMax = if (portrait) 15f * density else 18f * density
        val labelMin = if (portrait) 11f * density else 13f * density
        pickerTextPaint.textSize = labelMax
        // Portrait chips are narrow — cap the glyph to the cell, not the row height.
        val iconSize = min(
            rowH * if (portrait) 0.32f else 0.46f,
            chipW * if (portrait) 0.20f else 0.28f,
        ).coerceAtLeast(12f * density)
        val iconGap = if (portrait) 4f * density else 6f * density
        val corner = if (portrait) 12f * density else 14f * density
        items.forEach { (action, label) ->
            val rect = RectF(x, top, x + chipW, top + rowH)
            timerHits.add(RectF(rect.left, rect.top + slideY, rect.right, rect.bottom + slideY) to action)
            val accent = action == TimerAction.CANCEL
            val filled = running && action == TimerAction.PAUSE_RESUME
            pickerPaint.color = when {
                accent -> Color.argb(90, 255, 80, 80)
                filled -> Color.WHITE
                else -> Color.argb(70, 255, 255, 255)
            }
            canvas.drawRoundRect(rect, corner, corner, pickerPaint)
            val ink = if (filled) Color.BLACK else Color.WHITE
            pickerTextPaint.color = ink
            val textMax = (chipW - iconSize - iconGap - 8f * density).coerceAtLeast(12f * density)
            fitPickerLabel(label, textMax, labelMax, labelMin)
            // Icon + label, centered as a group (FlipFlow puts a glyph before each preset).
            val textW = pickerTextPaint.measureText(label)
            val groupW = iconSize + iconGap + textW
            val iconCx = rect.centerX() - groupW / 2f + iconSize / 2f
            val cy = rect.centerY()
            when (action) {
                TimerAction.M5, TimerAction.M10 -> drawStopwatchGlyph(canvas, iconCx, cy, iconSize, ink)
                TimerAction.M25 -> drawTomatoGlyph(canvas, iconCx, cy, iconSize, ink)
                TimerAction.CUSTOM -> drawSlidersGlyph(canvas, iconCx, cy, iconSize, ink)
                TimerAction.PAUSE_RESUME -> {
                    if (flipBoard.pausedRemainingMs != null) drawPlayGlyph(canvas, iconCx, cy, iconSize, ink)
                    else drawPauseGlyph(canvas, iconCx, cy, iconSize, ink)
                }
                TimerAction.CANCEL -> drawCrossGlyph(canvas, iconCx, cy, iconSize, ink)
            }
            pickerTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, cy + pickerTextPaint.textSize * 0.35f, pickerTextPaint)
            pickerTextPaint.textAlign = Paint.Align.CENTER
            x += chipW + gap
        }
    }

    // ---- small vector glyphs for the picker (no drawable resources) ------------------

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glyphPath = android.graphics.Path()

    private fun drawStopwatchGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        val r = size * 0.36f
        val sw = size * 0.11f
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = sw
        val dialCy = cy + size * 0.06f
        canvas.drawCircle(cx, dialCy, r, glyphPaint)
        // crown
        canvas.drawLine(cx, dialCy - r - size * 0.05f, cx, dialCy - r - size * 0.17f, glyphPaint)
        canvas.drawLine(cx - size * 0.12f, dialCy - r - size * 0.17f, cx + size * 0.12f, dialCy - r - size * 0.17f, glyphPaint)
        // hand
        canvas.drawLine(cx, dialCy, cx + r * 0.55f, dialCy - r * 0.45f, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    private fun drawTomatoGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        val r = size * 0.36f
        val sw = size * 0.11f
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = sw
        val bodyCy = cy + size * 0.08f
        canvas.drawCircle(cx, bodyCy, r, glyphPaint)
        // leaf + stem
        glyphPath.reset()
        glyphPath.moveTo(cx, bodyCy - r)
        glyphPath.lineTo(cx, bodyCy - r - size * 0.16f)
        canvas.drawPath(glyphPath, glyphPaint)
        glyphPath.reset()
        glyphPath.moveTo(cx - size * 0.02f, bodyCy - r - size * 0.02f)
        glyphPath.quadTo(cx - size * 0.22f, bodyCy - r - size * 0.20f, cx - size * 0.30f, bodyCy - r - size * 0.02f)
        canvas.drawPath(glyphPath, glyphPaint)
        glyphPath.reset()
        glyphPath.moveTo(cx + size * 0.02f, bodyCy - r - size * 0.02f)
        glyphPath.quadTo(cx + size * 0.22f, bodyCy - r - size * 0.20f, cx + size * 0.30f, bodyCy - r - size * 0.02f)
        canvas.drawPath(glyphPath, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    private fun drawSlidersGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        val sw = size * 0.11f
        val half = size * 0.38f
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = sw
        val rows = floatArrayOf(-0.26f, 0f, 0.26f)
        val knobs = floatArrayOf(-0.15f, 0.2f, -0.05f)
        for (i in rows.indices) {
            val y = cy + rows[i] * size
            canvas.drawLine(cx - half, y, cx + half, y, glyphPaint)
        }
        glyphPaint.style = Paint.Style.FILL
        for (i in rows.indices) {
            canvas.drawCircle(cx + knobs[i] * size, cy + rows[i] * size, sw * 1.4f, glyphPaint)
        }
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.FILL
        val barW = size * 0.16f
        val barH = size * 0.56f
        val g = size * 0.10f
        canvas.drawRoundRect(cx - g - barW, cy - barH / 2f, cx - g, cy + barH / 2f, barW / 2f, barW / 2f, glyphPaint)
        canvas.drawRoundRect(cx + g, cy - barH / 2f, cx + g + barW, cy + barH / 2f, barW / 2f, barW / 2f, glyphPaint)
    }

    private fun drawPlayGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.FILL
        val r = size * 0.32f
        glyphPath.reset()
        glyphPath.moveTo(cx - r * 0.75f, cy - r)
        glyphPath.lineTo(cx + r, cy)
        glyphPath.lineTo(cx - r * 0.75f, cy + r)
        glyphPath.close()
        canvas.drawPath(glyphPath, glyphPaint)
    }

    private fun drawCrossGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = size * 0.13f
        val a = size * 0.28f
        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, glyphPaint)
        canvas.drawLine(cx - a, cy + a, cx + a, cy - a, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    /** Theme-strip font toggle: plain “A” + chevron, same weight as timer icons. */
    private fun drawFontPillButton(canvas: Canvas, rect: RectF, density: Float, expanded: Boolean) {
        val chipRadius = 12f * density
        pickerPaint.color = if (expanded) Color.WHITE else Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(rect, chipRadius, chipRadius, pickerPaint)

        val ink = if (expanded) Color.argb(210, 0, 0, 0) else Color.argb(210, 255, 255, 255)
        val savedTypeface = pickerTextPaint.typeface
        val savedAlign = pickerTextPaint.textAlign
        pickerTextPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        pickerTextPaint.color = ink
        pickerTextPaint.textAlign = Paint.Align.CENTER
        pickerTextPaint.textSize = 13f * density
        // Optical center slightly left so the chevron can sit on the right without a divider.
        val aX = rect.left + rect.width() * 0.40f
        val baseline = rect.centerY() - (pickerTextPaint.ascent() + pickerTextPaint.descent()) / 2f
        canvas.drawText("A", aX, baseline, pickerTextPaint)
        pickerTextPaint.typeface = savedTypeface
        pickerTextPaint.textAlign = savedAlign
        drawChevronGlyph(
            canvas,
            rect.left + rect.width() * 0.72f,
            rect.centerY(),
            8f * density,
            ink,
            pointUp = expanded,
        )
    }

    private fun drawChevronGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        ink: Int,
        pointUp: Boolean,
    ) {
        val halfW = size * 0.42f
        val halfH = size * 0.28f
        val dir = if (pointUp) -1f else 1f
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = size * 0.12f
        glyphPaint.strokeCap = Paint.Cap.ROUND
        glyphPaint.strokeJoin = Paint.Join.ROUND
        glyphPath.reset()
        glyphPath.moveTo(cx - halfW, cy + halfH * dir)
        glyphPath.lineTo(cx, cy - halfH * dir)
        glyphPath.lineTo(cx + halfW, cy + halfH * dir)
        canvas.drawPath(glyphPath, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    /** Soft single-crest wave. [amp] 0→1 swells and shifts it along [pointUp]. */
    private fun drawWaveGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        ink: Int,
        pointUp: Boolean,
        amp: Float,
    ) {
        val dir = if (pointUp) -1f else 1f
        val pulse = amp.coerceIn(0f, 1f)
        val halfW = size * 0.50f
        val crest = size * (0.22f + 0.12f * pulse)
        val y = cy + dir * pulse * size * 0.18f
        glyphPaint.color = ink
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = size * 0.12f
        glyphPaint.strokeCap = Paint.Cap.ROUND
        glyphPaint.strokeJoin = Paint.Join.ROUND
        glyphPath.reset()
        val steps = 12
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = cx - halfW + halfW * 2f * t
            val wave = sin(t * Math.PI.toFloat())
            val py = y + dir * crest * wave
            if (i == 0) glyphPath.moveTo(x, py) else glyphPath.lineTo(x, py)
        }
        canvas.drawPath(glyphPath, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    /** 0→1 sine pulse for the stepper that was just tapped, else null. */
    private fun stepperWave(): Pair<CustomEdit, Float>? {
        val edit = stepperNudgeEdit ?: return null
        val t = ((SystemClock.elapsedRealtime() - stepperNudgeAt) / stepperNudgeMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            stepperNudgeEdit = null
            return null
        }
        return edit to sin(t * Math.PI.toFloat())
    }

    /**
     * Centered HH / MM / SS editor. Steppers are wave crests, ✕ / ▶ are icon-only.
     */
    private fun drawCustomEditor(canvas: Canvas) {
        customHits.clear()
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()

        val blur = editorBlurBitmap
        if (blur != null && !blur.isRecycled) {
            val dest = Rect(0, 0, width, height)
            canvas.drawBitmap(blur, null, dest, editorBlurPaint)
        }
        val frost = if (blur != null && LiquidGlass.blurEnabled) {
            (28f + 48f * LiquidGlass.fakeBlurAmount(LiquidGlass.viewBlurRadiusPx())).toInt()
        } else {
            96
        }
        canvas.drawColor(Color.argb(frost.coerceIn(24, 120), 0, 0, 0))

        val side = 24f * density
        val cardW = min(w - side * 2f, 448f * density)
        val cardH = 268f * density
        val left = (w - cardW) / 2f
        val top = ((h - cardH) / 2f).coerceIn(side, h - cardH - side)
        val card = RectF(left, top, left + cardW, top + cardH)
        val radius = 24f * density
        val glass = editorCardGlass ?: LiquidGlassDrawable(
            cornerRadiusPx = radius,
            tint = 0xA6121824.toInt(),
            ownedMaterial = true,
        ).also { editorCardGlass = it }
        glass.cornerRadiusPx = radius
        glass.setBounds(card.left.toInt(), card.top.toInt(), card.right.toInt(), card.bottom.toInt())
        glass.draw(canvas)
        if (!LiquidGlass.enabled) {
            pickerPaint.color = Color.argb(166, 28, 28, 32)
            canvas.drawRoundRect(card, radius, radius, pickerPaint)
        }

        val colGap = 16f * density
        val inset = 22f * density
        val colW = (cardW - inset * 2f - colGap * 2f) / 3f
        val colTop = top + 24f * density
        val arrowH = 40f * density
        val valueH = 66f * density
        val cols = listOf(
            Triple(customH, CustomEdit.H_UP, CustomEdit.H_DOWN),
            Triple(customM, CustomEdit.M_UP, CustomEdit.M_DOWN),
            Triple(customS, CustomEdit.S_UP, CustomEdit.S_DOWN),
        )
        var cx = left + inset
        pickerTextPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val wave = stepperWave()
        cols.forEach { (value, up, down) ->
            val upRect = RectF(cx, colTop, cx + colW, colTop + arrowH)
            val valRect = RectF(cx, upRect.bottom, cx + colW, upRect.bottom + valueH)
            val downRect = RectF(cx, valRect.bottom, cx + colW, valRect.bottom + arrowH)
            customHits.add(upRect to up)
            customHits.add(downRect to down)
            val arrowSize = 22f * density
            val pulse = if (wave != null && (wave.first == up || wave.first == down)) wave.second else 0f
            val arrowInk = Color.argb((210 + 45 * pulse).toInt().coerceAtMost(255), 255, 255, 255)
            drawWaveGlyph(canvas, upRect.centerX(), upRect.centerY(), arrowSize, arrowInk, pointUp = true, amp = if (wave?.first == up) pulse else 0f)
            drawWaveGlyph(canvas, downRect.centerX(), downRect.centerY(), arrowSize, arrowInk, pointUp = false, amp = if (wave?.first == down) pulse else 0f)
            pickerPaint.color = Color.argb(40, 255, 255, 255)
            canvas.drawRoundRect(valRect, 14f * density, 14f * density, pickerPaint)
            pickerTextPaint.color = Color.WHITE
            pickerTextPaint.textSize = 40f * density
            val valueShift = when (wave?.first) {
                up -> -pulse * 7f * density
                down -> pulse * 7f * density
                else -> 0f
            }
            canvas.drawText(
                "%02d".format(value),
                valRect.centerX(),
                valRect.centerY() + valueShift + pickerTextPaint.textSize * 0.35f,
                pickerTextPaint,
            )
            cx += colW + colGap
        }

        val btnH = 56f * density
        val btnTop = card.bottom - 22f * density - btnH
        val btnGap = 14f * density
        val btnW = (cardW - inset * 2f - btnGap) / 2f
        val closeRect = RectF(left + inset, btnTop, left + inset + btnW, btnTop + btnH)
        val startRect = RectF(closeRect.right + btnGap, btnTop, closeRect.right + btnGap + btnW, btnTop + btnH)
        customHits.add(closeRect to CustomEdit.CLOSE)
        customHits.add(startRect to CustomEdit.START)
        val iconSize = 28f * density
        pickerPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(closeRect, 16f * density, 16f * density, pickerPaint)
        drawCrossGlyph(canvas, closeRect.centerX(), closeRect.centerY(), iconSize, Color.WHITE)
        pickerPaint.color = Color.WHITE
        canvas.drawRoundRect(startRect, 16f * density, 16f * density, pickerPaint)
        drawPlayGlyph(canvas, startRect.centerX(), startRect.centerY(), iconSize, Color.BLACK)
    }

    /** Same frosted pill as the settings button; [rect] is filled in for hit-testing. */
    private fun drawCornerPill(canvas: Canvas, rect: RectF, density: Float, tint: Int = Color.argb(90, 20, 20, 24)) {
        val radius = 20f * density
        pickerPaint.style = Paint.Style.FILL
        pickerPaint.color = tint
        canvas.drawRoundRect(rect, radius, radius, pickerPaint)
        pickerPaint.color = Color.argb(50, 255, 255, 255)
        pickerPaint.style = Paint.Style.STROKE
        pickerPaint.strokeWidth = 1.2f * density
        canvas.drawRoundRect(rect, radius, radius, pickerPaint)
        pickerPaint.style = Paint.Style.FILL
    }

    /**
     * While a countdown runs (or holds at 00:00:00), two icon pills sit left of the
     * settings button: ⏸/▶ and ✕. Icons only — no text, no i18n.
     */
    private fun drawTimerCornerButtons(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val size = 62f * density
        val gap = 12f * density
        val anchor = if (settingsButtonRect.isEmpty) {
            val margin = 20f * density
            RectF(width - margin - size, height - margin - size, width - margin, height - margin)
        } else {
            settingsButtonRect
        }
        val cancelLeft = anchor.left - gap - size
        timerCancelRect.set(cancelLeft, anchor.top, cancelLeft + size, anchor.bottom)
        val pauseLeft = cancelLeft - gap - size
        timerPauseRect.set(pauseLeft, anchor.top, pauseLeft + size, anchor.bottom)

        val ink = Color.argb(200, 255, 255, 255)
        // ✕
        drawCornerPill(canvas, timerCancelRect, density, Color.argb(110, 120, 30, 30))
        pickerPaint.color = ink
        pickerPaint.style = Paint.Style.STROKE
        pickerPaint.strokeWidth = 2.6f * density
        pickerPaint.strokeCap = Paint.Cap.ROUND
        val cx = timerCancelRect.centerX()
        val cy = timerCancelRect.centerY()
        val arm = size * 0.16f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, pickerPaint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, pickerPaint)
        pickerPaint.style = Paint.Style.FILL

        // ⏸ / ▶ (hidden while holding at zero — nothing left to pause)
        if (!timerFinishedHold) {
            drawCornerPill(canvas, timerPauseRect, density)
            pickerPaint.color = ink
            val px = timerPauseRect.centerX()
            val py = timerPauseRect.centerY()
            if (flipBoard.pausedRemainingMs != null) {
                val tri = android.graphics.Path().apply {
                    val r = size * 0.17f
                    moveTo(px - r * 0.8f, py - r)
                    lineTo(px + r, py)
                    lineTo(px - r * 0.8f, py + r)
                    close()
                }
                canvas.drawPath(tri, pickerPaint)
            } else {
                val barW = size * 0.09f
                val barH = size * 0.30f
                val barGap = size * 0.08f
                canvas.drawRoundRect(px - barGap - barW, py - barH / 2f, px - barGap, py + barH / 2f, barW / 2f, barW / 2f, pickerPaint)
                canvas.drawRoundRect(px + barGap, py - barH / 2f, px + barGap + barW, py + barH / 2f, barW / 2f, barW / 2f, pickerPaint)
            }
        } else {
            timerPauseRect.setEmpty()
        }
    }

    /** Centered stop while the alarm rings: big pill with a filled square (■). */
    private fun drawStopAlarmButton(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val size = 118f * density
        val cx = width / 2f
        val cy = height / 2f
        stopAlarmRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)

        val now = SystemClock.elapsedRealtime()
        val appearRaw = if (stopAlarmAppearAt == 0L) 1f
            else ((now - stopAlarmAppearAt) / stopAlarmAppearMs).coerceIn(0f, 1f)
        val appearEase = 1f - (1f - appearRaw) * (1f - appearRaw) * (1f - appearRaw)
        val s = 1.55f
        val p = appearRaw - 1f
        val overshoot = p * p * ((s + 1f) * p + s) + 1f
        val pop = 0.58f + 0.42f * overshoot

        canvas.drawColor(Color.argb((96f * appearEase).toInt(), 0, 0, 0))

        val maxR = kotlin.math.hypot(width / 2.0, height / 2.0).toFloat()
        val minR = size * 0.6f
        val phase = now / 2600.0 * Math.PI
        val breathe = 0.5f - 0.5f * kotlin.math.cos(phase).toFloat()
        val r = minR + (maxR - minR) * breathe
        pickerPaint.style = Paint.Style.FILL
        pickerPaint.color = Color.argb(((18f * (1f - breathe) + 6f) * appearEase).toInt(), 255, 80, 80)
        canvas.drawCircle(cx, cy, r, pickerPaint)
        pickerPaint.style = Paint.Style.STROKE
        pickerPaint.strokeWidth = 1.5f * density
        pickerPaint.color = Color.argb(((40f * (1f - breathe) + 10f) * appearEase).toInt(), 255, 120, 120)
        canvas.drawCircle(cx, cy, r, pickerPaint)
        pickerPaint.style = Paint.Style.FILL

        val layer = canvas.saveLayerAlpha(
            0f, 0f, width.toFloat(), height.toFloat(), (appearEase * 255f).toInt(),
        )
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(pop, pop)
        val pill = RectF(-size / 2f, -size / 2f, size / 2f, size / 2f)
        drawCornerPill(canvas, pill, density, Color.argb(210, 200, 40, 40))
        pickerPaint.color = Color.WHITE
        val sq = size * 0.17f
        canvas.drawRoundRect(-sq, -sq, sq, sq, 4f * density, 4f * density, pickerPaint)
        canvas.restore()
        canvas.restoreToCount(layer)
        invalidate()
    }

    /**
     * Bottom-right settings control. On open it tucks under the rising sheet; on close it
     * stays fully hidden until the sheet is gone, then reappears — no staggered chase.
     */
    private fun drawSettingsButton(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val size = 62f * density
        val radius = 20f * density
        val margin = 20f * density
        val sheetT = pickerAnimT()
        val retract = when {
            // Closing: keep tucked the whole way so the FAB doesn't race the sheet.
            pickerClosing -> 1f
            pickerOpen -> easeInOut(sheetT)
            else -> 0f
        }
        if (retract >= 0.98f) {
            settingsButtonRect.setEmpty()
            return
        }

        val restLeft = width - margin - size
        val restTop = height - margin - size
        // Tuck down past the safe bottom and a touch outward — reads as "into" the sheet.
        val slideY = retract * (size + margin + sheetInsetBottom() * 0.6f)
        val slideX = retract * (10f * density)
        val scale = 1f - 0.18f * retract
        val alpha = (1f - retract * 1.05f).coerceIn(0f, 1f)

        val cx = restLeft + size / 2f + slideX
        val cy = restTop + size / 2f + slideY
        if (retract < 0.45f) {
            val half = size * scale / 2f
            settingsButtonRect.set(cx - half, cy - half, cx + half, cy + half)
        } else {
            settingsButtonRect.setEmpty()
        }

        val layer = canvas.saveLayerAlpha(
            0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255f).toInt(),
        )
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        val half = size / 2f
        val pill = RectF(-half, -half, half, half)
        pickerPaint.style = Paint.Style.FILL
        pickerPaint.color = Color.argb(90, 20, 20, 24)
        canvas.drawRoundRect(pill, radius, radius, pickerPaint)
        pickerPaint.color = Color.argb(50, 255, 255, 255)
        pickerPaint.style = Paint.Style.STROKE
        pickerPaint.strokeWidth = 1.2f * density
        canvas.drawRoundRect(pill, radius, radius, pickerPaint)
        pickerPaint.style = Paint.Style.STROKE
        pickerPaint.color = Color.argb(200, 255, 255, 255)
        pickerPaint.strokeWidth = 2.4f * density
        pickerPaint.strokeCap = Paint.Cap.ROUND
        val w = size * 0.22f
        val gap = size * 0.16f
        for (i in -1..1) {
            val y = i * gap
            canvas.drawLine(-w, y, w, y, pickerPaint)
            pickerPaint.style = Paint.Style.FILL
            val knobX = i * (w * 0.45f)
            canvas.drawCircle(knobX, y, 3.8f * density, pickerPaint)
            pickerPaint.style = Paint.Style.STROKE
        }
        pickerPaint.style = Paint.Style.FILL
        canvas.restore()
        canvas.restoreToCount(layer)
    }
}
