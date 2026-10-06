package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
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
import com.example.ava.clock.ClockAlert
import com.example.ava.clock.ClockAlertSensor
import com.example.ava.clock.ClockAlertStore
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.ui.components.ClockAlertGlyph
import com.example.ava.ui.components.ClockAlertSheet
import com.example.ava.ui.components.ClockAlertGlyphDp
import com.example.ava.ui.components.clockAlertGrow
import com.example.ava.ui.components.clockAlertScale

class ClockAlertOverlayService : Service(),
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
    private var iconView: ComposeView? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var sheetView: ComposeView? = null
    private var sheetParams: WindowManager.LayoutParams? = null

    private var items by mutableStateOf<List<ClockAlert>>(emptyList())
    private var selectedId by mutableStateOf<String?>(null)
    private var open by mutableStateOf(false)
    private var ringingId by mutableStateOf<String?>(null)
    private var editing by mutableStateOf(false)
    private var soundUri by mutableStateOf(ClockAlert.DEFAULT_SOUND)
    private var fadeGeneration = 0
    private var hiding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createIconView()
        createSheetView()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createIconView() {
        iconView = ComposeView(this).apply {
            attachComposeTrees()
            alpha = 0f
            setContent {
                ClockAlertGlyph(
                    count = items.size,
                    ringing = ringingId != null,
                    onClick = { onGlyphClick() },
                )
            }
        }
        iconParams = iconParams()
        try {
            windowManager?.addView(iconView, iconParams)
            OverlayZOrderCoordinator.noteWindowAdded()
            iconView?.animate()
                ?.alpha(1f)
                ?.setDuration(FADE_MS)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add clock alert icon", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSheetView() {
        sheetView = ComposeView(this).apply {
            attachComposeTrees()
            alpha = 0f
            visibility = View.GONE
            setContent {
                val selected = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
                ClockAlertSheet(
                    items = items,
                    selected = selected,
                    ringing = ringingId != null,
                    editing = editing,
                    soundUri = soundUri,
                    onDismissSheet = { if (ringingId == null) setSheetOpen(false) },
                    onSelect = { selectedId = it },
                    onClose = { onSheetClose() },
                    onDelete = { deleteItem(it) },
                    onToggleEdit = { editing = true },
                    onCommitTime = { hour, minute -> commitSelected(hour, minute) },
                    onSnooze = { snoozeSelected() },
                    onSoundSelected = { uri ->
                        ClockAlertStore.setSoundUri(this@ClockAlertOverlayService, uri)
                        soundUri = uri
                    },
                )
            }
        }
        sheetParams = sheetParams()
        try {
            windowManager?.addView(sheetView, sheetParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add clock alert sheet", e)
        }
    }

    private fun ComposeView.attachComposeTrees() {
        setViewTreeLifecycleOwner(this@ClockAlertOverlayService)
        setViewTreeViewModelStoreOwner(this@ClockAlertOverlayService)
        setViewTreeSavedStateRegistryOwner(this@ClockAlertOverlayService)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        iconView?.let { view ->
            val params = iconParams()
            iconParams = params
            if (view.isAttachedToWindow) {
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
        sheetView?.let { view ->
            val params = sheetParams()
            sheetParams = params
            if (view.isAttachedToWindow) {
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideAndStop()
                return START_NOT_STICKY
            }
            ACTION_RING -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                refreshItems()
                if (id.isNotEmpty() && items.any { it.id == id }) {
                    selectedId = id
                    ringingId = id
                    startRinging()
                    setSheetOpen(true)
                    ClockAlertSensor.publish(this)
                } else {
                    refreshOrStop()
                }
            }
            else -> refreshOrStop()
        }
        return START_STICKY
    }

    private fun refreshItems() {
        items = ClockAlertStore.list(this).filter { it.enabled }
        soundUri = ClockAlertStore.soundUri(this)
        if (selectedId == null || items.none { it.id == selectedId }) {
            selectedId = ClockAlertStore.soonest(this)?.id ?: items.firstOrNull()?.id
        }
    }

    private fun refreshOrStop() {
        refreshItems()
        if (items.isEmpty()) hideAndStop()
        else ClockAlertSensor.publish(this)
    }

    private fun setSheetOpen(value: Boolean) {
        if (!value) editing = false
        if (open == value) return
        open = value
        fadeSheet(value)
    }

    private fun fadeSheet(show: Boolean) {
        val view = sheetView ?: return
        val gen = ++fadeGeneration
        view.animate().cancel()
        view.animate().setListener(null)
        if (show) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            bringToFront()
            view.animate()
                .alpha(1f)
                .setDuration(FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
                .start()
        } else {
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
                        view.alpha = 0f
                    }
                })
                .start()
        }
    }

    private fun iconParams(): WindowManager.LayoutParams {
        val d = resources.displayMetrics.density
        val config = resources.configuration
        val grow = clockAlertGrow(
            clockAlertScale(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()),
        )
        val side = (ClockAlertGlyphDp * grow * d).toInt()
        return WindowManager.LayoutParams(
            side,
            side,
            PlatformCapabilities.overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * d).toInt()
            y = (12 * d).toInt()
            PlatformCapabilities.applyDisplayCutoutShortEdges(this)
            OverlayOrientation.apply(this)
        }
    }

    private fun sheetParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            PlatformCapabilities.overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            PlatformCapabilities.applyDisplayCutoutShortEdges(this)
            OverlayOrientation.apply(this)
        }
    }

    private fun bringToFront() {
        val wm = windowManager ?: return
        val sheet = sheetView
        val sheetLp = sheetParams
        if (open &&
            sheet != null &&
            sheetLp != null &&
            sheet.isAttachedToWindow &&
            sheet.visibility == View.VISIBLE
        ) {
            OverlayZOrderCoordinator.bringToFront(
                wm,
                sheet,
                sheetLp,
                TAG,
                raiseMic = false,
            )
        }
        val icon = iconView ?: return
        val iconLp = iconParams ?: return
        if (!icon.isAttachedToWindow) return
        OverlayZOrderCoordinator.bringToFront(wm, icon, iconLp, TAG, raiseMic = false)
    }

    private fun onGlyphClick() {
        if (ringingId != null) {
            silenceRinging()
            return
        }
        if (!open) setSheetOpen(true)
    }

    private fun onSheetClose() {
        if (ringingId != null) {
            silenceRinging()
            return
        }
        setSheetOpen(false)
    }

    private fun deleteItem(id: String) {
        if (ringingId == id) return
        ClockAlertStore.remove(this, id)
        editing = false
        refreshOrStop()
    }

    private fun commitSelected(hour: Int, minute: Int) {
        val id = selectedId ?: return
        ClockAlertStore.reschedule(this, id, hour, minute)
        editing = false
        refreshItems()
        ClockAlertSensor.publish(this)
    }

    private fun snoozeSelected() {
        val id = selectedId ?: return
        stopRinging()
        VoiceSatelliteService.getInstance()?.stopTimerSound()
        ClockAlertStore.snooze(this, id)
        setSheetOpen(false)
        refreshOrStop()
    }

    private fun startRinging() {
        vibrate(true)
        val uri = ClockAlertStore.soundUri(this)
        if (uri.isBlank()) return
        VoiceSatelliteService.getInstance()?.triggerDreamClockTimerFinished(uri)
    }

    fun silenceRinging() {
        val wasRinging = ringingId != null
        finishThisRing()
        if (wasRinging) {
            VoiceSatelliteService.getInstance()?.stopTimerSound()
        }
    }

    fun finishThisRing() {
        val id = ringingId ?: return
        // Close on the last item only stops the ring. Deleting it would also
        // tear the overlay down. Other items still drop when their ring is closed.
        val keepLast = ClockAlertStore.list(this).count { it.enabled } <= 1
        stopRinging()
        if (keepLast) ClockAlertStore.disarm(this, id)
        else ClockAlertStore.afterFire(this, id)
        setSheetOpen(false)
        refreshOrStop()
    }

    private fun stopRinging() {
        ringingId = null
        vibrate(false)
    }

    private fun vibrate(on: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!on) {
            vibrator.cancel()
            return
        }
        val pattern = longArrayOf(0, 400, 220, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun hideAndStop() {
        if (hiding) return
        hiding = true
        fadeGeneration++
        val wasRinging = ringingId != null
        stopRinging()
        if (wasRinging) VoiceSatelliteService.getInstance()?.stopTimerSound()
        open = false
        val views = listOfNotNull(iconView, sheetView).filter { it.isAttachedToWindow }
        if (views.isEmpty()) {
            finishHide()
            return
        }
        var pending = views.size
        views.forEach { view ->
            view.animate().cancel()
            view.animate().setListener(null)
            if (view.visibility != View.VISIBLE || view.alpha <= 0.01f) {
                pending--
                return@forEach
            }
            view.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.animate().setListener(null)
                        pending--
                        if (pending == 0) finishHide()
                    }
                })
                .start()
        }
        if (pending == 0) finishHide()
    }

    private fun finishHide() {
        listOf(iconView, sheetView).forEach { view ->
            view?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        iconView = null
        sheetView = null
        iconParams = null
        sheetParams = null
        ClockAlertSensor.publish(this)
        stopSelf()
    }

    override fun onDestroy() {
        fadeGeneration++
        val wasRinging = ringingId != null
        stopRinging()
        if (wasRinging) VoiceSatelliteService.getInstance()?.stopTimerSound()
        vibrate(false)
        iconView?.animate()?.cancel()
        sheetView?.animate()?.cancel()
        listOf(iconView, sheetView).forEach { view ->
            view?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        iconView = null
        sheetView = null
        viewModelStoreImpl.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClockAlertOverlay"
        private const val FADE_MS = 240L
        const val ACTION_SYNC = "com.example.ava.action.SYNC_CLOCK_ALERT"
        const val ACTION_HIDE = "com.example.ava.action.HIDE_CLOCK_ALERT"
        const val ACTION_RING = "com.example.ava.action.RING_CLOCK_ALERT"
        const val EXTRA_ID = "clock_alert_id"

        @Volatile
        private var instance: ClockAlertOverlayService? = null

        fun isShowing(): Boolean = instance?.items?.isNotEmpty() == true
        fun isRinging(): Boolean = instance?.ringingId != null
        fun ringingId(): String? = instance?.ringingId
        fun isSheetOpen(): Boolean = instance?.open == true

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        fun silence() {
            val svc = instance
            if (svc == null || svc.ringingId == null) return
            svc.silenceRinging()
        }

        fun onChimeStopped() {
            instance?.finishThisRing()
        }

        fun sync(context: Context) {
            val app = context.applicationContext
            if (ClockAlertStore.list(app).none { it.enabled }) {
                hide(app)
                return
            }
            if (!PlatformCapabilities.canDrawOverlays(app)) {
                Log.w(TAG, "Cannot show clock alert: overlay permission missing")
                return
            }
            app.startService(Intent(app, ClockAlertOverlayService::class.java).setAction(ACTION_SYNC))
        }

        fun hide(context: Context) {
            val app = context.applicationContext
            if (instance != null) {
                app.startService(Intent(app, ClockAlertOverlayService::class.java).setAction(ACTION_HIDE))
            }
        }

        fun ring(context: Context, id: String) {
            val app = context.applicationContext
            if (!PlatformCapabilities.canDrawOverlays(app)) {
                Log.w(TAG, "Cannot ring clock alert: overlay permission missing")
                return
            }
            app.startService(
                Intent(app, ClockAlertOverlayService::class.java)
                    .setAction(ACTION_RING)
                    .putExtra(EXTRA_ID, id),
            )
        }
    }
}
