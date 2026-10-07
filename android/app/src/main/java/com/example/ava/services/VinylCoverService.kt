package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ava.R
import com.example.ava.ui.AvaToast
import com.example.ava.mods.ModMediaOverlayExclusive
import com.example.ava.ui.components.CircleEqMiniPlayerFab
import com.example.ava.ui.components.DetailedMetadataMusicPlayerView
import com.example.ava.ui.components.GlassMusicPlayerView
import com.example.ava.settings.MediaOverlayStyle
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.SendspinSettingsStore
import com.example.ava.settings.sendspinSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.utils.HaMediaUrl
import com.example.ava.utils.TouchSoundHelper
import com.example.ava.voice.AvaSyncOffsetPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VinylCoverService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {


    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    
    private val viewModelStoreImpl = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = viewModelStoreImpl
    
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    /** Root overlay view attached to WindowManager — never remove/add during morph. */
    private var overlayRoot: FrameLayout? = null
    private var composeView: ComposeView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    

    private var coverUrl by mutableStateOf<String?>(null)
    private var coverBitmap by mutableStateOf<Bitmap?>(null)
    private var songTitle by mutableStateOf("")
    private var artistName by mutableStateOf("")
    private var albumName by mutableStateOf("")
    private var isPlaying by mutableStateOf(false)
    private var currentTimeMs by mutableStateOf(0L)
    private var totalTimeMs by mutableStateOf(0L)
    /**
     * Bumped only on an intentional force-playhead (dead stream → 0:00).
     * Compose sticky / finger-scrub seats ignore ordinary 0 flashes; this
     * token lets them accept that one paint.
     */
    private var playheadForceEpoch by mutableStateOf(0)
    /**
     * Mid-track playhead collapses to ~0:00 (metadata/progress race). After
     * [PLAYHEAD_ZERO_GLITCH_HOLD_AFTER] hits, hold last progress until title/queue change.
     */
    private var playheadZeroGlitchCount = 0
    private var volumeLevel by mutableStateOf(1.0f)
    private var repeatMode by mutableStateOf("off")
    private var shuffleEnabled by mutableStateOf(false)
    /**
     * Repeat/shuffle taps are bets, not writes. Sendspin `client/command` and HA
     * `repeat_set` are both fire-and-forget — neither protocol acknowledges a
     * command, and the only confirmation is the upstream later reporting the new
     * state back through [ACTION_UPDATE_PLAYBACK_SETTINGS].
     *
     * The glyph still moves on tap (a control that waits a round trip feels
     * broken), so the pre-tap value is parked here and restored if no upstream
     * report arrives within [TRANSPORT_CONFIRM_WINDOW_MS]. Mass API used to mask
     * this by re-reporting queue state within a beat; Sendspin-only and HA-only
     * sessions had nothing playing that role and simply kept the wrong glyph.
     */
    private var pendingRepeatRevert: String? = null
    private var pendingShuffleRevert: Boolean? = null
    private var repeatRevertJob: Job? = null
    private var shuffleRevertJob: Job? = null
    
    private var windowParams: WindowManager.LayoutParams? = null
    private var cachedHaCoverUrl: String? = null
    private var skipStateUpdateUntil: Long = 0L
    private var isSendspinSource by mutableStateOf(false)
    /** Sendspin-only: metadata progress ahead of speaker; lyrics subtract this. */
    private var lyricAudibleLagMs by mutableStateOf(0L)
    private var overlayStyle by mutableStateOf(MediaOverlayStyle.DETAILED)
    /** User setting: persistent circular mini control (not auto-hide with track). */
    private var enableEqMiniPlayer by mutableStateOf(true)
    /** When [enableEqMiniPlayer] is true, false = mini FAB, true = full overlay. */
    private var overlayExpanded by mutableStateOf(true)
    /**
     * Last **user-intent** presentation for mini mode: expanded full player vs FAB.
     * Written only by real gestures / intentional collapse:
     * - true: FAB tap ([expandFromMini]) or HA force-expand
     * - false: [persistAsMini] / force teardown / interrupted expand snap-back
     * Protocol paints (metadata, playback, ghost-pause rescue) must never flip
     * form either way via [show] — already-visible [show] is form-preserving.
     * Fresh birth still honors this flag (e.g. pause-idle tuck → audible re-show).
     * Default false so cold start still births the FAB.
     */
    private var userPrefersExpanded = false
    /**
     * false = WM shell shrunk to FAB (touch outside passes through, like
     * [VolumeControlService] WRAP_CONTENT / [DashboardOverlayChrome] strip).
     * true = MATCH_PARENT for expand morph + full player.
     */
    private var windowIsFullScreen by mutableStateOf(true)
    /**
     * Normalized FAB position in the movable range [0, 1] for the *current*
     * orientation. Remapped to px on every refresh so portrait↔landscape stays put
     * relatively. -1 = default bottom-end.
     */
    private var miniFabNormX = -1f
    private var miniFabNormY = -1f
    /** Drawn TOP|START px (from norms or default) — FAB offset while full-screen. */
    private var miniFabDrawX by mutableStateOf(0)
    private var miniFabDrawY by mutableStateOf(0)
    /**
     * Locked at expand/collapse start: pivot + host size for TransformOrigin.
     * Must use the *same* geometry for this animation frame — never mix last
     * orientation's px with this orientation's screen size.
     */
    private var morphPivotX by mutableStateOf(0)
    private var morphPivotY by mutableStateOf(0)
    private var morphHostW by mutableStateOf(1)
    private var morphHostH by mutableStateOf(1)
    /**
     * true only while the WM shell is the FAB-sized window. Keeps FAB layout mode in
     * lockstep with [applyWindowMode] so collapse never jumps offset→shell mid-frame.
     */
    private var miniShellLayout by mutableStateOf(false)
    /**
     * Compose-layer sequential crossfade for expand/collapse (API 21–36).
     * Must NOT rely on [ComposeView.setAlpha] — many OEMs ignore View alpha on
     * ComposeView, so fades looked like hard cuts. Drive [graphicsLayer] instead.
     */
    private var shellCrossfadeAlpha by mutableStateOf(1f)
    private var shellCrossfadeAnimator: ValueAnimator? = null
    /** Birth fade waiting for the first draw pass — see [startBirthCrossfadeAfterFirstFrame]. */
    private var birthCrossfadeDetach: (() -> Unit)? = null
    private var birthCrossfadeFallback: Runnable? = null
    /** True while FLAG_NOT_FOCUSABLE is lifted for the rail search box. */
    private var overlayImeFocusHeld = false
    /** ElapsedRealtime when IME focus was last handed back — skip bringToFront briefly. */
    private var overlayImeReleasedAtElapsed = 0L
    private var isDraggingMiniFab = false
    /**
     * Finger currently down anywhere on this overlay window (expanded player,
     * lyrics, Mass rail, or the FAB itself). While true, pause-idle and
     * queue-clear grace clocks must not collapse to FAB or hide the overlay;
     * DOWN/UP/CANCEL also restart those clocks via [noteOverlayUserActivity].
     */
    private var isUserTouchingOverlay = false
    /** Guards against double-tap; also read by Compose while retract is in flight. */
    private var isMorphAnimating by mutableStateOf(false)

    /**
     * Expand handoff while overlay split is on. The full player must count as
     * showing before the fade, so the pane rect is already set when it appears.
     */
    private var holdSplitDuringExpand = false
    private var coverLoadJob: Job? = null
    /** Warm [LyricsRepository] while FAB is collapsed so expand paints from memory. */
    private var lyricsWarmJob: Job? = null
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            onOverlayConfigurationChanged(newConfig)
        }

        override fun onLowMemory() = Unit
    }
    private val finishExpandRunnable = Runnable {
        overlayExpanded = true
        shellCrossfadeAlpha = 1f
        isMorphAnimating = false
        holdSplitDuringExpand = false
        syncMediaChrome()
        OverlayLayerSplit.sync()
        if (userPrefersExpanded) revealChromeForForcedExpand()
    }
    private val finishCollapseRunnable = Runnable {
        cancelShellCrossfadeAnimator()
        applyWindowMode(collapsed = true)
        overlayExpanded = false
        shellCrossfadeAlpha = 1f
        isMorphAnimating = false
        applyKeepScreenOn(false)
        syncMediaChrome()
        OverlayLayerSplit.sync()
        raiseCollapsedFabAboveBrowser()
    }

    /**
     * Empty-queue grace: identity stays painted on FAB until force teardown.
     * Expanded detailed view → 30s idle collapses to FAB; FAB waits 60s then hides.
     */
    private var queueClearedGraceActive = false
    private val queueClearedCollapseToFabRunnable = Runnable { onQueueClearedCollapseToFab() }
    private val queueClearedForceHideRunnable = Runnable { onQueueClearedForceHide() }

    private fun onQueueClearedCollapseToFab() {
        if (!queueClearedGraceActive) return
        // Finger still on the overlay — never yank it to FAB mid-interaction;
        // the release (UP/CANCEL) restarts this clock via [noteOverlayUserActivity].
        if (isUserTouchingOverlay) {
            handler.postDelayed(queueClearedCollapseToFabRunnable, QUEUE_CLEAR_EXPANDED_IDLE_MS)
            return
        }
        if (overlayExpanded) {
            persistAsMini()
        }
        scheduleQueueClearedFabForceHide()
    }

    private fun onQueueClearedForceHide() {
        if (!queueClearedGraceActive) return
        // Do not tear down under the user's finger (e.g. dragging the FAB).
        if (isUserTouchingOverlay) {
            handler.postDelayed(queueClearedForceHideRunnable, QUEUE_CLEAR_FAB_HIDE_MS)
            return
        }
        queueClearedGraceActive = false
        VoiceSatelliteService.getInstance()?.sendspinManager?.finishQueueClearedTeardown()
            ?: hide(force = true)
    }

    /**
     * Waiting-only shell (cold start / HA empty expand / MA clear wipe) has no
     * upstream event left to dismiss it — chrome back / HA OFF were the only
     * exits. After [WAITING_SHELL_TEARDOWN_MS] without real media, tear down via
     * the same force-hide HA OFF uses on an empty shell; teardown's
     * [syncMediaChrome] then mirrors HA `vinyl_cover_display` back to off.
     */
    private val waitingShellTeardownRunnable = Runnable {
        if (!isWaitingPlaceholderOnly()) return@Runnable
        val visible =
            overlayRoot?.visibility == View.VISIBLE &&
                composeView?.visibility == View.VISIBLE
        if (!visible) return@Runnable
        Log.d(TAG, "waiting-only shell idle ${WAITING_SHELL_TEARDOWN_MS}ms → force teardown")
        hide(force = true)
    }

    /** (Re)arm while the painted shell is waiting-only; cancels once real media shows. */
    private fun rearmWaitingShellTeardown() {
        handler.removeCallbacks(waitingShellTeardownRunnable)
        if (isWaitingPlaceholderOnly()) {
            handler.postDelayed(waitingShellTeardownRunnable, WAITING_SHELL_TEARDOWN_MS)
        }
    }

    /**
     * First-run discoverability toast for the full-screen player: system Back is
     * dead here (NOT_FOCUSABLE), so the exit gesture is long-press 3s → «Back».
     * Shown 1s after the player becomes visible, at most [LONG_PRESS_HINT_MAX_SHOWS]
     * times per device install (counted when actually displayed, persisted).
     */
    private var longPressHintJob: Job? = null

    private fun longPressHintShownCount(): Int {
        val prefs = getSharedPreferences(HINT_PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(KEY_LONG_PRESS_HINT_COUNT, -1).takeIf { it >= 0 }
            ?: if (prefs.contains(KEY_LONG_PRESS_HINT_SHOWN_LEGACY)) {
                // Older builds remembered "shown" without a count — treat as 1 so
                // upgraders get the remaining shows instead of all-or-nothing.
                1.also { prefs.edit().putInt(KEY_LONG_PRESS_HINT_COUNT, 1).apply() }
            } else {
                0
            }
    }

    private fun scheduleLongPressHintIfNeeded() {
        longPressHintJob?.cancel()
        if (longPressHintShownCount() >= LONG_PRESS_HINT_MAX_SHOWS) return
        longPressHintJob = coroutineScope.launch {
            delay(LONG_PRESS_HINT_DELAY_MS)
            // Must still be expanded — a quick collapse during the delay cancels the hint.
            if (!overlayExpanded) return@launch
            val count = longPressHintShownCount()
            if (count >= LONG_PRESS_HINT_MAX_SHOWS) return@launch
            getSharedPreferences(HINT_PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_LONG_PRESS_HINT_COUNT, count + 1)
                .apply()
            AvaToast.show(
                applicationContext,
                R.string.media_overlay_long_press_back_hint,
                tag = LONG_PRESS_HINT_TAG,
                durationMs = AvaToast.LONG_MS,
            )
        }
    }

    private fun cancelLongPressHint() {
        longPressHintJob?.cancel()
        longPressHintJob = null
    }

    /**
     * Shared pause-idle tuck for HA + Sendspin (not empty-queue grace).
     * 30s yield → FAB (mini) or temporary hide (legacy); already-FAB → tuck at 30s;
     * after soft→FAB wait another 60s then temporary hide. Keeps identity so the
     * next play can [show] again. Idempotent unless [restart].
     */
    private var pauseIdleArmed = false
    /**
     * True while [pauseIdleForceRunnable] owns the pause-idle clock (soft yield
     * already collapsed to FAB). Tells [noteOverlayUserActivity] which phase to
     * restart on touch.
     */
    private var pauseIdleForcePhase = false
    private val pauseIdleSoftRunnable = Runnable { onPauseIdleYield() }
    private val pauseIdleForceRunnable = Runnable { onPauseIdleForce() }
    private val sendspinSettingsStore by lazy {
        SendspinSettingsStore(applicationContext.sendspinSettingsStore)
    }
    private val playerSettingsStore by lazy {
        PlayerSettingsStore(applicationContext.playerSettingsStore)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Honor VS/DataStore arm before any Intent can race the settings Flow.
        enableEqMiniPlayer = preferredEqMini
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.MEDIA_PLAYER,
            showing = { isExpandedSplitShowing() },
            host = { overlayRoot },
            apply = { frame -> applyLayerSplit(frame) },
        )
        lastOrientation = resources.configuration.orientation
        createOverlayView()
        applicationContext.registerComponentCallbacks(configurationCallbacks)
        // Seed UI from process memory before any Intent arrives.
        applyMemoryCacheToUi()
        coroutineScope.launch {
            playerSettingsStore.getFlow().collect { settings ->
                overlayStyle = MediaOverlayStyle.fromStored(settings.mediaOverlayStyle)
                val mini = settings.enableEqMiniPlayer
                if (!isDraggingMiniFab) {
                    applySavedFabPosition(settings.eqMiniFabNormX, settings.eqMiniFabNormY,
                        settings.eqMiniFabX, settings.eqMiniFabY)
                    if (mini && !overlayExpanded && !windowIsFullScreen && !isMorphAnimating) {
                        applyWindowMode(collapsed = true)
                    }
                }
                if (enableEqMiniPlayer == mini) return@collect
                enableEqMiniPlayer = mini
                if (!mini) {
                    miniFabNormX = -1f
                    miniFabNormY = -1f
                    refreshMiniFabDrawPosition()
                    // Setting off: tear down completely (legacy auto overlay only).
                    hide(force = true)
                }
                // Setting on: arm only. Do not snapshot/paint — wait for ACTION_SHOW.
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        composeView = ComposeView(this).apply {
            // Keep the WRAP_CONTENT window fully clear so circular shadow pads
            // never flash opaque square corners behind the clipped FAB.
            setBackgroundColor(Color.TRANSPARENT)
            setViewTreeLifecycleOwner(this@VinylCoverService)
            setViewTreeViewModelStoreOwner(this@VinylCoverService)
            setViewTreeSavedStateRegistryOwner(this@VinylCoverService)
            
            setContent {
                val callbacks = remember(this@VinylCoverService) {
                    MusicOverlayCallbacks(
                        service = this@VinylCoverService,
                        getIsSendspinSource = { isSendspinSource },
                        getIsPlaying = { isPlaying },
                        setIsPlaying = {
                            isPlaying = it
                            MediaOverlayMemoryCache.updatePlayback(it)
                        },
                        getRepeatMode = { repeatMode },
                        setRepeatMode = { repeatMode = it },
                        getShuffleEnabled = { shuffleEnabled },
                        setShuffleEnabled = { shuffleEnabled = it },
                        setVolumeLevel = { volumeLevel = it },
                        getSkipStateUpdateUntil = { skipStateUpdateUntil },
                        setSkipStateUpdateUntil = { skipStateUpdateUntil = it },
                        onDismissOverlay = {
                            if (enableEqMiniPlayer && hasDisplayableContent()) {
                                persistAsMini()
                            } else {
                                hide(force = true)
                            }
                        },
                        // Mini mode: title blanks on next/prev must never auto-collapse
                        // (cover/artist still present). A truly empty transparent shell
                        // must NOT be immune — let 2s onInvalidData tear it down.
                        suppressInvalidTeardown = { enableEqMiniPlayer && hasDisplayableContent() },
                    )
                }
                // Birth / teardown / expand-collapse fades use Compose graphicsLayer
                // via [shellCrossfadeAlpha] — ComposeView View.alpha is flaky on many OEMs.
                if (enableEqMiniPlayer) {
                    val collapsing = !overlayExpanded
                    val glassAlpha = if (overlayExpanded) 1f else 0f
                    val fabAlpha = if (collapsing && miniShellLayout) 1f else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = shellCrossfadeAlpha
                            },
                    ) {
                        // Pre-cached glass — always in composition while mini mode is on.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = glassAlpha
                                    scaleX = 1f
                                    scaleY = 1f
                                    clip = true
                                },
                        ) {
                            when (overlayStyle) {
                                MediaOverlayStyle.DETAILED -> DetailedMetadataMusicPlayerView(
                                    coverUrl = coverUrl,
                                    coverBitmap = coverBitmap,
                                    songTitle = songTitle,
                                    artistName = artistName,
                                    albumName = albumName,
                                    isPlaying = isPlaying,
                                    currentTimeMs = currentTimeMs,
                                    totalTimeMs = totalTimeMs,
                                    volumeLevel = volumeLevel,
                                    repeatMode = repeatMode,
                                    shuffleEnabled = shuffleEnabled,
                                    isSendspinSource = isSendspinSource,
                                    lyricAudibleLagMs = if (isSendspinSource) lyricAudibleLagMs else 0L,
                                    playheadForceEpoch = playheadForceEpoch,
                                    onPlayPauseClick = callbacks.onPlayPauseClick,
                                    onPreviousClick = callbacks.onPreviousClick,
                                    onNextClick = callbacks.onNextClick,
                                    onVolumeChange = callbacks.onVolumeChange,
                                    onRepeatClick = callbacks.onRepeatClick,
                                    onShuffleClick = callbacks.onShuffleClick,
                                    onSeekClick = if (isSendspinSource) callbacks.onSeekClick else null,
                                    onRevealChrome = callbacks.onRevealChrome,
                                    onInvalidData = callbacks.onInvalidData,
                                    fullPlayerActive = overlayExpanded,
                                    karaokeLyrics = true,
                                )
                                MediaOverlayStyle.MINIMAL -> GlassMusicPlayerView(
                                    coverUrl = coverUrl,
                                    coverBitmap = coverBitmap,
                                    songTitle = songTitle,
                                    artistName = artistName,
                                    isPlaying = isPlaying,
                                    volumeLevel = volumeLevel,
                                    repeatMode = repeatMode,
                                    shuffleEnabled = shuffleEnabled,
                                    isSendspinSource = isSendspinSource,
                                    onPlayPauseClick = callbacks.onPlayPauseClick,
                                    onPreviousClick = callbacks.onPreviousClick,
                                    onNextClick = callbacks.onNextClick,
                                    onVolumeChange = callbacks.onVolumeChange,
                                    onRepeatClick = callbacks.onRepeatClick,
                                    onShuffleClick = callbacks.onShuffleClick,
                                    onRevealChrome = callbacks.onRevealChrome,
                                    onInvalidData = callbacks.onInvalidData,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .then(
                                    if (miniShellLayout) {
                                        // Idle mini shell is already positioned via WM params.
                                        Modifier.fillMaxSize()
                                    } else {
                                        // Full-screen: park FAB at the remembered spot (same
                                        // screen coords the shell will use after collapse).
                                        Modifier.offset {
                                            IntOffset(miniFabDrawX, miniFabDrawY)
                                        }
                                    },
                                )
                                .graphicsLayer {
                                    alpha = fabAlpha
                                    clip = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            key("stableEqMiniFab") {
                                val waitingForMedia =
                                    songTitle == getString(R.string.media_overlay_waiting_for_media)
                                CircleEqMiniPlayerFab(
                                    coverBitmap = coverBitmap,
                                    isPlaying = isPlaying,
                                    currentTimeMs = if (waitingForMedia) 0L else currentTimeMs,
                                    totalTimeMs = if (waitingForMedia) 0L else totalTimeMs,
                                    waitingForMedia = waitingForMedia,
                                    interactionEnabled = !overlayExpanded && !isMorphAnimating && !isDraggingMiniFab,
                                    onExpandClick = {
                                        TouchSoundHelper.playClick(this@VinylCoverService)
                                        expandFromMini()
                                        notifyPeerWindowIfEnabled(expanded = true)
                                    },
                                    onPlayPauseClick = callbacks.onPlayPauseClick,
                                )
                            }
                        }
                    }
                } else {
                    // Legacy full overlay (no FAB): same shell fade channel as mini mode.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = shellCrossfadeAlpha
                            },
                    ) {
                        when (overlayStyle) {
                            MediaOverlayStyle.DETAILED -> DetailedMetadataMusicPlayerView(
                                coverUrl = coverUrl,
                                coverBitmap = coverBitmap,
                                songTitle = songTitle,
                                artistName = artistName,
                                albumName = albumName,
                                isPlaying = isPlaying,
                                currentTimeMs = currentTimeMs,
                                totalTimeMs = totalTimeMs,
                                volumeLevel = volumeLevel,
                                repeatMode = repeatMode,
                                shuffleEnabled = shuffleEnabled,
                                isSendspinSource = isSendspinSource,
                                lyricAudibleLagMs = if (isSendspinSource) lyricAudibleLagMs else 0L,
                                playheadForceEpoch = playheadForceEpoch,
                                onPlayPauseClick = callbacks.onPlayPauseClick,
                                onPreviousClick = callbacks.onPreviousClick,
                                onNextClick = callbacks.onNextClick,
                                onVolumeChange = callbacks.onVolumeChange,
                                onRepeatClick = callbacks.onRepeatClick,
                                onShuffleClick = callbacks.onShuffleClick,
                                onSeekClick = if (isSendspinSource) callbacks.onSeekClick else null,
                                onRevealChrome = callbacks.onRevealChrome,
                                onInvalidData = callbacks.onInvalidData,
                                // Legacy (no FAB): surface stays up until hide; treat as active
                                // while the overlay shell is the full player.
                                fullPlayerActive = overlayExpanded,
                                karaokeLyrics = true,
                            )
                            MediaOverlayStyle.MINIMAL -> GlassMusicPlayerView(
                                coverUrl = coverUrl,
                                coverBitmap = coverBitmap,
                                songTitle = songTitle,
                                artistName = artistName,
                                isPlaying = isPlaying,
                                volumeLevel = volumeLevel,
                                repeatMode = repeatMode,
                                shuffleEnabled = shuffleEnabled,
                                isSendspinSource = isSendspinSource,
                                onPlayPauseClick = callbacks.onPlayPauseClick,
                                onPreviousClick = callbacks.onPreviousClick,
                                onNextClick = callbacks.onNextClick,
                                onVolumeChange = callbacks.onVolumeChange,
                                onRepeatClick = callbacks.onRepeatClick,
                                onShuffleClick = callbacks.onShuffleClick,
                                onRevealChrome = callbacks.onRevealChrome,
                                onInvalidData = callbacks.onInvalidData,
                            )
                        }
                    }
                }
            }
        }

        // WM-attached root. Collapsed mini uses a FAB-sized shell (see applyWindowMode)
        // so touches outside pass through — same pattern as VolumeControl / DashboardChrome.
        // ViewTree owners MUST live on this root; Compose walks from the window decor.
        // Drag: intercept after touch-slop so taps still reach the Compose FAB.
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val root = object : FrameLayout(this@VinylCoverService) {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startParamX = 0
            private var startParamY = 0
            private var dragging = false

            private fun canDragMiniFab(): Boolean {
                return enableEqMiniPlayer &&
                    !overlayExpanded &&
                    !windowIsFullScreen &&
                    !isMorphAnimating
            }

            // Every touch on this window counts as "user is here" for the idle
            // clocks (pause-idle / queue-clear grace) — regardless of which child
            // (player controls, lyrics, Mass rail, FAB) consumes the gesture.
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val action = ev.actionMasked
                if (action == MotionEvent.ACTION_DOWN) {
                    isUserTouchingOverlay = true
                    noteOverlayUserActivity()
                }
                val chromeAlreadyShown = DashboardOverlayChrome.isShown(
                    DashboardOverlayChrome.Kind.MEDIA_PLAYER,
                )
                val handled = super.dispatchTouchEvent(ev)
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    isUserTouchingOverlay = false
                    noteOverlayUserActivity()
                    // API <= 27: FLAG_NOT_FOCUSABLE swallows system Back, and the
                    // Compose 2s hold never finishes. These panels end the touch
                    // immediately (dumpsys: touchscreen UP, then mouse hover that
                    // is not delivered to this window), so wait-for-hold stays
                    // cancelled and «Back» never leaves GONE. Show it on release
                    // after the click has already been delivered.
                    if (!chromeAlreadyShown) {
                        revealLegacyBackChrome()
                    }
                }
                return handled
            }

            /**
             * Only reachable while the rail search box holds IME focus — the window is
             * otherwise FLAG_NOT_FOCUSABLE and receives no keys at all. Back has to hand the
             * focus flag back rather than die here, or a parked overlay would keep eating the
             * launcher's Back key.
             */
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && overlayImeFocusHeld) {
                    if (event.action == KeyEvent.ACTION_UP) releaseOverlayImeFocus()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            /** A hidden shell cannot own a search box; never let the focus flag outlive it. */
            override fun onVisibilityChanged(changedView: View, visibility: Int) {
                super.onVisibilityChanged(changedView, visibility)
                if (visibility != View.VISIBLE) releaseOverlayImeFocus()
            }

            override fun onDetachedFromWindow() {
                super.onDetachedFromWindow()
                if (isDraggingMiniFab || visibility == View.VISIBLE) {
                    handler.post { healMiniIfMissing() }
                }
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (!canDragMiniFab()) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        val params = windowParams
                        startParamX = params?.x ?: miniFabDrawX
                        startParamY = params?.y ?: miniFabDrawY
                        dragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) {
                            val dx = abs(ev.rawX - downRawX)
                            val dy = abs(ev.rawY - downRawY)
                            if (dx > touchSlop || dy > touchSlop) {
                                dragging = true
                                isDraggingMiniFab = true
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                        }
                        return dragging
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dragging = false
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (!canDragMiniFab() && !dragging) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) return false
                        val dx = (ev.rawX - downRawX).roundToInt()
                        val dy = (ev.rawY - downRawY).roundToInt()
                        moveMiniFabTo(startParamX + dx, startParamY + dy)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dragging = false
                            isDraggingMiniFab = false
                            persistMiniFabPosition()
                            return true
                        }
                        isDraggingMiniFab = false
                    }
                }
                return dragging
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            setViewTreeLifecycleOwner(this@VinylCoverService)
            setViewTreeViewModelStoreOwner(this@VinylCoverService)
            setViewTreeSavedStateRegistryOwner(this@VinylCoverService)
        }
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlayRoot = root
        DashboardOverlayChrome.attach(root, DashboardOverlayChrome.Kind.MEDIA_PLAYER)
        refreshMiniFabDrawPosition()
        lockMorphGeometry()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Match Weather/QuickEntity: TOP|START + origin. Gravity.CENTER with
            // SHORT_EDGES + NO_LIMITS shifts the whole MATCH_PARENT window right
            // in landscape when a left cutout makes the "center" frame asymmetric.
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // OEM default frame animation scales from TOP|START. Size changes
            // (FAB shell ↔ MATCH_PARENT) must not inherit that interpolator.
            windowAnimations = R.style.AppWindowNoAnimation
            // The rail search box raises the IME (see applyOverlayImeFocus). ADJUST_RESIZE is
            // load-bearing, not cosmetic: TYPE_APPLICATION_OVERLAY sits *above* the IME in
            // z-order, so a window that keeps its full height simply draws over the keyboard —
            // it rises but stays invisible and untouchable. Giving up the bottom strip is what
            // lets it show through. STATE_UNCHANGED keeps the WM from second-guessing us about
            // when the keyboard belongs on screen. Same pairing the browser overlay uses on an
            // otherwise identical fullscreen NO_LIMITS window.
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            OverlayOrientation.apply(this)
        }

        windowParams = params
        // Fresh params carry FLAG_NOT_FOCUSABLE, so any held state from a previous window
        // is stale — leaving it set would make the next grant a no-op and kill typing.
        overlayImeFocusHeld = false

        try {
            windowManager?.addView(root, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            root.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create glass music player window", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                val allowEmpty = intent.getBooleanExtra(EXTRA_ALLOW_EMPTY, false)
                val forceExpanded = intent.getBooleanExtra(EXTRA_FORCE_EXPANDED, false)
                var url = intent.getStringExtra(EXTRA_COVER_URL)
                val title = intent.getStringExtra(EXTRA_SONG_TITLE)
                val artist = intent.getStringExtra(EXTRA_ARTIST_NAME)
                val album = intent.getStringExtra(EXTRA_ALBUM_NAME)
                // HA empty/expanded toggle: hydrate cache before claiming ownership so we
                // do not steal the active protocol (HA vs Sendspin) with a blank SHOW.
                if (allowEmpty || forceExpanded) {
                    applyMemoryCacheToUi()
                }
                isSendspinSource = intent.getBooleanExtra(EXTRA_IS_SENDSPIN_SOURCE, isSendspinSource)
                // Latter wins: SHOW always (re)claims progress ownership.
                claimOverlayProgressOwner(isSendspinSource)
                if (intent.hasExtra(EXTRA_LYRIC_AUDIBLE_LAG_MS)) {
                    lyricAudibleLagMs = intent.getLongExtra(EXTRA_LYRIC_AUDIBLE_LAG_MS, lyricAudibleLagMs)
                        .coerceIn(0L, LYRIC_AUDIBLE_LAG_MAX_MS)
                } else if (!isSendspinSource) {
                    lyricAudibleLagMs = 0L
                }

                if (!allowEmpty && !forceExpanded) {
                    applyMemoryCacheToUi()
                }

                if (url == null && cachedHaCoverUrl != null && !isSendspinSource) {
                    url = cachedHaCoverUrl
                }
                if (url == null) {
                    url = coverUrl
                }

                applyOverlayIdentity(
                    title = title,
                    artist = artist,
                    album = album,
                    hasArtistExtra = intent.hasExtra(EXTRA_ARTIST_NAME),
                    hasAlbumExtra = intent.hasExtra(EXTRA_ALBUM_NAME),
                    clearPlayheadOnTitleChange = !intent.hasExtra(EXTRA_CURRENT_TIME_MS),
                )

                if (intent.hasExtra(EXTRA_IS_PLAYING)) {
                    isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
                }
                if (intent.hasExtra(EXTRA_CURRENT_TIME_MS)) {
                    applyCurrentTimeMs(intent.getLongExtra(EXTRA_CURRENT_TIME_MS, currentTimeMs))
                }
                if (intent.hasExtra(EXTRA_TOTAL_TIME_MS)) {
                    applyTotalTimeMs(intent.getLongExtra(EXTRA_TOTAL_TIME_MS, totalTimeMs))
                }
                
                if (url != null && (url != coverUrl || coverBitmap == null)) {
                    coverUrl = url
                    loadCoverImage(url)
                }
                
                // Do not flush placeholder/empty HA toggles back over a live protocol cache.
                if (!allowEmpty) {
                    syncUiToMemoryCache()
                    warmLyricsCacheInBackground()
                }
                if (forceExpanded) {
                    userPrefersExpanded = true
                }
                if (allowEmpty || hasDisplayableContent()) {
                    // Cover/artist-only cache still needs the placeholder: a blank
                    // title mounts a fully TRANSPARENT shell (Detailed/Glass refuse
                    // to draw without a title). No-op when a real title exists.
                    if (allowEmpty) {
                        ensureExpandableTitle()
                    }
                    show(allowEmpty = allowEmpty, forceExpanded = forceExpanded)
                    if (forceExpanded || allowEmpty) {
                        // Re-pull HA/Sendspin metadata after hide↔show (cache may be stale).
                        requestMediaSnapshot()
                    }
                }
                // After SHOW's posted paint: playing drops tuck; paused re-arms.
                // Must run on the handler so visibility is already applied.
                if (intent.hasExtra(EXTRA_IS_PLAYING)) {
                    handler.post { reconcilePauseIdleAfterPlaybackStateChange() }
                }
            }
            ACTION_HIDE_EXPANDED_FROM_HA -> {
                hideExpandedFromHaInternal()
            }
            ACTION_HIDE -> {
                val force = intent.getBooleanExtra(EXTRA_FORCE_HIDE, false)
                hide(force = force)
            }
            ACTION_BEGIN_QUEUE_CLEARED_GRACE -> {
                beginQueueClearedGraceInternal()
            }
            ACTION_END_QUEUE_CLEARED_GRACE -> {
                endQueueClearedGraceInternal()
            }
            ACTION_SCHEDULE_PAUSE_IDLE -> {
                schedulePauseIdleTeardownInternal(
                    restart = intent.getBooleanExtra(EXTRA_PAUSE_IDLE_RESTART, false),
                )
            }
            ACTION_CANCEL_PAUSE_IDLE -> {
                cancelPauseIdleTeardownInternal()
            }
            ACTION_CLEAR_QUEUE_CONTENT -> {
                clearQueueDisplayState()
            }
            ACTION_RESET_WAITING_FOR_MEDIA -> {
                resetToWaitingForMediaInternal()
            }
            ACTION_ENSURE_PERSISTENT_MINI -> {
                // Arm flag only — never paint or pull HA/Sendspin caches here.
                enableEqMiniPlayer = true
                preferredEqMini = true
            }
            ACTION_SET_HA_COVER -> {
                if (!ownsOverlayProgress(fromSendspin = false)) return
                val url = intent.getStringExtra(EXTRA_COVER_URL)
                if (!url.isNullOrEmpty()) {
                    cachedHaCoverUrl = url
                    if (overlayRoot?.visibility == View.VISIBLE && (url != coverUrl || coverBitmap == null)) {
                        coverUrl = url
                        loadCoverImage(url)
                    }
                }
            }
            ACTION_UPDATE_COVER -> {
                val fromSendspin = intent.getBooleanExtra(EXTRA_IS_SENDSPIN_SOURCE, false)
                if (!ownsOverlayProgress(fromSendspin)) return
                val url = intent.getStringExtra(EXTRA_COVER_URL)
                url?.let {
                    coverUrl = it
                    loadCoverImage(it)
                }
            }
            ACTION_UPDATE_METADATA -> {
                val fromSendspin = intent.getBooleanExtra(EXTRA_IS_SENDSPIN_SOURCE, false)
                if (!ownsOverlayProgress(fromSendspin)) return
                val title = intent.getStringExtra(EXTRA_SONG_TITLE)
                val artist = intent.getStringExtra(EXTRA_ARTIST_NAME)
                val album = intent.getStringExtra(EXTRA_ALBUM_NAME)
                
                applyOverlayIdentity(
                    title = title,
                    artist = artist,
                    album = album,
                    hasArtistExtra = intent.hasExtra(EXTRA_ARTIST_NAME),
                    hasAlbumExtra = intent.hasExtra(EXTRA_ALBUM_NAME),
                    clearPlayheadOnTitleChange = !intent.hasExtra(EXTRA_CURRENT_TIME_MS),
                )
                // Prefer explicit playback state. Never invent "playing" from a
                // non-empty title — that fights pause and shows EQ on the mini FAB.
                // Sendspin upstream always wins (ignore optimistic HA skip window).
                if (intent.hasExtra(EXTRA_IS_PLAYING)) {
                    val fromSendspin = intent.getBooleanExtra(EXTRA_IS_SENDSPIN_SOURCE, false)
                    if (fromSendspin || System.currentTimeMillis() > skipStateUpdateUntil) {
                        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
                    }
                }
                if (intent.hasExtra(EXTRA_CURRENT_TIME_MS)) {
                    applyCurrentTimeMs(intent.getLongExtra(EXTRA_CURRENT_TIME_MS, currentTimeMs))
                }
                if (intent.hasExtra(EXTRA_TOTAL_TIME_MS)) {
                    applyTotalTimeMs(intent.getLongExtra(EXTRA_TOTAL_TIME_MS, totalTimeMs))
                }
                syncUiToMemoryCache()
                warmLyricsCacheInBackground()
                // Legacy vinyl: metadata drip may re-show after reconnect.
                // Mini FAB: never birth from HA attribute hydration — only ACTION_SHOW.
                if (hasDisplayableContent()) {
                    if (isEqMiniArmed()) {
                        if (overlayRoot?.visibility == View.VISIBLE) {
                            show()
                        }
                    } else {
                        show()
                    }
                }
            }
            ACTION_UPDATE_PLAYBACK_STATE -> {
                val fromSendspin = intent.getBooleanExtra(EXTRA_IS_SENDSPIN_SOURCE, false)
                if (!ownsOverlayProgress(fromSendspin)) return
                // Sendspin/MA playback_state is authoritative — never let the HA
                // next/prev skip window leave ▶/❚❚ stuck out of sync.
                if (fromSendspin || System.currentTimeMillis() > skipStateUpdateUntil) {
                    isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
                    MediaOverlayMemoryCache.updatePlayback(isPlaying)
                    // Play blips used to cancel pause-idle without re-arm on the
                    // following pause — FAB stayed up forever. Reconcile here.
                    // Playing: drop tuck clock and surface immediately (incl. after
                    // pause-idle temporary hide). Post so it runs after any
                    // in-flight show()/hide visibility posts.
                    handler.post { reconcilePauseIdleAfterPlaybackStateChange() }
                }
            }
            ACTION_UPDATE_PROGRESS -> {
                val fromSendspin = intent.getBooleanExtra(EXTRA_IS_SENDSPIN_SOURCE, false)
                if (!ownsOverlayProgress(fromSendspin)) return
                var playheadAccepted = !intent.hasExtra(EXTRA_CURRENT_TIME_MS)
                if (intent.hasExtra(EXTRA_CURRENT_TIME_MS)) {
                    val forcePlayhead = intent.getBooleanExtra(EXTRA_FORCE_PLAYHEAD, false)
                    val incoming = intent.getLongExtra(EXTRA_CURRENT_TIME_MS, currentTimeMs)
                        .coerceAtLeast(0L)
                    applyCurrentTimeMs(incoming, force = forcePlayhead)
                    playheadAccepted = forcePlayhead || currentTimeMs == incoming
                    if (forcePlayhead) {
                        resetPlayheadZeroGlitchGuard()
                        playheadForceEpoch += 1
                    }
                }
                if (intent.hasExtra(EXTRA_TOTAL_TIME_MS)) {
                    applyTotalTimeMs(intent.getLongExtra(EXTRA_TOTAL_TIME_MS, totalTimeMs))
                }
                if (intent.hasExtra(EXTRA_LYRIC_AUDIBLE_LAG_MS)) {
                    lyricAudibleLagMs = intent.getLongExtra(EXTRA_LYRIC_AUDIBLE_LAG_MS, lyricAudibleLagMs)
                        .coerceIn(0L, LYRIC_AUDIBLE_LAG_MAX_MS)
                } else if (!isSendspinSource) {
                    lyricAudibleLagMs = 0L
                }
                // Write the seat that landed, not the pre-apply UI field. A
                // rejected 0:00 glitch must not push the old mid-track second
                // back over a hard-seat the companion already stored.
                MediaOverlayMemoryCache.updateProgress(
                    currentTimeMs = if (intent.hasExtra(EXTRA_CURRENT_TIME_MS) && playheadAccepted) {
                        currentTimeMs
                    } else {
                        null
                    },
                    totalTimeMs = if (intent.hasExtra(EXTRA_TOTAL_TIME_MS)) totalTimeMs else null,
                    lyricAudibleLagMs = lyricAudibleLagMs,
                )
            }
            ACTION_UPDATE_PLAYBACK_SETTINGS -> {
                if (intent.hasExtra(EXTRA_VOLUME_LEVEL)) {
                    volumeLevel = intent.getFloatExtra(EXTRA_VOLUME_LEVEL, volumeLevel)
                }
                // An upstream report is the only confirmation either protocol offers,
                // so landing one settles the bet armed by the tap — whatever the value.
                if (intent.hasExtra(EXTRA_REPEAT_MODE)) {
                    confirmRepeatFromUpstream()
                    repeatMode = intent.getStringExtra(EXTRA_REPEAT_MODE) ?: repeatMode
                }
                if (intent.hasExtra(EXTRA_SHUFFLE_ENABLED)) {
                    confirmShuffleFromUpstream()
                    shuffleEnabled = intent.getBooleanExtra(EXTRA_SHUFFLE_ENABLED, shuffleEnabled)
                }
            }
        }
    }

    private fun loadCoverImage(url: String) {
        if (url.isBlank() || url == "null" || !url.startsWith("http")) {
            return
        }
        coverLoadJob?.cancel()
        coverLoadJob = coroutineScope.launch {
            try {
                val sendspinLowMemoryMode = if (isSendspinSource) {
                    sendspinSettingsStore.get().lowMemoryMode
                } else {
                    false
                }
                suspend fun attemptDecode(): Bitmap? = withContext(Dispatchers.IO) {
                    val preferredMaxSize = getPreferredCoverMaxSize(sendspinLowMemoryMode)
                    decodeCoverBitmapFromUrl(url, preferredMaxSize, sendspinLowMemoryMode)
                        ?: decodeCoverBitmapFromUrl(url, preferredMaxSize / 2, true)
                }

                var bitmap = try {
                    attemptDecode()
                } catch (e: Exception) {
                    Log.w(TAG, "Load cover failed, will retry: $url", e)
                    null
                }
                // One brief retry for network blips; same URL, then give up.
                if (bitmap == null && isActive && url == coverUrl) {
                    Log.i(TAG, "Cover fetch missed; retrying once: $url")
                    delay(COVER_LOAD_RETRY_DELAY_MS)
                    if (!isActive || url != coverUrl) return@launch
                    bitmap = try {
                        attemptDecode()
                    } catch (e: Exception) {
                        Log.e(TAG, "Load cover failed after retry: $url", e)
                        null
                    }
                }
                if (!isActive || url != coverUrl) {
                    // Never assigned to Compose — safe to free immediately.
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                    return@launch
                }

                coverBitmap = bitmap
                // Do not recycle the previous cover: Compose Image/BitmapPainter may
                // still draw it this frame (Glass/Detailed also keep sticky covers).
                MediaOverlayMemoryCache.updateCover(url, bitmap)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Load cover OOM: $url", e)
                coverBitmap = null
            }
        }
    }

    private fun getPreferredCoverMaxSize(lowMemoryMode: Boolean): Int {
        val screenMax = min(
            max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            if (lowMemoryMode) 384 else 640
        )
        return screenMax.coerceAtLeast(if (lowMemoryMode) 256 else 384)
    }

    private fun decodeCoverBitmapFromUrl(
        url: String,
        maxSize: Int,
        lowMemoryMode: Boolean
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openCoverConnection(url).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
            inPreferredConfig = Bitmap.Config.RGB_565
            inDither = lowMemoryMode
        }
        return openCoverConnection(url).use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun openCoverConnection(url: String): BufferedInputStream {
        var current = url
        var hops = 0
        while (true) {
            val connection = URL(current).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            HaMediaUrl.applyBearer(connection, current)
            connection.connect()
            val code = connection.responseCode
            if (code in 301..308 && hops < 5) {
                val next = HaMediaUrl.resolveRedirect(current, connection.getHeaderField("Location"))
                connection.disconnect()
                if (next.isNullOrBlank() || next == current) {
                    throw java.io.IOException("HTTP $code redirect")
                }
                current = next
                hops++
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw java.io.IOException("HTTP $code")
            }
            return object : BufferedInputStream(connection.inputStream, 32 * 1024) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2

        while (halfWidth / sampleSize >= maxSize || halfHeight / sampleSize >= maxSize) {
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }
    
    private fun isEqMiniArmed(): Boolean = enableEqMiniPlayer || preferredEqMini

    private fun show(allowEmpty: Boolean = false, forceExpanded: Boolean = false) {
        if (suppressOverlayBirthUntilSessionPlayback && !allowEmpty) {
            return
        }
        // Chrome-back cooldown: block GONE rebirth from PCM/metadata only.
        // HA ON (allowEmpty / forceExpanded) and an already-visible FAB are unchanged.
        if (isUserDismissHideActive() && !allowEmpty && !forceExpanded &&
            overlayRoot?.visibility != View.VISIBLE
        ) {
            return
        }
        if (!allowEmpty && !hasDisplayableContent()) {
            return
        }
        if (forceExpanded) {
            userPrefersExpanded = true
        }
        claimOverlayProgressOwner(isSendspinSource)
        handler.post {
            val host = overlayRoot ?: return@post
            val view = composeView ?: return@post
            if (host.visibility == View.VISIBLE && view.visibility == View.VISIBLE) {
                // Already up — protocol paints only refresh metadata / chrome.
                // Do NOT clear [awaitingPauseIdleAudibleReshow] here: pause-idle tuck
                // may still be fading (still VISIBLE); wiping the latch strands PCM
                // re-show after onEnd sets GONE. Latch clears only on GONE→visible.
                // Form-preserving: never morph FAB↔full here unless forceExpanded
                // (HA ON). Ghost-pause rescue / seek aftermath share this path.
                applyKeepScreenOn(overlayExpanded || forceExpanded)
                if (forceExpanded && !overlayExpanded && !isMorphAnimating) {
                    expandFromMini()
                } else {
                    syncMediaChrome()
                    if (forceExpanded) revealChromeForForcedExpand()
                }
                rearmWaitingShellTeardown()
                return@post
            }
            // Fresh appearance: honor last user preference (FAB vs expanded).
            // Mini FAB must still appear when paused — pause must never hide it.
            awaitingPauseIdleAudibleReshow = false
            val miniArmed = isEqMiniArmed()
            if (miniArmed) {
                enableEqMiniPlayer = true
            }
            val startCollapsed = if (forceExpanded) false else (miniArmed && !userPrefersExpanded)
            overlayExpanded = !startCollapsed
            // Mini FAB shell when collapsed; MATCH_PARENT when expanded.
            applyWindowMode(collapsed = startCollapsed)
            view.animate().cancel()
            cancelShellCrossfadeAnimator()
            // Seed fade-out before VISIBLE so the first drawn frame is transparent
            // (View.alpha on ComposeView is unreliable — both mini and legacy use shell).
            view.alpha = 1f
            shellCrossfadeAlpha = 0f
            host.visibility = View.VISIBLE
            view.visibility = View.VISIBLE
            applyKeepScreenOn(!startCollapsed)
            syncMediaChrome()
            // Same tier as the voice orb: mini FAB stays above idle screensaver.
            // Hard raise only on fresh appearance — already-visible show() returns early above.
            if (startCollapsed) {
                // Own restack only — do not reassert weather/chrome (that flashes
                // both FABs a second time). Mic must still climb: same-type add
                // order would bury it, and its generation latch would never fire.
                OverlayZOrderCoordinator.bringToFront(windowManager, host, windowParams, TAG)
            } else {
                bringToFront()
                // Weather/browser often assert before Sendspin metadata — restack after birth.
                OverlayZOrderCoordinator.onMediaOverlayPresented(this)
            }
            // After the restacks: bringToFront re-adds the window, so arming the fade any
            // earlier would have it racing a detach/re-attach on top of the first draw.
            startBirthCrossfadeAfterFirstFrame(BIRTH_CROSSFADE_MS) {
                if (!startCollapsed) scheduleLongPressHintIfNeeded()
            }
            if (forceExpanded) revealChromeForForcedExpand()
            rearmWaitingShellTeardown()
            if (forceExpanded) scheduleLongPressHintIfNeeded()
        }
    }

    /**
     * Android 5–8 only (API 21–27). The expanded player is [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE],
     * so the system Back key is delivered to whatever sits behind the overlay.
     * The Compose 2s hold is also dead here: the digitizer finishes the touch at
     * once and the rest of the contact arrives as mouse hover, which this window
     * never sees. Pop «Back» on finger-up instead. Newer APIs keep the 2s hold.
     */
    private fun revealLegacyBackChrome() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return
        if (!windowIsFullScreen || !overlayExpanded) return
        DashboardOverlayChrome.onUserTouch(
            this,
            DashboardOverlayChrome.Kind.MEDIA_PLAYER,
            autoHideMs = DashboardOverlayChrome.MEDIA_AUTO_HIDE_MS,
        )
    }

    /**
     * Full-screen expand covers the launcher and is NOT_FOCUSABLE (system Back is
     * dead). Surface «Back» without the 3s long-press so cold-start empty shell
     * stays escapable.
     */
    private fun revealChromeForForcedExpand() {
        val showing =
            overlayRoot?.visibility == View.VISIBLE &&
                composeView?.visibility == View.VISIBLE
        if (!showing) return
        DashboardOverlayChrome.reveal(
            DashboardOverlayChrome.Kind.MEDIA_PLAYER,
            autoHideMs = if (isWaitingPlaceholderOnly()) {
                EMPTY_SHELL_CHROME_AUTO_HIDE_MS
            } else {
                DashboardOverlayChrome.MEDIA_AUTO_HIDE_MS
            },
        )
    }

    /** HA `vinyl_cover_display` OFF: leave FAB if mini+content, else tuck without wiping metadata. */
    private fun hideExpandedFromHaInternal() {
        handler.post {
            val host = overlayRoot
            val showing =
                host?.visibility == View.VISIBLE && composeView?.visibility == View.VISIBLE
            if (!showing) {
                syncHaVinylCoverExpandedVisible(false)
                return@post
            }
            if (!overlayExpanded && !windowIsFullScreen) {
                // Already FAB / hidden expanded — mirror OFF only.
                syncHaVinylCoverExpandedVisible(false)
                return@post
            }
            if (isWaitingPlaceholderOnly()) {
                // Empty HA/sidebar shell: tear down instead of parking a "waiting" FAB.
                hide(force = true)
            } else if (enableEqMiniPlayer && hasDisplayableContent()) {
                persistAsMini()
            } else {
                // Soft tuck: keep MediaOverlayMemoryCache / titles / progress owner so the
                // next HA ON (or protocol tick) can paint real metadata again.
                hideTemporarilyPreservingIdentity()
            }
        }
    }

    /** Rail More toggle: tell the same-stream peer to open or close its song page. */
    private fun notifyPeerWindowIfEnabled(expanded: Boolean) {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_SYNC_PEER_EXPAND, false)
        if (!enabled) return
        AvaSyncOffsetPeer.requestVinylWindow(expanded)
    }

    private fun expandFromMini() {
        handler.post {
            if (isMorphAnimating || overlayExpanded) return@post
            userPrefersExpanded = true
            // User opened full player while paused — restart the yield clock.
            if (!isPlaying) {
                schedulePauseIdleTeardownInternal(restart = true)
            } else {
                cancelPauseIdleTeardownInternal()
            }
            requestMediaSnapshot()
            ensureExpandableTitle()

            val host = overlayRoot ?: return@post
            val view = composeView ?: return@post
            handler.removeCallbacks(finishExpandRunnable)
            handler.removeCallbacks(finishCollapseRunnable)
            view.animate().cancel()
            cancelShellCrossfadeAnimator()
            resetMorphTransform(view)

            val params = windowParams
            val fabX: Int
            val fabY: Int
            if (miniShellLayout && params != null) {
                fabX = params.x
                fabY = params.y
            } else {
                refreshMiniFabDrawPosition()
                fabX = miniFabDrawX
                fabY = miniFabDrawY
            }

            isMorphAnimating = true
            applyKeepScreenOn(true)
            miniFabDrawX = fabX
            miniFabDrawY = fabY
            morphPivotX = fabX
            morphPivotY = fabY
            host.visibility = View.VISIBLE
            view.visibility = View.VISIBLE
            view.alpha = 1f
            shellCrossfadeAlpha = 1f
            handler.postDelayed(finishExpandRunnable, CROSSFADE_SAFETY_MS)

            // Fade FAB out → swap to MATCH_PARENT while invisible → fade glass in.
            // No scale / extra overlay: OEM frame animations are disabled on this window.
            animateShellCrossfade(
                to = 0f,
                durationMs = CROSSFADE_OUT_MS,
                interpolator = AccelerateInterpolator(),
            ) {
                if (!isMorphAnimating) return@animateShellCrossfade
                applyWindowMode(collapsed = false)
                miniFabDrawX = fabX
                miniFabDrawY = fabY
                morphPivotX = fabX
                morphPivotY = fabY
                overlayExpanded = true
                shellCrossfadeAlpha = 0f
                // Split is already on: land in the pane while still invisible.
                // Fading in at MATCH_PARENT then shrinking is the fullscreen flash.
                placeExpandedIntoSplitBeforeReveal(view)
                onQueueClearedExpanded()
                startBirthCrossfadeAfterFirstFrame(CROSSFADE_IN_MS) {
                    handler.removeCallbacks(finishExpandRunnable)
                    isMorphAnimating = false
                    holdSplitDuringExpand = false
                    shellCrossfadeAlpha = 1f
                    syncMediaChrome()
                    OverlayLayerSplit.sync()
                    if (userPrefersExpanded) revealChromeForForcedExpand()
                    scheduleLongPressHintIfNeeded()
                }
            }
        }
    }

    /** Glass/Detailed refuse to draw without a title — keep a localized empty-shell placeholder. */
    private fun ensureExpandableTitle() {
        val waiting = getString(R.string.media_overlay_waiting_for_media)
        if (songTitle.isBlank() && artistName.isBlank() && coverUrl == null && coverBitmap == null) {
            songTitle = waiting
        } else if (songTitle.isBlank()) {
            songTitle = artistName.ifBlank { waiting }
        }
    }

    /** Ask VoiceSatelliteService to push the latest HA or Sendspin media caches. */
    private fun requestMediaSnapshot() {
        coroutineScope.launch {
            repeat(8) { attempt ->
                val vs = VoiceSatelliteService.getInstance()
                if (vs != null) {
                    vs.pushMediaOverlaySnapshot()
                    return@launch
                }
                kotlinx.coroutines.delay(250L * (attempt + 1))
            }
            Log.w(TAG, "requestMediaSnapshot: VoiceSatelliteService not ready")
        }
    }

    /**
     * Collapse to the circular control when we already have media metadata.
     * Without displayable content the FAB stays hidden (cold start / no upstream).
     */
    private fun persistAsMini() {
        if (!enableEqMiniPlayer) return
        // Intentional collapse (chrome back / idle soft-hide) — remember FAB.
        userPrefersExpanded = false
        if (!hasDisplayableContent()) {
            handler.post {
                val host = overlayRoot
                val view = composeView ?: return@post
                cancelMorphAnimation(view)
                view.animate().cancel()
                resetMorphTransform(view)
                host?.visibility = View.GONE
                view.visibility = View.GONE
                view.alpha = 1f
                overlayExpanded = true
                applyWindowMode(collapsed = false)
                applyKeepScreenOn(false)
                host?.visibility = View.GONE
                syncMediaChrome()
                OverlayLayerSplit.sync()
            }
            return
        }
        handler.post {
            val host = overlayRoot ?: return@post
            val view = composeView ?: return@post

            // Already idle FAB shell — nothing to morph. Still sync keep-screen-on /
            // screensaver gate (stale blocksScreensaver must not soft-pause forever).
            if (!overlayExpanded && !windowIsFullScreen && !isMorphAnimating) {
                applyWindowMode(collapsed = true)
                resetMorphTransform(view)
                applyKeepScreenOn(false)
                if (host.visibility != View.VISIBLE || view.visibility != View.VISIBLE) {
                    view.alpha = 1f
                    shellCrossfadeAlpha = 0f
                    host.visibility = View.VISIBLE
                    view.visibility = View.VISIBLE
                    view.animate().cancel()
                    cancelShellCrossfadeAnimator()
                    animateShellCrossfade(
                        to = 1f,
                        durationMs = 220,
                        interpolator = DecelerateInterpolator(),
                    ) {}
                    view.requestLayout()
                    bringToFront()
                } else {
                    view.requestLayout()
                }
                syncMediaChrome()
                return@post
            }

            if (isMorphAnimating) return@post
            handler.removeCallbacks(finishExpandRunnable)
            handler.removeCallbacks(finishCollapseRunnable)
            view.animate().cancel()
            cancelShellCrossfadeAnimator()
            resetMorphTransform(view)
            isMorphAnimating = true

            // A split pane stays its current size. Forcing MATCH_PARENT here
            // flashes the full screen, then the button pops out of it.
            if (!OverlayLayerSplit.isPaneView(host)) {
                applyWindowMode(collapsed = false)
            }
            host.visibility = View.VISIBLE
            view.visibility = View.VISIBLE
            view.alpha = 1f
            shellCrossfadeAlpha = 1f
            lockMorphGeometry()
            syncMediaChrome()
            handler.postDelayed(finishCollapseRunnable, CROSSFADE_SAFETY_MS)

            animateShellCrossfade(
                to = 0f,
                durationMs = COLLAPSE_FADE_MS,
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f),
            ) {
                if (!isMorphAnimating) return@animateShellCrossfade
                applyWindowMode(collapsed = true)
                overlayExpanded = false
                shellCrossfadeAlpha = 0f
                applyKeepScreenOn(false)
                syncMediaChrome()
                OverlayLayerSplit.releasePane(OverlayLayerSplit.Layer.MEDIA_PLAYER, host)
                OverlayLayerSplit.sync()
                raiseCollapsedFabAboveBrowser()
                startBirthCrossfadeAfterFirstFrame(
                    durationMs = COLLAPSE_FADE_MS,
                    frameHops = 1,
                    interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f),
                ) {
                    handler.removeCallbacks(finishCollapseRunnable)
                    isMorphAnimating = false
                    shellCrossfadeAlpha = 1f
                    syncMediaChrome()
                    OverlayLayerSplit.sync()
                }
            }
        }
    }

    private fun cancelShellCrossfadeAnimator() {
        cancelPendingBirthCrossfade()
        shellCrossfadeAnimator?.cancel()
        shellCrossfadeAnimator = null
    }

    private fun cancelPendingBirthCrossfade() {
        birthCrossfadeFallback?.let { handler.removeCallbacks(it) }
        birthCrossfadeFallback = null
        birthCrossfadeDetach?.invoke()
        birthCrossfadeDetach = null
    }

    /**
     * Start the birth fade a few vsyncs late, never in the frame that flips the shell to
     * VISIBLE.
     *
     * That frame is the heaviest one the overlay ever runs: [applyWindowMode] has just
     * pushed a WM relayout (size, gravity and FULLSCREEN/LAYOUT_NO_LIMITS flags all
     * change), [bringToFront] re-adds the window, and Compose then does its first
     * measure, layout and draw for the whole detailed player — including the full-screen
     * 48dp backdrop blur. A time-based ValueAnimator started alongside that does not run
     * slowly, it *skips*: the first value it manages to deliver is already mid-fade,
     * which is the "yanked into place" feel.
     *
     * Frame callbacks run in the animation phase, ahead of traversal in the same frame,
     * so one hop would still land on the expensive traversal. Waiting
     * [BIRTH_CROSSFADE_FRAME_HOPS] hops puts the animator's clock past it, and because
     * each hop waits for a real vsync the wait absorbs jank instead of skipping it.
     * Choreographer is used rather than a pre-draw listener because the z-order restacks
     * around birth detach and re-attach the window, which would strand a listener
     * registered on the old ViewTreeObserver. The shell sits at alpha 0 meanwhile, so
     * nothing flashes.
     */
    private fun startBirthCrossfadeAfterFirstFrame(
        durationMs: Int,
        frameHops: Int = BIRTH_CROSSFADE_FRAME_HOPS,
        interpolator: Interpolator = DecelerateInterpolator(),
        onEnd: () -> Unit,
    ) {
        cancelPendingBirthCrossfade()
        var launched = false
        val begin = {
            if (!launched) {
                launched = true
                cancelPendingBirthCrossfade()
                animateShellCrossfade(
                    to = 1f,
                    durationMs = durationMs,
                    interpolator = interpolator,
                    onEnd = onEnd,
                )
            }
        }
        val choreographer = Choreographer.getInstance()
        var callback: Choreographer.FrameCallback? = null
        var hopsLeft = frameHops.coerceAtLeast(1)
        callback = Choreographer.FrameCallback { _ ->
            if (--hopsLeft > 0) {
                callback?.let { choreographer.postFrameCallback(it) }
            } else {
                begin()
            }
        }
        birthCrossfadeDetach = { callback?.let { choreographer.removeFrameCallback(it) } }
        choreographer.postFrameCallback(callback)
        // A shell parked at alpha 0 is invisible, and vsync stalls while the display is
        // off — never depend solely on frame callbacks arriving.
        val fallback = Runnable { begin() }
        birthCrossfadeFallback = fallback
        handler.postDelayed(fallback, BIRTH_CROSSFADE_FALLBACK_MS)
    }

    /**
     * Animate [shellCrossfadeAlpha] on the Compose graphicsLayer — the reliable
     * fade path for WindowManager + ComposeView overlays.
     */
    private fun animateShellCrossfade(
        to: Float,
        durationMs: Int,
        interpolator: Interpolator,
        onEnd: () -> Unit,
    ) {
        cancelShellCrossfadeAnimator()
        val from = shellCrossfadeAlpha
        if (kotlin.math.abs(from - to) < 0.01f) {
            shellCrossfadeAlpha = to
            onEnd()
            return
        }
        val anim = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs.toLong()
            this.interpolator = interpolator
            addUpdateListener { valueAnim ->
                shellCrossfadeAlpha = valueAnim.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                private var finished = false
                override fun onAnimationEnd(animation: Animator) {
                    if (finished) return
                    finished = true
                    shellCrossfadeAlpha = to
                    if (shellCrossfadeAnimator === animation) {
                        shellCrossfadeAnimator = null
                    }
                    onEnd()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finished = true
                    if (shellCrossfadeAnimator === animation) {
                        shellCrossfadeAnimator = null
                    }
                    // Snap to the intended end — do not run onEnd (morph phases must
                    // not advance). Leaving alpha mid-fade on a MATCH_PARENT shell
                    // made cold-start empty expand feel like an invisible freeze.
                    shellCrossfadeAlpha = to
                }
            })
        }
        shellCrossfadeAnimator = anim
        anim.start()
    }

    private fun resetMorphTransform(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        val w = view.width
        val h = view.height
        if (w > 0 && h > 0) {
            view.pivotX = w / 2f
            view.pivotY = h / 2f
        }
    }

    private fun cancelMorphAnimation(view: View) {
        handler.removeCallbacks(finishExpandRunnable)
        handler.removeCallbacks(finishCollapseRunnable)
        view.animate().cancel()
        cancelShellCrossfadeAnimator()
        resetMorphTransform(view)
        shellCrossfadeAlpha = 1f
        isMorphAnimating = false
        holdSplitDuringExpand = false
        // Snap WM shell to the logical state so touch pass-through stays correct.
        if (enableEqMiniPlayer) {
            applyWindowMode(collapsed = !overlayExpanded)
            // Interrupted expand must not leave soft-pause stuck while FAB is parked.
            applyKeepScreenOn(overlayExpanded)
            // [expandFromMini] sets preference true at morph start; if we snap
            // back to FAB before [overlayExpanded] flips, clear it so a later
            // protocol fresh-show cannot birth full-screen without another tap.
            if (!overlayExpanded) {
                userPrefersExpanded = false
            }
        }
    }

    /**
     * Lend the overlay window focus so the rail's search box can raise the soft keyboard.
     *
     * The window is born FLAG_NOT_FOCUSABLE precisely so it never takes focus from the
     * launcher or the voice layers, and the IME cannot attach to a window that cannot
     * focus — so typing requires dropping that flag for as long as the field is live.
     * FLAG_ALT_FOCUSABLE_IM is never set here, so clearing NOT_FOCUSABLE is enough: with
     * both absent the window is focusable *and* wants the IME.
     *
     * Like the browser overlay, focus is borrowed only while a field needs the IME and
     * then given back. The media overlay far outlives its search box and shares z-order
     * with the voice orb and screensaver, so a leaked flag would keep swallowing Back
     * and stealing focus long after the field was gone. [releaseOverlayImeFocus] is
     * therefore wired to field blur, page exit, collapse-to-FAB and shell hide.
     */
    private fun applyOverlayImeFocus(wanted: Boolean) {
        val host = overlayRoot ?: return
        val params = windowParams ?: return
        val wm = windowManager ?: return
        if (host.parent == null) return
        // Focus is only meaningful on the expanded shell; the FAB has no text field.
        if (wanted && (host.visibility != View.VISIBLE || !overlayExpanded)) return
        if (!wanted && !overlayImeFocusHeld) return
        params.flags = if (wanted) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        // ALWAYS_VISIBLE rather than STATE_VISIBLE: the latter only fires the first time a
        // window is shown, and this window is long-lived and re-focused repeatedly. This makes
        // raising the keyboard the window manager's job on every focus gain instead of relying
        // on the Compose-side show() request alone.
        // A second tap must still get here even when the flag is already held — the IME can
        // dismiss while Compose keeps editor focus, and an early return would leave it gone.
        @Suppress("DEPRECATION")
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            if (wanted) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            }
        try {
            wm.updateViewLayout(host, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set overlay IME focus wanted=$wanted", e)
            // Keep the tracked state honest: a failed grant must stay releasable.
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            overlayImeFocusHeld = false
            return
        }
        overlayImeFocusHeld = wanted
        if (!wanted) {
            overlayImeReleasedAtElapsed = SystemClock.elapsedRealtime()
        }
        if (wanted) {
            // Let the Compose editor take focus; do not requestFocus on the host —
            // that steals from the TextField and the second tap would immediately blur.
            composeView?.isFocusableInTouchMode = true
        } else {
            // Drop editor focus too, otherwise Compose keeps asking for an IME that the
            // window can no longer host.
            composeView?.clearFocus()
        }
    }

    private fun releaseOverlayImeFocus() {
        if (!overlayImeFocusHeld) return
        applyOverlayImeFocus(false)
    }

    internal fun releaseOverlayImeFocusInternal() {
        if (!overlayImeFocusHeld) return
        releaseOverlayImeFocus()
    }

    /**
     * Arm the revert for an optimistic repeat paint. Call right after painting the
     * new mode and sending the command; [confirmRepeatFromUpstream] cancels it as
     * soon as any upstream reports a repeat mode.
     */
    internal fun armRepeatRevertInternal(previousMode: String) {
        repeatRevertJob?.cancel()
        pendingRepeatRevert = previousMode
        // The overlay can hand off between Sendspin and HA inside the window; a
        // revert then belongs to a session that no longer owns the glyph.
        val armedForSendspin = isSendspinSource
        repeatRevertJob = coroutineScope.launch {
            delay(TRANSPORT_CONFIRM_WINDOW_MS)
            pendingRepeatRevert?.let {
                if (armedForSendspin == isSendspinSource) {
                    Log.w(TAG, "repeat not confirmed upstream in ${TRANSPORT_CONFIRM_WINDOW_MS}ms; reverting to $it")
                    repeatMode = it
                }
            }
            pendingRepeatRevert = null
            repeatRevertJob = null
        }
    }

    /** @see armRepeatRevertInternal */
    internal fun armShuffleRevertInternal(previousEnabled: Boolean) {
        shuffleRevertJob?.cancel()
        pendingShuffleRevert = previousEnabled
        val armedForSendspin = isSendspinSource
        shuffleRevertJob = coroutineScope.launch {
            delay(TRANSPORT_CONFIRM_WINDOW_MS)
            pendingShuffleRevert?.let {
                if (armedForSendspin == isSendspinSource) {
                    Log.w(TAG, "shuffle not confirmed upstream in ${TRANSPORT_CONFIRM_WINDOW_MS}ms; reverting to $it")
                    shuffleEnabled = it
                }
            }
            pendingShuffleRevert = null
            shuffleRevertJob = null
        }
    }

    private fun confirmRepeatFromUpstream() {
        repeatRevertJob?.cancel()
        repeatRevertJob = null
        pendingRepeatRevert = null
    }

    private fun confirmShuffleFromUpstream() {
        shuffleRevertJob?.cancel()
        shuffleRevertJob = null
        pendingShuffleRevert = null
    }

    /**
     * Collapsed mini = FAB-sized WM shell at the remembered (or default bottom-end) spot,
     * matching [VolumeControlService] WRAP_CONTENT / [DashboardOverlayChrome] strip.
     * Expanded = MATCH_PARENT for the full player + morph.
     */
    private fun applyWindowMode(collapsed: Boolean) {
        val host = overlayRoot ?: return
        val params = windowParams ?: return
        val wm = windowManager ?: return

        // Collapsing to the FAB takes the search box off screen with it.
        if (collapsed) releaseOverlayImeFocus()

        windowIsFullScreen = !collapsed
        // Layout mode must flip atomically with the WM shell size — otherwise the FAB
        // jumps between offset (full) and fillMaxSize (shell) for a visible frame.
        miniShellLayout = collapsed
        refreshMiniFabDrawPosition()
        params.windowAnimations = R.style.AppWindowNoAnimation

        if (collapsed) {
            val shell = miniFabShellSizePx()
            params.width = shell
            params.height = shell
            params.gravity = Gravity.TOP or Gravity.START
            params.x = miniFabDrawX
            params.y = miniFabDrawY
            params.flags = params.flags and
                WindowManager.LayoutParams.FLAG_FULLSCREEN.inv() and
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                // TYPE_PHONE on Android 7 keeps a fullscreen frame when these stay
                // set, so updateViewLayout never becomes a pass-through FAB.
                params.flags = params.flags and
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS.inv() and
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv() and
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION.inv()
            }
            // Keep expand TransformOrigin warm while parked on the FAB shell
            // (same center collapse uses — avoids first-expand jump to 0,0).
            val (sw, sh) = realScreenSize()
            morphPivotX = miniFabDrawX
            morphPivotY = miniFabDrawY
            morphHostW = sw.coerceAtLeast(1)
            morphHostH = sh.coerceAtLeast(1)
        } else {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        }

        if (collapsed &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 &&
            host.isAttachedToWindow
        ) {
            // Shrinking TYPE_PHONE is ignored until the window is re-added.
            // Do this only for the FAB shell — re-adding the fullscreen player
            // detaches Compose and the collapse fade never finishes.
            runCatching { wm.removeView(host) }
        }
        if (!ensureOverlayAttached()) {
            if (host.visibility == View.VISIBLE) {
                Log.w(TAG, "Failed to attach window mode collapsed=$collapsed")
            }
            return
        }
        runCatching { wm.updateViewLayout(host, params) }
            .onFailure { Log.w(TAG, "Failed to update window mode collapsed=$collapsed", it) }
        if (!collapsed && isExpandedSplitShowing()) {
            OverlayLayerSplit.sync()
        }
    }

    /** Expanded full player only. The collapsed button never takes a pane. */
    private fun isExpandedSplitShowing(): Boolean {
        val host = overlayRoot ?: return false
        return host.visibility == View.VISIBLE &&
            overlayExpanded &&
            windowIsFullScreen &&
            !miniShellLayout &&
            (!isMorphAnimating || holdSplitDuringExpand)
    }

    /**
     * Left/right or top/bottom split: put the expanded player on its pane
     * before the fade-in. A MATCH_PARENT window reads as already on screen, so
     * alpha is dropped for this sync and the arrival matches the other panes.
     */
    private fun placeExpandedIntoSplitBeforeReveal(view: View) {
        OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.MEDIA_PLAYER)
        if (!SettingsStyleSession.overlaySplitEnabled.value) return
        holdSplitDuringExpand = true
        view.alpha = 0f
        OverlayLayerSplit.sync()
        if (OverlayLayerSplit.isPaneView(overlayRoot)) {
            view.alpha = 1f
            return
        }
        view.post {
            if (!holdSplitDuringExpand) {
                view.alpha = 1f
                return@post
            }
            OverlayLayerSplit.sync()
            view.alpha = 1f
        }
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = overlayRoot
        if (!isExpandedSplitShowing()) {
            if (frame == null) OverlayLayerSplit.releasePane(OverlayLayerSplit.Layer.MEDIA_PLAYER, host)
            return
        }
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.MEDIA_PLAYER,
            windowManager,
            host,
            windowParams,
            frame,
        )
    }

    private fun realScreenSize(): Pair<Int, Int> {
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

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Movable range for FAB top-left in the current orientation. */
    private fun usableFabRange(shell: Int = miniFabShellSizePx()): Pair<Int, Int> {
        val (sw, sh) = realScreenSize()
        return (sw - shell).coerceAtLeast(0) to (sh - shell).coerceAtLeast(0)
    }

    private fun defaultMiniFabMarginPx(): Int {
        val density = resources.displayMetrics.density
        return (20f * screenScaleFactor() * density).roundToInt()
    }

    private fun defaultMiniFabPosition(shell: Int): Pair<Int, Int> {
        val (screenW, screenH) = realScreenSize()
        val margin = defaultMiniFabMarginPx()
        val x = (screenW - shell - margin).coerceAtLeast(0)
        val y = (screenH - shell - margin).coerceAtLeast(0)
        return x to y
    }

    private fun clampMiniFabPosition(x: Int, y: Int, shell: Int = miniFabShellSizePx()): Pair<Int, Int> {
        val (maxX, maxY) = usableFabRange(shell)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * Load norms (preferred) or migrate legacy absolute px into norms for this orientation.
     */
    private fun applySavedFabPosition(
        normX: Float,
        normY: Float,
        legacyX: Int,
        legacyY: Int,
    ) {
        when {
            normX >= 0f && normY >= 0f -> {
                miniFabNormX = normX.coerceIn(0f, 1f)
                miniFabNormY = normY.coerceIn(0f, 1f)
            }
            legacyX >= 0 && legacyY >= 0 -> {
                // One-shot migrate: treat legacy px as belonging to *current* screen.
                val shell = miniFabShellSizePx()
                val (maxX, maxY) = usableFabRange(shell)
                val (cx, cy) = clampMiniFabPosition(legacyX, legacyY, shell)
                miniFabNormX = if (maxX > 0) cx.toFloat() / maxX else 0f
                miniFabNormY = if (maxY > 0) cy.toFloat() / maxY else 0f
                coroutineScope.launch {
                    playerSettingsStore.update {
                        it.copy(
                            eqMiniFabNormX = miniFabNormX,
                            eqMiniFabNormY = miniFabNormY,
                            eqMiniFabX = -1,
                            eqMiniFabY = -1,
                        )
                    }
                }
            }
            else -> {
                miniFabNormX = -1f
                miniFabNormY = -1f
            }
        }
        refreshMiniFabDrawPosition()
    }

    private fun refreshMiniFabDrawPosition() {
        val shell = miniFabShellSizePx()
        val (maxX, maxY) = usableFabRange(shell)
        val (x, y) = if (miniFabNormX >= 0f && miniFabNormY >= 0f) {
            val px = (miniFabNormX * maxX).roundToInt()
            val py = (miniFabNormY * maxY).roundToInt()
            clampMiniFabPosition(px, py, shell)
        } else {
            defaultMiniFabPosition(shell)
        }
        miniFabDrawX = x
        miniFabDrawY = y
    }

    /**
     * Snapshot FAB px + overlay host size for TransformOrigin. Call at the start of
     * expand/collapse so this animation never mixes last orientation's geometry.
     */
    private fun lockMorphGeometry() {
        // During expand, prefer the pre-captured FAB draw (from shell params) —
        // refresh from norms only when not mid-expand morph.
        if (!isMorphAnimating || overlayExpanded) {
            refreshMiniFabDrawPosition()
        }
        val host = overlayRoot
        val (sw, sh) = realScreenSize()
        val shell = miniFabShellSizePx()
        val hw = host?.width ?: 0
        val hh = host?.height ?: 0
        // Host may still be FAB-sized for a frame after MATCH_PARENT — don't
        // let that crush TransformOrigin toward a corner.
        morphHostW = (if (hw > shell * 2) hw else sw).coerceAtLeast(1)
        morphHostH = (if (hh > shell * 2) hh else sh).coerceAtLeast(1)
        morphPivotX = miniFabDrawX
        morphPivotY = miniFabDrawY
    }

    private fun onOverlayConfigurationChanged(newConfig: Configuration) {
        val orientation = newConfig.orientation
        if (orientation == lastOrientation) {
            // Still refresh size (foldables / multi-window) without treating as rotate.
            handler.post {
                if (!enableEqMiniPlayer || isDraggingMiniFab || isMorphAnimating) return@post
                refreshMiniFabDrawPosition()
                if (!overlayExpanded && miniShellLayout) {
                    applyWindowMode(collapsed = true)
                }
            }
            return
        }
        lastOrientation = orientation
        handler.post {
            if (!enableEqMiniPlayer) return@post
            // Remap norms → px for the new orientation (portrait↔landscape).
            refreshMiniFabDrawPosition()
            lockMorphGeometry()
            when {
                isMorphAnimating -> Unit // let in-flight anim finish; pivot already locked
                !overlayExpanded -> applyWindowMode(collapsed = true)
                else -> {
                    // Expanded full player: keep MATCH_PARENT, FAB offset updates via state.
                    applyWindowMode(collapsed = false)
                }
            }
            Log.d(TAG, "orientation→${if (isLandscape()) "landscape" else "portrait"} " +
                "fab=(${miniFabDrawX},${miniFabDrawY}) norm=($miniFabNormX,$miniFabNormY)")
        }
    }

    private fun moveMiniFabTo(x: Int, y: Int) {
        val host = overlayRoot ?: return
        val params = windowParams ?: return
        val wm = windowManager ?: return
        val shell = miniFabShellSizePx()
        val (cx, cy) = clampMiniFabPosition(x, y, shell)
        val (maxX, maxY) = usableFabRange(shell)
        miniFabNormX = if (maxX > 0) cx.toFloat() / maxX else 0f
        miniFabNormY = if (maxY > 0) cy.toFloat() / maxY else 0f
        miniFabDrawX = cx
        miniFabDrawY = cy
        morphPivotX = cx
        morphPivotY = cy
        if (!windowIsFullScreen) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = cx
            params.y = cy
            params.width = shell
            params.height = shell
            if (ensureOverlayAttached()) {
                runCatching { wm.updateViewLayout(host, params) }
            }
        }
    }

    private fun persistMiniFabPosition() {
        if (miniFabNormX < 0f || miniFabNormY < 0f) return
        val nx = miniFabNormX
        val ny = miniFabNormY
        coroutineScope.launch {
            playerSettingsStore.update {
                it.copy(
                    eqMiniFabNormX = nx,
                    eqMiniFabNormY = ny,
                    eqMiniFabX = -1,
                    eqMiniFabY = -1,
                )
            }
        }
    }

    /** Must match [CircleEqMiniPlayerFab] outer box: 86dp circle (no shadow pad). */
    private fun miniFabShellSizePx(): Int {
        val density = resources.displayMetrics.density
        val scale = screenScaleFactor()
        val fabDp = 86f * scale
        return (fabDp * density).roundToInt().coerceAtLeast(1)
    }

    /** Same baseline as GlassMusicPlayerView: min side / 360, clamped 1..2. */
    private fun screenScaleFactor(): Float {
        val cfg = resources.configuration
        val minDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp).coerceAtLeast(1)
        return (minDp / 360f).coerceIn(1f, 2f)
    }

    /**
     * Mini FAB can leave WM after a restack/move while the service still wants
     * it. Re-add when the shell should still be on screen.
     */
    private fun ensureOverlayAttached(): Boolean {
        val host = overlayRoot ?: return false
        val params = windowParams ?: return false
        val wm = windowManager ?: return false
        if (runCatching { wm.updateViewLayout(host, params) }.isSuccess) return true
        if (host.visibility != View.VISIBLE && !isDraggingMiniFab) return false
        if (host.isAttachedToWindow || host.parent != null) {
            runCatching { wm.removeView(host) }
        }
        return try {
            wm.addView(host, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            Log.w(TAG, "Vinyl FAB re-attached")
            true
        } catch (e: IllegalStateException) {
            e.message?.contains("already been added") == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-add vinyl FAB", e)
            false
        }
    }

    private fun healMiniIfMissing() {
        val host = overlayRoot ?: return
        if (host.visibility != View.VISIBLE) return
        if (isMorphAnimating) return
        if (!ensureOverlayAttached()) return
        windowParams?.let { params ->
            runCatching { windowManager?.updateViewLayout(host, params) }
        }
    }

    /**
     * Collapsed button only. The expanded player can sit under the browser pane;
     * after it shrinks, lift that same window above the browser without the
     * full chrome cascade.
     */
    private fun raiseCollapsedFabAboveBrowser() {
        val host = overlayRoot ?: return
        if (host.visibility != View.VISIBLE) return
        if (overlayExpanded || isDraggingMiniFab) return
        if (OverlayLayerSplit.isPaneView(host)) return
        OverlayZOrderCoordinator.bringToFront(
            windowManager,
            host,
            windowParams,
            TAG,
            mask = false,
            raiseMic = false,
        )
    }

    private fun bringToFront() {
        val host = overlayRoot ?: return
        if (host.visibility != View.VISIBLE) return
        if (isDraggingMiniFab) return
        // Z-order reassert is remove + re-add, which destroys the window along with its IME
        // connection and editor focus. Firing that mid-typing yanks the keyboard away — and
        // it would fire often, since chrome syncs on every track and playback change. Holding
        // focus already means we are the window the user is working in, so skip; the next
        // chrome sync after release reasserts.
        if (overlayImeFocusHeld) return
        if (SystemClock.elapsedRealtime() - overlayImeReleasedAtElapsed < 500L) return
        if (overlayExpanded && !isMorphAnimating) {
            OverlayLayerSplit.sync()
        }
        if (!OverlayLayerSplit.isPaneView(host)) {
            OverlayZOrderCoordinator.bringToFront(windowManager, host, windowParams, TAG)
        }
        if (overlayExpanded) {
            DashboardOverlayChrome.bringToFront()
        }
    }

    /**
     * Wipe titles / cover / playhead for an empty queue. Does **not** touch
     * remembered FAB position ([miniFabNormX]/[miniFabNormY]) or mini arm —
     * unexpected clears must not break where the user parked the control.
     */
    private fun clearQueueDisplayState() {
        coverLoadJob?.cancel()
        coverLoadJob = null
        songTitle = ""
        artistName = ""
        albumName = ""
        coverUrl = null
        coverBitmap = null
        resetPlayheadZeroGlitchGuard()
        applyCurrentTimeMs(0L, force = true)
        applyTotalTimeMs(0L, force = true)
        isPlaying = false
        lyricAudibleLagMs = 0L
        MediaOverlayMemoryCache.clear()
        // Intentionally leave miniFabNorm* / userPrefersExpanded alone.
    }

    /**
     * MA clear-queue (button / upstream empty / unsync peer wipe): keep the shell
     * up, wipe identity, paint localized [R.string.media_overlay_waiting_for_media].
     * Does **not** start empty-queue FAB grace / force-hide.
     * Memory write is a hard [MediaOverlayMemoryCache.replaceAll] so sticky cover
     * / artist from [putFull] cannot survive the wipe.
     */
    private fun resetToWaitingForMediaInternal() {
        endQueueClearedGraceInternal()
        cancelPauseIdleTeardownInternal()
        clearQueueDisplayState()
        ensureExpandableTitle()
        // Nothing upstream will dismiss this wipe — arm the 2-min shell teardown.
        rearmWaitingShellTeardown()
        MediaOverlayMemoryCache.replaceAll(
            MediaOverlayMemoryCache.Snapshot(
                coverUrl = null,
                songTitle = songTitle,
                artistName = "",
                albumName = "",
                isPlaying = false,
                currentTimeMs = 0L,
                totalTimeMs = 0L,
                isSendspinSource = isSendspinSource,
                lyricAudibleLagMs = 0L,
                coverBitmap = null,
            ),
        )
    }

    /**
     * Empty-queue grace timers. Soft FAB is already showing when [begin] runs.
     */
    private fun beginQueueClearedGraceInternal() {
        // Empty-queue timeline owns teardown — do not race pause-idle.
        cancelPauseIdleTeardownInternal()
        queueClearedGraceActive = true
        handler.removeCallbacks(queueClearedCollapseToFabRunnable)
        scheduleQueueClearedFabForceHide()
    }

    private fun endQueueClearedGraceInternal() {
        queueClearedGraceActive = false
        handler.removeCallbacks(queueClearedCollapseToFabRunnable)
        handler.removeCallbacks(queueClearedForceHideRunnable)
    }

    /**
     * Arm shared pause-idle tuck. [restart]=false is idempotent (repeated
     * paused pushes must not reset the yield clock). Requires a visible overlay
     * with content and not playing.
     */
    private fun schedulePauseIdleTeardownInternal(restart: Boolean) {
        if (isPlaying) return
        if (queueClearedGraceActive) return
        // Soft pipeline restart: keep the painted shell until the new manager rebinds.
        if (awaitingPipelineRebind) return
        val visible =
            overlayRoot?.visibility == View.VISIBLE &&
                composeView?.visibility == View.VISIBLE
        if (!visible || !hasDisplayableContent()) return
        if (!restart && pauseIdleArmed) return
        handler.removeCallbacks(pauseIdleSoftRunnable)
        handler.removeCallbacks(pauseIdleForceRunnable)
        pauseIdleArmed = true
        pauseIdleForcePhase = false
        handler.postDelayed(pauseIdleSoftRunnable, PAUSE_IDLE_YIELD_MS)
        Log.d(TAG, "pause-idle armed restart=$restart yieldMs=$PAUSE_IDLE_YIELD_MS")
    }

    private fun cancelPauseIdleTeardownInternal() {
        pauseIdleArmed = false
        pauseIdleForcePhase = false
        handler.removeCallbacks(pauseIdleSoftRunnable)
        handler.removeCallbacks(pauseIdleForceRunnable)
    }

    /**
     * Keep pause-idle coherent after a play/pause paint.
     * - Playing: cancel tuck clock; [show] **only if currently hidden** (pause-idle
     *   GONE). Already-visible must not re-enter [show] — that cleared the PCM
     *   reshow latch and flashed expand/chrome.
     * - Paused: re-arm if visible (idempotent) so a play-blip cancel cannot strand FAB.
     * Does not touch empty-queue grace. Audible re-show stays on
     * [awaitingPauseIdleAudibleReshow] + Sendspin [noteTransportAudible].
     */
    private fun reconcilePauseIdleAfterPlaybackStateChange() {
        if (queueClearedGraceActive) {
            cancelPauseIdleTeardownInternal()
            return
        }
        if (isPlaying) {
            cancelPauseIdleTeardownInternal()
            val visible =
                overlayRoot?.visibility == View.VISIBLE &&
                    composeView?.visibility == View.VISIBLE
            // Only recover from temporary tuck — never repaint an on-screen FAB.
            if (!visible && hasDisplayableContent()) {
                show()
            }
            return
        }
        schedulePauseIdleTeardownInternal(restart = false)
    }

    private fun onPauseIdleYield() {
        if (!pauseIdleArmed) return
        if (isPlaying || queueClearedGraceActive) {
            cancelPauseIdleTeardownInternal()
            return
        }
        val visible =
            overlayRoot?.visibility == View.VISIBLE &&
                composeView?.visibility == View.VISIBLE
        if (!visible || !hasDisplayableContent()) {
            cancelPauseIdleTeardownInternal()
            return
        }
        // Finger still on the overlay — never collapse mid-interaction; the
        // release (UP/CANCEL) restarts this clock via [noteOverlayUserActivity].
        if (isUserTouchingOverlay) {
            handler.postDelayed(pauseIdleSoftRunnable, PAUSE_IDLE_YIELD_MS)
            return
        }
        // Already FAB: temporary tuck at the first yield mark (no second wait).
        if (enableEqMiniPlayer && !overlayExpanded) {
            Log.d(TAG, "pause-idle: already FAB → temporary hide")
            pauseIdleArmed = false
            handler.removeCallbacks(pauseIdleForceRunnable)
            hideTemporarilyPreservingIdentity(armAudibleReshow = true)
            return
        }
        if (enableEqMiniPlayer && overlayExpanded) {
            Log.d(TAG, "pause-idle: soft → FAB, arm tuck in ${PAUSE_IDLE_FORCE_MS}ms")
            persistAsMini()
            handler.removeCallbacks(pauseIdleForceRunnable)
            pauseIdleForcePhase = true
            handler.postDelayed(pauseIdleForceRunnable, PAUSE_IDLE_FORCE_MS)
            return
        }
        // Legacy (no mini): temporary hide at yield — keep identity for next play.
        Log.d(TAG, "pause-idle: no mini → temporary hide")
        pauseIdleArmed = false
        handler.removeCallbacks(pauseIdleForceRunnable)
        hideTemporarilyPreservingIdentity(armAudibleReshow = true)
    }

    private fun onPauseIdleForce() {
        if (!pauseIdleArmed) return
        if (isPlaying || queueClearedGraceActive) {
            cancelPauseIdleTeardownInternal()
            return
        }
        // Finger down (e.g. dragging the FAB) — defer; release restarts the clock.
        if (isUserTouchingOverlay) {
            handler.postDelayed(pauseIdleForceRunnable, PAUSE_IDLE_FORCE_MS)
            return
        }
        Log.d(TAG, "pause-idle: temporary hide after FAB dwell")
        pauseIdleArmed = false
        handler.removeCallbacks(pauseIdleSoftRunnable)
        hideTemporarilyPreservingIdentity(armAudibleReshow = true)
    }

    /**
     * Pause-idle only: fade the overlay away without wiping metadata, cover,
     * memory cache, FAB prefs, or progress ownership — so the next [show] can
     * restore immediately. Empty-queue / settings-off still use [hide](force).
     *
     * @param armAudibleReshow when true (pause-idle tuck), mark a prepared latch so
     * the next real PCM can re-show without waiting for an upstream replay command.
     */
    private fun hideTemporarilyPreservingIdentity(armAudibleReshow: Boolean = false) {
        cancelPauseIdleTeardownInternal()
        // Same stuck-flag guard as [hide] — surface goes GONE without a terminal
        // touch event in some HA-driven paths.
        isUserTouchingOverlay = false
        handler.post {
            isMorphAnimating = false
            cancelShellCrossfadeAnimator()
            composeView?.animate()?.cancel()
            composeView?.let { resetMorphTransform(it) }
            val host = overlayRoot
            val view = composeView ?: return@post
            view.animate().cancel()
            val tuckAway = {
                // Play won the race against this fade: do not finish GONE — PCM
                // audible notify may not fire again, which would strand the FAB.
                if (isPlaying && hasDisplayableContent()) {
                    awaitingPauseIdleAudibleReshow = false
                    host?.visibility = View.VISIBLE
                    view.visibility = View.VISIBLE
                    view.alpha = 1f
                    shellCrossfadeAlpha = 1f
                    applyKeepScreenOn(overlayExpanded)
                } else {
                    host?.visibility = View.GONE
                    view.visibility = View.GONE
                    view.alpha = 1f
                    shellCrossfadeAlpha = 1f
                    applyKeepScreenOn(false)
                    // Keep songTitle / cover / userPrefersExpanded / progressOwner.
                    // Always arm PCM re-show latch for pause-idle tuck — do not
                    // gate on isSendspinContentActive (can be stale/false after
                    // ownership races; next track metadata was the only recovery).
                    if (armAudibleReshow) {
                        awaitingPauseIdleAudibleReshow = true
                    }
                }
                syncMediaChrome()
            }
            view.alpha = 1f
            animateShellCrossfade(
                to = 0f,
                durationMs = 220,
                interpolator = AccelerateInterpolator(),
                onEnd = tuckAway,
            )
        }
    }

    private fun scheduleQueueClearedFabForceHide() {
        handler.removeCallbacks(queueClearedForceHideRunnable)
        if (!queueClearedGraceActive) return
        handler.postDelayed(queueClearedForceHideRunnable, QUEUE_CLEAR_FAB_HIDE_MS)
    }

    /** User opened detailed metadata during empty-queue grace → idle to FAB. */
    private fun onQueueClearedExpanded() {
        if (!queueClearedGraceActive) return
        handler.removeCallbacks(queueClearedForceHideRunnable)
        handler.removeCallbacks(queueClearedCollapseToFabRunnable)
        handler.postDelayed(queueClearedCollapseToFabRunnable, QUEUE_CLEAR_EXPANDED_IDLE_MS)
    }

    /** Any screen interaction while expanded during grace restarts the idle→FAB timer. */
    internal fun noteQueueClearedUserActivity() {
        if (!queueClearedGraceActive || !overlayExpanded) return
        handler.removeCallbacks(queueClearedCollapseToFabRunnable)
        handler.postDelayed(queueClearedCollapseToFabRunnable, QUEUE_CLEAR_EXPANDED_IDLE_MS)
    }

    /**
     * Touch began or ended on the overlay window (any surface — player controls,
     * lyrics, Mass rail, FAB). Restart whichever idle clocks are armed so they
     * count from the latest interaction; firing mid-gesture is additionally
     * blocked by the [isUserTouchingOverlay] guards inside the runnables.
     */
    private fun noteOverlayUserActivity() {
        if (pauseIdleArmed) {
            if (pauseIdleForcePhase) {
                handler.removeCallbacks(pauseIdleForceRunnable)
                handler.postDelayed(pauseIdleForceRunnable, PAUSE_IDLE_FORCE_MS)
            } else {
                handler.removeCallbacks(pauseIdleSoftRunnable)
                handler.postDelayed(pauseIdleSoftRunnable, PAUSE_IDLE_YIELD_MS)
            }
        }
        if (queueClearedGraceActive) {
            if (overlayExpanded) {
                noteQueueClearedUserActivity()
            } else {
                scheduleQueueClearedFabForceHide()
            }
        }
    }

    /**
     * @param force when false and persistent mini is on, collapse to mini instead
     * of tearing down. Force clears metadata and hides.
     * Soft hide with no displayable media also tears down (clear-queue safety).
     * Pause-idle temporary tuck uses [hideTemporarilyPreservingIdentity] instead.
     */
    private fun hide(force: Boolean = false) {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        cancelLongPressHint()
        // Surface is going away — an interrupted gesture may never deliver its
        // UP/CANCEL here; a stuck-true flag would defer idle clocks forever.
        isUserTouchingOverlay = false
        if (force) {
            endQueueClearedGraceInternal()
            cancelPauseIdleTeardownInternal()
            awaitingPauseIdleAudibleReshow = false
            awaitingPipelineRebind = false
            // Re-arm cold-start birth gate so service churn + cover drip cannot
            // reopen the window until a real in-session playback note arrives
            // (and Media Controls still allow paint).
            suppressOverlayBirthUntilSessionPlayback = true
        }
        if (!force && enableEqMiniPlayer) {
            if (hasDisplayableContent()) {
                persistAsMini()
                // Soft-collapse during grace: start/refresh the FAB hide clock.
                if (queueClearedGraceActive) {
                    scheduleQueueClearedFabForceHide()
                }
                return
            }
            // No track left — fall through to full teardown.
        }
        // Real teardown — the waiting-shell clock has nothing left to dismiss.
        // (Soft persist-as-mini above keeps it armed so a waiting FAB still expires.)
        handler.removeCallbacks(waitingShellTeardownRunnable)
        releaseOverlayProgressOwner()
        handler.post {
            isMorphAnimating = false
            cancelShellCrossfadeAnimator()
            composeView?.animate()?.cancel()
            composeView?.let { resetMorphTransform(it) }
            coverLoadJob?.cancel()
            coverLoadJob = null
            val host = overlayRoot
            val view = composeView ?: return@post
            view.animate().cancel()
            val teardown = {
                host?.visibility = View.GONE
                view.visibility = View.GONE
                view.alpha = 1f
                shellCrossfadeAlpha = 1f
                applyKeepScreenOn(false)
                overlayExpanded = true
                applyWindowMode(collapsed = false)
                songTitle = ""
                artistName = ""
                albumName = ""
                coverUrl = null
                // Drop Compose refs first; never recycle here — painters may
                // still record/draw the previous ImageBitmap this frame.
                coverBitmap = null
                cachedHaCoverUrl = null
                MediaOverlayMemoryCache.clear()
                // Next cold show starts as FAB preference — keep parked FAB norms.
                userPrefersExpanded = false
                // Do not reset miniFabNormX/Y here: remembered spot must survive
                // unexpected / queue-clear force hide.
                syncMediaChrome()
                OverlayLayerSplit.sync()
            }
            // Mini and legacy full overlay both fade via shellCrossfadeAlpha.
            view.alpha = 1f
            animateShellCrossfade(
                to = 0f,
                durationMs = 220,
                interpolator = AccelerateInterpolator(),
                onEnd = teardown,
            )
        }
    }

    /**
     * Bind the shared dashboard «Back» strip only while the full media overlay
     * is expanded (same chrome as weather / clock). Mini FAB stays unbound.
     * Also mirrors expanded visibility onto HA `vinyl_cover_display` when exposed.
     */
    private fun syncMediaChrome() {
        val shouldBind =
            overlayRoot?.visibility == View.VISIBLE &&
                composeView?.visibility == View.VISIBLE &&
                overlayExpanded
        if (shouldBind) {
            DashboardOverlayChrome.bind(this, DashboardOverlayChrome.Kind.MEDIA_PLAYER)
            DashboardOverlayChrome.bringToFront()
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                // Long-press never completes on these panels, and a tap-to-reveal
                // hides again in 3s. Keep «Back» up for the whole expanded session
                // so the player can actually be put away.
                DashboardOverlayChrome.reveal(
                    DashboardOverlayChrome.Kind.MEDIA_PLAYER,
                    autoHideMs = 3_600_000L,
                )
            }
        } else {
            DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.MEDIA_PLAYER)
        }
        syncHaVinylCoverExpandedVisible(shouldBind)
    }

    /**
     * Two-way: write [PlayerSettings.enableVinylCoverVisible] only when the HA display
     * preference is on. FAB-only / hidden → false; expanded glass → true.
     */
    private fun syncHaVinylCoverExpandedVisible(expandedShowing: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val settings = playerSettingsStore.get()
                if (!settings.enableVinylCoverDisplay) return@runCatching
                if (settings.enableVinylCoverVisible == expandedShowing) return@runCatching
                playerSettingsStore.enableVinylCoverVisible.set(expandedShowing)
                Log.d(TAG, "HA vinyl_cover_display → $expandedShowing (expanded mirror)")
            }.onFailure {
                Log.w(TAG, "Failed to sync HA vinyl_cover_display", it)
            }
        }
    }


    /**
     * Keep the display awake while the now-playing overlay is visible
     * (lyrics / cover). Cleared on hide. This is window-level, not a
     * notification MediaSession — MediaStyle notifications do not prevent
     * screen timeout.
     *
     * Also drives [blocksScreensaver]: full player (expanded, or no-FAB
     * full overlay) must not be covered by idle screensaver; mini FAB alone
     * does not block. Falling edge **must** notify [ScreensaverController]
     * so the idle countdown restarts — polling alone never re-arms the timer.
     */
    private fun applyKeepScreenOn(enabled: Boolean) {
        val wasBlocking = blocksScreensaver
        // Soft-pause screensaver display via the same hide-only path as Settings →
        // Background Pause (no HA screensaver_display OFF).
        blocksScreensaver = enabled
        if (enabled) {
            if (!wasBlocking) {
                ScreensaverController.refreshSoftPauseDisplay()
            }
        } else if (wasBlocking) {
            // FAB / tucked: soft-pause cleared — restart idle countdown.
            ScreensaverController.onFullPlayerReleased()
        }
        val host = overlayRoot ?: return
        val params = windowParams ?: return
        val wm = windowManager ?: return
        val hasFlag = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (enabled == hasFlag) {
            host.keepScreenOn = enabled
            composeView?.keepScreenOn = enabled
            return
        }
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        host.keepScreenOn = enabled
        composeView?.keepScreenOn = enabled
        if (host.parent != null) {
            runCatching { wm.updateViewLayout(host, params) }
                .onFailure { Log.w(TAG, "Failed to update keep-screen-on=$enabled", it) }
        }
    }

    /** True when the WM shell is the parked circular mini control (not expanded glass). */
    private fun isParkedMiniFab(): Boolean {
        if (!enableEqMiniPlayer) return false
        if (overlayExpanded || windowIsFullScreen || isMorphAnimating) return false
        val host = overlayRoot ?: return false
        val view = composeView ?: return false
        return host.visibility == View.VISIBLE &&
            view.visibility == View.VISIBLE &&
            miniShellLayout
    }

    private fun hasDisplayableContent(): Boolean {
        return songTitle.isNotEmpty() || artistName.isNotEmpty() || coverUrl != null || coverBitmap != null
    }

    /**
     * Localized empty-shell placeholder from [ensureExpandableTitle] — not real media.
     * Must not be treated like a track for soft-collapse (FAB-with-waiting still covers
     * touch paths poorly); chrome dismiss / HA OFF should tear the shell down.
     */
    private fun isWaitingPlaceholderOnly(): Boolean {
        val waiting = getString(R.string.media_overlay_waiting_for_media)
        return songTitle == waiting &&
            artistName.isBlank() &&
            albumName.isBlank() &&
            coverUrl == null &&
            coverBitmap == null
    }

    private fun resetPlayheadZeroGlitchGuard() {
        playheadZeroGlitchCount = 0
    }

    /**
     * Apply overlay playhead. Repeated mid-track collapses to ~0:00 (progress/metadata
     * races) are counted; after [PLAYHEAD_ZERO_GLITCH_HOLD_AFTER] we keep the last
     * progress instead of painting 0:00. Real track/queue clears reset via
     * [resetPlayheadZeroGlitchGuard] / [force].
     */
    private fun applyCurrentTimeMs(incoming: Long, force: Boolean = false) {
        val next = incoming.coerceAtLeast(0L)
        if (!force) {
            val prev = currentTimeMs
            val suspiciousDrop =
                prev >= PLAYHEAD_ZERO_GLITCH_ANCHOR_MS &&
                    next < PLAYHEAD_ZERO_GLITCH_NEAR_ZERO_MS &&
                    (prev - next) > PLAYHEAD_ZERO_GLITCH_DROP_MS
            if (suspiciousDrop) {
                // Reject immediately — do not paint the first 1–2 zero flashes
                // (pause/metadata races). Title/queue clear uses force=true.
                playheadZeroGlitchCount++
                return
            }
        }
        currentTimeMs = next
    }

    /**
     * Sticky track duration — pause/partial progress packets often omit or zero
     * [media_duration]; do not wipe a known length until the next title arrives.
     */
    private fun applyTotalTimeMs(incoming: Long, force: Boolean = false) {
        val next = incoming.coerceAtLeast(0L)
        if (next > 0L) {
            totalTimeMs = next
            return
        }
        if (force) {
            totalTimeMs = 0L
        }
    }

    /**
     * Apply title / artist / album from a SHOW or UPDATE_METADATA intent.
     * On title change, missing or blank artist/album clears sticky previous-track
     * credits so lyrics search is not "new title + old artist".
     */
    private fun applyOverlayIdentity(
        title: String?,
        artist: String?,
        album: String?,
        hasArtistExtra: Boolean,
        hasAlbumExtra: Boolean,
        clearPlayheadOnTitleChange: Boolean,
    ) {
        val titleChanged = !title.isNullOrEmpty() && title != songTitle
        if (title != null) {
            if (titleChanged) {
                resetPlayheadZeroGlitchGuard()
                applyTotalTimeMs(0L, force = true)
                // In-session track change without an explicit playhead → clear.
                // Cold reconnect (blank → first title) must NOT invent 0:00 as a
                // trusted position while audio may already be mid-track.
                if (clearPlayheadOnTitleChange && songTitle.isNotEmpty()) {
                    applyCurrentTimeMs(0L, force = true)
                }
            }
            songTitle = title
        }
        when {
            titleChanged -> {
                artistName = if (hasArtistExtra) artist.orEmpty() else ""
                albumName = if (hasAlbumExtra) album.orEmpty() else ""
            }
            hasArtistExtra -> artistName = artist.orEmpty()
            // no artist extra on same title → keep sticky (partial packet)
        }
        if (!titleChanged && hasAlbumExtra) {
            albumName = album.orEmpty()
        }
    }

    /** Hydrate service UI fields from the process-wide memory snapshot. */
    private fun applyMemoryCacheToUi() {
        val snap = MediaOverlayMemoryCache.get()
        if (snap.songTitle.isNotEmpty()) {
            songTitle = snap.songTitle
            // Empty artist/album in snapshot is intentional (title changed, no credit).
            artistName = snap.artistName
            albumName = snap.albumName
        } else {
            if (snap.artistName.isNotEmpty()) artistName = snap.artistName
            if (snap.albumName.isNotEmpty()) albumName = snap.albumName
        }
        if (!snap.coverUrl.isNullOrEmpty()) coverUrl = snap.coverUrl
        isPlaying = snap.isPlaying
        if (snap.progressSeated || snap.currentTimeMs > 0L) {
            applyCurrentTimeMs(
                snap.currentTimeMs,
                force = snap.progressSeated && snap.currentTimeMs == 0L,
            )
        }
        applyTotalTimeMs(snap.totalTimeMs)
        isSendspinSource = snap.isSendspinSource
        lyricAudibleLagMs = snap.lyricAudibleLagMs
        val cachedBmp = snap.coverBitmap
        if (coverBitmap == null && cachedBmp != null && !cachedBmp.isRecycled) {
            coverBitmap = cachedBmp
        }
    }

    /** Persist current UI fields into process memory (survives service recreate). */
    private fun syncUiToMemoryCache() {
        MediaOverlayMemoryCache.putFull(
            coverUrl = coverUrl,
            songTitle = songTitle,
            artistName = artistName,
            albumName = albumName,
            isPlaying = isPlaying,
            currentTimeMs = currentTimeMs,
            totalTimeMs = totalTimeMs,
            isSendspinSource = isSendspinSource,
            lyricAudibleLagMs = lyricAudibleLagMs,
            coverBitmap = coverBitmap,
        )
    }

    /**
     * Prefetch current-track lyrics into [LyricsRepository] memory while the mini
     * FAB is up (glass alpha 0). Expanding then peeks cache instead of refetching.
     * When Mass API is connected, also nudge neighbor (±2) MA disk warm.
     */
    private fun warmLyricsCacheInBackground() {
        val title = songTitle.trim()
        if (title.isEmpty()) return
        lyricsWarmJob?.cancel()
        val artist = artistName
        val album = albumName
        val duration = totalTimeMs
        // Karaoke rendering is always on, so warm word-synced lyrics when available.
        val preferWord = true
        lyricsWarmJob = coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                com.example.ava.lyrics.LyricsRepository.prefetch(
                    title = title,
                    artist = artist,
                    durationMs = duration,
                    album = album,
                    preferWordSync = preferWord,
                    context = applicationContext,
                )
            }
            runCatching {
                com.example.ava.lyrics.LyricsRepository.prefetchMassNeighborLyrics(
                    applicationContext,
                    radius = 2,
                )
            }
        }
    }

    override fun onDestroy() {
        OverlayLayerSplit.releasePane(OverlayLayerSplit.Layer.MEDIA_PLAYER, overlayRoot)
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.MEDIA_PLAYER)
        super.onDestroy()
        runCatching { applicationContext.unregisterComponentCallbacks(configurationCallbacks) }
        releaseOverlayProgressOwner()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        
        handler.removeCallbacksAndMessages(null)
        cancelShellCrossfadeAnimator()
        composeView?.animate()?.cancel()
        isMorphAnimating = false
        shellCrossfadeAlpha = 1f
        coroutineScope.cancel()
        coverLoadJob?.cancel()
        coverLoadJob = null
        lyricsWarmJob?.cancel()
        lyricsWarmJob = null
        viewModelStoreImpl.clear()

        // Clear state before tearing down the Compose hierarchy so draw
        // never sees a recycled bitmap. Do not Bitmap.recycle() — shared
        // with sticky cover painters until GC.
        coverBitmap = null
        
        DashboardOverlayChrome.unbind(DashboardOverlayChrome.Kind.MEDIA_PLAYER)
        try { 
            overlayRoot?.let { windowManager?.removeView(it) }
                ?: composeView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove glass music player window", e)
        }
        overlayRoot = null
        composeView = null
        if (blocksScreensaver) {
            blocksScreensaver = false
            ScreensaverController.onFullPlayerReleased()
        } else {
            blocksScreensaver = false
        }
        instance = null
    }

    companion object {
        private const val TAG = "VinylCoverService"

        /** Fresh-appearance fade of the whole shell — the one animation birth should show. */
        private const val BIRTH_CROSSFADE_MS = 280
        /** Vsyncs [startBirthCrossfadeAfterFirstFrame] lets pass before starting the fade. */
        private const val BIRTH_CROSSFADE_FRAME_HOPS = 3
        /**
         * Deadline for that wait. Generous enough (~12 frames at 60Hz) that a genuinely
         * slow birth still gets deferred, short enough that a shell which never receives a
         * frame callback — vsync stalls with the display off — does not stay invisible.
         */
        private const val BIRTH_CROSSFADE_FALLBACK_MS = 200L

        /**
         * True while the full-screen now-playing surface holds keep-screen-on
         * (expanded player, or full overlay when mini FAB is off). Mini FAB
         * alone leaves this false so idle screensaver can still appear.
         */
        @Volatile
        private var blocksScreensaver: Boolean = false

        /**
         * Parked mini FAB must never soft-pause idle screensaver — even if
         * [blocksScreensaver] was left stale after an interrupted expand/collapse.
         */
        fun isFullPlayerBlockingScreensaver(): Boolean {
            val svc = instance
            if (svc?.isParkedMiniFab() == true) return false
            if (OverlayLayerSplit.isPaneView(svc?.overlayRoot)) return false
            return blocksScreensaver
        }

        /**
         * Browser home / settings closes the split. Park the expanded player as
         * the small button so its half does not grow over the screen.
         */
        fun collapseExpandedForSplitNavigation() {
            val svc = instance ?: return
            val collapse = Runnable {
                val host = svc.overlayRoot ?: return@Runnable
                if (host.visibility != View.VISIBLE) return@Runnable
                if (!svc.overlayExpanded && !svc.windowIsFullScreen) return@Runnable
                svc.cancelShellCrossfadeAnimator()
                svc.composeView?.let { svc.resetMorphTransform(it) }
                svc.isMorphAnimating = false
                svc.userPrefersExpanded = false
                if (svc.enableEqMiniPlayer && svc.hasDisplayableContent()) {
                    svc.overlayExpanded = false
                    svc.applyWindowMode(collapsed = true)
                    svc.applyKeepScreenOn(false)
                    svc.syncMediaChrome()
                    OverlayLayerSplit.sync()
                    svc.bringToFront()
                } else {
                    host.visibility = View.GONE
                    svc.composeView?.visibility = View.GONE
                    svc.overlayExpanded = false
                    svc.windowIsFullScreen = false
                    svc.miniShellLayout = false
                    svc.applyKeepScreenOn(false)
                    OverlayLayerSplit.sync()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) collapse.run() else svc.handler.post(collapse)
        }

        /**
         * Expand/collapse is fade-out → swap WM shell while invisible → fade-in.
         * No scale. OEM windowAnimations are disabled on this overlay.
         */
        private const val CROSSFADE_OUT_MS = 180
        private const val CROSSFADE_IN_MS = 260
        private const val COLLAPSE_FADE_MS = 240
        private const val CROSSFADE_SAFETY_MS = 900L
        /** Keep «Back» up longer on empty waiting shell — no media controls to discover exit. */
        private const val EMPTY_SHELL_CHROME_AUTO_HIDE_MS = 12_000L

        /** First-run long-press hint: delay after the full player becomes visible. */
        private const val LONG_PRESS_HINT_DELAY_MS = 1_000L
        /** Persisted toast shows per install; afterwards never bother again. */
        private const val LONG_PRESS_HINT_MAX_SHOWS = 2
        private const val HINT_PREFS_NAME = "vinyl_cover_hints"
        private const val KEY_LONG_PRESS_HINT_COUNT = "long_press_back_hint_count"
        /** Legacy boolean from pre-count builds (never written anymore). */
        private const val KEY_LONG_PRESS_HINT_SHOWN_LEGACY = "long_press_back_hint_shown"
        /** Re-showing same tag refreshes the toast instead of stacking. */
        private const val LONG_PRESS_HINT_TAG = "vinyl_long_press_back"
        /** Waiting-only shell with no real media after this → force teardown (HA OFF mirror). */
        private const val WAITING_SHELL_TEARDOWN_MS = 120_000L
        /** Empty-queue: expanded detailed metadata idle → collapse to FAB. */
        private const val QUEUE_CLEAR_EXPANDED_IDLE_MS = 30_000L
        /** Empty-queue: FAB idle → force teardown. */
        private const val QUEUE_CLEAR_FAB_HIDE_MS = 60_000L
        /** Pause (track still held): yield to FAB / temporary hide. Shared HA + Sendspin. */
        private const val PAUSE_IDLE_YIELD_MS = 30_000L
        /** After soft→FAB on pause: temporary hide if still idle (identity kept). */
        private const val PAUSE_IDLE_FORCE_MS = 60_000L
        /**
         * Chrome back with no FAB: keep the full overlay down this long so
         * continuous PCM cannot immediately reopen it. FAB collapse is unchanged.
         */
        private const val USER_DISMISS_HIDE_MS = 20_000L
        /** Playhead was meaningful before a collapse toward 0:00. */
        private const val PLAYHEAD_ZERO_GLITCH_ANCHOR_MS = 1_500L
        /** Treat as "0:00 flash" rather than a real intro scrub. */
        private const val PLAYHEAD_ZERO_GLITCH_NEAR_ZERO_MS = 500L
        /** Ignore tiny seeks; require a real collapse. */
        private const val PLAYHEAD_ZERO_GLITCH_DROP_MS = 2_000L
        /** After this many mid-track 0:00 flashes, hold last progress. */
        private const val PLAYHEAD_ZERO_GLITCH_HOLD_AFTER = 3
        /** Brief pause before a second cover fetch; then abandon. */
        private const val COVER_LOAD_RETRY_DELAY_MS = 300L
        @Volatile private var instance: VinylCoverService? = null

        /**
         * True while the overlay is visible with Sendspin-sourced content.
         * HA-driven hide paths must not tear down a live Sendspin overlay
         * (e.g. HA idle events racing a Sendspin network reconnect).
         */
        @Volatile
        var isSendspinContentActive: Boolean = false
            private set

        /**
         * Single owner for overlay progress / lyrics / playhead UI.
         * [ACTION_SHOW] always claims (latter wins); non-owner progress/metadata
         * mutations are dropped so HA + Sendspin tickers cannot deadlock-fight.
         */
        enum class OverlayProgressOwner { NONE, HA, SENDSPIN }

        @Volatile
        var progressOwner: OverlayProgressOwner = OverlayProgressOwner.NONE
            private set

        fun isSendspinProgressOwner(): Boolean =
            progressOwner == OverlayProgressOwner.SENDSPIN

        fun isHaProgressOwner(): Boolean =
            progressOwner == OverlayProgressOwner.HA

        /** Accept HA vs Sendspin overlay mutations under current [progressOwner]. */
        fun ownsOverlayProgress(fromSendspin: Boolean): Boolean =
            when (progressOwner) {
                OverlayProgressOwner.NONE -> true
                OverlayProgressOwner.SENDSPIN -> fromSendspin
                OverlayProgressOwner.HA -> !fromSendspin
            }

        /** Latter-wins claim from SHOW (or equivalent full paint). */
        fun claimOverlayProgressOwner(fromSendspin: Boolean) {
            progressOwner = if (fromSendspin) {
                OverlayProgressOwner.SENDSPIN
            } else {
                OverlayProgressOwner.HA
            }
            isSendspinContentActive = fromSendspin
        }

        fun releaseOverlayProgressOwner() {
            progressOwner = OverlayProgressOwner.NONE
            isSendspinContentActive = false
        }

        /**
         * Process-wide arm for mini FAB, set from VS/DataStore before the service
         * may exist — avoids HA title drip painting a full overlay before settings load.
         */
        @Volatile
        var preferredEqMini: Boolean = false

        /**
         * Cold session gate: block overlay birth until HA/Sendspin reports real
         * in-session playback (not stale reconnect metadata). [show] still writes
         * [MediaOverlayMemoryCache] while suppressed.
         */
        @Volatile
        var suppressOverlayBirthUntilSessionPlayback: Boolean = true

        fun isOverlayBirthSuppressed(): Boolean = suppressOverlayBirthUntilSessionPlayback

        /**
         * Chrome-back force-hide cooldown. PCM must not lift the birth gate
         * until this elapses. HA [showExpandedFromHa] still uses allowEmpty.
         */
        @Volatile
        private var userDismissHideUntilElapsed = 0L

        private fun armUserDismissHide() {
            userDismissHideUntilElapsed = SystemClock.elapsedRealtime() + USER_DISMISS_HIDE_MS
        }

        private fun isUserDismissHideActive(): Boolean =
            SystemClock.elapsedRealtime() < userDismissHideUntilElapsed

        /**
         * Finger still on the dashboard during chrome-back cooldown: restart
         * the hide window from now. No-op unless the cooldown is already armed.
         */
        fun pokeUserDismissHideIfArmed() {
            if (isUserDismissHideActive()) armUserDismissHide()
        }

        /** @return true when the gate was lifted (first session playback). */
        fun noteSessionPlaybackStarted(): Boolean {
            if (isUserDismissHideActive()) return false
            if (!suppressOverlayBirthUntilSessionPlayback) return false
            suppressOverlayBirthUntilSessionPlayback = false
            return true
        }

        /**
         * Pause-idle temporary tuck completed with identity kept — waiting for
         * the next real PCM to re-show. Not armed for force-hide / HA soft tuck.
         */
        @Volatile
        var awaitingPauseIdleAudibleReshow: Boolean = false
            private set

        /** True only while pause-idle prepared hide is waiting for audible re-show. */
        fun isAwaitingPauseIdleAudibleReshow(): Boolean = awaitingPauseIdleAudibleReshow

        /**
         * Soft restart / Sendspin pipeline recreate: keep the painted shell and
         * wait for the next live manager to rebind (no tear-down). Blocks
         * pause-idle tuck so pressure restarts do not GONE a still-valid UI.
         */
        @Volatile
        var awaitingPipelineRebind: Boolean = false
            private set

        /** Failsafe: do not block pause-idle forever if MA never comes back. */
        private const val PIPELINE_REBIND_TIMEOUT_MS = 60_000L

        private val pipelineRebindTimeoutHandler = Handler(Looper.getMainLooper())
        private val pipelineRebindTimeoutRunnable = Runnable {
            if (!awaitingPipelineRebind) return@Runnable
            awaitingPipelineRebind = false
            Log.w(TAG, "pipeline rebind timed out; pause-idle allowed again")
            instance?.handler?.post {
                val svc = instance ?: return@post
                if (!svc.isPlaying && svc.hasDisplayableContent()) {
                    svc.schedulePauseIdleTeardownInternal(restart = true)
                }
            }
        }

        fun isAwaitingPipelineRebind(): Boolean = awaitingPipelineRebind

        fun markAwaitingPipelineRebind() {
            awaitingPipelineRebind = true
            awaitingPauseIdleAudibleReshow = false
            val shellUp = isLiveOverlayShellVisible()
            // Only lift the cold-start birth gate when a shell is already painted.
            // GONE + cover drip must still wait for real session playback.
            if (shellUp) {
                suppressOverlayBirthUntilSessionPlayback = false
            }
            instance?.handler?.post {
                instance?.cancelPauseIdleTeardownInternal()
            }
            // Keep Sendspin ownership on a live zombie shell so HA cannot steal it.
            // Do not claim from NONE while GONE — that let cover mutations "own" birth.
            if (shellUp &&
                (progressOwner == OverlayProgressOwner.NONE ||
                    MediaOverlayMemoryCache.get().isSendspinSource ||
                    progressOwner == OverlayProgressOwner.SENDSPIN)
            ) {
                claimOverlayProgressOwner(fromSendspin = true)
            }
            pipelineRebindTimeoutHandler.removeCallbacks(pipelineRebindTimeoutRunnable)
            pipelineRebindTimeoutHandler.postDelayed(
                pipelineRebindTimeoutRunnable,
                PIPELINE_REBIND_TIMEOUT_MS,
            )
            Log.d(TAG, "pipeline rebind armed (shellUp=$shellUp)")
        }

        fun clearAwaitingPipelineRebind() {
            pipelineRebindTimeoutHandler.removeCallbacks(pipelineRebindTimeoutRunnable)
            if (!awaitingPipelineRebind) return
            awaitingPipelineRebind = false
            Log.d(TAG, "pipeline rebind cleared")
        }

        /** Visible FAB / expanded shell still on screen (zombie or live). */
        fun isLiveOverlayShellVisible(): Boolean {
            val svc = instance ?: return false
            return svc.overlayRoot?.visibility == View.VISIBLE &&
                svc.composeView?.visibility == View.VISIBLE
        }

        /**
         * Pause-idle (or equivalent) left the surface GONE while title/cover identity
         * is still preserved — PCM should call [SendspinManager.refreshVinylFromCacheIfNeeded]
         * even if [awaitingPauseIdleAudibleReshow] was cleared by a race.
         */
        fun shouldAudibleReshowHiddenOverlay(): Boolean {
            val svc = instance
            val visible =
                svc?.overlayRoot?.visibility == View.VISIBLE &&
                    svc.composeView?.visibility == View.VISIBLE
            if (visible) return false
            if (svc != null && svc.hasDisplayableContent()) return true
            return MediaOverlayMemoryCache.get().hasDisplayableContent()
        }

        const val ACTION_SHOW = "com.example.ava.SHOW_VINYL"
        const val ACTION_HIDE = "com.example.ava.HIDE_VINYL"
        const val ACTION_HIDE_EXPANDED_FROM_HA = "com.example.ava.HIDE_VINYL_EXPANDED_FROM_HA"
        const val ACTION_BEGIN_QUEUE_CLEARED_GRACE = "com.example.ava.BEGIN_QUEUE_CLEARED_GRACE"
        const val ACTION_END_QUEUE_CLEARED_GRACE = "com.example.ava.END_QUEUE_CLEARED_GRACE"
        const val ACTION_SCHEDULE_PAUSE_IDLE = "com.example.ava.SCHEDULE_PAUSE_IDLE"
        const val ACTION_CANCEL_PAUSE_IDLE = "com.example.ava.CANCEL_PAUSE_IDLE"
        const val ACTION_CLEAR_QUEUE_CONTENT = "com.example.ava.CLEAR_QUEUE_CONTENT"
        const val ACTION_RESET_WAITING_FOR_MEDIA = "com.example.ava.RESET_WAITING_FOR_MEDIA"
        const val ACTION_ENSURE_PERSISTENT_MINI = "com.example.ava.ENSURE_PERSISTENT_MINI"
        const val ACTION_UPDATE_COVER = "com.example.ava.UPDATE_VINYL_COVER"
        const val ACTION_SET_HA_COVER = "com.example.ava.SET_HA_COVER"
        const val ACTION_UPDATE_METADATA = "com.example.ava.UPDATE_METADATA"
        const val ACTION_UPDATE_PLAYBACK_STATE = "com.example.ava.UPDATE_PLAYBACK_STATE"
        const val ACTION_UPDATE_PROGRESS = "com.example.ava.UPDATE_PROGRESS"
        
        const val EXTRA_COVER_URL = "cover_url"
        const val EXTRA_SONG_TITLE = "song_title"
        const val EXTRA_ARTIST_NAME = "artist_name"
        const val EXTRA_ALBUM_NAME = "album_name"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_CURRENT_TIME_MS = "current_time_ms"
        const val EXTRA_TOTAL_TIME_MS = "total_time_ms"
        const val EXTRA_VOLUME_LEVEL = "volume_level"
        const val EXTRA_REPEAT_MODE = "repeat_mode"
        const val EXTRA_SHUFFLE_ENABLED = "shuffle_enabled"
        const val EXTRA_IS_SENDSPIN_SOURCE = "is_sendspin_source"
        const val EXTRA_LYRIC_AUDIBLE_LAG_MS = "lyric_audible_lag_ms"
        /** Bypass the mid-track→0:00 glitch guard (dead-stream seat, queue clear). */
        const val EXTRA_FORCE_PLAYHEAD = "force_playhead"
        /** Pipeline latency + progress-bar fill-in undo (see SendspinManager). */
        private const val LYRIC_AUDIBLE_LAG_MAX_MS = 660L
        /**
         * How long an optimistic repeat/shuffle paint may stand without an upstream
         * report. Long enough to cover a LAN round trip plus the upstream's own
         * state-push cadence, short enough that a wrong glyph is not what the user
         * ends up looking at.
         */
        private const val TRANSPORT_CONFIRM_WINDOW_MS = 2_000L
        const val EXTRA_FORCE_HIDE = "force_hide"
        const val EXTRA_PAUSE_IDLE_RESTART = "pause_idle_restart"
        const val EXTRA_ALLOW_EMPTY = "allow_empty"
        const val EXTRA_FORCE_EXPANDED = "force_expanded"
        /** Home-prefs key: FAB expand also expands the paired now-playing page. Off by default. */
        const val PREF_SYNC_PEER_EXPAND = "mass_rail_sync_fab_expand"
        
        const val ACTION_UPDATE_PLAYBACK_SETTINGS = "com.example.ava.UPDATE_PLAYBACK_SETTINGS"

        fun bringToFrontIfVisible() {
            instance?.bringToFront()
        }

        /** Mini FAB armed via settings or process preference. */
        /**
         * Rail search box asking for / handing back the soft keyboard. Posted to the main
         * handler so a Compose focus callback can never re-enter WindowManager mid-layout.
         */
        fun setRailSearchImeFocus(wanted: Boolean) {
            instance?.handler?.post { instance?.applyOverlayImeFocus(wanted) }
        }

        fun isEqMiniArmed(): Boolean =
            instance?.enableEqMiniPlayer == true || preferredEqMini

        /**
         * Full-screen media overlay is visible without a mini FAB to collapse into.
         * Peers (browser) may cover this layer — same idea as voice-message on top.
         */
        fun isFullscreenWithoutMiniFab(): Boolean {
            val svc = instance ?: return false
            if (svc.enableEqMiniPlayer || preferredEqMini) return false
            val host = svc.overlayRoot ?: return false
            return host.visibility == View.VISIBLE
        }

        /** Expanded / MATCH_PARENT player — not the mini FAB. */
        fun isExpandedShowing(): Boolean {
            val svc = instance ?: return false
            val host = svc.overlayRoot ?: return false
            return host.visibility == View.VISIBLE && (svc.overlayExpanded || svc.windowIsFullScreen)
        }

        /**
         * Soft-collapse expanded player → FAB when another foreground overlay
         * (browser, etc.) needs the screen. No-op without mini FAB or when already collapsed.
         */
        fun yieldExpandedToForegroundOverlay() {
            val svc = instance ?: return
            val collapse = Runnable {
                if (!svc.enableEqMiniPlayer) return@Runnable
                if (svc.overlayRoot?.visibility != View.VISIBLE) return@Runnable
                if (!svc.overlayExpanded && !svc.windowIsFullScreen) return@Runnable
                if (!svc.hasDisplayableContent()) return@Runnable
                svc.persistAsMini()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                collapse.run()
            } else {
                svc.handler.post(collapse)
            }
        }

        /**
         * HA `vinyl_cover_display` ON: present the expanded player (empty shell allowed).
         * Does not require metadata — playback can fill it later.
         */
        fun showExpandedFromHa(context: Context) {
            if (ModMediaOverlayExclusive.isActive(context)) {
                Log.d(TAG, "Skip HA expanded show: mod exclusive media overlay active")
                return
            }
            val svc = instance
            if (svc != null && svc.overlayExpanded && svc.overlayRoot?.visibility == View.VISIBLE) {
                return
            }
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_ALLOW_EMPTY, true)
                putExtra(EXTRA_FORCE_EXPANDED, true)
            }
            context.startService(intent)
        }

        /**
         * HA `vinyl_cover_display` OFF: collapse to FAB when mini+content, else soft-hide
         * while preserving protocol metadata / memory cache (never force-clear).
         */
        fun hideExpandedFromHa(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.hideExpandedFromHaInternal()
                return
            }
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_HIDE_EXPANDED_FROM_HA
            }
            context.startService(intent)
        }

        fun show(
            context: Context,
            coverUrl: String? = null,
            songTitle: String? = null,
            artistName: String? = null,
            albumName: String? = null,
            isPlaying: Boolean? = null,
            currentTimeMs: Long? = null,
            totalTimeMs: Long? = null,
            isSendspinSource: Boolean = false,
            lyricAudibleLagMs: Long? = null,
        ) {
            // AirPlay / DLNA cinema owns media UI — do not birth Sendspin/HA vinyl FAB.
            if (ModMediaOverlayExclusive.isActive(context)) {
                Log.d(TAG, "Skip vinyl show: mod exclusive media overlay active")
                return
            }
            // Latter wins: claim before cache/service so concurrent HA/MA tickers drop.
            claimOverlayProgressOwner(isSendspinSource)
            // Detect title change before cache write (putFull updates the snapshot).
            val titleChanging =
                !songTitle.isNullOrEmpty() &&
                    songTitle != MediaOverlayMemoryCache.get().songTitle
            // Write memory BEFORE starting the service so recreate / races still
            // see the latest playback + metadata snapshot.
            val playing = isPlaying ?: MediaOverlayMemoryCache.get().isPlaying
            MediaOverlayMemoryCache.putFull(
                coverUrl = coverUrl,
                songTitle = songTitle,
                artistName = artistName,
                albumName = albumName,
                isPlaying = playing,
                currentTimeMs = currentTimeMs,
                totalTimeMs = totalTimeMs,
                isSendspinSource = isSendspinSource,
                lyricAudibleLagMs = lyricAudibleLagMs,
            )
            // The gate blocks BIRTH only. An already-visible shell (sidebar
            // allowEmpty waiting state) must keep receiving metadata pushes,
            // or the waiting placeholder starves until manual back-out.
            if ((suppressOverlayBirthUntilSessionPlayback || isUserDismissHideActive()) &&
                !isLiveOverlayShellVisible()
            ) {
                Log.d(TAG, "Skip vinyl show: awaiting session playback")
                return
            }
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_SHOW
                coverUrl?.let { putExtra(EXTRA_COVER_URL, it) }
                putOverlayIdentityExtras(
                    songTitle = songTitle,
                    artistName = artistName,
                    albumName = albumName,
                    clearMissingCredits = titleChanging,
                )
                putExtra(EXTRA_IS_PLAYING, playing)
                currentTimeMs?.let { putExtra(EXTRA_CURRENT_TIME_MS, it) }
                totalTimeMs?.let { putExtra(EXTRA_TOTAL_TIME_MS, it) }
                putExtra(EXTRA_IS_SENDSPIN_SOURCE, isSendspinSource)
                lyricAudibleLagMs?.let { putExtra(EXTRA_LYRIC_AUDIBLE_LAG_MS, it.coerceIn(0L, LYRIC_AUDIBLE_LAG_MAX_MS)) }
            }
            context.startService(intent)
        }

        /**
         * Hide the overlay. When [force] is false and persistent mini is enabled
         * with displayable media, collapses to the mini control instead of tearing down.
         */
        fun hide(context: Context, force: Boolean = false) {
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_HIDE
                putExtra(EXTRA_FORCE_HIDE, force)
            }
            context.startService(intent)
        }

        /**
         * Shared pause-idle (HA + Sendspin): 30s yield to FAB (or temporary hide
         * without mini); FAB then tucks away after another 60s while keeping
         * identity for the next play. [restart]=false does not reset the clock.
         */
        fun schedulePauseIdleTeardown(context: Context, restart: Boolean = false) {
            val svc = instance
            if (svc != null) {
                svc.handler.post { svc.schedulePauseIdleTeardownInternal(restart) }
                return
            }
            context.startService(
                Intent(context, VinylCoverService::class.java).apply {
                    action = ACTION_SCHEDULE_PAUSE_IDLE
                    putExtra(EXTRA_PAUSE_IDLE_RESTART, restart)
                }
            )
        }

        /** Cancel pause-idle (playback resumed / queue-clear / force hide). */
        fun cancelPauseIdleTeardown(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.handler.post { svc.cancelPauseIdleTeardownInternal() }
                return
            }
            context.startService(
                Intent(context, VinylCoverService::class.java).apply {
                    action = ACTION_CANCEL_PAUSE_IDLE
                }
            )
        }

        /**
         * After soft-collapse to FAB on empty queue: arm 60s FAB hide; expand
         * switches to 30s idle→FAB then 60s hide (see [onQueueClearedExpanded]).
         */
        fun beginQueueClearedGrace(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.handler.post { svc.beginQueueClearedGraceInternal() }
                return
            }
            context.startService(
                Intent(context, VinylCoverService::class.java).apply {
                    action = ACTION_BEGIN_QUEUE_CLEARED_GRACE
                }
            )
        }

        /** Cancel empty-queue grace (new playback / disconnect). */
        fun endQueueClearedGrace(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.handler.post { svc.endQueueClearedGraceInternal() }
                return
            }
            context.startService(
                Intent(context, VinylCoverService::class.java).apply {
                    action = ACTION_END_QUEUE_CLEARED_GRACE
                }
            )
        }

        /**
         * Queue empty final teardown: wipe overlay identity, playhead, cover, and
         * [MediaOverlayMemoryCache]. Only safe at force-hide time — never during
         * the pause/clear soft window or expanded metadata goes blank.
         */
        fun clearContentForQueueCleared(context: Context) {
            MediaOverlayMemoryCache.clear()
            val svc = instance
            if (svc != null) {
                svc.handler.post { svc.clearQueueDisplayState() }
                return
            }
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_CLEAR_QUEUE_CONTENT
            }
            context.startService(intent)
        }

        /**
         * Soft empty shell for MA queue clear — title becomes waiting-for-media.
         * Overlay stays visible; no grace/hide timeline.
         */
        fun resetToWaitingForMedia(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.handler.post { svc.resetToWaitingForMediaInternal() }
                return
            }
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_RESET_WAITING_FOR_MEDIA
            }
            context.startService(intent)
        }

        /**
         * Shared [DashboardOverlayChrome] back button — same dismiss path as the
         * former long-press on Glass / Detailed (mini soft-collapse or force hide).
         */
        fun dismissViaChrome() {
            val svc = instance ?: return
            svc.handler.post {
                svc.notifyPeerWindowIfEnabled(expanded = false)
                if (svc.isWaitingPlaceholderOnly()) {
                    armUserDismissHide()
                    svc.hide(force = true)
                } else if (svc.enableEqMiniPlayer && svc.hasDisplayableContent()) {
                    svc.persistAsMini()
                    if (svc.queueClearedGraceActive) {
                        svc.scheduleQueueClearedFabForceHide()
                    }
                } else {
                    armUserDismissHide()
                    svc.hide(force = true)
                }
            }
        }

        /**
         * Arm mini mode in-process only. Does **not** start the overlay service
         * and does **not** paint — first [show] with displayable media does.
         */
        fun ensurePersistentMini(context: Context) {
            preferredEqMini = true
            instance?.let {
                it.enableEqMiniPlayer = true
            }
            // Intentionally no startService — avoids empty FAB on cold init.
        }

        /** Keep companion arm in sync with DataStore / VS boot. */
        fun setEqMiniPreferred(enabled: Boolean) {
            preferredEqMini = enabled
            instance?.let { svc ->
                svc.enableEqMiniPlayer = enabled
                if (!enabled) {
                    svc.miniFabNormX = -1f
                    svc.miniFabNormY = -1f
                    svc.refreshMiniFabDrawPosition()
                }
            }
        }
        
        fun setHaCover(context: Context, coverUrl: String) {
            if (!ownsOverlayProgress(fromSendspin = false)) return
            MediaOverlayMemoryCache.updateCover(coverUrl)
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_SET_HA_COVER
                putExtra(EXTRA_COVER_URL, coverUrl)
            }
            context.startService(intent)
        }

        fun updateCover(context: Context, coverUrl: String, isSendspinSource: Boolean = true) {
            if (!ownsOverlayProgress(fromSendspin = isSendspinSource)) return
            MediaOverlayMemoryCache.updateCover(coverUrl)
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_UPDATE_COVER
                putExtra(EXTRA_COVER_URL, coverUrl)
                putExtra(EXTRA_IS_SENDSPIN_SOURCE, isSendspinSource)
            }
            context.startService(intent)
        }

        /**
         * Apply an already-decoded cover (Sendspin artwork binary). Skips HTTP.
         * Pass null to clear.
         */
        fun applyCoverBitmap(bitmap: Bitmap?) {
            if (!ownsOverlayProgress(fromSendspin = true)) return
            MediaOverlayMemoryCache.setCoverBitmap(bitmap)
            val svc = instance
            if (svc != null) {
                svc.handler.post {
                    // Swap only — never recycle the previous UI bitmap while
                    // Compose may still paint it (see recycled-bitmap crash).
                    svc.coverBitmap = bitmap?.takeIf { !it.isRecycled }
                    svc.coverUrl = if (bitmap != null && !bitmap.isRecycled) "sendspin:binary" else null
                }
            }
        }

        fun updateMetadata(
            context: Context,
            songTitle: String? = null,
            artistName: String? = null,
            albumName: String? = null,
            isPlaying: Boolean? = null,
            currentTimeMs: Long? = null,
            totalTimeMs: Long? = null,
            isSendspinSource: Boolean = false,
        ) {
            if (!ownsOverlayProgress(fromSendspin = isSendspinSource)) return
            val titleChanging =
                !songTitle.isNullOrEmpty() &&
                    songTitle != MediaOverlayMemoryCache.get().songTitle
            MediaOverlayMemoryCache.updateMetadata(
                songTitle = songTitle,
                artistName = artistName,
                albumName = albumName,
                isPlaying = isPlaying,
                currentTimeMs = currentTimeMs,
                totalTimeMs = totalTimeMs,
            )
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_UPDATE_METADATA
                putExtra(EXTRA_IS_SENDSPIN_SOURCE, isSendspinSource)
                putOverlayIdentityExtras(
                    songTitle = songTitle,
                    artistName = artistName,
                    albumName = albumName,
                    clearMissingCredits = titleChanging,
                )
                isPlaying?.let { putExtra(EXTRA_IS_PLAYING, it) }
                currentTimeMs?.let { putExtra(EXTRA_CURRENT_TIME_MS, it) }
                totalTimeMs?.let { putExtra(EXTRA_TOTAL_TIME_MS, it) }
            }
            context.startService(intent)
        }

        /**
         * Title paint: on track change, always send artist/album (possibly "") so
         * missing credits clear sticky previous-track values. Same-title partial
         * updates still omit null fields (keep sticky).
         */
        private fun Intent.putOverlayIdentityExtras(
            songTitle: String?,
            artistName: String?,
            albumName: String?,
            clearMissingCredits: Boolean,
        ) {
            songTitle?.let { putExtra(EXTRA_SONG_TITLE, it) }
            if (clearMissingCredits) {
                putExtra(EXTRA_ARTIST_NAME, artistName.orEmpty())
                putExtra(EXTRA_ALBUM_NAME, albumName.orEmpty())
            } else {
                if (artistName != null) putExtra(EXTRA_ARTIST_NAME, artistName)
                if (albumName != null) putExtra(EXTRA_ALBUM_NAME, albumName)
            }
        }

        fun updateProgress(
            context: Context,
            currentTimeMs: Long? = null,
            totalTimeMs: Long? = null,
            lyricAudibleLagMs: Long? = null,
            isSendspinSource: Boolean = false,
            forcePlayhead: Boolean = false,
        ) {
            if (!ownsOverlayProgress(fromSendspin = isSendspinSource)) return
            MediaOverlayMemoryCache.updateProgress(currentTimeMs, totalTimeMs, lyricAudibleLagMs)
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_IS_SENDSPIN_SOURCE, isSendspinSource)
                currentTimeMs?.let { putExtra(EXTRA_CURRENT_TIME_MS, it) }
                totalTimeMs?.let { putExtra(EXTRA_TOTAL_TIME_MS, it) }
                lyricAudibleLagMs?.let { putExtra(EXTRA_LYRIC_AUDIBLE_LAG_MS, it.coerceIn(0L, LYRIC_AUDIBLE_LAG_MAX_MS)) }
                if (forcePlayhead) putExtra(EXTRA_FORCE_PLAYHEAD, true)
            }
            context.startService(intent)
        }
        
        fun updatePlaybackState(
            context: Context,
            isPlaying: Boolean,
            isSendspinSource: Boolean = false,
        ) {
            if (!ownsOverlayProgress(fromSendspin = isSendspinSource)) return
            MediaOverlayMemoryCache.updatePlayback(isPlaying)
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_UPDATE_PLAYBACK_STATE
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_IS_SENDSPIN_SOURCE, isSendspinSource)
            }
            context.startService(intent)
        }
        
        fun updatePlaybackSettings(context: Context, volumeLevel: Float? = null, repeatMode: String? = null, shuffleEnabled: Boolean? = null) {
            val intent = Intent(context, VinylCoverService::class.java).apply {
                action = ACTION_UPDATE_PLAYBACK_SETTINGS
                volumeLevel?.let { putExtra(EXTRA_VOLUME_LEVEL, it) }
                repeatMode?.let { putExtra(EXTRA_REPEAT_MODE, it) }
                shuffleEnabled?.let { putExtra(EXTRA_SHUFFLE_ENABLED, it) }
            }
            context.startService(intent)
        }
    }
}

