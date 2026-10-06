package com.example.ava.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.ui.applyQuickEntityOverlayViewSetup
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenBlankOverlay
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wins the BLE scanner back from ROMs that power it down during long screen-off stretches, by
 * briefly making the platform interactive again behind an opaque black overlay.
 *
 * **Asked for, never scheduled.** An earlier version pulsed on a timer, twenty minutes into
 * every screen-off. That only works if the suppression arrives on a predictable schedule, and
 * on the device this was built for it does not — it has been observed biting anywhere from
 * under half an hour to nearly an hour in. A clock tuned for one of those is wrong for the
 * other in both directions: too early and it lights the panel all night for nothing, too late
 * and the departure it was meant to prevent has already been published.
 *
 * So the presence layer asks instead, at the one moment the answer matters: after a tracked
 * device has been silent long enough to be declared away. Silence at that point means either
 * the device left or the receiver went deaf, and those are indistinguishable from the outside
 * — but a pulse tells them apart. If the device reappears while the scanner is back, it never
 * left. If it stays silent through a working scan, it is genuinely gone. Timing the suppression
 * stops mattering because nothing has to predict it.
 *
 * Three properties are load-bearing.
 *
 * **The wake has to move `PowerManager` wakefulness, not the panel.** Setting the display power
 * mode through Shizuku (what [ScreenControlUtils.setScreenOn] does when Shizuku is granted)
 * lights the panel without ever making the platform interactive, which is the same split
 * `VoiceSatelliteBluetooth.isProxyScanScreenOn` guards against: the scan would stay filtered
 * and the suppression would stay in force, so the pulse would cost light and buy nothing.
 * Hence a wakelock plus KEYCODE_WAKEUP, and then [awaitPulseEffective] to confirm both halves
 * of that condition — an unconfirmed pulse is abandoned rather than held.
 *
 * **Darkness is a window attribute, never a setting.** `WindowManager.LayoutParams`
 * `screenBrightness` applies only while this window is up and is reverted by the window manager
 * when it goes away. Writing `Settings.System.SCREEN_BRIGHTNESS` instead would mean a restore
 * that has to survive process death mid-pulse, a user who adjusts brightness while it is
 * clamped, and overlapping pulses whose saved value is already the clamped one — a panel that
 * comes up dim the next morning with nothing left to say what it should have been. Here there
 * is no saved state to lose: if this process dies during a pulse the overlay dies with it and
 * the display returns to the user's own brightness.
 *
 * **A pulse may only start if it can be undone.** Every precondition for putting the display
 * back to sleep is checked before anything is woken, because the failure mode of waking a panel
 * that cannot be re-slept is a lit screen nobody asked for — worse than the missing presence
 * this exists to fix.
 */
object DarkWakePulse {
    private const val TAG = "DarkWakePulse"

    /**
     * How long the platform stays interactive per pulse, and the one constant here that is not
     * free to choose.
     *
     * Becoming interactive is enough on its own to clear the vendor suppression, but it is not
     * enough to get the scan back off its screen-off filters, and those filters are the second
     * half of the problem: they match on identity addresses, so an iPhone that rotated its
     * random address while we were blind stays unmatched until an unfiltered pass re-resolves
     * it. `VoiceSatelliteBluetooth` deliberately does not rebuild the scan on the screen-on
     * event — a forced rebuild there is what flashed the MediaTek adapter and dropped the Home
     * Assistant socket — so the switch to unfiltered comes from its maintenance loop, which
     * ticks every 30s. A hold shorter than one tick would leave the strategy switch to luck:
     * the 17s between display-on and republished presence measured on this device was a
     * favourable tick phase, not a bound.
     *
     * So: one full maintenance tick, plus room for the rebuild and the first advert after it.
     */
    private const val HOLD_MS = 55_000L

