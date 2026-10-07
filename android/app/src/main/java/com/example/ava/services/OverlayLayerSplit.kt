package com.example.ava.services

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.settingsStyleSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Half layout for fullscreen floating-overlay windows: browser, weather,
 * quick entity, simple clock, dream clock, voice message, and the expanded
 * media player. The collapsed music button is not a layer.
 *
 * Portrait stacks top/bottom. Landscape places left/right. The two frames
 * meet at the ratio seam and together cover the real display, edge to edge.
 * A third layer stays full screen. The Esper sphere stays full-screen and
 * centered above the pair. The floating mini window is not a layer.
 */
object OverlayLayerSplit {
    private const val TAG = "OverlayLayerSplit"

    enum class Layer {
        BROWSER,
        WEATHER,
        QUICK_ENTITY,
        SIMPLE_CLOCK,
        DREAM_CLOCK,
        VOICE_MESSAGE,
        MEDIA_PLAYER,
    }

    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Screen rectangle both panes are cut from. Portrait stays at the origin. */
    private data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

    /** One clock for both panes. [first] is the leaver when [exiting]. */
    private class PairMove(
        val first: Layer,
        val second: Layer,
        val firstStart: Frame,
        val secondStart: Frame,
        val firstEnd: Frame,
        val secondEnd: Frame,
        val exiting: Boolean,
    )

    private class Slot(
        val showing: () -> Boolean,
        val host: () -> View?,
        val apply: (Frame?) -> Unit,
    )

    private class Snapshot(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val gravity: Int,
        val horizontalMargin: Float,
        val verticalMargin: Float,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val slots = HashMap<Layer, Slot>()
    /** First-seen order. Hiding a layer does not forget its side. */
    private val appearance = ArrayList<Layer>()
    private val shownOrder = ArrayList<Layer>()
    /** Who was already up on the previous layout. A later open inserts beside that one. */
    private var previouslyShown: Set<Layer> = emptySet()
    /** The two seats. A fullscreen layer already up must not slide into a seat that just emptied. */
    private var pairMembers: Set<Layer> = emptySet()
    private var covered: Set<Layer> = emptySet()
    /** Layers that have already occupied a pane. A later open snaps back instead of flying in. */
    private val placedOnce = HashSet<Layer>()
    private var holdingReveal = false
    private val pendingReveals = ArrayList<() -> Unit>()
    private val saved = HashMap<Layer, Snapshot>()
    private val paneViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
    private var appContext: Context? = null
    private var syncing = false
    private var syncQueued = false
    /** Cold start places the saved pair directly. The push plays only for a later arrival. */
    private var snapEnter = false
    /** Cold start: the first overlay that actually started, and the next one, which waits and inserts. */
    private var coldFirst: Layer? = null
    private var coldSecond: Layer? = null
    private var coldInserted = false
    /** Cold start has at least two remembered layers and the split switch is on. */
    private var coldStartExpectsPair = false
    private var coldStartAttempts = 0
    private var livePrimaryPx: Int? = null
    private var fittedLayer: Pair<Int, Int>? = null
    private var dividerHot = false
    private var dividerView: View? = null
    private var dividerParams: WindowManager.LayoutParams? = null
    private var dividerWm: WindowManager? = null
    private var settingsReady = false
    private var settingsLoadStarted = false
    private var pendingSplitMeasure = false
    private var lastBox: Box? = null
    private var raiseChromeAfterSplit = false
    private val exitingLayers = HashSet<Layer>()
    /** Start rects for the shared enter. Both panes are written before the one animator runs. */
    private var scriptedStart: Map<Layer, Frame>? = null
    private var pairMove: PairMove? = null
    private var pairMotion: ValueAnimator? = null
    private val settled = HashMap<Layer, Frame>()
    private val motions = HashMap<Layer, ValueAnimator>()
    private val hosts = HashMap<Layer, View>()
    private val hostParams = HashMap<Layer, WindowManager.LayoutParams>()
    private val hostWm = HashMap<Layer, WindowManager>()
    private val paneWaiters = HashMap<Layer, ArrayList<() -> Unit>>()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            fittedLayer = null
            sync()
        }

