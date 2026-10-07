package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.homeassistant.HaManager
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySlot
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.utils.TouchSoundHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

class QuickEntityOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayHost: FrameLayout? = null
    private var overlayView: QuickEntityOverlayView? = null
    private var smartAodOverlay: SmartAodMaskOverlay? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isEnabled = false
    private var isVisible = false
    private var hostHideGeneration = 0
    private var smartAodEnabled = false
    private var smartAodTimeoutSeconds = 60
    private var smartAodMaskPercent = 100
    private var smartAodCovering = false
    private var layoutLocked = false
    private var restackedAtGeneration = -1L
    private val enterSmartAodRunnable = Runnable { enterSmartAodMask() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.QUICK_ENTITY,
            showing = { isOverlayShowing() },
            host = { overlayHost },
            apply = { frame -> applyLayerSplit(frame) },
        )
        serviceScope.launch {
            quickEntitySettingsStore.data.collectLatest { settings ->
                val aodWasEnabled = smartAodEnabled
                smartAodEnabled = settings.smartAodEnabled
                smartAodTimeoutSeconds = settings.smartAodTimeoutSeconds.coerceIn(10, 3600)
                smartAodMaskPercent = settings.smartAodMaskPercent.coerceIn(
                    SmartAodMaskOverlay.MIN_PERCENT,
                    SmartAodMaskOverlay.MAX_PERCENT
                )
                layoutLocked = settings.layoutLocked
                overlayView?.setLayoutLocked(layoutLocked)
                DashboardOverlayChrome.syncQuickEntityLock(layoutLocked)
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

    private fun ensureViewCreated() {
        if (overlayHost == null) {
            createOverlayView()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val realMetrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager?.defaultDisplay?.getRealMetrics(realMetrics)
        } else {
            windowManager?.defaultDisplay?.getMetrics(realMetrics)
        }
        val realWidth = realMetrics.widthPixels
        val realHeight = realMetrics.heightPixels

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
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

        val panel = QuickEntityOverlayView(this, realWidth, realHeight).apply {
            onEntityClick = { slot -> handleEntityClick(slot) }
            setOnSlotsReordered { newSlots -> handleSlotsReordered(newSlots) }
            setOnCameraPanChanged { entityId, panX, panY, zoom ->
                handleCameraView(entityId, panX, panY, zoom)
            }
            setLayoutLocked(layoutLocked)
        }
        overlayView = panel

        val aod = SmartAodMaskOverlay(this)
        smartAodOverlay = aod
        aod.view.setOnTouchListener { _, event ->
            when {
                // Light mask keeps tiles legible: taps act straight through the cover.
                // The panel's own ACTION_DOWN wakes the AOD.
                smartAodMaskPercent <= QuickEntitySettings.SMART_AOD_TAP_THROUGH_MAX_PERCENT -> false
                event.actionMasked == MotionEvent.ACTION_DOWN -> {
                    if (smartAodCovering) {
                        interruptSmartAod()
                        true
                    } else {
                        // Cover already fading out — a quick second tap must reach the tiles.
                        false
                    }
                }
                else -> smartAodCovering
            }
        }

        val host = FrameLayout(this).apply {
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            aod.attachTo(this)
            DashboardOverlayChrome.attach(this, DashboardOverlayChrome.Kind.QUICK_ENTITY)
            visibility = View.GONE
        }
        overlayHost = host

        try {
            windowManager?.addView(host, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun visibleHost(): View? = overlayHost

    /**
     * MJPEG may run only while the tiles are actually on screen. GONE host,
     * Smart AOD cover, and screensaver all keep the last URL and drop the socket.
     */
    private fun isCameraSurfaceLive(): Boolean {
        if (!isEnabled || !isVisible) return false
        if (overlayHost?.visibility != View.VISIBLE) return false
        if (smartAodCovering) return false
        if (ScreensaverController.isScreensaverVisible()) return false
        return true
    }

    fun pauseCamera(dropFrames: Boolean = true) {
        overlayView?.pauseCamera(dropFrames)
    }

    fun resumeCamera() {
        if (!isCameraSurfaceLive()) return
        overlayView?.resumeCamera()
    }

    private fun animateHostIn() {
        visibleHost()?.let { v ->
            hostHideGeneration++
            v.animate().cancel()
            v.animate().setListener(null)
            v.alpha = 0f
            v.visibility = View.VISIBLE
            OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.QUICK_ENTITY) {
                if (v.visibility != View.VISIBLE) return@fadeWhenPaneReady
                v.animate().alpha(1f).setDuration(230)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { scheduleSmartAodEnter() }
                    .start()
            }
        }
        resumeCamera()
    }

    private fun animateHostOut(endAction: (() -> Unit)? = null) {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        pauseCamera(dropFrames = true)
        clearSmartAod(animated = false)
        val host = visibleHost()
        if (host == null) {
            endAction?.invoke()
            return
        }
        val gen = ++hostHideGeneration
        host.animate().cancel()
        host.animate().setListener(null)
        host.animate().alpha(0f).setDuration(230)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                    if (gen == hostHideGeneration && !isVisible) {
                        host.visibility = View.GONE
                        host.alpha = 1f
                        host.animate().setListener(null)
                        endAction?.invoke()
                    }
                }
                override fun onAnimationEnd(animation: Animator) {
                    host.animate().setListener(null)
                    if (canceled || gen != hostHideGeneration) return
                    host.visibility = View.GONE
                    host.alpha = 1f
                    endAction?.invoke()
                }
            })
            .start()
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
        DashboardOverlayChrome.hideIfTop(DashboardOverlayChrome.Kind.QUICK_ENTITY)
        pauseCamera(dropFrames = false)
        mask.animate()
            .alpha(smartAodMaskTargetAlpha())
            .setDuration(SMART_AOD_FADE_MS)
            .withEndAction(null)
            .start()
    }

    /**
     * Fade AOD cover out (if covering) and restart idle timer. Above the tap-through
     * depth the first touch only wakes; at or below it taps pass through to the tiles.
     */
    fun interruptSmartAod() {
        if (!smartAodEnabled || !isEnabled || !isVisible) {
            handler.removeCallbacks(enterSmartAodRunnable)
            return
        }
        val mask = smartAodOverlay?.view
        handler.removeCallbacks(enterSmartAodRunnable)
        if (mask == null) {
            smartAodCovering = false
            OverlayZOrderCoordinator.syncFabForAod()
            resumeCamera()
            scheduleSmartAodEnter()
            return
        }
        mask.animate().cancel()
        if (mask.visibility == View.VISIBLE && mask.alpha > 0.01f) {
            smartAodCovering = false
            OverlayZOrderCoordinator.syncFabForAod()
            resumeCamera()
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
            resumeCamera()
            scheduleSmartAodEnter()
        }
    }

    private fun clearSmartAod(animated: Boolean) {
        handler.removeCallbacks(enterSmartAodRunnable)
        val wasCovering = smartAodCovering
        smartAodCovering = false
        OverlayZOrderCoordinator.syncFabForAod()
        val mask = smartAodOverlay?.view
        if (mask == null) {
            if (wasCovering) resumeCamera()
            return
        }
        mask.animate().cancel()
        if (animated && mask.visibility == View.VISIBLE && mask.alpha > 0.01f) {
            mask.animate()
                .alpha(0f)
                .setDuration(SMART_AOD_FADE_MS)
                .withEndAction {
                    mask.visibility = View.GONE
                    mask.alpha = 0f
                    if (wasCovering) resumeCamera()
                }
                .start()
        } else {
            mask.visibility = View.GONE
            mask.alpha = 0f
            if (wasCovering) resumeCamera()
        }
    }

    private fun handleEntityClick(slot: QuickEntitySlot) {
        if (slot.entityId.isEmpty()) {
            Log.d(TAG, "Entity click ignored: empty entityId")
            return
        }

        Log.d(TAG, "Entity clicked: ${slot.entityId}")
        
        serviceScope.launch {
            try {
                val service = VoiceSatelliteService.getInstance()
                if (service == null) {
                    Log.e(TAG, "VoiceSatelliteService is null!")
                    return@launch
                }

                // For timer entities, toggle between pause and start based on current state
                val serviceName = when {
                    slot.entityId.startsWith("switch.") -> "switch.toggle"
                    slot.entityId.startsWith("light.") -> "light.toggle"
                    slot.entityId.startsWith("fan.") -> "fan.toggle"
                    slot.entityId.startsWith("cover.") -> "cover.toggle"
                    slot.entityId.startsWith("input_boolean.") -> "input_boolean.toggle"
                    slot.entityId.startsWith("automation.") -> "automation.toggle"
                    slot.entityId.startsWith("button.") -> "button.press"
                    slot.entityId.startsWith("script.") -> "script.turn_on"
                    slot.entityId.startsWith("scene.") -> "scene.turn_on"
                    slot.entityId.startsWith("timer.") -> {
                        val currentState = overlayView?.getTimerState(slot.entityId) ?: "idle"
                        val isTimerRinging = VoiceSatelliteService.getInstance()?.isTimerRinging() == true
                        if (isTimerRinging) {
                            VoiceSatelliteService.getInstance()?.stopTimerSound()
                            overlayView?.clearTimerRinging(slot.entityId)
                            return@launch
                        }
                        when (currentState) {
                            "active" -> "timer.pause"
                            "paused" -> "timer.start"
                            else -> "timer.start"
                        }
                    }
                    else -> "homeassistant.toggle"
                }
                Log.d(TAG, "Calling HA service: $serviceName for ${slot.entityId}")
                service.callHaService(serviceName, slot.entityId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call HA service", e)
            }
        }
    }

    private fun toggleVisibility() {
        if (!isEnabled) return

        isVisible = !isVisible
        OverlayLayerSplit.sync()
        if (isVisible) {
            animateHostIn()
            bindDashboardChrome(revealOnShow = true)
        } else {
            animateHostOut()
            unbindDashboardChrome()
        }

        serviceScope.launch {
            quickEntitySettingsStore.updateData {
                it.copy(enableQuickEntityDisplay = isVisible)
            }
        }
    }

    private fun bindDashboardChrome(revealOnShow: Boolean = false) {
        DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.QUICK_ENTITY)
        DashboardOverlayChrome.syncQuickEntityLock(layoutLocked)
        if (revealOnShow) {
            DashboardOverlayChrome.revealOnShow(DashboardOverlayChrome.Kind.QUICK_ENTITY)
            OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
        }
    }

    fun setLayoutLocked(locked: Boolean) {
        if (layoutLocked == locked) {
            overlayView?.setLayoutLocked(locked)
            DashboardOverlayChrome.syncQuickEntityLock(locked)
            return
        }
        layoutLocked = locked
        overlayView?.setLayoutLocked(locked)
        DashboardOverlayChrome.syncQuickEntityLock(locked)
        serviceScope.launch {
            quickEntitySettingsStore.updateData { it.copy(layoutLocked = locked) }
        }
    }

    private fun unbindDashboardChrome() {
        DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.QUICK_ENTITY)
    }

    private fun handleSlotsReordered(newSlots: List<QuickEntitySlot>) {
        if (layoutLocked) return
        persistSlots(newSlots)
    }

    private fun handleCameraView(entityId: String, panX: Float, panY: Float, zoom: Float) {
        serviceScope.launch {
            quickEntitySettingsStore.updateData { settings ->
                settings.copy(
                    slots = settings.slots.map { slot ->
                        if (slot.entityId == entityId) {
                            slot.copy(
                                cameraPanX = panX,
                                cameraPanY = panY,
                                cameraZoom = zoom
                            )
                        } else {
                            slot
                        }
                    }
                )
            }
        }
    }

    private fun persistSlots(newSlots: List<QuickEntitySlot>) {
        serviceScope.launch {
            quickEntitySettingsStore.updateData { settings ->
                val fullSlots = newSlots.toMutableList()
                while (fullSlots.size < 6) {
                    fullSlots.add(QuickEntitySlot())
                }
                settings.copy(slots = fullSlots)
            }
        }
    }

    fun updateEntityState(entityId: String, state: String) {
        overlayView?.updateEntityState(entityId, state)
    }

    fun updateEntityUnit(entityId: String, unit: String) {
        overlayView?.updateEntityUnit(entityId, unit)
    }
    
    fun updateTimerRemaining(entityId: String, remaining: String) {
        overlayView?.updateTimerRemaining(entityId, remaining)
    }
    
    fun updateTimerFinishesAt(entityId: String, finishesAt: String) {
        overlayView?.updateTimerFinishesAt(entityId, finishesAt)
    }

    fun updateEntityLabel(entityId: String, label: String) {
        overlayView?.updateEntityLabel(entityId, label)
    }

    fun updateEntityPicture(
        entityId: String,
        pictureUrl: String,
        haRemoteUrl: String? = null,
        forceRestart: Boolean = false,
    ): Boolean {
        if (overlayView == null) {
            if (!isEnabled || !isVisible) {
                return false
            }
            // Hidden host is GONE — do not build a view just to hold a URL.
            if (overlayHost?.visibility != View.VISIBLE) {
                return false
            }
            ensureViewCreated()
        }
        val view = overlayView
        if (view == null) {
            Log.w(TAG, "display overlay null entity=$entityId")
            return false
        }
        view.updateEntityPicture(entityId, pictureUrl, haRemoteUrl, forceRestart)
        return true
    }

    fun clearEntityPicture(entityId: String) {
        overlayView?.clearEntityPicture(entityId)
    }

    fun activeCameraEntityIds(): Set<String> =
        overlayView?.activeCameraEntityIds().orEmpty()

    fun updateSlots(slots: List<QuickEntitySlot>) {
        overlayView?.updateSlots(slots)
    }
    
    fun reloadSlots() {
        serviceScope.launch {
            val settings = quickEntitySettingsStore.data.first()
            val hasAnyEntity = settings.slots.any { it.entityId.isNotEmpty() }
            
            if (!hasAnyEntity) {
                if (isVisible) {
                    isVisible = false
                    isEnabled = false
                    animateHostOut()
                    unbindDashboardChrome()
                    OverlayLayerSplit.sync()
                    quickEntitySettingsStore.updateData {
                        it.copy(enableQuickEntityDisplay = false)
                    }
                }
                return@launch
            }

            // Hidden wait must stay hidden. HA slot text must not force SHOW + MJPEG.
            overlayView?.updateSlots(settings.slots)
            VoiceSatelliteService.getInstance()?.let { service ->
                service.getQuickEntityStates().forEach { (entityId, state) ->
                    overlayView?.updateEntityState(entityId, state)
                }
                service.getQuickEntityUnits().forEach { (entityId, unit) ->
                    overlayView?.updateEntityUnit(entityId, unit)
                }
                // Get timer remaining from attributes
                service.getQuickEntityAttributes().forEach { (entityId, attrs) ->
                    if (entityId.startsWith("timer.")) {
                        attrs["remaining"]?.let { remaining ->
                            overlayView?.updateTimerRemaining(entityId, remaining)
                        }
                        attrs["finishes_at"]?.let { finishesAt ->
                            overlayView?.updateTimerFinishesAt(entityId, finishesAt)
                        }
                    }
                }
                restoreQuickEntityCameraPictures(service)
                service.resubscribeQuickEntities()
                forceRefreshTimerTiles(settings.slots)
            }
        }
    }

    /**
     * Fallback forced read: with the direct HA WebSocket signed in, re-read timer tiles
     * from `get_states`. The cached pushes replayed on show/reload are only as
     * fresh as their last delivery, and HA never ticks a timer's `remaining`
     * attribute between transitions — the snapshot is always the current truth.
     */
    private suspend fun forceRefreshTimerTiles(slots: List<QuickEntitySlot>) {
        val timerIds = slots.mapNotNullTo(mutableSetOf()) { slot ->
            slot.entityId.takeIf { it.startsWith("timer.") }
        }
        if (timerIds.isEmpty()) return
        val snapshots = HaManager.get()?.fetchTimerStates(timerIds).orEmpty()
        snapshots.forEach { snap ->
            overlayView?.applyTimerSnapshot(snap.entityId, snap.state, snap.remaining, snap.finishesAt)
        }
    }

    private fun restoreQuickEntityCameraPictures(service: VoiceSatelliteService) {
        service.rebindQuickEntityCameras()
    }

    /** Service already on screen: poke HA tiles only. Do not restack the mic. */
    private fun notifyHaWhileAlreadyRunning() {
        serviceScope.launch {
            val settings = quickEntitySettingsStore.data.first()
            overlayView?.updateSlots(settings.slots)
            VoiceSatelliteService.getInstance()?.let { service ->
                service.getQuickEntityStates().forEach { (entityId, state) ->
                    overlayView?.updateEntityState(entityId, state)
                }
                service.getQuickEntityUnits().forEach { (entityId, unit) ->
                    overlayView?.updateEntityUnit(entityId, unit)
                }
                service.getQuickEntityAttributes().forEach { (entityId, attrs) ->
                    if (entityId.startsWith("timer.")) {
                        attrs["remaining"]?.let { remaining ->
                            overlayView?.updateTimerRemaining(entityId, remaining)
                        }
                        attrs["finishes_at"]?.let { finishesAt ->
                            overlayView?.updateTimerFinishesAt(entityId, finishesAt)
                        }
                    }
                }
                restoreQuickEntityCameraPictures(service)
                service.resubscribeQuickEntities()
                forceRefreshTimerTiles(settings.slots)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                ensureViewCreated()
                val alreadyShown = isEnabled && isVisible &&
                    overlayHost?.visibility == View.VISIBLE
                isEnabled = true
                isVisible = true
                OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.QUICK_ENTITY)
                if (!alreadyShown) {
                    OverlayLayerSplit.sync()
                    bringToFront()
                    animateHostIn()
                    OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
                } else {
                    resumeCamera()
                }
                bindDashboardChrome(revealOnShow = !alreadyShown)
                serviceScope.launch {
                    val settings = quickEntitySettingsStore.data.first()
                    overlayView?.updateSlots(settings.slots)

                    VoiceSatelliteService.getInstance()?.let { service ->
                        service.getQuickEntityStates().forEach { (entityId, state) ->
                            overlayView?.updateEntityState(entityId, state)
                        }
                        service.getQuickEntityUnits().forEach { (entityId, unit) ->
                            overlayView?.updateEntityUnit(entityId, unit)
                        }
                        // Get timer remaining from attributes
                        service.getQuickEntityAttributes().forEach { (entityId, attrs) ->
                            if (entityId.startsWith("timer.")) {
                                attrs["remaining"]?.let { remaining ->
                                    overlayView?.updateTimerRemaining(entityId, remaining)
                                }
                                attrs["finishes_at"]?.let { finishesAt ->
                                    overlayView?.updateTimerFinishesAt(entityId, finishesAt)
                                }
                            }
                        }
                        restoreQuickEntityCameraPictures(service)
                        service.resubscribeQuickEntities()
                        forceRefreshTimerTiles(settings.slots)
                    }
                }
            }
            ACTION_HIDE -> {
                isEnabled = false
                isVisible = false
                OverlayLayerSplit.sync()
                animateHostOut()
                unbindDashboardChrome()
            }
            ACTION_TOGGLE -> {
                if (isEnabled) {
                    toggleVisibility()
                }
            }
            ACTION_UPDATE_SLOTS -> {
                serviceScope.launch {
                    val settings = quickEntitySettingsStore.data.first()
                    overlayView?.updateSlots(settings.slots)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun bringToFront() {
        if (!isEnabled || !isVisible) return
        OverlayLayerSplit.sync()
        if (OverlayLayerSplit.isPaneView(overlayHost)) {
            OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
            return
        }
        if (!smartAodCovering) {
            overlayHost?.let { view ->
                windowParams?.let { params ->
                    if (view.isAttachedToWindow &&
                        restackedAtGeneration == OverlayZOrderCoordinator.stackGeneration
                    ) {
                        return
                    }
                    if (OverlayZOrderCoordinator.bringToFront(windowManager, view, params, TAG)) {
                        restackedAtGeneration = OverlayZOrderCoordinator.stackGeneration
                    }
                }
            }
        }
        OverlayZOrderCoordinator.raiseVinylFabAbovePassiveDashboard()
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = overlayHost ?: return
        if (frame == null && !isVisible && host.visibility == View.VISIBLE) return
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.QUICK_ENTITY,
            windowManager,
            host,
            windowParams,
            frame,
        )
    }

    override fun onDestroy() {
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.QUICK_ENTITY)
        super.onDestroy()
        unbindDashboardChrome()
        handler.removeCallbacksAndMessages(null)
        clearSmartAod(animated = false)
        smartAodOverlay?.detach()
        smartAodOverlay = null
        serviceScope.cancel()
        overlayView?.releaseRenderer()
        try {
            overlayHost?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view", e)
        }
        overlayHost = null
        overlayView = null
        instance = null
    }

    companion object {
        private const val TAG = "QuickEntityOverlay"
        private const val SMART_AOD_FADE_MS = 380L
        private var instance: QuickEntityOverlayService? = null

        const val ACTION_SHOW = "com.example.ava.SHOW_QUICK_ENTITY"
        const val ACTION_HIDE = "com.example.ava.HIDE_QUICK_ENTITY"
        const val ACTION_TOGGLE = "com.example.ava.TOGGLE_QUICK_ENTITY"
        const val ACTION_UPDATE_SLOTS = "com.example.ava.UPDATE_QUICK_ENTITY_SLOTS"

        fun getInstance() = instance

        fun isOverlayShowing(): Boolean = instance?.let { it.isEnabled && it.isVisible } == true

        fun notifySmartAodInterrupt() {
            Handler(Looper.getMainLooper()).post {
                instance?.interruptSmartAod()
            }
        }

        /** Timer ring stopped somewhere other than a tile tap — clear ringing tiles. */
        fun notifyTimerRingStopped() {
            Handler(Looper.getMainLooper()).post {
                instance?.overlayView?.clearAllTimerRinging()
            }
        }
        
        fun bringToFrontStatic() {
            instance?.bringToFront()
        }

        fun isSmartAodCovering(): Boolean =
            isOverlayShowing() && instance?.smartAodCovering == true

        /** Screensaver just covered the tiles — stop MJPEG, keep the last frame. */
        fun notifyScreensaverCovered() {
            Handler(Looper.getMainLooper()).post {
                instance?.pauseCamera(dropFrames = false)
            }
        }

        /** Screensaver left; restart MJPEG only if the tiles are still showing. */
        fun notifyScreensaverUncovered() {
            Handler(Looper.getMainLooper()).post {
                instance?.resumeCamera()
            }
        }

        fun show(context: Context) {
            val svc = instance
            if (svc != null && svc.isEnabled && svc.isVisible) {
                svc.notifyHaWhileAlreadyRunning()
                return
            }
            context.startService(Intent(context, QuickEntityOverlayService::class.java).apply {
                action = ACTION_SHOW
            })
        }

        fun hide(context: Context) {
            if (instance == null) return
            context.startService(Intent(context, QuickEntityOverlayService::class.java).apply {
                action = ACTION_HIDE
            })
        }

        fun toggle(context: Context) {
            context.startService(Intent(context, QuickEntityOverlayService::class.java).apply {
                action = ACTION_TOGGLE
            })
        }

        fun updateSlots(context: Context) {
            context.startService(Intent(context, QuickEntityOverlayService::class.java).apply {
                action = ACTION_UPDATE_SLOTS
            })
        }
    }
}