    /** Grace for the wake to take effect before the pulse is written off as ineffective. */
    private const val EFFECTIVE_TIMEOUT_MS = 3_000L
    private const val EFFECTIVE_POLL_MS = 100L

    /** Covers the scan rebuild on the way back down, which the hold does not. */
    private const val SLEEP_SETTLE_MS = 15_000L

    /**
     * The whole pulse, wake to settled sleep. Doubles as the window away decisions are held
     * across, so a caller that waits [SETTLE_MS] is guaranteed to be looking at evidence
     * gathered by a scanner that had already been handed back.
     */
    private const val PULSE_WINDOW_MS = EFFECTIVE_TIMEOUT_MS + HOLD_MS + SLEEP_SETTLE_MS

    /**
     * How long a caller must wait after [requestPulse] before its silence means anything.
     * Public because the away pipeline's timing is only correct if it matches this exactly.
     */
    const val SETTLE_MS = PULSE_WINDOW_MS

    /**
     * Floor between pulses regardless of who asks. Callers rate-limit themselves per device,
     * but several tracked devices going quiet together would otherwise queue up a pulse each
     * for one shared radio outage.
     */
    private const val MIN_PULSE_GAP_MS = 3 * 60_000L

    /**
     * A negative sleep-capability result is worth remembering — it turns every future request
     * into an instant no, instead of making the away pipeline wait a full [SETTLE_MS] for a
     * pulse that cannot run. Not remembered forever, so granting Shizuku later takes effect.
     */
    private const val CAPABILITY_TTL_MS = 30 * 60_000L

    /**
     * Past this a pulse is considered wedged and torn down. Belt and braces: teardown does not
     * depend on a caller's scope, so reaching this should be impossible — but a stuck [pulsing]
     * flag would silently disable the feature for the rest of the process, which is too quiet a
     * way to fail.
     */
    private const val PULSE_DEADLINE_MS = 3 * PULSE_WINDOW_MS

    /**
     * Deliberately not the caller's scope. Teardown runs from a `finally`, and a scope that has
     * been cancelled — exactly what happens when the satellite stops mid-pulse — silently drops
     * whatever is launched into it, which would leave the black overlay on screen and the
     * wakelock held.
     */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pulseJob: Job? = null

    private var appContext: Context? = null
    private var pulseAllowed: (() -> Boolean)? = null
    private var suppressAway: ((Long) -> Unit)? = null

    /** Main thread only. */
    private var overlayView: View? = null

    @Volatile
    private var overlayAttached = false

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var pulsing = false

    @Volatile
    private var pulseStartedAt = 0L

    @Volatile
    private var lastPulseEndedAt = 0L

    /** Optimistic until a pulse proves otherwise; see [CAPABILITY_TTL_MS]. */
    @Volatile
    private var sleepCapable = true

    @Volatile
    private var sleepCapableCheckedAt = 0L

    /**
     * Set by [onUserTouch] and cleared when the next pulse starts. While true the screen belongs
     * to whoever touched it: the hold ends early and, crucially, the scheduled sleep is skipped
     * rather than firing in their face.
     */
    @Volatile
    private var userTookOver = false

    /** Whether this pulse actually made the platform interactive, and so owes it a sleep. */
    @Volatile
    private var wokeTheScreen = false

    @Volatile
    private var loggedOverlayDenied = false

    /**
     * Arms the pulse. Nothing runs until someone calls [requestPulse].
     *
     * @param pulseAllowed Whether presence tracking currently wants this at all — the user's
     * switch, essentially. Read per request so toggling it takes effect immediately.
     * @param suppressAway Hands the pulse window to the presence layer so the two scan-strategy
     * handovers it causes cannot themselves be read as a departure. Both injected so this stays
     * a display mechanism with no opinion about Bluetooth.
     */
    fun start(
        context: Context,
        pulseAllowed: () -> Boolean,
        suppressAway: (Long) -> Unit,
    ) {
        appContext = context.applicationContext
        this.pulseAllowed = pulseAllowed
        this.suppressAway = suppressAway
        Log.i(TAG, "Armed: hold ${HOLD_MS}ms per pulse, settle ${SETTLE_MS}ms, on request only")
    }

