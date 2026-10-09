package com.example.ava.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.ava.R
import com.example.ava.mods.ModDeviceSupport
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.SimpleClockPortraitStyle
import com.example.ava.settings.SimpleClockStatusSlot
import com.example.ava.settings.migrateScreensaverWeatherIfNeeded
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.resolveScreensaverWeatherEntityId
import com.example.ava.ui.OverlayLogoBadge
import com.example.ava.ui.BlurFadeRevealLayout
import com.example.ava.ui.components.MdiIconMapper
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.weather.WeatherData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import kotlin.math.max


class ScreensaverService : Service() {

    private var windowManager: WindowManager? = null
    private var revealHost: BlurFadeRevealLayout? = null
    private var screensaverView: ScreensaverView? = null
    /** Top-end HA watermark, layered above the clock so it never shifts clock layout. */
    private var logoView: ImageView? = null
    /** Dark AOD cover above clock + logo; idle timer fades it in, interrupt fades it out. */
    private var smartAodOverlay: SmartAodMaskOverlay? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isEnabled = false
    private var isVisible = false
    private var wallpaperUrl: String = ""
    private var wallpaperRefreshSeconds: Int = 30
    private var wallpaperDualPane: Boolean = false
    private var wallpaperDarkOverlayEnabled = true
    @Volatile private var isServiceRunning = true
    private val pixelShift = ScreensaverPixelShift(handler)
    private var pixelShiftEnabled = false
    private var use12Hour = false
    private var darkOffEnabled = false
    private var smartAodEnabled = false
    private var smartAodTimeoutSeconds = 60
    private var smartAodMaskPercent = 100
    private var smartAodCovering = false
    private var hideEpoch = 0

