package com.example.ava.webcompat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.ava.fleet.FleetShell
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.DarkModeManager
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils

/**
 * Render engine selection for the floating browser.
 *
 * GeckoView cannot be loaded at runtime like a mod: its child-process <service> declarations
 * must be merged into a manifest at build time. It therefore ships in a separate `gecko` product
 * flavor (a downloadable APK with applicationId `com.example.ava.gecko`).
 *
 * The main APK detects whether the gecko engine pack is installed and delegates rendering to it.
 */
object BrowserEngine {

    private const val TAG = "BrowserEngine"

    const val SYSTEM = 0
    const val GECKO = 1

    /** Package name of the standalone GeckoView engine APK. */
    const val GECKO_ENGINE_PACKAGE = "com.example.ava.gecko"

    /** Headless bridge activity in the gecko pack (same as adb -n …/GeckoBrowserBridge). */
    private const val GECKO_BROWSER_BRIDGE_CLASS = "com.example.ava.GeckoBrowserBridge"

    /** Exported WebViewService in the gecko pack — preferred over Bridge for show/hide. */
    private const val GECKO_WEBVIEW_SERVICE_CLASS = "com.example.ava.services.WebViewService"
    /** Child-pack broadcast door. Host FGS into a dead child is dropped; this wakes it. */
    private const val GECKO_CHILD_RECEIVER_CLASS = "com.example.ava.GeckoChildControlReceiver"

    /** Host Ava app that owns HA entities and BrowserSettings DataStore. */
    const val HOST_PACKAGE = "com.example.ava"

    /** Gecko pack → host: keep HA browser_display switch aligned with overlay visibility. */
    const val ACTION_SYNC_BROWSER_VISIBLE = "com.example.ava.action.SYNC_BROWSER_VISIBLE"
    const val ACTION_REASSERT_FOREGROUND_OVERLAYS = "com.example.ava.action.REASSERT_FOREGROUND_OVERLAYS"
    const val EXTRA_BROWSER_VISIBLE = "visible"
    /**
     * When false, host updates only the in-memory peer overlay flag.
     * Runtime hide/destroy must not flip the HA browser_display switch — that made
     * VoiceSatellite send ACTION_HIDE into a subsequent SHOW and left gecko
     * VISIBLE at alpha 0 (focus stolen, no pixels).
     */
    const val EXTRA_UPDATE_HA_SWITCH = "update_ha_switch"
    /** Gecko pack → host: SHOW is only done when this token comes back attached. */
    const val EXTRA_RECEIPT_TOKEN = "receipt_token"
    const val EXTRA_RECEIPT_ATTACHED = "attached"

    /** Host → gecko pack: liveness Binder so the pack can linkToDeath() and reap its overlay. */
    const val EXTRA_HOST_LIVENESS = "host_liveness"
    const val KEY_HOST_LIVENESS_TOKEN = "token"

    /** Intent actions for communicating with the gecko engine pack Bridge activity. */
    private const val ACTION_SHOW_BROWSER = "com.example.ava.gecko.action.SHOW_BROWSER"
    private const val ACTION_HIDE_BROWSER = "com.example.ava.gecko.action.HIDE_BROWSER"
    private const val ACTION_DESTROY_BROWSER = "com.example.ava.gecko.action.DESTROY_BROWSER"
    private const val ACTION_NAVIGATE = "com.example.ava.gecko.action.NAVIGATE"
    private const val ACTION_REFRESH_DARK_MODE = "com.example.ava.gecko.action.REFRESH_DARK_MODE"
    const val ACTION_RESTORE_FROM_SETTINGS = "com.example.ava.gecko.action.RESTORE_FROM_SETTINGS"
    const val ACTION_CLEAR_CACHE = "com.example.ava.gecko.action.CLEAR_CACHE"
    private const val ACTION_FORCE_REFRESH = "com.example.ava.gecko.action.FORCE_REFRESH"
    private const val EXTRA_URL = "url"
    const val EXTRA_FOLLOW_DARK_MODE = "follow_dark_mode"
    const val EXTRA_DARK_MODE = "dark_mode"