    fun stop() {
        pulseJob?.cancel()
        pulseJob = null
        // Unconditional: cancelling the pulse job runs its own teardown, but stop() may also be
        // reached with no pulse in flight and a stale overlay from a torn-down one.
        teardown(sleepAfterwards = false)
    }

    /**
     * A tracked device has gone silent long enough to be declared away, and the caller wants to
     * know whether the receiver is at fault before it says so.
     *
     * @return true when a pulse is in flight and the caller should wait [SETTLE_MS] before
     * concluding anything. False when no pulse is possible — the display is already on, the user
     * turned this off, the panel could not be re-slept, or another pulse just ran — in which
     * case the caller must decide on the evidence it already has. False is never a reason to
     * hold a departure back: silence that nothing can explain away is still a departure.
     */
    fun requestPulse(): Boolean {
        failWedgedPulse()
        if (pulsing) return true

        val context = appContext ?: return false
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        val now = System.currentTimeMillis()

        if (now - lastPulseEndedAt < MIN_PULSE_GAP_MS) return false
        if (!sleepCapable && now - sleepCapableCheckedAt < CAPABILITY_TTL_MS) return false

        return startPulse(context, powerManager)
    }

    /**
     * A real touch landed. Hooked into `ScreenTouchSensor.onUserTouch`, which is the narrowest
     * funnel every genuine touch passes through — the activity, the screensaver web view and the
     * browser overlay all call it.
     *
     * `ScreensaverController.onUserInteraction` would have been the wider net and is the wrong
     * one: camera motion and the proximity sensor call it too, so a curtain moving at 3am would
     * count as "the user is here", skip the sleep, and leave the panel lit. A pulse cannot
     * manufacture a touch; it can easily manufacture motion.
     *
     * Nothing is torn down here. Cancelling is enough — the pulse's own completion path reads
     * [userTookOver], so there is one teardown rather than two racing each other.
     */
    fun onUserTouch() {
        if (!pulsing) return
        userTookOver = true
        Log.i(TAG, "Touch during pulse; leaving the screen to whoever is there")
        pulseJob?.cancel()
    }

    private fun failWedgedPulse() {
        if (!pulsing) return
        if (System.currentTimeMillis() - pulseStartedAt <= PULSE_DEADLINE_MS) return
        Log.w(TAG, "Pulse overran ${PULSE_DEADLINE_MS}ms; forcing teardown")
        pulseJob?.cancel()
        teardown(sleepAfterwards = false)
    }

    /** @return true when a pulse was actually launched. */
    private fun startPulse(context: Context, powerManager: PowerManager): Boolean {
        if (pulseAllowed?.invoke() != true) return false
        // Already interactive means the scan is unfiltered and unsuppressed already, so silence
        // is about the device. A pulse would be both pointless and visible.
        if (powerManager.isInteractive) return false
        if (ScreenBlankOverlay.isShowing()) return false
        if (!PlatformCapabilities.canDrawOverlays(context)) {
            if (!loggedOverlayDenied) {
                loggedOverlayDenied = true
                Log.w(TAG, "No overlay permission; a pulse could not be kept dark, standing down")
            }
            return false
        }

        pulsing = true
        pulseStartedAt = System.currentTimeMillis()
        userTookOver = false
        wokeTheScreen = false
        pulseJob = ownScope.launch {
            try {
                runPulse(context, powerManager)
            } finally {
                teardown(sleepAfterwards = wokeTheScreen && !userTookOver)
            }
        }
        return true
    }