private class MusicOverlayCallbacks(
    private val service: VinylCoverService,
    private val getIsSendspinSource: () -> Boolean,
    private val getIsPlaying: () -> Boolean,
    private val setIsPlaying: (Boolean) -> Unit,
    private val getRepeatMode: () -> String,
    private val setRepeatMode: (String) -> Unit,
    private val getShuffleEnabled: () -> Boolean,
    private val setShuffleEnabled: (Boolean) -> Unit,
    private val setVolumeLevel: (Float) -> Unit,
    private val getSkipStateUpdateUntil: () -> Long,
    private val setSkipStateUpdateUntil: (Long) -> Unit,
    private val onDismissOverlay: () -> Unit,
    private val suppressInvalidTeardown: () -> Boolean = { false },
) {
    val onPlayPauseClick: () -> Unit = {
        TouchSoundHelper.playClick(service)
        service.releaseOverlayImeFocusInternal()
        if (getIsSendspinSource()) {
            val manager = sendspinManagerOrRecover()
            // Decide from the Manager's authoritative state, not the painted glyph:
            // during a paint-settle window the UI value can lag upstream truth.
            val playingNow = manager?.isPlaying?.value ?: getIsPlaying()
            val cmd = if (playingNow) "pause" else "play"
            if (manager == null || !manager.sendMediaCommand(cmd)) {
                recoverSendspinPipeline("play_pause:$cmd")
                com.example.ava.massapi.MassApiManager.get()?.requestPlayerCommand(cmd)
            }
        } else {
            VoiceSatelliteService.getInstance()?.let { satelliteService ->
                satelliteService.lifecycleScope.launch {
                    satelliteService._voiceSatellite.value?.player?.haMediaPlayPause()
                }
            }
        }
    }

    /** Absolute seek: Sendspin [seek]+position_ms. Optimistic UI is applied in Manager. */
    val onSeekClick: (Long) -> Unit = { positionMs ->
        if (getIsSendspinSource()) {
            val manager = sendspinManagerOrRecover()
            if (manager != null && manager.supportsAbsoluteSeek()) {
                TouchSoundHelper.playClick(service)
                if (!manager.seekTo(positionMs)) {
                    recoverSendspinPipeline("seek")
                }
            } else if (manager == null) {
                recoverSendspinPipeline("seek_no_manager")
            }
        }
    }

    val onPreviousClick: () -> Unit = {
        TouchSoundHelper.playClick(service)
        if (getIsSendspinSource()) {
            val manager = sendspinManagerOrRecover()
            if (manager == null || !manager.sendMediaCommand("previous")) {
                recoverSendspinPipeline("previous")
                com.example.ava.massapi.MassApiManager.get()?.requestPlayerCommand("previous")
            }
        } else {
            setIsPlaying(true)
            setSkipStateUpdateUntil(System.currentTimeMillis() + 2000)
            VoiceSatelliteService.getInstance()?.let { satelliteService ->
                satelliteService.lifecycleScope.launch {
                    satelliteService._voiceSatellite.value?.player?.haMediaPrevious()
                }
            }
        }
    }

    val onNextClick: () -> Unit = {
        TouchSoundHelper.playClick(service)
        if (getIsSendspinSource()) {
            val manager = sendspinManagerOrRecover()
            if (manager == null || !manager.sendMediaCommand("next")) {
                recoverSendspinPipeline("next")
                com.example.ava.massapi.MassApiManager.get()?.requestPlayerCommand("next")
            }
        } else {
            setIsPlaying(true)
            setSkipStateUpdateUntil(System.currentTimeMillis() + 2000)
            VoiceSatelliteService.getInstance()?.let { satelliteService ->
                satelliteService.lifecycleScope.launch {
                    satelliteService._voiceSatellite.value?.player?.haMediaNext()
                }
            }
        }
    }

    val onVolumeChange: (Float) -> Unit = { volume ->
        TouchSoundHelper.playClick(service)
        setVolumeLevel(volume)
        if (getIsSendspinSource()) {
            val volumePercent = (volume * 100f).roundToInt().coerceIn(0, 100)
            VoiceSatelliteService.getInstance()?.sendspinManager?.sendMediaCommand(
                command = "volume",
                volume = volumePercent,
            )
        } else {
            VoiceSatelliteService.getInstance()?.let { satelliteService ->
                satelliteService.lifecycleScope.launch {
                    satelliteService._voiceSatellite.value?.player?.haSetVolume(volume)
                }
            }
        }
    }

    /**
     * Repeat is upstream state, not a local toggle, and neither protocol confirms
     * a command. So there are three outcomes to keep separate:
     *
     * - **Server says it cannot do it** (`supported_commands`): do not move the
     *   glyph at all.
     * - **Command did not reach the wire**: restore immediately and re-push the
     *   value we actually hold.
     * - **Command went out**: paint it, but arm a revert — only an upstream report
     *   settles it ([VinylCoverService.armRepeatRevertInternal]).
     */
    val onRepeatClick: () -> Unit = {
        TouchSoundHelper.playClick(service)
        val previousMode = getRepeatMode()
        val nextMode = when (previousMode) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        if (getIsSendspinSource()) {
            val cmd = when (nextMode) {
                "all" -> "repeat_all"
                "one" -> "repeat_one"
                else -> "repeat_off"
            }
            val manager = sendspinManagerOrRecover()
            if (manager?.supportsControllerCommand(cmd) == false) {
                Log.i(TAG, "repeat: server does not support $cmd; keeping $previousMode")
            } else {
                setRepeatMode(nextMode)
                if (manager?.sendMediaCommand(cmd) == true) {
                    service.armRepeatRevertInternal(previousMode)
                } else {
                    setRepeatMode(previousMode)
                    manager?.repushPlaybackSettingsUi()
                }
            }
        } else {
            // HA `repeat_set` has no response either; the `repeat` attribute push
            // is the confirmation, so the same window applies.
            setRepeatMode(nextMode)
            service.armRepeatRevertInternal(previousMode)
            VoiceSatelliteService.getInstance()?.let { satelliteService ->
                satelliteService.lifecycleScope.launch {
                    satelliteService._voiceSatellite.value?.player?.haSetRepeat(nextMode)
                }
            }
        }
    }

    /** Same three outcomes as [onRepeatClick]. */
    val onShuffleClick: () -> Unit = {
        TouchSoundHelper.playClick(service)
        val previousShuffle = getShuffleEnabled()
        val newShuffle = !previousShuffle
        if (getIsSendspinSource()) {
            val cmd = if (newShuffle) "shuffle" else "unshuffle"
            val manager = sendspinManagerOrRecover()
            if (manager?.supportsControllerCommand(cmd) == false) {
                Log.i(TAG, "shuffle: server does not support $cmd; keeping $previousShuffle")
            } else {
                setShuffleEnabled(newShuffle)
                if (manager?.sendMediaCommand(cmd) == true) {
                    service.armShuffleRevertInternal(previousShuffle)
                } else {
                    setShuffleEnabled(previousShuffle)
                    manager?.repushPlaybackSettingsUi()
                }
            }
        } else {
            setShuffleEnabled(newShuffle)
            service.armShuffleRevertInternal(previousShuffle)
            VoiceSatelliteService.getInstance()?.let { satelliteService ->
                satelliteService.lifecycleScope.launch {
                    satelliteService._voiceSatellite.value?.player?.haSetShuffle(newShuffle)
                }
            }
        }
    }

    val onRevealChrome: () -> Unit = {
        DashboardOverlayChrome.onUserTouch(
            service,
            DashboardOverlayChrome.Kind.MEDIA_PLAYER,
            autoHideMs = DashboardOverlayChrome.MEDIA_AUTO_HIDE_MS,
        )
        service.noteQueueClearedUserActivity()
    }

    val onInvalidData: () -> Unit = {
        // Persistent mini mode: keep the control even if title is briefly blank.
        if (!suppressInvalidTeardown()) {
            onDismissOverlay()
        }
    }

    /**
     * Soft satellite/Sendspin restarts can leave the vinyl shell painted while
     * [VoiceSatelliteService.sendspinManager] is briefly (or permanently) null.
     */
    private fun sendspinManagerOrRecover(): com.example.ava.sendspin.SendspinManager? {
        val manager = VoiceSatelliteService.getInstance()?.sendspinManager
        if (manager != null) return manager
        recoverSendspinPipeline("manager_null")
        return VoiceSatelliteService.getInstance()?.sendspinManager
    }

    private fun recoverSendspinPipeline(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendspinRecoverAtMs < SENDSPIN_RECOVER_MIN_INTERVAL_MS) {
            return
        }
        lastSendspinRecoverAtMs = now
        val manager = VoiceSatelliteService.getInstance()?.sendspinManager
        if (manager != null) {
            // enabled=true + no client is a dead inbound listen, not a missing
            // manager. Recreating the session is what BindException'd 8928 and
            // dropped every subsequent play/pause.
            Log.w(TAG, "Sendspin control dead ($reason); re-arming inbound listen")
            manager.ensureInboundListening()
            return
        }
        Log.w(TAG, "Sendspin control dead ($reason); restarting session for zombie shell")
        VoiceSatelliteService.getInstance()?.restartSendspinSession()
    }

    companion object {
        private const val TAG = "MusicOverlayCallbacks"
        private const val SENDSPIN_RECOVER_MIN_INTERVAL_MS = 2_500L
        @Volatile
        private var lastSendspinRecoverAtMs = 0L
    }
}