    /**
     * Host visibility sync used to fire dozens of SHOW/HIDE per second. Direct FGS delivery made
     * every one hit gecko mid-[GeckoRuntime] init and SIGSEGV in libxul. Coalesce to last-wins.
     */
    private const val SERVICE_COALESCE_MS = 400L
    private const val PACKAGE_REPLACE_SETTLE_MS = 5_000L
    private val SERVICE_START_RETRY_DELAYS_MS = longArrayOf(350L, 900L, 1_800L, 3_000L)
    private val RECEIPT_RETRY_DELAYS_MS = longArrayOf(4_000L, 7_000L, 12_000L)
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val serviceLock = Any()
    private var coalescedAction: String? = null
    private var coalescedUrl: String? = null
    private var coalescedFollowDark: Boolean? = null
    private var coalescedDarkMode: Boolean? = null
    private var coalesceScheduled = false
    private var serviceCommandGeneration = 0L
    private var lastFlushedAction: String? = null
    private var lastFlushedUrl: String? = null
    private var lastFlushedAtMs = 0L
    private var receiptTokenSeq = 0L
    private var pendingShowReceiptToken = 0L
    private var ackedShowReceiptToken = 0L
    private var lastReceiptVisible = false
    private var expectVisible = false
    private var pendingReceiptUrl: String? = null
    private var pendingReceiptFollow: Boolean? = null
    private var pendingReceiptDark: Boolean? = null

