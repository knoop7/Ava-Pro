package com.example.ava.services

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.audio.PlaybackEnergyMonitor
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import com.example.ava.esphome.voicesatellite.Listening
import com.example.ava.esphome.voicesatellite.Processing
import com.example.ava.esphome.voicesatellite.Responding
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickWakeTrigger
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.voice.QuickWakeFabGestures
import com.example.ava.voice.QuickWakePushToTalk
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.utils.OverlayRaiseCover
import com.example.ava.utils.ScreenBlankOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Persistent floating mic — starts a voice session without the wake word.
 *
 * Default rest pose is a D-handle sucked to the nearest screen edge (style 1). Left and
 * right handles stand tall; top and bottom handles lie flat. The control stays a
 * [FAB_SIZE_DP] circle until it is in a tight band against that edge, then it becomes a D.
 * A corner stays a circle so the handle does not hang off the screen; releasing there
 * snaps out to a seat on the nearer edge. Sliding along the edge while docked stays a
 * handle. Processing, arm-fill, ripple, and the level meter follow the D silhouette when
 * docked — they do not orbit the mic as a circle.
 *
 * Gesture model ([MicrophoneSettingsStore.quickWakeTrigger]):
 * - TAP: short press → [VoiceSatelliteService.quickWake]. A tap during STT or TTS
 *   is the same abort as HA "Just stop" ([VoiceSatelliteService.stopVoiceSession]).
 *   Still-release after the drag-arm window is still a tap (open or Just stop).
 *   A swipe always repositions — idle after Just stop included. Never drop the stream.
 * - HOLD: long press → [QuickWakePushToTalk.begin]; release → end-of-speech marker.
 *   A tap after that, while STT or TTS is still running, is the same abort.
 *   Sliding past slop repositions the button and keeps the recording. Only a tap
 *   aborts — drag is never "Just stop".
 * Connecting (HA uplink still down): tap-to-open and hold wait. A tap while a
 * listen is already in flight is Just stop. Drag always repositions.
 *
 * The whole gesture lives in the window root's [View.onTouchEvent]: the disc child is not
 * clickable, so the ViewGroup intercept path has no target and the root must own the
 * stream itself (return true on DOWN).
 */
class QuickWakeFabService : Service() {

    /**
     * ATTENDING: hybrid wake-word STT. The sphere / ripple own the mic meter; the disc
     * steps back (yield scale) and keeps only the interrupt affordance.
     */
    private enum class VisualState { IDLE, LISTENING, ATTENDING, PROCESSING, SPEAKING, RECORDING }

    /** Screen edge the handle is sucked to. Top and bottom lie flat; left and right stand. */
    private enum class FabEdge { LEFT, RIGHT, TOP, BOTTOM }

    private var windowManager: WindowManager? = null
    private var fabView: QuickWakeFabView? = null
    private var fabRoot: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var captionView: TextView? = null
    private var captionParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val microphoneStore by lazy {
        MicrophoneSettingsStore(applicationContext.microphoneSettingsStore)
    }
    private val playerStore by lazy {
        PlayerSettingsStore(applicationContext.playerSettingsStore)
    }
    /** Mirrors the floating-captions switch: off → no transcript bubble either. */
    private var captionsEnabled = true