class QuickEntityOverlayView(
    context: Context,
    private var screenWidth: Int,
    private var screenHeight: Int
) : View(context) {

    private val slots = mutableListOf<QuickEntitySlot>()
    private val entityStates = mutableMapOf<String, String>()
    private val entityUnits = mutableMapOf<String, String>()
    private val timerRemaining = mutableMapOf<String, String>()
    private val timerInitialMs = mutableMapOf<String, Long>()
    private val timerStartTime = mutableMapOf<String, Long>()
    // Wall-clock end time from HA's `finishes_at` — the only redelivery-safe anchor.
    // HA never ticks the `remaining` attribute, so a replayed cache value re-anchored
    // "now" jumps the countdown back up; `finishes_at` stays true no matter how often
    // it is replayed. This is also what HA's own frontend counts down from.
    private val timerFinishesAt = mutableMapOf<String, Long>()
    private val timerRinging = mutableSetOf<String>()
    private var breathingPhase = 0f
    private var timerUpdateHandler: android.os.Handler? = null
    private var timerUpdateRunnable: Runnable? = null
    var onEntityClick: ((QuickEntitySlot) -> Unit)? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchedSlotIndex = -1
    
    private var isDragging = false
    private var dragStartTime = 0L
    private var dragSlotIndex = -1
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f
    private var dragTargetIndex = -1
    private val longPressThreshold = 400L
    private val dragThreshold = 20f
    /** Same idea as media «Back»: hold, don't wake chrome on a tap. */
    private val chromeRevealHoldMs = 1_500L
    private val chromeRevealHandler = Handler(Looper.getMainLooper())
    private val chromeRevealRunnable = Runnable {
        DashboardOverlayChrome.onUserTouch(context, DashboardOverlayChrome.Kind.QUICK_ENTITY)
    }
    private var onSlotsReordered: ((List<QuickEntitySlot>) -> Unit)? = null
    private var onCameraViewChanged: ((String, Float, Float, Float) -> Unit)? = null
    private var layoutLocked = false
    private var isPanningCamera = false
    private var isPinchingCamera = false
    private var panStartPanX = 0.5f
    private var panStartPanY = 0.5f
    private var cameraFadeAlpha = 0f
    private var cameraFadeEntityId: String? = null
    private var cameraFadeAnimator: ValueAnimator? = null
    private val cameraScaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!layoutLocked) return false
                val slot = slots.getOrNull(touchedSlotIndex)
                if (slot == null || !isCameraSlot(slot)) return false
                isPinchingCamera = true
                isPanningCamera = false
                cancelPendingChromeReveal()
                startCameraEdgeFade(slot.entityId)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val index = touchedSlotIndex
                val slot = slots.getOrNull(index) ?: return false
                if (!isCameraSlot(slot) || index !in tileRects.indices) return false
                val nextZoom = cameraRenderer.zoomByPinch(
                    entityId = slot.entityId,
                    tileRect = tileRects[index],
                    currentZoom = slot.cameraZoom,
                    factor = detector.scaleFactor
                ) ?: return true
                slots[index] = slot.copy(cameraZoom = nextZoom)
                val dirty = cameraRenderer.invalidateRectForEntity(slot.entityId)
                    ?: tileRects[index].let {
                        Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                    }
                invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
                return true
            }
        }
    )

    private var buttonPressedIndex = -1
    private var buttonPressedTime = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tileRects = mutableListOf<RectF>()
    private val cameraRenderer = QuickEntityCameraRenderer(
        context = context,
        surfaceHost = { parent as? ViewGroup },
        invalidate = { dirtyRect ->
            if (dirtyRect != null) {
                postInvalidateOnAnimation(
                    dirtyRect.left,
                    dirtyRect.top,
                    dirtyRect.right,
                    dirtyRect.bottom
                )
            } else {
                postInvalidateOnAnimation()
            }
        }
    )

    private val typeface: Typeface = try {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    } catch (e: Exception) {
        Typeface.DEFAULT
    }
    
    private val rajdhaniSemibold: Typeface = try {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.example.ava.R.font.rajdhani_semibold)
            ?: Typeface.create("sans-serif", Typeface.BOLD)
    } catch (e: Exception) {
        Typeface.create("sans-serif", Typeface.BOLD)
    }
    
    private val oxaniumBold: Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/Oxanium-Bold-NumbersOnly.ttf")
    } catch (e: Exception) {
        Typeface.create("sans-serif", Typeface.BOLD)
    }
    

    init {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
        repeat(6) { slots.add(QuickEntitySlot()) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            screenWidth = w
            screenHeight = h
            calculateTileRects()
        }
    }

    private fun calculateTileRects() {
        tileRects.clear()
        
        val activeCount = slots.count { it.entityId.isNotEmpty() }
        if (activeCount == 0) return
        
        val isLandscape = screenWidth > screenHeight
        val padding = (min(screenWidth, screenHeight) * 0.05f)
        val gap = (min(screenWidth, screenHeight) * 0.03f)
        
        val availableWidth = screenWidth - padding * 2
        val availableHeight = screenHeight - padding * 2
        
        when (activeCount) {
            1 -> {

                tileRects.add(RectF(padding, padding, padding + availableWidth, padding + availableHeight))
            }
            2 -> {

                if (isLandscape) {
                    val tileWidth = (availableWidth - gap) / 2
                    tileRects.add(RectF(padding, padding, padding + tileWidth, padding + availableHeight))
                    tileRects.add(RectF(padding + tileWidth + gap, padding, padding + availableWidth, padding + availableHeight))
                } else {
                    val tileHeight = (availableHeight - gap) / 2
                    tileRects.add(RectF(padding, padding, padding + availableWidth, padding + tileHeight))
                    tileRects.add(RectF(padding, padding + tileHeight + gap, padding + availableWidth, padding + availableHeight))
                }
            }
            3 -> {

                val leftWidth = availableWidth * 0.45f
                val rightWidth = availableWidth - leftWidth - gap
                val halfHeight = (availableHeight - gap) / 2
                
                tileRects.add(RectF(padding, padding, padding + leftWidth, padding + halfHeight))
                tileRects.add(RectF(padding, padding + halfHeight + gap, padding + leftWidth, padding + availableHeight))
                tileRects.add(RectF(padding + leftWidth + gap, padding, padding + availableWidth, padding + availableHeight))
            }
            4 -> {

                val tileWidth = (availableWidth - gap) / 2
                val tileHeight = (availableHeight - gap) / 2
                
                tileRects.add(RectF(padding, padding, padding + tileWidth, padding + tileHeight))
                tileRects.add(RectF(padding + tileWidth + gap, padding, padding + availableWidth, padding + tileHeight))
                tileRects.add(RectF(padding, padding + tileHeight + gap, padding + tileWidth, padding + availableHeight))
                tileRects.add(RectF(padding + tileWidth + gap, padding + tileHeight + gap, padding + availableWidth, padding + availableHeight))
            }
            5 -> {

                val leftWidth = availableWidth * 0.4f
                val rightWidth = availableWidth - leftWidth - gap
                val halfHeight = (availableHeight - gap) / 2
                val thirdHeight = (availableHeight - gap * 2) / 3
                
                tileRects.add(RectF(padding, padding, padding + leftWidth, padding + halfHeight))
                tileRects.add(RectF(padding, padding + halfHeight + gap, padding + leftWidth, padding + availableHeight))
                tileRects.add(RectF(padding + leftWidth + gap, padding, padding + availableWidth, padding + thirdHeight))
                tileRects.add(RectF(padding + leftWidth + gap, padding + thirdHeight + gap, padding + availableWidth, padding + thirdHeight * 2 + gap))
                tileRects.add(RectF(padding + leftWidth + gap, padding + thirdHeight * 2 + gap * 2, padding + availableWidth, padding + availableHeight))
            }
            else -> {

                val cols = if (isLandscape) 3 else 2
                val rows = if (isLandscape) 2 else 3
                val tileWidth = (availableWidth - gap * (cols - 1)) / cols
                val tileHeight = (availableHeight - gap * (rows - 1)) / rows
                
                for (i in 0 until activeCount.coerceAtMost(6)) {
                    val col = i % cols
                    val row = i / cols
                    val left = padding + col * (tileWidth + gap)
                    val top = padding + row * (tileHeight + gap)
                    tileRects.add(RectF(left, top, left + tileWidth, top + tileHeight))
                }
            }
        }
        syncCameraTileLayouts()
    }

    private fun syncCameraTileLayouts() {
        for (i in slots.indices) {
            if (i >= tileRects.size) break
            val slot = slots[i]
            if (slot.entityType == "camera" || slot.entityId.startsWith("camera.")) {
                cameraRenderer.updateTileLayout(slot.entityId, cameraDrawRect(tileRects[i]))
            }
        }
    }

    /** Match drawTile scaledRect (non-pressed) for layout + invalidate. */
    private fun cameraDrawRect(rect: RectF): RectF {
        return RectF(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom
        )
    }

    fun updateSlots(newSlots: List<QuickEntitySlot>) {
        slots.clear()
        slots.addAll(
            newSlots.filter { it.entityId.isNotEmpty() }
                .take(6)
                .map { slot ->
                    if (slot.entityId.startsWith("camera.") && slot.entityType != "camera") {
                        slot.copy(entityType = "camera")
                    } else {
                        slot
                    }
                }
        )
        cameraRenderer.syncSlots(slots)
        calculateTileRects()
        postInvalidate()
    }

    fun updateEntityState(entityId: String, state: String) {
        val oldState = entityStates[entityId]
        entityStates[entityId] = state
        
        if (entityId.startsWith("timer.")) {
            val newStateLower = state.lowercase()
            val oldStateLower = oldState?.lowercase()
            
            when {
                newStateLower == "active" && oldStateLower == "paused" -> {
                    kotlinx.coroutines.GlobalScope.launch {
                        VoiceSatelliteService.getInstance()?.resubscribeQuickEntities()
                    }
                    startTimerUpdateLoop()
                }
                newStateLower == "active" && oldStateLower != "active" -> {
                    startTimerUpdateLoop()
                }
                newStateLower == "paused" && oldStateLower == "active" -> {
                    stopTimerUpdateLoop()
                }
                newStateLower == "idle" && oldStateLower == "active" -> {
                    timerInitialMs.remove(entityId)
                    timerStartTime.remove(entityId)
                    timerFinishesAt.remove(entityId)
                    timerRinging.add(entityId)
                    startBreathingAnimation()
                    VoiceSatelliteService.getInstance()?.triggerTimerFinished()
                }
                newStateLower == "idle" -> {
                    timerInitialMs.remove(entityId)
                    timerStartTime.remove(entityId)
                    timerFinishesAt.remove(entityId)
                    timerRemaining[entityId] = "00:00"
                }
            }
        }
        
        postInvalidate()
    }

    fun updateEntityUnit(entityId: String, unit: String) {
        entityUnits[entityId] = unit
        postInvalidate()
    }
    
    fun updateTimerRemaining(entityId: String, remaining: String) {
        val active = entityStates[entityId]?.lowercase() == "active"
        val totalMs = parseTimeToSeconds(remaining) * 1000
        if (totalMs > 0 && active) {
            timerInitialMs[entityId] = totalMs
            timerStartTime[entityId] = System.currentTimeMillis()
            startTimerUpdateLoop()
        }
        // HA freezes `remaining` at the last start/resume; cache replays (panel
        // show, resubscribe) redeliver that stale value. While finishes_at is
        // driving a live countdown, writing it here would flash the tile back
        // to the start value until the next 1 s tick.
        if (active && timerFinishesAt.containsKey(entityId)) {
            return
        }
        val formatted = formatTimeString(remaining)
        timerRemaining[entityId] = formatted
        postInvalidate()
    }
    
    private fun formatTimeString(timeStr: String): String {
        if (!timeStr.contains(":")) return "00:00"
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].split(".")[0].toInt()
                    if (h > 0) {
                        String.format("%d:%02d:%02d", h, m, s)
                    } else {
                        String.format("%02d:%02d", m, s)
                    }
                }
                2 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].split(".")[0].toInt()
                    String.format("%02d:%02d", m, s)
                }
                else -> "00:00"
            }
        } catch (e: Exception) {
            "00:00"
        }
    }
    
    fun updateTimerFinishesAt(entityId: String, finishesAt: String) {
        // HA clears finishes_at on pause/idle; ESPHome pushes that as blank/None.
        if (finishesAt.isBlank() || finishesAt.equals("none", ignoreCase = true)) {
            timerFinishesAt.remove(entityId)
            return
        }
        val targetMs = parseIsoTimestampMs(finishesAt) ?: return
        timerFinishesAt[entityId] = targetMs
        if (entityStates[entityId]?.lowercase() == "active") {
            startTimerUpdateLoop()
        }
        postInvalidate()
    }

    /** HA sends ISO-8601 with offset, e.g. `2026-08-26T07:30:00+00:00`. */
    private fun parseIsoTimestampMs(iso: String): Long? = try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /**
     * Fresh `get_states` snapshot from the direct HA WebSocket (panel show /
     * slot reload). Deliberately bypasses [updateEntityState]'s transition
     * logic: a finish that happened while the panel was hidden must not start
     * the ring retroactively — snapshots set display state only, transitions
     * stay push-driven.
     */
    fun applyTimerSnapshot(entityId: String, state: String, remaining: String, finishesAt: String) {
        val stateLower = state.lowercase()
        entityStates[entityId] = state
        updateTimerFinishesAt(entityId, finishesAt)
        when {
            stateLower == "active" -> {
                if (!timerFinishesAt.containsKey(entityId)) {
                    // No wall-clock target in the snapshot — anchor `remaining`
                    // at receipt like the push path does.
                    val totalMs = parseTimeToSeconds(remaining) * 1000
                    if (totalMs > 0) {
                        timerInitialMs[entityId] = totalMs
                        timerStartTime[entityId] = System.currentTimeMillis()
                        timerRemaining[entityId] = formatTimeString(remaining)
                    }
                }
                startTimerUpdateLoop()
            }
            stateLower == "paused" -> {
                timerInitialMs.remove(entityId)
                timerStartTime.remove(entityId)
                if (remaining.isNotBlank()) {
                    timerRemaining[entityId] = formatTimeString(remaining)
                }
            }
            else -> {
                timerInitialMs.remove(entityId)
                timerStartTime.remove(entityId)
                timerFinishesAt.remove(entityId)
                timerRemaining[entityId] = "00:00"
            }
        }
        postInvalidate()
    }
    
    private fun parseTimeToSeconds(timeStr: String): Long {
        if (!timeStr.contains(":")) return 0
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].split(".")[0].toLong()
                    h * 3600 + m * 60 + s
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].split(".")[0].toLong()
                    m * 60 + s
                }
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun startTimerUpdateLoop() {
        if (timerUpdateHandler == null) {
            timerUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        // Remove existing runnable to avoid duplicates
        timerUpdateRunnable?.let { timerUpdateHandler?.removeCallbacks(it) }
        
        timerUpdateRunnable = object : Runnable {
            override fun run() {
                updateTimersFromLocalCountdown()
                postInvalidate()
                
                if (hasActiveTimers()) {
                    timerUpdateHandler?.postDelayed(this, 1000)
                } else {
                    timerUpdateRunnable = null
                }
            }
        }
        timerUpdateHandler?.post(timerUpdateRunnable!!)
    }
    
    
    private fun stopTimerUpdateLoop() {
        timerUpdateRunnable?.let { timerUpdateHandler?.removeCallbacks(it) }
        timerUpdateRunnable = null
    }
    
    private fun hasActiveTimers(): Boolean {
        return slots.any { slot ->
            slot.entityId.startsWith("timer.") &&
            entityStates[slot.entityId]?.lowercase() == "active"
        }
    }
    
    private var breathingAnimator: android.animation.ValueAnimator? = null
    
    private fun startBreathingAnimation() {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                breathingPhase = animator.animatedValue as Float
                postInvalidate()
            }
            start()
        }
    }
    
    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        breathingPhase = 0f
    }
    
    fun clearTimerRinging(entityId: String) {
        timerRinging.remove(entityId)
        timerRemaining[entityId] = "00:00"
        if (timerRinging.isEmpty()) {
            stopBreathingAnimation()
        }
        postInvalidate()
    }

    /**
     * Ring dismissed outside the panel (stop word, wake word, HA button,
     * disconnect). The satellite does not know which entity rang — HA voice
     * timers are not tied to a slot — so every ringing tile is reset.
     */
    fun clearAllTimerRinging() {
        if (timerRinging.isEmpty()) return
        timerRinging.forEach { timerRemaining[it] = "00:00" }
        timerRinging.clear()
        stopBreathingAnimation()
        postInvalidate()
    }
    
    private fun updateTimersFromLocalCountdown() {
        val now = System.currentTimeMillis()
        (timerFinishesAt.keys + timerInitialMs.keys).forEach { entityId ->
            if (entityStates[entityId]?.lowercase() != "active") return@forEach
            // finishes_at first: absolute wall-clock target, immune to stale
            // attribute replays. The receipt-time anchor is only a fallback for
            // pushes that arrived without finishes_at.
            val finishesAtMs = timerFinishesAt[entityId]
            val remainingMs = if (finishesAtMs != null) {
                finishesAtMs - now
            } else {
                val initialMs = timerInitialMs[entityId] ?: return@forEach
                val startTime = timerStartTime[entityId] ?: now
                initialMs - (now - startTime)
            }
            if (remainingMs <= 0) {
                // Target passed; HA's idle push (which runs the ring) is due.
                timerRemaining[entityId] = "00:00"
                return@forEach
            }
            val totalSeconds = remainingMs / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60

            val current = timerRemaining[entityId]
            val timeStr = if (current != null && current.count { it == ':' } == 2) {
                String.format("%d:%02d:%02d", h, m, s)
            } else if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%02d:%02d", m, s)
            }
            timerRemaining[entityId] = timeStr
        }
    }

    fun updateEntityLabel(entityId: String, label: String) {
        val idx = slots.indexOfFirst { it.entityId == entityId }
        if (idx >= 0) {
            slots[idx] = slots[idx].copy(label = label)
            postInvalidate()
        }
    }

    fun updateEntityPicture(
        entityId: String,
        pictureUrl: String,
        haRemoteUrl: String? = null,
        forceRestart: Boolean = false,
    ) {
        cameraRenderer.updatePictureUrl(entityId, pictureUrl, haRemoteUrl, forceRestart)
    }

    fun clearEntityPicture(entityId: String) {
        cameraRenderer.clearPictureUrl(entityId)
    }

    fun activeCameraEntityIds(): Set<String> = cameraRenderer.activeEntityIds()

    fun releaseRenderer() {
        cameraRenderer.release()
        stopTimerUpdateLoop()
        stopBreathingAnimation()
    }

    fun pauseCamera(dropFrames: Boolean = true) {
        cameraFadeAnimator?.cancel()
        cameraFadeAlpha = 0f
        cameraFadeEntityId = null
        cameraRenderer.setAdjustFade(null, 0f)
        cameraRenderer.pause(dropFrames)
    }

    fun resumeCamera() {
        cameraRenderer.resume()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Host stays attached while GONE. Starting the camera here would keep
        // MJPEG alive after hide, and after a z-order restack while hidden.
        if (hasActiveTimers()) {
            startTimerUpdateLoop()
        }
    }

    override fun onDetachedFromWindow() {
        // Z-order restacks detach this view briefly. Keep MJPEG sockets;
        // pauseCamera() is only for a real hide / destroy.
        stopTimerUpdateLoop()
        stopBreathingAnimation()
        cancelPendingChromeReveal()
        super.onDetachedFromWindow()
    }

    private fun scheduleChromeReveal() {
        cancelPendingChromeReveal()
        chromeRevealHandler.postDelayed(chromeRevealRunnable, chromeRevealHoldMs)
    }

    private fun cancelPendingChromeReveal() {
        chromeRevealHandler.removeCallbacks(chromeRevealRunnable)
    }

    fun setOnSlotsReordered(callback: (List<QuickEntitySlot>) -> Unit) {
        onSlotsReordered = callback
    }

    fun setOnCameraPanChanged(callback: (String, Float, Float, Float) -> Unit) {
        onCameraViewChanged = callback
    }

    fun setLayoutLocked(locked: Boolean) {
        if (layoutLocked == locked) return
        layoutLocked = locked
        if (locked && isDragging) {
            isDragging = false
            dragSlotIndex = -1
            dragTargetIndex = -1
            invalidate()
        }
        if (!locked) {
            isPanningCamera = false
            isPinchingCamera = false
            endCameraEdgeFade()
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (layoutLocked) {
            cameraScaleDetector.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                QuickEntityOverlayService.getInstance()?.interruptSmartAod()
                touchStartX = event.x
                touchStartY = event.y
                touchedSlotIndex = findTouchedSlot(event.x, event.y)
                dragStartTime = System.currentTimeMillis()
                dragSlotIndex = -1
                isDragging = false
                isPanningCamera = false
                isPinchingCamera = false
                val downSlot = slots.getOrNull(touchedSlotIndex)
                if (downSlot != null && isCameraSlot(downSlot)) {
                    panStartPanX = downSlot.cameraPanX
                    panStartPanY = downSlot.cameraPanY
                }
                scheduleChromeReveal()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (layoutLocked) cancelPendingChromeReveal()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinchingCamera || cameraScaleDetector.isInProgress || event.pointerCount > 1) {
                    return true
                }
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val holdTime = System.currentTimeMillis() - dragStartTime
                val touched = slots.getOrNull(touchedSlotIndex)

                if (layoutLocked &&
                    !isPanningCamera &&
                    touched != null &&
                    isCameraSlot(touched) &&
                    (abs(dx) > dragThreshold || abs(dy) > dragThreshold)
                ) {
                    isPanningCamera = true
                    cancelPendingChromeReveal()
                    startCameraEdgeFade(touched.entityId)
                }

                if (isPanningCamera && touched != null && touchedSlotIndex in tileRects.indices) {
                    val next = cameraRenderer.panByDrag(
                        entityId = touched.entityId,
                        tileRect = tileRects[touchedSlotIndex],
                        panX = panStartPanX,
                        panY = panStartPanY,
                        zoom = touched.cameraZoom,
                        dx = dx,
                        dy = dy
                    )
                    if (next != null) {
                        slots[touchedSlotIndex] = touched.copy(
                            cameraPanX = next.first,
                            cameraPanY = next.second
                        )
                        val dirty = cameraRenderer.invalidateRectForEntity(touched.entityId)
                            ?: tileRects[touchedSlotIndex].let {
                                Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                            }
                        invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
                    }
                    return true
                }

                if (!layoutLocked && !isDragging && touchedSlotIndex >= 0 && holdTime > longPressThreshold && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                    isDragging = true
                    dragSlotIndex = touchedSlotIndex
                    cancelPendingChromeReveal()
                }

                if (isDragging) {
                    dragCurrentX = event.x
                    dragCurrentY = event.y
                    dragTargetIndex = findTouchedSlot(event.x, event.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingChromeReveal()
                persistCameraViewIfNeeded()
                val wasAdjusting = isPanningCamera || isPinchingCamera
                isDragging = false
                isPanningCamera = false
                isPinchingCamera = false
                dragSlotIndex = -1
                dragTargetIndex = -1
                touchedSlotIndex = -1
                if (wasAdjusting) endCameraEdgeFade()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingChromeReveal()
                val dx = abs(event.x - touchStartX)
                val dy = abs(event.y - touchStartY)

                val wasAdjusting = isPinchingCamera || isPanningCamera
                if (wasAdjusting) {
                    persistCameraViewIfNeeded()
                } else if (!isDragging && dx < 80 && dy < 80) {
                    if (touchedSlotIndex >= 0 && touchedSlotIndex < slots.size) {
                        val slot = slots[touchedSlotIndex]
                        if (slot.entityId.isNotEmpty()) {
                            val isCameraTile = isCameraSlot(slot)
                            if (!isCameraTile) {
                                TouchSoundHelper.playClick(this)

                                if (slot.entityId.startsWith("button.") || slot.entityId.startsWith("script.") || slot.entityId.startsWith("scene.")) {
                                    buttonPressedIndex = touchedSlotIndex
                                    buttonPressedTime = System.currentTimeMillis()
                                    invalidate()

                                    Handler(Looper.getMainLooper()).postDelayed({
                                        buttonPressedIndex = -1
                                        invalidate()
                                    }, 500)
                                }
                                onEntityClick?.invoke(slot)
                            }
                        }
                    }
                } else if (isDragging && dragSlotIndex >= 0 && dragTargetIndex >= 0 && dragSlotIndex != dragTargetIndex) {
                    val temp = slots[dragSlotIndex]
                    slots[dragSlotIndex] = slots[dragTargetIndex]
                    slots[dragTargetIndex] = temp
                    calculateTileRects()
                    onSlotsReordered?.invoke(slots.toList())
                }

                isDragging = false
                isPanningCamera = false
                isPinchingCamera = false
                dragSlotIndex = -1
                dragTargetIndex = -1
                touchedSlotIndex = -1
                if (wasAdjusting) endCameraEdgeFade()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun persistCameraViewIfNeeded() {
        val changed = slots.getOrNull(touchedSlotIndex)
        if (changed != null && isCameraSlot(changed)) {
            onCameraViewChanged?.invoke(
                changed.entityId,
                changed.cameraPanX,
                changed.cameraPanY,
                changed.cameraZoom
            )
        }
    }

    private fun startCameraEdgeFade(entityId: String) {
        cameraFadeEntityId = entityId
        animateCameraEdgeFade(1f)
    }

    private fun endCameraEdgeFade() {
        animateCameraEdgeFade(0f)
    }

    private fun animateCameraEdgeFade(target: Float) {
        cameraFadeAnimator?.cancel()
        val start = cameraFadeAlpha
        if (abs(start - target) < 0.01f) {
            applyCameraEdgeFade(target)
            if (target <= 0f) cameraFadeEntityId = null
            return
        }
        cameraFadeAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = if (target > start) 160L else 280L
            interpolator = if (target > start) {
                DecelerateInterpolator()
            } else {
                AccelerateInterpolator()
            }
            addUpdateListener { animator ->
                applyCameraEdgeFade(animator.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (canceled) return
                    if (target <= 0f) {
                        cameraFadeEntityId = null
                        cameraRenderer.setAdjustFade(null, 0f)
                    }
                }
            })
            start()
        }
    }

    private fun applyCameraEdgeFade(alpha: Float) {
        cameraFadeAlpha = alpha
        cameraRenderer.setAdjustFade(cameraFadeEntityId, alpha)
        val entityId = cameraFadeEntityId
        val dirty = if (entityId != null) cameraRenderer.invalidateRectForEntity(entityId) else null
        if (dirty != null) {
            invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
        } else {
            invalidate()
        }
    }

    private fun isCameraSlot(slot: QuickEntitySlot): Boolean =
        slot.entityType == "camera" || slot.entityId.startsWith("camera.")

    /** Press shrink (0.96) is unlocked-only. Locked tiles stay put so a hold does not twitch. */
    private fun isPressScaleActive(index: Int): Boolean {
        return !layoutLocked && touchedSlotIndex == index && !isDragging
    }

    private fun findTouchedSlot(x: Float, y: Float): Int {
        for (i in tileRects.indices) {
            if (tileRects[i].contains(x, y)) {
                return i
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width > 0 && height > 0 && (screenWidth != width || screenHeight != height || tileRects.isEmpty())) {
            screenWidth = width
            screenHeight = height
            calculateTileRects()
        }

        val clip = canvas.clipBounds
        val isPartialUpdate = clip.left > 0 || clip.top > 0 ||
            clip.right < width || clip.bottom < height

        if (!isPartialUpdate) {
            drawBackground(canvas)
            drawTiles(canvas)
        } else {
            drawTilesInClip(canvas, clip)
        }
        cameraRenderer.notifyDrawn()
    }

    private fun rectIntersectsClip(rect: RectF, clip: Rect): Boolean {
        return Rect.intersects(
            Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
            clip
        )
    }

    private fun drawTilesInClip(canvas: Canvas, clip: Rect) {
        for (i in slots.indices) {
            if (i >= tileRects.size) break
            if (isDragging && i == dragSlotIndex) continue

            val rect = tileRects[i]
            if (!rectIntersectsClip(rect, clip)) {
                continue
            }

            val slot = slots[i]
            val isActive = getEntityState(slot.entityId)
            val isPressed = isPressScaleActive(i)
            val isButtonPressed = buttonPressedIndex == i
            drawTile(canvas, rect, slot, isActive, false, isPressed, isButtonPressed)
        }

        if (isDragging && dragSlotIndex >= 0 && dragSlotIndex < slots.size) {
            val slot = slots[dragSlotIndex]
            val originalRect = tileRects[dragSlotIndex]
            val dragSize = min(originalRect.width(), originalRect.height()) * 1.1f
            val dragRect = RectF(
                dragCurrentX - dragSize / 2,
                dragCurrentY - dragSize / 2,
                dragCurrentX + dragSize / 2,
                dragCurrentY + dragSize / 2
            )
            if (rectIntersectsClip(dragRect, clip)) {
                val isActive = getEntityState(slot.entityId)
                val isButtonPressed = buttonPressedIndex == dragSlotIndex
                drawTile(canvas, dragRect, slot, isActive, false, true, isButtonPressed)
            }
        }
    }

    private fun drawBackground(canvas: Canvas) {

        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, screenHeight.toFloat(),
            intArrayOf(Color.parseColor("#050608"), Color.parseColor("#0a0a0c")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), bgPaint)
        

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        dotPaint.color = Color.argb(13, 255, 255, 255)
        val gridSize = 40f
        val dotRadius = 1f
        var x = gridSize / 2
        while (x < screenWidth) {
            var y = gridSize / 2
            while (y < screenHeight) {
                canvas.drawCircle(x, y, dotRadius, dotPaint)
                y += gridSize
            }
            x += gridSize
        }
    }

    private fun drawTiles(canvas: Canvas) {
        

        for (i in slots.indices) {
            if (i >= tileRects.size) break
            if (isDragging && i == dragSlotIndex) continue
            
            val slot = slots[i]
            val rect = tileRects[i]
            val isActive = getEntityState(slot.entityId)
            val isPressed = isPressScaleActive(i)
            val isDropTarget = isDragging && i == dragTargetIndex

            if (isDropTarget) {
                val tileSize = min(rect.width(), rect.height())
                val highlightPaint = Paint().apply {
                    color = Color.argb(60, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(rect, tileSize * 0.12f, tileSize * 0.12f, highlightPaint)
            }
            
            val isButtonPressed = buttonPressedIndex == i
            drawTile(canvas, rect, slot, isActive, false, isPressed, isButtonPressed)
        }
        

        if (isDragging && dragSlotIndex >= 0 && dragSlotIndex < slots.size) {
            val slot = slots[dragSlotIndex]
            val originalRect = tileRects[dragSlotIndex]
            val dragSize = min(originalRect.width(), originalRect.height()) * 1.1f
            val dragRect = RectF(
                dragCurrentX - dragSize / 2,
                dragCurrentY - dragSize / 2,
                dragCurrentX + dragSize / 2,
                dragCurrentY + dragSize / 2
            )
            
            val isActive = getEntityState(slot.entityId)
            val isButtonPressed = buttonPressedIndex == dragSlotIndex
            drawTile(canvas, dragRect, slot, isActive, false, true, isButtonPressed)
        }
    }

    private fun drawTile(
        canvas: Canvas,
        rect: RectF,
        slot: QuickEntitySlot,
        isActive: Boolean,
        isEmpty: Boolean,
        isPressed: Boolean,
        isButtonPressed: Boolean
    ) {
        val theme = getThemeColors(slot.icon, slot.color.ifEmpty { null })
        
        val scale = if (isPressed) 0.96f else 1f
        val scaledRect = RectF(
            rect.centerX() - rect.width() * scale / 2,
            rect.centerY() - rect.height() * scale / 2,
            rect.centerX() + rect.width() * scale / 2,
            rect.centerY() + rect.height() * scale / 2
        )
        
        val tileSize = min(scaledRect.width(), scaledRect.height())
        val cornerRadius = tileSize * 0.12f
        
        if (isActive && !isEmpty && slot.entityType != "sensor" && slot.entityType != "timer" && slot.entityType != "camera") {
            glowPaint.shader = RadialGradient(
                scaledRect.centerX(), scaledRect.centerY(),
                scaledRect.width() * 0.8f,
                intArrayOf(theme.glow, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(scaledRect, cornerRadius, cornerRadius, glowPaint)
        }


        if (isEmpty) {
            drawEmptySlot(canvas, scaledRect)
        } else if (slot.entityType == "sensor") {
            drawSensorTile(canvas, scaledRect, slot, theme)
        } else if (slot.entityType == "timer") {
            drawTimerTile(canvas, scaledRect, slot, theme)
        } else if (slot.entityType == "camera" || slot.entityId.startsWith("camera.")) {
            cameraRenderer.drawTile(
                canvas = canvas,
                rect = scaledRect,
                slot = slot,
                textPaint = textPaint
            )
        } else {
            drawButtonTile(canvas, scaledRect, slot, isActive, isButtonPressed, theme)
        }
    }

    private fun drawEmptySlot(canvas: Canvas, rect: RectF) {
        val tileSize = min(rect.width(), rect.height())
        val cornerRadius = tileSize * 0.12f
        

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.shader = LinearGradient(
            rect.left, rect.top,
            rect.left + rect.width() * 0.3f, rect.bottom,
            Color.parseColor("#0c0c0e"), Color.parseColor("#060607"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        
        val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        edgeGlowPaint.style = Paint.Style.STROKE
        edgeGlowPaint.strokeWidth = 1f
        edgeGlowPaint.shader = LinearGradient(
            rect.left, rect.top,
            rect.left + rect.width() * 0.4f, rect.top + rect.height() * 0.4f,
            Color.argb(20, 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        val edgePath = Path()
        edgePath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.drawPath(edgePath, edgeGlowPaint)
        
        val iconSize = (tileSize * 0.2f).toInt()
        val drawable = ContextCompat.getDrawable(context, R.drawable.mdi_plus)
        drawable?.let {
            it.setTint(Color.argb(40, 255, 255, 255))
            val iconLeft = (rect.centerX() - iconSize / 2).toInt()
            val iconTop = (rect.centerY() - iconSize / 2).toInt()
            it.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            it.draw(canvas)
        }
    }

    private fun drawButtonTile(
        canvas: Canvas,
        rect: RectF,
        slot: QuickEntitySlot,
        isActive: Boolean,
        isButtonPressed: Boolean,
        theme: TileTheme
    ) {
        val tileWidth = rect.width()
        val tileHeight = rect.height()
        val tileSize = min(tileWidth, tileHeight)
        val cornerRadius = tileSize * 0.12f
        
        val isActionEntity = slot.entityId.startsWith("button.") || 
                            slot.entityId.startsWith("script.") || 
                            slot.entityId.startsWith("scene.")
        

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isActive || isButtonPressed) {

            bgPaint.shader = LinearGradient(
                rect.left, rect.top,
                rect.left + tileWidth * 0.3f, rect.bottom,
                theme.topColor, theme.bottomColor,
                Shader.TileMode.CLAMP
            )
        } else {
            bgPaint.shader = LinearGradient(
                rect.left, rect.top,
                rect.left + tileWidth * 0.3f, rect.bottom,
                Color.parseColor("#0f1012"), Color.parseColor("#08090a"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        

        val presetColor = com.example.ava.ui.components.MdiColorMapper.getTileColorForIcon(slot.icon, slot.color.ifEmpty { null })
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 1.5f
        val borderAlpha = if (isActive || isButtonPressed) 220 else 100
        borderPaint.color = Color.argb(borderAlpha, 
            Color.red(presetColor.topColor), 
            Color.green(presetColor.topColor), 
            Color.blue(presetColor.topColor))
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        

        val screenMin = min(screenWidth, screenHeight).toFloat()
        val tileArea = tileWidth * tileHeight
        val screenArea = screenWidth.toFloat() * screenHeight.toFloat()
        val areaRatio = tileArea / screenArea
        val baseIconSize = when {
            areaRatio > 0.3f -> tileSize * 0.80f
            areaRatio > 0.15f -> tileSize * 0.65f
            else -> tileSize * 0.40f
        }
        val iconSize = baseIconSize.coerceIn(32f, 600f).toInt()
        val iconResId = getIconResIdForState(slot.icon, isActive)
        val drawable = ContextCompat.getDrawable(context, iconResId)
        
        val iconColor = if (isActive || isButtonPressed) {
            Color.argb(230, 0, 0, 0)
        } else {
            Color.parseColor("#5a5a5f")
        }
        
        drawable?.let {
            it.setTint(iconColor)
            val iconLeft = (rect.centerX() - iconSize / 2).toInt()
            val iconTop = (rect.centerY() - iconSize / 2).toInt()
            it.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            it.draw(canvas)
        }
    }

    private fun drawSensorTile(
        canvas: Canvas,
        rect: RectF,
        slot: QuickEntitySlot,
        theme: TileTheme
    ) {
        val rawState = entityStates[slot.entityId] ?: "--"
        val state = rawState.toDoubleOrNull()?.let { v ->
            val dotIdx = rawState.indexOf('.')
            if (dotIdx < 0) rawState
            else {
                val decimals = rawState.length - dotIdx - 1
                if (decimals <= 2) rawState
                else String.format("%.2f", v)
            }
        } ?: rawState
        val tileWidth = rect.width()
        val tileHeight = rect.height()
        val tileSize = min(tileWidth, tileHeight)
        val cornerRadius = tileSize * 0.12f
        val padding = tileSize * 0.12f
        
        val sensorType = extractSensorType(slot.entityId)
        

        val presetColor = com.example.ava.ui.components.MdiColorMapper.getTileColorForIcon(slot.icon, slot.color.ifEmpty { null })
        val hasCustomColor = slot.color.isNotEmpty()
        

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.shader = LinearGradient(
            rect.left, rect.top,
            rect.left + tileWidth * 0.3f, rect.bottom,
            Color.parseColor("#0f1012"), Color.parseColor("#08090a"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        

        if (hasCustomColor) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val diagonal = kotlin.math.sqrt(tileWidth * tileWidth + tileHeight * tileHeight)
            val radius = diagonal * 0.55f
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.shader = RadialGradient(
                centerX, centerY, radius,
                intArrayOf(
                    Color.argb(80, Color.red(presetColor.topColor), Color.green(presetColor.topColor), Color.blue(presetColor.topColor)),
                    Color.argb(30, Color.red(presetColor.topColor), Color.green(presetColor.topColor), Color.blue(presetColor.topColor)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            val clipPath = Path()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawCircle(centerX, centerY, radius, glowPaint)
            canvas.restore()
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 1.5f
        borderPaint.color = Color.argb(100, 
            Color.red(presetColor.topColor), 
            Color.green(presetColor.topColor), 
            Color.blue(presetColor.topColor))
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        

        val iconResId = getIconResId(slot.icon)
        val bgDrawable = ContextCompat.getDrawable(context, iconResId)
        bgDrawable?.let {
            it.setTint(Color.argb(8, 255, 255, 255))

            val bgIconSize = (tileSize * 1.3f).toInt()
            val iconRight = (rect.right + tileSize * 0.35f).toInt()
            val iconBottom = (rect.bottom + tileSize * 0.35f).toInt()
            canvas.save()
            val clipPath = Path()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            it.setBounds(iconRight - bgIconSize, iconBottom - bgIconSize, iconRight, iconBottom)
            it.draw(canvas)
            canvas.restore()
        }
        

        val isLargeTile = tileHeight > tileWidth * 1.3f || tileWidth > tileHeight * 1.3f || tileSize > 300
        val isLandscape = tileWidth > tileHeight
        val availableWidth = tileWidth - padding * 2
        val availableHeight = tileHeight - padding * 2
        val aspectRatio = tileWidth / tileHeight
        val horizontalShift = if (aspectRatio > 1.5f) {
            (tileWidth - tileHeight) * 0.25f
        } else if (aspectRatio < 0.67f) {
            0f
        } else {
            0f
        }


        val labelSize = (availableHeight * 0.10f).coerceIn(8f, 36f)
        textPaint.color = Color.parseColor("#6a6a6f")
        textPaint.textSize = labelSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.letterSpacing = 0.15f
        val labelText = slot.label.uppercase().ifEmpty { sensorType.uppercase() }
        val labelCenterX = rect.centerX()
        val labelY = rect.top + padding + labelSize
        canvas.drawText(labelText, labelCenterX, labelY, textPaint)
        

        val labelWidth = textPaint.measureText(labelText)
        val lineY = labelY - labelSize * 0.3f
        val lineGap = padding * 0.4f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        linePaint.strokeWidth = 1f
        linePaint.shader = LinearGradient(
            labelCenterX + labelWidth / 2 + lineGap, lineY,
            rect.right - padding, lineY,
            Color.argb(77, 255, 214, 10), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(labelCenterX + labelWidth / 2 + lineGap, lineY, rect.right - padding, lineY, linePaint)
        val linePaintLeft = Paint(Paint.ANTI_ALIAS_FLAG)
        linePaintLeft.strokeWidth = 1f
        linePaintLeft.shader = LinearGradient(
            labelCenterX - labelWidth / 2 - lineGap, lineY,
            rect.left + padding, lineY,
            Color.argb(77, 255, 214, 10), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(labelCenterX - labelWidth / 2 - lineGap, lineY, rect.left + padding, lineY, linePaintLeft)
        

        if (isLargeTile) {
            val decorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            decorLinePaint.shader = LinearGradient(
                rect.left + padding * 0.3f, rect.top + padding * 2,
                rect.left + padding * 0.3f, rect.bottom - padding * 2,
                Color.argb(0, Color.red(presetColor.topColor), Color.green(presetColor.topColor), Color.blue(presetColor.topColor)),
                Color.argb(60, Color.red(presetColor.topColor), Color.green(presetColor.topColor), Color.blue(presetColor.topColor)),
                Shader.TileMode.CLAMP
            )
            decorLinePaint.strokeWidth = 2f
            canvas.drawLine(rect.left + padding * 0.3f, rect.top + padding * 2, rect.left + padding * 0.3f, rect.bottom - padding * 2, decorLinePaint)
            
            val decorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            decorTextPaint.color = Color.argb(30, 255, 255, 255)
            decorTextPaint.textSize = (tileSize * 0.05f).coerceIn(10f, 18f)
            decorTextPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            decorTextPaint.letterSpacing = 0.3f
            decorTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("SENSOR DATA", rect.right - padding, rect.bottom - padding * 0.5f, decorTextPaint)
            
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            dotPaint.color = Color.argb(20, 255, 255, 255)
            val dotSpacing = tileSize * 0.03f
            val dotRadius = 1.5f
            for (row in 0..2) {
                for (col in 0..2) {
                    canvas.drawCircle(
                        rect.left + padding + col * dotSpacing,
                        rect.bottom - padding * 1.5f - row * dotSpacing,
                        dotRadius, dotPaint
                    )
                }
            }
        }
        

        val contentTop = rect.top + padding + labelSize + padding * 0.3f
        val contentBottom = rect.bottom - padding
        val contentHeight = contentBottom - contentTop
        var valueSize = (contentHeight * 0.85f).coerceIn(16f, 700f)
        
        textPaint.textSize = valueSize
        textPaint.typeface = rajdhaniSemibold
        textPaint.letterSpacing = 0.02f
        textPaint.textAlign = Paint.Align.LEFT
        
        val maxValueWidth = availableWidth * 0.92f
        while (textPaint.measureText(state) > maxValueWidth && valueSize > 12f) {
            valueSize -= 2f
            textPaint.textSize = valueSize
        }
        
        val contentCenterY = (contentTop + contentBottom) / 2
        val verticalOffset = if (isLargeTile) -valueSize * 0.05f else 0f
        val valueY = contentCenterY + valueSize * 0.35f + verticalOffset
        
        var unit = entityUnits[slot.entityId] ?: ""
        if (unit.length > 5) unit = unit.take(5)
        val stateWidth = textPaint.measureText(state)
        val gap = (padding * 0.3f).coerceIn(2f, 12f)
        var unitSize = (contentHeight * 0.28f).coerceIn(10f, 56f)
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        unitPaint.textSize = unitSize
        unitPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        unitPaint.letterSpacing = 0.02f
        val unitWidth = if (unit.isNotEmpty()) unitPaint.measureText(unit) + gap else 0f
        val totalWidth = stateWidth + unitWidth
        val valueX = rect.centerX() - totalWidth / 2
        
        textPaint.shader = LinearGradient(
            valueX, valueY - valueSize,
            valueX, valueY,
            Color.WHITE, Color.argb(80, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawText(state, valueX, valueY, textPaint)
        textPaint.shader = null
        

        if (unit.isNotEmpty()) {
            unitPaint.color = Color.parseColor("#6a6a6f")
            canvas.drawText(unit, valueX + stateWidth + gap, valueY - unitSize * 0.15f, unitPaint)
        }
    }

    private fun getEntityState(entityId: String): Boolean {
        if (entityId.isEmpty()) return false
        val state = entityStates[entityId]?.lowercase() ?: return false

        val activeStates = listOf("on", "true", "playing", "home", "open", "unlocked", "detected", "active")
        val inactiveStates = listOf("off", "false", "paused", "idle", "standby", "unavailable", "unknown", "away", "closed", "locked", "not_home")
        return when {
            state in activeStates -> true
            state in inactiveStates -> false
            state.toDoubleOrNull() != null -> state.toDouble() > 0
            else -> false
        }
    }
    
    fun getTimerState(entityId: String): String {
        return entityStates[entityId]?.lowercase() ?: "idle"
    }
    
    private fun drawTimerTile(
        canvas: Canvas,
        rect: RectF,
        slot: QuickEntitySlot,
        theme: TileTheme
    ) {
        val rawState = entityStates[slot.entityId]?.lowercase() ?: "idle"
        val tileWidth = rect.width()
        val tileHeight = rect.height()
        val tileSize = min(tileWidth, tileHeight)
        val cornerRadius = tileSize * 0.12f
        val padding = tileSize * 0.12f
        
        val isActive = rawState == "active"
        val isPaused = rawState == "paused"
        val isIdle = rawState == "idle" || rawState == "unknown" || rawState == "unavailable"
        
        val presetColor = com.example.ava.ui.components.MdiColorMapper.getTileColorForIcon(slot.icon, slot.color.ifEmpty { null })
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isActive) {
            bgPaint.shader = LinearGradient(
                rect.left, rect.top,
                rect.left + tileWidth * 0.3f, rect.bottom,
                theme.topColor, theme.bottomColor,
                Shader.TileMode.CLAMP
            )
        } else {
            bgPaint.shader = LinearGradient(
                rect.left, rect.top,
                rect.left + tileWidth * 0.3f, rect.bottom,
                Color.parseColor("#0f1012"), Color.parseColor("#08090a"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        
        // Draw timer icon as background (like sensor tiles)
        val iconResId = getIconResId(slot.icon.ifEmpty { "mdi:timer" })
        val bgDrawable = ContextCompat.getDrawable(context, iconResId)
        bgDrawable?.let {
            it.setTint(Color.argb(8, 255, 255, 255))
            val bgIconSize = (tileSize * 1.3f).toInt()
            val iconRight = (rect.right + tileSize * 0.35f).toInt()
            val iconBottom = (rect.bottom + tileSize * 0.35f).toInt()
            canvas.save()
            val clipPath = Path()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            it.setBounds(iconRight - bgIconSize, iconBottom - bgIconSize, iconRight, iconBottom)
            it.draw(canvas)
            canvas.restore()
        }
        
        val isRinging = slot.entityId.startsWith("timer.") && timerRinging.contains(slot.entityId)
        
        if (isRinging) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val diagonal = kotlin.math.sqrt(tileWidth * tileWidth + tileHeight * tileHeight)
            val baseRadius = diagonal * 0.4f
            val maxRadius = diagonal * 0.75f
            val currentRadius = baseRadius + (maxRadius - baseRadius) * breathingPhase
            val alpha = 40 + ((1f - breathingPhase * 0.5f) * 60).toInt()
            val glowColor = if (slot.color.isNotEmpty()) {
                try { Color.parseColor(slot.color) } catch (e: Exception) { Color.parseColor("#8B3A3A") }
            } else {
                Color.parseColor("#8B3A3A")
            }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.shader = RadialGradient(
                centerX, centerY, currentRadius,
                intArrayOf(
                    Color.argb(alpha, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                    Color.argb(alpha / 2, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            val clipPath = Path()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawCircle(centerX, centerY, currentRadius, glowPaint)
            canvas.restore()
        } else if (isActive) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val diagonal = kotlin.math.sqrt(tileWidth * tileWidth + tileHeight * tileHeight)
            val radius = diagonal * 0.55f
            val glowColor = presetColor.topColor
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.shader = RadialGradient(
                centerX, centerY, radius,
                intArrayOf(
                    Color.argb(80, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                    Color.argb(30, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            val clipPath = Path()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawCircle(centerX, centerY, radius, glowPaint)
            canvas.restore()
        }
        
        val borderColor = if (isPaused && slot.color.isEmpty()) Color.parseColor("#FFD60A") else presetColor.topColor
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = if (isActive || isPaused) 2f else 1.5f
        val borderAlpha = if (isActive) 220 else if (isPaused) 200 else 80
        borderPaint.color = Color.argb(borderAlpha, 
            Color.red(borderColor), 
            Color.green(borderColor), 
            Color.blue(borderColor))
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        
        val isSingleCard = slots.size == 1
        
        // Bottom: Label (name) - moved from top to bottom
        val labelSize = if (isSingleCard) {
            (tileHeight * 0.10f).coerceIn(10f, 32f)
        } else {
            (tileHeight * 0.08f).coerceIn(8f, 24f)
        }
        textPaint.color = Color.argb(230, 255, 255, 255)
        textPaint.textSize = labelSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.letterSpacing = 0.15f
        textPaint.shader = null
        val labelText = slot.label.uppercase().ifEmpty { "TIMER" }
        val labelY = if (isSingleCard) {
            rect.bottom - padding * 0.8f
        } else {
            rect.bottom - padding * 0.8f - 20f
        }
        canvas.drawText(labelText, rect.centerX(), labelY, textPaint)
        
        val contentTop = rect.top + padding + labelSize * 1.0f
        val contentBottom = rect.bottom - padding - labelSize
        val contentHeight = contentBottom - contentTop
        
        val remaining = timerRemaining[slot.entityId] ?: ""
        val timerDisplay = formatTimerDisplay(rawState, remaining)
        
        // Single card mode: use larger font
        val hasThreeSegments = timerDisplay.count { it == ':' } == 2
        var valueSize = if (isSingleCard) {
            if (hasThreeSegments) {
                (contentHeight * 1.1f).coerceIn(32f, 700f)
            } else {
                (contentHeight * 1.6f).coerceIn(32f, 700f)
            }
        } else {
            if (hasThreeSegments) {
                (contentHeight * 0.8f).coerceIn(16f, 500f)
            } else {
                (contentHeight * 1.0f).coerceIn(16f, 500f)
            }
        }
        textPaint.textSize = valueSize
        textPaint.typeface = oxaniumBold
        textPaint.letterSpacing = 0.02f
        textPaint.textScaleX = 1.0f
        textPaint.textAlign = Paint.Align.LEFT
        
        val maxValueWidth = tileWidth - padding * 2
        while (textPaint.measureText(timerDisplay) > maxValueWidth && valueSize > 12f) {
            valueSize -= 2f
            textPaint.textSize = valueSize
        }
        
        // Center vertically and horizontally in content area
        val fontMetrics = textPaint.fontMetrics
        val contentCenterY = (contentTop + contentBottom) / 2
        val baselineY = contentCenterY - (fontMetrics.ascent + fontMetrics.descent) / 2
        
        val textColor = when {
            isActive -> Color.WHITE
            isPaused -> if (slot.color.isEmpty()) Color.parseColor("#FFD60A") else presetColor.topColor
            else -> Color.parseColor("#6a6a6f")
        }
        
        // Draw each character separately with fixed width digits and centered colon
        val colonSize = valueSize * 0.65f
        val colonPaint = Paint(textPaint)
        colonPaint.textSize = colonSize
        colonPaint.typeface = rajdhaniSemibold
        colonPaint.textAlign = Paint.Align.CENTER
        
        // Use fixed digit width based on widest digit "0"
        val digitWidth = textPaint.measureText("0")
        val colonWidth = digitWidth * 0.3f
        val colonGap = digitWidth * 0.02f
        
        // Calculate total width
        var totalWidth = 0f
        for (c in timerDisplay) {
            if (c == ':') {
                totalWidth += colonWidth + colonGap * 2
            } else {
                totalWidth += digitWidth
            }
        }
        
        var currentX = rect.centerX() - totalWidth / 2
        
        textPaint.color = textColor
        textPaint.shader = null
        textPaint.textAlign = Paint.Align.CENTER
        colonPaint.color = textColor
        
        for (c in timerDisplay) {
            if (c == ':') {
                val colonCenterX = currentX + colonGap + colonWidth / 2
                val colonOffset = when (slots.size) {
                    1 -> 35f
                    2 -> 28f
                    3 -> 22f
                    4 -> 18f
                    5 -> 15f
                    else -> 12f
                }
                val colonY = contentCenterY + colonSize * 0.35f - colonOffset
                canvas.drawText(":", colonCenterX, colonY, colonPaint)
                currentX += colonWidth + colonGap * 2
            } else {
                // Draw digit centered in its fixed-width slot
                val digitCenterX = currentX + digitWidth / 2
                canvas.drawText(c.toString(), digitCenterX, baselineY, textPaint)
                currentX += digitWidth
            }
        }
    }
    
    private fun formatTimerDisplay(state: String, remaining: String): String {
        if (state == "idle" || state == "unknown" || state == "unavailable") {
            return "00:00"
        }
        
        // Try remaining first, then state
        val timeStr = when {
            remaining.isNotEmpty() && remaining.contains(":") -> remaining
            state.contains(":") -> state
            else -> return "00:00"
        }
        
        // Parse and format with fixed width digits
        
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].split(".")[0].toInt()
                    if (h > 0) {
                        String.format("%d:%02d:%02d", h, m, s)
                    } else {
                        String.format("%02d:%02d", m, s)
                    }
                }
                2 -> {
                    val m = parts[0].toInt()
                    val s = parts[1].split(".")[0].toInt()
                    String.format("%02d:%02d", m, s)
                }
                else -> "00:00"
            }
        } catch (e: Exception) {
            "00:00"
        }
    }

    private fun extractSensorType(entityId: String): String {

        val knownTypes = listOf(
            "temperature", "humidity", "battery", "pressure", "illuminance", "lux",
            "power", "energy", "voltage", "current", "co2", "pm25", "pm10",
            "motion", "door", "window", "smoke", "gas", "water", "moisture"
        )
        val lowerEntityId = entityId.lowercase()
        for (type in knownTypes) {
            if (lowerEntityId.contains(type)) return type
        }
        return "sensor"
    }

    private fun getIconResId(icon: String): Int = 
        com.example.ava.ui.components.MdiIconMapper.getIconResId(icon)

    private fun getIconResIdForState(icon: String, isActive: Boolean): Int = 
        com.example.ava.ui.components.MdiIconMapper.getIconResIdForState(icon, isActive)

    private fun getThemeColors(icon: String, customColor: String? = null): TileTheme {
        val tileColor = com.example.ava.ui.components.MdiColorMapper.getTileColorForIcon(icon, customColor)
        return TileTheme(tileColor.topColor, tileColor.bottomColor, tileColor.glowColor)
    }

    data class TileTheme(
        val topColor: Int,
        val bottomColor: Int,
        val glow: Int
    )
}
