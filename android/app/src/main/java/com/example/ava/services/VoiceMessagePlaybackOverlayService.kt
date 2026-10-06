package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.notificationSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.applyQuickEntityOverlayViewSetup
import com.example.ava.ui.applyQuickEntityOverlayWindowFlags
import com.example.ava.ui.components.VoiceMessagePlaybackOverlayContent
import com.example.ava.utils.TouchSoundHelper
import com.example.ava.settings.PlayerSettings
import com.example.ava.voice.AvaVoiceCallRingtonePlayer
import com.example.ava.voice.AvaVoiceInboundBus
import com.example.ava.voice.AvaVoiceIncomingMessage
import com.example.ava.voice.AvaVoiceIncomingPhase
import com.example.ava.voice.AvaVoiceMessageBoard
import com.example.ava.voice.AvaVoiceMode
import com.example.ava.voice.AvaVoiceProtocol
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDeviceType
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceMessenger
import com.example.ava.voice.AvaVoiceSessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

class VoiceMessagePlaybackOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val viewModelStoreImpl = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = viewModelStoreImpl

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listenJob: Job? = null
    private var progressJob: Job? = null
    private var hideRunnable: Runnable? = null

    private var activeMessage by mutableStateOf<AvaVoiceIncomingMessage?>(null)
    private var playbackProgress by mutableFloatStateOf(0f)
    private var messageBoardMode by mutableStateOf(false)
    private var isListening = false
    private var popupHideGeneration = 0
    private var fallbackRingtone: Ringtone? = null
    private var callRingtonePlayer: AvaVoiceCallRingtonePlayer? = null
    private var ringTimeoutRunnable: Runnable? = null

    private val notificationSettingsStore by lazy {
        NotificationSettingsStore(applicationContext.notificationSettingsStore)
    }
    private val playerSettings by lazy {
        PlayerSettingsStore(applicationContext.playerSettingsStore)
    }

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
            setViewTreeLifecycleOwner(this@VoiceMessagePlaybackOverlayService)
            setViewTreeViewModelStoreOwner(this@VoiceMessagePlaybackOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VoiceMessagePlaybackOverlayService)
            setContent {
                val message = activeMessage
                if (message != null) {
                    VoiceMessagePlaybackOverlayContent(
                        message = message,
                        progress = playbackProgress,
                        messageBoardMode = messageBoardMode,
                        onDismiss = {
                            TouchSoundHelper.playClick(this@VoiceMessagePlaybackOverlayService)
                            hidePopup()
                        },
                        onAnswer = {
                            TouchSoundHelper.playClick(this@VoiceMessagePlaybackOverlayService)
                            stopCallRingtone()
                            cancelRingTimeout()
                            AvaVoiceSessionHub.answerInboundCall(message.sessionId)
                        },
                        onDecline = {
                            TouchSoundHelper.playClick(this@VoiceMessagePlaybackOverlayService)
                            stopCallRingtone()
                            AvaVoiceSessionHub.declineInboundCall(message.fromDeviceId)
                            hidePopup()
                        }
                    )
                }
            }
        }

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
            windowManager?.addView(composeView, windowParams)
            OverlayZOrderCoordinator.noteWindowAdded()
            composeView?.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add playback overlay", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayoutOverlay()
    }

    /**
     * Keep the same [ComposeView] across orientation changes.
     * Remove+recreate would dispose the call composition (and tear down duplex/video).
     */
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
            Log.e(TAG, "Failed to relayout playback overlay", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_STOP_LISTENING -> stopListening()
            ACTION_SHOW_MESSAGE -> {
                val fromId = intent.getStringExtra(EXTRA_FROM_ID).orEmpty()
                val fromName = intent.getStringExtra(EXTRA_FROM_NAME).orEmpty()
                val toId = intent.getStringExtra(EXTRA_TO_ID).orEmpty()
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 1000L)
                if (fromId.isNotBlank() && toId.isNotBlank()) {
                    showIncoming(
                        AvaVoiceIncomingMessage(
                            fromDeviceId = fromId,
                            fromName = fromName.ifBlank { fromId },
                            toDeviceId = toId,
                            durationMs = durationMs
                        )
                    )
                }
            }
            ACTION_DISMISS_ACTIVE -> {
                endActiveVoiceSession()
                hidePopup()
            }
        }
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        listenJob?.cancel()
        serviceScope.launch {
            val boardMode = playerSettings.voiceMessageReceiveMode.get() == "board"
            messageBoardMode = boardMode
            AvaVoiceSessionHub.setMessageBoardMode(boardMode)
        }
        listenJob = merge(
            AvaVoiceInboundBus.messages.onEach { message ->
                when (message.phase) {
                    AvaVoiceIncomingPhase.Ringing -> showRingingIncoming(message)
                    AvaVoiceIncomingPhase.Started -> showStreamingIncoming(message)
                    AvaVoiceIncomingPhase.Ended -> onStreamingEnded(message)
                    AvaVoiceIncomingPhase.Hangup -> onCallHangup(message)
                    AvaVoiceIncomingPhase.Declined,
                    AvaVoiceIncomingPhase.NoAnswer -> { /* caller-only; handled by send overlay */ }
                    AvaVoiceIncomingPhase.LegacyStub -> showLegacyIncoming(message)
                }
            },
            playerSettings.voiceMessageReceiveMode.onEach { mode ->
                val boardMode = mode == "board"
                messageBoardMode = boardMode
                AvaVoiceSessionHub.setMessageBoardMode(boardMode)
            },
            AvaVoiceInboundBus.playbackProgress.onEach { progress ->
                playbackProgress = progress
                if (progress >= 1f && activeMessage?.mode != AvaVoiceMode.Call && !messageBoardMode) {
                    scheduleHideAfterDrain()
                }
            }
        ).retryWhen { cause, attempt ->
            // One throwing handler (e.g. a DataStore read error) must not permanently
            // kill inbound call/ring handling while isListening stays true — that wedge
            // silently drops every future inbound ring until the process restarts.
            Log.e(TAG, "inbound listener failed (attempt=$attempt) — restarting", cause)
            delay(LISTENER_RETRY_DELAY_MS)
            isListening
        }.launchIn(serviceScope)
    }

    private fun stopListening() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        AvaVoiceSessionHub.setMessageBoardMode(false)
        hidePopup()
    }

    private fun shouldShowInboundCallUi(message: AvaVoiceIncomingMessage): Boolean {
        if (message.mode != AvaVoiceMode.Call) return true
        val localId = AvaVoiceDiscovery.localId()
        return !AvaVoiceSessionHub.hasActiveCallOutboundTo(localId, setOf(message.fromDeviceId))
    }

    private fun showRingingIncoming(message: AvaVoiceIncomingMessage) {
        if (!shouldShowInboundCallUi(message)) return
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }
            hideRunnable = null
            progressJob?.cancel()
            progressJob = null
            val wasHidden = activeMessage == null
            activeMessage = message
            playbackProgress = 0f
            VoiceMessageOverlayService.setCallSuppressed(this@VoiceMessagePlaybackOverlayService, true)
            wakeScreenForIncomingCall()
            setCallKeepScreenOn(true)
            startCallRingtone(message.fromDeviceId)
            armRingTimeout(message.fromDeviceId)
            bringToFront(raiseToTop = wasHidden)
            composeView?.let { fadeInPopup(it) }
        }
    }

    private fun showStreamingIncoming(
        message: AvaVoiceIncomingMessage,
        allowPeerSwap: Boolean = false
    ) {
        if (message.mode == AvaVoiceMode.Call && !shouldShowInboundCallUi(message)) return
        handler.post {
            val current = activeMessage
            if (!allowPeerSwap && message.mode == AvaVoiceMode.Call && current != null &&
                current.mode == AvaVoiceMode.Call &&
                current.fromDeviceId != message.fromDeviceId
            ) {
                // Conference join (auto-answer): the hub already mixes the joiner's audio and
                // extended our mic leg. Swapping the single message slot would re-key the
                // call composition and stop the live mic leg, so the first peer stays on
                // screen and the joiner runs audio-only.
                Log.d(
                    TAG,
                    "call UI kept on peer=${current.fromDeviceId}; " +
                        "peer=${message.fromDeviceId} joined audio-only"
                )
                return@post
            }
            hideRunnable?.let { handler.removeCallbacks(it) }
            hideRunnable = null
            progressJob?.cancel()
            progressJob = null
            stopCallRingtone()
            cancelRingTimeout()
            val wasHidden = activeMessage == null
            activeMessage = message
            playbackProgress = 0f
            if (message.mode == AvaVoiceMode.Call) {
                VoiceMessageOverlayService.setCallSuppressed(this@VoiceMessagePlaybackOverlayService, true)
                wakeScreenForIncomingCall()
                setCallKeepScreenOn(true)
            }
            bringToFront(raiseToTop = wasHidden)
            composeView?.let { fadeInPopup(it) }
        }
    }

    /**
     * @param raiseToTop remove+re-add the window so it stacks above dashboard overlays
     * added later (dream clock / weather / simple clock). Only safe on the hidden →
     * visible transition: at that instant the composition holds no call/message UI yet
     * (recomposition runs after this main-thread block), so nothing gets torn down.
     * While a popup is already showing, remove+re-add would dispose the live call
     * composition, so only the layout params are refreshed.
     */
    private fun bringToFront(raiseToTop: Boolean = false) {
        val view = composeView ?: return
        val params = windowParams ?: return
        if (!view.isAttachedToWindow) return
        try {
            if (!raiseToTop && (activeMessage != null || AvaVoiceSessionHub.hasLiveCallSessions())) {
                params.applyQuickEntityOverlayWindowFlags(notFocusable = false)
                windowManager?.updateViewLayout(view, params)
            } else {
                OverlayZOrderCoordinator.bringToFront(windowManager, view, params, TAG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring playback overlay to front", e)
        }
    }

    private fun onStreamingEnded(message: AvaVoiceIncomingMessage) {
        handler.post {
            if (message.mode == AvaVoiceMode.Call) {
                // A call-mode Ended is a stream-level event (encoder restart, idle reap of a
                // stale leg) — normalize it back to Started only for the call already on
                // screen. It must never resurrect a hidden popup into a phantom connected
                // call, affect a different peer's UI, or promote a still-ringing popup to
                // connected (which would silently open the microphone).
                val current = activeMessage
                if (current == null || current.mode != AvaVoiceMode.Call ||
                    current.fromDeviceId != message.fromDeviceId ||
                    current.phase == AvaVoiceIncomingPhase.Ringing
                ) {
                    return@post
                }
                activeMessage = current.copy(
                    fromName = message.fromName,
                    durationMs = message.durationMs,
                    totalBytes = message.totalBytes,
                    mode = message.mode,
                    phase = AvaVoiceIncomingPhase.Started
                )
                playbackProgress = 0f
                return@post
            }
            // Update-only: a late Ended for a dismissed popup must not resurrect state
            // (an invisible non-null activeMessage breaks the hidden→visible raise logic).
            val currentMessage = activeMessage ?: return@post
            activeMessage = currentMessage.copy(
                fromName = message.fromName,
                durationMs = message.durationMs,
                totalBytes = message.totalBytes,
                mode = message.mode,
                phase = AvaVoiceIncomingPhase.Ended
            )
            val fallbackMs = message.durationMs.coerceAtLeast(800L) + 1200L
            hideRunnable?.let { handler.removeCallbacks(it) }
            if (!messageBoardMode) {
                hideRunnable = Runnable { hidePopup() }
                handler.postDelayed(hideRunnable!!, fallbackMs)
            }
        }
    }

    private fun scheduleHideAfterDrain() {
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }
            hideRunnable = Runnable { hidePopup() }
            handler.postDelayed(hideRunnable!!, 350L)
        }
    }

    private fun showLegacyIncoming(message: AvaVoiceIncomingMessage) {
        handler.post {
            val wasHidden = activeMessage == null
            activeMessage = message
            playbackProgress = 0f
            playIncomingSound()
            bringToFront(raiseToTop = wasHidden)
            composeView?.let { fadeInPopup(it) }
            progressJob?.cancel()
            val durationMs = message.durationMs.coerceAtLeast(800L)
            val startedAt = System.currentTimeMillis()
            progressJob = serviceScope.launch {
                while (true) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    playbackProgress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                    if (elapsed >= durationMs) break
                    kotlinx.coroutines.delay(32)
                }
                hidePopup()
            }
            hideRunnable?.let { handler.removeCallbacks(it) }
            hideRunnable = Runnable { hidePopup() }
            handler.postDelayed(hideRunnable!!, durationMs + 350L)
        }
    }

    private fun showIncoming(message: AvaVoiceIncomingMessage) {
        when (message.phase) {
            AvaVoiceIncomingPhase.Ringing -> showRingingIncoming(message)
            AvaVoiceIncomingPhase.Started -> showStreamingIncoming(message)
            AvaVoiceIncomingPhase.Ended -> onStreamingEnded(message)
            AvaVoiceIncomingPhase.Hangup -> onCallHangup(message)
            AvaVoiceIncomingPhase.LegacyStub -> showLegacyIncoming(message)
            AvaVoiceIncomingPhase.Declined,
            AvaVoiceIncomingPhase.NoAnswer -> Unit
        }
    }

    private fun onCallHangup(message: AvaVoiceIncomingMessage) {
        val target = AvaVoiceDevice(
            id = message.fromDeviceId,
            name = message.fromName,
            host = message.sourceHost,
            type = AvaVoiceDeviceType.UNKNOWN
        )
        AvaVoiceMessenger.stopLocalCallStreams(AvaVoiceDiscovery.localId(), listOf(target))
        // Only tear down the popup when the hangup concerns what is on screen. A peer
        // ending an unrelated leg must not hide a live call (or a playing message)
        // with a different device — the session with that device keeps running.
        val current = activeMessage
        val affectsUi = current == null ||
            (current.mode == AvaVoiceMode.Call && current.fromDeviceId == message.fromDeviceId)
        if (!affectsUi) return
        // Conference: when other peers are still on the call, hand the UI to one of them
        // instead of hiding a live call. The mic leg already serves the survivor (it was
        // shrunk above, not stopped); the disposed controller skips teardown for legs that
        // serve peers it never owned, and the re-keyed composition attaches without
        // restarting the mic (hasActiveCallOutboundTo sees the live leg).
        if (current != null && current.mode == AvaVoiceMode.Call) {
            val survivor = AvaVoiceSessionHub.activeCallPeers()
                .firstOrNull { it.id != message.fromDeviceId && it.host.isNotBlank() }
            if (survivor != null) {
                val sessionId = AvaVoiceSessionHub.findCallSessionId(
                    AvaVoiceDiscovery.localId(),
                    setOf(survivor.id)
                ) ?: 0
                Log.d(TAG, "call UI handed off to surviving peer=${survivor.id}")
                showStreamingIncoming(
                    current.copy(
                        fromDeviceId = survivor.id,
                        fromName = survivor.name,
                        sourceHost = survivor.host,
                        sessionId = sessionId,
                        phase = AvaVoiceIncomingPhase.Started
                    ),
                    allowPeerSwap = true
                )
                return
            }
        }
        stopCallRingtone()
        hidePopup()
    }

    private fun startCallRingtone(fromDeviceId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val settings = playerSettings.get()
                val uri = settings.voiceCallRingtone.ifBlank {
                    PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE
                }
                handler.post {
                    if (callRingtonePlayer == null) {
                        callRingtonePlayer = AvaVoiceCallRingtonePlayer(this@VoiceMessagePlaybackOverlayService, handler)
                    }
                    callRingtonePlayer?.start(
                        uriString = uri,
                        maxDurationMs = AvaVoiceProtocol.CALL_RING_TIMEOUT_MS
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start call ringtone", e)
            }
        }
    }

    private fun stopCallRingtone() {
        callRingtonePlayer?.stop()
    }

    /**
     * The ring timeout is owned by the service, not the ringtone player: a ringtone that
     * fails to start (bad custom URI, missing asset) must still time the ring out —
     * otherwise no NO_ANSWER is ever sent and the ringing popup lingers until the hub's
     * idle cleanup morphs it into a phantom connected call.
     */
    private fun armRingTimeout(fromDeviceId: String) {
        cancelRingTimeout()
        val runnable = Runnable { onCallRingTimeout(fromDeviceId) }
        ringTimeoutRunnable = runnable
        handler.postDelayed(runnable, AvaVoiceProtocol.CALL_RING_TIMEOUT_MS)
    }

    private fun cancelRingTimeout() {
        ringTimeoutRunnable?.let { handler.removeCallbacks(it) }
        ringTimeoutRunnable = null
    }

    private fun onCallRingTimeout(fromDeviceId: String) {
        ringTimeoutRunnable = null
        if (fromDeviceId.isBlank()) return
        // Safe on answered calls: only still-pending rings are timed out at the hub.
        AvaVoiceSessionHub.timeoutInboundCall(fromDeviceId)
        // Belt and braces: if the call was answered (Started raced past this timer),
        // never tear down the connected UI — only a ring that is still actually ringing.
        val current = activeMessage
        val stillRingingThisPeer = current == null ||
            (current.mode == AvaVoiceMode.Call &&
                current.fromDeviceId == fromDeviceId &&
                current.phase == AvaVoiceIncomingPhase.Ringing)
        if (!stillRingingThisPeer) return
        stopCallRingtone()
        hidePopup()
    }

    /**
     * Wake the display and dismiss the screensaver so the incoming-call overlay is
     * visible. Covers both tap-to-answer ringing and auto-answered (no-ring) calls
     * on photo-frame / kiosk devices where a dream or screensaver may be active.
     */
    private fun wakeScreenForIncomingCall() {
        ScreensaverController.dismissForIncomingCall()
    }

    /**
     * Hold the screen on while ringing or in a call. Uses [View.keepScreenOn] rather
     * than a window flag: the overlay layout helpers rewrite window flags wholesale
     * on every bring-to-front, which would silently drop FLAG_KEEP_SCREEN_ON.
     */
    private fun setCallKeepScreenOn(enabled: Boolean) {
        composeView?.keepScreenOn = enabled
    }

    private fun endActiveVoiceSession() {
        val message = activeMessage ?: return
        if (message.mode != AvaVoiceMode.Call) return
        val target = AvaVoiceDevice(
            id = message.fromDeviceId,
            name = message.fromName,
            host = message.sourceHost,
            type = AvaVoiceDeviceType.UNKNOWN
        )
        when (message.phase) {
            AvaVoiceIncomingPhase.Ringing ->
                AvaVoiceSessionHub.declineInboundCall(message.fromDeviceId)
            else -> {
                // Hanging up a conference means leaving it entirely: include every live
                // call peer, not just the one on screen (joiners run audio-only).
                val peers = LinkedHashMap<String, AvaVoiceDevice>()
                peers[target.id] = target
                AvaVoiceSessionHub.activeCallPeers().forEach { peers.putIfAbsent(it.id, it) }
                AvaVoiceMessenger.hangupVoiceCall(
                    AvaVoiceDiscovery.localId(),
                    peers.values.toList()
                )
            }
        }
    }

    private fun hidePopup() {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        progressJob?.cancel()
        progressJob = null
        stopIncomingSound()
        stopCallRingtone()
        cancelRingTimeout()
        setCallKeepScreenOn(false)
        val wasCall = activeMessage?.mode == AvaVoiceMode.Call
        activeMessage?.let { message ->
            if (message.mode != AvaVoiceMode.Call) {
                AvaVoiceMessageBoard.remove(message.sessionId)
            }
        }
        activeMessage = null
        playbackProgress = 0f
        val view = composeView
        if (view != null) {
            val gen = ++popupHideGeneration
            view.animate().cancel()
            view.animate().setListener(null)
            view.animate().alpha(0f).setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    private var canceled = false
                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                        if (gen == popupHideGeneration && activeMessage == null) {
                            view.visibility = View.GONE
                            view.alpha = 1f
                            view.animate().setListener(null)
                            if (wasCall) {
                                VoiceMessageOverlayService.setCallSuppressed(
                                    this@VoiceMessagePlaybackOverlayService,
                                    false
                                )
                            }
                        }
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        view.animate().setListener(null)
                        if (canceled || gen != popupHideGeneration) return
                        view.visibility = View.GONE
                        view.alpha = 1f
                        if (wasCall) {
                            VoiceMessageOverlayService.setCallSuppressed(
                                this@VoiceMessagePlaybackOverlayService,
                                false
                            )
                        }
                    }
                })
                .start()
        } else if (wasCall) {
            VoiceMessageOverlayService.setCallSuppressed(this@VoiceMessagePlaybackOverlayService, false)
        }
    }

    private fun fadeInPopup(view: View) {
        popupHideGeneration++
        view.animate().cancel()
        view.animate().setListener(null)
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun playIncomingSound() {
        val satellite = VoiceSatelliteService.getInstance()
        if (satellite != null) {
            satellite.playVoiceMessageReceiveSound()
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val settings = notificationSettingsStore.get()
                val uri = when {
                    settings.soundEnabled && settings.soundUri.isNotEmpty() ->
                        Uri.parse(settings.soundUri)
                    else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } ?: return@launch
                handler.post {
                    stopIncomingSound()
                    fallbackRingtone = RingtoneManager.getRingtone(this@VoiceMessagePlaybackOverlayService, uri)
                    fallbackRingtone?.play()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play incoming voice message sound", e)
            }
        }
    }

    private fun stopIncomingSound() {
        fallbackRingtone?.stop()
        fallbackRingtone = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try {
            composeView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove playback overlay", e)
        }
        instance = null
    }

    companion object {
        private const val TAG = "VoiceMsgPlayback"
        private const val LISTENER_RETRY_DELAY_MS = 1_000L
        private var instance: VoiceMessagePlaybackOverlayService? = null

        const val ACTION_START_LISTENING = "com.example.ava.START_VOICE_PLAYBACK_LISTENING"
        const val ACTION_STOP_LISTENING = "com.example.ava.STOP_VOICE_PLAYBACK_LISTENING"
        const val ACTION_SHOW_MESSAGE = "com.example.ava.SHOW_VOICE_PLAYBACK"
        const val ACTION_DISMISS_ACTIVE = "com.example.ava.DISMISS_VOICE_PLAYBACK"
        const val EXTRA_FROM_ID = "from_id"
        const val EXTRA_FROM_NAME = "from_name"
        const val EXTRA_TO_ID = "to_id"
        const val EXTRA_DURATION_MS = "duration_ms"

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        /** True while an inbound call / voice-message popup is actually showing. */
        fun isActivelyShowing(): Boolean {
            val service = instance ?: return false
            return service.activeMessage != null &&
                service.composeView?.visibility == View.VISIBLE
        }

        fun dismissActiveSession(context: Context) {
            val service = instance
            if (service != null) {
                service.handler.post {
                    service.endActiveVoiceSession()
                    service.hidePopup()
                }
                return
            }
            val intent = Intent(context, VoiceMessagePlaybackOverlayService::class.java).apply {
                action = ACTION_DISMISS_ACTIVE
            }
            context.startService(intent)
        }

        fun ensureStarted(context: Context) {
            val svc = instance
            if (svc != null && svc.isListening) return
            val intent = Intent(context, VoiceMessagePlaybackOverlayService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceMessagePlaybackOverlayService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }
    }
}