    private var normX = -1f
    private var normY = -1f
    /** Shape centre in screen pixels — window top-left is derived from this. */
    private var centerX = 0f
    private var centerY = 0f
    private var drawX = 0
    private var drawY = 0
    /** Drag started on a docked D; skip wall magnet while the finger is leaving. */
    private var dragFromDock = false
    private var dragEdge = FabEdge.RIGHT
    private var dragVx = 0f
    private var dragVy = 0f
    private var lastDragAt = 0L
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var physicsRunning = false
    private var physicsTx = 0f
    private var physicsTy = 0f
    private var physicsVx = 0f
    private var physicsVy = 0f
    /** Release is docking to a wall: no overshoot on that axis, and the overlay keeps its drag shell. */
    private var physicsToEdge = false
    /** Edge the in-flight settle is docking to or peeling off. */
    private var physicsEdge = FabEdge.RIGHT
    /** Dock-in or morph-reject: softer along the dock axis so the magnet does not yank. */
    private var physicsSoftX = false
    private var lastPhysicsNanos = 0L
    private val physicsCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!physicsRunning) return
            if (lastPhysicsNanos == 0L) lastPhysicsNanos = frameTimeNanos
            val dt = ((frameTimeNanos - lastPhysicsNanos) / 1_000_000_000f).coerceIn(0.008f, 0.033f)
            lastPhysicsNanos = frameTimeNanos
            if (!stepPhysics(dt)) {
                physicsRunning = false
                snapToNearestHome()
                persistPosition()
                // Size stays the drag shell — shrinking here was the edge hitch.
                applyWindow(moveOnly = true)
                repositionCaptionIfShown()
                return
            }
            // During the droplet slide, position only — no per-frame width/height churn.
            applyWindow(moveOnly = true)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var visualState = VisualState.IDLE
    private var sessionListening = false
    /**
     * Wake-word turn in hybrid mode: the disc yields for the whole session (STT →
     * processing → TTS) and comes back when the session ends. Button turns never yield.
     */
    private var yieldedTurn = false
    /** True once TTS audio has actually started. Responding alone is still processing chrome. */
    private var ttsAudible = false
    /**
     * This Quick Wake turn has received STT_END. HA flips to Processing at VAD_END,
     * before the transcript exists. Visual chrome goes PROCESSING then; the outer
     * level fades out. This latch is only for the STT caption gate.
     */
    private var heardSttThisTurn = false
    /** Reply plate has settled this turn — arm the STT linger even if the bubble attached late. */
    private var ttsCaptionSettledThisTurn = false
    /** Bubble already left after the reply; a late STT_END must not revive it. */
    private var sttCaptionDoneThisTurn = false
    /**
     * This button turn still owes the STT bubble, even after the satellite is idle.
     * Local music (and any fast HA service) returns to Connected before STT_END /
     * the reply plate; the local-AI path stays on Responding through TTS. Either
     * way the bubble must appear, linger, then leave — idle chrome must not pass it.
     */
    private var captionTurnActive = false
    private var lastSatState: Any? = null
    private var micMuted = false
    /** Live copy of the setting — `getCached()` can still hold the default on first read. */
    private var trigger = QuickWakeTrigger.TAP
    /** WM refused the disc (permission revoked, token gone): retry with backoff. */
    private var attachRetries = 0
    private val attachRetryRunnable = Runnable { ensureAttached() }
    /** Disc vanished from WM after an overlay burst / mid-drag restack. */
    private val healthRunnable = Runnable { healDiscIfMissing(scheduleNext = true) }
    /** One next-frame climb when WM has the disc but [View.isAttachedToWindow] still lags. */
    private var attachClimbPosted = false
    /** While yielded to AOD, poll so the disc comes back even if nobody asks for a raise. */
    private val aodRecheckRunnable = Runnable { recheckAod() }
    /** AOD plate is covering: mic is hidden and not touchable, window stays attached. */
    private var aodYielded = false
    /**
     * [OverlayZOrderCoordinator.stackGeneration] at the last successful climb.
     * A raise while this still matches means nothing else has add/restack'd —
     * remove+add would only flash the mic glyph.
     */
    private var stackedAtGeneration = -1L

    private val hideCaptionRunnable = Runnable { hideCaption() }
    /** No transcript arrived after idle — drop the latch so the next tap is clean. */
    private val abandonCaptionTurnRunnable = Runnable {
        if (!heardSttThisTurn && !captionIsShowing()) {
            captionTurnActive = false
        }
    }
    /** Bubble is fading out — do not remove+add it or the fade freezes mid-alpha. */
    private var captionDismissing = false
    /** Parked in WM (INVISIBLE). Next STT reuses the same window — no addView flash. */
    private var captionParked = false
    /** Cold-start park may sit under the boot stack. The first reveal climbs once. */
    private var captionNeedsClimb = false
    /** Reply plate has settled and the bubble's exit is on the clock; later pages must not re-arm. */
    private var captionLingerArmed = false

    private val homePrefs: SharedPreferences by lazy {
        getSharedPreferences(com.example.ava.ui.screens.home.PREFS_NAME, MODE_PRIVATE)
    }
    private val darkModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == com.example.ava.ui.screens.home.KEY_DARK_MODE) {
            fabView?.setDarkMode(isDarkMode())
            captionView?.let { styleCaption(it) }
        }
    }

    /** Last physical screen used to place the control — remap through this on rotate. */
    private var lastScreenW = 0
    private var lastScreenH = 0
    /** One extra pass: some OEMs still report portrait metrics on the first callback. */
    private var relayoutRetryPending = false

    private val relayoutRunnable = Runnable { relayoutForConfiguration() }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            scheduleRelayout()
        }
        override fun onLowMemory() {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleRelayout()
    }

    private fun scheduleRelayout() {
        handler.removeCallbacks(relayoutRunnable)
        handler.post(relayoutRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Not sticky: after a process death [VoiceSatelliteService] decides whether the
     * button belongs on screen. A system-restarted disc with no satellite behind it
     * would press into nothing.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadPosition()
        createFab()
        observeSettings()
        observeSessionState()
        observePushToTalk()
        observeTranscript()
        homePrefs.registerOnSharedPreferenceChangeListener(darkModeListener)
        registerComponentCallbacks(configCallbacks)
        scheduleHealth()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPhysics()
        QuickWakePushToTalk.cancel()
        scope.cancel()
        runCatching { homePrefs.unregisterOnSharedPreferenceChangeListener(darkModeListener) }
        runCatching { unregisterComponentCallbacks(configCallbacks) }
        releaseCaptionWindow()
        fabView?.destroy()
        runCatching { fabRoot?.let { windowManager?.removeView(it) } }
        fabRoot = null
        fabView = null
        windowParams = null
        instance = null
        super.onDestroy()
    }

    // -- View / window setup --

    @SuppressLint("ClickableViewAccessibility")
    private fun createFab() {
        val params = WindowManager.LayoutParams(
            windowSizePx(), windowSizePx(),
            overlayWindowType(),
            voiceLayerWindowFlags(touchable = true),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = R.style.AppWindowNoAnimation
            applyIgnoreSystemBars()
        }
        windowParams = params

        val view = QuickWakeFabView(this, isDarkMode(), discSizePx()) {
            VoiceSatelliteService.getInstance()?.currentMicrophoneLevel() ?: 0f
        }
        fabView = view

        val root = GestureRoot(this, view)
        root.setBackgroundColor(Color.TRANSPARENT)
        root.fitsSystemWindows = false
        fabRoot = root

        refreshDrawPosition()
        rememberScreenSize()
        val (winW, winH) = overlayShellSize()
        params.width = winW
        params.height = winH
        params.x = (centerX - winW / 2f).roundToInt()
        params.y = (centerY - winH / 2f).roundToInt()
        drawX = params.x
        drawY = params.y
        view.setShapeMetrics(discSizePx(), handleWpx(), handleHpx())
        view.setDock(dockAmount(), activeEdge())
        root.addView(view, FrameLayout.LayoutParams(winW, winH, Gravity.CENTER))

        try {
            windowManager?.addView(root, params)
            attachRetries = 0
            markOnCurrentStack()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add quick wake FAB", e)
            scheduleAttachRetry()
        }
    }

    /**
     * Put a detached disc back into WM. The coordinator's remove+add is not atomic:
     * when the add half throws, the view is out of WM while the service still runs,
     * and nothing else would ever re-add it. A fresh add is already the top
     * same-type window, so no restack is needed afterwards.
     *
     * [View.isAttachedToWindow] can stay false after a successful [WindowManager.addView]
     * (same Mali/EGL lag as the browser overlay). Asking WM via updateViewLayout
     * is the source of truth; a second addView would throw "already been added".
     */
    private fun ensureAttached(): Boolean {
        val root = fabRoot ?: return false
        val params = windowParams ?: return false
        val wm = windowManager ?: return false
        if (isRegisteredWithWindowManager(wm, root, params)) {
            attachRetries = 0
            return true
        }
        // View can still report attached after WM dropped the token. A second
        // addView then throws; peel the dead root first.
        if (root.isAttachedToWindow) {
            runCatching { wm.removeView(root) }
        }
        return try {
            wm.addView(root, params)
            attachRetries = 0
            markOnCurrentStack()
            Log.w(TAG, "Quick wake FAB re-attached")
            true
        } catch (e: IllegalStateException) {
            if (e.message?.contains("already been added") == true) {
                attachRetries = 0
                true
            } else {
                Log.e(TAG, "Failed to re-add quick wake FAB", e)
                scheduleAttachRetry()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-add quick wake FAB", e)
            scheduleAttachRetry()
            false
        }
    }

    private fun isRegisteredWithWindowManager(
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
    ): Boolean = runCatching { wm.updateViewLayout(view, params) }.isSuccess

    private fun scheduleAttachRetry() {
        attachRetries = (attachRetries + 1).coerceAtMost(ATTACH_RETRY_MAX)
        handler.removeCallbacks(attachRetryRunnable)
        handler.postDelayed(attachRetryRunnable, ATTACH_RETRY_MS * attachRetries)
    }

    private fun scheduleHealth() {
        handler.removeCallbacks(healthRunnable)
        handler.postDelayed(healthRunnable, HEALTH_MS)
    }

    /**
     * Service still wants the disc, but WM no longer has it (overlay burst,
     * mid-drag restack, token death). Put it back. Does not fight AOD yield
     * or an in-flight restack.
     */
    private fun healDiscIfMissing(scheduleNext: Boolean = false) {
        if (scheduleNext) scheduleHealth()
        if (fabView?.restacking == true) return
        if (isUserMovingFab()) return
        OverlayZOrderCoordinator.syncFabForAod()
        if (aodYielded) return
        val root = fabRoot ?: return
        if (!ensureAttached()) return
        if (root.visibility != View.VISIBLE) {
            root.visibility = View.VISIBLE
        }
        val params = windowParams ?: return
        if (params.width <= 0 || params.height <= 0) applyWindow()
    }

    private fun isUserMovingFab(): Boolean {
        val root = fabRoot as? GestureRoot
        return root?.isInteracting() == true || physicsRunning
    }

    /** Push the current params. Re-adds the disc if the token died under a move. */
    private fun pushWindowLayout(): Boolean {
        val root = fabRoot ?: return false
        val params = windowParams ?: return false
        val wm = windowManager ?: return false
        if (isRegisteredWithWindowManager(wm, root, params)) {
            attachRetries = 0
            return true
        }
        return ensureAttached()
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * Esper / captions / ripple / pinned app windows share [OverlayZOrderCoordinator.voiceSublayerFlags].
     * Same-type overlays without FLAG_FULLSCREEN live in another WM bucket — remove+add
     * of the mic never climbs them.
     */
    private fun voiceLayerWindowFlags(touchable: Boolean): Int {
        var flags = OverlayZOrderCoordinator.voiceSublayerFlags()
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /** Same edge-to-edge contract as the vinyl FAB: draw into the nav / status / cutout. */
    private fun WindowManager.LayoutParams.applyIgnoreSystemBars() {
        PlatformCapabilities.applyDisplayCutoutShortEdges(this)
        OverlayOrientation.apply(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(0)
        }
    }

    /**
     * Owns the full touch stream for the window. Returns true on DOWN so every following
     * MOVE / UP arrives here regardless of the (non-clickable) disc child.
     */
    @SuppressLint("ViewConstructor")
    private inner class GestureRoot(
        context: Context,
        private val disc: QuickWakeFabView,
    ) : FrameLayout(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var downRawX = 0f
        private var downRawY = 0f
        private var startCenterX = 0f
        private var startCenterY = 0f
        private var downAt = 0L
        private var maxMoved = 0f
        private var tracking = false
        private var dragging = false
        /**
         * "Picked up": the finger rested ≥ [DRAG_ARM_MS] without moving. Only now does
         * motion move the button at normal slop. Before that, small wander is still a
         * tap (a thumb never lands perfectly still) and a clear swipe is a drag —
         * including idle after Just stop. Still-release after pickup is still a tap.
         */
        private var pickedUp = false
        /** HOLD mode: long press started a push-to-talk recording. */
        private var holding = false

        fun isInteracting(): Boolean = tracking || dragging

        /** TAP mode, [DRAG_ARM_MS] after DOWN: lift the disc — it is now draggable. */
        private val pickupRunnable = Runnable {
            if (!tracking || dragging || holding) return@Runnable
            pickedUp = true
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            disc.setPressed(false)
            disc.setDragging(true)
        }

        /** HOLD mode: recording starts after the rim arc finishes. */
        private val recordRunnable = Runnable {
            if (!tracking || dragging) return@Runnable
            if (isVoiceConnecting()) {
                disc.cancelArm()
                return@Runnable
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (micMuted) {
                VoiceSatelliteService.setMicMute(false)
                disc.cancelArm()
                disc.playTapBurst()
                return@Runnable
            }
            if (!QuickWakePushToTalk.begin()) {
                disc.cancelArm()
                return@Runnable
            }
            holding = true
            disc.setRecording(true)
        }

        /** HOLD mode: press squash first, then the arm — brushes never fill the rim. */
        private val holdAckRunnable = Runnable {
            if (!tracking || dragging || holding) return@Runnable
            if (isVoiceConnecting()) return@Runnable
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (!micMuted) disc.startArm(HOLD_ARM_MS)
            handler.postDelayed(recordRunnable, HOLD_ARM_MS)
        }

        init {
            isClickable = false
            isFocusable = false
        }

        private fun clearTimers() {
            handler.removeCallbacks(pickupRunnable)
            handler.removeCallbacks(holdAckRunnable)
            handler.removeCallbacks(recordRunnable)
        }

        private fun abandonGesture() {
            // Finger skated off before anything armed: neither tap nor drag. Quietly reset.
            tracking = false
            clearTimers()
            disc.setPressed(false)
            disc.setDragging(false)
            disc.cancelArm()
        }

        /**
         * A WM restack detaches this root; the in-flight touch stream dies with it and
         * no UP / CANCEL ever arrives. Treat the detach as the finger lifting.
         */
        override fun onDetachedFromWindow() {
            if (tracking) {
                clearTimers()
                if (holding) finishHold()
                if (dragging) {
                    dragging = false
                    finishDrag()
                }
                tracking = false
                pickedUp = false
                disc.setPressed(false)
                disc.setDragging(false)
                disc.cancelArm()
            }
            super.onDetachedFromWindow()
            // Restack add can fail after this detach; heal on the next turn.
            handler.post { healDiscIfMissing() }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isInsideHitArea(ev.x, ev.y)) return false
                    tracking = true
                    dragging = false
                    pickedUp = false
                    holding = false
                    maxMoved = 0f
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startCenterX = centerX
                    startCenterY = centerY
                    downAt = SystemClock.elapsedRealtime()
                    clearTimers()
                    disc.setPressed(true)
                    // DOWN is not a decision. A live turn used to abort here, so a
                    // reposition cut STT/TTS before MOVE could become a drag.
                    attachRetries = 0
                    healStaleChromeIfIdle()
                    val connecting = isVoiceConnecting()
                    val liveTurn = isAssistTurnActive()
                    when {
                        // Wait-mic / pending listen: only drag. Tap would Just-stop the
                        // handshake; hold would start a recording into the void.
                        connecting -> handler.postDelayed(pickupRunnable, DRAG_ARM_MS)
                        liveTurn -> handler.postDelayed(pickupRunnable, DRAG_ARM_MS)
                        trigger == QuickWakeTrigger.TAP -> handler.postDelayed(pickupRunnable, DRAG_ARM_MS)
                        trigger == QuickWakeTrigger.HOLD ->
                            handler.postDelayed(holdAckRunnable, HOLD_GUARD_MS)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    if (!dragging) {
                        val dx = abs(ev.rawX - downRawX)
                        val dy = abs(ev.rawY - downRawY)
                        val moved = max(dx, dy)
                        if (moved > maxMoved) maxMoved = moved
                        when {
                            // TAP, picked up: normal slop starts the drag.
                            pickedUp && moved > touchSlop -> beginDrag()
                            // Recording: hands wander while talking; a clear swipe
                            // only moves the button. The hold stays live until UP.
                            holding && moved > touchSlop * 3 -> beginDrag()
                            // HOLD, arc still filling: the arc *is* the stall. Stay still and it
                            // becomes a recording; move and it becomes a drag. No dead zone.
                            trigger == QuickWakeTrigger.HOLD && !holding && moved > touchSlop * 1.5f -> beginDrag()
                            // TAP, before pickup: a clear swipe is always a drag.
                            // Idle-after-Just-stop used to abandon the stream and stick.
                            trigger == QuickWakeTrigger.TAP &&
                                QuickWakeFabGestures.shouldDragOnTapWander(
                                    pickedUp = pickedUp,
                                    maxMovedPx = moved,
                                    touchSlopPx = touchSlop.toFloat(),
                                    wanderMul = TAP_WANDER_MUL,
                                ) -> beginDrag()
                        }
                    }
                    if (dragging) {
                        dragTo(
                            startCenterX + (ev.rawX - downRawX),
                            startCenterY + (ev.rawY - downRawY),
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!tracking) return false
                    tracking = false
                    clearTimers()
                    disc.setPressed(false)
                    disc.setDragging(false)
                    disc.cancelArm()
                    val isUp = ev.actionMasked == MotionEvent.ACTION_UP
                    when {
                        holding -> {
                            // Drag during a hold is reposition, not abort. Finger up
                            // (or a stolen CANCEL) still sends, same as a still hold.
                            if (dragging) {
                                dragging = false
                                finishDrag()
                            }
                            finishHold()
                        }
                        dragging -> {
                            dragging = false
                            finishDrag()
                        }
                        // Still-release is tap even after pickup. Connecting still
                        // reaches onTap so a second tap can Just-stop the in-flight listen.
                        isUp && !dragging && !holding &&
                            (trigger == QuickWakeTrigger.TAP || isAssistTurnActive()) -> {
                            val heldMs = SystemClock.elapsedRealtime() - downAt
                            val liveTurn = isAssistTurnActive()
                            if (QuickWakeFabGestures.shouldCommitTap(
                                    liveTurn = liveTurn,
                                    maxMovedPx = maxMoved,
                                    touchSlopPx = touchSlop.toFloat(),
                                    heldMs = heldMs,
                                    minTapMs = MIN_TAP_MS,
                                    insideHit = isInsideHitArea(ev.x, ev.y),
                                )
                            ) {
                                onTap()
                            }
                        }
                    }
                    pickedUp = false
                    return true
                }
            }
            return false
        }

        private fun finishHold() {
            if (!holding) return
            holding = false
            disc.setRecording(false)
            QuickWakePushToTalk.release()
            disc.playSendBurst()
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        /**
         * PTT ended without this touch stream (2 min cap, cancel). Drop the
         * local hold so a later UP does not send again.
         */
        fun releaseHoldFromExternal() {
            if (!holding) return
            holding = false
            disc.setRecording(false)
        }

        private fun beginDrag() {
            dragging = true
            clearTimers()
            cancelTapCommit()
            disc.cancelArm()
            disc.setPressed(false)
            disc.setDragging(true)
            dragFromDock = dockAmount() > 0.45f
            dragEdge = activeEdge()
            ensureDragShell()
            lastDragAt = 0L
            dragVx = 0f
            dragVy = 0f
        }
    }

    // -- Gestures --

    private var tapCommitScheduled = false
    private val tapCommitRunnable = Runnable {
        tapCommitScheduled = false
        val view = fabView ?: return@Runnable
        view.setPendingListen(true)
        VoiceSatelliteService.quickWake()
    }

    private fun cancelTapCommit() {
        if (!tapCommitScheduled) return
        tapCommitScheduled = false
        handler.removeCallbacks(tapCommitRunnable)
    }

    /**
     * Gesture chrome: drag-vs-hold and tap-to-abort while a turn is painted.
     * Not the same as [VoiceSatelliteService.isAssistTurnActive] — the disc can
     * still be LISTENING/SPEAKING after the satellite is already idle.
     */
    private fun isAssistTurnActive(): Boolean =
        fabView?.isPendingListen() == true ||
            visualState == VisualState.LISTENING ||
            visualState == VisualState.ATTENDING ||
            visualState == VisualState.PROCESSING ||
            visualState == VisualState.SPEAKING ||
            visualState == VisualState.RECORDING ||
            VoiceSatelliteService.isAssistTurnActive()

    /**
     * Wait-mic dots (HA not up) or the post-tap orbit still waiting for Listening.
     * Starting a new listen / hold must wait. Drag and tap-to-close stay live.
     */
    private fun isVoiceConnecting(): Boolean =
        QuickWakeFabGestures.shouldBlockVoiceTrigger(
            uplinkReady = fabView?.isLinked() == true,
            awaitingListen = tapCommitScheduled || fabView?.isPendingListen() == true,
        )

    /**
     * Satellite already idle, but the disc still paints a turn. The session
     * collector is [distinctUntilChanged] and will not fire again while HA
     * stays Connected — so chrome must be reset here, not waited out.
     */
    private fun healStaleChromeIfIdle() {
        if (VoiceSatelliteService.isAssistTurnActive()) return
        if (tapCommitScheduled || QuickWakePushToTalk.isHolding) return
        if (fabView?.isPendingListen() == true) return
        if (visualState != VisualState.IDLE) setVisualState(VisualState.IDLE)
    }

    private fun abortTurn() {
        val view = fabView ?: return
        cancelTapCommit()
        hideCaption()
        view.setPendingListen(false)
        view.playRestartBurst()
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (VoiceSatelliteService.isAssistTurnActive()) {
            VoiceSatelliteService.stopVoiceSession()
        } else {
            setVisualState(VisualState.IDLE)
        }
    }

    private fun onTap() {
        val view = fabView ?: return
        val awaitingListen = tapCommitScheduled || view.isPendingListen()
        val liveTurn = VoiceSatelliteService.isAssistTurnActive()
        when {
            QuickWakeFabGestures.shouldCloseOnTap(
                awaitingListen = awaitingListen,
                assistTurnActive = liveTurn,
            ) -> abortTurn()
            micMuted -> {
                view.playTapBurst()
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                VoiceSatelliteService.setMicMute(false)
            }
            view.isLinked() -> {
                healStaleChromeIfIdle()
                hideCaption()
                view.playTapBurst()
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tapCommitScheduled = true
                handler.postDelayed(tapCommitRunnable, TAP_COMMIT_MS)
            }
        }
    }

    private fun isInsideHitArea(x: Float, y: Float): Boolean {
        return fabView?.hitShape(x, y, dp(HIT_MARGIN_DP).toFloat()) ?: false
    }

    // -- Observers --

    private fun observeSettings() {
        scope.launch {
            microphoneStore.getFlow().collect { settings ->
                trigger = settings.quickWakeTrigger
                if (micMuted != settings.muted) {
                    micMuted = settings.muted
                    fabView?.setMuted(settings.muted)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSessionState() {
        scope.launch {
            // Re-resolve the service on every start/stop so a dead instance is never held.
            VoiceSatelliteService.isRunning
                .flatMapLatest { running ->
                    if (running) {
                        VoiceSatelliteService.getInstance()?.voiceSatelliteState ?: flowOf(Stopped)
                    } else {
                        flowOf(Stopped)
                    }
                }
                .distinctUntilChanged()
                .collect { state ->
                    // Hybrid wake-word listen stays still (sphere / ripple own that
                    // chapter). Processing and TTS share the button-turn chrome:
                    // outer orbit, then inner playback bars.
                    val ownsTurn = VoiceSatelliteService.isQuickWakeSessionActive()
                    sessionListening = ownsTurn && state == Listening
                    lastSatState = state
                    fabView?.setLinked(
                        state !is Disconnected &&
                            state !is Stopped &&
                            state !is ServerError
                    )
                    val inVoice = state == Listening || state == Processing || state == Responding
                    // Speaking latch survives Processing ↔ Responding flaps. Drop it only
                    // on a new listen or when the turn has actually left chrome.
                    if (state == Listening || !inVoice) {
                        ttsAudible = false
                    }
                    if (ownsTurn && inVoice) captionTurnActive = true
                    if (state == Listening) {
                        handler.removeCallbacks(abandonCaptionTurnRunnable)
                        hideCaption()
                        heardSttThisTurn = false
                        ttsCaptionSettledThisTurn = false
                        sttCaptionDoneThisTurn = false
                        captionLingerArmed = false
                        captionTurnActive = ownsTurn
                    } else if (!inVoice) {
                        // Idle / Connected. Disc chrome may rest; the STT bubble must not.
                        // Fast music returns here before STT_END. Local AI stays Responding
                        // until the reply plate, then [noteTtsCaptionSettled] arms linger.
                        keepSttCaptionThroughIdle()
                    }
                    // Yield latch: set on wake-word STT, held through the reply, released
                    // when the session leaves. A button press mid-turn takes it back.
                    val inTurn = inVoice
                    val wantYield = when {
                        !inTurn -> false
                        ownsTurn -> false
                        state == Listening -> true
                        else -> yieldedTurn
                    }
                    if (wantYield != yieldedTurn) {
                        yieldedTurn = wantYield
                        fabView?.setYielded(wantYield)
                    }
                    if (QuickWakePushToTalk.isHolding) return@collect
                    // Restart paints pending spin/bars. Must still leave LISTENING /
                    // SPEAKING so the outer level is not kept alive on Connected
                    // (mic fallback would keep drawing ticks).
                    if (fabView?.isPendingListen() == true &&
                        state != Listening &&
                        state != Processing &&
                        state != Responding
                    ) {
                        setVisualState(VisualState.IDLE)
                        return@collect
                    }
                    applyTurnVisual(state, ownsTurn)
                }
        }
    }

    private fun observePushToTalk() {
        scope.launch {
            QuickWakePushToTalk.holding.collect { holding ->
                if (holding) {
                    hideCaption()
                    setVisualState(VisualState.RECORDING)
                } else {
                    // 2min cap / external cancel end the hold without a touch UP.
                    (fabRoot as? GestureRoot)?.releaseHoldFromExternal()
                    fabView?.setRecording(false)
                    if (visualState == VisualState.RECORDING) {
                        setVisualState(if (sessionListening) VisualState.LISTENING else VisualState.IDLE)
                    }
                }
            }
        }
    }

    /**
     * Transcript for turns this button started; wake-word turns keep their own captions.
     * The bubble is the STT half of the button-turn caption style (the reply uses the
     * bottom caption slot), so it follows the same user switch: captions off → no bubble.
     */
    private fun observeTranscript() {
        scope.launch {
            VoiceSatelliteService.sttText
                .map { it.trim() }
                .collect { text ->
                    if (text.isEmpty()) return@collect
                    val buttonTurn = VoiceSatelliteService.isQuickWakeSessionActive() || captionTurnActive
                    if (!buttonTurn) return@collect
                    // Latch before the caption gate: VAD_END already set Processing, and a
                    // fast TTS_START / music-idle may have left Responding before this runs.
                    heardSttThisTurn = true
                    captionTurnActive = true
                    handler.removeCallbacks(abandonCaptionTurnRunnable)
                    if (!QuickWakePushToTalk.isHolding) {
                        applyTurnVisual(lastSatState, ownsTurn = true)
                    }
                    if (!captionsEnabled) return@collect
                    if (!canShowSttCaption()) return@collect
                    attachCaptionNow(text)
                }
        }
        scope.launch {
            playerStore.getFlow()
                .map { it.enableFloatingWindow }
                .distinctUntilChanged()
                .collect { enabled ->
                    captionsEnabled = enabled
                    if (enabled) {
                        // Cold start: TTS plate, then the STT bubble. The mic climb
                        // already queued by the plate's addView lands last.
                        // Neither window is created on the press.
                        FloatingWindowService.prewarm(this@QuickWakeFabService)
                        ensureCaptionParked()
                    } else {
                        hideCaption(animated = false)
                        releaseCaptionWindow()
                        FloatingWindowService.release(this@QuickWakeFabService)
                    }
                }
        }
    }

    private fun setVisualState(state: VisualState) {
        if (visualState == state) return
        visualState = state
        fabView?.setVisualState(state)
    }

    /**
     * Button-turn chrome. HA enters Processing at VAD_END — before STT_END.
     * The disc goes PROCESSING then; the outer level must fade out and hide
     * (not stay, not snap off). Playback bars wait on [ttsAudible].
     */
    private fun applyTurnVisual(state: Any?, ownsTurn: Boolean) {
        if (QuickWakePushToTalk.isHolding) {
            setVisualState(VisualState.RECORDING)
            return
        }
        setVisualState(
            when {
                state == Listening ->
                    if (ownsTurn) VisualState.LISTENING else VisualState.ATTENDING
                state == Processing || state == Responding ->
                    if (ttsAudible) VisualState.SPEAKING else VisualState.PROCESSING
                else -> VisualState.IDLE
            },
        )
    }

    // -- Transcript bubble --

    /** Which bubble corner touches the disc; that corner is drawn tight so the two read as one. */
    private enum class BubbleAnchor { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT }

    private var bubbleAnchor = BubbleAnchor.BOTTOM_RIGHT

    /**
     * This turn's transcript may land after TTS_START (fast pipelines process both
     * events before the SharedFlow collector runs). Responding / audible TTS must
     * not drop it — the bubble is meant to sit next to the reply. Only refuse once
     * this turn has already taken the bubble down after the reply settled.
     */
    private fun canShowSttCaption(): Boolean {
        if (sttCaptionDoneThisTurn || captionLingerArmed || captionDismissing) return false
        if (captionTurnActive) return true
        val sat = lastSatState
        return sat == null || sat == Listening || sat == Processing || sat == Responding
    }

    private fun attachCaptionNow(text: String) {
        if (!canShowSttCaption()) return
        captionDismissing = false
        val wm = windowManager ?: return
        val view = captionView ?: TextView(this).also { tv ->
            styleCaption(tv)
            captionView = tv
        }
        view.text = wrapSttCaption(view, text)
        val params = captionParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            voiceLayerWindowFlags(touchable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = R.style.AppWindowNoAnimation
            applyIgnoreSystemBars()
            OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(this)
        }.also { captionParams = it }

        val attached = view.isAttachedToWindow
        positionCaption(view, params)
        view.animate().cancel()
        if (attached && captionNeedsClimb) {
            // Still invisible. One queue climb, then the fade — the first
            // painted frame is already in the voice slot. Later turns only fade.
            captionNeedsClimb = false
            view.alpha = 0f
            view.visibility = View.INVISIBLE
            restackCaption()
        }
        captionParked = false
        view.visibility = View.VISIBLE
        try {
            if (attached) {
                wm.updateViewLayout(view, params)
                if (view.alpha < 1f) view.animate().alpha(1f).setDuration(CAPTION_FADE_MS).start()
            } else {
                view.alpha = 0f
                view.translationY = dp(6f).toFloat()
                wm.addView(view, params)
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(CAPTION_FADE_MS)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "caption attach failed", e)
        }
        if (AppWindowService.hasWindowAttached()) {
            OverlayZOrderCoordinator.raiseVoiceAboveAppWindows()
        }
        // Safety net: a reply that never arrives (pipeline error) must not leave it up forever.
        handler.removeCallbacks(hideCaptionRunnable)
        val sat = lastSatState
        val alreadyIdle = sat != null && sat != Listening && sat != Processing && sat != Responding
        if (ttsCaptionSettledThisTurn || alreadyIdle) {
            armCaptionLinger()
        } else {
            handler.postDelayed(hideCaptionRunnable, CAPTION_MAX_HOLD_MS)
        }
    }

    /**
     * Satellite chrome is idle. Do not [hideCaption] — wait until the transcript is
     * on screen, then linger. Local AI never hits this until after the reply plate
     * because it stays on Responding through [playLocalReply].
     */
    private fun keepSttCaptionThroughIdle() {
        handler.removeCallbacks(abandonCaptionTurnRunnable)
        if (sttCaptionDoneThisTurn || captionDismissing) {
            captionTurnActive = false
            return
        }
        if (!captionTurnActive && !heardSttThisTurn) return
        if (captionIsShowing()) {
            armCaptionLinger()
            return
        }
        handler.postDelayed(abandonCaptionTurnRunnable, CAPTION_MAX_HOLD_MS)
    }

    private fun armCaptionLinger() {
        if (captionLingerArmed || captionDismissing) return
        val view = captionView
        if (view == null || !captionIsShowing()) return
        captionLingerArmed = true
        handler.removeCallbacks(hideCaptionRunnable)
        handler.postDelayed(hideCaptionRunnable, CAPTION_LINGER_AFTER_TTS_MS)
    }

    /** On screen, not the cold-start park. A parked window stays attached on purpose. */
    private fun captionIsShowing(): Boolean {
        val view = captionView ?: return false
        return view.isAttachedToWindow && !captionParked && view.visibility == View.VISIBLE
    }

    /**
     * Button is up and captions are on: put the STT bubble in WM now, invisible.
     * The first transcript reuses it. Leaving button mode or captions removes it.
     */
    private fun ensureCaptionParked() {
        if (captionView?.isAttachedToWindow == true) return
        val wm = windowManager ?: return
        val view = TextView(this).also { tv ->
            styleCaption(tv)
            tv.text = ""
            tv.alpha = 0f
            tv.visibility = View.INVISIBLE
            captionView = tv
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            voiceLayerWindowFlags(touchable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = R.style.AppWindowNoAnimation
            applyIgnoreSystemBars()
            OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(this)
        }
        captionParams = params
        try {
            wm.addView(view, params)
            captionParked = true
            captionNeedsClimb = true
            captionDismissing = false
        } catch (e: Exception) {
            Log.w(TAG, "caption prewarm failed", e)
            captionView = null
            captionParams = null
            captionParked = false
        }
    }

    /** Feature hidden, or the button service is going away: drop the parked window. */
    private fun releaseCaptionWindow() {
        handler.removeCallbacks(hideCaptionRunnable)
        handler.removeCallbacks(abandonCaptionTurnRunnable)
        captionDismissing = false
        captionLingerArmed = false
        captionParked = false
        captionNeedsClimb = false
        captionTurnActive = false
        val view = captionView ?: return
        view.animate().cancel()
        if (view.isAttachedToWindow) {
            runCatching { windowManager?.removeView(view) }
        }
        captionView = null
        captionParams = null
    }

    private fun hideCaption(animated: Boolean = true) {
        handler.removeCallbacks(hideCaptionRunnable)
        handler.removeCallbacks(abandonCaptionTurnRunnable)
        if (captionIsShowing() || captionDismissing) {
            sttCaptionDoneThisTurn = true
        }
        captionLingerArmed = false
        captionTurnActive = false
        val view = captionView ?: return
        if (!view.isAttachedToWindow) {
            captionDismissing = false
            captionParked = false
            return
        }
        if (captionParked && view.visibility != View.VISIBLE) {
            captionDismissing = false
            return
        }
        if (!animated) {
            view.animate().cancel()
            view.alpha = 0f
            view.visibility = View.INVISIBLE
            captionParked = true
            captionDismissing = false
            return
        }
        // Already on its way out: let that fade finish rather than restart the clock.
        if (captionDismissing) return
        captionDismissing = true
        view.animate().cancel()
        // Alpha only. A translate on a WRAP_CONTENT window clips the plate edge as it
        // leaves, which reads as a flicker rather than a fade.
        view.translationY = 0f
        view.animate()
            .alpha(0f)
            .setDuration(CAPTION_EXIT_MS)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                if (captionDismissing && view.isAttachedToWindow) {
                    view.alpha = 0f
                    view.visibility = View.INVISIBLE
                    captionParked = true
                }
                captionDismissing = false
            }
            .start()
    }

    private fun styleCaption(tv: TextView) {
        val scale = screenScaleFactor()
        tv.setTextColor(0xFFF4F5F7.toInt())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale.coerceAtMost(1.35f))
        tv.setLineSpacing(0f, 1.15f)
        tv.maxLines = STT_BUBBLE_MAX_LINES
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.includeFontPadding = false
        tv.setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
        tv.maxWidth = captionMaxWidthPx(tv)
        applyBubbleShape(tv)
    }

    private fun applyBubbleShape(tv: TextView) {
        val big = dp(18f).toFloat()
        val tight = dp(4f).toFloat()
        // cornerRadii order: TL, TL, TR, TR, BR, BR, BL, BL
        val radii = when (bubbleAnchor) {
            BubbleAnchor.BOTTOM_RIGHT -> floatArrayOf(big, big, big, big, tight, tight, big, big)
            BubbleAnchor.BOTTOM_LEFT -> floatArrayOf(big, big, big, big, big, big, tight, tight)
            BubbleAnchor.TOP_RIGHT -> floatArrayOf(big, big, tight, tight, big, big, big, big)
            BubbleAnchor.TOP_LEFT -> floatArrayOf(tight, tight, big, big, big, big, big, big)
        }
        val current = tv.background as? GradientDrawable
        val bg = current ?: GradientDrawable()
        bg.cornerRadii = radii
        bg.setColor(0x99181A1C.toInt())
        bg.setStroke(
            dp(1f).coerceAtLeast(1),
            android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt()),
        )
        if (current == null) tv.background = bg
        // Lines grow out of the tail so a short last line still sits on the kiss.
        tv.gravity = when (bubbleAnchor) {
            BubbleAnchor.BOTTOM_RIGHT, BubbleAnchor.TOP_RIGHT -> Gravity.END
            BubbleAnchor.BOTTOM_LEFT, BubbleAnchor.TOP_LEFT -> Gravity.START
        }
    }

    private fun captionEmPx(tv: TextView): Float =
        tv.paint.measureText("字").coerceAtLeast(1f)

    /** One line is [STT_BUBBLE_UNITS_PER_LINE] CJK-em; long STT wraps here, not across the screen. */
    private fun captionMaxWidthPx(tv: TextView): Int {
        val line = (captionEmPx(tv) * STT_BUBBLE_UNITS_PER_LINE).roundToInt()
        return line + tv.paddingLeft + tv.paddingRight
    }

    private fun sttCharUnits(ch: Char): Float =
        if (ch.code >= 0x2E80) 1f else 0.55f

    /** Hard-wrap long transcripts so each line stays next to the mic. */
    private fun wrapSttCaption(tv: TextView, raw: String): String {
        val text = raw.trim().replace('\n', ' ')
        if (text.isEmpty()) return text
        val limit = STT_BUBBLE_UNITS_PER_LINE
        val lines = ArrayList<String>(STT_BUBBLE_MAX_LINES)
        val line = StringBuilder()
        var units = 0f
        var consumed = 0
        for (ch in text) {
            val u = sttCharUnits(ch)
            if (line.isNotEmpty() && units + u > limit) {
                lines += line.toString()
                line.clear()
                units = 0f
                if (lines.size == STT_BUBBLE_MAX_LINES) break
            }
            if (lines.size == STT_BUBBLE_MAX_LINES) break
            if (line.isEmpty() && ch == ' ') {
                consumed++
                continue
            }
            line.append(ch)
            units += u
            consumed++
        }
        if (line.isNotEmpty() && lines.size < STT_BUBBLE_MAX_LINES) {
            lines += line.toString()
        }
        if (consumed < text.length && lines.isNotEmpty()) {
            val last = lines.last()
            lines[lines.lastIndex] = if (last.length <= 1) "…" else last.dropLast(1) + "…"
        }
        tv.maxWidth = captionMaxWidthPx(tv)
        return lines.joinToString("\n")
    }

    /**
     * Tail sits just inside the D inner face (or circle rim), above the mic.
     */
    private fun positionCaption(view: TextView, params: WindowManager.LayoutParams) {
        val (sw, sh) = realScreenSize()
        val maxW = captionMaxWidthPx(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = view.measuredWidth
        val h = view.measuredHeight
        val edge = dp(8f)
        val (swShape, shShape) = shapeSize()
        val docked = dockAmount() > 0.45f
        val edgeNow = edgeOf(centerX, centerY)
        // Just outside the inner face so the plate does not sit on the mic.
        val gap = dp(6f).toFloat()
        val lift = dp(10f).toFloat()
        val verticalDock = docked && isVerticalEdge(edgeNow)
        val rim = when {
            verticalDock -> shShape / 2f
            docked -> swShape / 2f
            else -> min(swShape, shShape) / 2f
        }
        if (verticalDock) {
            val extendRight = centerX + gap + w <= sw - edge || centerX < sw / 2f
            val kissPad = dp(18f).toFloat()
            val x = if (extendRight) centerX - kissPad else centerX - w + kissPad
            val y = if (edgeNow == FabEdge.TOP) centerY + rim + gap else centerY - rim - gap - h
            val anchor = when {
                edgeNow == FabEdge.TOP && extendRight -> BubbleAnchor.TOP_LEFT
                edgeNow == FabEdge.TOP -> BubbleAnchor.TOP_RIGHT
                extendRight -> BubbleAnchor.BOTTOM_LEFT
                else -> BubbleAnchor.BOTTOM_RIGHT
            }
            if (anchor != bubbleAnchor) {
                bubbleAnchor = anchor
                applyBubbleShape(view)
            }
            params.x = x.roundToInt().coerceIn(edge, max(edge, sw - w - edge))
            params.y = y.roundToInt().coerceIn(edge, max(edge, sh - h - edge))
            return
        }
        // Docked: always the inner face. Free: the side that still fits.
        val attachLeft = if (docked) edgeNow == FabEdge.RIGHT else centerX - rim - gap - w >= edge
        val kissX = if (attachLeft) centerX - rim - gap else centerX + rim + gap

        val above = centerY - h - lift
        val below = centerY + lift
        val fitsTop = above >= edge

        val anchor = when {
            attachLeft && fitsTop -> BubbleAnchor.BOTTOM_RIGHT
            !attachLeft && fitsTop -> BubbleAnchor.BOTTOM_LEFT
            attachLeft && !fitsTop -> BubbleAnchor.TOP_RIGHT
            else -> BubbleAnchor.TOP_LEFT
        }
        val x = if (attachLeft) kissX - w else kissX
        val y = when (anchor) {
            BubbleAnchor.BOTTOM_RIGHT, BubbleAnchor.BOTTOM_LEFT -> above
            BubbleAnchor.TOP_RIGHT, BubbleAnchor.TOP_LEFT -> below
        }
        if (anchor != bubbleAnchor) {
            bubbleAnchor = anchor
            applyBubbleShape(view)
        }
        params.x = x.roundToInt().coerceIn(edge, max(edge, sw - w - edge))
        params.y = y.roundToInt().coerceIn(edge, max(edge, sh - h - edge))
    }

    private fun repositionCaptionIfShown() {
        val view = captionView ?: return
        val params = captionParams ?: return
        if (!view.isAttachedToWindow) return
        positionCaption(view, params)
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    // -- Geometry / persistence --

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun isDarkMode(): Boolean =
        homePrefs.getBoolean(com.example.ava.ui.screens.home.KEY_DARK_MODE, false)

    private fun screenScaleFactor(): Float {
        // Physical short side, not configuration dp — landscape and foldables
        // otherwise kept the last portrait size until a process restart.
        val (sw, sh) = realScreenSize()
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val minDp = min(sw, sh) / density
        return (minDp / 360f).coerceIn(1f, 1.9f)
    }

    private fun discSizePx(): Int = dp(FAB_SIZE_DP * screenScaleFactor())
    private fun windowSizePx(): Int = dp(WINDOW_SIZE_DP * screenScaleFactor())
    private fun handleWpx(): Int = dp(HANDLE_W_DP * screenScaleFactor())
    private fun handleHpx(): Int = dp(HANDLE_H_DP * screenScaleFactor())
    private fun hangPx(): Float = dp(HANDLE_HANG_DP * screenScaleFactor()).toFloat()
    private fun morphPx(): Float = dp(MORPH_RANGE_DP * screenScaleFactor()).toFloat()
    private fun restInset(): Float = handleWpx() / 2f - hangPx()

    private fun realScreenSize(): Pair<Int, Int> {
        // Vinyl / weather / wake-ripple: physical display, including the nav-bar strip.
        // currentWindowMetrics.bounds can be inset, which parked the D above the bar.
        val metrics = android.util.DisplayMetrics()
        val wm = windowManager
        if (wm != null) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                wm.defaultDisplay.getRealMetrics(metrics)
            } else {
                wm.defaultDisplay.getMetrics(metrics)
            }
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun isVerticalEdge(edge: FabEdge): Boolean =
        edge == FabEdge.TOP || edge == FabEdge.BOTTOM

    /** Long-handle clearance. Inside this on two axes, a D would hang off the screen. */
    private fun alongLimit(): Float =
        handleHpx() / 2f + dp(8f * screenScaleFactor()).toFloat()

    private fun edgeOf(x: Float, y: Float): FabEdge {
        val (sw, sh) = realScreenSize()
        var best = FabEdge.LEFT
        var bestDist = x
        val right = sw - x
        if (right < bestDist) {
            best = FabEdge.RIGHT
            bestDist = right
        }
        if (y < bestDist) {
            best = FabEdge.TOP
            bestDist = y
        }
        if (sh - y < bestDist) best = FabEdge.BOTTOM
        return best
    }

    private fun distTo(edge: FabEdge, x: Float, y: Float): Float {
        val (sw, sh) = realScreenSize()
        return when (edge) {
            FabEdge.LEFT -> x
            FabEdge.RIGHT -> sw - x
            FabEdge.TOP -> y
            FabEdge.BOTTOM -> sh - y
        }
    }

    /**
     * Edge the shape is melting toward. A settle in flight keeps the edge it
     * was released on, so a corner slide does not flip the D mid-spring.
     */
    private fun activeEdge(x: Float = centerX, y: Float = centerY): FabEdge =
        if (physicsRunning && physicsSoftX) physicsEdge else edgeOf(x, y)

    /** Both axes inside the long-handle clearance — stay a circle. */
    private fun inCornerPocket(x: Float, y: Float): Boolean {
        val (sw, sh) = realScreenSize()
        val pocket = alongLimit()
        return min(x, sw - x) < pocket && min(y, sh - y) < pocket
    }

    /** Distance from the shape centre to the nearest screen edge. */
    private fun nearestEdgeDist(x: Float = centerX, y: Float = centerY): Float {
        val (sw, sh) = realScreenSize()
        return min(min(x, sw - x), min(y, sh - y))
    }

    /** Dock amount from raw distance, including a corner. Release uses this to snap. */
    private fun rawDock(x: Float, y: Float): Float {
        val dist = nearestEdgeDist(x, y)
        return (1f - (dist - restInset()) / morphPx()).coerceIn(0f, 1f)
    }

    /**
     * Morph only in a tight band against the nearest edge.
     * Farther than that the control stays a circle — a wide interpolating D
     * sitting "near" the edge was neither a handle nor a disc. A corner pocket
     * stays a circle too; the handle would hang off the adjacent edge.
     */
    private fun dockAmount(x: Float = centerX, y: Float = centerY): Float {
        if (!(physicsRunning && physicsToEdge) && inCornerPocket(x, y)) return 0f
        val dist = distTo(activeEdge(x, y), x, y)
        return (1f - (dist - restInset()) / morphPx()).coerceIn(0f, 1f)
    }

    private fun shapeSizeFor(t: Float, vertical: Boolean): Pair<Float, Float> {
        val disc = discSizePx().toFloat()
        val thin = disc + (handleWpx() - disc) * t
        val long = disc + (handleHpx() - disc) * t
        return if (vertical) long to thin else thin to long
    }

    private fun shapeSize(t: Float = dockAmount()): Pair<Float, Float> =
        shapeSizeFor(t, t > 0f && isVerticalEdge(activeEdge()))

    private fun ringPadPx(t: Float = dockAmount()): Float {
        val full = (windowSizePx() - discSizePx()) / 2f
        val docked = dp(20f * screenScaleFactor()).toFloat()
        return full + (docked - full) * t
    }

    private fun shellSize(t: Float, vertical: Boolean): Pair<Int, Int> {
        val (w, h) = shapeSizeFor(t, vertical)
        val pad = ringPadPx(t)
        return (w + pad * 2f).roundToInt() to (h + pad * 2f).roundToInt()
    }

    /**
     * Overlay box is the max of the free circle and both D orientations so
     * morphing never resizes the WindowManager window — that size flip was the edge jump.
     */
    private fun overlayShellSize(): Pair<Int, Int> {
        val (w0, h0) = shellSize(0f, false)
        val (wSide, hSide) = shellSize(1f, false)
        val (wFlat, hFlat) = shellSize(1f, true)
        return max(w0, max(wSide, wFlat)) to max(h0, max(hSide, hFlat))
    }

    /** Seat on [edge] at [inset] from that edge, kept clear of the other two. */
    private fun seatOnEdge(edge: FabEdge, x: Float, y: Float, inset: Float): Pair<Float, Float> {
        val (sw, sh) = realScreenSize()
        val half = alongLimit()
        val minX = half
        val maxX = max(half, sw - half)
        val minY = half
        val maxY = max(half, sh - half)
        return when (edge) {
            FabEdge.LEFT -> inset to y.coerceIn(minY, maxY)
            FabEdge.RIGHT -> (sw - inset) to y.coerceIn(minY, maxY)
            FabEdge.TOP -> x.coerceIn(minX, maxX) to inset
            FabEdge.BOTTOM -> x.coerceIn(minX, maxX) to (sh - inset)
        }
    }

    private fun clampCenter(overshootPx: Float = 0f) {
        val (sw, sh) = realScreenSize()
        val depth = restInset()
        // A settle eases out of a corner. Clamping the long axis here would pop.
        val sliding = physicsRunning && physicsToEdge
        val docked = !sliding && dockAmount() > 0.02f
        val vertical = docked && isVerticalEdge(activeEdge())
        val half = alongLimit()
        val minX: Float
        val maxX: Float
        val minY: Float
        val maxY: Float
        if (vertical) {
            minX = half
            maxX = sw - half
            minY = depth - overshootPx
            maxY = sh - depth + overshootPx
        } else if (docked) {
            minX = depth - overshootPx
            maxX = sw - depth + overshootPx
            minY = half
            maxY = sh - half
        } else {
            minX = depth - overshootPx
            maxX = sw - depth + overshootPx
            minY = depth - overshootPx
            maxY = sh - depth + overshootPx
        }
        centerX = centerX.coerceIn(minX, max(minX, maxX))
        centerY = centerY.coerceIn(minY, max(minY, maxY))
    }

    private fun applyWindow(moveOnly: Boolean = false) {
        fabRoot ?: return
        val params = windowParams ?: return
        val t = dockAmount()
        val (winW, winH) = if (moveOnly) {
            params.width to params.height
        } else {
            overlayShellSize()
        }
        val x = (centerX - winW / 2f).roundToInt()
        val y = (centerY - winH / 2f).roundToInt()
        if (moveOnly && x == params.x && y == params.y) {
            fabView?.setDock(t, activeEdge())
            return
        }
        drawX = x
        drawY = y
        val sizeChanged = !moveOnly && (params.width != winW || params.height != winH)
        if (!moveOnly) {
            params.width = winW
            params.height = winH
        }
        params.x = x
        params.y = y
        fabView?.let { view ->
            if (!moveOnly) view.setShapeMetrics(discSizePx(), handleWpx(), handleHpx())
            view.setDock(t, activeEdge())
            if (sizeChanged) {
                view.layoutParams = FrameLayout.LayoutParams(winW, winH, Gravity.CENTER)
            }
        }
        pushWindowLayout()
        if (!moveOnly) repositionCaptionIfShown()
    }

    /**
     * Grow the overlay to the free-circle box once at pickup so D→circle is paint-only.
     * Resizing the window mid-peel is the hitch.
     */
    private fun ensureDragShell() {
        fabRoot ?: return
        val params = windowParams ?: return
        val (wantW, wantH) = overlayShellSize()
        val w = max(params.width, wantW)
        val h = max(params.height, wantH)
        if (w == params.width && h == params.height) return
        params.width = w
        params.height = h
        params.x = (centerX - w / 2f).roundToInt()
        params.y = (centerY - h / 2f).roundToInt()
        drawX = params.x
        drawY = params.y
        fabView?.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        pushWindowLayout()
    }

    private fun magnetToward(pos: Float, wall: Float, range: Float): Float {
        val d = abs(pos - wall)
        if (d >= range) return pos
        val t = 1f - d / range
        return pos + (wall - pos) * t * t * t * MAGNET_PULL
    }

    /** Soft pull toward the nearest wall. A corner has no winner, so the finger stays free. */
    private fun magnetPoint(x: Float, y: Float): Pair<Float, Float> {
        if (inCornerPocket(x, y)) return x to y
        val (sw, sh) = realScreenSize()
        val rest = restInset()
        val range = dp(MAGNET_RANGE_DP * screenScaleFactor()).toFloat()
        return when (edgeOf(x, y)) {
            FabEdge.LEFT -> magnetToward(x, rest, range) to y
            FabEdge.RIGHT -> magnetToward(x, sw - rest, range) to y
            FabEdge.TOP -> x to magnetToward(y, rest, range)
            FabEdge.BOTTOM -> x to magnetToward(y, sh - rest, range)
        }
    }

    private fun sampleVelocity(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (lastDragAt > 0L) {
            val dt = ((now - lastDragAt) / 1000f).coerceAtLeast(0.004f)
            if (dt < 0.08f) {
                dragVx = dragVx * 0.35f + ((x - lastDragX) / dt) * 0.65f
                dragVy = dragVy * 0.35f + ((y - lastDragY) / dt) * 0.65f
            }
        }
        lastDragAt = now
        lastDragX = x
        lastDragY = y
    }

    private fun dragTo(fingerX: Float, fingerY: Float) {
        stopPhysics()
        sampleVelocity(fingerX, fingerY)
        val leaving = if (dragFromDock) {
            val (sw, sh) = realScreenSize()
            val rest = restInset()
            val inward = when (dragEdge) {
                FabEdge.RIGHT -> (sw - rest) - fingerX
                FabEdge.LEFT -> fingerX - rest
                FabEdge.BOTTOM -> (sh - rest) - fingerY
                FabEdge.TOP -> fingerY - rest
            }
            inward > dp(1f).toFloat()
        } else {
            false
        }
        // Peel follows the finger with no wall magnet, so the D melts instead of popping.
        if (leaving) {
            centerX = fingerX
            centerY = fingerY
        } else {
            val (mx, my) = magnetPoint(fingerX, fingerY)
            centerX = mx
            centerY = my
        }
        clampCenter()
        fabView?.setPull(0f)
        if (dockAmount() < 0.02f) dragFromDock = false
        applyWindow(moveOnly = true)
    }

    private fun finishDrag() {
        dragFromDock = false
        fabView?.setPull(0f)
        val fromX = centerX
        val fromY = centerY
        // Snap only to the edge the finger is already on, and only when it is
        // clearly parked. Farther than that, ease back to a circle — a half-D
        // frozen in the morph band was the old "too sticky" magnet.
        // rawDock includes a corner: releasing there still seats on the nearer edge.
        val edge = edgeOf(fromX, fromY)
        val dock = rawDock(fromX, fromY)
        val toEdge = dock > SNAP_DOCK
        physicsEdge = edge
        physicsToEdge = toEdge
        physicsSoftX = toEdge || dock > 0.02f
        val alongVx = dragVx.coerceIn(-1600f, 1600f)
        val alongVy = dragVy.coerceIn(-1600f, 1600f)
        if (toEdge) {
            val (tx, ty) = seatOnEdge(edge, fromX, fromY, restInset())
            physicsTx = tx
            physicsTy = ty
            if (isVerticalEdge(edge)) {
                physicsVx = alongVx
                physicsVy = 0f
            } else {
                physicsVx = 0f
                physicsVy = alongVy
            }
        } else if (dock > 0.02f) {
            val clear = restInset() + morphPx() + dp(2f).toFloat()
            val (tx, ty) = seatOnEdge(edge, fromX, fromY, clear)
            physicsTx = tx
            physicsTy = ty
            if (isVerticalEdge(edge)) {
                physicsVx = alongVx
                physicsVy = 0f
            } else {
                physicsVx = 0f
                physicsVy = alongVy
            }
        } else {
            physicsTx = fromX
            physicsTy = fromY
            physicsVx = alongVx
            physicsVy = alongVy
        }
        centerX = fromX
        centerY = fromY
        startPhysics()
    }

    private fun startPhysics() {
        physicsRunning = true
        lastPhysicsNanos = 0L
        Choreographer.getInstance().removeFrameCallback(physicsCallback)
        Choreographer.getInstance().postFrameCallback(physicsCallback)
    }

    private fun stopPhysics() {
        if (!physicsRunning) return
        physicsRunning = false
        Choreographer.getInstance().removeFrameCallback(physicsCallback)
    }

    /**
     * Soft droplet settle: almost critically damped, slow.
     * Docking never overshoots the wall — that pop was the edge jump.
     */
    private fun stepPhysics(dt: Float): Boolean {
        val vertical = physicsSoftX && isVerticalEdge(physicsEdge)
        val softX = physicsSoftX && !vertical
        val stiffX = if (softX) SPRING_STIFF * 0.52f else SPRING_STIFF
        val dampX = if (softX) SPRING_DAMP * 1.7f else SPRING_DAMP
        val stiffY = if (vertical) SPRING_STIFF * 0.52f else SPRING_STIFF
        val dampY = if (vertical) SPRING_DAMP * 1.7f else SPRING_DAMP
        val ax = -stiffX * (centerX - physicsTx) - dampX * physicsVx
        val ay = -stiffY * (centerY - physicsTy) - dampY * physicsVy
        physicsVx += ax * dt
        physicsVy += ay * dt
        centerX += physicsVx * dt
        centerY += physicsVy * dt
        if (physicsToEdge) {
            when (physicsEdge) {
                FabEdge.RIGHT -> if (centerX >= physicsTx) {
                    centerX = physicsTx
                    physicsVx = 0f
                }
                FabEdge.LEFT -> if (centerX <= physicsTx) {
                    centerX = physicsTx
                    physicsVx = 0f
                }
                FabEdge.BOTTOM -> if (centerY >= physicsTy) {
                    centerY = physicsTy
                    physicsVy = 0f
                }
                FabEdge.TOP -> if (centerY <= physicsTy) {
                    centerY = physicsTy
                    physicsVy = 0f
                }
            }
            clampCenter(0f)
        } else {
            clampCenter(dp(8f * screenScaleFactor()).toFloat())
        }
        val settled =
            abs(centerX - physicsTx) < 1.2f &&
                abs(centerY - physicsTy) < 1.2f &&
                hypot(physicsVx, physicsVy) < 28f
        if (settled) {
            centerX = physicsTx
            centerY = physicsTy
            physicsVx = 0f
            physicsVy = 0f
        }
        return !settled
    }

    private fun moveCenterTo(x: Float, y: Float) {
        stopPhysics()
        centerX = x
        centerY = y
        clampCenter()
        applyWindow()
    }

    private fun refreshDrawPosition() {
        val (sw, sh) = realScreenSize()
        if (normX >= 0f && normY >= 0f) {
            centerX = normX * sw
            centerY = normY * sh
        } else {
            centerX = sw - restInset()
            centerY = sh * 0.56f
        }
        clampCenter()
        snapToNearestHome()
    }

    /**
     * A seat in the morph band is not a home — the D looks jammed "near" the wall.
     * Commit to the rest inset or clear out to a circle.
     */
    private fun snapToNearestHome() {
        val dock = rawDock(centerX, centerY)
        if (dock <= 0.02f) return
        val edge = edgeOf(centerX, centerY)
        val rest = restInset()
        val inset = if (dock >= SNAP_DOCK || nearestEdgeDist() <= rest + morphPx() * 0.45f) {
            rest
        } else {
            rest + morphPx() + dp(2f).toFloat()
        }
        val (x, y) = seatOnEdge(edge, centerX, centerY, inset)
        centerX = x
        centerY = y
        clampCenter()
    }

    private fun rememberScreenSize() {
        val (sw, sh) = realScreenSize()
        lastScreenW = sw
        lastScreenH = sh
    }

    private fun relayoutForConfiguration() {
        if (fabRoot == null) return
        val (sw, sh) = realScreenSize()
        if (sw == lastScreenW && sh == lastScreenH && !relayoutRetryPending) {
            relayoutRetryPending = true
            handler.removeCallbacks(relayoutRunnable)
            handler.postDelayed(relayoutRunnable, 64L)
            return
        }
        relayoutRetryPending = false
        stopPhysics()
        dragFromDock = false
        fabView?.setPull(0f)
        // Keep the same relative seat on the new screen. Reloading `_land` prefs
        // jumped back to an old landscape park instead of following the rotate.
        if (lastScreenW > 0 && lastScreenH > 0 && (sw != lastScreenW || sh != lastScreenH)) {
            normX = (centerX / lastScreenW).coerceIn(0f, 1f)
            normY = (centerY / lastScreenH).coerceIn(0f, 1f)
        }
        lastScreenW = sw
        lastScreenH = sh
        fabView?.setShapeMetrics(discSizePx(), handleWpx(), handleHpx())
        refreshDrawPosition()
        applyWindow()
        persistPosition()
        captionView?.let { styleCaption(it) }
        repositionCaptionIfShown()
        OverlayZOrderCoordinator.syncFabForAod()
    }

    private fun loadPosition() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val suffix = orientationSuffix()
        normX = prefs.getFloat(KEY_NORM_X + suffix, prefs.getFloat(KEY_NORM_X, -1f))
        normY = prefs.getFloat(KEY_NORM_Y + suffix, prefs.getFloat(KEY_NORM_Y, -1f))
    }

    private fun persistPosition() {
        val (sw, sh) = realScreenSize()
        normX = if (sw > 0) centerX / sw else 0f
        normY = if (sh > 0) centerY / sh else 0f
        val suffix = orientationSuffix()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_NORM_X + suffix, normX)
            .putFloat(KEY_NORM_Y + suffix, normY)
            .apply()
    }

    private fun orientationSuffix(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else ""

    /**
     * Climb when [OverlayZOrderCoordinator.stackGeneration] moved — another
     * overlay add/restack landed on top. Skip when it has not: a guess that
     * "something might have buried us" was the mic-glyph flash on every
     * Esper / Feishu / toast hide.
     */
    fun bringToFront() {
        if (OverlayZOrderCoordinator.shouldYieldVoiceToAod()) {
            yieldToAod()
            return
        }
        if (OverlayZOrderCoordinator.shouldSkipVoiceClimb()) return
        if (isUserMovingFab()) return
        restoreFromAod()
        if (isOnCurrentStack()) return
        val root = fabRoot ?: return
        // Out of WM (a previous add half failed): a fresh add is already topmost.
        // In WM but attach still pending: restack would no-op — climb next frame.
        if (!root.isAttachedToWindow) {
            if (!ensureAttached()) return
            if (!root.isAttachedToWindow) {
                if (!attachClimbPosted) {
                    attachClimbPosted = true
                    root.post {
                        attachClimbPosted = false
                        if (fabRoot === root && root.isAttachedToWindow) bringToFront()
                    }
                }
                return
            }
        }
        if (root.visibility != View.VISIBLE) {
            restackNow()
            return
        }
        OverlayRaiseCover.run(windowManager, root, windowParams, restack = { restackDisc() })
        restackCaptionMasked()
    }

    private fun restackNow() {
        restackDisc()
        restackCaption()
    }

    private fun restackDisc(): Boolean {
        // Keep the disc's animators alive across the detach: a z-order move
        // must not read as a state change on the disc.
        fabView?.restacking = true
        val raised = try {
            OverlayZOrderCoordinator.bringToFront(
                windowManager, fabRoot, windowParams, TAG,
                isVoiceWindow = true,
                mask = false,
            )
        } finally {
            fabView?.restacking = false
        }
        // remove succeeded, add threw: the disc is gone from WM — put it back now.
        if (!raised && fabRoot?.isAttachedToWindow == false) ensureAttached()
        val ok = fabRoot?.isAttachedToWindow == true
        if (ok) markOnCurrentStack()
        return ok
    }

    private fun isOnCurrentStack(): Boolean {
        val root = fabRoot ?: return false
        return !aodYielded &&
            root.isAttachedToWindow &&
            root.visibility == View.VISIBLE &&
            stackedAtGeneration == OverlayZOrderCoordinator.stackGeneration
    }

    private fun markOnCurrentStack() {
        stackedAtGeneration = OverlayZOrderCoordinator.stackGeneration
    }

    private fun restackCaptionMasked() {
        // Parked STT stays in the slot it was given. Climbing it on every mic
        // raise is a remove+add of a window the user cannot see.
        if (captionParked) return
        val view = captionView ?: return
        val params = captionParams ?: return
        if (!view.isAttachedToWindow) return
        OverlayRaiseCover.run(windowManager, view, params, restack = { restackCaption() })
    }

    private fun restackCaption(): Boolean {
        val view = captionView ?: return false
        val params = captionParams ?: return false
        if (!view.isAttachedToWindow) return false
        // Detach+attach cancels the fade and freezes the plate mid-alpha.
        val resumeExit = captionDismissing
        if (resumeExit) view.animate().cancel()
        val ok = OverlayZOrderCoordinator.bringToFront(
            windowManager, view, params, TAG,
            isVoiceWindow = true,
            mask = false,
        )
        if (ok && resumeExit) {
            captionDismissing = false
            hideCaption(animated = true)
        }
        return ok
    }

    /**
     * Esper / caption overlay just (re)attached above us. Join their fullscreen
     * WM bucket first — otherwise remove+add stays under that window forever.
     */
    fun raiseAboveVoiceOverlay() {
        if (ScreenBlankOverlay.isShowing()) return
        if (OverlayZOrderCoordinator.shouldYieldVoiceToAod()) {
            yieldToAod()
            return
        }
        if (OverlayZOrderCoordinator.shouldSkipVoiceClimb()) return
        restoreFromAod()
        if (isUserMovingFab()) return
        val flagsChanged = syncVoiceLayerFlags()
        if (isOnCurrentStack()) {
            if (flagsChanged) applyVoiceLayerFlags()
            return
        }
        bringToFront()
    }

    private fun syncVoiceLayerFlags(): Boolean {
        var changed = false
        windowParams?.let { params ->
            val keepNotTouchable =
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            val before = params.flags
            val beforeCutout = PlatformCapabilities.displayCutoutMode(params)
            val beforeAlpha = params.alpha
            params.flags = voiceLayerWindowFlags(touchable = !keepNotTouchable)
            params.applyIgnoreSystemBars()
            OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(params)
            if (params.flags != before ||
                PlatformCapabilities.displayCutoutMode(params) != beforeCutout ||
                params.alpha != beforeAlpha
            ) {
                changed = true
            }
        }
        captionParams?.let { params ->
            val before = params.flags
            val beforeCutout = PlatformCapabilities.displayCutoutMode(params)
            val beforeAlpha = params.alpha
            params.flags = voiceLayerWindowFlags(touchable = false)
            params.applyIgnoreSystemBars()
            OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(params)
            if (params.flags != before ||
                PlatformCapabilities.displayCutoutMode(params) != beforeCutout ||
                params.alpha != beforeAlpha
            ) {
                changed = true
            }
        }
        return changed
    }

    private fun applyVoiceLayerFlags() {
        val root = fabRoot
        val params = windowParams
        if (root != null && params != null && root.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(root, params) }
        }
        val caption = captionView
        val capParams = captionParams
        if (caption != null && capParams != null && caption.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(caption, capParams) }
        }
    }

    /** Hide the mic without tearing the window so an AOD plate can cover it. */
    fun yieldToAod() {
        if (aodYielded) return
        val root = fabRoot ?: return
        val params = windowParams ?: return
        aodYielded = true
        root.visibility = View.INVISIBLE
        captionView?.visibility = View.INVISIBLE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(params)
        if (root.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(root, params) }
        }
        handler.removeCallbacks(aodRecheckRunnable)
        handler.postDelayed(aodRecheckRunnable, AOD_RECHECK_MS)
        Log.i(TAG, "Voice FAB yield to AOD")
    }

    /**
     * The AOD plate went away without anyone restacking (screensaver dismissed
     * straight to the launcher, blank plate lifted by a touch). Without this the
     * disc stays INVISIBLE until the next unrelated raise.
     */
    private fun recheckAod() {
        if (!aodYielded) return
        if (ScreenBlankOverlay.isShowing() || OverlayZOrderCoordinator.shouldYieldVoiceToAod()) {
            handler.postDelayed(aodRecheckRunnable, AOD_RECHECK_MS)
            return
        }
        raiseAboveVoiceOverlay()
    }

    fun restoreFromAod() {
        if (!aodYielded) return
        handler.removeCallbacks(aodRecheckRunnable)
        aodYielded = false
        val root = fabRoot
        val params = windowParams
        if (root == null || params == null) {
            Log.i(TAG, "Voice FAB restore skipped (no window)")
            return
        }
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(params)
        root.visibility = View.VISIBLE
        if (!captionParked) captionView?.visibility = View.VISIBLE
        if (root.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(root, params) }
                .onFailure { Log.w(TAG, "Voice FAB restore layout failed", it) }
        }
        if (!root.isAttachedToWindow) ensureAttached()
        Log.i(TAG, "Voice FAB restore from AOD yield")
    }

    // -- The disc itself --

    @SuppressLint("ViewConstructor")
    private inner class QuickWakeFabView(
        context: Context,
        private var darkMode: Boolean,
        discSizePx: Int,
        private val levelProvider: () -> Float,
    ) : View(context) {

        private val density = context.resources.displayMetrics.density
        private var discPx = discSizePx
        private var handleWpx = discSizePx
        private var handleHpx = discSizePx
        private var dock = 0f
        private var dockEdge = FabEdge.RIGHT
        private var jellyX = 1f
        private var jellyY = 1f
        private var pull = 0f
        private val shapePath = Path()
        private val shapeRect = RectF()
        private val radii = FloatArray(8)
        private val orbitPath = Path()
        private val orbitSeg = Path()
        private val orbitRest = Path()
        private val orbitRect = RectF()
        private val orbitRadii = FloatArray(8)
        private val orbitMeasure = PathMeasure()
        private val orbitPos = FloatArray(2)
        private val orbitTan = FloatArray(2)

        private var glassTop = 0
        private var glassBottom = 0

        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val micIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic_24)?.mutate()
        private val micOffIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic_off_24)?.mutate()
        private val micWaitIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic_wait_24)?.mutate()
        private val waitDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = INK
        }

        private var pressed = false
        private var dragging = false
        private var muted = false
        private var recording = false
        private var state = VisualState.IDLE

        private var rippleProgress = -1f
        private var rippleAnimator: ValueAnimator? = null
        private var spinAnimator: ValueAnimator? = null
        private var spinFadeAnimator: ValueAnimator? = null
        private var spinAngle = 0f
        private var spinAlpha = 0f
        private var spinWanted = false

        /** Disc scale: 0.92 pressed, 1.10 recording, springs back through 1.0 on release. */
        private var discScale = 1f
        private var scaleAnimator: ValueAnimator? = null
        /**
         * Session-level size, multiplied with the gesture scale. 1 = full; [YIELD_SCALE]
         * while a wake-word turn has the sphere on stage. Docked D keeps its flush edge
         * ([buildShapePath] pins it), so it reads as a smaller handle, not a floating one.
         */
        private var yieldScale = 1f
        private var yieldAnimator: ValueAnimator? = null
        private var yielded = false
        /** Inner glyph: a small bow on yield, settles slightly small while yielded. */
        private var iconYieldScale = 1f
        private var iconYieldAnimator: ValueAnimator? = null
        /**
         * HA uplink. 0 = wait mic + cycling stand dots, 1 = the live mic.
         * Cross-fades so a connect / drop is a beat, not a snap.
         */
        private var linked = false
        private var linkMix = 0f
        private var linkAnimator: ValueAnimator? = null
        private var iconLinkScale = 1f
        private var iconLinkAnimator: ValueAnimator? = null
        private var dotsPhase = 0f
        private var dotsAnimator: ValueAnimator? = null
        /** Hold mode pre-arm: rim arc 0..1 across the long-press window. */
        private var armProgress = 0f
        private var armAnimator: ValueAnimator? = null
        /** 0 = mic glyph, 1 = record dot; cross-fades on hold start / end. */
        private var iconMix = 0f
        private var iconMixAnimator: ValueAnimator? = null
        /** 0 = mic glyph, 1 = listening bars (tap-started turn); also lifts the disc style. */
        private var barsMix = 0f
        private var barsMixAnimator: ValueAnimator? = null
        /**
         * Outer mic-level ticks. Processing / idle fade this to 0 so the ring hides
         * instead of being cut by the orbit or left up through the wait.
         */
        private var meterFade = 0f
        private var meterFadeAnimator: ValueAnimator? = null
        /**
         * Tap acknowledged, HA not yet listening. Bars + orbit arc show immediately so the
         * user sees cause and effect; resolved by the first real session state, or by the
         * timeout below, which shakes the disc and drops it back to the mic.
         */
        private var pendingListen = false
        private var pendingSinceMs = 0L
        private val pendingTimeoutRunnable = Runnable {
            if (!pendingListen) return@Runnable
            setPendingListen(false)
            animateBarsMix(0f)
            stopSpin()
            playShake()
            this@QuickWakeFabService.setVisualState(VisualState.IDLE)
        }
        private var shakeAnimator: ValueAnimator? = null
        private var dotPulse = 0f
        private var dotPulseAnimator: ValueAnimator? = null
        /** Send burst after a hold release: -1 idle, else 0..1 arrow flight. */
        private var sendProgress = -1f
        private var sendAnimator: ValueAnimator? = null

        // Voice-level ring. Level arrives two ways: pushed per uplink frame from the
        // satellite ([pushLevel], the authoritative source while a turn streams) and, as a
        // fallback before the uplink opens, polled from the mic meter via [levelProvider].
        private var waveRunning = false
        private var waveWanted = false
        /**
         * Which meter the running wave belongs to. Rim ticks are mic only: a playback
         * tail decaying after TTS must never be drawn on the rim, and a mic tail must
         * not carry into the first TTS bars.
         */
        private var waveIsMic = true
        @Volatile private var pushedLevel = 0f
        @Volatile private var pushedAtNanos = 0L
        private var smoothLevel = 0f
        private var waveClock = 0f
        private val barPhase = FloatArray(BAR_COUNT) { i -> (i * 2.399f) % 6.2832f } // golden-angle spread
        private val barRate = FloatArray(BAR_COUNT) { i -> 2.6f + ((i * 7) % 5) * 0.35f }
        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!waveRunning) return
                // Pushed frames are already on the app's perceptual mic scale (speech ≈ 0.4–1).
                // A stale push (> 250 ms, e.g. uplink not open yet) falls back to the meter,
                // whose linear float RMS needs a dB map: -46 dB → 0, -14 dB → 1.
                val pushFresh = frameTimeNanos - pushedAtNanos < 250_000_000L
                val speaking = state == VisualState.SPEAKING
                val shaped = when {
                    // TTS bars follow playback only. Leftover mic push is cleared on
                    // enter; PCM may push again, URL lives on PlaybackEnergyMonitor.
                    speaking -> {
                        val play = PlaybackEnergyMonitor.currentLevel().coerceIn(0f, 1f)
                        when {
                            play > 0.015f -> play
                            pushFresh -> sqrt(pushedLevel.coerceIn(0f, 1f))
                            else -> 0f
                        }
                    }
                    pushFresh -> sqrt(pushedLevel.coerceIn(0f, 1f))
                    else -> {
                        val raw = levelProvider().coerceIn(0f, 1f)
                        val db = 20f * log10(max(raw, 1e-4f))
                        ((db + 46f) / 32f).coerceIn(0f, 1f)
                    }
                }
                if (!waveWanted) {
                    // Chapter left: ~0.5 s fall, then the loop parks itself.
                    smoothLevel *= 0.86f
                    waveClock += 1f / 60f
                    invalidate()
                    if (smoothLevel < 0.012f) {
                        waveRunning = false
                        smoothLevel = 0f
                        return
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                    return
                }
                // Soft attack / slower decay so speech motion eases instead of kicking.
                smoothLevel = if (shaped > smoothLevel) {
                    smoothLevel + (shaped - smoothLevel) * 0.22f
                } else {
                    smoothLevel * 0.93f
                }
                waveClock += 1f / 60f
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun pushLevel(level: Float) {
            pushedLevel = level
            pushedAtNanos = System.nanoTime()
        }

        init {
            applyPalette()
        }

        fun setDarkMode(dark: Boolean) {
            if (darkMode == dark) return
            darkMode = dark
            applyPalette()
            invalidate()
        }

        private fun applyPalette() {
            if (darkMode) {
                glassTop = GLASS_DARK_TOP
                glassBottom = GLASS_DARK_BOTTOM
            } else {
                glassTop = GLASS_LIGHT_TOP
                glassBottom = GLASS_LIGHT_BOTTOM
            }
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        private fun liftAlpha(color: Int, add: Float): Int {
            val a = Color.alpha(color)
            return withAlpha(color, (a + (255 - a) * add).toInt())
        }

        private fun muteAlpha(color: Int): Int =
            withAlpha(color, (Color.alpha(color) * 0.82f).toInt())

        /**
         * Outer meter chrome: a faint grey stroke sitting on a hair of shadow.
         * Used only for the processing orbit and the level bars — never for inner glyphs.
         */
        private fun drawMeterStroke(
            canvas: Canvas,
            widthPx: Float,
            alpha: Int,
            block: (Paint) -> Unit,
        ) {
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeCap = Paint.Cap.ROUND
            ringPaint.strokeWidth = widthPx
            ringPaint.color = Color.BLACK
            ringPaint.alpha = (alpha * 0.22f).toInt().coerceIn(0, 70)
            canvas.save()
            canvas.translate(0f, 0.8f * density)
            block(ringPaint)
            canvas.restore()
            ringPaint.color = METER_GRAY
            ringPaint.alpha = alpha.coerceIn(0, 255)
            block(ringPaint)
        }

        fun setShapeMetrics(disc: Int, handleW: Int, handleH: Int) {
            if (discPx == disc && handleWpx == handleW && handleHpx == handleH) return
            discPx = disc
            handleWpx = handleW
            handleHpx = handleH
            invalidate()
        }

        fun setDock(amount: Float, edge: FabEdge) {
            val t = amount.coerceIn(0f, 1f)
            if (abs(dock - t) < 0.002f && dockEdge == edge) return
            dock = t
            dockEdge = edge
            invalidate()
        }

        fun setJelly(sx: Float, sy: Float) {
            if (abs(jellyX - sx) < 0.002f && abs(jellyY - sy) < 0.002f) return
            jellyX = sx
            jellyY = sy
            invalidate()
        }

        fun setPull(t: Float) {
            val v = t.coerceIn(0f, 1f)
            if (abs(pull - v) < 0.002f) return
            pull = v
            invalidate()
        }

        fun pullExtraPx(): Float = pull * 16f * density

        fun setDiscSizePx(px: Int) {
            discPx = px
            invalidate()
        }

        fun hitShape(x: Float, y: Float, extra: Float): Boolean {
            val (w, h) = currentShape()
            val hw = w / 2f + extra
            val hh = h / 2f + extra
            val cx = width / 2f
            val cy = height / 2f
            val dx = abs(x - cx)
            val dy = abs(y - cy)
            val r = min(hw, hh)
            if (dx <= hw - r && dy <= hh) return true
            if (dy <= hh - r && dx <= hw) return true
            val cx2 = dx - (hw - r)
            val cy2 = dy - (hh - r)
            return cx2 * cx2 + cy2 * cy2 <= r * r
        }

        private fun currentShape(): Pair<Float, Float> {
            val extra = pull * 16f * density
            val vertical = isVerticalEdge(dockEdge)
            val thin = discPx + (handleWpx - discPx) * dock
            val long = discPx + (handleHpx - discPx) * dock
            val bw = if (vertical) long else thin
            val bh = if (vertical) thin else long
            return bw * jellyX + extra to bh * jellyY
        }

        /** Flat bezel corners vs the rounded inner face. Order is TL, TR, BR, BL. */
        private fun writeRadii(out: FloatArray, rIn: Float, rOut: Float) {
            val tl: Float
            val tr: Float
            val br: Float
            val bl: Float
            when (dockEdge) {
                FabEdge.RIGHT -> {
                    tl = rIn; tr = rOut; br = rOut; bl = rIn
                }
                FabEdge.LEFT -> {
                    tl = rOut; tr = rIn; br = rIn; bl = rOut
                }
                FabEdge.TOP -> {
                    tl = rOut; tr = rOut; br = rIn; bl = rIn
                }
                FabEdge.BOTTOM -> {
                    tl = rIn; tr = rIn; br = rOut; bl = rOut
                }
            }
            out[0] = tl; out[1] = tl
            out[2] = tr; out[3] = tr
            out[4] = br; out[5] = br
            out[6] = bl; out[7] = bl
        }

        private fun buildShapePath(cx: Float, cy: Float, w: Float, h: Float) {
            val vertical = isVerticalEdge(dockEdge)
            val thin = discPx + (handleWpx - discPx) * dock
            val long = discPx + (handleHpx - discPx) * dock
            val baseW = if (vertical) long else thin
            val baseH = if (vertical) thin else long
            shapeRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            // Docked or peeling off: pin the outer edge so squash/stretch reads as hitting the wall,
            // not shrinking about the centre.
            if (dock > 0.45f || pull > 0.01f) {
                when (dockEdge) {
                    FabEdge.RIGHT -> {
                        val right = cx + baseW / 2f
                        shapeRect.right = right
                        shapeRect.left = right - w
                    }
                    FabEdge.LEFT -> {
                        val left = cx - baseW / 2f
                        shapeRect.left = left
                        shapeRect.right = left + w
                    }
                    FabEdge.BOTTOM -> {
                        val bottom = cy + baseH / 2f
                        shapeRect.bottom = bottom
                        shapeRect.top = bottom - h
                    }
                    FabEdge.TOP -> {
                        val top = cy - baseH / 2f
                        shapeRect.top = top
                        shapeRect.bottom = top + h
                    }
                }
            }
            val rIn = min(w, h) / 2f
            val rOut = rIn * (1f - dock)
            writeRadii(radii, rIn, rOut)
            shapePath.reset()
            shapePath.addRoundRect(shapeRect, radii, Path.Direction.CW)
        }

        /** Docked / peeling: chrome follows the D silhouette, not a circle around the mic. */
        private fun alongShape(): Boolean = dock > 0.45f

        /**
         * Inflate [shapeRect] by [pad]. The flush bezel edge stays pinned so the orbit
         * traces the D, not a disc that floats off the wall.
         */
        private fun buildOrbitPath(pad: Float) {
            orbitRect.set(shapeRect)
            orbitRect.inset(-pad, -pad)
            if (alongShape()) {
                val hair = 1.2f * density
                when (dockEdge) {
                    FabEdge.RIGHT -> orbitRect.right = shapeRect.right + hair
                    FabEdge.LEFT -> orbitRect.left = shapeRect.left - hair
                    FabEdge.TOP -> orbitRect.top = shapeRect.top - hair
                    FabEdge.BOTTOM -> orbitRect.bottom = shapeRect.bottom + hair
                }
            }
            val ow = orbitRect.width()
            val oh = orbitRect.height()
            val rIn = min(ow, oh) / 2f
            val rOut = rIn * (1f - dock)
            writeRadii(orbitRadii, rIn, rOut)
            orbitPath.reset()
            orbitPath.addRoundRect(orbitRect, orbitRadii, Path.Direction.CW)
        }

        private fun addPathSweep(start: Float, sweep: Float) {
            orbitSeg.reset()
            val len = orbitMeasure.length
            if (len <= 1f || sweep <= 0.5f) return
            val s = ((start % len) + len) % len
            val e = s + sweep.coerceAtMost(len)
            if (e <= len) {
                orbitMeasure.getSegment(s, e, orbitSeg, true)
            } else {
                orbitMeasure.getSegment(s, len, orbitSeg, true)
                orbitRest.reset()
                orbitMeasure.getSegment(0f, e - len, orbitRest, true)
                orbitSeg.addPath(orbitRest)
            }
        }

        private fun drawOrbitSweep(
            canvas: Canvas,
            pad: Float,
            startDeg: Float,
            sweepDeg: Float,
            widthPx: Float,
            alpha: Int,
        ) {
            buildOrbitPath(pad)
            orbitMeasure.setPath(orbitPath, true)
            val len = orbitMeasure.length
            if (len < 4f) return
            val start = (startDeg / 360f) * len
            val sweep = (sweepDeg / 360f).coerceIn(0.02f, 1f) * len
            drawMeterStroke(canvas, widthPx, alpha) { p ->
                addPathSweep(start, sweep)
                canvas.drawPath(orbitSeg, p)
            }
        }

        fun setVisualState(next: VisualState) {
            if (state == next) return
            // The real session state resolves a pending tap. IDLE right after the tap means
            // the pipeline refused (not connected / not subscribed): fail loudly so the user
            // does not keep tapping into a void.
            if (pendingListen) {
                if (next != VisualState.IDLE) {
                    setPendingListen(false)
                } else if (SystemClock.elapsedRealtime() - pendingSinceMs > PENDING_FAIL_FAST_MS) {
                    setPendingListen(false)
                    playShake()
                }
            }
            val from = state
            state = next
            // The record dot is finger-owned; a lost UP (window restacked mid-hold)
            // must not leave it on once the chapter has moved on.
            if (next != VisualState.RECORDING && recording) setRecording(false)
            val listening = next == VisualState.LISTENING || next == VisualState.RECORDING
            val speaking = next == VisualState.SPEAKING
            val fromSpeaking = from == VisualState.SPEAKING
            if ((speaking && !fromSpeaking) || (listening && fromSpeaking)) {
                // Meter swap: mic tail must not kick the first TTS bars, and a TTS tail
                // must not show up on the rim when continuous dialogue re-opens the mic.
                pushedLevel = 0f
                pushedAtNanos = 0L
                smoothLevel = 0f
            }
            if (listening || speaking) startWave(mic = listening) else stopWave()
            if (next == VisualState.PROCESSING || pendingListen) startSpin() else stopSpin()
            // Listen (button turn): bars while the mic is open. Hold keeps the record dot.
            // TTS: same bars, playback energy, overlapping the orbit fade.
            val wantBars = (next == VisualState.LISTENING && !recording) || pendingListen || speaking
            val barsMs = if (speaking && from == VisualState.PROCESSING) 520L else 420L
            animateBarsMix(if (wantBars) 1f else 0f, barsMs)
            // Processing: the rim ticks fade out and hide. Do not snap them off
            // when the orbit starts, and do not keep them at full through the wait.
            fadeMeterTo(if (listening) 1f else 0f, if (listening) 280L else 520L)
            invalidate()
        }

        fun setPendingListen(on: Boolean) {
            if (pendingListen == on) return
            pendingListen = on
            removeCallbacks(pendingTimeoutRunnable)
            if (on) {
                pendingSinceMs = SystemClock.elapsedRealtime()
                postDelayed(pendingTimeoutRunnable, PENDING_TIMEOUT_MS)
                animateBarsMix(1f)
                startSpin()
                stopWave()
            } else {
                stopWave()
            }
            invalidate()
        }

        /** "Didn't take": a short horizontal shudder that decays, then everything is back to idle. */
        private fun playShake() {
            shakeAnimator?.cancel()
            val amp = 4.2f * density
            shakeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480L
                interpolator = null
                addUpdateListener {
                    val t = it.animatedValue as Float
                    translationX = (sin(t * 6.2832f * 3f) * amp * (1f - t))
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        translationX = 0f
                    }
                })
                start()
            }
        }

        private fun animateBarsMix(target: Float, durationMs: Long = 420L) {
            if (barsMix == target && barsMixAnimator == null) return
            barsMixAnimator?.cancel()
            barsMixAnimator = ValueAnimator.ofFloat(barsMix, target).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.3f)
                addUpdateListener {
                    barsMix = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun fadeMeterTo(target: Float, durationMs: Long) {
            if (abs(meterFade - target) < 0.012f && meterFadeAnimator == null) {
                meterFade = target
                return
            }
            meterFadeAnimator?.cancel()
            meterFadeAnimator = ValueAnimator.ofFloat(meterFade, target).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    meterFade = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        meterFadeAnimator = null
                    }
                })
                start()
            }
        }

        override fun setPressed(pressed: Boolean) {
            if (this.pressed == pressed) return
            this.pressed = pressed
            animateScaleToTarget(releaseBounce = !pressed && !recording)
        }

        fun setDragging(value: Boolean) {
            if (dragging == value) return
            dragging = value
            animateScaleToTarget(releaseBounce = false)
        }

        fun setMuted(value: Boolean) {
            if (muted == value) return
            muted = value
            invalidate()
        }

        /**
         * HA / satellite uplink. Offline keeps the wait mic and cycles the three
         * stand dots; a flip either way plays a short glyph handoff.
         */
        fun setLinked(on: Boolean) {
            if (linked == on) return
            linked = on
            if (!on) startWaitDots()
            linkAnimator?.cancel()
            iconLinkAnimator?.cancel()
            val fromMix = linkMix
            val toMix = if (on) 1f else 0f
            linkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LINK_MS
                interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    linkMix = fromMix + (toMix - fromMix) * t
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (cancelled || linkAnimator !== animation) return
                        linkMix = toMix
                        linkAnimator = null
                        if (linked) stopWaitDots()
                    }
                })
                start()
            }
            iconLinkAnimator = if (on) {
                ValueAnimator.ofFloat(iconLinkScale, 0.86f, 1.08f, 1f)
            } else {
                ValueAnimator.ofFloat(iconLinkScale, 0.94f, 1f)
            }.apply {
                duration = if (on) LINK_MS + 80L else LINK_MS
                interpolator = DecelerateInterpolator(1.3f)
                addUpdateListener {
                    iconLinkScale = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (cancelled || iconLinkAnimator !== animation) return
                        iconLinkScale = 1f
                        iconLinkAnimator = null
                    }
                })
                start()
            }
            invalidate()
        }

        /** Hold mode: recording started / ended. Swaps the glyph and lifts the disc. */
        fun setRecording(value: Boolean) {
            if (recording == value) return
            recording = value
            cancelArmInternal(snapToFull = value)
            animateIconMix(if (value) 1f else 0f)
            if (value) startDotPulse() else stopDotPulse()
            animateScaleToTarget(releaseBounce = !value)
        }

        private fun targetScale(): Float = when {
            recording -> 1.05f
            dragging -> 1.03f
            pressed -> 0.96f
            else -> 1f
        }

        /**
         * Step back for a wake-word turn / come back when it ends. Paint-only: the
         * window and hit area keep full size — tap = interrupt must stay easy to land.
         */
        fun setYielded(on: Boolean) {
            if (yielded == on) return
            yielded = on
            yieldAnimator?.cancel()
            iconYieldAnimator?.cancel()
            val fromScale = yieldScale
            if (on) {
                // Ripple is bursting right now; start a beat later so it reads as
                // the wave pushing the disc back. Ease in *and* out — no snap either end.
                yieldAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    startDelay = YIELD_IN_DELAY_MS
                    duration = YIELD_IN_MS
                    interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        yieldScale = fromScale + (YIELD_SCALE - fromScale) * t
                        invalidate()
                    }
                    start()
                }
                // Glyph bows a touch deeper than the disc, then settles slightly small.
                iconYieldAnimator = ValueAnimator.ofFloat(iconYieldScale, 0.82f, ICON_YIELD_SCALE).apply {
                    startDelay = YIELD_IN_DELAY_MS
                    duration = YIELD_IN_MS + 140L
                    interpolator = DecelerateInterpolator(1.3f)
                    addUpdateListener {
                        iconYieldScale = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                // Come back on a soft ease-out: grows, slows, lands. No bounce.
                yieldAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = YIELD_OUT_MS
                    interpolator = android.view.animation.PathInterpolator(0.2f, 0.7f, 0.2f, 1f)
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        yieldScale = fromScale + (1f - fromScale) * t
                        invalidate()
                    }
                    start()
                }
                iconYieldAnimator = ValueAnimator.ofFloat(iconYieldScale, 1.05f, 1f).apply {
                    duration = YIELD_OUT_MS
                    interpolator = DecelerateInterpolator(1.4f)
                    addUpdateListener {
                        iconYieldScale = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }

        private fun animateScaleToTarget(releaseBounce: Boolean) {
            val target = targetScale()
            scaleAnimator?.cancel()
            scaleAnimator = ValueAnimator.ofFloat(discScale, target).apply {
                duration = if (releaseBounce) 420L else 160L
                interpolator = if (releaseBounce) OvershootInterpolator(1.25f) else DecelerateInterpolator()
                addUpdateListener {
                    discScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        /** Hold mode pre-arm arc: fills the rim over the long-press window. */
        fun startArm(durationMs: Long) {
            armAnimator?.cancel()
            armAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = null
                addUpdateListener {
                    armProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun cancelArm() = cancelArmInternal(snapToFull = false)

        private fun cancelArmInternal(snapToFull: Boolean) {
            armAnimator?.cancel()
            armAnimator = null
            if (snapToFull || armProgress <= 0f) {
                armProgress = 0f
                invalidate()
                return
            }
            // Let go early: the arc unwinds instead of vanishing, so the eye reads "not yet".
            armAnimator = ValueAnimator.ofFloat(armProgress, 0f).apply {
                duration = 220L
                interpolator = DecelerateInterpolator(1.3f)
                addUpdateListener {
                    armProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun animateIconMix(target: Float) {
            iconMixAnimator?.cancel()
            iconMixAnimator = ValueAnimator.ofFloat(iconMix, target).apply {
                duration = 360L
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener {
                    iconMix = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun startDotPulse() {
            if (dotPulseAnimator != null) return
            dotPulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    dotPulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopDotPulse() {
            dotPulseAnimator?.cancel()
            dotPulseAnimator = null
            dotPulse = 0f
        }

        private fun startWaitDots() {
            if (dotsAnimator != null) return
            dotsAnimator = ValueAnimator.ofFloat(0f, 3f).apply {
                duration = DOTS_CYCLE_MS
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    dotsPhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopWaitDots() {
            dotsAnimator?.cancel()
            dotsAnimator = null
        }

        /** Tap acknowledgement: bright white ripple leaving the rim. Scale bounce comes from setPressed. */
        fun playTapBurst() {
            playRipple(560L, DecelerateInterpolator(1.8f))
        }

        /**
         * Kill-and-restart: a shorter ripple plus a small scale dip so the disc
         * reads as cutting the old turn, not as a second "hello".
         */
        fun isPendingListen(): Boolean = pendingListen

        fun isLinked(): Boolean = linked

        fun playRestartBurst() {
            sendAnimator?.cancel()
            sendAnimator = null
            sendProgress = -1f
            playRipple(320L, DecelerateInterpolator(1.6f))
            scaleAnimator?.cancel()
            scaleAnimator = ValueAnimator.ofFloat(discScale, 0.93f, 1f).apply {
                duration = 280L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    discScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun playRipple(durationMs: Long, interpolator: android.view.animation.Interpolator) {
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                this.interpolator = interpolator
                addUpdateListener {
                    rippleProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        rippleProgress = -1f
                        invalidate()
                    }
                })
                start()
            }
        }

        /** Hold release: an arrow lifts off the disc and fades — "sent". */
        fun playSendBurst() {
            sendAnimator?.cancel()
            sendAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480L
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    sendProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        sendProgress = -1f
                        invalidate()
                    }
                })
                start()
            }
        }

        /** Stop every animator / frame callback so a removed window stops invalidating. */
        fun release() {
            rippleAnimator?.cancel()
            rippleAnimator = null
            scaleAnimator?.cancel()
            scaleAnimator = null
            armAnimator?.cancel()
            armAnimator = null
            iconMixAnimator?.cancel()
            iconMixAnimator = null
            barsMixAnimator?.cancel()
            barsMixAnimator = null
            meterFadeAnimator?.cancel()
            meterFadeAnimator = null
            sendAnimator?.cancel()
            sendAnimator = null
            shakeAnimator?.cancel()
            shakeAnimator = null
            // Session-level animators (yield / glyph) are *kept*: a WM restack at
            // wake detaches us mid-ease, and killing them here is what made the shrink snap.
            // ValueAnimators run fine detached; [destroy] ends them for real.
            removeCallbacks(pendingTimeoutRunnable)
            pendingListen = false
            translationX = 0f
            jellyX = 1f
            jellyY = 1f
            pull = 0f
            stopDotPulse()
            stopWave(immediate = true)
            stopSpin(immediate = true)
        }

        /** Service teardown: everything, including the session animators [release] spares. */
        fun destroy() {
            release()
            yieldAnimator?.cancel()
            yieldAnimator = null
            iconYieldAnimator?.cancel()
            iconYieldAnimator = null
            linkAnimator?.cancel()
            linkAnimator = null
            iconLinkAnimator?.cancel()
            iconLinkAnimator = null
            stopWaitDots()
        }

        /**
         * Set by the service around a z-order remove+add. The detach/attach pair
         * is then not a lifecycle event: animators keep running (ValueAnimator and
         * the Choreographer loop survive a detach) and the next frame just paints.
         */
        var restacking = false

        override fun onDetachedFromWindow() {
            if (!restacking) release()
            super.onDetachedFromWindow()
        }

        /**
         * A real detach mid-session ran [release], which kills every animator and
         * leaves the mixes wherever they were. Rebuild the drawn state from
         * [state] / [recording] / [pendingListen] so the orbit, level and glyph
         * come back exactly as the chapter says — no half-mic-half-bars residue.
         */
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (restacking) invalidate() else resyncAfterAttach()
        }

        private fun resyncAfterAttach() {
            val listening = state == VisualState.LISTENING || state == VisualState.RECORDING
            val speaking = state == VisualState.SPEAKING
            barsMixAnimator?.cancel()
            barsMixAnimator = null
            barsMix = if ((state == VisualState.LISTENING && !recording) || pendingListen || speaking) 1f else 0f
            meterFadeAnimator?.cancel()
            meterFadeAnimator = null
            meterFade = if (listening) 1f else 0f
            iconMixAnimator?.cancel()
            iconMixAnimator = null
            iconMix = if (recording) 1f else 0f
            armProgress = 0f
            if (recording) startDotPulse() else stopDotPulse()
            if (listening || speaking) startWave(mic = listening)
            // Session size: an ease still in flight keeps going (it survived the detach);
            // only settle when nothing is animating.
            if (yieldAnimator?.isRunning != true) {
                yieldScale = if (yielded) YIELD_SCALE else 1f
            }
            if (iconYieldAnimator?.isRunning != true) {
                iconYieldScale = if (yielded) ICON_YIELD_SCALE else 1f
            }
            if (linkAnimator?.isRunning != true) {
                linkMix = if (linked) 1f else 0f
            }
            if (iconLinkAnimator?.isRunning != true) {
                iconLinkScale = 1f
            }
            if (!linked || linkMix < 0.995f) startWaitDots() else stopWaitDots()
            if (state == VisualState.PROCESSING || pendingListen) {
                startSpin()
                spinFadeAnimator?.cancel()
                spinFadeAnimator = null
                spinAlpha = 1f
            }
            invalidate()
        }

        private fun startWave(mic: Boolean = true) {
            waveIsMic = mic
            waveWanted = true
            if (waveRunning) return
            waveRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        /**
         * Default: let the level fall on its own clock (frame loop decays it and stops
         * itself), so bars and disc breathing land instead of snapping to zero.
         * [immediate] is for teardown / a tap that must repaint now.
         */
        private fun stopWave(immediate: Boolean = false) {
            waveWanted = false
            pushedLevel = 0f
            pushedAtNanos = 0L
            if (immediate || !waveRunning) {
                waveRunning = false
                smoothLevel = 0f
            }
            invalidate()
        }

        private fun startSpin() {
            spinWanted = true
            if (spinAnimator == null) {
                spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1900L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = null
                    addUpdateListener {
                        spinAngle = it.animatedValue as Float
                        if (!waveRunning) waveClock += 1f / 60f
                        // URL / PCM arm the monitor on first audible frame — catch
                        // a missed onTtsPlaybackStarted without leaving the orbit.
                        if (state == VisualState.PROCESSING &&
                            PlaybackEnergyMonitor.isEnabled() &&
                            PlaybackEnergyMonitor.currentLevel() > 0.015f
                        ) {
                            noteTtsAudible()
                        }
                        invalidate()
                    }
                    start()
                }
            }
            fadeSpinTo(1f, 340L)
        }

        private fun stopSpin(immediate: Boolean = false) {
            spinWanted = false
            if (immediate) {
                spinFadeAnimator?.cancel()
                spinFadeAnimator = null
                spinAnimator?.cancel()
                spinAnimator = null
                spinAlpha = 0f
                spinAngle = 0f
                return
            }
            fadeSpinTo(0f, 520L)
        }

        private fun fadeSpinTo(target: Float, durationMs: Long) {
            if (abs(spinAlpha - target) < 0.012f) {
                spinAlpha = target
                if (target <= 0f && !spinWanted) killSpinMotor()
                return
            }
            spinFadeAnimator?.cancel()
            spinFadeAnimator = ValueAnimator.ofFloat(spinAlpha, target).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    spinAlpha = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!spinWanted && spinAlpha <= 0.02f) killSpinMotor()
                    }
                })
                start()
            }
        }

        private fun killSpinMotor() {
            spinAnimator?.cancel()
            spinAnimator = null
            spinAngle = 0f
            spinAlpha = 0f
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val voiceAmp = if (state == VisualState.SPEAKING) 0.038f else 0.016f
            val voiceScale = if (waveRunning) 1f + voiceAmp * smoothLevel else 1f
            val (baseW, baseH) = currentShape()
            val w = baseW * discScale * yieldScale * voiceScale
            val h = baseH * discScale * yieldScale * voiceScale
            if (w <= 0f || h <= 0f) return
            val radius = min(w, h) / 2f
            buildShapePath(cx, cy, w, h)

            val top: Int
            val bottom: Int
            if (muted) {
                top = muteAlpha(glassTop)
                bottom = muteAlpha(glassBottom)
            } else {
                val lift = 0.10f * barsMix
                top = liftAlpha(glassTop, lift)
                bottom = liftAlpha(glassBottom, lift * 0.6f)
            }
            discPaint.shader = LinearGradient(
                cx, cy - h / 2f, cx, cy + h / 2f, top, bottom, Shader.TileMode.CLAMP,
            )
            canvas.drawPath(shapePath, discPaint)
            discPaint.shader = null

            rimPaint.style = Paint.Style.STROKE
            rimPaint.strokeWidth = density * (1f + 1.0f * barsMix)
            var rimColor = if (darkMode) 0x59FFFFFF else 0x66FFFFFF
            if (barsMix > 0f) {
                rimColor = (rimColor and 0x00FFFFFF) or ((0x66 + (0x38 * barsMix)).toInt() shl 24)
            }
            rimPaint.color = rimColor
            canvas.drawPath(shapePath, rimPaint)

            // Rim ticks are the mic meter only. A decaying playback tail stays inside.
            // Processing keeps drawing while [meterFade] falls so the ring hides.
            if (meterFade > 0.02f && waveIsMic && state != VisualState.SPEAKING) {
                if (alongShape()) {
                    drawLevelOnPath(canvas, meterFade)
                } else {
                    drawLevelRing(canvas, cx, cy, radius, meterFade)
                }
            }

            val orbitR = radius + 4f * density
            val orbitPad = 4f * density
            if (spinAlpha > 0.01f) {
                val fade = spinAlpha
                val sweep = 80f * (0.28f + 0.72f * fade)
                val width = (1.35f + 0.65f * fade) * density
                val alpha = (200 * fade).toInt().coerceIn(0, 255)
                if (alongShape()) {
                    drawOrbitSweep(canvas, orbitPad, spinAngle, sweep, width, alpha)
                } else {
                    drawMeterStroke(canvas, width, alpha) { p ->
                        canvas.drawArc(
                            cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR,
                            spinAngle, sweep, false, p,
                        )
                    }
                }
            }

            if (armProgress > 0f) {
                if (alongShape()) {
                    drawOrbitSweep(canvas, orbitPad, -90f, 360f * armProgress, 2.5f * density, 210)
                } else {
                    drawMeterStroke(canvas, 2.5f * density, 210) { p ->
                        canvas.drawArc(
                            cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR,
                            -90f, 360f * armProgress, false, p,
                        )
                    }
                }
            }

            if (rippleProgress in 0f..1f) {
                val widthPx = (3f - 1.8f * rippleProgress) * density
                val alpha = ((1f - rippleProgress) * 0.7f * 255).toInt()
                if (alongShape()) {
                    val pad = 2.6f * density + 2.4f * density * rippleProgress
                    drawOrbitSweep(canvas, pad, 0f, 360f, widthPx, alpha)
                } else {
                    drawMeterStroke(canvas, widthPx, alpha) { p ->
                        canvas.drawCircle(cx, cy, radius + radius * 0.75f * rippleProgress, p)
                    }
                }
            }

            canvas.save()
            val nudge = 5.5f * density * dock
            val (nx, ny) = when (dockEdge) {
                FabEdge.RIGHT -> -nudge to 0f
                FabEdge.LEFT -> nudge to 0f
                FabEdge.TOP -> 0f to nudge
                FabEdge.BOTTOM -> 0f to -nudge
            }
            canvas.translate(nx, ny)
            // Glyph group sits on the *shape's* centre. Docked and yielded, the D shrinks
            // toward its flush edge, so the shape centre is no longer the window centre.
            val gx = shapeRect.centerX()
            val gy = shapeRect.centerY()
            drawIcon(canvas, gx, gy, radius * (1f + 0.06f * dock))
            canvas.restore()
            if (sendProgress in 0f..1f) drawSendArrow(canvas, gx, gy, radius)
        }

        private fun drawRecordDot(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
            if (alpha <= 0.01f) return
            val grow = 0.7f + 0.3f * alpha
            val dotR = radius * 0.26f * (0.94f + 0.06f * dotPulse) * grow
            haloPaint.style = Paint.Style.FILL
            haloPaint.color = INK
            haloPaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(cx, cy, dotR, haloPaint)
        }

        private fun drawSendArrow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val p = sendProgress
            val lift = radius * 0.95f * p
            val alpha = (1f - p) * (1f - p)
            val y = cy - lift
            val s = radius * 0.24f
            barPaint.style = Paint.Style.STROKE
            barPaint.strokeCap = Paint.Cap.ROUND
            barPaint.strokeWidth = 2.6f * density
            barPaint.color = INK
            barPaint.alpha = (alpha * 255).toInt()
            // Shaft flies up. On a top handle that is into the bezel, so flip it inward.
            val flip = dockEdge == FabEdge.TOP
            if (flip) {
                canvas.save()
                canvas.rotate(180f, cx, cy)
            }
            canvas.drawLine(cx, y + s, cx, y - s, barPaint)
            canvas.drawLine(cx - s * 0.8f, y - s * 0.2f, cx, y - s, barPaint)
            canvas.drawLine(cx + s * 0.8f, y - s * 0.2f, cx, y - s, barPaint)
            if (flip) canvas.restore()
        }

        private fun drawLevelOnPath(canvas: Canvas, fade: Float) {
            if (fade <= 0.02f) return
            buildOrbitPath(3.6f * density)
            orbitMeasure.setPath(orbitPath, true)
            val len = orbitMeasure.length
            if (len < 8f) return
            val n = 18
            val maxLen = min(shapeRect.width(), shapeRect.height()) * 0.075f
            val minLen = 1.7f * density
            val width = 2.0f * density
            val level = smoothLevel
            val alpha = ((0.32f + level * 0.55f) * fade * 255).toInt().coerceIn(0, 255)
            val flushTol = 8f * density
            val cornerAlong = 11f * density
            val cornerDepth = flushTol + 10f * density
            for (i in 0 until n) {
                if (!orbitMeasure.getPosTan(i * len / n, orbitPos, orbitTan)) continue
                val px = orbitPos[0]
                val py = orbitPos[1]
                val onFlush = when (dockEdge) {
                    FabEdge.RIGHT ->
                        abs(px - shapeRect.right) < flushTol ||
                            (abs(px - shapeRect.right) < cornerDepth &&
                                (abs(py - shapeRect.top) < cornerAlong || abs(py - shapeRect.bottom) < cornerAlong))
                    FabEdge.LEFT ->
                        abs(px - shapeRect.left) < flushTol ||
                            (abs(px - shapeRect.left) < cornerDepth &&
                                (abs(py - shapeRect.top) < cornerAlong || abs(py - shapeRect.bottom) < cornerAlong))
                    FabEdge.TOP ->
                        abs(py - shapeRect.top) < flushTol ||
                            (abs(py - shapeRect.top) < cornerDepth &&
                                (abs(px - shapeRect.left) < cornerAlong || abs(px - shapeRect.right) < cornerAlong))
                    FabEdge.BOTTOM ->
                        abs(py - shapeRect.bottom) < flushTol ||
                            (abs(py - shapeRect.bottom) < cornerDepth &&
                                (abs(px - shapeRect.left) < cornerAlong || abs(px - shapeRect.right) < cornerAlong))
                }
                if (onFlush) continue
                // Outward — inward ticks were punching through the D and stacking on the corners.
                val hyp = hypot(orbitTan[0], orbitTan[1])
                if (hyp < 0.001f) continue
                val ux = orbitTan[1] / hyp
                val uy = -orbitTan[0] / hyp
                val flutter = sin(
                    waveClock * barRate[i % BAR_COUNT] * (1f + 1.2f * level) + barPhase[i % BAR_COUNT],
                )
                val wobble = 0.7f + 0.3f * (0.5f + 0.5f * flutter)
                val drive = 0.06f + 0.94f * level
                val tick = minLen + maxLen * drive * wobble
                val x0 = orbitPos[0]
                val y0 = orbitPos[1]
                drawMeterStroke(canvas, width, alpha) { paint ->
                    canvas.drawLine(x0, y0, x0 + ux * tick, y0 + uy * tick, paint)
                }
            }
        }

        private fun drawLevelRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, fade: Float) {
            if (fade <= 0.02f) return
            val inner = radius + 5f * density
            val maxLen = radius * 0.22f
            val minLen = 2.2f * density
            val width = 2.2f * density
            val level = smoothLevel
            val alpha = ((0.32f + level * 0.55f) * fade * 255).toInt().coerceIn(0, 255)
            for (i in 0 until BAR_COUNT) {
                val angle = (i.toFloat() / BAR_COUNT) * 6.2832f - 1.5708f
                val flutter = sin(waveClock * barRate[i] * (1f + 1.2f * level) + barPhase[i])
                val wobble = 0.7f + 0.3f * (0.5f + 0.5f * flutter)
                val drive = 0.06f + 0.94f * level
                val len = minLen + maxLen * drive * wobble
                val ca = cos(angle)
                val sa = sin(angle)
                val x0 = cx + ca * inner
                val y0 = cy + sa * inner
                val x1 = cx + ca * (inner + len)
                val y1 = cy + sa * (inner + len)
                drawMeterStroke(canvas, width, alpha) { paint ->
                    canvas.drawLine(x0, y0, x1, y1, paint)
                }
            }
        }

        private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val sendFade = if (sendProgress in 0f..1f) (1f - sendProgress).coerceIn(0f, 1f) else 1f
            val swap = max(iconMix, barsMix)
            // Mic leaves first and comes back last: gone by the time the bars / dot are
            // ~half way in, so the centre never shows two silhouettes at once.
            val micAlpha = (1f - (swap * MIC_YIELD_GAIN).coerceAtMost(1f)) * sendFade
            // One glyph in the centre at a time: bars win over the dot on hold release.
            val dotAlpha = iconMix * (1f - barsMix)

            if (micAlpha > 0.01f) {
                val size = (radius * (1.0f - 0.25f * swap) * iconYieldScale * iconLinkScale)
                    .roundToInt()
                val left = (cx - size / 2f).roundToInt()
                val top = (cy - size / 2f).roundToInt()
                val base = when {
                    muted -> 225
                    state == VisualState.PROCESSING -> 190
                    state == VisualState.SPEAKING -> 240
                    else -> 255
                }
                val waitA = micAlpha * (1f - linkMix)
                val liveA = micAlpha * linkMix
                if (waitA > 0.01f) {
                    // Stand dots sit at 23/24 and pull the wait group below the live mic.
                    // Lift by the bbox-center delta only — live glyph stays put.
                    val waitTop = top - (size * WAIT_GLYPH_LIFT).roundToInt()
                    val icon = micWaitIcon
                    if (icon != null) {
                        val a = (base * waitA).roundToInt().coerceIn(0, 255)
                        icon.setBounds(left, waitTop, left + size, waitTop + size)
                        icon.setTint(INK)
                        icon.alpha = a
                        icon.draw(canvas)
                    }
                    drawWaitDots(canvas, left, waitTop, size, waitA * (base / 255f))
                }
                if (liveA > 0.01f) {
                    val icon = (if (muted) micOffIcon else micIcon)
                    if (icon != null) {
                        val a = (base * liveA).roundToInt().coerceIn(0, 255)
                        icon.setBounds(left, top, left + size, top + size)
                        icon.setTint(INK)
                        icon.alpha = a
                        icon.draw(canvas)
                    }
                }
            }
            drawRecordDot(canvas, cx, cy, radius, dotAlpha)
            drawListenBars(canvas, cx, cy, radius, barsMix)
        }

        /** Three stand dots from the wait glyph: left → mid → right, then around. */
        private fun drawWaitDots(canvas: Canvas, left: Int, top: Int, size: Int, alpha: Float) {
            if (alpha <= 0.01f || size <= 0) return
            val s = size.toFloat()
            val r = s * (1.05f / 24f)
            val cy = top + s * (23f / 24f)
            for (i in 0..2) {
                var d = abs(dotsPhase - (i + 0.5f))
                if (d > 1.5f) d = 3f - d
                val lit = (1f - (d / 1.05f).coerceIn(0f, 1f))
                val a = (alpha * (0.22f + 0.78f * lit * lit) * 255f).toInt().coerceIn(0, 255)
                waitDotPaint.alpha = a
                val cx = left + s * ((8f + i * 4f) / 24f)
                canvas.drawCircle(cx, cy, r, waitDotPaint)
            }
        }

        private fun drawListenBars(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
            if (alpha <= 0.01f) return
            val grow = 0.7f + 0.3f * alpha
            val barW = radius * 0.16f * grow
            val gap = radius * 0.12f * grow
            val minH = radius * 0.22f * grow
            val maxH = radius * 0.72f * grow
            barPaint.style = Paint.Style.STROKE
            barPaint.strokeCap = Paint.Cap.ROUND
            barPaint.strokeWidth = barW
            barPaint.color = INK
            barPaint.alpha = (alpha * 255).toInt()
            for (i in -1..1) {
                val phase = waveClock * (3.1f + i * 0.7f) + i * 1.3f
                val idle = 0.5f + 0.5f * sin(phase)
                val weight = if (i == 0) 1f else 0.72f
                // TTS: height is playback only. The idle sine is listen-chrome
                // ("mic is open") and must not fake motion before audio.
                val drive = if (state == VisualState.SPEAKING) {
                    smoothLevel
                } else {
                    0.25f * idle + 0.75f * smoothLevel
                }
                // Rise out of the centre line as the mix comes in (and sink back on the
                // way out) — bars grow into place rather than materialising at rest height.
                val h = ((minH + (maxH - minH) * drive * weight) * alpha)
                    .coerceIn(0f, maxH)
                val x = cx + i * (barW + gap)
                canvas.drawLine(x, cy - h / 2f, x, cy + h / 2f, barPaint)
            }
        }
    }

    companion object {
        private const val TAG = "QuickWakeFab"

        /**
         * Same black-gray glass on both walls so the mic never flips to porcelain.
         * Light: charcoal ~75–80% so day wallpaper shows through. Dark: a bit more
         * open ~70–76% so it still lifts off a black home. Glyphs stay white.
         */
        private const val GLASS_LIGHT_TOP = 0xC01C1E22.toInt()
        private const val GLASS_LIGHT_BOTTOM = 0xCC141518.toInt()
        private const val GLASS_DARK_TOP = 0xB8363840.toInt()
        private const val GLASS_DARK_BOTTOM = 0xC4282A30.toInt()
        private const val INK = 0xFFF7F7F8.toInt()
        /** Faint grey for the processing orbit and level meter — not a dark outline. */
        private const val METER_GRAY = 0xFFB4B4BA.toInt()
        private const val BAR_COUNT = 32
        /** Tap acknowledged but no Listening after this: shake and give up. */
        private const val PENDING_TIMEOUT_MS = 2_500L
        /** IDLE landing sooner than this after a tap is the old turn closing, not a refusal. */
        private const val PENDING_FAIL_FAST_MS = 450L

        /** Drawn circle when pulled off the edge. */
        private const val FAB_SIZE_DP = 80f
        /** D-handle when sucked to an edge (style 1). */
        private const val HANDLE_W_DP = 48f
        private const val HANDLE_H_DP = 104f
        /** How far the handle hangs into the bezel when docked. */
        private const val HANDLE_HANG_DP = 7.5f
        /** Band against the nearest screen edge where a circle melts into a D. */
        private const val MORPH_RANGE_DP = 36f
        /** Live magnet only in a short band against the wall — not a far suck. */
        private const val MAGNET_RANGE_DP = 30f
        /** Release closer than this dock amount eases into a handle. */
        private const val SNAP_DOCK = 0.55f
        /** Hint toward the nearest edge while docking — off while peeling away. */
        private const val MAGNET_PULL = 0.07f
        /** Near-critical droplet: zeta ≈ 0.92, wet slide. Docking over-damps X so it does not bounce. */
        private const val SPRING_STIFF = 88f
        private const val SPRING_DAMP = 17.3f
        /** Window box: disc + room for the voice-level ring on every side. */
        private const val WINDOW_SIZE_DP = 134f
        /** Touch counts this far outside the visible shape edge. */
        private const val HIT_MARGIN_DP = 10f
        /** Palm-brush filter: shorter than this never counts as a tap. */
        private const val MIN_TAP_MS = 120L
        /** Finger must rest this long before motion moves the button (tap mode "pick up"). */
        private const val DRAG_ARM_MS = 340L
        /** After a valid tap: ripple first, then start STT. */
        private const val TAP_COMMIT_MS = 200L
        /** Hold mode: press squash first; brushes never start the rim arc. */
        private const val HOLD_GUARD_MS = 100L
        /** Hold mode: rim fills, then recording. */
        private const val HOLD_ARM_MS = 480L
        /** Pre-pickup wander tolerance, in touch slops: a settling thumb is still a tap. */
        private const val TAP_WANDER_MUL = 2.5f
        /** Bubble entrance. */
        private const val CAPTION_FADE_MS = 420L
        /** Bubble exit: short, alpha only — it is being taken away, not handed over. */
        private const val CAPTION_EXIT_MS = 220L
        private const val ATTACH_RETRY_MS = 1_000L
        private const val ATTACH_RETRY_MAX = 5
        private const val HEALTH_MS = 2_500L
        private const val AOD_RECHECK_MS = 1_000L
        /** Mic glyph is fully out once the incoming glyph mix reaches 1/this. */
        private const val MIC_YIELD_GAIN = 1.8f
        /**
         * Wait mic + stand dots vs live mic, in 24-unit viewport:
         * live bbox centre 11.5, wait+dots 12.525. Lift the wait group by that
         * delta so both share a centre. ~1.1dp on the docked D, scales with size.
         */
        private const val WAIT_GLYPH_LIFT = 1.025f / 24f

        /** Wake-word turn: the disc steps back to this while the sphere has the stage. */
        private const val YIELD_SCALE = 0.82f
        /** Glyph settles a touch smaller than the disc so it reads as sitting back. */
        private const val ICON_YIELD_SCALE = 0.94f
        /** Let the wake ripple burst first (~210 ms to peak), then the disc gives way. */
        private const val YIELD_IN_DELAY_MS = 120L
        private const val YIELD_IN_MS = 340L
        /** Session over: come back with a light overshoot — the "done" beat. */
        private const val YIELD_OUT_MS = 480L
        /** HA connect / drop: wait-mic ↔ live-mic handoff. */
        private const val LINK_MS = 520L
        /** One lap of the three stand dots while waiting for HA. */
        private const val DOTS_CYCLE_MS = 1260L
        /** Bubble normally leaves after the reply plate settles; this caps it if no reply ever comes. */
        private const val CAPTION_MAX_HOLD_MS = 12_000L
        /** How long the transcript stays next to the settled reply before fading. */
        private const val CAPTION_LINGER_AFTER_TTS_MS = 2_000L
        /** Hard wrap: one CJK em per unit. Keeps the box on the mic instead of throwing long STT. */
        private const val STT_BUBBLE_UNITS_PER_LINE = 11f
        private const val STT_BUBBLE_MAX_LINES = 3

        private const val PREFS_NAME = "quick_wake_fab"
        private const val KEY_NORM_X = "norm_x"
        private const val KEY_NORM_Y = "norm_y"

        @Volatile
        private var instance: QuickWakeFabService? = null

        /**
         * The reply caption is fully on screen. Let the transcript bubble linger a beat so
         * the user can read question and answer together, then take it away. Idempotent:
         * later caption pages only re-arm if nothing is scheduled yet.
         */
        fun noteTtsCaptionSettled() {
            val svc = instance ?: return
            svc.handler.post {
                svc.ttsCaptionSettledThisTurn = true
                svc.armCaptionLinger()
            }
        }

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        fun raiseAboveVoiceOverlay() {
            instance?.raiseAboveVoiceOverlay()
        }

        /** Finger is moving the disc (or it is still settling). Restacks would drop it. */
        fun isUserMoving(): Boolean = instance?.isUserMovingFab() == true

        fun yieldToAod() {
            instance?.yieldToAod()
        }

        fun restoreFromAod() {
            instance?.restoreFromAod()
        }

        /**
         * Playback has started. Processing orbit may hand the disc to inner bars.
         * Must not wait on caption / overlay delays in the satellite callback.
         */
        fun noteTtsAudible() {
            val svc = instance ?: return
            svc.handler.post {
                val sat = svc.lastSatState
                // Some pipelines sit on Processing until the first audible frame.
                if (sat != Responding && sat != Processing) return@post
                if (svc.ttsAudible && svc.visualState == VisualState.SPEAKING) return@post
                svc.ttsAudible = true
                svc.setVisualState(VisualState.SPEAKING)
            }
        }

        /**
         * Per-frame mic level (0..1, [com.example.ava.audio.AudioEnergy.rmsLevelMic] scale)
         * for the current Quick Wake turn. Arrives on the audio thread; the view keeps the
         * latest sample and the render loop consumes it on the next frame.
         */
        fun feedAudioLevel(level: Float) {
            instance?.fabView?.pushLevel(level)
        }

        fun show(context: Context) {
            if (!FloatingWindowService.hasOverlayPermission(context)) return
            if (instance != null) return
            context.startService(Intent(context, QuickWakeFabService::class.java))
        }

        fun hide(context: Context) {
            if (instance == null) return
            context.stopService(Intent(context, QuickWakeFabService::class.java))
        }

        fun isShowing(): Boolean = instance != null
    }
}