        override fun onLowMemory() = Unit
    }

    /**
     * Remember open order before the window is visible. The pair is these
     * first two, not whichever hosts happen to be attached on the next layout.
     */
    fun noteOpened(layer: Layer) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { noteOpened(layer) }
            return
        }
        if (layer !in appearance) appearance.add(layer)
        if (coldInserted) return
        if (coldFirst == null) {
            if (coldStartExpectsPair) coldFirst = layer
        } else if (coldSecond == null && layer != coldFirst) {
            coldSecond = layer
        }
    }

    fun register(
        context: Context,
        layer: Layer,
        showing: () -> Boolean,
        host: () -> View?,
        apply: (Frame?) -> Unit,
    ) {
        slots[layer] = Slot(showing, host, apply)
        if (appContext == null) {
            val app = context.applicationContext
            appContext = app
            app.registerComponentCallbacks(configCallbacks)
        }
    }

    fun unregister(layer: Layer) {
        slots.remove(layer)
        appearance.remove(layer)
        shownOrder.remove(layer)
        pairMembers = pairMembers - layer
        covered = covered - layer
        placedOnce.remove(layer)
        saved.remove(layer)
        settled.remove(layer)
        exitingLayers.remove(layer)
        motions.remove(layer)?.cancel()
        hosts.remove(layer)
        hostParams.remove(layer)
        hostWm.remove(layer)
    }

    fun forget(layer: Layer) {
        saved.remove(layer)
    }

    /**
     * Leave the pair without writing the saved full-screen frame back.
     * The collapsed music button uses this so a pane restore cannot blow the
     * button back up to the full screen.
     */
    fun releasePane(layer: Layer, view: View?) {
        val move = pairMove
        if (move != null && (move.first == layer || move.second == layer)) {
            cancelPairMotion()
        }
        motions.remove(layer)?.cancel()
        settled.remove(layer)
        exitingLayers.remove(layer)
        hosts.remove(layer)
        hostParams.remove(layer)
        hostWm.remove(layer)
        saved.remove(layer)
        if (view != null) paneViews.remove(view)
    }

    /** A window currently occupying one half. Z-order must not restack it over its sibling. */
    fun isPaneView(view: View?): Boolean = view != null && view in paneViews

    fun holdsPanes(): Boolean = paneViews.isNotEmpty()

    /** True while cold start is still waiting to snap the pair before the fade. */
    fun isColdStartHolding(): Boolean = holdingReveal

    /**
     * Call before the cold-start restore shows anything. Windows stay transparent
     * until [finishColdStart] has snapped the saved pair, so the first paint is
     * already the remembered ratio.
     */
    fun beginColdStart(expectPair: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { beginColdStart(expectPair) }
            return
        }
        snapEnter = true
        coldStartAttempts = 0
        coldStartExpectsPair = expectPair
        coldFirst = null
        coldSecond = null
        coldInserted = !expectPair
        holdingReveal = true
    }

    /** Style store was applied before the first window. Do not read it again over that. */
    fun markSettingsReady() {
        settingsReady = true
    }

    /** Snap the pair, then let the held fades run. */
    fun finishColdStart() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishColdStart() }
            return
        }
        snapEnter = true
        coldStartAttempts = 0
        pumpColdStart()
    }

    /** Run [block] now, or after the cold-start snap if a restore is in progress. */
    fun runAfterColdStart(block: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { runAfterColdStart(block) }
            return
        }
        if (!holdingReveal) block() else pendingReveals.add(block)
    }

    /**
     * Cold start creates the second window at MATCH_PARENT, then fades it.
     * The pane is only written once the earlier window is attached, so the fade
     * has to wait for that write. Raising alpha before it paints the full screen.
     */
    fun fadeWhenPaneReady(layer: Layer, block: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { fadeWhenPaneReady(layer, block) }
            return
        }
        sync()
        if (!mustWaitForPane(layer)) {
            block()
            return
        }
        paneWaiters.getOrPut(layer) { ArrayList() }.add(block)
    }

    private fun mustWaitForPane(layer: Layer): Boolean {
        if (layer != coldSecond || coldInserted) return false
        val view = slots[layer]?.host?.invoke()
        return view == null || view !in paneViews
    }

    private fun flushPaneWaiters() {
        if (paneWaiters.isEmpty()) return
        val ready = paneWaiters.keys.filter { !mustWaitForPane(it) }
        for (layer in ready) {
            val blocks = paneWaiters.remove(layer) ?: continue
            for (block in blocks) {
                runCatching { block() }
            }
        }
    }

    private fun pumpColdStart() {
        coldStartAttempts += 1
        sync()
        val showingCount = slots.values.count { it.showing() }
        val settingsPending = !settingsReady && !SettingsStyleSession.overlaySplitHydrated
        val enabled = SettingsStyleSession.overlaySplitEnabled.value
        // The second window is started after the browser. Releasing here paints
        // the first one fullscreen, then the pair shrinks into place.
        val pairHeld = paneViews.size >= 2 && (coldSecond == null || coldInserted)
        // The second overlay is recorded and still waiting to insert.
        // Releasing here is what fades it in at MATCH_PARENT.
        val pairPending = coldStartExpectsPair && !pairHeld &&
            (coldSecond == null || !coldInserted)
        val limit = if (coldStartExpectsPair) 80 else 12
        val waiting = holdingReveal &&
            coldStartAttempts < limit &&
            (settingsPending ||
                pairPending ||
                (enabled && showingCount >= 2 && !pairHeld))
        if (waiting) {
            mainHandler.postDelayed({ pumpColdStart() }, 80)
            return
        }
        // A pair that lands after this pump must still snap. Clearing the snap
        // here made the late placement play the squeeze, which is the fullscreen flash.
        if (pairHeld || !coldStartExpectsPair) snapEnter = false
        coldStartExpectsPair = false
        holdingReveal = false
        val pending = pendingReveals.toList()
        pendingReveals.clear()
        for (reveal in pending) {
            runCatching { reveal() }
        }
    }

    /**
     * Browser sidebar home / settings. Hide the other half so it does not expand
     * over the screen being opened. The overlay-split switch stays as the user left it.
     */
    fun closeForBrowserNavigation(context: Context) {
        if (!SettingsStyleSession.overlaySplitEnabled.value && !holdsPanes()) return
        val app = context.applicationContext
        if (ScreensaverService.isOverlayShowing()) ScreensaverService.setVisible(app, false)
        if (DreamClockService.isOverlayShowing()) DreamClockService.setVisible(app, false)
        if (WeatherOverlayService.isOverlayShowing()) WeatherOverlayService.setVisible(app, false)
        if (QuickEntityOverlayService.isOverlayShowing()) QuickEntityOverlayService.hide(app)
        if (VoiceMessageOverlayService.isOverlayShowing()) VoiceMessageOverlayService.setVisible(app, false)
        VinylCoverService.collapseExpandedForSplitNavigation()
        sync()
    }

    fun sync() {
        if (appContext == null || slots.isEmpty()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sync() }
            return
        }
        if (syncing) {
            syncQueued = true
            return
        }
        syncing = true
        try {
            var spins = 0
            do {
                syncQueued = false
                layout()
                spins += 1
            } while (syncQueued && spins < 3)
        } finally {
            syncing = false
        }
    }

    private fun layout() {
        ensureSettings()
        // HashMap key order is not open order. A layer that never called
        // noteOpened is appended after the ones that did.
        for (layer in Layer.entries) {
            if (slots[layer]?.showing() == true && layer !in appearance) appearance.add(layer)
        }
        val enabled = SettingsStyleSession.overlaySplitEnabled.value ||
            (holdingReveal && coldStartExpectsPair) ||
            (coldSecond != null && !coldInserted)
        val openShowing = appearance.filter { slots[it]?.showing() == true }
        val seated = seatPair(openShowing)
        shownOrder.clear()
        shownOrder.addAll(seated)
        val active = seated
        if (enabled && active.size >= 2 && !splitSizeReady() && lastBox == null) {
            val (w, h) = layerSize(anchorView())
            // Unmeasured MATCH_PARENT used to bail, so the first fade painted the
            // full screen and the pair only appeared afterwards. Display size is
            // enough to cut the panes before that paint.
            if (w <= 1 || h <= 1) {
                if (!pendingSplitMeasure) {
                    pendingSplitMeasure = true
                    mainHandler.post {
                        pendingSplitMeasure = false
                        sync()
                    }
                }
                return
            }
        }
        val frames = if (enabled && active.size >= 2) computeFrames() else null
        if (frames != null) lastBox = layoutBox(anchorView())
        val box = lastBox
        if (pairMotion?.isRunning == true) {
            val hold = pairMove
            val holdOk = hold != null && livePrimaryPx == null && enabled && if (hold.exiting) {
                slots[hold.first]?.showing() != true && slots[hold.second]?.showing() == true
            } else {
                slots[hold.first]?.showing() == true && slots[hold.second]?.showing() == true
            }
            if (holdOk) return
            cancelPairMotion()
        }
        val primary = if (frames != null) active[0] else null
        val secondary = if (frames != null) active[1] else null
        if (coldFirst != null && coldSecond != null && !coldInserted && active.size >= 2) {
            snapEnter = true
        }
        val enterMove = if (
            primary != null && secondary != null && frames != null && box != null &&
            !snapEnter && livePrimaryPx == null
        ) {
            buildEnterMove(primary, secondary, frames, box)
        } else {
            null
        }
        val exitMove = if (enterMove == null && box != null && livePrimaryPx == null && !snapEnter) {
            buildExitMove(box)
        } else {
            null
        }
        if (exitMove != null && startPairMove(exitMove)) {
            settled.remove(exitMove.first)
        }
        scriptedStart = if (exitMove == null) {
            enterMove?.let { mapOf(it.first to it.firstStart, it.second to it.secondStart) }
        } else {
            null
        }
        val exitRunning = exitingLayers.isNotEmpty()
        for (layer in Layer.entries) {
            val slot = slots[layer] ?: continue
            if (slot.showing()) {
                val frame = when (layer) {
                    primary -> frames?.first
                    secondary -> frames?.second
                    else -> null
                }
                if (frame == null && saved[layer] == null) continue
                // Partner is sliding out. Keep this pane until that motion ends.
                if (frame == null && saved[layer] != null && exitRunning) continue
                slot.apply(frame)
            } else if (saved[layer] != null) {
                slot.apply(null)
            }
        }
        scriptedStart = null
        if (enterMove != null) startPairMove(enterMove)
        val moving = enterMove != null || exitMove != null || pairMotion?.isRunning == true
        placeDivider(if (moving) null else frames)
        if (raiseChromeAfterSplit) {
            raiseChromeAfterSplit = false
            raiseDividerAbovePanes()
            OverlayZOrderCoordinator.noteWindowAdded()
        }
        if (snapEnter && frames != null && paneViews.size >= 2) snapEnter = false
        if (
            !coldInserted && coldFirst != null && coldSecond != null &&
            coldFirst in active && coldSecond in active
        ) {
            coldInserted = true
        }
        val waitingForSeat = (coldSecond != null && !coldInserted) ||
            (pairMembers.isEmpty() &&
                active.size < 2 &&
                openShowing.any { !hostAttached(it) })
        if (!waitingForSeat) {
            pairMembers = active.toSet()
            val attachedShowing = openShowing.filter { hostAttached(it) }
            covered = covered.filter { slots[it]?.showing() == true }.toSet() +
                attachedShowing.filter { it !in active }
        }
        previouslyShown = if (waitingForSeat) previouslyShown else active.toSet()
        flushPaneWaiters()
    }

    /**
     * Seats are the first two opened layers. A window that is already fullscreen
     * on top does not drop into a seat when one side closes. A layer that opens
     * later does. An earlier seat that has not attached yet is not given away.
     */
    private fun seatPair(openShowing: List<Layer>): List<Layer> {
        val first = coldFirst
        val second = coldSecond
        if (first != null && second != null && !coldInserted) {
            // Recorded pair only. Wait until both windows exist, then insert.
            // Do not hand the second seat to whichever host attached first.
            if (hostAttached(first) && hostAttached(second)) return listOf(first, second)
            return emptyList()
        }
        if (pairMembers.isEmpty() && covered.isEmpty()) {
            val picked = ArrayList<Layer>(2)
            for (layer in openShowing) {
                if (picked.size == 2) break
                if (!hostAttached(layer)) break
                picked.add(layer)
            }
            return picked
        }
        val still = pairMembers.filter { it in openShowing && hostAttached(it) }
        val arrived = openShowing.filter {
            it !in pairMembers && it !in covered && hostAttached(it)
        }
        val members = when {
            still.size >= 2 -> still.take(2)
            still.size == 1 && arrived.isNotEmpty() -> listOf(still[0], arrived.first())
            else -> still
        }
        return members.sortedBy { appearance.indexOf(it) }
    }

    /**
     * [frame] null restores the window's own full-screen params.
     * No-op when the view is already gone.
     */
    fun applyTo(
        layer: Layer,
        windowManager: WindowManager?,
        view: View?,
        params: WindowManager.LayoutParams?,
        frame: Frame?,
    ) {
        if (windowManager == null || view == null || params == null || !view.isAttachedToWindow) {
            if (frame == null && view != null) paneViews.remove(view)
            return
        }
        if (frame == null) {
            val running = motions[layer]
            if (running?.isRunning == true && (running === pairMotion || view.visibility == View.VISIBLE)) {
                return
            }
            motions.remove(layer)?.cancel()
            settled.remove(layer)
            exitingLayers.remove(layer)
            hosts.remove(layer)
            hostParams.remove(layer)
            hostWm.remove(layer)
            paneViews.remove(view)
            val previous = saved.remove(layer) ?: return
            params.width = previous.width
            params.height = previous.height
            params.x = previous.x
            params.y = previous.y
            params.gravity = previous.gravity
            params.horizontalMargin = previous.horizontalMargin
            params.verticalMargin = previous.verticalMargin
        } else {
            if (saved[layer] == null) {
                saved[layer] = Snapshot(
                    params.width,
                    params.height,
                    params.x,
                    params.y,
                    params.gravity,
                    params.horizontalMargin,
                    params.verticalMargin,
                )
            }
            val gravity = Gravity.TOP or Gravity.START
            paneViews.add(view)
            hosts[layer] = view
            hostParams[layer] = params
            hostWm[layer] = windowManager
            if (motions[layer]?.isRunning == true && settled[layer] == frame) return
            val scripted = scriptedStart?.get(layer)
            params.gravity = gravity
            params.horizontalMargin = 0f
            params.verticalMargin = 0f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (scripted != null) {
                params.x = scripted.x
                params.y = scripted.y
                params.width = scripted.width
                params.height = scripted.height
                settled[layer] = frame
                placedOnce.add(layer)
            } else {
                val already = settled[layer] == frame &&
                    params.width == frame.width &&
                    params.height == frame.height &&
                    params.x == frame.x &&
                    params.y == frame.y &&
                    params.gravity == gravity
                if (already) {
                    val measured = view.width > 1 && view.height > 1
                    val mismatch = measured && (
                        kotlin.math.abs(view.width - frame.width) > 2 ||
                            kotlin.math.abs(view.height - frame.height) > 2
                        )
                    if (!mismatch) {
                        if (!measured) view.requestLayout()
                        return
                    }
                }
                if (pairMove?.first == layer || pairMove?.second == layer) cancelPairMotion()
                params.x = frame.x
                params.y = frame.y
                params.width = frame.width
                params.height = frame.height
                settled[layer] = frame
                placedOnce.add(layer)
            }
        }
        try {
            windowManager.updateViewLayout(view, params)
            view.requestLayout()
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) view.getChildAt(i).requestLayout()
            }
        } catch (e: Exception) {
            Log.w(TAG, "update $layer failed", e)
        }
    }

    /**
     * Both panes share one soft curve. A window already on screen squeezes from
     * where it is; a window that is not visible yet comes in from its outer edge,
     * glued to the same seam. Leaving reverses that: the seam travels out and the
     * one that stays grows to fill.
     */
    private fun buildEnterMove(
        primary: Layer,
        secondary: Layer,
        frames: Pair<Frame, Frame>,
        box: Box,
    ): PairMove? {
        if (settled[primary] != null && settled[secondary] != null) return null
        // Reopening a layer that already had a side snaps back there. A fresh
        // arrival is the only one that pushes in.
        if (placedOnce.contains(primary) && placedOnce.contains(secondary)) return null
        val endA = frames.first
        val endB = frames.second
        val side = box.width >= box.height
        val full = Frame(box.x, box.y, box.width, box.height)
        val primaryWasUp = primary in previouslyShown
        val secondaryWasUp = secondary in previouslyShown
        val aResident = looksResident(primary, endA)
        val bResident = looksResident(secondary, endB)
        val startA: Frame
        val startB: Frame
        when {
            // The one already on screen keeps the screen. The new one inserts
            // from its outer edge. An opaque newcomer is not a second resident.
            primaryWasUp && !secondaryWasUp -> {
                startA = residentStart(primary, full)
                startB = outside(endB, box, side, farEdge = true)
            }
            secondaryWasUp && !primaryWasUp -> {
                startB = residentStart(secondary, full)
                startA = outside(endA, box, side, farEdge = false)
            }
            primaryWasUp && secondaryWasUp -> {
                startA = if (aResident) full else settled[primary] ?: outside(endA, box, side, farEdge = false)
                startB = if (bResident) full else settled[secondary] ?: outside(endB, box, side, farEdge = true)
            }
            else -> {
                startA = outside(endA, box, side, farEdge = false)
                startB = outside(endB, box, side, farEdge = true)
            }
        }
        if (startA == endA && startB == endB) return null
        // Starting from the full screen is what paints the fullscreen frame.
        // Snap to the panes instead of holding that size for the animation.
        if (startA == full || startB == full) return null
        return PairMove(primary, secondary, startA, startB, endA, endB, exiting = false)
    }

    private fun buildExitMove(box: Box): PairMove? {
        val leaving = ArrayList<Layer>(1)
        val staying = ArrayList<Layer>(1)
        for (layer in settled.keys) {
            if (saved[layer] == null) continue
            if (slots[layer]?.showing() == true) staying.add(layer) else leaving.add(layer)
        }
        if (leaving.size != 1 || staying.size != 1) return null
        val leaver = leaving[0]
        val stayer = staying[0]
        // The expanded player and the round button are the same window. Sliding
        // the leaver off screen would throw the button away.
        if (leaver == Layer.MEDIA_PLAYER) return null
        val fromLeave = liveFrame(leaver) ?: return null
        val fromStay = liveFrame(stayer) ?: return null
        val leaverView = hosts[leaver] ?: return null
        if (!leaverView.isAttachedToWindow || leaverView.visibility != View.VISIBLE) return null
        if (hosts[stayer]?.isAttachedToWindow != true) return null
        val side = box.width >= box.height
        val nearEdge = if (side) fromLeave.x <= fromStay.x else fromLeave.y <= fromStay.y
        val endStay = Frame(box.x, box.y, box.width, box.height)
        val endLeave = if (side) {
            if (nearEdge) {
                Frame(box.x - fromLeave.width, fromLeave.y, fromLeave.width, fromLeave.height)
            } else {
                Frame(box.x + box.width, fromLeave.y, fromLeave.width, fromLeave.height)
            }
        } else if (nearEdge) {
            Frame(fromLeave.x, box.y - fromLeave.height, fromLeave.width, fromLeave.height)
        } else {
            Frame(fromLeave.x, box.y + box.height, fromLeave.width, fromLeave.height)
        }
        return PairMove(leaver, stayer, fromLeave, fromStay, endLeave, endStay, exiting = true)
    }

    /** A solo window starts from the full screen, not from a stale half pane. */
    private fun residentStart(layer: Layer, full: Frame): Frame {
        val live = liveFrame(layer) ?: return full
        if (live.width >= full.width - 2 && live.height >= full.height - 2) return full
        if (settled[layer] != null) return live
        return full
    }

    private fun looksResident(layer: Layer, end: Frame): Boolean {
        val view = slots[layer]?.host?.invoke() ?: return false
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || view.alpha <= 0.5f) return false
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return false
        if (lp.width < 0 || lp.height < 0) return true
        return lp.width > end.width + 2 || lp.height > end.height + 2
    }

    private fun liveFrame(layer: Layer): Frame? {
        val params = hostParams[layer]
        if (params != null && params.width > 0 && params.height > 0) {
            return Frame(params.x, params.y, params.width, params.height)
        }
        return settled[layer]
    }

    /** [farEdge] is the right side in landscape and the bottom side in portrait. */
    private fun outside(end: Frame, box: Box, sideBySide: Boolean, farEdge: Boolean): Frame {
        return if (sideBySide) {
            val x = if (farEdge) box.x + box.width else box.x - end.width
            Frame(x, end.y, end.width, end.height)
        } else {
            val y = if (farEdge) box.y + box.height else box.y - end.height
            Frame(end.x, y, end.width, end.height)
        }
    }

    private class PaneEndpoint(
        val view: View,
        val params: WindowManager.LayoutParams,
        val wm: WindowManager,
    )

    private fun endpoint(layer: Layer): PaneEndpoint? {
        val view = hosts[layer] ?: return null
        val params = hostParams[layer] ?: return null
        val wm = hostWm[layer] ?: return null
        if (!view.isAttachedToWindow) return null
        return PaneEndpoint(view, params, wm)
    }

    private fun cancelPairMotion() {
        val running = pairMotion ?: return
        pairMotion = null
        pairMove = null
        running.cancel()
    }

    private fun startPairMove(move: PairMove): Boolean {
        val first = endpoint(move.first) ?: return false
        val second = endpoint(move.second) ?: return false
        cancelPairMotion()
        // Freeze child measure while the window rect moves. A WebView or Compose
        // tree laid out on every frame is what makes the seam stutter.
        holdContentLayout(first.view, true)
        holdContentLayout(second.view, true)
        val animator = ValueAnimator.ofFloat(0f, 1f)
        // Exit has to finish with the layer's own fade. A longer slide keeps
        // moving after that fade removes the window, and the one that stays
        // is left at a half width.
        animator.duration = if (move.exiting) 240L else 560L
        // Ease in and out. The old curve spent most of the distance in the
        // first moments, so a single dropped frame read as a hard jump.
        animator.interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        var cancelled = false
        animator.addUpdateListener { value ->
            val t = value.animatedValue as Float
            val firstOk = writeFrame(first, move.firstStart, move.firstEnd, t)
            val secondOk = writeFrame(second, move.secondStart, move.secondEnd, t)
            // The leaver fades and may detach. That must not freeze the stayer.
            if (move.exiting) {
                if (!secondOk) animator.cancel()
            } else if (!firstOk || !secondOk) {
                animator.cancel()
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (move.exiting) {
                    writeFrame(second, move.secondEnd, move.secondEnd, 1f)
                    if (!cancelled) writeFrame(first, move.firstEnd, move.firstEnd, 1f)
                } else if (!cancelled) {
                    writeFrame(first, move.firstEnd, move.firstEnd, 1f)
                    writeFrame(second, move.secondEnd, move.secondEnd, 1f)
                }
                releasePair(move, animator)
                if (!cancelled || move.exiting) mainHandler.post { sync() }
            }
        })
        motions[move.first] = animator
        motions[move.second] = animator
        pairMotion = animator
        pairMove = move
        if (move.exiting) exitingLayers.add(move.first)
        animator.start()
        return true
    }

    private fun releasePair(move: PairMove, animator: ValueAnimator) {
        if (motions[move.first] === animator) motions.remove(move.first)
        if (motions[move.second] === animator) motions.remove(move.second)
        if (pairMotion === animator) {
            pairMotion = null
            pairMove = null
        }
        exitingLayers.clear()
        if (move.exiting) hosts[move.first]?.let { paneViews.remove(it) }
        holdContentLayout(hosts[move.first], false)
        holdContentLayout(hosts[move.second], false)
    }

    /** API 29+. Older releases still resize, they just also remeasure children. */
    private fun holdContentLayout(view: View?, hold: Boolean) {
        if (Build.VERSION.SDK_INT < 29 || view !is android.view.ViewGroup) return
        view.suppressLayout(hold)
        if (!hold) view.requestLayout()
    }

    private fun writeFrame(pane: PaneEndpoint, start: Frame, end: Frame, t: Float): Boolean {
        if (!pane.view.isAttachedToWindow) return false
        pane.params.x = (start.x + (end.x - start.x) * t).roundToInt()
        pane.params.y = (start.y + (end.y - start.y) * t).roundToInt()
        pane.params.width = (start.width + (end.width - start.width) * t).roundToInt().coerceAtLeast(1)
        pane.params.height = (start.height + (end.height - start.height) * t).roundToInt().coerceAtLeast(1)
        return runCatching { pane.wm.updateViewLayout(pane.view, pane.params) }.isSuccess
    }

    private fun raiseDividerAbovePanes() {
        val bar = dividerView ?: return
        val params = dividerParams ?: return
        val wm = dividerWm ?: return
        if (!bar.isAttachedToWindow) return
        runCatching {
            wm.removeView(bar)
            wm.addView(bar, params)
        }
    }

    /** Attached, including GONE. Cold start writes the pane before the fade. */
    private fun hostAttached(layer: Layer): Boolean {
        val view = slots[layer]?.host?.invoke() ?: return false
        return view.isAttachedToWindow
    }

    private fun anchorView(): View? {
        for (layer in shownOrder) {
            val view = slots[layer]?.host?.invoke() ?: continue
            if (view.isAttachedToWindow) return view
        }
        return null
    }

    /** A fullscreen window that has not been measured yet must not lock a guessed frame. */
    private fun splitSizeReady(): Boolean {
        val anchor = anchorView() ?: return false
        val lp = anchor.layoutParams as? WindowManager.LayoutParams
        val waitingForFirstLayout = lp != null &&
            lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT &&
            anchor.width <= 1
        if (waitingForFirstLayout) return false
        val (w, h) = layerSize(anchor)
        return w > 1 && h > 1
    }

    /**
     * Cold start used to lay out before the style store reached the session, so the
     * switch looked off and the saved left/right ratio was replaced by 5:5.
     * Read the store once before the first placement. A toggle that already wrote
     * the session is left as-is.
     */
    private fun ensureSettings() {
        if (settingsReady || SettingsStyleSession.overlaySplitHydrated) {
            settingsReady = true
            return
        }
        val app = appContext ?: return
        if (settingsLoadStarted) return
        settingsLoadStarted = true
        // DataStore is collected on Main.immediate. runBlocking(data.first())
        // from that same thread deadlocks: the collector is inside sync(), and
        // the store cannot emit until the collector returns.
        persistScope.launch {
            val settings = app.settingsStyleSettingsStore.data.first()
            mainHandler.post {
                if (!SettingsStyleSession.overlaySplitHydrated) {
                    SettingsStyleSession.syncFromSettings(settings)
                }
                settingsReady = true
                sync()
            }
        }
    }

    /**
     * Both panes are cut from this one size. [Display.getRealSize] is the
     * coordinate space the overlay windows actually use, in the current rotation.
     * [applicationContext] configuration often stays portrait, which pushed the
     * right pane off the landscape screen.
     */
    private fun layerSize(anchor: View?): Pair<Int, Int> {
        val display = anchor?.display?.let { display ->
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            if (point.x > 1 && point.y > 1) point.x to point.y else null
        }
        val measured = anchor?.let { view ->
            val lp = view.layoutParams as? WindowManager.LayoutParams
            if (
                lp?.width == WindowManager.LayoutParams.MATCH_PARENT &&
                lp.height == WindowManager.LayoutParams.MATCH_PARENT &&
                view.width > 1 &&
                view.height > 1
            ) {
                view.width to view.height
            } else {
                null
            }
        }
        if (measured != null && measuredFillsDisplay(measured, display)) {
            fittedLayer = measured
            return measured
        }
        val fitted = fittedLayer
        if (
            fitted != null &&
            (display == null || (fitted.first >= fitted.second) == (display.first >= display.second))
        ) {
            return fitted
        }
        if (display != null) return display
        val context = appContext
        val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            if (bounds.width() > 1 && bounds.height() > 1) {
                return bounds.width() to bounds.height()
            }
        }
        if (wm != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 1 && metrics.heightPixels > 1) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val metrics = context?.resources?.displayMetrics
        return (metrics?.widthPixels ?: 0) to (metrics?.heightPixels ?: 0)
    }

    /**
     * Edge to edge in the same rectangle a fullscreen overlay already fills.
     * The visible-frame and [Display.getMetrics] boxes stop short of the
     * bottom and the right, which left a gap there.
     */
    private fun layoutBox(anchor: View?): Box {
        val (w, h) = layerSize(anchor)
        if (!isLandscapeNow(anchor)) return Box(0, 0, w, h)
        val screen = rotationAwareSize(anchor)
        val measured = fullscreenMeasured(anchor)?.takeIf { measuredFillsDisplay(it, screen) }
            ?: fittedLayer?.takeIf { it.first >= it.second && measuredFillsDisplay(it, screen) }
        if (measured != null) return Box(0, 0, measured.first, measured.second)
        val aware = rotationAwareSize(anchor)
        if (aware != null && aware.first >= aware.second) return Box(0, 0, aware.first, aware.second)
        if (w >= h) return Box(0, 0, w, h)
        return Box(0, 0, h, w)
    }

    /**
     * A window that was just brought back from a pane or a small shell can still
     * report that old size for a frame after its params are MATCH_PARENT. That
     * size is not the screen, and saving it made every later open miss the pair.
     */
    private fun measuredFillsDisplay(measured: Pair<Int, Int>, display: Pair<Int, Int>?): Boolean {
        val screen = display ?: return true
        return measured.first >= (screen.first * 0.8f).toInt() &&
            measured.second >= (screen.second * 0.8f).toInt()
    }

    private fun fullscreenMeasured(anchor: View?): Pair<Int, Int>? {
        val view = anchor ?: return null
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return null
        if (
            lp.width != WindowManager.LayoutParams.MATCH_PARENT ||
            lp.height != WindowManager.LayoutParams.MATCH_PARENT ||
            view.width <= 1 ||
            view.height <= 1
        ) {
            return null
        }
        return view.width to view.height
    }

    private fun isLandscapeNow(anchor: View?): Boolean {
        val rotation = anchor?.display?.rotation
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) return true
        val aware = rotationAwareSize(anchor) ?: return false
        return aware.first >= aware.second
    }

    private fun rotationAwareSize(anchor: View?): Pair<Int, Int>? {
        val display = anchor?.display ?: return null
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        if (point.x <= 1 || point.y <= 1) return null
        var width = point.x
        var height = point.y
        val sideways = display.rotation == Surface.ROTATION_90 ||
            display.rotation == Surface.ROTATION_270
        if (sideways && width < height) {
            val swap = width
            width = height
            height = swap
        }
        return width to height
    }

    private fun computeFrames(): Pair<Frame, Frame>? {
        val box = layoutBox(anchorView())
        if (box.width < 2 || box.height < 2) return null
        val sideBySide = box.width >= box.height
        val span = if (sideBySide) box.width else box.height
        val dragged = livePrimaryPx
        val primary = if (dragged != null) {
            val min = (span * 0.15f).toInt().coerceAtLeast(1)
            dragged.coerceIn(min, span - min)
        } else {
            val left = SettingsStyleSession.overlaySplitRatioLeft.value.coerceIn(1, 9)
            val right = SettingsStyleSession.overlaySplitRatioRight.value.coerceIn(1, 9)
            (span * left / (left + right)).coerceIn(1, span - 1)
        }
        val secondary = span - primary
        return if (sideBySide) {
            Frame(box.x, box.y, primary, box.height) to
                Frame(box.x + primary, box.y, secondary, box.height)
        } else {
            Frame(box.x, box.y, box.width, primary) to
                Frame(box.x, box.y + primary, box.width, secondary)
        }
    }

    private fun placeDivider(frames: Pair<Frame, Frame>?) {
        val context = anchorView()?.context ?: appContext
        val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val view = dividerView
        if (frames == null || context == null || wm == null) {
            val owner = dividerWm ?: wm
            if (view != null && view.isAttachedToWindow && owner != null) {
                runCatching { owner.removeView(view) }
            }
            dividerView = null
            dividerWm = null
            return
        }
        val box = layoutBox(anchorView())
        val sideBySide = box.width >= box.height
        val density = context.resources.displayMetrics.density
        val thickness = (36f * density).toInt().coerceAtLeast(1)
        val grip = (112f * density).toInt().coerceAtLeast(thickness)
        val seam = if (sideBySide) frames.first.width else frames.first.height
        val params = (dividerParams ?: WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            OverlayOrientation.apply(this)
        }).also { dividerParams = it }
        if (sideBySide) {
            params.width = thickness
            params.height = grip.coerceAtMost(box.height)
            params.x = (box.x + seam - thickness / 2).coerceAtLeast(0)
            params.y = box.y + (box.height - params.height) / 2
        } else {
            params.width = grip.coerceAtMost(box.width)
            params.height = thickness
            params.x = box.x + (box.width - params.width) / 2
            params.y = (box.y + seam - thickness / 2).coerceAtLeast(0)
        }
        val bar = view ?: DividerView(context).also { dividerView = it }
        if (bar.isAttachedToWindow) {
            runCatching { (dividerWm ?: wm).updateViewLayout(bar, params) }
        } else {
            runCatching {
                wm.addView(bar, params)
                dividerWm = wm
            }
            // Do not raise every interactive window here. That walk calls back
            // into sync() and the main thread stops taking touches.
            OverlayZOrderCoordinator.noteWindowAdded()
        }
    }

    private fun onDividerDrag(raw: Float, commit: Boolean) {
        val box = layoutBox(anchorView())
        if (box.width < 2 || box.height < 2) return
        val sideBySide = box.width >= box.height
        val span = if (sideBySide) box.width else box.height
        val origin = if (sideBySide) box.x else box.y
        val min = (span * 0.15f).toInt().coerceAtLeast(1)
        livePrimaryPx = (raw - origin).toInt().coerceIn(min, span - min)
        sync()
        if (!commit) return
        val fraction = livePrimaryPx!! / span.toFloat()
        val left = (fraction * 10f).roundToInt().coerceIn(1, 9)
        val right = (10 - left).coerceIn(1, 9)
        livePrimaryPx = null
        SettingsStyleSession.setOverlaySplitRatio(left, right)
        val app = appContext
        if (app != null) {
            persistScope.launch {
                com.example.ava.settings.SettingsStyleSettingsStore(app.settingsStyleSettingsStore)
                    .setOverlaySplitRatio(left, right)
            }
        }
        sync()
    }

    private class DividerView(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
        }

        init {
            setOnTouchListener { _, event ->
                val sideBySide = layoutBox(anchorView()).let { it.width >= it.height }
                val raw = if (sideBySide) event.rawX else event.rawY
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dividerHot = true
                        invalidate()
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        dividerHot = true
                        onDividerDrag(raw, commit = false)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        onDividerDrag(raw, commit = true)
                        dividerHot = false
                        invalidate()
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        livePrimaryPx = null
                        dividerHot = false
                        invalidate()
                        sync()
                        true
                    }
                    else -> false
                }
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (!dividerHot) return
            val sideBySide = layoutBox(anchorView()).let { it.width >= it.height }
            val density = resources.displayMetrics.density
            val stroke = 3f * density
            val radius = 2f * density
            if (sideBySide) {
                val x = width / 2f
                canvas.drawRoundRect(
                    x - stroke / 2f,
                    height * 0.18f,
                    x + stroke / 2f,
                    height * 0.82f,
                    radius,
                    radius,
                    paint,
                )
            } else {
                val y = height / 2f
                canvas.drawRoundRect(
                    width * 0.18f,
                    y - stroke / 2f,
                    width * 0.82f,
                    y + stroke / 2f,
                    radius,
                    radius,
                    paint,
                )
            }
        }
    }
}