    // Dark-off (mirrors ScreensaverController; independent of WebView screensaver darkOff).
    private var lightSensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightSensorRegistered = false
    private var isScreenOffByDark = false
    private var clockPausedForDark = false
    private var clockHiddenForModDark = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastObservedLux: Float? = null
    private var darkCandidateSinceMs: Long? = null
    private var lightCandidateSinceMs: Long? = null
    private var darkTransitionJob: Job? = null
    private var lightTransitionJob: Job? = null
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                handleLightLevel(event.values.firstOrNull() ?: return)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var revealMeasureWaits = 0
    private val revealSettleRunnable = object : Runnable {
        override fun run() {
            val epoch = hideEpoch
            val host = revealHost ?: return
            if (epoch != hideEpoch) return
            if (host.isAttachedToWindow && host.width <= 1 && revealMeasureWaits < 4) {
                revealMeasureWaits += 1
                host.post(this)
                return
            }
            revealMeasureWaits = 0
            OverlayLayerSplit.sync()
            host.requestLayout()
            screensaverView?.requestLayout()
            OverlayLayerSplit.runAfterColdStart {
                if (epoch != hideEpoch) return@runAfterColdStart
                host.visibility = View.VISIBLE
                host.alpha = 0f
                OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.SIMPLE_CLOCK) {
                    if (epoch != hideEpoch) return@fadeWhenPaneReady
                    host.revealIn(BlurFadeRevealLayout.REVEAL_IN_MS)
                    syncPixelShift()
                    scheduleSmartAodEnter()
                }
            }
        }
    }
    private val positionLogoBadgeRunnable = Runnable { positionLogoBadge() }
    private val enterSmartAodRunnable = Runnable { enterSmartAodMask() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.SIMPLE_CLOCK,
            showing = { isOverlayShowing() },
            host = { revealHost },
            apply = { frame -> applyLayerSplit(frame) },
        )
        // Simple Clock weather is pushed via updateClockWeather (dedicated entity).
        // Do not listen to WeatherService — that cache belongs to the immersive overlay.
        serviceScope.launch {
            migrateScreensaverWeatherIfNeeded(playerSettingsStore)
            playerSettingsStore.data.collectLatest { settings ->
                wallpaperUrl = settings.screensaverWallpaperUrl
                wallpaperRefreshSeconds = settings.screensaverWallpaperRefreshSeconds
                wallpaperDualPane = settings.screensaverWallpaperDualPane
                wallpaperDarkOverlayEnabled = settings.screensaverWallpaperDarkOverlayEnabled
                pixelShiftEnabled = settings.screensaverPixelShiftEnabled
                use12Hour = settings.screensaver12HourEnabled
                darkOffEnabled = settings.screensaverDarkOffEnabled
                val aodWasEnabled = smartAodEnabled
                smartAodEnabled = settings.smartPowerSavingAodEnabled
                smartAodTimeoutSeconds = settings.smartPowerSavingAodTimeoutSeconds.coerceIn(10, 3600)
                smartAodMaskPercent = settings.smartPowerSavingAodMaskPercent.coerceIn(
                    SmartAodMaskOverlay.MIN_PERCENT,
                    SmartAodMaskOverlay.MAX_PERCENT
                )
                screensaverView?.setWallpaperDarkOverlayEnabled(wallpaperDarkOverlayEnabled)
                screensaverView?.updateWallpaperSettings(
                    url = wallpaperUrl,
                    refreshSeconds = wallpaperRefreshSeconds,
                    dualPane = wallpaperDualPane
                )
                screensaverView?.updateStatusSlots(
                    enabled = settings.enableScreensaverStatusSlots,
                    slots = settings.screensaverStatusSlots
                )
                screensaverView?.set12HourEnabled(use12Hour)
                screensaverView?.setPortraitStyle(
                    SimpleClockPortraitStyle.fromStored(settings.screensaverPortraitStyle)
                )
                screensaverView?.setWeatherEnabled(
                    enabled = settings.enableScreensaverWeather,
                    hasEntity = resolveScreensaverWeatherEntityId(settings).isNotEmpty()
                )
                syncPixelShift()
                updateLightSensor()
                if (!smartAodEnabled) {
                    clearSmartAod(animated = aodWasEnabled)
                } else if (isEnabled && isVisible) {
                    if (smartAodCovering) {
                        val mask = smartAodOverlay?.view
                        mask?.animate()?.cancel()
                        mask?.alpha = smartAodMaskTargetAlpha()
                    }
                    scheduleSmartAodEnter()
                }
            }
        }
    }

    private fun syncPixelShift() {
        val host = revealHost
        val clock = screensaverView
        // Offset drawn clock/weather/status only — wallpaper + host plate stay pinned;
        // logo / AOD remain on the overlay layer and do not shift.
        if (clock == null || host == null || !isEnabled || !isVisible || host.visibility != View.VISIBLE) {
            pixelShift.pause()
            return
        }
        pixelShift.attachSink(
            identity = clock,
            sink = ScreensaverPixelShift.Sink { dx, dy -> clock.setContentPixelShift(dx, dy) },
            reset = { clock.clearContentPixelShift() },
        )
        pixelShift.setEnabled(pixelShiftEnabled)
    }

    /**
     * Reposition HA watermark after host size changes.
     * Must not assign [View.setLayoutParams] when unchanged — that requests layout during an
     * in-flight layout pass and loops with [View.addOnLayoutChangeListener].
     */
    private fun positionLogoBadge() {
        val host = revealHost ?: return
        val logo = logoView ?: return
        if (!host.isLaidOut || host.width <= 0 || host.height <= 0) return
        val layout = OverlayLogoBadge.layoutPx(host)
        if (layout.sizePx <= 0) return
        val gravity = Gravity.TOP or Gravity.END
        val existing = logo.layoutParams as? FrameLayout.LayoutParams
        if (existing != null &&
            existing.width == layout.sizePx &&
            existing.height == layout.sizePx &&
            existing.topMargin == layout.marginTopPx &&
            existing.marginEnd == layout.marginEndPx &&
            existing.gravity == gravity
        ) {
            return
        }
        val lp = existing ?: FrameLayout.LayoutParams(layout.sizePx, layout.sizePx)
        lp.width = layout.sizePx
        lp.height = layout.sizePx
        lp.gravity = gravity
        lp.topMargin = layout.marginTopPx
        lp.marginEnd = layout.marginEndPx
        logo.layoutParams = lp
    }

    private fun schedulePositionLogoBadge() {
        handler.removeCallbacks(positionLogoBadgeRunnable)
        handler.post(positionLogoBadgeRunnable)
    }

    private fun bringToFront() {
        if (!isEnabled || !isVisible) return
        OverlayLayerSplit.sync()
        // AOD cover is a child of this window. remove+add recreates the surface
        // and punches a full-bright hole; keep the plate and only raise the FAB.
        if (!smartAodCovering &&
            !OverlayLayerSplit.isPaneView(revealHost) &&
            !OverlayLayerSplit.deferRestack(OverlayLayerSplit.Layer.SIMPLE_CLOCK)
        ) {
            OverlayZOrderCoordinator.bringToFront(windowManager, revealHost, windowParams, TAG)
        }
        OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createScreensaverView() {
        val realMetrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager?.defaultDisplay?.getRealMetrics(realMetrics)
        } else {
            windowManager?.defaultDisplay?.getMetrics(realMetrics)
        }
        val screenWidth = realMetrics.widthPixels
        val screenHeight = realMetrics.heightPixels

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val swipeListener = DashboardTouchListener(
            context = this@ScreensaverService,
            kind = DashboardOverlayChrome.Kind.SIMPLE_CLOCK,
            onSwipeLeft = { switchToDreamClock() }
        )
        screensaverView = ScreensaverView(this, screenWidth, screenHeight).apply {
            setWallpaperDarkOverlayEnabled(wallpaperDarkOverlayEnabled)
            updateWallpaperSettings(
                url = wallpaperUrl,
                refreshSeconds = wallpaperRefreshSeconds,
                dualPane = wallpaperDualPane
            )
            set12HourEnabled(use12Hour)
        }
        serviceScope.launch {
            migrateScreensaverWeatherIfNeeded(playerSettingsStore)
            val settings = playerSettingsStore.data.first()
            screensaverView?.set12HourEnabled(settings.screensaver12HourEnabled)
            screensaverView?.setPortraitStyle(
                SimpleClockPortraitStyle.fromStored(settings.screensaverPortraitStyle)
            )
            screensaverView?.updateStatusSlots(
                enabled = settings.enableScreensaverStatusSlots,
                slots = settings.screensaverStatusSlots
            )
            screensaverView?.setWeatherEnabled(
                enabled = settings.enableScreensaverWeather,
                hasEntity = resolveScreensaverWeatherEntityId(settings).isNotEmpty()
            )
            val voice = VoiceSatelliteService.getInstance()
            if (voice != null && settings.enableScreensaverWeather) {
                voice.getCachedClockWeather()?.let { cached ->
                    screensaverView?.updateWeather(cached)
                }
            }
            if (voice != null && settings.enableScreensaverStatusSlots) {
                val entityIds = settings.screensaverStatusSlots
                    .map { it.entityId.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (entityIds.isNotEmpty()) {
                    screensaverView?.restoreStatusEntityCaches(
                        states = voice.getQuickEntityStates().filterKeys { it in entityIds },
                        labels = emptyMap(),
                        units = voice.getQuickEntityUnits().filterKeys { it in entityIds }
                    )
                }
            }
        }

        logoView = ImageView(this).apply {
            OverlayLogoBadge.loadHaLogo(this@ScreensaverService)?.let { setImageBitmap(it) }
            alpha = OverlayLogoBadge.ALPHA / 255f
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }

        val aod = SmartAodMaskOverlay(this)
        smartAodOverlay = aod
        aod.view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                interruptSmartAod()
                true
            } else {
                smartAodCovering
            }
        }

        revealHost = BlurFadeRevealLayout(this).apply {
            // Opaque plate under the clock: HA cannot show through the host edges.
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
            addView(
                screensaverView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                logoView,
                FrameLayout.LayoutParams(0, 0).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
            )
            aod.attachTo(this)
            DashboardOverlayChrome.attach(this, DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
            setOnTouchListener { v, event ->
                if (event?.actionMasked == MotionEvent.ACTION_DOWN) {
                    interruptSmartAod()
                }
                swipeListener.onTouch(v, event)
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                // Only host size changes need a logo reflow; same-bounds re-layout (child
                // requestLayout) must not call back into setLayoutParams.
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    schedulePositionLogoBadge()
                    screensaverView?.invalidate()
                }
            }
            snapHidden()
            visibility = View.GONE
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
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            OverlayOrientation.apply(this)
        }

        try {
            windowManager?.addView(revealHost, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create screensaver window", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                Log.d(TAG, "ACTION_SHOW received")
                if (!com.example.ava.platform.PlatformCapabilities.canDrawOverlays(this)) {
                    Log.w(TAG, "Cannot draw overlays, returning")
                    return
                }
                if (screensaverView == null || revealHost == null) {
                    createScreensaverView()
                }
                val alreadyShown = isEnabled && isVisible &&
                    revealHost?.visibility == View.VISIBLE
                isEnabled = true
                isVisible = true
                if (!alreadyShown) {
                    bringToFront()
                    showScreensaver(showContainer = true)
                    OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
                }
                DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
                if (!alreadyShown) {
                    DashboardOverlayChrome.revealOnShow(DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
                }
                updateLightSensor()
            }
            ACTION_HIDE -> {
                isEnabled = false
                hideScreensaver()
                DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
                updateLightSensor()
            }
            ACTION_TOGGLE -> {
                if (isEnabled) {
                    toggleScreensaver()
                }
            }
            ACTION_SET_VISIBLE -> {
                val visible = intent.getBooleanExtra(EXTRA_VISIBLE, true)
                val hostVisible = revealHost?.visibility == View.VISIBLE
                if (visible && isEnabled && !isVisible) {
                    showScreensaver(showContainer = true)
                    DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
                    DashboardOverlayChrome.revealOnShow(DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
                    OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
                } else if (!visible && (isVisible || hostVisible)) {
                    hideScreensaver()
                    DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
                }
            }
            "com.example.ava.ACTION_BRING_SCREENSAVER_TO_FRONT" -> {
                bringToFront()
            }
        }
    }

    private fun cancelRevealAnimator() {
        handler.removeCallbacks(revealSettleRunnable)
        revealHost?.cancelReveal()
    }

    private fun showScreensaver(showContainer: Boolean) {
        isVisible = showContainer
        if (showContainer) {
            OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.SIMPLE_CLOCK)
            DreamClockService.retireFromSplit()
            DreamClockService.setVisible(this, false)
            hideEpoch += 1
            revealMeasureWaits = 0
            cancelRevealAnimator()
            revealHost?.let { host ->
                // Stay GONE until revealIn — avoids an invisible touch shield resetting idle.
                host.snapHidden()
                handler.postDelayed(revealSettleRunnable, REVEAL_SETTLE_MS)
            }
            screensaverView?.startUpdates()
            syncPixelShift()
            WebViewService.pause(this)
            WebViewService.notifyCoverOverlayReady()
        } else {
            screensaverView?.stopUpdates()
            pixelShift.pause()
            clearSmartAod(animated = false)
            OverlayLayerSplit.sync()
        }
    }

    private fun hideScreensaver(resumeBrowser: Boolean = true) {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        // Only un-pause what showScreensaver actually paused. A redundant hide
        // (ACTION_HIDE while no cover is up) must not poke or cold-start the
        // browser service with a spurious ACTION_RESUME. Split home/settings
        // is already leaving the browser, so that resume would open it again.
        if (resumeBrowser && isVisible) {
            WebViewService.resume(this)
        }
        screensaverView?.stopUpdates()
        pixelShift.pause()
        clearSmartAod(animated = false)
        isVisible = false
        OverlayLayerSplit.sync()
        hideEpoch += 1
        cancelRevealAnimator()
        val epoch = hideEpoch
        val host = revealHost
        if (host == null || host.visibility != View.VISIBLE) {
            host?.snapHidden()
            return
        }
        if (host.alpha <= 0.01f) {
            host.snapHidden()
            return
        }
        host.revealOut(BlurFadeRevealLayout.REVEAL_OUT_MS) {
            if (epoch != hideEpoch) return@revealOut
            host.snapHidden()
        }
    }

    private fun smartAodMaskTargetAlpha(): Float =
        smartAodMaskPercent.coerceIn(
            SmartAodMaskOverlay.MIN_PERCENT,
            SmartAodMaskOverlay.MAX_PERCENT
        ) / 100f

    private fun scheduleSmartAodEnter() {
        handler.removeCallbacks(enterSmartAodRunnable)
        if (!smartAodEnabled || !isEnabled || !isVisible) return
        if (NotificationOverlayService.isOverlayVisible()) return
        if (VoiceSatelliteService.getInstance()?.isVoiceAssistantPipelineActive() == true) return
        val delayMs = smartAodTimeoutSeconds.coerceIn(10, 3600) * 1000L
        handler.postDelayed(enterSmartAodRunnable, delayMs)
    }

    private fun enterSmartAodMask() {
        if (!smartAodEnabled || !isEnabled || !isVisible) return
        if (NotificationOverlayService.isOverlayVisible()) {
            scheduleSmartAodEnter()
            return
        }
        if (VoiceSatelliteService.getInstance()?.isVoiceAssistantPipelineActive() == true) {
            scheduleSmartAodEnter()
            return
        }
        val mask = smartAodOverlay?.view ?: return
        mask.animate().cancel()
        if (mask.visibility != View.VISIBLE) {
            mask.alpha = 0f
            mask.visibility = View.VISIBLE
        }
        smartAodCovering = true
        OverlayZOrderCoordinator.syncFabForAod()
        DashboardOverlayChrome.hideIfTop(DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
        mask.animate()
            .alpha(smartAodMaskTargetAlpha())
            .setDuration(SMART_AOD_FADE_MS)
            .withEndAction(null)
            .start()
    }

    /** Fade the AOD cover out (if covering) and restart the idle enter timer. */
    fun interruptSmartAod() {
        if (!smartAodEnabled || !isEnabled || !isVisible) {
            handler.removeCallbacks(enterSmartAodRunnable)
            return
        }
        val mask = smartAodOverlay?.view
        handler.removeCallbacks(enterSmartAodRunnable)
        if (mask == null) {
            scheduleSmartAodEnter()
            return
        }
        mask.animate().cancel()
        if (mask.visibility == View.VISIBLE && mask.alpha > 0.01f) {
            smartAodCovering = false
            OverlayZOrderCoordinator.syncFabForAod()
            mask.animate()
                .alpha(0f)
                .setDuration(SMART_AOD_FADE_MS)
                .withEndAction {
                    if (!smartAodCovering) {
                        mask.visibility = View.GONE
                        mask.alpha = 0f
                    }
                    scheduleSmartAodEnter()
                }
                .start()
        } else {
            smartAodCovering = false
            OverlayZOrderCoordinator.syncFabForAod()
            mask.visibility = View.GONE
            mask.alpha = 0f
            scheduleSmartAodEnter()
        }
    }

    private fun clearSmartAod(animated: Boolean) {
        handler.removeCallbacks(enterSmartAodRunnable)
        smartAodCovering = false
        OverlayZOrderCoordinator.syncFabForAod()
        val mask = smartAodOverlay?.view ?: return
        mask.animate().cancel()
        if (animated && mask.visibility == View.VISIBLE && mask.alpha > 0.01f) {
            mask.animate()
                .alpha(0f)
                .setDuration(SMART_AOD_FADE_MS)
                .withEndAction {
                    mask.visibility = View.GONE
                    mask.alpha = 0f
                }
                .start()
        } else {
            mask.visibility = View.GONE
            mask.alpha = 0f
        }
    }

    /**
     * Simple Clock dark-off: same lux thresholds / debounce as [ScreensaverController],
     * gated by [darkOffEnabled] and only while this overlay service is enabled.
     */
    private fun updateLightSensor() {
        if (!darkOffEnabled || !isEnabled) {
            stopLightSensor()
            return
        }
        if (lightSensorManager == null) {
            lightSensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            lightSensor = lightSensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        }
        if (lightSensor == null) {
            stopLightSensor()
            return
        }
        if (!lightSensorRegistered) {
            val registered = lightSensorManager?.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
            ) == true
            if (registered) {
                lightSensorRegistered = true
                Log.d(TAG, "Simple Clock light sensor registered: ${lightSensor?.name}")
            } else {
                lightSensorRegistered = false
                Log.w(TAG, "Failed to register Simple Clock light sensor")
            }
        }
    }

    private fun stopLightSensor() {
        darkTransitionJob?.cancel()
        darkTransitionJob = null
        lightTransitionJob?.cancel()
        lightTransitionJob = null
        if (lightSensorRegistered) {
            lightSensorManager?.unregisterListener(lightSensorListener)
            lightSensorRegistered = false
        }
        darkCandidateSinceMs = null
        lightCandidateSinceMs = null
        lastObservedLux = null
        lightSensor = null
        lightSensorManager = null
        releaseWakeLock()
    }

    private fun handleLightLevel(lux: Float) {
        if (!darkOffEnabled || !isEnabled) return
        lastObservedLux = lux
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isInteractive = powerManager.isInteractive
        val now = System.currentTimeMillis()

        if (!isScreenOffByDark) {
            lightCandidateSinceMs = null
            lightTransitionJob?.cancel()
            lightTransitionJob = null
            if (lux <= DARK_OFF_THRESHOLD_LUX && isInteractive) {
                if (darkCandidateSinceMs == null) {
                    darkCandidateSinceMs = now
                    darkTransitionJob?.cancel()
                    darkTransitionJob = serviceScope.launch {
                        delay(DARK_TRANSITION_DEBOUNCE_MS)
                        val latestLux = lastObservedLux ?: return@launch
                        val powerManagerNow = getSystemService(POWER_SERVICE) as PowerManager
                        if (!darkOffEnabled || !isEnabled || isScreenOffByDark || !powerManagerNow.isInteractive) {
                            return@launch
                        }
                        if (latestLux > DARK_OFF_THRESHOLD_LUX) return@launch
                        Log.d(TAG, "Dark detected (lux=$latestLux), turning off screen")
                        prepareOverlayForDarkSleep()
                        acquireWakeLock()
                        val modSlept = ModDeviceSupport.trySleepScreenForDark(this@ScreensaverService)
                        if (!modSlept) {
                            ScreenControlUtils.setScreenOn(this@ScreensaverService, false)
                        } else {
                            Log.d(TAG, "Dark off handled by device support mod")
                        }
                        isScreenOffByDark = true
                        darkCandidateSinceMs = null
                    }
                }
                return
            } else {
                darkTransitionJob?.cancel()
                darkTransitionJob = null
                darkCandidateSinceMs = null
                return
            }
        }

        darkCandidateSinceMs = null
        darkTransitionJob?.cancel()
        darkTransitionJob = null
        if (lux >= LIGHT_RESTORE_THRESHOLD_LUX) {
            if (lightCandidateSinceMs == null) {
                lightCandidateSinceMs = now
                lightTransitionJob?.cancel()
                lightTransitionJob = serviceScope.launch {
                    delay(LIGHT_TRANSITION_DEBOUNCE_MS)
                    val latestLux = lastObservedLux ?: return@launch
                    if (!darkOffEnabled || !isEnabled || !isScreenOffByDark) return@launch
                    if (latestLux < LIGHT_RESTORE_THRESHOLD_LUX) return@launch

                    Log.d(TAG, "Light restored (lux=$latestLux), turning on screen")
                    val modWoke = ModDeviceSupport.tryWakeScreenFromDark(this@ScreensaverService)
                    if (!modWoke) {
                        ScreenControlUtils.setScreenOn(this@ScreensaverService, true)
                    } else {
                        Log.d(TAG, "Screen wake handled by device support mod")
                    }
                    restoreOverlayAfterDarkWake()
                    releaseWakeLock()
                    isScreenOffByDark = false
                    lightCandidateSinceMs = null
                }
            }
        } else {
            lightTransitionJob?.cancel()
            lightTransitionJob = null
            lightCandidateSinceMs = null
        }
    }

    /** Pause or hide Simple Clock before screen-off (KEEP_SCREEN_ON + mod sleep). */
    private fun prepareOverlayForDarkSleep() {
        clockPausedForDark = false
        clockHiddenForModDark = false
        if (!isVisible) return
        if (ModDeviceSupport.hasSleepScreenForDarkHook(this)) {
            screensaverView?.stopUpdates()
            pixelShift.pause()
            clearSmartAod(animated = false)
            hideEpoch += 1
            cancelRevealAnimator()
            revealHost?.snapHidden()
            isVisible = false
            clockHiddenForModDark = true
        } else {
            screensaverView?.stopUpdates()
            pixelShift.pause()
            clockPausedForDark = true
        }
    }

    private fun restoreOverlayAfterDarkWake() {
        when {
            clockHiddenForModDark -> {
                clockHiddenForModDark = false
                if (isEnabled) {
                    showScreensaver(showContainer = true)
                    OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
                }
            }
            clockPausedForDark -> {
                clockPausedForDark = false
                if (isEnabled && isVisible) {
                    screensaverView?.startUpdates()
                    syncPixelShift()
                    scheduleSmartAodEnter()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Ava:SimpleClockDarkSensorWakeLock"
        ).apply {
            acquire(DARK_WAKELOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "WakeLock acquired for Simple Clock dark sensor")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun toggleScreensaver() {
        if (!isEnabled) return

        if (isVisible) {
            hideScreensaver()
            serviceScope.launch {
                playerSettingsStore.updateData { it.copy(enableScreensaverVisible = false) }
            }
        } else {
            showScreensaver(showContainer = true)
            serviceScope.launch {
                playerSettingsStore.updateData { it.copy(enableScreensaverVisible = true) }
            }
        }
    }

    private fun switchToDreamClock() {
        serviceScope.launch {
            try {
                val settings = playerSettingsStore.data.first()
                if (settings.enableDreamClock) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        hide(this@ScreensaverService)
                        DreamClockService.show(this@ScreensaverService)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch to dream clock: ${e.message}")
            }
        }
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = revealHost ?: return
        if (frame == null && !isVisible && host.visibility == View.VISIBLE) return
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.SIMPLE_CLOCK,
            windowManager,
            host,
            windowParams,
            frame,
        )
        host.post {
            screensaverView?.requestLayout()
            screensaverView?.invalidate()
        }
    }

    override fun onDestroy() {
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.SIMPLE_CLOCK)
        super.onDestroy()
        DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.SIMPLE_CLOCK)
        isServiceRunning = false
        stopLightSensor()
        isScreenOffByDark = false
        clockPausedForDark = false
        clockHiddenForModDark = false
        pixelShift.detach()
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        screensaverView?.stopUpdates()
        try {
            revealHost?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove screensaver window", e)
        }
        revealHost = null
        screensaverView = null
        logoView = null
        smartAodOverlay?.detach()
        smartAodOverlay = null
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "ScreensaverService"
        /** Brief delay so the clock view has laid out before soft reveal. */
        private const val REVEAL_SETTLE_MS = 48L
        private const val SMART_AOD_FADE_MS = 380L
        private const val DARK_OFF_THRESHOLD_LUX = 1.5f
        private const val LIGHT_RESTORE_THRESHOLD_LUX = 4.0f
        private const val DARK_TRANSITION_DEBOUNCE_MS = 1500L
        private const val LIGHT_TRANSITION_DEBOUNCE_MS = 1500L
        private const val DARK_WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private var instance: ScreensaverService? = null

        const val ACTION_SHOW = "com.example.ava.SHOW_SCREENSAVER"
        const val ACTION_HIDE = "com.example.ava.HIDE_SCREENSAVER"
        const val ACTION_TOGGLE = "com.example.ava.TOGGLE_SCREENSAVER"
        const val ACTION_SET_VISIBLE = "com.example.ava.SET_SCREENSAVER_VISIBLE"
        const val EXTRA_VISIBLE = "visible"

        fun bringToFrontStatic() {
            instance?.bringToFront()
        }

        fun isOverlayShowing(): Boolean = instance?.let { it.isEnabled && it.isVisible } == true

        fun isSmartAodCovering(): Boolean =
            isOverlayShowing() && instance?.smartAodCovering == true

        /** Fade out smart AOD cover (if any) and restart its idle timer. */
        fun notifySmartAodInterrupt() {
            Handler(Looper.getMainLooper()).post {
                instance?.interruptSmartAod()
            }
        }

        fun show(context: Context) {
            val svc = instance
            if (svc != null && svc.isEnabled && svc.isVisible) return
            val intent = Intent(context, ScreensaverService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, ScreensaverService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun toggle(context: Context) {
            val intent = Intent(context, ScreensaverService::class.java).apply {
                action = ACTION_TOGGLE
            }
            context.startService(intent)
        }

        fun retireFromSplit() {
            instance?.isVisible = false
        }

        fun setVisible(context: Context, visible: Boolean) {
            if (!visible && instance == null) return
            val intent = Intent(context, ScreensaverService::class.java).apply {
                action = ACTION_SET_VISIBLE
                putExtra(EXTRA_VISIBLE, visible)
            }
            context.startService(intent)
        }

        /** Same click as browser home/settings. Hide now, and do not resume the browser. */
        fun dismissForSplitNavigation() {
            instance?.hideScreensaver(resumeBrowser = false)
        }

        fun stop(context: Context) {
            instance?.let {
                it.hideScreensaver()
                context.stopService(Intent(context, ScreensaverService::class.java))
            }
        }

        fun isRunning(): Boolean = instance != null

        fun getInstance(): ScreensaverService? = instance

        fun updateStatusEntityState(entityId: String, state: String) {
            Handler(Looper.getMainLooper()).post {
                val changed = instance?.screensaverView?.updateStatusEntityState(entityId, state) == true
                if (changed && shouldWakeSmartAodForEntity(entityId)) {
                    instance?.interruptSmartAod()
                }
            }
        }

        /**
         * Push weather from VoiceSatellite's dedicated Simple Clock subscription.
         * Does not use [WeatherService] shared cache (overlay path).
         */
        fun updateClockWeather(data: WeatherData?) {
            Handler(Looper.getMainLooper()).post {
                instance?.screensaverView?.updateWeather(data)
            }
        }

        /**
         * Continuous domains update too often — keep AOD cover.
         * Locks / covers / switches / binary_sensor / lights still count as wake events.
         */
        private fun shouldWakeSmartAodForEntity(entityId: String): Boolean {
            val domain = entityId.substringBefore('.', missingDelimiterValue = "").lowercase()
            return when (domain) {
                "sensor", "number", "input_number", "weather", "sun", "air_quality" -> false
                else -> domain.isNotEmpty()
            }
        }

        fun updateStatusEntityLabel(entityId: String, label: String) {
            Handler(Looper.getMainLooper()).post {
                instance?.screensaverView?.updateStatusEntityLabel(entityId, label)
            }
        }

        fun updateStatusEntityUnit(entityId: String, unit: String) {
            Handler(Looper.getMainLooper()).post {
                instance?.screensaverView?.updateStatusEntityUnit(entityId, unit)
            }
        }

        fun restoreStatusEntityCaches(
            states: Map<String, String>,
            labels: Map<String, String>,
            units: Map<String, String> = emptyMap()
        ) {
            Handler(Looper.getMainLooper()).post {
                instance?.screensaverView?.restoreStatusEntityCaches(states, labels, units)
            }
        }
    }
}


class ScreensaverView(context: Context, private var screenWidth: Int, private var screenHeight: Int) : View(context) {

    companion object {
        private const val WALLPAPER_LOAD_TIMEOUT_MS = 45_000L
        private const val WALLPAPER_WATCHDOG_INTERVAL_MS = 60_000L
        /** App-private disk cache; filename embeds URL marker for cleanup on switch. */
        private const val WALLPAPER_CACHE_DIR = "simple_clock_wallpaper"
        private const val WALLPAPER_CACHE_PREFIX = "ava_sc_wp_"
        private const val SIMPLE_PORTRAIT_SLOT_SCALE = 1.08f
        private const val SIMPLE_PORTRAIT_SLOT_GAP_SCALE = 1.35f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val wallpaperScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val xiaomiDigitalTypeface: Typeface = try {
        ResourcesCompat.getFont(context, R.font.time)
            ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
    } catch (_: Exception) {
        Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = xiaomiDigitalTypeface
    }

    private val temperaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255)
        textAlign = Paint.Align.LEFT
        typeface = xiaomiDigitalTypeface
    }

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val statusNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val statusValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private var wallpaperUrl: String = ""
    private var wallpaperRefreshSeconds: Int = 30
    private var wallpaperDualPane: Boolean = false
    private var wallpaperDarkOverlayEnabled: Boolean = true
    private var leftWallpaper: Bitmap? = null
    private var rightWallpaper: Bitmap? = null
    private var isWallpaperLoading = false
    private var wallpaperLoadStartedAt = 0L
    private var lastWallpaperLoadAt = 0L
    private var lastWallpaperAttemptAt = 0L
    private var lastWallpaperLoadSucceeded = false
    private var weatherData: WeatherData? = null
    /** Master switch for Simple Clock weather chrome (default on in settings). */
    private var weatherEnabled = true
    /** True when a dedicated weather.* entity is configured for Simple Clock. */
    private var weatherEntityConfigured = false
    private var statusSlotsEnabled = false
    private var statusSlots: List<SimpleClockStatusSlot> = emptyList()
    private var use12Hour = false
    private var portraitStyle = SimpleClockPortraitStyle.SIMPLE
    private val statusEntityStates = mutableMapOf<String, String>()
    private val statusEntityLabels = mutableMapOf<String, String>()
    private val statusEntityUnits = mutableMapOf<String, String>()
    /** Burn-in nudge for clock/weather/status only; background stays pinned. */
    private var contentShiftX = 0f
    private var contentShiftY = 0f

    fun setContentPixelShift(dx: Float, dy: Float) {
        if (contentShiftX == dx && contentShiftY == dy) return
        contentShiftX = dx
        contentShiftY = dy
        invalidate()
    }

    fun clearContentPixelShift() {
        setContentPixelShift(0f, 0f)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            invalidate()
            if (isRunning) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val wallpaperRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            maybeRefreshWallpaper(force = false)
            scheduleWallpaperRefresh()
        }
    }

    private val wallpaperWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || wallpaperUrl.isBlank()) return
            val now = System.currentTimeMillis()
            if (isWallpaperLoading && now - wallpaperLoadStartedAt > WALLPAPER_LOAD_TIMEOUT_MS) {
                isWallpaperLoading = false
            }
            val refreshIntervalMs = wallpaperRefreshSeconds.coerceAtLeast(1) * 1000L
            val stale = lastWallpaperAttemptAt == 0L ||
                now - lastWallpaperAttemptAt > refreshIntervalMs * 2
            if (stale) {
                maybeRefreshWallpaper(force = true)
            }
            scheduleWallpaperWatchdog()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            screenWidth = w
            screenHeight = h
        }
    }

    fun startUpdates() {
        isRunning = true
        handler.post(updateRunnable)
        handler.removeCallbacks(wallpaperRefreshRunnable)
        handler.removeCallbacks(wallpaperWatchdogRunnable)
        // Disk first so cold start / crash recovery isn't a black frame while network loads.
        hydrateWallpaperFromDisk()
        maybeRefreshWallpaper(force = wallpaperUrl.isNotBlank())
        scheduleWallpaperRefresh()
        scheduleWallpaperWatchdog()
    }

    fun stopUpdates() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(wallpaperRefreshRunnable)
        handler.removeCallbacks(wallpaperWatchdogRunnable)
        isWallpaperLoading = false
        wallpaperLoadStartedAt = 0L
        lastWallpaperLoadAt = 0L
        lastWallpaperAttemptAt = 0L
        lastWallpaperLoadSucceeded = false
    }

    fun updateWeather(data: WeatherData?) {
        weatherData = data
        invalidate()
    }

    /**
     * [enabled] = appearance switch. Off → hide weather chrome and free layout space.
     * On with empty entity / no data → still draw sunny + "-" placeholders.
     */
    fun setWeatherEnabled(enabled: Boolean, hasEntity: Boolean) {
        if (weatherEnabled == enabled && weatherEntityConfigured == hasEntity) return
        weatherEnabled = enabled
        weatherEntityConfigured = hasEntity
        if (!enabled) {
            weatherData = null
        }
        invalidate()
    }

    fun updateStatusSlots(enabled: Boolean, slots: List<SimpleClockStatusSlot>) {
        statusSlotsEnabled = enabled
        statusSlots = slots.take(3)
        invalidate()
    }

    fun set12HourEnabled(enabled: Boolean) {
        if (use12Hour == enabled) return
        use12Hour = enabled
        invalidate()
    }

    fun setPortraitStyle(style: SimpleClockPortraitStyle) {
        if (portraitStyle == style) return
        portraitStyle = style
        invalidate()
    }

    /** @return true if the stored state changed (HA may re-push the same value). */
    fun updateStatusEntityState(entityId: String, state: String): Boolean {
        val previous = statusEntityStates[entityId]
        if (previous == state) return false
        statusEntityStates[entityId] = state
        invalidate()
        return true
    }

    fun updateStatusEntityLabel(entityId: String, label: String) {
        statusEntityLabels[entityId] = label
        invalidate()
    }

    fun updateStatusEntityUnit(entityId: String, unit: String) {
        statusEntityUnits[entityId] = unit
        invalidate()
    }

    fun restoreStatusEntityCaches(
        states: Map<String, String>,
        labels: Map<String, String>,
        units: Map<String, String> = emptyMap()
    ) {
        statusEntityStates.clear()
        statusEntityStates.putAll(states)
        statusEntityLabels.clear()
        statusEntityLabels.putAll(labels)
        statusEntityUnits.clear()
        statusEntityUnits.putAll(units)
        invalidate()
    }

    fun setWallpaperDarkOverlayEnabled(enabled: Boolean) {
        wallpaperDarkOverlayEnabled = enabled
        invalidate()
    }

    fun updateWallpaperSettings(url: String, refreshSeconds: Int, dualPane: Boolean) {
        val normalizedUrl = url.trim()
        val didUrlChange = wallpaperUrl != normalizedUrl
        val didModeChange = wallpaperDualPane != dualPane
        val didRefreshChange = wallpaperRefreshSeconds != refreshSeconds

        wallpaperUrl = normalizedUrl
        wallpaperDualPane = dualPane
        wallpaperRefreshSeconds = refreshSeconds

        if (wallpaperUrl.isBlank()) {
            clearWallpaperBitmaps()
            lastWallpaperLoadAt = 0L
            lastWallpaperAttemptAt = 0L
            lastWallpaperLoadSucceeded = false
            wallpaperScope.launch(Dispatchers.IO) { cleanupWallpaperCache(keepMarker = null) }
            invalidate()
            return
        }

        if (didUrlChange || didModeChange || didRefreshChange) {
            if (didUrlChange || didModeChange) {
                hydrateWallpaperFromDisk()
            }
            maybeRefreshWallpaper(force = true)
            if (isRunning) {
                handler.removeCallbacks(wallpaperRefreshRunnable)
                scheduleWallpaperRefresh()
                scheduleWallpaperWatchdog()
            }
        }
    }

    private fun scheduleWallpaperRefresh() {
        if (!isRunning || wallpaperUrl.isBlank()) return
        handler.removeCallbacks(wallpaperRefreshRunnable)
        val delayMs = wallpaperRefreshSeconds.coerceAtLeast(1) * 1000L
        handler.postDelayed(wallpaperRefreshRunnable, delayMs)
    }

    private fun scheduleWallpaperWatchdog() {
        if (!isRunning || wallpaperUrl.isBlank()) return
        handler.removeCallbacks(wallpaperWatchdogRunnable)
        handler.postDelayed(wallpaperWatchdogRunnable, WALLPAPER_WATCHDOG_INTERVAL_MS)
    }

    private fun maybeRefreshWallpaper(force: Boolean) {
        if (wallpaperUrl.isBlank()) return
        val now = System.currentTimeMillis()
        if (isWallpaperLoading) {
            if (now - wallpaperLoadStartedAt < WALLPAPER_LOAD_TIMEOUT_MS) return
            isWallpaperLoading = false
        }
        val refreshIntervalMs = wallpaperRefreshSeconds.coerceAtLeast(1) * 1000L
        if (!force && lastWallpaperAttemptAt > 0L && now - lastWallpaperAttemptAt < refreshIntervalMs) return

        isWallpaperLoading = true
        wallpaperLoadStartedAt = now
        lastWallpaperAttemptAt = now
        val requestUrl = wallpaperUrl
        val dualPane = wallpaperDualPane
        wallpaperScope.launch(Dispatchers.IO) {
            try {
                val marker = wallpaperUrlMarker(requestUrl)
                val leftBytes = fetchWallpaperBytes(requestUrl, cacheBustToken = "${now}_0")
                val left = leftBytes?.let { decodeBitmapScaled(it) }
                val rightBytes = if (dualPane) {
                    fetchWallpaperBytes(requestUrl, cacheBustToken = "${now}_1")
                } else {
                    null
                }
                val right = rightBytes?.let { decodeBitmapScaled(it) }
                if (leftBytes != null && left != null) {
                    persistWallpaperCache(marker, pane = 0, leftBytes)
                    if (dualPane && rightBytes != null && right != null) {
                        persistWallpaperCache(marker, pane = 1, rightBytes)
                    }
                    cleanupWallpaperCache(keepMarker = marker)
                }
                withContext(Dispatchers.Main) {
                    if (wallpaperUrl != requestUrl) return@withContext
                    if (left != null) {
                        applyWallpaperBitmaps(left = left, right = right, dualPane = dualPane)
                        lastWallpaperLoadAt = now
                        lastWallpaperLoadSucceeded = true
                        invalidate()
                    } else if (!lastWallpaperLoadSucceeded && leftWallpaper == null) {
                        clearWallpaperBitmaps()
                        invalidate()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isWallpaperLoading = false
                }
            }
        }
    }

    /** Show last successful wallpaper immediately from disk (crash / cold start). */
    private fun hydrateWallpaperFromDisk() {
        if (wallpaperUrl.isBlank()) return
        val requestUrl = wallpaperUrl
        val dualPane = wallpaperDualPane
        wallpaperScope.launch(Dispatchers.IO) {
            val marker = wallpaperUrlMarker(requestUrl)
            val left = loadCachedWallpaperBitmap(marker, pane = 0) ?: return@launch
            val right = if (dualPane) loadCachedWallpaperBitmap(marker, pane = 1) else null
            withContext(Dispatchers.Main) {
                if (wallpaperUrl != requestUrl) {
                    left.recycle()
                    right?.takeIf { it !== left && !it.isRecycled }?.recycle()
                    return@withContext
                }
                // Keep a freshly fetched in-memory image if network already won the race.
                if (leftWallpaper != null && lastWallpaperLoadSucceeded) {
                    left.recycle()
                    right?.takeIf { it !== left && !it.isRecycled }?.recycle()
                    return@withContext
                }
                applyWallpaperBitmaps(left = left, right = right, dualPane = dualPane)
                lastWallpaperLoadSucceeded = true
                invalidate()
            }
        }
    }

    private fun applyWallpaperBitmaps(left: Bitmap, right: Bitmap?, dualPane: Boolean) {
        val oldLeft = leftWallpaper
        val oldRight = rightWallpaper
        leftWallpaper = left
        if (dualPane) {
            rightWallpaper = right ?: left
        } else {
            rightWallpaper = null
        }
        if (oldLeft != null && oldLeft !== leftWallpaper && oldLeft !== rightWallpaper && !oldLeft.isRecycled) {
            oldLeft.recycle()
        }
        if (oldRight != null &&
            oldRight !== leftWallpaper &&
            oldRight !== rightWallpaper &&
            !oldRight.isRecycled
        ) {
            oldRight.recycle()
        }
    }

    private fun clearWallpaperBitmaps() {
        val oldLeft = leftWallpaper
        val oldRight = rightWallpaper
        leftWallpaper = null
        rightWallpaper = null
        oldLeft?.takeIf { !it.isRecycled }?.recycle()
        if (oldRight != null && oldRight !== oldLeft) {
            oldRight.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun wallpaperCacheDir(): File {
        val dir = File(context.filesDir, WALLPAPER_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Short stable marker from the wallpaper URL — used in filenames for cleanup. */
    private fun wallpaperUrlMarker(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun wallpaperCacheFile(marker: String, pane: Int): File {
        return File(wallpaperCacheDir(), "${WALLPAPER_CACHE_PREFIX}${marker}_${pane}.bin")
    }

    private fun loadCachedWallpaperBitmap(marker: String, pane: Int): Bitmap? {
        val file = wallpaperCacheFile(marker, pane)
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            decodeBitmapScaled(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    private fun persistWallpaperCache(marker: String, pane: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val file = wallpaperCacheFile(marker, pane)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** Keep only files for [keepMarker]; pass null to wipe the whole wallpaper cache. */
    private fun cleanupWallpaperCache(keepMarker: String?) {
        val dir = wallpaperCacheDir()
        val keepPrefix = keepMarker?.let { "${WALLPAPER_CACHE_PREFIX}${it}_" }
        dir.listFiles()?.forEach { file ->
            val name = file.name
            if (!name.startsWith(WALLPAPER_CACHE_PREFIX)) {
                file.delete()
                return@forEach
            }
            if (keepPrefix == null || !name.startsWith(keepPrefix)) {
                file.delete()
            }
        }
    }

    private fun fetchWallpaperBytes(url: String, cacheBustToken: String, depth: Int = 0): ByteArray? {
        if (depth > 3) return null
        val normalizedUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
        val separator = if (normalizedUrl.contains("?")) "&" else "?"
        val requestUrl = URL("${normalizedUrl}${separator}_ava_wallpaper=$cacheBustToken")
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        return try {
            connection = (requestUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Accept", "image/avif,image/heic,image/heif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return null
            val contentType = connection.contentType?.lowercase(Locale.ROOT).orEmpty()
            val finalUrl = connection.url?.toString().orEmpty()
            inputStream = connection.inputStream
            val bytes = inputStream.readBytes()
            if (contentType.startsWith("image/")) return bytes
            if (!shouldAttemptPayloadExtraction(contentType, finalUrl, bytes) &&
                canDecodeWallpaperBytes(bytes)
            ) {
                return bytes
            }
            if (shouldAttemptPayloadExtraction(contentType, finalUrl, bytes)) {
                extractImageUrlFromPayload(bytes, contentType)?.let { extractedUrl ->
                    return fetchWallpaperBytes(extractedUrl, cacheBustToken, depth + 1)
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            connection?.disconnect()
        }
    }

    private fun extractImageUrlFromPayload(bytes: ByteArray, contentType: String): String? {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return null

        if (contentType.contains("application/json") || text.startsWith("{") || text.startsWith("[")) {
            extractImageUrlFromJson(text)?.let { return it }
        }

        return extractImageUrlFromText(text)
    }

    private fun shouldAttemptPayloadExtraction(
        contentType: String,
        finalUrl: String,
        bytes: ByteArray
    ): Boolean {
        if (contentType.startsWith("image/")) return false
        val lowerUrl = finalUrl.lowercase(Locale.ROOT)
        val contentLooksStructured = contentType.contains("application/json") ||
            contentType.contains("text/plain") ||
            contentType.contains("text/html") ||
            contentType.contains("application/javascript")
        val urlLooksStructured = lowerUrl.endsWith(".json") ||
            lowerUrl.contains("/api/") ||
            lowerUrl.contains("callback=")
        val isReasonableTextPayload = bytes.size in 1..524_288
        return isReasonableTextPayload && (contentLooksStructured || urlLooksStructured)
    }

    private fun extractImageUrlFromJson(text: String): String? {
        return try {
            when {
                text.startsWith("{") -> findImageUrlInJsonValue(JSONObject(text))
                text.startsWith("[") -> findImageUrlInJsonValue(JSONArray(text))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findImageUrlInJsonValue(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findImageUrlInJsonValue(value.opt(key))?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findImageUrlInJsonValue(value.opt(index))?.let { return it }
                }
                null
            }
            is String -> extractImageUrlFromText(value)
            else -> null
        }
    }

    private fun extractImageUrlFromText(text: String): String? {
        val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        val urls = urlRegex.findAll(text)
            .map { it.value.trim().trimEnd(',', ';') }
            .toList()

        return urls.firstOrNull { isLikelyImageUrl(it) }
            ?: urls.firstOrNull()
    }

    private fun isLikelyImageUrl(url: String): Boolean {
        val lowerUrl = url.lowercase(Locale.ROOT)
        return listOf(
            ".jpg",
            ".jpeg",
            ".png",
            ".webp",
            ".gif",
            ".bmp",
            ".avif",
            ".heic",
            ".heif",
            ".svg",
            ".jfif"
        ).any { lowerUrl.contains(it) }
    }

    private fun canDecodeWallpaperBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun decodeBitmapScaled(bytes: ByteArray): Bitmap? {
        val maxSize = maxOf(screenWidth, screenHeight).let { if (it > 0) it else 1920 }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateWallpaperInSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (w, h) = info.size.width to info.size.height
                    val sample = calculateWallpaperInSampleSize(w, h, maxSize)
                    if (sample > 1) decoder.setTargetSize(w / sample, h / sample)
                }
            } catch (_: Exception) { null }
        } else null
    }

    private fun calculateWallpaperInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxSize || height / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return sample
    }

    private fun drawWallpaperBitmap(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val srcWidth = bitmap.width.toFloat()
        val srcHeight = bitmap.height.toFloat()
        if (srcWidth <= 0f || srcHeight <= 0f) return

        val scale = maxOf(target.width() / srcWidth, target.height() / srcHeight)
        val drawWidth = srcWidth * scale
        val drawHeight = srcHeight * scale
        val left = target.left + (target.width() - drawWidth) / 2f
        val top = target.top + (target.height() - drawHeight) / 2f
        canvas.save()
        canvas.clipRect(target)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), null)
        canvas.restore()
    }

    /**
     * Landscape screen, window is a left/right slice: full height, not full width.
     * Portrait top/bottom panes are shorter than the screen, so they stay out.
     */
    private fun isLandscapeSideBySidePane(layoutWidth: Int, layoutHeight: Int): Boolean {
        if (layoutWidth <= 1 || layoutHeight <= 1) return false
        if (!SettingsStyleSession.overlaySplitEnabled.value) return false
        val screen = display ?: return false
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        screen.getRealSize(point)
        var screenW = point.x
        var screenH = point.y
        val rotation = screen.rotation
        if (
            (rotation == android.view.Surface.ROTATION_90 ||
                rotation == android.view.Surface.ROTATION_270) &&
            screenW > 1 && screenH > 1 && screenW < screenH
        ) {
            val swapped = screenW
            screenW = screenH
            screenH = swapped
        }
        if (screenW < screenH) return false
        return layoutWidth < screenW * 0.92f && layoutHeight > screenH * 0.72f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.BLACK)

        if (wallpaperDualPane) {
            leftWallpaper?.let {
                drawWallpaperBitmap(canvas, it, RectF(0f, 0f, width / 2f, height.toFloat()))
            }
            rightWallpaper?.let {
                drawWallpaperBitmap(canvas, it, RectF(width / 2f, 0f, width.toFloat(), height.toFloat()))
            }
            if (leftWallpaper != null || rightWallpaper != null) {
                val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(56, 255, 255, 255)
                    strokeWidth = resources.displayMetrics.density
                }
                canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), dividerPaint)
            }
        } else {
            leftWallpaper?.let {
                drawWallpaperBitmap(canvas, it, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            }
        }

        val hasWallpaper = leftWallpaper != null || rightWallpaper != null

        val shouldApplyTextShadow = hasWallpaper && !wallpaperDarkOverlayEnabled
        val shouldApplyScrim = hasWallpaper && wallpaperDarkOverlayEnabled
        if (shouldApplyScrim) {
            canvas.drawColor(Color.argb(140, 0, 0, 0))
        }

        // Pixel shift moves UI chrome only; black + wallpaper + scrim stay full-bleed.
        val shiftingContent = contentShiftX != 0f || contentShiftY != 0f
        if (shiftingContent) {
            canvas.save()
            canvas.translate(contentShiftX, contentShiftY)
        }

        val density = resources.displayMetrics.density
        val layoutWidth = width
        val layoutHeight = height

        val vmin = minOf(layoutWidth, layoutHeight).toFloat()
        val horizontalPadding = max(24f * density, vmin * 0.045f)
        val availableWidth = layoutWidth - horizontalPadding * 2
        val calendar = Calendar.getInstance()
        val timeText = formatSimpleClockTime(calendar, use12Hour)
        val dateText = formatDate(calendar)
        // Switch off → no weather chrome / no layout reserve.
        // Switch on → always reserve space; empty entity / no push → sunny + "-".
        val showWeather = weatherEnabled
        val weather = if (showWeather) weatherData else null
        // Left/right split always uses the portrait clock, even when that half
        // is still wider than tall. Fullscreen landscape is unchanged.
        val sideBySidePane = OverlayLayerSplit.isSideBySidePane(this) ||
            isLandscapeSideBySidePane(layoutWidth, layoutHeight)
        val squarePanel = com.example.ava.ui.isCompactSquarePixels(
            maxOf(layoutWidth, layoutHeight),
            minOf(layoutWidth, layoutHeight),
        )
        val portrait = sideBySidePane || squarePanel || layoutHeight >= layoutWidth
        val isLandscape = !squarePanel && !sideBySidePane && layoutWidth > layoutHeight * 1.2f
        val isUltraWide = !sideBySidePane && layoutWidth > layoutHeight * 2.0f
        
        val screenDiagonalPx = kotlin.math.sqrt((layoutWidth * layoutWidth + layoutHeight * layoutHeight).toDouble()).toFloat()
        val screenDiagonalInch = screenDiagonalPx / (density * 160f)
        
        val screenCategory = when {
            vmin <= 320 * density -> "tiny"
            screenDiagonalInch < 4.5f -> "small"
            screenDiagonalInch < 6f -> "medium"
            screenDiagonalInch < 8f -> "large"
            screenDiagonalInch < 10f -> "xlarge"
            else -> "tablet"
        }

        val configuredStatusSlots = statusSlots.filter { it.entityId.isNotBlank() }
        val showStatusSlots = statusSlotsEnabled && configuredStatusSlots.isNotEmpty()
        // Landscape + status chips: slightly enlarge clock, weather temp, and weather icon together.
        val statusLandscapeBoost = if (!portrait && showStatusSlots) 1.06f else 1f

        // 12h landscape: AM/PM is now a separate prefix, no width shrink needed.
        val landscape12hShrink = 1f
        val timeScale = when (screenCategory) {
            "tiny" -> 0.65f
            "small" -> 0.60f
            "medium" -> if (portrait) 0.58f else 0.48f
            "large" -> if (portrait) 0.55f else 0.45f
            "xlarge" -> if (portrait) 0.52f else 0.42f
            "tablet" -> if (isUltraWide) 0.35f else if (portrait) 0.50f else 0.40f
            else -> 0.50f
        } * statusLandscapeBoost * landscape12hShrink
        val tempScale = when (screenCategory) {
            "tiny" -> 0.16f
            "small" -> 0.14f
            "medium" -> 0.13f
            "large" -> 0.12f
            "xlarge" -> 0.11f
            "tablet" -> if (isUltraWide) 0.08f else 0.10f
            else -> 0.12f
        } * statusLandscapeBoost
        val dateScale = when (screenCategory) {
            "tiny" -> 0.10f
            "small" -> 0.09f
            "medium" -> 0.085f
            "large" -> 0.08f
            "xlarge" -> 0.075f
            "tablet" -> if (isUltraWide) 0.055f else 0.07f
            else -> 0.08f
        }
        
        temperaturePaint.textSize = vmin * tempScale
        datePaint.textSize = vmin * dateScale
        datePaint.letterSpacing = 0.06f

        val weatherText = if (showWeather) {
            weather?.let { "${it.temperature}°" } ?: "-"
        } else {
            ""
        }
        val iconScale = tempScale
        val iconSize = if (showWeather) {
            (vmin * iconScale).toInt().coerceIn((24f * density).toInt(), (100f * density).toInt())
        } else {
            0
        }
        val weatherColumnWidth = if (showWeather) {
            maxOf(iconSize.toFloat(), temperaturePaint.measureText(weatherText))
        } else {
            0f
        }
        val weatherGap = if (showWeather) vmin * 0.03f else 0f

        // ---------- Portrait magazine: stacked hour/minute ----------
        if (portrait && portraitStyle == SimpleClockPortraitStyle.MAGAZINE) {
            applyTextShadowIfNeeded(shouldApplyTextShadow, density)
            applyDateShadowIfNeeded(shouldApplyTextShadow, density)
            drawPortrait12hLayout(
                canvas = canvas,
                calendar = calendar,
                weather = weather,
                showWeather = showWeather,
                dateText = dateText,
                vmin = vmin,
                density = density,
                layoutWidth = layoutWidth,
                layoutHeight = layoutHeight,
                availableWidth = availableWidth,
                showStatusSlots = showStatusSlots,
                configuredStatusSlots = configuredStatusSlots,
                screenCategory = screenCategory,
                hasWallpaper = hasWallpaper,
                use12Hour = use12Hour
            )
            if (shiftingContent) canvas.restore()
            return
        }

        // ---------- Simple portrait: old weather column, masthead under the time ----------
        if (portrait) {
            applyTextShadowIfNeeded(shouldApplyTextShadow, density)
            applyDateShadowIfNeeded(shouldApplyTextShadow, density)
            statusNamePaint.color = if (hasWallpaper) {
                Color.parseColor("#B3B3B3")
            } else {
                Color.parseColor("#888888")
            }
            drawSimplePortraitJoinWeather(
                canvas = canvas,
                calendar = calendar,
                weather = weather,
                showWeather = showWeather,
                vmin = vmin,
                density = density,
                layoutWidth = layoutWidth,
                layoutHeight = layoutHeight,
                availableWidth = availableWidth,
                showStatusSlots = showStatusSlots,
                configuredStatusSlots = configuredStatusSlots,
                screenCategory = screenCategory,
                timeScale = timeScale,
                dateScale = dateScale,
                tempScale = tempScale,
                use12Hour = use12Hour
            )
            if (shiftingContent) canvas.restore()
            return
        }

        // ---------- Landscape ----------
        val layoutRef = if (use12Hour) "12:00" else timeText
        val maxTimeWidth = availableWidth * 0.85f
        var majorSize = vmin * timeScale
        timePaint.textSize = majorSize
        var layoutWidth12 = timePaint.measureText(layoutRef)
        while (majorSize > 64f && layoutWidth12 > maxTimeWidth) {
            majorSize *= 0.98f
            timePaint.textSize = majorSize
            layoutWidth12 = timePaint.measureText(layoutRef)
        }
        applyTextShadowIfNeeded(shouldApplyTextShadow, density)
        applyDateShadowIfNeeded(shouldApplyTextShadow, density)
        statusNamePaint.color = if (hasWallpaper) {
            Color.parseColor("#B3B3B3")
        } else {
            Color.parseColor("#888888")
        }

        val timeMetrics = timePaint.fontMetrics
        val weatherSpacing = vmin * 0.015f
        var timeWidth = layoutWidth12

        val verticalOffset = when (screenCategory) {
            "tiny" -> 10f * density
            "small" -> if (portrait) 40f * density else 15f * density
            "medium" -> if (portrait) 45f * density else 20f * density
            "large" -> if (portrait) 50f * density else 25f * density
            "xlarge" -> if (portrait) 55f * density else 30f * density
            "tablet" -> if (portrait) 60f * density else 35f * density
            else -> if (portrait) 45f * density else 20f * density
        } + if (!portrait) {
            15f * density
        } else {
            0f
        }
        val timeCenterY = layoutHeight / 2f - verticalOffset
        val timeBaseline = timeCenterY - (timeMetrics.ascent + timeMetrics.descent) / 2f

        val dateGap = vmin * 0.01f
        val dateBaseline = timeBaseline + timeMetrics.descent + dateGap - datePaint.fontMetrics.ascent

        val contentCenterX = layoutWidth / 2f - 5f * density
        val horizontalOffset = if (showWeather && (portrait || isLandscape || isUltraWide)) {
            (weatherGap + weatherColumnWidth) / 2f
        } else {
            0f
        }
        // 12h landscape: AM/PM prefix pulls visual weight left — nudge clock group right.
        val amPmNudgeX = if (!portrait && use12Hour) 15f * density else 0f
        val timeCenterX = contentCenterX - horizontalOffset + amPmNudgeX

        if (!portrait && use12Hour) {
            val parts = splitTwelveHourParts(calendar)
            val hourText = parts.hour.padStart(2, '0')
            val minuteText = parts.minute.padStart(2, '0')
            val slotWidth = widestDigitWidth(timePaint)
            val colonWidth = timePaint.measureText(":")
            val pairWidth = slotWidth * 2f
            val colonGap = timePaint.textSize * 0.02f
            val stableWidth = pairWidth + colonGap + colonWidth + colonGap + pairWidth
            timeWidth = stableWidth

            val boxLeft = timeCenterX - stableWidth / 2f
            val hourSlotLeft = boxLeft
            val colonX = hourSlotLeft + pairWidth + colonGap
            val minuteSlotLeft = colonX + colonWidth + colonGap

            drawStableDigits(
                canvas = canvas,
                text = hourText,
                slots = 2,
                slotLeft = hourSlotLeft,
                slotWidth = slotWidth,
                baseline = timeBaseline,
                paint = timePaint
            )
            timePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(":", colonX, timeBaseline, timePaint)
            timePaint.textAlign = Paint.Align.CENTER
            drawStableDigits(
                canvas = canvas,
                text = minuteText,
                slots = 2,
                slotLeft = minuteSlotLeft,
                slotWidth = slotWidth,
                baseline = timeBaseline,
                paint = timePaint
            )

            // Always two hour digits — AM/PM stays left of the hour block (quiet prefix).
            drawLandscapeTwelveHourAmPm(
                canvas = canvas,
                amPm = parts.amPm,
                firstDigitLeft = hourSlotLeft,
                timeBaseline = timeBaseline,
                timeSize = timePaint.textSize,
                ink = digitInkMetrics(timePaint)
            )
        } else {
            canvas.drawText(timeText, timeCenterX, timeBaseline, timePaint)
        }

        val weatherIconRes = if (showWeather) {
            weather?.condition?.let { resolveWeatherIconRes(it) } ?: R.drawable.mdi_weather_sunny
        } else {
            null
        }
        val isSmallScreen = vmin <= 480
        val timeVisualCenterY = timeBaseline + (timeMetrics.ascent + timeMetrics.descent) / 2f
        val weatherTotalHeight = if (showWeather) {
            iconSize + weatherSpacing +
                (temperaturePaint.fontMetrics.descent - temperaturePaint.fontMetrics.ascent)
        } else {
            0f
        }
        val portraitWeatherNudgeY = if (portrait && showWeather) 4f * density else 0f
        val weatherBottom = timeVisualCenterY + weatherTotalHeight / 2f + portraitWeatherNudgeY

        if (portrait && showWeather && weatherIconRes != null) {
            drawWeatherColumnBesideTime(
                canvas = canvas,
                weatherIconRes = weatherIconRes,
                weatherText = weatherText,
                timeCenterX = timeCenterX,
                timeWidth = timeWidth,
                timeBaseline = timeBaseline,
                timeMetrics = timeMetrics,
                weatherGap = weatherGap,
                weatherColumnWidth = weatherColumnWidth,
                iconSize = iconSize,
                weatherSpacing = weatherSpacing,
                verticalNudgeY = portraitWeatherNudgeY
            )
        }

        val contentBottom = if (portrait && showWeather) {
            maxOf(timeBaseline + timeMetrics.descent, weatherBottom)
        } else {
            timeBaseline + timeMetrics.descent
        }
        if (showStatusSlots) {
            drawStatusSlots(
                canvas = canvas,
                slots = configuredStatusSlots,
                centerX = contentCenterX,
                top = contentBottom + dateGap,
                vmin = vmin,
                density = density,
                availableWidth = availableWidth,
                screenCategory = screenCategory,
                portrait = portrait
            )
        } else if (portrait) {
            val portraitDateBaseline = contentBottom + dateGap - datePaint.fontMetrics.ascent
            canvas.drawText(dateText, contentCenterX, portraitDateBaseline, datePaint)
        } else {
            canvas.drawText(dateText, contentCenterX, dateBaseline, datePaint)
        }

        if (!portrait && showWeather && weatherIconRes != null) {
            if (isSmallScreen) {
                val smallIconSize = (iconSize * 0.7f).toInt()
                val smallTempSize = temperaturePaint.textSize * 0.8f
                temperaturePaint.textSize = smallTempSize

                val tempWidth = temperaturePaint.measureText(weatherText)
                val totalWidth = tempWidth + vmin * 0.02f + smallIconSize
                val startX = contentCenterX - totalWidth / 2f

                temperaturePaint.textAlign = Paint.Align.LEFT
                val weatherCenterY = timeBaseline + (timeMetrics.ascent + timeMetrics.descent) / 2f
                val tempBaseline = weatherCenterY -
                    (temperaturePaint.fontMetrics.ascent + temperaturePaint.fontMetrics.descent) / 2f
                canvas.drawText(weatherText, startX, tempBaseline, temperaturePaint)

                val iconLeft = startX + tempWidth + vmin * 0.02f
                val iconTop = weatherCenterY - smallIconSize / 2f
                drawWeatherIcon(
                    canvas = canvas,
                    resId = weatherIconRes,
                    left = iconLeft,
                    top = iconTop,
                    size = smallIconSize
                )
            } else {
                drawWeatherColumnBesideTime(
                    canvas = canvas,
                    weatherIconRes = weatherIconRes,
                    weatherText = weatherText,
                    timeCenterX = timeCenterX,
                    timeWidth = timeWidth,
                    timeBaseline = timeBaseline,
                    timeMetrics = timeMetrics,
                    weatherGap = weatherGap,
                    weatherColumnWidth = weatherColumnWidth,
                    iconSize = iconSize,
                    weatherSpacing = weatherSpacing
                )
            }
        }

        if (shiftingContent) {
            canvas.restore()
        }
    }

    /**
     * Portrait B5: hour/minute centered on optical ~36%, late minute dissolve,
     * short hairline, then narrow credits (name ···· value). Square 2–3 slots
     * still use the right rail chip column.
     */
    private fun drawPortrait12hLayout(
        canvas: Canvas,
        calendar: Calendar,
        weather: WeatherData?,
        showWeather: Boolean,
        dateText: String,
        vmin: Float,
        density: Float,
        layoutWidth: Int,
        layoutHeight: Int,
        availableWidth: Float,
        showStatusSlots: Boolean,
        configuredStatusSlots: List<SimpleClockStatusSlot>,
        screenCategory: String,
        hasWallpaper: Boolean,
        use12Hour: Boolean
    ) {
        val hourText: String
        val minuteText = String.format("%02d", calendar.get(Calendar.MINUTE))
        val amPmText: String
        if (use12Hour) {
            val parts = splitTwelveHourParts(calendar)
            hourText = parts.hour.padStart(2, '0')
            amPmText = parts.amPm
        } else {
            hourText = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY))
            amPmText = ""
        }
        val padding = max(16f * density, vmin * 0.045f)
        val aspect = layoutHeight.toFloat() / layoutWidth.toFloat()
        val shape = when {
            aspect < 1.12f -> "square"
            aspect < 1.45f -> "squarish"
            else -> "tall"
        }

        val weatherIconRes = if (showWeather) {
            weather?.condition?.let { resolveWeatherIconRes(it) } ?: R.drawable.mdi_weather_sunny
        } else {
            null
        }
        val weatherText = if (showWeather) {
            weather?.let { "${it.temperature}°" } ?: "-"
        } else {
            ""
        }

        val footerTempSize = vmin * when (shape) {
            "square" -> 0.040f
            "squarish" -> 0.043f
            else -> 0.045f
        }
        temperaturePaint.textSize = footerTempSize
        temperaturePaint.textAlign = Paint.Align.LEFT
        val footerIconSize = if (showWeather) {
            (footerTempSize * 1.45f).toInt()
                .coerceIn((16f * density).toInt(), (44f * density).toInt())
        } else {
            0
        }

        datePaint.textAlign = Paint.Align.RIGHT
        datePaint.textSize = vmin * when (shape) {
            "square" -> 0.034f
            "squarish" -> 0.038f
            else -> 0.040f
        }
        datePaint.letterSpacing = 0.06f
        val dateFm = datePaint.fontMetrics

        val amPmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 255, 255, 255)
            textSize = (vmin * when (shape) {
                "square" -> 0.034f
                "squarish" -> 0.036f
                else -> 0.038f
            }).coerceIn(10f * density, 18f * density)
            textAlign = Paint.Align.RIGHT
            typeface = timePaint.typeface
            letterSpacing = 0.04f
            isFakeBoldText = true
        }
        val amPmFm = amPmPaint.fontMetrics
        val amPmHeight = amPmFm.descent - amPmFm.ascent
        val pmDateGap = max(2f * density, amPmPaint.textSize * 0.12f)

        val dateBaseline = layoutHeight - padding - 4f * density
        val dateTop = dateBaseline + dateFm.ascent
        val footerTop = if (use12Hour) {
            dateTop - pmDateGap - amPmHeight
        } else {
            dateTop
        }
        val amPmBaseline = footerTop - amPmFm.ascent

        val splitRail = showStatusSlots && configuredStatusSlots.size >= 2 && shape == "square"
        val portraitSlotCount = if (showStatusSlots && !splitRail) {
            configuredStatusSlots.size
        } else {
            0
        }
        val railGap = vmin * 0.03f
        val railWidth = if (splitRail) {
            minOf(availableWidth * 0.40f, vmin * 0.38f)
        } else {
            0f
        }
        val clockAreaLeft = padding
        val clockAreaWidth = if (splitRail) {
            (layoutWidth - padding - railGap - railWidth - padding).coerceAtLeast(1f)
        } else {
            availableWidth
        }
        val clockCenterX = clockAreaLeft + clockAreaWidth / 2f

        val areaTop = padding
        val areaBottom = footerTop
        val contentHeight = (areaBottom - areaTop).coerceAtLeast(1f)
        // B5 credits: single-line name····value rows at 68% width (no icon stack).
        val creditsWidth = availableWidth * 0.68f
        val hairGapAbove = if (portraitSlotCount > 0) {
            max(14f * density, vmin * 0.035f)
        } else {
            0f
        }
        val hairGapBelow = if (portraitSlotCount > 0) {
            max(12f * density, vmin * 0.030f)
        } else {
            0f
        }
        val creditsHeight = if (portraitSlotCount > 0) {
            portraitCreditsColumnHeight(
                slotCount = portraitSlotCount,
                vmin = vmin,
                density = density,
                screenCategory = screenCategory
            )
        } else {
            0f
        }
        val slotsBlockHeight = if (portraitSlotCount > 0) {
            hairGapAbove + 1f * density + hairGapBelow + creditsHeight
        } else {
            0f
        }

        val lineAdvanceRatio = 0.76f
        // B5: late dissolve whenever credits sit under the clock (including 1 slot).
        val dissolveMinutes = portraitSlotCount >= 1
        val maxDigitWidth = clockAreaWidth * when (shape) {
            "square" -> 0.92f
            "squarish" -> 0.88f
            else -> 0.86f
        }
        var digitSize = vmin * 0.18f
        val maxDigitSize = when {
            splitRail -> vmin * 0.56f
            else -> vmin * when (shape) {
                "tall" -> 0.58f
                "squarish" -> 0.60f
                else -> 0.60f
            }
        }
        val maxClockHeight = if (splitRail) {
            contentHeight
        } else {
            val slotReserve = if (portraitSlotCount > 0) slotsBlockHeight else 0f
            (contentHeight - slotReserve).coerceAtLeast(contentHeight * 0.44f)
        }
        while (digitSize < maxDigitSize) {
            val next = digitSize * 1.03f
            timePaint.textSize = next
            val stackWidth = widestDigitWidth(timePaint) * 2f
            val inkProbe = digitInkMetrics(timePaint)
            val clockHeight = inkProbe.height + next * lineAdvanceRatio
            if (stackWidth > maxDigitWidth || clockHeight > maxClockHeight) break
            digitSize = next
        }
        timePaint.textSize = digitSize
        val ink = digitInkMetrics(timePaint)
        val lineAdvance = digitSize * lineAdvanceRatio
        val clockHeight = ink.height + lineAdvance
        val groupHeight = if (portraitSlotCount > 0 && !splitRail) {
            clockHeight + slotsBlockHeight
        } else {
            clockHeight
        }

        // B5: lock the time pair on optical center ~36%. No slots → content-band center.
        // Nudge whole clock/credits group down; HA logo is a separate overlay and stays put.
        val portraitNudgeY = 48f * density
        val opticalCenterY = if (portraitSlotCount <= 0 && !splitRail) {
            areaTop + contentHeight / 2f + portraitNudgeY
        } else {
            layoutHeight * 0.36f + portraitNudgeY
        }
        val minTop = areaTop
        val maxTop = (areaBottom - groupHeight).coerceAtLeast(minTop)
        val hourTop = if (portraitSlotCount <= 0 && !splitRail) {
            (opticalCenterY - groupHeight / 2f).coerceIn(minTop, maxTop)
        } else {
            (opticalCenterY - clockHeight / 2f).coerceIn(minTop, maxTop)
        }
        val hairY = if (portraitSlotCount > 0 && !splitRail) {
            hourTop + clockHeight + hairGapAbove
        } else {
            0f
        }
        val creditsTop = if (portraitSlotCount > 0 && !splitRail) {
            hairY + 1f * density + hairGapBelow
        } else {
            0f
        }
        val hourBaseline = hourTop - ink.top
        val minuteBaseline = hourBaseline + lineAdvance

        val slotWidth = widestDigitWidth(timePaint)
        val pairWidth = slotWidth * 2f
        val pairLeft = clockCenterX - pairWidth / 2f
        timePaint.textAlign = Paint.Align.CENTER
        val digitPaint = Paint(timePaint).apply {
            // Same quiet ink for hour and minute; late dissolve still only fades minutes.
            color = Color.argb(if (dissolveMinutes) 76 else 87, 255, 255, 255)
        }
        drawStableDigits(
            canvas = canvas,
            text = hourText,
            slots = 2,
            slotLeft = pairLeft,
            slotWidth = slotWidth,
            baseline = hourBaseline,
            paint = digitPaint
        )
        if (dissolveMinutes) {
            drawDissolvedDigits(
                canvas = canvas,
                text = minuteText,
                slotLeft = pairLeft,
                slotWidth = slotWidth,
                baseline = minuteBaseline,
                ink = ink,
                paint = digitPaint,
                fadeStartRatio = 0.58f
            )
        } else {
            drawStableDigits(
                canvas = canvas,
                text = minuteText,
                slots = 2,
                slotLeft = pairLeft,
                slotWidth = slotWidth,
                baseline = minuteBaseline,
                paint = digitPaint
            )
        }

        val rightEdge = layoutWidth - padding
        if (use12Hour) {
            canvas.drawText(amPmText, rightEdge, amPmBaseline, amPmPaint)
        }
        canvas.drawText(dateText, rightEdge, dateBaseline, datePaint)
        datePaint.textAlign = Paint.Align.CENTER

        if (showWeather && weatherIconRes != null) {
            val tempFm = temperaturePaint.fontMetrics
            val tempHeight = tempFm.descent - tempFm.ascent
            val weatherHeight = max(footerIconSize.toFloat(), tempHeight)
            val dateBottom = dateBaseline + dateFm.descent
            val weatherTop = dateBottom - weatherHeight
            val iconTop = weatherTop + (weatherHeight - footerIconSize) / 2f
            val tempBaseline = iconTop + footerIconSize / 2f -
                (tempFm.ascent + tempFm.descent) / 2f
            drawWeatherIcon(
                canvas = canvas,
                resId = weatherIconRes,
                left = padding,
                top = iconTop,
                size = footerIconSize
            )
            canvas.drawText(
                weatherText,
                padding + footerIconSize + 4f * density,
                tempBaseline,
                temperaturePaint
            )
        }

        if (showStatusSlots) {
            statusNamePaint.color = if (hasWallpaper) {
                Color.parseColor("#B3B3B3")
            } else {
                Color.parseColor("#888888")
            }
            if (splitRail) {
                val railCenterX = layoutWidth - padding - railWidth / 2f
                val slotCount = configuredStatusSlots.size
                val chipHeight = vmin * if (shape == "square") 0.11f else 0.10f
                val railBlockGap = vmin * if (shape == "square") 0.045f else 0.055f
                val columnHeight = chipHeight * slotCount +
                    railBlockGap * (slotCount - 1).coerceAtLeast(0)
                val railTop = (hourTop + groupHeight / 2f - columnHeight / 2f)
                    .coerceAtLeast(areaTop)
                drawStatusSlots(
                    canvas = canvas,
                    slots = configuredStatusSlots,
                    centerX = railCenterX,
                    top = railTop,
                    vmin = vmin,
                    density = density,
                    availableWidth = railWidth,
                    screenCategory = screenCategory,
                    portrait = true
                )
            } else {
                // Short centered hairline, then narrow credits (name ···· value).
                val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(51, 255, 255, 255)
                    strokeWidth = max(1f, density * 0.9f)
                }
                val hairHalf = creditsWidth * 0.14f
                canvas.drawLine(
                    clockCenterX - hairHalf,
                    hairY,
                    clockCenterX + hairHalf,
                    hairY,
                    hairPaint
                )
                drawPortraitCreditsSlots(
                    canvas = canvas,
                    slots = configuredStatusSlots,
                    centerX = clockCenterX,
                    top = creditsTop,
                    vmin = vmin,
                    density = density,
                    creditsWidth = creditsWidth,
                    screenCategory = screenCategory,
                    hasWallpaper = hasWallpaper
                )
            }
        }
    }

    /** Weather icon above temperature, vertically centered to the clock, placed to its right. */
    private fun drawWeatherColumnBesideTime(
        canvas: Canvas,
        weatherIconRes: Int,
        weatherText: String,
        timeCenterX: Float,
        timeWidth: Float,
        timeBaseline: Float,
        timeMetrics: Paint.FontMetrics,
        weatherGap: Float,
        weatherColumnWidth: Float,
        iconSize: Int,
        weatherSpacing: Float,
        verticalNudgeY: Float = 0f
    ) {
        val timeRightEdge = timeCenterX + timeWidth / 2f
        val weatherColumnLeft = timeRightEdge + weatherGap
        val weatherCenterX = weatherColumnLeft + weatherColumnWidth / 2f
        val timeVisualCenterY = timeBaseline + (timeMetrics.ascent + timeMetrics.descent) / 2f
        val weatherTotalHeight = iconSize + weatherSpacing +
            (temperaturePaint.fontMetrics.descent - temperaturePaint.fontMetrics.ascent)
        val weatherTop = timeVisualCenterY - weatherTotalHeight / 2f + verticalNudgeY
        val tempBaseline = weatherTop + iconSize + weatherSpacing - temperaturePaint.fontMetrics.ascent

        drawWeatherIcon(
            canvas = canvas,
            resId = weatherIconRes,
            left = weatherCenterX - iconSize / 2f,
            top = weatherTop,
            size = iconSize
        )
        temperaturePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(weatherText, weatherCenterX, tempBaseline, temperaturePaint)
        temperaturePaint.textAlign = Paint.Align.LEFT
    }

    /**
     * Simple portrait: old one-line clock with the weather column still on the
     * right. A hairline under the time carries date (left) and AM (right).
     * Slots stay the old vertical stack. Clock is centered only when there
     * are no slots; with slots the whole group sits higher and tighter.
     */
    private fun drawSimplePortraitJoinWeather(
        canvas: Canvas,
        calendar: Calendar,
        weather: WeatherData?,
        showWeather: Boolean,
        vmin: Float,
        density: Float,
        layoutWidth: Int,
        layoutHeight: Int,
        availableWidth: Float,
        showStatusSlots: Boolean,
        configuredStatusSlots: List<SimpleClockStatusSlot>,
        screenCategory: String,
        timeScale: Float,
        dateScale: Float,
        tempScale: Float,
        use12Hour: Boolean
    ) {
        val clockText = if (use12Hour) {
            val parts = splitTwelveHourParts(calendar)
            "${parts.hour}:${parts.minute}"
        } else {
            formatSimpleClockTime(calendar, twelveHour = false)
        }
        val layoutRef = if (use12Hour) "12:00" else clockText
        val weatherText = if (showWeather) {
            weather?.let { "${it.temperature}°" } ?: "-"
        } else {
            ""
        }
        val weatherIconRes = if (showWeather) {
            weather?.condition?.let { resolveWeatherIconRes(it) } ?: R.drawable.mdi_weather_sunny
        } else {
            null
        }
        val sideInset = max(14f * density, vmin * 0.045f)
        val maxTimeWidth = (availableWidth - sideInset * 2f) * 0.78f
        var majorSize = vmin * timeScale
        timePaint.textSize = majorSize
        var measuredTime = timePaint.measureText(layoutRef)
        while (majorSize > 64f && measuredTime > maxTimeWidth) {
            majorSize *= 0.98f
            timePaint.textSize = majorSize
            measuredTime = timePaint.measureText(layoutRef)
        }
        val timeWidth = timePaint.measureText(clockText)
        val timeMetrics = timePaint.fontMetrics
        val timeHeight = timeMetrics.descent - timeMetrics.ascent
        val weatherRatio = 0.26f
        val weatherGap = if (showWeather) majorSize * 0.12f else 0f
        val weatherSpacing = majorSize * 0.06f
        val iconSize = if (showWeather) {
            (majorSize * weatherRatio).toInt().coerceIn((18f * density).toInt(), (80f * density).toInt())
        } else {
            0
        }
        temperaturePaint.textSize = majorSize * weatherRatio * 0.9f
        val weatherColumnWidth = if (showWeather) {
            maxOf(iconSize.toFloat(), temperaturePaint.measureText(weatherText))
        } else {
            0f
        }

        val weekdayText = weekdayShort(calendar)
        val monthDayText = formatMastheadMonthDay(calendar)
        val amPmText = if (use12Hour) splitTwelveHourParts(calendar).amPm else ""
        val leftMeta = if (use12Hour) formatMastheadDate(calendar) else weekdayText
        val rightMeta = if (use12Hour) amPmText else monthDayText
        datePaint.textSize = vmin * dateScale
        datePaint.letterSpacing = 0.08f
        val savedDateColor = datePaint.color
        val dateFm = datePaint.fontMetrics
        val dateHeight = dateFm.descent - dateFm.ascent
        val rightPaint = Paint(datePaint).apply {
            color = if (use12Hour) Color.argb(56, 255, 255, 255) else Color.argb(82, 255, 255, 255)
            letterSpacing = if (use12Hour) 0.12f else 0.08f
            textAlign = Paint.Align.RIGHT
        }
        val rightFm = rightPaint.fontMetrics
        val rightHeight = if (rightMeta.isNotEmpty()) rightFm.descent - rightFm.ascent else 0f
        val metaHeight = maxOf(dateHeight, rightHeight)

        val hairGap = vmin * 0.012f
        val cellGap = vmin * 0.008f
        val slotsGap = vmin * 0.092f
        val hairStroke = max(1f, density * 0.9f)
        val mastheadHeight = hairGap + hairStroke + cellGap + metaHeight
        val slotsHeight = if (showStatusSlots) {
            estimatePortraitStatusSlotsHeight(
                slotCount = 3,
                vmin = vmin,
                density = density,
                screenCategory = screenCategory,
                sizeScale = SIMPLE_PORTRAIT_SLOT_SCALE,
                blockGapScale = SIMPLE_PORTRAIT_SLOT_GAP_SCALE
            )
        } else {
            0f
        }

        val verticalOffset = when (screenCategory) {
            "tiny" -> 10f * density
            "small" -> 40f * density
            "medium" -> 45f * density
            "large" -> 50f * density
            "xlarge" -> 55f * density
            "tablet" -> 60f * density
            else -> 45f * density
        }
        val timeCenterY = if (showStatusSlots) {
            val groupHeight = timeHeight + mastheadHeight + slotsGap + slotsHeight
            val minTop = 20f * density
            val maxTop = (layoutHeight - groupHeight - 20f * density).coerceAtLeast(minTop)
            val groupTop = (layoutHeight * 0.28f).coerceIn(minTop, maxTop)
            groupTop + timeHeight / 2f
        } else {
            layoutHeight / 2f - verticalOffset
        }
        val timeBaseline = timeCenterY - (timeMetrics.ascent + timeMetrics.descent) / 2f

        val contentCenterX = layoutWidth / 2f
        val horizontalOffset = if (showWeather) (weatherGap + weatherColumnWidth) / 2f else 0f
        val timeCenterX = contentCenterX - horizontalOffset

        timePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(clockText, timeCenterX, timeBaseline, timePaint)

        if (showWeather && weatherIconRes != null) {
            drawWeatherColumnBesideTime(
                canvas = canvas,
                weatherIconRes = weatherIconRes,
                weatherText = weatherText,
                timeCenterX = timeCenterX,
                timeWidth = timeWidth,
                timeBaseline = timeBaseline,
                timeMetrics = timeMetrics,
                weatherGap = weatherGap,
                weatherColumnWidth = weatherColumnWidth,
                iconSize = iconSize,
                weatherSpacing = weatherSpacing,
                verticalNudgeY = 4f * density
            )
        }

        val lineLeft = timeCenterX - timeWidth / 2f
        val lineRight = if (showWeather) {
            timeCenterX + timeWidth / 2f + weatherGap + weatherColumnWidth
        } else {
            timeCenterX + timeWidth / 2f
        }
        val hairY = timeBaseline + timeMetrics.descent + hairGap
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(36, 255, 255, 255)
            strokeWidth = hairStroke
        }
        canvas.drawLine(lineLeft, hairY, lineRight, hairY, hairPaint)

        val metaBaseline = hairY + cellGap - dateFm.ascent
        datePaint.color = Color.argb(82, 255, 255, 255)
        datePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(leftMeta, lineLeft, metaBaseline, datePaint)
        if (rightMeta.isNotEmpty()) {
            val rightBaseline = hairY + cellGap - rightFm.ascent
            canvas.drawText(rightMeta, lineRight, rightBaseline, rightPaint)
        }

        datePaint.color = savedDateColor
        datePaint.textAlign = Paint.Align.CENTER

        val mastheadBottom = hairY + cellGap + metaHeight
        if (showStatusSlots) {
            drawStatusSlots(
                canvas = canvas,
                slots = configuredStatusSlots,
                centerX = contentCenterX,
                top = mastheadBottom + slotsGap,
                vmin = vmin,
                density = density,
                availableWidth = availableWidth,
                screenCategory = screenCategory,
                portrait = true,
                sizeScale = SIMPLE_PORTRAIT_SLOT_SCALE,
                blockGapScale = SIMPLE_PORTRAIT_SLOT_GAP_SCALE,
                threeSeat = true
            )
        }
    }

    private fun estimatePortraitStatusSlotsHeight(
        slotCount: Int,
        vmin: Float,
        density: Float,
        screenCategory: String,
        sizeScale: Float = 1f,
        blockGapScale: Float = 1f
    ): Float {
        if (slotCount <= 0) return 0f
        val nameScale = when (screenCategory) {
            "tiny" -> 0.055f
            "small" -> 0.050f
            "medium" -> 0.048f
            "large" -> 0.046f
            "xlarge" -> 0.044f
            "tablet" -> 0.042f
            else -> 0.045f
        } * sizeScale
        val nameSize = (vmin * nameScale).coerceIn(13f * density, 44f * density)
        val valueSize = (vmin * nameScale * 1.12f).coerceIn(14f * density, 48f * density)
        val iconSize = (vmin * nameScale * 2.05f)
            .toInt()
            .coerceIn((26f * density).toInt(), (80f * density).toInt())
        val lineGap = vmin * 0.008f
        val blockGap = vmin * 0.045f * blockGapScale
        val textStack = nameSize * 1.15f + lineGap + valueSize * 1.1f
        val rowHeight = maxOf(iconSize.toFloat(), textStack)
        return rowHeight * slotCount + blockGap * (slotCount - 1)
    }

    private fun applyTextShadowIfNeeded(enabled: Boolean, density: Float) {
        if (enabled) {
            val blur = 10f * density
            val offsetY = 3f * density
            timePaint.setShadowLayer(blur, 0f, offsetY, Color.argb(118, 0, 0, 0))
            temperaturePaint.setShadowLayer(blur * 0.45f, 0f, offsetY * 0.55f, Color.argb(96, 0, 0, 0))
            statusNamePaint.setShadowLayer(4f * density, 0f, 1.5f * density, Color.argb(80, 0, 0, 0))
            statusValuePaint.setShadowLayer(5f * density, 0f, 2f * density, Color.argb(96, 0, 0, 0))
        } else {
            timePaint.clearShadowLayer()
            temperaturePaint.clearShadowLayer()
            statusNamePaint.clearShadowLayer()
            statusValuePaint.clearShadowLayer()
        }
    }

    private fun applyDateShadowIfNeeded(enabled: Boolean, density: Float) {
        if (enabled) {
            datePaint.setShadowLayer(6f * density, 0f, 2f * density, Color.argb(96, 0, 0, 0))
        } else {
            datePaint.clearShadowLayer()
        }
    }

    /**
     * Draws up to 3 status chips.
     * Landscape: horizontal row.
     * Portrait: vertical column, left-aligned as a group and centered.
     * Square screens with 2–3 chips use a right-side rail instead of stacking under the clock.
     */
    private fun drawStatusSlots(
        canvas: Canvas,
        slots: List<SimpleClockStatusSlot>,
        centerX: Float,
        top: Float,
        vmin: Float,
        density: Float,
        availableWidth: Float,
        screenCategory: String,
        portrait: Boolean,
        sizeScale: Float = 1f,
        blockGapScale: Float = 1f,
        threeSeat: Boolean = false
    ) {
        val nameScale = when (screenCategory) {
            "tiny" -> 0.055f
            "small" -> 0.050f
            "medium" -> if (portrait) 0.048f else 0.042f
            "large" -> if (portrait) 0.046f else 0.040f
            "xlarge" -> if (portrait) 0.044f else 0.038f
            "tablet" -> if (portrait) 0.042f else 0.036f
            else -> 0.045f
        } * sizeScale
        val valueScale = nameScale * 1.12f
        val iconScale = nameScale * 2.05f

        val nameSize = (vmin * nameScale).coerceIn(13f * density, 44f * density)
        val valueSize = (vmin * valueScale).coerceIn(14f * density, 48f * density)
        val iconSize = (vmin * iconScale).toInt().coerceIn((26f * density).toInt(), (80f * density).toInt())
        val iconTextGap = vmin * 0.02f
        val blockGap = if (portrait) vmin * 0.045f * blockGapScale else vmin * 0.085f
        val lineGap = vmin * 0.008f

        statusNamePaint.textSize = nameSize
        statusValuePaint.textSize = valueSize

        data class MeasuredBlock(
            val name: String,
            val value: String,
            val iconRes: Int,
            val textWidth: Float,
            val width: Float
        )

        val blocks = slots.map { slot ->
            val entityId = slot.entityId.trim()
            val rawState = statusEntityStates[entityId] ?: ""
            val isActive = isStatusEntityActive(rawState)
            // Custom label from settings is shown as-is; auto names (HA paste / friendly_name)
            // truncate English to at most 2 words.
            val name = if (slot.label.isNotBlank()) {
                slot.label.trim()
            } else {
                truncateAutoDisplayName(
                    statusEntityLabels[entityId] ?: guessLabelFromEntityId(entityId)
                )
            }
            val value = formatStatusLabel(rawState, statusEntityUnits[entityId].orEmpty())
            val iconKey = resolveStatusSlotIcon(slot.icon, entityId)
            val iconRes = MdiIconMapper.getIconResIdForState(iconKey, isActive)
            val textWidth = maxOf(
                statusNamePaint.measureText(name),
                statusValuePaint.measureText(value)
            )
            MeasuredBlock(name, value, iconRes, textWidth, iconSize + iconTextGap + textWidth)
        }

        val scale = if (portrait) {
            val maxBlockWidth = blocks.maxOfOrNull { it.width } ?: 0f
            if (maxBlockWidth > availableWidth && maxBlockWidth > 0f) {
                (availableWidth / maxBlockWidth).coerceAtMost(1f)
            } else {
                1f
            }
        } else {
            val totalWidth = blocks.sumOf { it.width.toDouble() }.toFloat() +
                blockGap * (blocks.size - 1).coerceAtLeast(0)
            if (totalWidth > availableWidth && totalWidth > 0f) {
                (availableWidth / totalWidth).coerceAtMost(1f)
            } else {
                1f
            }
        }

        val drawIconSize = (iconSize * scale).toInt().coerceAtLeast(1)
        val drawNameSize = nameSize * scale
        val drawValueSize = valueSize * scale
        val drawIconTextGap = iconTextGap * scale
        val drawLineGap = lineGap * scale
        val drawBlockGap = blockGap * scale

        statusNamePaint.textSize = drawNameSize
        statusValuePaint.textSize = drawValueSize
        val drawNameFm = statusNamePaint.fontMetrics
        val drawValueFm = statusValuePaint.fontMetrics
        val drawNameHeight = drawNameFm.descent - drawNameFm.ascent
        val drawValueHeight = drawValueFm.descent - drawValueFm.ascent
        val drawTextStackHeight = drawNameHeight + drawLineGap + drawValueHeight
        val drawRowHeight = maxOf(drawIconSize.toFloat(), drawTextStackHeight)

        if (portrait) {
            val maxBlockWidth = blocks.maxOfOrNull { block ->
                drawIconSize + drawIconTextGap + block.textWidth * scale
            } ?: 0f
            val columnLeft = centerX - maxBlockWidth / 2f
            val seatIndex = if (threeSeat && blocks.size == 1) 1 else 0
            var y = top + (drawRowHeight + drawBlockGap) * seatIndex
            blocks.forEach { block ->
                val rowCenterY = y + drawRowHeight / 2f
                drawStatusIcon(
                    canvas = canvas,
                    resId = block.iconRes,
                    left = columnLeft,
                    top = rowCenterY - drawIconSize / 2f,
                    size = drawIconSize
                )
                val textLeft = columnLeft + drawIconSize + drawIconTextGap
                val textStackTop = rowCenterY - drawTextStackHeight / 2f
                val nameBaseline = textStackTop - drawNameFm.ascent
                val valueBaseline = nameBaseline + drawNameFm.descent + drawLineGap - drawValueFm.ascent
                canvas.drawText(block.name, textLeft, nameBaseline, statusNamePaint)
                canvas.drawText(block.value, textLeft, valueBaseline, statusValuePaint)
                y += drawRowHeight + drawBlockGap
            }
        } else {
            val totalDrawWidth = blocks.sumOf { block ->
                (drawIconSize + drawIconTextGap + block.textWidth * scale).toDouble()
            }.toFloat() + drawBlockGap * (blocks.size - 1).coerceAtLeast(0)

            var x = centerX - totalDrawWidth / 2f
            val rowCenterY = top + drawRowHeight / 2f

            blocks.forEach { block ->
                val blockTextWidth = block.textWidth * scale
                val blockWidth = drawIconSize + drawIconTextGap + blockTextWidth
                drawStatusIcon(
                    canvas = canvas,
                    resId = block.iconRes,
                    left = x,
                    top = rowCenterY - drawIconSize / 2f,
                    size = drawIconSize
                )
                val textLeft = x + drawIconSize + drawIconTextGap
                val textStackTop = rowCenterY - drawTextStackHeight / 2f
                val nameBaseline = textStackTop - drawNameFm.ascent
                val valueBaseline = nameBaseline + drawNameFm.descent + drawLineGap - drawValueFm.ascent
                canvas.drawText(block.name, textLeft, nameBaseline, statusNamePaint)
                canvas.drawText(block.value, textLeft, valueBaseline, statusValuePaint)
                x += blockWidth + drawBlockGap
            }
        }
    }

    /** Prefer configured icon; if still the default HA logo, infer from entity id. */
    private fun resolveStatusSlotIcon(configuredIcon: String, entityId: String): String {
        val icon = configuredIcon.trim()
        if (icon.isNotEmpty() && icon != "mdi:home-assistant" && icon != "mdi:home") {
            return icon
        }
        val name = entityId.substringAfter('.').lowercase(Locale.ROOT)
        return when {
            name.contains("garage") || name.contains("gate") -> "mdi:garage"
            name.contains("lock") -> "mdi:lock"
            name.contains("door") -> "mdi:door"
            name.contains("window") -> "mdi:window-closed"
            name.contains("curtain") -> "mdi:curtains"
            name.contains("blind") -> "mdi:blinds"
            entityId.startsWith("lock.") -> "mdi:lock"
            entityId.startsWith("cover.") -> "mdi:garage"
            entityId.startsWith("binary_sensor.") -> "mdi:door"
            entityId.startsWith("light.") -> "mdi:lightbulb"
            else -> icon.ifEmpty { "mdi:home-assistant" }
        }
    }

    private fun drawStatusIcon(canvas: Canvas, resId: Int, left: Float, top: Float, size: Int) {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return
        drawable.mutate()
        drawable.setTint(Color.argb(235, 255, 255, 255))
        drawable.setBounds(left.toInt(), top.toInt(), left.toInt() + size, top.toInt() + size)
        drawable.alpha = 235
        drawable.draw(canvas)
    }

    private fun isStatusEntityActive(rawState: String): Boolean {
        val state = rawState.lowercase(Locale.ROOT)
        val activeStates = listOf("on", "true", "playing", "home", "open", "unlocked", "detected", "active")
        val inactiveStates = listOf(
            "off", "false", "paused", "idle", "standby", "unavailable", "unknown",
            "away", "closed", "locked", "not_home"
        )
        return when {
            state in activeStates -> true
            state in inactiveStates -> false
            state.toDoubleOrNull() != null -> state.toDouble() > 0
            else -> false
        }
    }

    private fun formatStatusLabel(rawState: String, unit: String = ""): String {
        if (rawState.isBlank()) return "--"
        return when (rawState.lowercase(Locale.ROOT)) {
            "open" -> "Open"
            "closed" -> "Closed"
            "locked" -> "Locked"
            "unlocked" -> "Unlocked"
            "on" -> "On"
            "off" -> "Off"
            "home" -> "Home"
            "not_home", "away" -> "Away"
            "unavailable", "unknown" -> "--"
            "detected" -> "Detected"
            "clear" -> "Clear"
            "active" -> "Active"
            "idle" -> "Idle"
            else -> {
                val number = rawState.toDoubleOrNull()
                if (number != null) {
                    val formatted = formatSensorNumber(rawState)
                    val u = unit.trim()
                    if (u.isEmpty()) return formatted
                    // Keep °C / °F / % tight; other units get a space.
                    return if (u.startsWith("°") || u == "%") "$formatted$u" else "$formatted $u"
                }
                rawState
                    .replace('_', ' ')
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() }
                    }
            }
        }
    }

    private fun formatSensorNumber(raw: String): String {
        val value = raw.toDoubleOrNull() ?: return raw
        val dotIdx = raw.indexOf('.')
        if (dotIdx < 0) return raw
        val decimals = raw.length - dotIdx - 1
        return if (decimals <= 1) raw else String.format(Locale.US, "%.1f", value)
    }

    private fun guessLabelFromEntityId(entityId: String): String {
        val raw = entityId.substringAfter('.', entityId)
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() }
            }
        return raw.ifBlank { entityId }
    }

    /** Auto names only: English ≤ 2 words; Chinese keeps a short char budget. */
    private fun truncateAutoDisplayName(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val hasChinese = trimmed.any { it.code in 0x4E00..0x9FFF }
        if (hasChinese) {
            return if (trimmed.length > 7) trimmed.take(7) else trimmed
        }
        return trimmed
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(" ")
    }

    private fun drawWeatherIcon(canvas: Canvas, resId: Int, left: Float, top: Float, size: Int) {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return
        drawable.setBounds(left.toInt(), top.toInt(), left.toInt() + size, top.toInt() + size)
        drawable.alpha = 235
        drawable.draw(canvas)
    }

    private fun resolveWeatherIconRes(condition: String): Int {
        return when (condition) {
            "sunny" -> R.drawable.mdi_weather_sunny
            "clear_night" -> R.drawable.mdi_weather_night
            "cloudy" -> R.drawable.mdi_weather_cloudy
            "partly_cloudy" -> R.drawable.mdi_weather_partly_cloudy
            "rainy", "light_rain" -> R.drawable.mdi_weather_rainy
            "heavy_rain" -> R.drawable.mdi_weather_pouring
            "light_snow" -> R.drawable.mdi_weather_snowy
            "heavy_snow" -> R.drawable.mdi_weather_snowy_heavy
            "fog" -> R.drawable.mdi_weather_fog
            "haze" -> R.drawable.mdi_weather_hazy
            "wind" -> R.drawable.mdi_weather_windy
            "sandstorm" -> R.drawable.mdi_weather_dust
            else -> R.drawable.mdi_weather_sunny
        }
    }

    private fun formatSimpleClockTime(calendar: Calendar, twelveHour: Boolean): String {
        if (!twelveHour) {
            return String.format(
                "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
            )
        }
        val hour = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val amPm = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return String.format("%02d:%02d %s", hour, calendar.get(Calendar.MINUTE), amPm)
    }

    /**
     * Landscape 12h prefix: stacked AM/PM left of the hour block.
     * Optical vertical center in digit ink, quieter metadata voice, hairline to lift it off the digits.
     */
    private fun drawLandscapeTwelveHourAmPm(
        canvas: Canvas,
        amPm: String,
        firstDigitLeft: Float,
        timeBaseline: Float,
        timeSize: Float,
        ink: DigitInk
    ) {
        val density = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Slightly bolder stroke, a touch quieter (~21% white).
            color = Color.argb(54, 255, 255, 255)
            textSize = timeSize * 0.155f
            textAlign = Paint.Align.CENTER
            typeface = timePaint.typeface
            isFakeBoldText = true
        }
        val letterW = max(
            paint.measureText(amPm.take(1)),
            paint.measureText(amPm.takeLast(1))
        )
        val fm = paint.fontMetrics
        val letterHeight = fm.descent - fm.ascent
        // Slightly open stack so P/M breathe (was ~0.82 × size, cramped).
        val lineStep = paint.textSize * 1.08f
        val stackHeight = letterHeight + lineStep

        val digitTop = timeBaseline + ink.top
        val digitCenterY = digitTop + ink.height / 2f
        // Optical lift (~2%) so the stack doesn't read heavy.
        val stackCenterY = digitCenterY - ink.height * 0.02f
        val firstBaseline = stackCenterY - stackHeight / 2f - fm.ascent
        val secondBaseline = firstBaseline + lineStep

        // More air between prefix and hour digits.
        val gap = max(8f * density, timeSize * 0.055f)
        val ruleGap = max(5f * density, timeSize * 0.028f)
        val x = firstDigitLeft - gap - letterW / 2f

        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(31, 255, 255, 255)
            strokeWidth = max(1f, density * 0.85f)
        }
        val ruleX = firstDigitLeft - ruleGap
        val ruleInset = ink.height * 0.22f
        canvas.drawLine(
            ruleX,
            digitTop + ruleInset,
            ruleX,
            digitTop + ink.height - ruleInset,
            hairPaint
        )

        canvas.drawText(amPm.take(1), x, firstBaseline, paint)
        canvas.drawText(amPm.takeLast(1), x, secondBaseline, paint)
    }

    /** Minutes dissolve into black. [fadeStartRatio] 0.58 = B5 late fade (room for credits). */
    private fun drawDissolvedDigits(
        canvas: Canvas,
        text: String,
        slotLeft: Float,
        slotWidth: Float,
        baseline: Float,
        ink: DigitInk,
        paint: Paint,
        fadeStartRatio: Float = 0.42f
    ) {
        val top = baseline + ink.top
        val bottom = top + ink.height
        val left = slotLeft - slotWidth * 0.08f
        val right = slotLeft + slotWidth * 2f + slotWidth * 0.08f
        val save = canvas.saveLayer(left, top, right, bottom, null)
        drawStableDigits(
            canvas = canvas,
            text = text,
            slots = 2,
            slotLeft = slotLeft,
            slotWidth = slotWidth,
            baseline = baseline,
            paint = paint
        )
        val fadeStart = top + ink.height * fadeStartRatio.coerceIn(0.2f, 0.85f)
        val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                fadeStart,
                0f,
                bottom,
                Color.WHITE,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(left, top, right, bottom, fade)
        canvas.restoreToCount(save)
    }

    private data class DigitInk(val top: Float, val height: Float)

    private fun digitInkMetrics(paint: Paint): DigitInk {
        val bounds = Rect()
        var inkTop = 0
        var inkBottom = 0
        var first = true
        for (digit in '0'..'9') {
            paint.getTextBounds(digit.toString(), 0, 1, bounds)
            if (first) {
                inkTop = bounds.top
                inkBottom = bounds.bottom
                first = false
            } else {
                inkTop = minOf(inkTop, bounds.top)
                inkBottom = maxOf(inkBottom, bounds.bottom)
            }
        }
        return DigitInk(top = inkTop.toFloat(), height = (inkBottom - inkTop).toFloat())
    }

    /**
     * B5 portrait credits: narrow centered column, name left ···· value right.
     * No icons — quiet book-copyright rhythm under the dissolved minutes.
     */
    private fun drawPortraitCreditsSlots(
        canvas: Canvas,
        slots: List<SimpleClockStatusSlot>,
        centerX: Float,
        top: Float,
        vmin: Float,
        density: Float,
        creditsWidth: Float,
        screenCategory: String,
        hasWallpaper: Boolean
    ) {
        if (slots.isEmpty()) return
        val nameScale = when (screenCategory) {
            "tiny" -> 0.037f
            "small" -> 0.035f
            "medium" -> 0.033f
            "large" -> 0.032f
            "xlarge" -> 0.030f
            "tablet" -> 0.029f
            else -> 0.033f
        }
        val nameSize = (vmin * nameScale).coerceIn(11f * density, 18f * density)
        val valueSize = (vmin * nameScale * 1.35f).coerceIn(13f * density, 22f * density)
        val rowGap = vmin * 0.032f

        val prevNameAlign = statusNamePaint.textAlign
        val prevValueAlign = statusValuePaint.textAlign
        val prevNameSpacing = statusNamePaint.letterSpacing
        val prevValueSpacing = statusValuePaint.letterSpacing

        statusNamePaint.textSize = nameSize
        statusNamePaint.textAlign = Paint.Align.LEFT
        statusNamePaint.letterSpacing = 0.12f
        statusNamePaint.color = if (hasWallpaper) {
            Color.argb(90, 255, 255, 255)
        } else {
            Color.argb(72, 255, 255, 255)
        }
        statusValuePaint.textSize = valueSize
        statusValuePaint.textAlign = Paint.Align.RIGHT
        statusValuePaint.letterSpacing = 0.04f
        statusValuePaint.color = if (hasWallpaper) {
            Color.argb(168, 255, 255, 255)
        } else {
            Color.argb(148, 255, 255, 255)
        }

        val nameFm = statusNamePaint.fontMetrics
        val valueFm = statusValuePaint.fontMetrics
        val rowHeight = max(
            nameFm.descent - nameFm.ascent,
            valueFm.descent - valueFm.ascent
        )
        val columnLeft = centerX - creditsWidth / 2f
        val columnRight = centerX + creditsWidth / 2f
        val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(28, 255, 255, 255)
            strokeWidth = max(1f, density * 0.85f)
            pathEffect = DashPathEffect(
                floatArrayOf(1.6f * density, 3.2f * density),
                0f
            )
        }
        val leaderPad = 6f * density

        var y = top
        slots.forEach { slot ->
            val entityId = slot.entityId.trim()
            val rawState = statusEntityStates[entityId] ?: ""
            val name = if (slot.label.isNotBlank()) {
                slot.label.trim()
            } else {
                truncateAutoDisplayName(
                    statusEntityLabels[entityId] ?: guessLabelFromEntityId(entityId)
                )
            }.uppercase(Locale.ROOT)
            val value = formatStatusLabel(rawState, statusEntityUnits[entityId].orEmpty())
            val baseline = y - nameFm.ascent
            val nameW = statusNamePaint.measureText(name)
            val valueW = statusValuePaint.measureText(value)
            val leaderStart = columnLeft + nameW + leaderPad
            val leaderEnd = columnRight - valueW - leaderPad
            if (leaderEnd > leaderStart + 4f * density) {
                val leaderY = baseline + (nameFm.ascent + nameFm.descent) * 0.35f
                canvas.drawLine(leaderStart, leaderY, leaderEnd, leaderY, leaderPaint)
            }
            canvas.drawText(name, columnLeft, baseline, statusNamePaint)
            canvas.drawText(value, columnRight, baseline, statusValuePaint)
            y += rowHeight + rowGap
        }

        statusNamePaint.textAlign = prevNameAlign
        statusValuePaint.textAlign = prevValueAlign
        statusNamePaint.letterSpacing = prevNameSpacing
        statusValuePaint.letterSpacing = prevValueSpacing
    }

    private fun portraitCreditsColumnHeight(
        slotCount: Int,
        vmin: Float,
        density: Float,
        screenCategory: String
    ): Float {
        if (slotCount <= 0) return 0f
        val nameScale = when (screenCategory) {
            "tiny" -> 0.037f
            "small" -> 0.035f
            "medium" -> 0.033f
            "large" -> 0.032f
            "xlarge" -> 0.030f
            "tablet" -> 0.029f
            else -> 0.033f
        }
        val nameSize = (vmin * nameScale).coerceIn(11f * density, 18f * density)
        val valueSize = (vmin * nameScale * 1.35f).coerceIn(13f * density, 22f * density)
        val rowGap = vmin * 0.032f
        statusNamePaint.textSize = nameSize
        statusValuePaint.textSize = valueSize
        val nameFm = statusNamePaint.fontMetrics
        val valueFm = statusValuePaint.fontMetrics
        val rowHeight = max(
            nameFm.descent - nameFm.ascent,
            valueFm.descent - valueFm.ascent
        )
        return rowHeight * slotCount + rowGap * (slotCount - 1).coerceAtLeast(0)
    }

    private fun widestDigitWidth(paint: Paint): Float {
        var widest = 0f
        for (digit in '0'..'9') {
            widest = max(widest, paint.measureText(digit.toString()))
        }
        return widest
    }

    private fun drawStableDigits(
        canvas: Canvas,
        text: String,
        slots: Int,
        slotLeft: Float,
        slotWidth: Float,
        baseline: Float,
        paint: Paint
    ) {
        val previousAlign = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        val offset = (slots - text.length).coerceAtLeast(0)
        for (index in text.indices) {
            val centerX = slotLeft + (offset + index) * slotWidth + slotWidth / 2f
            canvas.drawText(text[index].toString(), centerX, baseline, paint)
        }
        paint.textAlign = previousAlign
    }

    private data class TwelveHourParts(val hour: String, val minute: String, val amPm: String)

    private fun splitTwelveHourParts(calendar: Calendar): TwelveHourParts {
        val hour = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val minute = String.format("%02d", calendar.get(Calendar.MINUTE))
        val amPm = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return TwelveHourParts(String.format("%02d", hour), minute, amPm)
    }

    private fun formatDate(calendar: Calendar): String {
        val weekday = weekdayShort(calendar)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "$weekday  |  $month/$day"
    }

    private fun formatMastheadDate(calendar: Calendar): String {
        return "${weekdayShort(calendar)}  ${formatMastheadMonthDay(calendar)}"
    }

    private fun formatMastheadMonthDay(calendar: Calendar): String {
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "$month/$day"
    }

    private fun weekdayShort(calendar: Calendar): String = when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "MON"
        Calendar.TUESDAY -> "TUE"
        Calendar.WEDNESDAY -> "WED"
        Calendar.THURSDAY -> "THU"
        Calendar.FRIDAY -> "FRI"
        Calendar.SATURDAY -> "SAT"
        Calendar.SUNDAY -> "SUN"
        else -> ""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wallpaperScope.cancel()
    }
}