    /**
     * True when GeckoView is available for use:
     * - In the gecko flavor: always true (bundled).
     * - In the default flavor: true only if the gecko engine pack is installed.
     */
    fun isGeckoAvailable(context: Context): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) return true
        return isGeckoEnginePackInstalled(context)
    }

    /** Check if the standalone gecko engine APK is installed on the device. */
    fun isGeckoEnginePackInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(GECKO_ENGINE_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(GECKO_ENGINE_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "isGeckoEnginePackInstalled failed", e)
            false
        }
    }

    /** Engine actually usable right now, falling back to the system WebView when GeckoView is absent. */
    fun effectiveEngine(context: Context, preference: Int): Int {
        // The standalone gecko engine pack exists solely to render with GeckoView, so it always
        // uses GECKO regardless of its own (unused) preference store.
        if (EngineCapabilities.GECKO_BUNDLED) return GECKO
        return if (preference == GECKO && isGeckoAvailable(context)) GECKO else SYSTEM
    }

    /**
     * True when the main app should hand browser overlay work to the external gecko pack.
     * This is only true in the default flavor when the gecko pack is installed and the user selected the GECKO engine.
     */
    fun shouldDelegateToGeckoPack(context: Context, enginePreference: Int): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) return false
        if (enginePreference != GECKO) return false
        return isGeckoEnginePackInstalled(context)
    }

    /** Start the GeckoView browser in the external gecko engine pack. */
    fun showGeckoBrowser(context: Context, url: String): Boolean {
        // Same Shizuku/root plane as fleet scrcpy: grant gecko overlay before first launch.
        GeckoEngineRootSetup.ensureOverlayForLaunch(context)
        GeckoEngineDeathMonitor.suppressBriefly()
        // Prefer direct FGS into gecko WebViewService. GeckoBrowserBridge is Theme.NoDisplay and
        // finish()s immediately, which races startForegroundService on many OEMs (MIUI) so the
        // overlay never attaches — verified via adb: Bridge SHOW no-ops, direct FGS works.
        // Dispatch also pries via AppWindow su/Shizuku `am` (API-aware fallbacks).
        return startGeckoWebViewService(context, "ACTION_SHOW") {
            putExtra(EXTRA_URL, url)
            putDarkModeExtras(context)
        } || sendGeckoBridgeIntent(context, ACTION_SHOW_BROWSER) {
            putExtra(EXTRA_URL, url)
            putDarkModeExtras(context)
        }
    }

    /** Hide the GeckoView browser in the external gecko engine pack. */
    fun hideGeckoBrowser(context: Context): Boolean {
        return startGeckoWebViewService(context, "ACTION_HIDE") ||
            sendGeckoBridgeIntent(context, ACTION_HIDE_BROWSER)
    }

    /** Restore the GeckoView browser after returning from host settings. */
    fun restoreGeckoBrowser(context: Context): Boolean {
        // restoreIfPending lives on the companion; Bridge still owns that path.
        val sent = sendGeckoBridgeIntent(context, ACTION_RESTORE_FROM_SETTINGS)
        pryGeckoPackViaAppWindowShell(
            context = context,
            serviceAction = null,
            bridgeAction = ACTION_RESTORE_FROM_SETTINGS,
        )
        return sent
    }

    /** Clear browsing data in the external gecko engine pack. */
    fun clearGeckoBrowserCache(context: Context): Boolean {
        return startGeckoWebViewService(context, "ACTION_CLEAR_CACHE") ||
            sendGeckoBridgeIntent(context, ACTION_CLEAR_CACHE)
    }

    /** Cookies / site data in the external gecko engine pack. */
    fun clearGeckoCookiesAndHistory(context: Context): Boolean {
        return startGeckoWebViewService(context, "ACTION_CLEAR_COOKIES_HISTORY")
    }

    /** Tear down the GeckoView browser in the external gecko engine pack (service lifecycle only). */
    fun destroyGeckoBrowser(context: Context): Boolean {
        GeckoEngineDeathMonitor.suppressBriefly()
        return startGeckoWebViewService(context, "ACTION_DESTROY") ||
            sendGeckoBridgeIntent(context, ACTION_DESTROY_BROWSER)
    }

    /** Gecko engine pack runs in a separate process; push visibility into the host DataStore. */
    fun syncBrowserVisibleToHost(
        context: Context,
        visible: Boolean,
        updateHaSwitch: Boolean = true,
        receiptToken: Long = 0L,
        attached: Boolean = visible,
    ) {
        try {
            context.sendBroadcast(
                Intent(ACTION_SYNC_BROWSER_VISIBLE).apply {
                    setPackage(HOST_PACKAGE)
                    putExtra(EXTRA_BROWSER_VISIBLE, visible)
                    putExtra(EXTRA_UPDATE_HA_SWITCH, updateHaSwitch)
                    putExtra(EXTRA_RECEIPT_TOKEN, receiptToken)
                    putExtra(EXTRA_RECEIPT_ATTACHED, attached)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync browser visible=$visible to host", e)
        }
    }

    fun lastRequestedShowUrl(): String? = synchronized(serviceLock) {
        pendingReceiptUrl ?: lastFlushedUrl
    }

    /**
     * Pack ACK that the overlay window is actually up (or gone). Fire-and-forget SHOW
     * is not enough — host only stops retrying after [attached] comes back true.
     */
    fun noteGeckoOverlayReceipt(token: Long, visible: Boolean, attached: Boolean) {
        val up = visible && attached
        synchronized(serviceLock) {
            lastReceiptVisible = up
            if (up && (token == 0L || token == pendingShowReceiptToken)) {
                ackedShowReceiptToken = if (token != 0L) token else pendingShowReceiptToken
            }
            if (!up) {
                lastFlushedAction = null
                lastFlushedUrl = null
                lastFlushedAtMs = 0L
                expectVisible = false
            }
        }
        Log.i(TAG, "Gecko overlay receipt token=$token visible=$visible attached=$attached")
    }

    /** Navigate to a URL in the external gecko engine pack's browser. */
    fun navigateGeckoBrowser(context: Context, url: String): Boolean {
        return startGeckoWebViewService(context, "ACTION_SHOW_OR_REFRESH") {
            putExtra(EXTRA_URL, url)
            putDarkModeExtras(context)
        } || sendGeckoBridgeIntent(context, ACTION_NAVIGATE) {
            putExtra(EXTRA_URL, url)
            putDarkModeExtras(context)
        }
    }

    /** Push the host app's dark-mode state into the external Gecko engine pack. */
    fun refreshGeckoDarkMode(context: Context): Boolean {
        return startGeckoWebViewService(context, "ACTION_REFRESH_DARK_MODE") {
            putDarkModeExtras(context)
        } || sendGeckoBridgeIntent(context, ACTION_REFRESH_DARK_MODE) {
            putDarkModeExtras(context)
        }
    }

    /** Force reload the current page in the external gecko engine pack's browser. */
    fun forceRefreshGeckoBrowser(context: Context): Boolean {
        return startGeckoWebViewService(context, "ACTION_FORCE_REFRESH") ||
            sendGeckoBridgeIntent(context, ACTION_FORCE_REFRESH)
    }

    private fun Intent.putDarkModeExtras(context: Context) {
        putExtra(EXTRA_FOLLOW_DARK_MODE, BrowserSettingsStore.getCachedFollowSystemDarkMode(context))
        putExtra(EXTRA_DARK_MODE, DarkModeManager.getInstance(context).isDarkMode())
    }

    /**
     * Punch the gecko child from outside the host process.
     * 1. Explicit broadcast into [GeckoChildControlReceiver] (no root).
     * 2. Fleet / su `am` lines — same plane as the ADB terminal.
     */
    private fun pryGeckoPackViaAppWindowShell(
        context: Context? = null,
        serviceAction: String?,
        url: String? = null,
        followDark: Boolean? = null,
        darkMode: Boolean? = null,
        bridgeAction: String? = serviceAction?.let { GeckoPackAm.bridgeActionForService(it) },
        receiptToken: Long = 0L,
    ) {
        if (EngineCapabilities.GECKO_BUNDLED) return
        punchGeckoChildBroadcast(
            context,
            serviceAction,
            url,
            followDark,
            darkMode,
            receiptToken,
            bridgeAction,
        )
        val cmds = GeckoPackAm.commands(
            sdkInt = Build.VERSION.SDK_INT,
            serviceAction = serviceAction,
            bridgeAction = bridgeAction,
            url = url,
            followDark = followDark,
            darkMode = darkMode,
            receiptToken = receiptToken,
        )
        if (cmds.isEmpty()) return
        val app = context?.applicationContext
        Thread({
            if (app != null && FleetShell.backend() != null) {
                for (cmd in cmds) {
                    val ran = FleetShell.exec(app, cmd, timeoutSec = 8)
                    if (ran.ok) {
                        Log.i(TAG, "Punched gecko child via FleetShell: $cmd")
                        return@Thread
                    }
                    Log.w(TAG, "FleetShell punch missed code=${ran.code}: $cmd")
                }
            }
            if (!RootUtils.isRootAvailable() && !ShizukuUtils.isShizukuPermissionGranted()) {
                return@Thread
            }
            for (cmd in cmds) {
                val code = execAppWindowShell(cmd)
                if (code == 0) {
                    Log.i(TAG, "Pried gecko pack via app-window shell: $cmd")
                    return@Thread
                }
                Log.w(TAG, "App-window shell pry missed (code=$code): $cmd")
            }
        }, "GeckoPackShell").apply {
            isDaemon = true
            start()
        }
    }

    private fun punchGeckoChildBroadcast(
        context: Context?,
        serviceAction: String?,
        url: String?,
        followDark: Boolean?,
        darkMode: Boolean?,
        receiptToken: Long,
        bridgeAction: String? = null,
    ) {
        val app = context?.applicationContext ?: return
        val action = serviceAction?.let { GeckoPackAm.bridgeActionForService(it) }
            ?: bridgeAction
            ?: return
        try {
            val intent = Intent(action).setClassName(GECKO_ENGINE_PACKAGE, GECKO_CHILD_RECEIVER_CLASS)
            if (!url.isNullOrBlank()) intent.putExtra(EXTRA_URL, url)
            if (followDark != null) intent.putExtra(EXTRA_FOLLOW_DARK_MODE, followDark)
            if (darkMode != null) intent.putExtra(EXTRA_DARK_MODE, darkMode)
            if (receiptToken != 0L) intent.putExtra(EXTRA_RECEIPT_TOKEN, receiptToken)
            app.sendBroadcast(intent)
            Log.i(TAG, "Broadcast punch to gecko child: $action url=$url")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast punch to gecko child failed", e)
        }
    }

    private fun execAppWindowShell(command: String): Int = when {
        RootUtils.isRootAvailable() -> runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        }.getOrDefault(-1)
        ShizukuUtils.isShizukuPermissionGranted() -> ShizukuUtils.executeCommand(command).first
        else -> -2
    }

    /**
     * Start gecko pack [WebViewService] directly (exported). Uses [Context.getApplicationContext]
     * so a finishing Bridge activity cannot drop the foreground-service start.
     *
     * SHOW/HIDE/DESTROY/SHOW_OR_REFRESH are coalesced (last-wins) so host visibility sync storms
     * cannot kill libxul mid-init.
     */
    private fun startGeckoWebViewService(
        context: Context,
        serviceAction: String,
        extras: Intent.() -> Unit = {}
    ): Boolean {
        if (!isGeckoEnginePackInstalled(context)) {
            Log.w(TAG, "Gecko engine pack not installed, cannot start $serviceAction")
            return false
        }
        val app = context.applicationContext
        val probe = Intent().apply(extras)
        val url = probe.getStringExtra(EXTRA_URL)
        val follow = if (probe.hasExtra(EXTRA_FOLLOW_DARK_MODE)) {
            probe.getBooleanExtra(EXTRA_FOLLOW_DARK_MODE, true)
        } else {
            null
        }
        val dark = if (probe.hasExtra(EXTRA_DARK_MODE)) {
            probe.getBooleanExtra(EXTRA_DARK_MODE, false)
        } else {
            null
        }

        if (!shouldCoalesce(serviceAction)) {
            return dispatchGeckoWebViewService(app, serviceAction, url, follow, dark)
        }

        synchronized(serviceLock) {
            serviceCommandGeneration += 1L
            coalescedAction = serviceAction
            coalescedUrl = url
            coalescedFollowDark = follow
            coalescedDarkMode = dark
            if (!coalesceScheduled) {
                coalesceScheduled = true
                serviceHandler.postDelayed({
                    val action: String?
                    val flushUrl: String?
                    val flushFollow: Boolean?
                    val flushDark: Boolean?
                    val commandGeneration: Long
                    synchronized(serviceLock) {
                        coalesceScheduled = false
                        action = coalescedAction
                        flushUrl = coalescedUrl
                        flushFollow = coalescedFollowDark
                        flushDark = coalescedDarkMode
                        commandGeneration = serviceCommandGeneration
                        coalescedAction = null
                        coalescedUrl = null
                        coalescedFollowDark = null
                        coalescedDarkMode = null
                    }
                    if (action != null) {
                        dispatchGeckoWebViewService(
                            app,
                            action,
                            flushUrl,
                            flushFollow,
                            flushDark,
                            commandGeneration,
                        )
                    }
                }, SERVICE_COALESCE_MS)
            }
        }
        return true
    }

    private fun shouldCoalesce(serviceAction: String): Boolean =
        serviceAction == "ACTION_SHOW" ||
            serviceAction == "ACTION_HIDE" ||
            serviceAction == "ACTION_DESTROY" ||
            serviceAction == "ACTION_SHOW_OR_REFRESH"

    private fun isShowAction(serviceAction: String): Boolean =
        serviceAction == "ACTION_SHOW" || serviceAction == "ACTION_SHOW_OR_REFRESH"

    private fun dispatchGeckoWebViewService(
        app: Context,
        serviceAction: String,
        url: String?,
        followDark: Boolean?,
        darkMode: Boolean?,
        commandGeneration: Long? = null,
        retryIndex: Int = 0,
        force: Boolean = false,
        receiptToken: Long = 0L,
    ): Boolean {
        if (commandGeneration != null && !isCurrentServiceCommand(commandGeneration)) {
            Log.d(TAG, "Drop stale gecko WebViewService $serviceAction")
            return true
        }

        if (commandGeneration != null && retryIndex == 0 && !force) {
            val settleDelay = geckoPackageSettleDelayMs(app)
            if (settleDelay > 0L) {
                Log.i(
                    TAG,
                    "Deferring gecko WebViewService $serviceAction for ${settleDelay}ms after package update",
                )
                scheduleGeckoServiceRetry(
                    app,
                    serviceAction,
                    url,
                    followDark,
                    darkMode,
                    commandGeneration,
                    retryIndex,
                    settleDelay,
                )
                return true
            }
        }

        synchronized(serviceLock) {
            val now = SystemClock.elapsedRealtime()
            val duplicate = serviceAction == lastFlushedAction &&
                url == lastFlushedUrl &&
                now - lastFlushedAtMs < SERVICE_COALESCE_MS
            if (!force && duplicate && lastReceiptVisible) {
                Log.d(TAG, "Skip duplicate gecko WebViewService $serviceAction")
                return true
            }
        }
        val token = when {
            receiptToken != 0L -> receiptToken
            isShowAction(serviceAction) -> synchronized(serviceLock) { ++receiptTokenSeq }
            else -> 0L
        }
        if (isShowAction(serviceAction)) {
            synchronized(serviceLock) {
                expectVisible = true
                pendingShowReceiptToken = token
                pendingReceiptUrl = url
                pendingReceiptFollow = followDark
                pendingReceiptDark = darkMode
            }
        } else if (serviceAction == "ACTION_HIDE" || serviceAction == "ACTION_DESTROY") {
            synchronized(serviceLock) {
                expectVisible = false
                pendingShowReceiptToken += 1L
            }
        }
        return try {
            val intent = Intent().setClassName(GECKO_ENGINE_PACKAGE, GECKO_WEBVIEW_SERVICE_CLASS).apply {
                action = serviceAction
                if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
                if (followDark != null) putExtra(EXTRA_FOLLOW_DARK_MODE, followDark)
                if (darkMode != null) putExtra(EXTRA_DARK_MODE, darkMode)
                if (token != 0L) putExtra(EXTRA_RECEIPT_TOKEN, token)
            }
            startGeckoServiceBestEffort(app, intent)
            synchronized(serviceLock) {
                lastFlushedAction = serviceAction
                lastFlushedUrl = url
                lastFlushedAtMs = SystemClock.elapsedRealtime()
            }
            Log.i(TAG, "Started gecko WebViewService: $serviceAction url=$url token=$token")
            pryGeckoPackViaAppWindowShell(
                context = app,
                serviceAction = serviceAction,
                url = url,
                followDark = followDark,
                darkMode = darkMode,
                receiptToken = token,
            )
            if (isShowAction(serviceAction) && !force) {
                scheduleOverlayReceiptWatchdog(app, token, 0)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start gecko WebViewService: $serviceAction", e)
            pryGeckoPackViaAppWindowShell(
                context = app,
                serviceAction = serviceAction,
                url = url,
                followDark = followDark,
                darkMode = darkMode,
                receiptToken = token,
            )
            if (commandGeneration != null &&
                retryIndex < SERVICE_START_RETRY_DELAYS_MS.size &&
                isGeckoEnginePackInstalled(app)
            ) {
                val retryDelay = SERVICE_START_RETRY_DELAYS_MS[retryIndex]
                Log.w(
                    TAG,
                    "Retry gecko WebViewService $serviceAction " +
                        "attempt=${retryIndex + 1}/${SERVICE_START_RETRY_DELAYS_MS.size} in ${retryDelay}ms",
                )
                scheduleGeckoServiceRetry(
                    app,
                    serviceAction,
                    url,
                    followDark,
                    darkMode,
                    commandGeneration,
                    retryIndex + 1,
                    retryDelay,
                )
                true
            } else {
                false
            }
        }
    }

    private fun scheduleOverlayReceiptWatchdog(app: Context, token: Long, retryIndex: Int) {
        val delay = RECEIPT_RETRY_DELAYS_MS.getOrNull(retryIndex) ?: return
        serviceHandler.postDelayed({
            val stillWanted: Boolean
            val url: String?
            val follow: Boolean?
            val dark: Boolean?
            synchronized(serviceLock) {
                stillWanted = expectVisible &&
                    pendingShowReceiptToken == token &&
                    ackedShowReceiptToken != token
                url = pendingReceiptUrl
                follow = pendingReceiptFollow
                dark = pendingReceiptDark
            }
            if (!stillWanted) return@postDelayed
            if (!isGeckoEnginePackInstalled(app)) {
                Log.e(TAG, "Gecko overlay receipt missing and pack gone — not using the system WebView")
                return@postDelayed
            }
            Log.w(
                TAG,
                "Gecko overlay receipt missing token=$token; " +
                    "retry ${retryIndex + 1}/${RECEIPT_RETRY_DELAYS_MS.size} — not using the system WebView",
            )
            dispatchGeckoWebViewService(
                app,
                "ACTION_SHOW",
                url,
                follow,
                dark,
                force = true,
                receiptToken = token,
            )
            sendGeckoBridgeIntent(app, ACTION_SHOW_BROWSER) {
                if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
                if (follow != null) putExtra(EXTRA_FOLLOW_DARK_MODE, follow)
                if (dark != null) putExtra(EXTRA_DARK_MODE, dark)
                putExtra(EXTRA_RECEIPT_TOKEN, token)
            }
            if (retryIndex + 1 < RECEIPT_RETRY_DELAYS_MS.size) {
                scheduleOverlayReceiptWatchdog(app, token, retryIndex + 1)
            } else {
                Log.e(TAG, "Gecko overlay never ACKed token=$token — not using the system WebView")
            }
        }, delay)
    }

    private fun scheduleGeckoServiceRetry(
        app: Context,
        serviceAction: String,
        url: String?,
        followDark: Boolean?,
        darkMode: Boolean?,
        commandGeneration: Long,
        retryIndex: Int,
        delayMs: Long,
    ) {
        serviceHandler.postDelayed({
            if (!isCurrentServiceCommand(commandGeneration)) {
                Log.d(TAG, "Cancel stale gecko WebViewService retry $serviceAction")
                return@postDelayed
            }
            dispatchGeckoWebViewService(
                app,
                serviceAction,
                url,
                followDark,
                darkMode,
                commandGeneration,
                retryIndex,
            )
        }, delayMs)
    }

    private fun isCurrentServiceCommand(commandGeneration: Long): Boolean =
        synchronized(serviceLock) { commandGeneration == serviceCommandGeneration }

    /**
     * Android 8+ wants [Context.startForegroundService]; Android 5–7 only have
     * [Context.startService]. On 12+ a background FGS start can throw — fall
     * through to startService so a live gecko process still gets the extras.
     */
    private fun startGeckoServiceBestEffort(app: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                app.startForegroundService(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed, trying startService", e)
            }
        }
        app.startService(intent)
    }

    internal fun geckoPackageSettleDelayMs(context: Context): Long {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    GECKO_ENGINE_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(GECKO_ENGINE_PACKAGE, 0)
            }
            val ageMs = System.currentTimeMillis() - info.lastUpdateTime
            if (ageMs >= 0L && ageMs < PACKAGE_REPLACE_SETTLE_MS) {
                PACKAGE_REPLACE_SETTLE_MS - ageMs
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun sendGeckoBridgeIntent(
        context: Context,
        action: String,
        extras: Intent.() -> Unit = {}
    ): Boolean {
        if (!isGeckoEnginePackInstalled(context)) {
            Log.w(TAG, "Gecko engine pack not installed, cannot send $action")
            return false
        }
        return try {
            val intent = Intent(action).apply {
                component = ComponentName(GECKO_ENGINE_PACKAGE, GECKO_BROWSER_BRIDGE_CLASS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                extras()
            }
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                // Some Android 7 ROMs false-negative here while startActivity still works (adb -n OK).
                Log.w(TAG, "resolveActivity null for $action; trying startActivity anyway")
            }
            context.applicationContext.startActivity(intent)
            Log.i(TAG, "Sent gecko bridge intent: $action url=${intent.getStringExtra(EXTRA_URL)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send intent to gecko pack: $action", e)
            false
        }
    }
}