    /**
     * A pulse turns the display on and off again, and each transition rebuilds the scan. Both
     * rebuilds already carry their own short grace, but the gap between them does not, so the
     * whole window is handed to the presence layer at once.
     */
    private suspend fun runPulse(context: Context, powerManager: PowerManager) {
        // Cheapest way to fail, and the most important: waking a panel that cannot be re-slept
        // is the one outcome worse than the missing presence this exists to fix. The result is
        // remembered so the away pipeline stops waiting on a pulse that can never run.
        val capable = withContext(Dispatchers.IO) { canRestoreSleep(context) }
        sleepCapable = capable
        sleepCapableCheckedAt = System.currentTimeMillis()
        if (!capable) {
            Log.w(
                TAG,
                "No way to put the display back to sleep (no root, Shizuku or device admin); " +
                    "not waking it in the first place",
            )
            return
        }

        suppressAway?.invoke(PULSE_WINDOW_MS)

        // Dark first, and only continue if it took. The overlay has to be up and holding
        // brightness down before anything lights the panel.
        withContext(Dispatchers.Main) { attachDarkOverlay(context) }
        if (!overlayAttached) {
            Log.w(TAG, "Dark overlay did not attach; not waking the display")
            return
        }

        withContext(Dispatchers.IO) { acquireWake(context) }

        if (!awaitPulseEffective(context, powerManager)) {
            Log.w(TAG, "Wake did not take effect for the scanner; abandoning pulse")
            return
        }
        Log.i(TAG, "Interactive behind dark overlay; holding ${HOLD_MS}ms for the scanner")
        delay(HOLD_MS)
    }

    /**
     * Every route [ScreenControlUtils.lockScreen] can take, queried without requesting anything —
     * unlike `ensureScreenOffPermission`, which would raise a permission dialog. The root probe
     * shells out on its first call, so callers keep this off the main and default dispatchers.
     */
    private fun canRestoreSleep(context: Context): Boolean =
        RootUtils.isRootAvailable() ||
            ShizukuUtils.isShizukuPermissionGranted() ||
            ScreenControlUtils.isDeviceAdminActive(context)

    /**
     * The wakelock is what moves wakefulness; the Shizuku key event is sent alongside because
     * `FULL_WAKE_LOCK` is deprecated and honoured inconsistently, and between the two one of them
     * lands. Neither is trusted — [awaitPulseEffective] decides.
     */
    private fun acquireWake(context: Context) {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            @Suppress("DEPRECATION")
            val flags = PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            val lock = powerManager.newWakeLock(flags, "Ava:DarkWakePulse")
            // Timeout is a backstop only; teardown releases it explicitly. Sized past the hold so
            // an overrun cannot drop the screen mid-pulse.
            lock.acquire(PULSE_WINDOW_MS)
            wakeLock = lock
        }.onFailure { Log.w(TAG, "Wakelock acquire failed", it) }

        if (ShizukuUtils.isShizukuPermissionGranted()) {
            runCatching { ShizukuUtils.executeCommand("input keyevent 224") }
                .onFailure { Log.w(TAG, "KEYCODE_WAKEUP via Shizuku failed", it) }
        }
    }

    /**
     * Both halves of the condition the scan strategy actually reads, not just wakefulness:
     * `VoiceSatelliteBluetooth` drops its filters only when `PowerManager` and
     * [ScreenControlUtils.deviceAwakeState] agree. That flow is normally driven by the screen
     * broadcast, which is asynchronous and may not have landed yet, so
     * [ScreenControlUtils.syncScreenState] is nudged rather than waited on.
     *
     * [wokeTheScreen] is set on interactivity alone: once the platform is awake the pulse owes it
     * a sleep whether or not the rest of the handshake completed.
     */
    private suspend fun awaitPulseEffective(context: Context, powerManager: PowerManager): Boolean {
        var waited = 0L
        while (waited < EFFECTIVE_TIMEOUT_MS) {
            if (powerManager.isInteractive) {
                wokeTheScreen = true
                ScreenControlUtils.syncScreenState(context)
                if (ScreenControlUtils.deviceAwakeState.value) return true
            }
            delay(EFFECTIVE_POLL_MS)
            waited += EFFECTIVE_POLL_MS
        }
        return false
    }

    /**
     * Opaque black at the lowest brightness the window manager will grant, so its brightness
     * request is the one that applies and whatever the screensaver was drawing stays hidden.
     *
     * Touchable on purpose, and it hands the event to the same pair of funnels
     * `MainActivity.dispatchTouchEvent` uses, so a touch during a pulse behaves exactly as a
     * touch always did — the screensaver dismisses, and [onUserTouch] hears about it. A touch
     * landing on a screen the user cannot see is better consumed than delivered.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDarkOverlay(context: Context) {
        if (overlayView != null) {
            // A view left over from a removal that failed is still a view holding the panel dark,
            // so this counts as attached. Reporting it as a failure instead would abort every
            // pulse from here on for no reason.
            overlayAttached = true
            return
        }
        val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
                Log.w(TAG, "No WindowManager; cannot go dark")
                return
            }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            PlatformCapabilities.overlayWindowType(),
            // Not focusable so no key or IME focus is stolen, but deliberately not NOT_TOUCHABLE —
            // touches are the whole abort mechanism.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 0f is the dimmest the window manager will hand out. Window-scoped, so there is
            // nothing to restore and nothing to leak.
            screenBrightness = 0f
            com.example.ava.services.OverlayOrientation.apply(this)
        }
        val view = View(context).apply {
            setBackgroundColor(Color.BLACK)
            applyQuickEntityOverlayViewSetup()
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    com.example.ava.services.ScreensaverController.onUserInteraction()
                    com.example.ava.sensor.ScreenTouchSensor.onUserTouch()
                }
                true
            }
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlayView = view
                overlayAttached = true
            }
            .onFailure { Log.w(TAG, "Failed to add dark overlay", it) }
    }

    /**
     * The wakelock goes first, then the sleep, then the overlay.
     *
     * The wakelock has to be released before the sleep is even attempted. `FULL_WAKE_LOCK` is a
     * standing request to keep the display on and `PowerManager` honours it over anything the
     * pulse asks for afterwards, so releasing it later — as this once did — meant the pulse
     * vetoed its own sleep and left the panel lit for whatever else eventually took it down.
     *
     * The overlay comes off last so the panel does not show the screensaver at full brightness
     * for the frames between the sleep landing and the window going away. If the sleep fails the
     * overlay is still removed: what is left then is a screen simply on at the user's own
     * brightness, and keeping a black overlay up to hide that would be far worse — an unreadable
     * panel with no way for the user to tell it apart from a dead one.
     *
     * Idempotent, and safe to call with nothing in flight.
     */
    private fun teardown(sleepAfterwards: Boolean) {
        ownScope.launch {
            releaseWake()
            try {
                val context = appContext
                if (sleepAfterwards && context != null) {
                    val slept = withContext(Dispatchers.IO) {
                        // Re-checked rather than trusted from [runPulse]: with none of the routes
                        // left, `ScreenControlUtils.sleepScreen` would try to *acquire* device
                        // admin, and a permission dialog is the last thing to raise on a panel at
                        // 3am.
                        canRestoreSleep(context) && ScreenControlUtils.lockScreen(context)
                    }
                    if (!slept) {
                        Log.w(TAG, "Could not put the display back to sleep after the pulse")
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { detachDarkOverlay() }
                lastPulseEndedAt = System.currentTimeMillis()
                pulsing = false
            }
        }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(TAG, "Wakelock release failed", it) }
        wakeLock = null
    }

    private fun detachDarkOverlay() {
        overlayAttached = false
        val view = overlayView ?: return
        overlayView = null
        val windowManager = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeView(view) }
            .onFailure { Log.w(TAG, "Failed to remove dark overlay", it) }
    }
}
