package com.example.ava.services

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleService
import com.example.ava.ui.theme.AvaTheme
import com.example.ava.webcompat.SecureContextProxy
import java.util.concurrent.CopyOnWriteArrayList
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.ava.R
import com.example.ava.notifications.FullscreenOverlayEscape
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.notifications.createBrowserServiceNotification
import com.example.ava.notifications.createBrowserServiceNotificationChannel
import com.example.ava.settings.BrowserPowerMode
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.isBrowserDisplayActive
import com.example.ava.settings.SidebarPosition
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.utils.BrowserNavHintKind
import com.example.ava.utils.PullRefreshDwell
import com.example.ava.utils.pullRefreshDwell
import com.example.ava.utils.UserScriptManager
import com.example.ava.utils.WebViewGestureDetector
import com.example.ava.utils.ExtractedContent
import com.example.ava.utils.ReadabilityExtractor
import com.example.ava.utils.TouchSoundHelper
import com.example.ava.webmonkey.GmApi
import com.example.ava.webmonkey.GmApiInjector
import com.example.ava.webcompat.BrowserLegacyTranspiler
import com.example.ava.webcompat.OxcTranspiler
import com.example.ava.webcompat.WebViewRuntime
import com.example.ava.webcompat.BrowserEngine
import com.example.ava.webcompat.EngineCapabilities
import com.example.ava.webcompat.GeckoEngineDeathMonitor
import com.example.ava.webcompat.GeckoEngineFactory
import com.example.ava.webcompat.GeckoEngineRootSetup
import com.example.ava.webcompat.HostSidebarCommandBridge
import com.example.ava.webcompat.HostSidebarSettingsContract
import com.example.ava.webcompat.HostSidebarSettingsBridge
import com.example.ava.webcompat.HostSidebarSettingsMirror
import com.example.ava.webcompat.EngineSurface
import com.example.ava.touchpad.TouchPadMath
import com.example.ava.touchpad.TouchPadPageScroll
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.Screen
import com.example.ava.webcompat.BrowserDarkModeScripts
import com.example.ava.webcompat.BrowserDarkModeResolver
import com.example.ava.webcompat.BrowserHaKioskScripts
import com.example.ava.webcompat.BrowserScrollAssist
import com.example.ava.webcompat.BrowserScrollBridge
import com.example.ava.webcompat.BrowserMemoryGuard
import com.example.ava.webcompat.BrowserWsStewardConsole
import com.example.ava.webcompat.BrowserWsStewardHost
import com.example.ava.webcompat.BrowserWsStewardScripts
import com.example.ava.webcompat.BrowserHtmlFullscreenController
import com.example.ava.webcompat.BrowserDownloadBridge
import com.example.ava.webcompat.BrowserPlatformCompat
import com.example.ava.webcompat.HaDarkModeJsBridge
import com.example.ava.settings.DarkModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.AvaToast
import com.example.ava.ui.glass.LiquidGlass

class WebViewService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {
    enum class BrowserPane { LEFT, RIGHT }

    private val viewModelStoreImpl = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore
        get() = viewModelStoreImpl

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private fun installComposeViewTreeOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
    private var windowManager: WindowManager? = null
    private var containerView: ViewGroup? = null
    private var browserPaneFit: Runnable? = null
    private var browserPaneLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var browserPaneFitW = -1
    private var browserPaneFitH = -1
    private var windowParams: WindowManager.LayoutParams? = null
    private var padLiftActive = false
    /**
     * System-bar/cutout overlap over the overlay per side (top,right,bottom,left) in
     * CSS px; fed to pages via `__avaSetInsets` — official-companion-app parity.
     */
    @Volatile
    private var pageInsetsCssPx = intArrayOf(0, 0, 0, 0)
    /** `left` / `right` / `none` — which HA header icons clear Ava's edge handle. */
    @Volatile
    private var haSidebarHandleEdge = "left"
    /** True while FLAG_NOT_FOCUSABLE is lifted so a WebView field can host the IME. */
    private var overlayImeFocusHeld = false
    private var pullRefreshWatching = false
    private var pullRefreshDwellReady = false
    private var pullRefreshDownAtMs = 0L
    private var pullRefreshActive = false
    private var pullRefreshStartX = 0f
    private var pullRefreshStartY = 0f
    private var pullRefreshPane = BrowserPane.LEFT
    private var pullRefreshHoldRunnable: Runnable? = null
    private var webView: WebView? = null
    /** Right/bottom tile when [BrowserSettings.splitViewEnabled]; system WebView only. */
    private var webViewRight: WebView? = null
    private var splitLayout: android.widget.LinearLayout? = null
    private var focusedPane: BrowserPane = BrowserPane.LEFT
    /** When split is active, which tiles currently have a non-empty HA URL. */
    private var splitLeftPaneActive = true
    private var splitRightPaneActive = true
    /**
     * Left/right HA remote URLs resolve to the same page. Both tiles stay live (the user
     * chose that layout), but the duplicated subscribe_entities stream is batched via
     * steward lite mode instead of running two full-rate sessions.
     */
    private var splitSharedSession = false
    /** One idle-timer refresh per overlay lifetime; see [applyHaRemoteUrls]. */
    private var coldStartIdleRefreshDone = false
    /** Right URL held back until the left pane finishes its HttpCache burst. */
    private var pendingStaggeredRightUrl: String? = null
    /** One-shot per overlay: first dual-pane entry under pressure may hold the right tile. */
    private var splitRightColdStaggerUsed = false
    private var pendingStaggeredRightRunnable: Runnable? = null
    // Non-null only when the gecko engine is active; renders in place of the (background) webView.
    private var engineSurface: EngineSurface? = null
    private var gmApi: GmApi? = null
    /** Split view runs userscripts on both tiles, and each GmApi is bound to one WebView. */
    private var gmApiRight: GmApi? = null
    private lateinit var browserSettingsStore: BrowserSettingsStore
    private lateinit var userScriptManager: UserScriptManager
    private var currentSettings: BrowserSettings = BrowserSettings.DEFAULT
    private var originalUrl: String = ""
    private var originalUrlRight: String = ""
    private var currentPageUrl: String = ""
    private var currentPageUrlRight: String = ""
    private var lastSyncedEntityUrl: String = ""
    private var lastSyncedEntityUrlRight: String = ""
    private var tampermonkeyDialog: android.app.AlertDialog? = null
    private var userAgentDialog: android.app.AlertDialog? = null
    private var remoteUrlDialog: android.app.AlertDialog? = null
    private var scriptListDialog: android.app.AlertDialog? = null
    private var webConsoleCompose: ComposeView? = null
    private val consoleEntries = CopyOnWriteArrayList<BrowserConsoleEntry>()
    private val consoleRevision = MutableStateFlow(0)
    private val consoleEntrySeq = java.util.concurrent.atomic.AtomicLong(0)
    private var isPageLoaded = false
    /** Per-pane main-frame loaded flags. [isPageLoaded] follows the focused pane. */
    private var leftPageLoaded = false
    private var rightPageLoaded = false
    /** Per-pane navigation start; a right-pane cold start must not age out the left. */
    private var leftLoadStartTime = 0L
    private var rightLoadStartTime = 0L
    /**
     * Navigation generation for the focused load path. Paired with [failedLoadGeneration] so
     * [onPageFinished] after a Chromium error page does not clear [awaitingNetworkRetry].
     */
    private var loadGeneration = 0
    private var failedLoadGeneration = -1
    /** True only after a recoverable remote main-frame failure; cleared on a clean finish. */
    @Volatile private var awaitingNetworkRetry = false
    private var networkRetryCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkRestoreRetryAtMs = 0L
    private var gestureDetector: WebViewGestureDetector? = null
    private var navHintView: BrowserNavHintView? = null
    private var readabilityExtractor: ReadabilityExtractor? = null 
    private var haDarkModeBridge: HaDarkModeJsBridge? = null
    private var browserDownloadBridge: BrowserDownloadBridge? = null
    /** Per-pane HA shadow-scroll position (WebView.canScrollVertically lies for Lovelace). */
    private var scrollBridgeLeft: BrowserScrollBridge? = null
    private var scrollBridgeRight: BrowserScrollBridge? = null
    private var browserSettingsObserverJob: Job? = null
    private var appDarkModeObserverJob: Job? = null
    private var sidebarSettingsObserverJob: Job? = null
    private var browserSidebarOverlay: BrowserSidebarOverlayView? = null
    private val sidebarSettingsStore by lazy {
        SidebarSettingsStore(applicationContext.sidebarSettingsStore)
    }
    @Volatile private var containerRebuildInFlight = false
    @Volatile private var suppressHaToAvaSync = false
    @Volatile private var suppressAvaToHaSync = false
    @Volatile private var isForegroundPromoted = false
    @Volatile private var darkSyncEpoch = 0
    @Volatile private var pendingSettingsRebuild = false
    private var htmlFullscreenController: BrowserHtmlFullscreenController? = null
    companion object {
        private const val TAG = "WebViewService"
        /** Every `addJavascriptInterface` name used in [setupWebView], for teardown. */
        private val JS_BRIDGE_NAMES = listOf(
            GmApi.JS_BRIDGE_NAME,
            BrowserScrollBridge.JS_BRIDGE_NAME,
            BrowserDarkModeScripts.JS_BRIDGE_NAME,
            BrowserDownloadBridge.JS_BRIDGE_NAME,
        )
        /** Window for collapsing consecutive rebuild-class setting flips into one teardown. */
        private const val SETTINGS_REBUILD_COALESCE_MS = 400L
        /** Home Assistant `text` entity state hard limit (see HA text platform). */
        private const val HA_TEXT_ENTITY_STATE_MAX = 255
        const val ACTION_RESTART_SELF = "ACTION_RESTART_SELF"
        const val ACTION_END_SELF = "ACTION_END_SELF"
        private const val BROWSER_NOTIFICATION_ID = 4
        /**
         * Pull-to-refresh may start only when DOWN is in this thin top strip.
         * Never use half-screen — that made portrait/landscape refresh from mid-page.
         */
        private const val PULL_REFRESH_TRIGGER_ZONE_DP = 96
        /**
         * Leave this top strip to the platform shade so a bezel peel does not
         * also arm overlay pull-to-refresh.
         */
        private const val PULL_REFRESH_TOP_INSET_DP = 5
        private const val PULL_REFRESH_DRAG_DP = 64
        private const val PULL_REFRESH_EDGE_BLOCK_DP = 32
        /**
         * Finger must rest in the top strip before a downward pull may arm.
         * Stays under the platform long-press (~400ms) so the pause itself does not
         * open a WebView selection. A flick that leaves the slop sooner is ignored.
         */
        private const val PULL_REFRESH_DWELL_MS = 320L
        /** Keep the inset spinner spinning after release, matching refresh-in-progress. */
        private const val PULL_REFRESH_HOLD_MS = 1500L
        /** Debounce before re-asserting immersive once the platform revealed a system bar. */
        private const val IMMERSIVE_REASSERT_DELAY_MS = 500L
        /** Match ScreensaverWebView / Quick Entity overlay fade. */
        private const val FADE_MS = 230L
        /**
         * Fallback deepen for COVERED dormancy if the screensaver never signals ready.
         * Must clear ScreensaverWebView REVEAL_MAX_WAIT (2.5s) + REVEAL_SETTLE (120ms)
         * *plus* its own cold WebView build, otherwise this fires mid-paint — which is
         * exactly what the ready handshake exists to avoid.
         */
        private const val COVERED_DEEPEN_FALLBACK_MS = 6_000L
        /**
         * First dual-pane entry: the right tile waits until the left first-paint
         * settle finishes (shared Chromium HttpCache). If left HTML never lands,
         * start right after this so the tile is not blank forever.
         * Refresh / clear-cache / later HA sync still load both immediately.
         */
        private const val SPLIT_RIGHT_FALLBACK_MS = 12_000L
        /** Hard cap on the cold-load protection window; a page that never finishes must not block idle forever. */
        private const val WARMUP_MAX_MS = 20_000L
        /**
         * Chromium [WebViewClient.onPageFinished] fires when the HTML shell lands, not when
         * Lovelace cards have painted. Holding warm-up / steward stream this long lets the
         * first subscribe_entities burst reach native cards (calendar, gauge) before we
         * start deferring, batching, or covering the pane.
         */
        private const val FIRST_PAINT_SETTLE_MS = 4_000L
        /**
         * After a main-frame network failure, ignore further connectivity flaps for this long
         * before auto-reloading. Offline / intentional no-Wi‑Fi users never set the await flag.
         */
        private const val NETWORK_RESTORE_RETRY_MIN_INTERVAL_MS = 5_000L
        /** Main-frame errors that usually mean the dashboard host is unreachable. */
        private val RECOVERABLE_MAIN_FRAME_ERROR_CODES = setOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO,
        )
        /** L2 → L3: park the entity subscription once the cover/hide outlives a flicker. */
        private const val DORMANT_PARK_AFTER_MS = 60_000L
        /**
         * L3 → L4: a real socket suspend costs a reconnect on wake, so it is reserved for
         * a long *hidden* browser or genuine RED pressure — never for a screensaver cover.
         */
        private const val DORMANT_SUSPEND_AFTER_MS = 10 * 60_000L
        private val NON_WEB_SCHEME =
            Regex(
                "^(about|file|data|blob|javascript|content|intent|chrome|ava-darkmode):",
                RegexOption.IGNORE_CASE,
            )
        /** Memory sampling cadence for the browser pressure tier machine. */
        private const val PRESSURE_SAMPLE_MS = 10_000L
        /** Consecutive calm samples required before stepping the tier down one level. */
        private const val PRESSURE_RELAX_SAMPLES = 3
        /** Short overlay fade used from YELLOW upward (vs [FADE_MS] in GREEN). */
        private const val PRESSURE_FADE_MS = 120L
        /**
         * Soft-hide keeps the WebView warm, then tears it down — tier-graded.
         *
         * The old flat 15s fuse meant every menu trip / app switch destroyed the
         * page and the next show was a full cold reboot (logged by Chromium as a
         * renderer "crash (code -1)", perceived as constant dashboard flicker).
         * Warm wake via the dormancy ladder (park → `resume-from-hide` → unpark,
         * no reload) is strictly better, so healthy tiers keep the page alive for
         * long hides and only real memory pressure shortens the fuse.
         */
        private const val HIDDEN_DESTROY_GREEN_MS = 30 * 60_000L
        private const val HIDDEN_DESTROY_YELLOW_MS = 5 * 60_000L
        private const val HIDDEN_DESTROY_ORANGE_MS = 60_000L
        private const val HIDDEN_DESTROY_RED_MS = 15_000L
        /**
         * After first paint / uncover, keep full CPU so Lovelace cards can finish
         * layout. Adaptive drops after this window (cold start ≈ 15s).
         * High skips settle; low uses a shorter window.
         */
        private const val TOUCH_POWER_DISPLAY_SETTLE_MS = 15_000L
        private const val TOUCH_POWER_DISPLAY_SETTLE_LOW_MS = 4_000L
        /**
         * Finger-up grace before adaptive/low rest. Settle-end skips this and
         * idles immediately so cold start is settle-only.
         */
        private const val TOUCH_POWER_IDLE_MS = 8_000L
        private const val TOUCH_POWER_IDLE_LOW_MS = 3_000L
        /** Low-power only: extra delay after idle before visible [WebView.onPause]. */
        private const val TOUCH_POWER_VISIBLE_PAUSE_MS = 30_000L
        // Log-only: HACS/custom-card noise stays in console. Do not preventDefault —
        // HA Lovelace uses unhandledrejection during bootstrap; swallowing it left
        // cards empty with no recovery path.
        private const val JS_ERROR_GUARD = """
            (function(){
              if (window.__avaErrGuard) return;
              window.__avaErrGuard = true;
              window.addEventListener('error', function(e){
                try {
                  var detail = e && (e.message || e.error);
                  if (!detail && e && e.target && e.target !== window) {
                    // Resource load failure: the event carries no message, only the tag.
                    var t = e.target;
                    detail = 'failed to load <' + (t.tagName || '?').toLowerCase() + '> ' +
                             (t.src || t.href || '');
                  }
                  console.warn('[Ava] page error:', detail || e);
                } catch(_) {}
              }, true);
              window.addEventListener('unhandledrejection', function(e){
                try { console.warn('[Ava] page rejection:', e && e.reason); } catch(_) {}
              }, true);
            })();
        """
        // Process-scoped writer so DataStore updates survive service teardown without CancellationException.
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var instance: WebViewService? = null
        private var pendingRestoreUrl: String? = null
        private var pendingRestoreUrlRight: String? = null
        private var lastKnownPageUrl: String? = null
        private var lastKnownPageUrlRight: String? = null
        private val aiLoadLock = Any()
        private var aiLoadWaiter: CompletableDeferred<String>? = null
        private var isCreating = false
        /** Set by the live instance while any pane's first paint is still pending. */
        @Volatile
        private var isWarmingUp = false
        private val browserOverlayVisibleState = MutableStateFlow(false)

        private const val EXTRA_PANE = "pane"
        private const val EXTRA_LEFT_URL = "leftUrl"
        private const val EXTRA_RIGHT_URL = "rightUrl"
        /** When true, HA browser_refresh reloads every active split pane (not only focused). */
        private const val EXTRA_ALL_ACTIVE_PANES = "allActivePanes"

        /**
         * Gecko engine pack (separate APK) mirrors HA browser_display here so host
         * z-order can yield fullscreen vinyl without a local [WebViewService] window.
         */
        @Volatile
        private var peerBrowserOverlayVisible: Boolean = false

        /** Reactive mirror of in-process browser overlay attach + visibility. */
        fun browserOverlayVisible(): StateFlow<Boolean> = browserOverlayVisibleState.asStateFlow()

        fun isBrowserCreating(): Boolean = isCreating

        /**
         * True while the overlay is building *or* a pane's first paint is still in flight.
         * Covering / pausing inside this window is what leaves a dual-pane dashboard
         * half-rendered, so the screensaver waits it out.
         */
        fun isBrowserWarmingUp(): Boolean = isCreating || (isWarmingUp && instance != null)

        /**
         * Placeholder / internal-signal documents. They must never reach the HA URL entity,
         * the restore cache, or split-URL comparison — writing one back would permanently
         * overwrite the user's configured dashboard address.
         *
         * Includes the legacy `ava-darkmode:` theme-notify navigation (and the https-boosted
         * form Chromium may show as ERR_UNSAFE_PORT).
         */
        fun isPlaceholderPageUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return true
            val lower = trimmed.lowercase()
            return lower.startsWith("about:") ||
                lower.startsWith("http://about:") ||
                lower.startsWith("https://about:") ||
                lower.startsWith("ava-darkmode:") ||
                lower.startsWith("http://ava-darkmode:") ||
                lower.startsWith("https://ava-darkmode:")
        }

        /** Parse `ava-darkmode:1` / `https://ava-darkmode:1/` style theme signals. */
        fun parseAvaDarkModeSignal(url: String): Boolean? {
            val lower = url.trim().lowercase()
            val marker = "ava-darkmode:"
            val idx = lower.indexOf(marker)
            if (idx < 0) return null
            val rest = lower.substring(idx + marker.length).trimStart('/')
            return when (rest.firstOrNull()) {
                '1' -> true
                '0' -> false
                else -> null
            }
        }

        internal fun publishBrowserOverlayVisible(visible: Boolean) {
            if (browserOverlayVisibleState.value != visible) {
                browserOverlayVisibleState.value = visible
                // Soft-hide keeps the WebView (slow destroy). Shade must follow
                // the overlay, not the renderer.
                FullscreenOverlayEscape.sync()
            }
        }

        /** Host-side mirror for gecko-pack overlay visibility (cross-process). */
        fun setPeerBrowserOverlayVisible(context: Context, visible: Boolean) {
            val wasVisible = peerBrowserOverlayVisible
            peerBrowserOverlayVisible = visible
            // Peer hide does not touch in-process WM; restack so music is not left under weather.
            if (wasVisible && !visible) {
                OverlayZOrderCoordinator.reassertForegroundOverlays(context.applicationContext)
            }
        }

        /**
         * True when the in-process overlay is up, or the gecko pack reported visible.
         * Used by [OverlayZOrderCoordinator] to yield / stack relative to music.
         */
        fun isAnyBrowserOverlayActive(): Boolean =
            isBrowserOverlayVisible() || peerBrowserOverlayVisible

        /**
         * Pixel source for other overlays (e.g. chorus-wake frost). The blur itself is
         * not applied on this window.
         */
        fun overlayViewForSnapshot(): View? {
            val view = instance?.containerView ?: return null
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
            return view
        }

        private fun wantsGeckoPackDelegation(context: Context): Boolean {
            val enginePref = BrowserSettingsStore.getCachedEngine(context)
            return BrowserEngine.shouldDelegateToGeckoPack(context, enginePref)
        }

        /** Do not fall back to lite in-process WebView when Gecko pack is the configured engine. */
        private fun logGeckoDelegationFailure(context: Context, action: String, detail: String) {
            if (!wantsGeckoPackDelegation(context)) return
            Log.e(
                TAG,
                "Gecko pack delegation required but $action failed ($detail); " +
                    "enginePref=${BrowserSettingsStore.getCachedEngine(context)} — not using lite WebView fallback"
            )
        }

        /**
         * Crash-safe delegation to the external gecko engine pack. Returns true when the request
         * was handed off to the gecko APK (so the caller should NOT start the local service).
         * Any failure returns false and lets the caller fall back to local rendering.
         */
        private fun delegateToGeckoPack(context: Context, action: String, url: String?): Boolean {
            return try {
                val enginePref = BrowserSettingsStore.getCachedEngine(context)
                if (!BrowserEngine.shouldDelegateToGeckoPack(context, enginePref)) {
                    Log.d(TAG, "Gecko delegate skip: enginePref=$enginePref action=$action")
                    return false
                }
                when (action) {
                    "ACTION_SHOW", "ACTION_SHOW_OR_REFRESH", "ACTION_RESTORE_FROM_SETTINGS" -> {
                        when (action) {
                            "ACTION_SHOW" -> {
                                if (url.isNullOrBlank()) {
                                    Log.w(TAG, "Gecko delegate failed: empty url for $action")
                                    return false
                                }
                                BrowserEngine.showGeckoBrowser(context, url)
                            }
                            "ACTION_SHOW_OR_REFRESH" -> {
                                if (url.isNullOrBlank()) {
                                    Log.w(TAG, "Gecko delegate failed: empty url for $action")
                                    return false
                                }
                                BrowserEngine.navigateGeckoBrowser(context, url)
                            }
                            "ACTION_RESTORE_FROM_SETTINGS" -> BrowserEngine.restoreGeckoBrowser(context)
                            else -> false
                        }
                    }
                    "ACTION_HIDE" -> BrowserEngine.hideGeckoBrowser(context)
                    "ACTION_DESTROY" -> BrowserEngine.destroyGeckoBrowser(context)
                    "ACTION_REFRESH_DARK_MODE" -> BrowserEngine.refreshGeckoDarkMode(context)
                    "ACTION_CLEAR_CACHE" -> BrowserEngine.clearGeckoBrowserCache(context)
                    "ACTION_CLEAR_COOKIES_HISTORY" -> BrowserEngine.clearGeckoCookiesAndHistory(context)
                    "ACTION_FORCE_REFRESH" -> BrowserEngine.forceRefreshGeckoBrowser(context)
                    else -> false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gecko pack delegation failed for $action", e)
                false
            }
        }

        /** Gecko engine pack runs headless; it must use a foreground service or OEM killers stop it. */
        private fun startBrowserService(context: Context, configure: Intent.() -> Unit) {
            if (wantsGeckoPackDelegation(context)) {
                logGeckoDelegationFailure(
                    context,
                    "startBrowserService",
                    "refusing in-process WebView while Gecko pack is the engine",
                )
                return
            }
            // Always applicationContext: Bridge is Theme.NoDisplay and finish()s in onCreate, which
            // races Activity-scoped startForegroundService on MIUI and drops the SHOW intent.
            val app = context.applicationContext
            val intent = Intent(app, WebViewService::class.java).apply(configure)
            if (EngineCapabilities.GECKO_BUNDLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun show(context: Context, url: String, receiptToken: Long = 0L) {
            if (url.isNotBlank() && isAlreadyShowingUrl(url)) return
            // Browser switch ON must force-start gecko overlay: grant via Shizuku/root first.
            if (wantsGeckoPackDelegation(context)) {
                GeckoEngineRootSetup.ensureOverlayForLaunch(context)
            }
            if (delegateToGeckoPack(context, "ACTION_SHOW", url)) return
            if (wantsGeckoPackDelegation(context)) {
                logGeckoDelegationFailure(context, "ACTION_SHOW", "showGeckoBrowser returned false")
                return
            }
            
            startBrowserService(context) {
                action = "ACTION_SHOW"
                putExtra("url", url)
                if (receiptToken != 0L) {
                    putExtra(BrowserEngine.EXTRA_RECEIPT_TOKEN, receiptToken)
                }
            }
        }
        
        // Only flips enableBrowserVisible (the HA browser_display switch). Never touches
        // enableBrowserDisplay: that is a user preference owned by the Browser settings screen,
        // and forcing it back on here would fight an explicit "off" made while in settings.
        private suspend fun syncHaBrowserDisplayVisible(context: Context, visible: Boolean) {
            if (EngineCapabilities.GECKO_BUNDLED) {
                instance?.reportGeckoOverlay(visible, attached = visible, updateHaSwitch = true)
                    ?: BrowserEngine.syncBrowserVisibleToHost(context, visible)
                return
            }
            val store = BrowserSettingsStore(context.applicationContext)
            if (store.get().enableBrowserVisible != visible) {
                store.enableBrowserVisible.set(visible)
            }
        }

        fun restoreIfPending(context: Context) {
            pendingRestoreUrl?.let { url ->
                pendingRestoreUrl = null
                show(context, url)
            }
        }
        
        /** No-op kept for settings back / home safety; browser restore is user-driven via the HA switch. */
        fun exitSettings(@Suppress("UNUSED_PARAMETER") context: Context) {}
        
        fun hide(context: Context) {
            // Hide the gecko pack browser if we're delegating to it; otherwise hide the local one.
            if (delegateToGeckoPack(context, "ACTION_HIDE", null)) return
            startBrowserService(context) {
                action = "ACTION_HIDE"
            }
        }
        
        fun destroy(context: Context) {
            val enginePref = BrowserSettingsStore.getCachedEngine(context)
            if (BrowserEngine.shouldDelegateToGeckoPack(context, enginePref)) {
                GeckoEngineDeathMonitor.suppressBriefly()
            }
            if (delegateToGeckoPack(context, "ACTION_DESTROY", null)) return
            startBrowserService(context) {
                action = "ACTION_DESTROY"
            }
        }

        /** Tear the renderer down and show the same page again. Recovers a frozen overlay. */
        fun relaunch(context: Context) {
            if (wantsGeckoPackDelegation(context)) {
                val url = lastKnownPageUrl.orEmpty()
                GeckoEngineDeathMonitor.suppressBriefly()
                delegateToGeckoPack(context, "ACTION_DESTROY", null)
                if (url.isNotBlank()) {
                    delegateToGeckoPack(context, "ACTION_SHOW", url)
                } else {
                    BrowserEngine.restoreGeckoBrowser(context)
                }
                return
            }
            startBrowserService(context) {
                action = "ACTION_RELAUNCH"
            }
        }
        
        fun updateUrl(context: Context, url: String) {
            startBrowserService(context) {
                action = "ACTION_UPDATE"
                putExtra("url", url)
            }
        }
        
        fun showOrRefresh(context: Context, url: String) {
            showOrRefreshPane(context, url, BrowserPane.LEFT)
        }

        /**
         * Show or refresh a specific left/right tile. Right pane is ignored when split is off
         * or Gecko is active (falls back to left-only navigation).
         */
        fun showOrRefreshPane(context: Context, url: String, pane: BrowserPane) {
            if (isAlreadyShowingUrl(url, pane)) return
            if (wantsGeckoPackDelegation(context)) {
                GeckoEngineRootSetup.ensureOverlayForLaunch(context)
            }
            if (delegateToGeckoPack(context, "ACTION_SHOW_OR_REFRESH", url)) return
            if (wantsGeckoPackDelegation(context)) {
                logGeckoDelegationFailure(context, "ACTION_SHOW_OR_REFRESH", "showGeckoBrowser returned false")
                return
            }
            startBrowserService(context) {
                action = "ACTION_SHOW_OR_REFRESH"
                putExtra("url", url)
                putExtra(EXTRA_PANE, pane.name)
            }
        }

        /**
         * Apply left/right HA remote URLs together for split browser.
         * Blank side is collapsed; both blank hides the overlay.
         */
        fun applyHaRemoteUrls(context: Context, leftUrl: String, rightUrl: String) {
            val left = leftUrl.trim()
            val right = rightUrl.trim()
            if (left.isNotBlank() || right.isNotBlank()) {
                val svc = instance
                if (svc != null && svc.isOverlayVisible() && !svc.isBrowserHidden) {
                    val leftSame = left.isBlank() || isAlreadyShowingUrl(left, BrowserPane.LEFT)
                    val rightSame = right.isBlank() || isAlreadyShowingUrl(right, BrowserPane.RIGHT)
                    if (leftSame && rightSame) return
                }
            }
            if (wantsGeckoPackDelegation(context)) {
                // Gecko pack is single-pane; only left URL is meaningful.
                if (left.isBlank()) {
                    hide(context)
                } else {
                    showOrRefresh(context, left)
                }
                return
            }
            startBrowserService(context) {
                action = "ACTION_APPLY_HA_REMOTE_URLS"
                putExtra(EXTRA_LEFT_URL, leftUrl)
                putExtra(EXTRA_RIGHT_URL, rightUrl)
            }
        }

        fun updateScale(context: Context, scale: Int) {
            startBrowserService(context) {
                action = "ACTION_SET_SCALE"
                putExtra("scale", scale)
            }
        }
        
        
        fun executeCommand(context: Context, command: String) {
            startBrowserService(context) {
                action = "ACTION_COMMAND"
                putExtra("command", command)
            }
        }
        
        fun refreshIfStuck(context: Context) {
            startBrowserService(context) {
                action = "ACTION_REFRESH_IF_STUCK"
            }
        }
        
        /**
         * @param allActivePanes HA [browser_refresh]: hard-reload every pane that currently has a
         * URL. Manual pull-to-refresh stays per-pane via [reloadPane] and must keep this false.
         */
        fun forceRefresh(context: Context, allActivePanes: Boolean = false) {
            if (delegateToGeckoPack(context, "ACTION_FORCE_REFRESH", null)) return
            startBrowserService(context) {
                action = "ACTION_FORCE_REFRESH"
                putExtra(EXTRA_ALL_ACTIVE_PANES, allActivePanes)
            }
        }

        /** True when the in-process browser overlay window is attached and visible. */
        fun isBrowserOverlayVisible(): Boolean = instance?.isOverlayVisible() == true
        fun isBrowserHidden(): Boolean = instance?.isBrowserHidden == true

        /** Already painted this URL — retry is only HA poke, do not remount. */
        private fun isAlreadyShowingUrl(url: String, pane: BrowserPane = BrowserPane.LEFT): Boolean {
            val svc = instance ?: return false
            if (!svc.isOverlayVisible()) return false
            val normalized = svc.normalizeUrl(url.trim())
            if (normalized.isBlank()) return false
            val current = when (pane) {
                BrowserPane.RIGHT -> svc.currentPageUrlRight.ifBlank { svc.originalUrlRight }
                BrowserPane.LEFT -> svc.currentPageUrl.ifBlank { svc.originalUrl }
            }
            val original = when (pane) {
                BrowserPane.RIGHT -> svc.originalUrlRight
                BrowserPane.LEFT -> svc.originalUrl
            }
            return svc.normalizeUrl(current) == normalized || svc.normalizeUrl(original) == normalized
        }

        fun beginPadPageScroll() {
            instance?.evalPadJs(TouchPadPageScroll.jsPin(true))
        }

        fun nudgePageScroll(screenX: Float, screenY: Float, dx: Float, dy: Float): Boolean {
            val svc = instance ?: return false
            if (!svc.isOverlayVisible()) return false
            svc.applyPadPageScroll(screenX, screenY, dx, dy)
            return true
        }

        fun endPadPageScroll() {
            instance?.evalPadJs(TouchPadPageScroll.jsPin(false))
        }

        fun beginPadLift(): Boolean = instance?.beginPadOverlayLift() == true

        fun nudgePadLift(dy: Float): Boolean = instance?.nudgePadOverlayLift(dy) == true

        fun endPadLift(commit: Boolean): Boolean = instance?.endPadOverlayLift(commit) == true

        fun tapAt(screenX: Float, screenY: Float): Boolean =
            instance?.tapPadPointer(screenX, screenY) == true

        fun secondaryTapAt(screenX: Float, screenY: Float): Boolean =
            instance?.secondaryTapPadPointer(screenX, screenY) == true

        fun beginPadContentDrag(screenX: Float, screenY: Float): Boolean =
            instance?.beginPadContentDrag(screenX, screenY) == true

        /** True when the overlay is visible AND the given screen point is inside it. */
        fun containsScreenPoint(x: Float, y: Float): Boolean =
            instance?.containsScreenPointInternal(x, y) == true

        /** Two-finger tap on the pad: same BACK path as a hardware key. */
        fun padGoBack(): Boolean {
            if (!isBrowserOverlayVisible()) return false
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(
                now,
                now,
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_BACK,
                0,
            )
            val up = android.view.KeyEvent(
                now,
                now,
                android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_BACK,
                0,
            )
            dispatchRemoteNavKey(down)
            return dispatchRemoteNavKey(up)
        }

        /**
         * Remote-control MENU key. The system-WebView overlay window is
         * FLAG_NOT_FOCUSABLE, so hardware keys land on [com.example.ava.MainActivity],
         * which forwards here while the overlay is visible. Main-thread only.
         */
        fun toggleBrowserSidebar() {
            instance?.browserSidebarOverlay?.toggleOpen()
        }

        /**
         * Remote-control navigation penetration for the system-WebView overlay.
         * Its window is FLAG_NOT_FOCUSABLE, so D-pad/BACK land on
         * [com.example.ava.MainActivity]; it forwards them here and we inject
         * straight into the focused pane's WebView. View-level dispatch works
         * without window focus: BACK hits the WebView's own key listener
         * (goBack / hide) and arrows scroll or spatially navigate the page.
         * Returns false when the overlay is hidden or Gecko renders (its window
         * is focusable and receives hardware keys directly).
         */
        fun dispatchRemoteNavKey(event: android.view.KeyEvent): Boolean {
            val svc = instance ?: return false
            if (!svc.isOverlayVisible()) return false
            // Open browser sidebar owns the keys (row navigation + BACK closes).
            svc.browserSidebarOverlay?.let { sidebar ->
                if (sidebar.dispatchRemoteKey(event)) return true
            }
            val wv = svc.focusedWebView() ?: return false
            if (!wv.isFocusable) return false // Gecko active: background pane only
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                android.view.KeyEvent.KEYCODE_TAB,
                android.view.KeyEvent.KEYCODE_PAGE_UP,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                android.view.KeyEvent.KEYCODE_BACK,
                -> Unit
                else -> return false
            }
            if (!wv.hasFocus()) wv.requestFocus()
            wv.dispatchKeyEvent(event)
            // Consume even when the WebView reports unhandled: leaking a DPAD or
            // BACK press into the (covered) MainActivity UI navigates it blindly.
            return true
        }

        /**
         * Raise the browser overlay above peers (e.g. fullscreen vinyl without mini FAB).
         * Same remove+add z-order pattern as voice-message / vinyl [bringToFrontIfVisible].
         */
        fun bringToFrontIfVisible() {
            instance?.bringOverlayToFront()
        }

        private fun refreshPublishedOverlayVisible() {
            publishBrowserOverlayVisible(isBrowserOverlayVisible())
        }

        fun refreshDarkMode(context: Context) {
            if (delegateToGeckoPack(context, "ACTION_REFRESH_DARK_MODE", null)) return
            startBrowserService(context) {
                action = "ACTION_REFRESH_DARK_MODE"
            }
        }

        fun clearCache(context: Context) {
            clearBrowserCache(context)
            clearCookiesAndHistory(context)
        }

        /** HTTP/disk cache only — does not remove cookies, history, or Web Storage.
         *  Always hard-reloads the focused pane so a cleared cache takes effect immediately. */
        fun clearBrowserCache(context: Context) {
            if (delegateToGeckoPack(context, "ACTION_CLEAR_CACHE", null)) {
                forceRefresh(context)
                return
            }
            startBrowserService(context) {
                action = "ACTION_CLEAR_CACHE"
            }
        }

        /**
         * Cookies + site storage + navigation history — does not clear the HTTP disk cache.
         * Also clears WebStorage (localStorage / sessionStorage / IndexedDB) and reloads,
         * otherwise logins often appear to survive a cookie-only wipe.
         */
        fun clearCookiesAndHistory(context: Context) {
            // Optional gecko-pack hook; system CookieManager / WebStorage still run below.
            delegateToGeckoPack(context, "ACTION_CLEAR_COOKIES_HISTORY", null)

            try {
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                android.util.Log.w("WebViewService", "WebStorage.deleteAllData failed", e)
            }

            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeSessionCookies(null)
            cookieManager.removeAllCookies { _ ->
                cookieManager.flush()
                startBrowserService(context) {
                    action = "ACTION_CLEAR_COOKIES_HISTORY"
                }
            }
            cookieManager.flush()
        }

        /** Open the overlay User-Agent picker (same TYPE_APPLICATION_OVERLAY style as Tampermonkey). */
        fun showUserAgentPicker(context: Context) {
            instance?.showUserAgentDialog()
        }

        /** Open the overlay remote URL editor (bidirectional HA URL sync). */
        fun showRemoteUrlEditor(context: Context) {
            instance?.showRemoteUrlDialog()
        }

        /** Open the Tampermonkey overlay (same TYPE_APPLICATION_OVERLAY style as UA picker). */
        fun showTampermonkeyPicker(context: Context) {
            instance?.showTampermonkeyDialog()
        }

        /** Open or close the bottom-docked Web Console panel (kiosk-style toggle). */
        fun showWebConsole(context: Context) {
            instance?.toggleWebConsolePanel()
        }

        /** Toggle HA header/sidebar kiosk chrome (browser overlay sidebar). */
        fun toggleHaKioskMode(context: Context) {
            instance?.toggleHaKioskModeInternal()
        }

        @JvmStatic
        fun getCurrentPageText(context: Context): String {
            val active = instance ?: return JSONObject()
                .put("ok", false)
                .put("error", "browser_not_running")
                .toString()
            return active.extractCurrentPageText()
        }

        /**
         * Host tools: show [url] and wait until the left pane finishes the document.
         * Null means timeout or the engine never settled.
         */
        suspend fun loadAndAwait(context: Context, url: String, timeoutMs: Long = 18_000L): String? {
            val waiter = CompletableDeferred<String>()
            synchronized(aiLoadLock) {
                aiLoadWaiter?.cancel()
                aiLoadWaiter = waiter
            }
            showOrRefresh(context, url)
            val settled = try {
                withTimeoutOrNull(timeoutMs) { waiter.await() }
            } finally {
                synchronized(aiLoadLock) {
                    if (aiLoadWaiter === waiter) aiLoadWaiter = null
                }
            }
            if (!settled.isNullOrBlank()) return settled
            return instance?.hostUrl()?.takeIf { it.isNotBlank() }
        }

        suspend fun evaluateJavascript(script: String, timeoutMs: Long = 5_000L): String? {
            val svc = instance ?: return null
            return withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 20_000L)) {
                withContext(Dispatchers.Main) {
                    val wv = svc.hostWebView()
                    if (wv != null) {
                        suspendCancellableCoroutine { cont ->
                            wv.evaluateJavascript(script) { raw ->
                                if (cont.isActive) cont.resume(unwrapJsResult(raw))
                            }
                        }
                    } else {
                        val surf = svc.hostEngineSurface() ?: return@withContext null
                        suspendCancellableCoroutine { cont ->
                            surf.evaluateJavascript(script) { raw ->
                                if (cont.isActive) cont.resume(unwrapJsResult(raw))
                            }
                        }
                    }
                }
            }
        }

        suspend fun extractArticle(): ExtractedContent? {
            val extractor = instance?.getReadabilityExtractor() ?: return null
            return extractor.extract()
        }

        fun hostUrl(): String = instance?.hostUrl().orEmpty()

        fun overlayHomeUrl(): String = instance?.overlayHomeUrl().orEmpty().ifBlank { hostUrl() }

        suspend fun awaitReady(): Boolean = withTimeoutOrNull(18_000L) {
            while (true) {
                if (isBrowserOverlayVisible() && instance?.hostUrl().orEmpty().isNotBlank()) return@withTimeoutOrNull true
                kotlinx.coroutines.delay(40)
            }
            false
        } ?: false

        suspend fun viewport(): String? = evaluateJavascript(PAGE_VIEWPORT_JS)

        suspend fun navigateAndAwait(action: String): String? {
            val waiter = CompletableDeferred<String>()
            synchronized(aiLoadLock) {
                aiLoadWaiter?.cancel()
                aiLoadWaiter = waiter
            }
            val started = withContext(Dispatchers.Main) { instance?.hostNavigate(action) == true }
            if (!started) {
                synchronized(aiLoadLock) { if (aiLoadWaiter === waiter) aiLoadWaiter = null }
                return null
            }
            val settled = try {
                withTimeoutOrNull(18_000L) { waiter.await() }
            } finally {
                synchronized(aiLoadLock) { if (aiLoadWaiter === waiter) aiLoadWaiter = null }
            }
            if (!settled.isNullOrBlank()) return settled
            return instance?.hostUrl()?.takeIf { it.isNotBlank() }
        }

        suspend fun scroll(direction: String, amountPx: Int = 0): JSONObject? {
            val dir = when (direction.trim().lowercase()) {
                "up", "down", "top", "bottom" -> direction.trim().lowercase()
                else -> "down"
            }
            val step = amountPx.coerceIn(0, 20_000)
            val raw = evaluateJavascript(pageScrollJs(dir, step)) ?: return null
            return runCatching { JSONObject(raw) }.getOrNull()
        }

        private fun pageScrollJs(dir: String, step: Int): String = """
            (function(){
              function overflowed(el){
                if(!el||el.nodeType!==1) return false;
                var st; try{st=getComputedStyle(el);}catch(e){return false;}
                if(!st) return false;
                var oy=st.overflowY;
                var root=el===document.scrollingElement||el===document.documentElement||el===document.body;
                if(!(oy==='auto'||oy==='scroll'||oy==='overlay'||root)) return false;
                return el.scrollHeight>el.clientHeight+2;
              }
              function pierce(sel,root){
                var n=(root||document).querySelector(sel);
                return n&&n.shadowRoot?n.shadowRoot:n;
              }
              function haTarget(){
                var n=pierce('home-assistant');
                if(!n) return null;
                n=pierce('home-assistant-main',n)||n;
                n=pierce('ha-drawer',n)||n;
                var root=pierce('hui-root',n)||pierce('ha-panel-lovelace',n)||n;
                var hit=root&&(root.querySelector('#view')||root.querySelector('.content')||root.querySelector('hui-view'));
                if(hit&&overflowed(hit)) return hit;
                return (root&&overflowed(root))?root:null;
              }
              var t=haTarget()||document.scrollingElement||document.documentElement;
              if(!t) return null;
              var h=t.clientHeight||window.innerHeight||600;
              var max=Math.max(0,(t.scrollHeight||0)-h);
              var y=t.scrollTop||0;
              var step=$step>0?$step:Math.floor(h*0.85);
              var next=y, dir="$dir";
              if(dir==='down') next=Math.min(max,y+step);
              else if(dir==='up') next=Math.max(0,y-step);
              else if(dir==='top') next=0;
              else next=max;
              if(t.scrollTo) t.scrollTo(0,next); else t.scrollTop=next;
              var now=t.scrollTop||next;
              try{window.__avaScrollGate&&window.__avaScrollGate.mark&&window.__avaScrollGate.mark();}catch(e){}
              return JSON.stringify({scroll_y:Math.round(now),viewport:Math.round(h),max:Math.round(max),at_top:now<=0,at_bottom:now>=max-1});
            })();
        """.trimIndent()

        private const val PAGE_VIEWPORT_JS = """
            (function(){
              function pierce(sel,root){
                var n=(root||document).querySelector(sel);
                return n&&n.shadowRoot?n.shadowRoot:n;
              }
              function haRoot(){
                var n=pierce('home-assistant');
                if(!n) return document.body;
                n=pierce('home-assistant-main',n)||n;
                n=pierce('ha-drawer',n)||n;
                var root=pierce('hui-root',n)||pierce('ha-panel-lovelace',n)||n;
                return (root&&(root.querySelector('#view')||root.querySelector('.content')||root.querySelector('hui-view')))||root||document.body;
              }
              var start=haRoot(), width=innerWidth, height=innerHeight, cap=1600, out=[], len=0, truncated=false;
              function visible(el){
                if(!el||el.nodeType!==1) return false;
                var s; try{s=getComputedStyle(el);}catch(e){return false;}
                if(!s||s.display==='none'||s.visibility==='hidden'||s.opacity==='0') return false;
                var r=el.getBoundingClientRect();
                return r.width>0&&r.height>0&&r.bottom>0&&r.top<height&&r.right>0&&r.left<width;
              }
              function walk(node){
                if(!node||truncated) return;
                if(node.nodeType===3){
                  var t=String(node.data||'').replace(/\s+/g,' ').trim();
                  if(!t||!node.parentElement||!visible(node.parentElement)) return;
                  var room=cap-len-(out.length?1:0);
                  if(t.length>room){t=t.slice(0,room);truncated=true;}
                  if(t){out.push(t);len+=t.length+(out.length>1?1:0);}
                  return;
                }
                if(node.nodeType!==1) return;
                if(node.shadowRoot) walk(node.shadowRoot);
                var kids=node.childNodes;
                if(!kids) return;
                for(var i=0;i<kids.length&&!truncated;i++) walk(kids[i]);
              }
              try{walk(start);}catch(e){}
              var y=window.scrollY||document.documentElement.scrollTop||0;
              return JSON.stringify({scrollY:Math.round(y),viewportHeight:Math.round(height),text:out.join('\n'),truncated:truncated});
            })();
        """

        fun isInProcessSystemWebView(context: Context): Boolean {
            if (EngineCapabilities.GECKO_BUNDLED) return false
            val engine = BrowserSettingsStore.getCachedEngine(context)
            return !BrowserEngine.shouldDelegateToGeckoPack(context, engine)
        }

        private fun notifyAiLoadSettled(url: String) {
            synchronized(aiLoadLock) {
                aiLoadWaiter?.complete(url)
                aiLoadWaiter = null
            }
        }

        private fun unwrapJsResult(raw: String?): String? {
            if (raw.isNullOrBlank() || raw == "null") return null
            val trimmed = raw.trim()
            return runCatching {
                org.json.JSONTokener(trimmed).nextValue() as? String ?: trimmed
            }.getOrDefault(trimmed)
        }
        
        fun pause(context: Context) {
            startBrowserService(context) {
                action = "ACTION_PAUSE"
            }
        }
        
        fun resume(context: Context) {
            startBrowserService(context) {
                action = "ACTION_RESUME"
            }
        }

        /**
         * Screensaver finished its cold reveal (or hit max-wait). Deepens COVERED dormancy
         * that was kept light so the third WebView can finish loading against shared
         * Chromium cache / render-process budget.
         */
        fun notifyCoverOverlayReady() {
            // No live overlay means nothing to deepen. Never cold-start the service for a
            // no-op — on the gecko flavor that would promote an empty foreground service.
            val active = instance ?: return
            active.mainHandler.post {
                active.deepenCoveredDormant("cover-overlay-ready")
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        // Gecko flavor always uses startForegroundService(); promote immediately so OEM
        // background killers cannot trigger RemoteServiceException before onStartCommand.
        if (EngineCapabilities.GECKO_BUNDLED) {
            promoteForegroundIfNeeded()
        }
        savedStateController.performRestore(null)
        instance = this
        browserSettingsStore = BrowserSettingsStore(this)
        userScriptManager = UserScriptManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        OverlayLayerSplit.register(
            this,
            OverlayLayerSplit.Layer.BROWSER,
            showing = { isBrowserSplitShowing() },
            host = { containerView },
            apply = { frame -> applyLayerSplit(frame) },
        )
        pressureFloor = computePressureFloor()
        pressureTier = pressureFloor
        // Sampling only means something once a renderer exists, so the monitor is
        // started from setupWebView and stopped from cleanupWebView instead of here.
        observeBrowserSettings()
        observeAppDarkMode()
        observeSidebarSettings()
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.init(this)
            lifecycleScope.launch {
                HostSidebarSettingsMirror.pullFromHost(this@WebViewService)
                HostSidebarSettingsBridge.requestFromHost(this@WebViewService)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (webViewRight != null) {
            refreshSplitLayoutOrientation()
        }
    }

    private fun observeSidebarSettings() {
        sidebarSettingsObserverJob?.cancel()
        sidebarSettingsObserverJob = lifecycleScope.launch {
            var initialized = false
            sidebarSettingsStore.getFlow()
                .distinctUntilChanged()
                .collect { settings ->
                    if (!initialized) {
                        initialized = true
                        return@collect
                    }
                    syncBrowserSidebar(settings)
                }
        }
    }

    private suspend fun syncBrowserSidebar(settings: SidebarSettings) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarSettingsMirror.pullFromHost(this)
        }
        withContext(Dispatchers.Main) {
            val container = containerView as? FrameLayout ?: run {
                browserSidebarOverlay = null
                return@withContext
            }
            applyBrowserSidebar(container, settings)
        }
    }

    private fun htmlFullscreen(): BrowserHtmlFullscreenController {
        return htmlFullscreenController ?: BrowserHtmlFullscreenController(
            tag = TAG,
            getHostContainer = { containerView as? FrameLayout },
            setMainContentVisible = { visible ->
                val visibility = if (visible) View.VISIBLE else View.GONE
                when {
                    splitLayout != null -> splitLayout?.visibility = visibility
                    else -> {
                        engineSurface?.view?.visibility = visibility
                        webView?.visibility = visibility
                    }
                }
                browserSidebarOverlay?.visibility = visibility
                if (!visible) navHintView?.dismiss(committed = false)
            }
        ).also { htmlFullscreenController = it }
    }

    private fun applyBrowserSidebar(container: FrameLayout, settings: SidebarSettings) {
        if (!currentSettings.enableBrowserSidebar) {
            teardownBrowserSidebar(container)
            setHaSidebarHandleEdge("none")
            return
        }
        val darkMode = DarkModeManager.getInstance(this).isDarkMode()
        val existing = browserSidebarOverlay
        if (existing != null && existing.parent === container) {
            existing.applySettings(settings, darkMode)
            existing.refreshPanelContent()
            setHaSidebarHandleEdge(
                if (settings.sidebarPosition == SidebarPosition.RIGHT) "right" else "left",
            )
            return
        }
        teardownBrowserSidebar(container)
        browserSidebarOverlay = BrowserSidebarOverlayView.attach(
            container = container,
            lifecycleOwner = this,
            viewModelStoreOwner = this,
            savedStateRegistryOwner = this,
            settings = settings,
            darkMode = darkMode,
            onSettingsClick = ::openSettingsFromBrowser,
            onOpenChanged = { open ->
                if (open) applyOverlayImmersive(container)
            },
        )
        setHaSidebarHandleEdge(
            if (settings.sidebarPosition == SidebarPosition.RIGHT) "right" else "left",
        )
    }

    private fun teardownBrowserSidebar(container: FrameLayout) {
        browserSidebarOverlay?.let { overlay ->
            try {
                container.removeView(overlay)
            } catch (_: Exception) {
            }
        }
        browserSidebarOverlay = null
        setHaSidebarHandleEdge("none")
    }

    private suspend fun rebuildBrowserSidebar(container: FrameLayout) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarSettingsMirror.pullFromHost(this)
        }
        teardownBrowserSidebar(container)
        applyBrowserSidebar(container, sidebarSettingsStore.get())
    }

    private fun resetBrowserSidebarForShow() {
        val container = containerView as? FrameLayout ?: return
        lifecycleScope.launch {
            runCatching { rebuildBrowserSidebar(container) }
                .onFailure { Log.w(TAG, "Failed to rebuild browser sidebar", it) }
        }
    }

    private fun openSettingsFromBrowser(route: String) {
        lifecycleScope.launch {
            // Spring close already runs page frost → clear. Do not arm
            // SettingsGlassEnter or Settings would frost the new page again.
            browserSidebarOverlay?.closeAnimatedAndAwait()
            (containerView as? FrameLayout)?.let { teardownBrowserSidebar(it) }
            OverlayLayerSplit.closeForBrowserNavigation(this@WebViewService)
            syncHaBrowserDisplayVisible(this@WebViewService, false)
            hideWebView()
            launchSettingsActivity(route)
        }
    }

    private fun launchSettingsActivity(route: String) {
        MainNavigationCoordinator.requestNavigation(route)
        if (EngineCapabilities.GECKO_BUNDLED) {
            val intent = Intent().apply {
                setClassName(
                    com.example.ava.webcompat.BrowserEngine.HOST_PACKAGE,
                    "com.example.ava.MainActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("navigate_to", route)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch host settings", e)
            }
        } else {
            val intent = Intent(this, com.example.ava.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("navigate_to", route)
            }
            startActivity(intent)
        }
    }

    /** User dismissed the overlay (back key hide); sync HA browser_display off, no restore on settings exit. */
    private fun dismissBrowserFromUser() {
        lifecycleScope.launch {
            val settings = browserSettingsStore.get()
            if (settings.enableBrowserVisible) {
                syncHaBrowserDisplayVisible(this@WebViewService, false)
            }
            hideWebView()
        }
    }

    private suspend fun attachBrowserSidebar(container: FrameLayout) {
        browserSidebarOverlay = null
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarSettingsMirror.pullFromHost(this)
            HostSidebarSettingsBridge.requestFromHost(this)
        }
        val settings = sidebarSettingsStore.get()
        withContext(Dispatchers.Main) {
            applyBrowserSidebar(container, settings)
        }
    }

    /**
     * Check if WebView is available on this device.
     * WebView may be missing or disabled on some devices.
     */
    private fun isWebViewAvailable(): Boolean {
        return try {
            packageManager.getPackageInfo("com.google.android.webview", 0)
            true
        } catch (e: Exception) {
            try {
                packageManager.getPackageInfo("com.android.webview", 0)
                true
            } catch (e2: Exception) {
                // API 26+ only; Allwinner Android 7.1 has no getCurrentWebViewPackage().
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        android.webkit.WebView.getCurrentWebViewPackage() != null
                    } catch (e3: Throwable) {
                        Log.w(TAG, "WebView not available: ${e3.message}")
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    private fun observeBrowserSettings() {
        browserSettingsObserverJob?.cancel()
        browserSettingsObserverJob = lifecycleScope.launch {
            var initialized = false
            browserSettingsStore.getFlow()
                .distinctUntilChanged()
                .collect { storedSettings ->
                    val nextSettings = applyHostDarkModeOverride(storedSettings)
                    if (!initialized) {
                        initialized = true
                        currentSettings = nextSettings
                        return@collect
                    }
                    handleBrowserSettingsChanged(nextSettings)
                }
        }
    }

    private fun observeAppDarkMode() {
        appDarkModeObserverJob?.cancel()
        appDarkModeObserverJob = lifecycleScope.launch {
            var initialized = false
            DarkModeManager.getInstance(this@WebViewService)
                .darkModeState
                .distinctUntilChanged()
                .collect {
                    if (!initialized) {
                        initialized = true
                        return@collect
                    }
                    if (containerView != null && (webView != null || engineSurface != null)) {
                        refreshDarkModeInternal()
                        browserSidebarOverlay?.applySettings(
                            sidebarSettingsStore.get(),
                            DarkModeManager.getInstance(this@WebViewService).isDarkMode()
                        )
                    }
                }
        }
    }

    private fun handleBrowserSettingsChanged(nextSettings: BrowserSettings) {
        val previousSettings = currentSettings
        if (previousSettings == nextSettings) return

        val hasRenderer = containerView != null && (webView != null || engineSurface != null)
        currentSettings = nextSettings
        if (!hasRenderer) return

        if (requiresContainerRebuild(previousSettings, nextSettings)) {
            if (isCreating || isBrowserHidden || !isOverlayVisible()) {
                pendingSettingsRebuild = true
                Log.d(TAG, "Browser settings changed while hidden/creating; rebuild deferred")
                return
            }
            rebuildActiveBrowserForSettingsChange()
            return
        }

        if (hasRuntimeSettingsChange(previousSettings, nextSettings)) {
            applyRuntimeBrowserSettings(previousSettings, nextSettings)
        }

        if (previousSettings.enableBrowserSidebar != nextSettings.enableBrowserSidebar) {
            lifecycleScope.launch { syncBrowserSidebar(sidebarSettingsStore.get()) }
        }
    }

    private fun requiresContainerRebuild(previous: BrowserSettings, next: BrowserSettings): Boolean {
        return previous.hardwareAcceleration != next.hardwareAcceleration ||
            previous.browserEngine != next.browserEngine ||
            previous.splitViewEnabled != next.splitViewEnabled ||
            // Steward feature switches: destroy + recreate WebViews so install/boot
            // scripts and page state match the new policy (no half-applied JS).
            previous.wsStewardEnabled != next.wsStewardEnabled ||
            previous.wsStewardStreamEnabled != next.wsStewardStreamEnabled ||
            previous.wsStewardChunkedRenderingEnabled != next.wsStewardChunkedRenderingEnabled ||
            previous.wsStewardDormantQuietEnabled != next.wsStewardDormantQuietEnabled ||
            previous.wsStewardEntityTrimEnabled != next.wsStewardEntityTrimEnabled ||
            previous.wsStewardLiteAlwaysEnabled != next.wsStewardLiteAlwaysEnabled ||
            previous.wsStewardFreezeAnimationsEnabled != next.wsStewardFreezeAnimationsEnabled ||
            previous.wsStewardPauseMediaEnabled != next.wsStewardPauseMediaEnabled ||
            // The GM bridge is attached at WebView setup, so flipping this later would either
            // leave userscripts calling a bridge that is not there, or leave the bridge exposed
            // to pages after the user switched userscripts off.
            previous.tampermonkeyEnabled != next.tampermonkeyEnabled
    }

    private fun isSplitViewActive(): Boolean =
        currentSettings.splitViewEnabled &&
            webViewRight != null &&
            BrowserEngine.effectiveEngine(this, currentSettings.browserEngine) != BrowserEngine.GECKO

    private fun focusedWebView(): WebView? =
        if (focusedPane == BrowserPane.RIGHT && webViewRight != null) webViewRight else webView

    internal fun hostWebView(): WebView? = focusedWebView() ?: webView

    internal fun hostEngineSurface(): EngineSurface? = engineSurface

    internal fun hostNavigate(action: String): Boolean = when (action) {
        "back" -> if (engineCanGoBack()) { engineGoBack(); true } else false
        "forward" -> if (engineCanGoForward()) { engineGoForward(); true } else false
        "refresh" -> {
            engineSurface?.reload() ?: focusedWebView()?.reload() ?: webView?.reload()
            true
        }
        else -> false
    }

    private fun evalPadJs(script: String) {
        engineSurface?.evaluateJavascript(script)
        webView?.evaluateJavascript(script, null)
        webViewRight?.evaluateJavascript(script, null)
    }

    private fun applyPadPageScroll(screenX: Float, screenY: Float, dx: Float, dy: Float) {
        val script = TouchPadPageScroll.jsNudge(dx, dy)
        val surface = engineSurface
        if (surface != null) {
            surface.evaluateJavascript(script)
            return
        }
        val target = paneWebViewAt(screenX, screenY) ?: focusedWebView() ?: webView ?: return
        target.evaluateJavascript(script, null)
    }

    private fun paneWebViewAt(x: Float, y: Float): WebView? {
        val loc = IntArray(2)
        fun hit(view: WebView?): Boolean {
            view ?: return false
            if (!view.isAttachedToWindow) return false
            view.getLocationOnScreen(loc)
            return x >= loc[0] && x < loc[0] + view.width &&
                y >= loc[1] && y < loc[1] + view.height
        }
        if (hit(webViewRight)) return webViewRight
        if (hit(webView)) return webView
        return null
    }

    private fun beginPadOverlayLift(): Boolean {
        val container = containerView ?: return false
        if (!isOverlayVisible()) return false
        padLiftActive = true
        container.animate().cancel()
        return true
    }

    private fun nudgePadOverlayLift(dy: Float): Boolean {
        if (!padLiftActive) return false
        val container = containerView ?: return false
        container.translationY = TouchPadMath.overlayLiftTranslation(
            container.translationY,
            dy,
            container.height,
        )
        return true
    }

    private fun endPadOverlayLift(commit: Boolean): Boolean {
        if (!padLiftActive) return false
        padLiftActive = false
        val container = containerView ?: return false
        val stay = commit &&
            TouchPadMath.overlayLiftShouldCommit(container.translationY, container.height)
        if (stay) return true
        container.animate()
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return false
    }

    private fun tapPadPointer(screenX: Float, screenY: Float): Boolean {
        if (!isOverlayVisible()) return false
        val target = paneWebViewAt(screenX, screenY)
            ?: engineSurface?.view
            ?: focusedWebView()
            ?: webView
            ?: return false
        return TouchPadPageScroll.dispatchScreenTap(target, screenX, screenY)
    }

    private fun secondaryTapPadPointer(screenX: Float, screenY: Float): Boolean {
        if (!isOverlayVisible()) return false
        val target = paneWebViewAt(screenX, screenY)
            ?: engineSurface?.view
            ?: focusedWebView()
            ?: webView
            ?: return false
        return TouchPadPageScroll.dispatchScreenSecondaryTap(target, screenX, screenY)
    }

    private fun beginPadContentDrag(screenX: Float, screenY: Float): Boolean {
        if (!isOverlayVisible()) return false
        if (!containsScreenPointInternal(screenX, screenY)) return false
        val target = paneWebViewAt(screenX, screenY)
            ?: engineSurface?.view
            ?: focusedWebView()
            ?: webView
            ?: return false
        return TouchPadPageScroll.beginViewDrag(target, screenX, screenY)
    }

    internal fun hostUrl(): String = currentPageUrl.ifBlank { lastKnownPageUrl.orEmpty() }

    internal fun overlayHomeUrl(): String =
        originalUrl.ifBlank { currentPageUrl }.ifBlank { lastKnownPageUrl.orEmpty() }

    private fun webViewFor(pane: BrowserPane): WebView? =
        if (pane == BrowserPane.RIGHT) webViewRight else webView

    private fun setFocusedPane(pane: BrowserPane) {
        if (webViewRight == null && pane == BrowserPane.RIGHT) return
        if (focusedPane == pane) return
        focusedPane = pane
        isPageLoaded = if (pane == BrowserPane.RIGHT && webViewRight != null) {
            rightPageLoaded
        } else {
            leftPageLoaded
        }
        // Focus moves the "full rate" budget onto the tile the user is reading.
        syncChunkedRendering()
        syncLiteMode()
    }

    /** Portrait → stacked (top/bottom); landscape → side-by-side (left/right). */
    private fun isPortraitBrowserSplit(): Boolean {
        val host = containerView
        if (host != null && OverlayLayerSplit.isPaneView(host) && host.width > 1 && host.height > 1) {
            return host.height >= host.width
        }
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    /** Left/top and right/bottom weights from [BrowserSettings.splitViewRatioLeft]/[splitViewRatioRight]. */
    private fun splitViewWeights(): Pair<Float, Float> {
        val left = currentSettings.splitViewRatioLeft.coerceIn(1, 9)
        val right = currentSettings.splitViewRatioRight.coerceIn(1, 9)
        return left.toFloat() to right.toFloat()
    }

    private fun splitPaneLayoutParams(pane: BrowserPane): android.widget.LinearLayout.LayoutParams {
        val (leftW, rightW) = splitViewWeights()
        val weight = if (pane == BrowserPane.LEFT) leftW else rightW
        return if (isPortraitBrowserSplit()) {
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                weight,
            )
        } else {
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                weight,
            )
        }
    }

    private fun applySplitLayoutOrientation(layout: android.widget.LinearLayout) {
        layout.orientation =
            if (isPortraitBrowserSplit()) android.widget.LinearLayout.VERTICAL
            else android.widget.LinearLayout.HORIZONTAL
    }

    private fun refreshSplitLayoutOrientation() {
        val layout = splitLayout ?: return
        if (webView == null || webViewRight == null) return
        applySplitLayoutOrientation(layout)
        applySplitPaneVisibility(splitLeftPaneActive, splitRightPaneActive)
    }

    private fun splitPaneHostView(pane: BrowserPane): View? =
        when (pane) {
            BrowserPane.LEFT -> webView
            BrowserPane.RIGHT -> webViewRight
        }

    private fun fullSplitPaneLayoutParams(): android.widget.LinearLayout.LayoutParams =
        if (isPortraitBrowserSplit()) {
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
        } else {
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
        }

    /** Collapse blank HA URL panes; remaining pane fills the split layout. */
    private fun applySplitPaneVisibility(leftActive: Boolean, rightActive: Boolean) {
        if (!isSplitViewActive()) {
            splitLeftPaneActive = leftActive
            splitRightPaneActive = false
            return
        }
        if (!leftActive && !rightActive) {
            splitLeftPaneActive = false
            splitRightPaneActive = false
            hideWebView()
            return
        }
        splitLeftPaneActive = leftActive
        splitRightPaneActive = rightActive
        val leftHost = splitPaneHostView(BrowserPane.LEFT) ?: return
        val rightHost = splitPaneHostView(BrowserPane.RIGHT) ?: return
        when {
            leftActive && rightActive -> {
                leftHost.visibility = View.VISIBLE
                rightHost.visibility = View.VISIBLE
                leftHost.layoutParams = splitPaneLayoutParams(BrowserPane.LEFT)
                rightHost.layoutParams = splitPaneLayoutParams(BrowserPane.RIGHT)
            }
            leftActive -> {
                leftHost.visibility = View.VISIBLE
                rightHost.visibility = View.GONE
                leftHost.layoutParams = fullSplitPaneLayoutParams()
                setFocusedPane(BrowserPane.LEFT)
            }
            else -> {
                leftHost.visibility = View.GONE
                rightHost.visibility = View.VISIBLE
                rightHost.layoutParams = fullSplitPaneLayoutParams()
                setFocusedPane(BrowserPane.RIGHT)
            }
        }
        splitLayout?.requestLayout()
    }

    private fun blankAndPausePane(pane: BrowserPane) {
        val wv = webViewFor(pane) ?: return
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.onPause()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to blank pane $pane", e)
        }
        when (pane) {
            BrowserPane.LEFT -> {
                originalUrl = ""
                currentPageUrl = ""
                lastKnownPageUrl = null
                lastSyncedEntityUrl = ""
                leftPageLoaded = false
            }
            BrowserPane.RIGHT -> {
                originalUrlRight = ""
                currentPageUrlRight = ""
                lastKnownPageUrlRight = null
                lastSyncedEntityUrlRight = ""
                rightPageLoaded = false
            }
        }
        setPaneLoaded(focusedPane, if (focusedPane == BrowserPane.RIGHT) rightPageLoaded else leftPageLoaded)
    }

    /** Compare split URLs after normalize + HTTP-boost unmap (trailing slash ignored). */
    private fun canonicalSplitUrl(url: String): String {
        if (isPlaceholderPageUrl(url)) return ""
        val normalized = normalizeUrl(url.trim())
        if (normalized.isBlank()) return ""
        return SecureContextProxy.instance.unmapUrl(normalized).trimEnd('/')
    }

    private fun isSharedSplitSession(leftUrl: String, rightUrl: String): Boolean {
        val left = canonicalSplitUrl(leftUrl)
        val right = canonicalSplitUrl(rightUrl)
        return left.isNotBlank() && left == right
    }

    private fun cancelStaggeredRightLoad() {
        pendingStaggeredRightUrl = null
        pendingStaggeredRightRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStaggeredRightRunnable = null
    }

    private fun shouldStaggerRightForFirstEntry(): Boolean = !splitRightColdStaggerUsed

    private fun markRightColdStaggerConsumed() {
        splitRightColdStaggerUsed = true
    }

    /** Refresh / clear-cache / force-reload must not wait out a first-entry hold. */
    private fun abortRightStaggerForReload() {
        markRightColdStaggerConsumed()
        if (pendingStaggeredRightUrl != null) {
            flushStaggeredRightLoad("reload-abort")
        }
    }

    /**
     * Hold the right tile on the first dual-pane entry until the left first-paint
     * settle completes. Refresh, clear-cache, and later HA URL syncs load both
     * immediately.
     *
     * The waiting pane stays laid out and resumed. `View.GONE` + [WebView.onPause] is what
     * freezes Chromium's overlay compositor (null GraphicBuffer / ANB) when the tile
     * later comes back.
     */
    private fun scheduleStaggeredRightLoad(rightUrl: String) {
        val normalized = normalizeUrl(rightUrl)
        if (normalized.isBlank() || webViewRight == null) return
        val alreadyLive =
            rightPageLoaded &&
                !rightPaneWarming &&
                canonicalSplitUrl(currentPageUrlRight.ifBlank { originalUrlRight }) ==
                canonicalSplitUrl(normalized)
        if (alreadyLive) {
            cancelStaggeredRightLoad()
            markRightColdStaggerConsumed()
            originalUrlRight = normalized
            webViewRight?.onResume()
            Log.d(TAG, "Split right already live, skip stagger: $normalized")
            return
        }
        // Same destination already waiting: keep the original timer. HA URL
        // reconcile during left first-paint used to cancel+re-arm forever.
        val alreadyPending =
            pendingStaggeredRightUrl != null &&
                canonicalSplitUrl(pendingStaggeredRightUrl.orEmpty()) ==
                canonicalSplitUrl(normalized)
        if (alreadyPending) {
            originalUrlRight = normalized
            return
        }
        cancelStaggeredRightLoad()
        pendingStaggeredRightUrl = normalized
        originalUrlRight = normalized
        if (!shouldStaggerRightForFirstEntry()) {
            flushStaggeredRightLoad("immediate")
            return
        }
        if (!leftPaneWarming && leftPageLoaded) {
            flushStaggeredRightLoad("left-already-settled")
            return
        }
        val currentRight = canonicalSplitUrl(currentPageUrlRight)
        if (currentRight.isNotBlank() && currentRight != canonicalSplitUrl(normalized)) {
            try {
                webViewRight?.stopLoading()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop right pane before stagger", e)
            }
        }
        val runnable = Runnable {
            pendingStaggeredRightRunnable = null
            flushStaggeredRightLoad("left-exclusive-fallback-${SPLIT_RIGHT_FALLBACK_MS}ms")
        }
        pendingStaggeredRightRunnable = runnable
        mainHandler.postDelayed(runnable, SPLIT_RIGHT_FALLBACK_MS)
        Log.d(TAG, "Split right held until left first-paint (fallback ${SPLIT_RIGHT_FALLBACK_MS}ms): $normalized")
    }

    private fun flushStaggeredRightLoad(reason: String) {
        val url = pendingStaggeredRightUrl ?: return
        pendingStaggeredRightUrl = null
        pendingStaggeredRightRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStaggeredRightRunnable = null
        if (webViewRight == null) return
        // Do not clear [leftPaneWarming]: left cards may still be reading the
        // shared HttpCache. Keep the warm-up window until left's own settle.
        markRightColdStaggerConsumed()
        rightPaneWarming = true
        refreshWarmupState()
        Log.d(TAG, "Split staggered right load ($reason): $url")
        // Size the tile before any resume/load so Chromium has a real Surface.
        applySplitPaneVisibility(
            originalUrl.isNotBlank() || currentPageUrl.isNotBlank(),
            true,
        )
        navigatePaneToUrl(BrowserPane.RIGHT, url, claimFocus = false)
        // A duplicated address now has two live sessions — batch their entity delivery.
        syncLiteMode()
    }

    /**
     * @param claimFocus HA URL reconcile and stagger must not steal the pane the user
     *   is reading; touch / collapse already set focus.
     */
    private fun navigatePaneToUrl(pane: BrowserPane, url: String, claimFocus: Boolean = false) {
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isBlank()) return
        webViewFor(pane)?.onResume()
        if (claimFocus) setFocusedPane(pane)
        val current = when (pane) {
            BrowserPane.LEFT -> currentPageUrl
            BrowserPane.RIGHT -> currentPageUrlRight
        }.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
        when (pane) {
            BrowserPane.LEFT -> originalUrl = normalizedUrl
            BrowserPane.RIGHT -> originalUrlRight = normalizedUrl
        }
        if (current == normalizedUrl ||
            canonicalSplitUrl(current.orEmpty()) == canonicalSplitUrl(normalizedUrl)
        ) {
            // Already on this dashboard. Reloading on every HA URL sync is what
            // fights a live dual-pane.
            return
        }
        loadUrl(normalizedUrl, pane)
    }

    private fun setPaneLoaded(pane: BrowserPane, loaded: Boolean) {
        if (pane == BrowserPane.RIGHT) {
            rightPageLoaded = loaded
        } else {
            leftPageLoaded = loaded
        }
        isPageLoaded = if (focusedPane == BrowserPane.RIGHT && webViewRight != null) {
            rightPageLoaded
        } else {
            leftPageLoaded
        }
    }

    private fun resetPaneLoadedFlags() {
        leftPageLoaded = false
        rightPageLoaded = false
        isPageLoaded = false
        leftLoadStartTime = 0L
        rightLoadStartTime = 0L
    }

    private fun markPaneLoadStart(pane: BrowserPane, at: Long = System.currentTimeMillis()) {
        if (pane == BrowserPane.RIGHT) rightLoadStartTime = at else leftLoadStartTime = at
    }

    private fun paneLoadStart(pane: BrowserPane): Long =
        if (pane == BrowserPane.RIGHT) rightLoadStartTime else leftLoadStartTime

    /**
     * Sync split overlay to the latest left/right HA remote URLs.
     * Empty side collapses; both empty hides the floating browser.
     * Two configured tiles always stagger — the right cold start waits for the left to
     * settle. Identical addresses still get both tiles (the user picked that layout), but
     * run the steward in lite mode so the duplicated subscription is batched.
     */
    private fun applyHaRemoteUrls(leftUrl: String, rightUrl: String) {
        val splitWanted =
            currentSettings.splitViewEnabled &&
                BrowserEngine.effectiveEngine(this, currentSettings.browserEngine) != BrowserEngine.GECKO
        // Drop internal signals (ava-darkmode:…) that may have been written back to HA.
        val left = leftUrl.trim().takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
        val right = if (splitWanted) {
            rightUrl.trim().takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
        } else {
            ""
        }

        if (left.isBlank() && right.isBlank()) {
            cancelStaggeredRightLoad()
            splitSharedSession = false
            hideWebView()
            return
        }

        if (!splitWanted) {
            cancelStaggeredRightLoad()
            splitSharedSession = false
            if (left.isNotBlank()) {
                showOrRefreshWebView(left, BrowserPane.LEFT)
            } else {
                hideWebView()
            }
            return
        }

        if (isCreating) {
            pendingRestoreUrl = left.takeIf { it.isNotBlank() } ?: ""
            pendingRestoreUrlRight = right.takeIf { it.isNotBlank() }
            latchShowDuringCreate(left.ifBlank { right })
            return
        }

        if (containerView == null || (webView == null && engineSurface == null)) {
            pendingRestoreUrl = left.takeIf { it.isNotBlank() } ?: ""
            pendingRestoreUrlRight = right.takeIf { it.isNotBlank() }
            showWebView(left.ifBlank { right })
            return
        }

        if (isBrowserHidden) {
            pendingRestoreUrl = left.takeIf { it.isNotBlank() } ?: ""
            pendingRestoreUrlRight = right.takeIf { it.isNotBlank() }
            if (pendingSettingsRebuild) {
                // A rebuild-class setting (split layout, engine, pull-to-refresh...) changed while
                // hidden. Resuming would bring the stale container back with the new URLs in it and
                // leave the rebuild pending forever, so take showWebView's rebuild path instead.
                pendingSettingsRebuild = false
                cleanupWebView("applyHaRemoteUrls-settings-rebuild-from-hidden")
                WebViewService.show(this, left.ifBlank { right })
                return
            }
            // Avoid seeding the left tile with the right URL when left is blank.
            resumeBrowserFromHide(
                url = left.ifBlank { right },
                navigate = left.isNotBlank(),
            )
        }

        val shared = left.isNotBlank() && right.isNotBlank() && isSharedSplitSession(left, right)

        if (left.isNotBlank()) {
            navigatePaneToUrl(BrowserPane.LEFT, left)
        } else {
            blankAndPausePane(BrowserPane.LEFT)
        }

        splitSharedSession = shared
        if (right.isBlank()) {
            cancelStaggeredRightLoad()
            blankAndPausePane(BrowserPane.RIGHT)
        } else {
            val leftCanonical = canonicalSplitUrl(left)
            val leftShowing = canonicalSplitUrl(currentPageUrl.ifBlank { originalUrl })
            // Hold the right tile only on first-entry pressure. Refresh / HA
            // URL sync after that must load both sides immediately.
            val holdRight =
                pendingStaggeredRightUrl != null ||
                    (left.isNotBlank() &&
                        shouldStaggerRightForFirstEntry() &&
                        (leftPaneWarming || !leftPageLoaded || leftCanonical != leftShowing))
            if (holdRight) {
                scheduleStaggeredRightLoad(right)
            } else {
                cancelStaggeredRightLoad()
                markRightColdStaggerConsumed()
                navigatePaneToUrl(BrowserPane.RIGHT, right)
            }
        }

        applySplitPaneVisibility(left.isNotBlank(), right.isNotBlank())
        syncLiteMode()
        // Only the first reconcile of this overlay counts as activity (a cold host start
        // must not be covered immediately). Later HA URL churn must not keep resetting
        // idle, or the screensaver would never fire again.
        if (!coldStartIdleRefreshDone) {
            coldStartIdleRefreshDone = true
            ScreensaverController.onUserActivity()
        }
    }

    /**
     * A pane began a real navigation — open (or extend) the cold-load protection window.
     * Driven from [WebViewClient.onPageStarted] so it covers reload() and SPA entry points,
     * not just the [loadUrl] funnel.
     */
    private fun markPaneWarming(pane: BrowserPane) {
        when (pane) {
            BrowserPane.LEFT -> leftPaneWarming = true
            BrowserPane.RIGHT -> rightPaneWarming = true
        }
        refreshWarmupState()
    }

    private fun markPaneSettled(pane: BrowserPane) {
        when (pane) {
            BrowserPane.LEFT -> {
                leftPaneWarming = false
                refreshWarmupState()
                // Exclusive cache window is over — start the waiting right tile.
                if (pendingStaggeredRightUrl != null) {
                    flushStaggeredRightLoad("left-first-paint")
                }
            }
            BrowserPane.RIGHT -> {
                rightPaneWarming = false
                refreshWarmupState()
            }
        }
    }

    private fun firstPaintStewardRunnable(pane: BrowserPane): Runnable? =
        if (pane == BrowserPane.RIGHT) pendingFirstPaintStewardRight else pendingFirstPaintStewardLeft

    private fun firstPaintSettleRunnable(pane: BrowserPane): Runnable? =
        if (pane == BrowserPane.RIGHT) pendingFirstPaintSettleRight else pendingFirstPaintSettleLeft

    private fun setFirstPaintStewardRunnable(pane: BrowserPane, runnable: Runnable?) {
        if (pane == BrowserPane.RIGHT) pendingFirstPaintStewardRight = runnable
        else pendingFirstPaintStewardLeft = runnable
    }

    private fun setFirstPaintSettleRunnable(pane: BrowserPane, runnable: Runnable?) {
        if (pane == BrowserPane.RIGHT) pendingFirstPaintSettleRight = runnable
        else pendingFirstPaintSettleLeft = runnable
    }

    private fun cancelFirstPaintFollowup(pane: BrowserPane) {
        firstPaintStewardRunnable(pane)?.let { mainHandler.removeCallbacks(it) }
        firstPaintSettleRunnable(pane)?.let { mainHandler.removeCallbacks(it) }
        setFirstPaintStewardRunnable(pane, null)
        setFirstPaintSettleRunnable(pane, null)
    }

    private fun cancelAllFirstPaintFollowup() {
        cancelFirstPaintFollowup(BrowserPane.LEFT)
        cancelFirstPaintFollowup(BrowserPane.RIGHT)
    }

    /**
     * Hold [isWarmingUp] past HTML [WebViewClient.onPageFinished] so COVERED
     * onPause / lite cannot land on Lovelace's first card layout.
     */
    private fun schedulePaneFirstPaintSettled(pane: BrowserPane) {
        firstPaintSettleRunnable(pane)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            setFirstPaintSettleRunnable(pane, null)
            markPaneSettled(pane)
        }
        setFirstPaintSettleRunnable(pane, runnable)
        mainHandler.postDelayed(runnable, FIRST_PAINT_SETTLE_MS)
    }

    /**
     * Arm stream / trim / lite only after the first-paint window. The steward
     * object itself is installed immediately so late attach still wraps HA.
     */
    private fun scheduleFirstPaintSteward(pane: BrowserPane) {
        firstPaintStewardRunnable(pane)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            setFirstPaintStewardRunnable(pane, null)
            val wv = webViewFor(pane) ?: return@Runnable
            if (!isStewardPageRuntimeNeeded()) return@Runnable
            // Do not eval streamOffJs when stream is already off — that bundle is
            // STEWARD_INSTALL and would re-enter the page mid late-card mount.
            if (currentSettings.wsStewardStreamEnabled) {
                wv.evaluateJavascript(BrowserWsStewardScripts.streamOnJs, null)
                applyWsStewardOpaqueCardLines(
                    currentSettings.wsStewardOpaqueCardLines,
                    currentSettings.wsStewardOpaqueCardLinesCustom,
                )
                applyWsStewardEntityTrimEnabled(
                    currentSettings.wsStewardEntityTrimEnabled,
                )
            }
            reassertStewardAfterLoad(pane)
        }
        setFirstPaintStewardRunnable(pane, runnable)
        mainHandler.postDelayed(runnable, FIRST_PAINT_SETTLE_MS)
    }

    private fun refreshWarmupState() {
        val warming = leftPaneWarming || rightPaneWarming || pendingStaggeredRightUrl != null
        if (warming) {
            if (!isWarmingUp) {
                isWarmingUp = true
                Log.d(TAG, "Browser warm-up window opened")
            }
            armWarmupWatchdog()
            return
        }
        cancelWarmupWatchdog()
        if (isWarmingUp) {
            isWarmingUp = false
            Log.d(TAG, "Browser warm-up window closed")
            if (!isBrowserDormant()) {
                beginDisplaySettle("warmup-closed")
            }
        }
        finishPendingCoveredAfterWarmup()
    }

    /** Releases the protection window unconditionally so a stuck page cannot block idle. */
    private fun armWarmupWatchdog() {
        if (warmupWatchdogRunnable != null) return
        val runnable = Runnable {
            warmupWatchdogRunnable = null
            if (!isWarmingUp) return@Runnable
            Log.w(TAG, "Browser warm-up watchdog fired after ${WARMUP_MAX_MS}ms")
            leftPaneWarming = false
            rightPaneWarming = false
            isWarmingUp = false
            finishPendingCoveredAfterWarmup()
            if (!isBrowserDormant()) {
                beginDisplaySettle("warmup-watchdog")
            }
        }
        warmupWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WARMUP_MAX_MS)
    }

    private fun cancelWarmupWatchdog() {
        warmupWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        warmupWatchdogRunnable = null
    }

    /** Screensaver asked to cover mid-build / mid-load; apply it now that the panes painted. */
    private fun finishPendingCoveredAfterWarmup() {
        if (!pendingCoveredWhileWarming) return
        if (isCreating || isWarmingUp) return
        pendingCoveredWhileWarming = false
        if (ScreensaverController.isScreensaverVisible()) {
            enterCoveredDormant("deferred-after-warmup")
        }
    }

    /** Thin top strip only (dp), independent of portrait/landscape pane height. */
    private fun pullRefreshTriggerZonePx(): Int =
        (resources.displayMetrics.density * PULL_REFRESH_TRIGGER_ZONE_DP).toInt().coerceAtLeast(1)

    private fun pullRefreshDragPx(): Float =
        resources.displayMetrics.density * PULL_REFRESH_DRAG_DP

    private fun pullRefreshEdgeBlockPx(): Float =
        resources.displayMetrics.density * PULL_REFRESH_EDGE_BLOCK_DP

    private fun scrollBridgeFor(pane: BrowserPane): BrowserScrollBridge? =
        if (pane == BrowserPane.LEFT) scrollBridgeLeft else scrollBridgeRight

    /** True when WebView or HA shadow scroller can scroll up (not at top). */
    private fun paneCanScrollUp(wv: WebView, pane: BrowserPane): Boolean =
        wv.canScrollVertically(-1) || (scrollBridgeFor(pane)?.haCanScrollUp() == true)

    private fun paneAtTop(pane: BrowserPane): Boolean {
        engineSurface?.let { return !it.canScrollVerticallyUp() }
        val wv = webViewFor(pane) ?: return true
        return !paneCanScrollUp(wv, pane)
    }

    private fun paneAtScreenPoint(rawX: Float, rawY: Float): BrowserPane {
        if (!isSplitViewActive()) return BrowserPane.LEFT
        val right = splitPaneHostView(BrowserPane.RIGHT) ?: return BrowserPane.LEFT
        if (right.visibility == View.VISIBLE && pointOnScreenInView(right, rawX, rawY)) {
            return BrowserPane.RIGHT
        }
        return BrowserPane.LEFT
    }

    private fun pointOnScreenInView(view: View, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX < loc[0] + view.width &&
            rawY >= loc[1] && rawY < loc[1] + view.height
    }

    private fun pullRefreshTopInsetPx(): Float =
        resources.displayMetrics.density * PULL_REFRESH_TOP_INSET_DP

    /** DOWN in the top 96dp of this pane, starting below the bezel so shade peel wins. */
    private fun isInPanePullStrip(pane: BrowserPane, rawY: Float): Boolean {
        val view = if (isSplitViewActive()) {
            splitPaneHostView(pane)
        } else {
            containerView
        } ?: return false
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val localY = rawY - loc[1]
        val inset = pullRefreshTopInsetPx()
        return localY >= inset && localY <= inset + pullRefreshTriggerZonePx()
    }

    private fun isPullRefreshEdgeBlocked(event: MotionEvent, pane: BrowserPane): Boolean {
        if (!currentSettings.gestureNavigationEnabled) return false
        if (event.x > pullRefreshEdgeBlockPx()) return false
        return !isSplitViewActive() ||
            (!isPortraitBrowserSplit() && pane == BrowserPane.LEFT)
    }

    /**
     * Pull-to-refresh at the overlay container — same layer as back/forward.
     * The finger must rest in the top strip before a downward pull may arm.
     * A flick that leaves the touch slop during that pause stays with the page.
     * Returns true when this event is owned here and must not reach the page.
     */
    private fun dispatchOverlayPullRefresh(event: MotionEvent): Boolean {
        if (!currentSettings.pullRefreshEnabled || shouldBlockPageDrag()) {
            resetOverlayPullRefresh(dismiss = pullRefreshWatching || pullRefreshActive)
            return false
        }
        if (htmlFullscreenController?.isActive() == true ||
            browserSidebarOverlay?.isSidebarOpen() == true
        ) {
            resetOverlayPullRefresh(dismiss = true)
            return false
        }
        if (navHintView?.isHoldingRefresh == true) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetOverlayPullRefresh(dismiss = false)
                if (browserSidebarOverlay?.isPointInHandleZone(event.x, event.y) == true) {
                    return false
                }
                val pane = paneAtScreenPoint(event.rawX, event.rawY)
                if (!paneAtTop(pane) ||
                    !isInPanePullStrip(pane, event.rawY) ||
                    isPullRefreshEdgeBlocked(event, pane)
                ) {
                    return false
                }
                pullRefreshWatching = true
                pullRefreshDwellReady = false
                pullRefreshDownAtMs = event.eventTime
                pullRefreshStartX = event.x
                pullRefreshStartY = event.y
                pullRefreshPane = pane
                setFocusedPane(pane)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (pullRefreshActive) {
                    pulseOverlayPullRefresh(event.y - pullRefreshStartY)
                    return true
                }
                if (!pullRefreshWatching) return false
                val dy = event.y - pullRefreshStartY
                val dx = event.x - pullRefreshStartX
                val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                if (!pullRefreshDwellReady) {
                    when (
                        pullRefreshDwell(
                            elapsedMs = event.eventTime - pullRefreshDownAtMs,
                            dx = dx,
                            dy = dy,
                            slopPx = slop,
                            dwellMs = PULL_REFRESH_DWELL_MS,
                        )
                    ) {
                        PullRefreshDwell.WAITING -> return false
                        PullRefreshDwell.REJECTED -> {
                            pullRefreshWatching = false
                            return false
                        }
                        PullRefreshDwell.RESTED -> {
                            pullRefreshDwellReady = true
                            return false
                        }
                        PullRefreshDwell.PULL -> pullRefreshDwellReady = true
                    }
                }
                if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                    pullRefreshWatching = false
                    pullRefreshDwellReady = false
                    return false
                }
                if (dy > slop && dy > kotlin.math.abs(dx)) {
                    pullRefreshActive = true
                    pulseOverlayPullRefresh(dy)
                    return true
                }
                if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                    pullRefreshWatching = false
                    pullRefreshDwellReady = false
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (!pullRefreshActive) {
                    resetOverlayPullRefresh(dismiss = true)
                    return false
                }
                val committed = (event.y - pullRefreshStartY) >= pullRefreshDragPx()
                val pane = pullRefreshPane
                resetOverlayPullRefresh(dismiss = false)
                if (committed) {
                    setFocusedPane(pane)
                    holdOverlayPullRefresh()
                    reloadPane(pane)
                } else {
                    navHintView?.dismiss(committed = false)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = pullRefreshActive
                resetOverlayPullRefresh(dismiss = true)
                return consumed
            }
        }
        return pullRefreshActive
    }

    private fun pulseOverlayPullRefresh(dy: Float) {
        val progress = (dy / pullRefreshDragPx().coerceAtLeast(1f)).coerceIn(0f, 1f)
        if (progress <= 0f) return
        val container = containerView as? FrameLayout ?: return
        ensureNavHint(container).show(BrowserNavHintKind.REFRESH, progress)
    }

    private fun holdOverlayPullRefresh() {
        val container = containerView as? FrameLayout ?: return
        ensureNavHint(container).holdRefreshing()
        pullRefreshHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pullRefreshHoldRunnable = null
            if (navHintView?.isHoldingRefresh == true) {
                navHintView?.dismiss(committed = false)
            }
        }
        pullRefreshHoldRunnable = runnable
        mainHandler.postDelayed(runnable, PULL_REFRESH_HOLD_MS)
    }

    private fun resetOverlayPullRefresh(dismiss: Boolean) {
        pullRefreshWatching = false
        pullRefreshDwellReady = false
        pullRefreshActive = false
        if (dismiss) {
            pullRefreshHoldRunnable?.let { mainHandler.removeCallbacks(it) }
            pullRefreshHoldRunnable = null
            navHintView?.dismiss(committed = false)
        }
    }

    private fun hasRuntimeSettingsChange(previous: BrowserSettings, next: BrowserSettings): Boolean {
        return previous.initialScale != next.initialScale ||
            previous.fontSize != next.fontSize ||
            previous.touchEnabled != next.touchEnabled ||
            previous.dragEnabled != next.dragEnabled ||
            previous.userAgentMode != next.userAgentMode ||
            previous.legacyCompatEnabled != next.legacyCompatEnabled ||
            previous.gestureNavigationEnabled != next.gestureNavigationEnabled ||
            previous.pullRefreshEnabled != next.pullRefreshEnabled ||
            previous.followSystemDarkMode != next.followSystemDarkMode ||
            previous.secureContextProxyEnabled != next.secureContextProxyEnabled ||
            previous.keepScreenOnEnabled != next.keepScreenOnEnabled ||
            previous.splitViewRatioLeft != next.splitViewRatioLeft ||
            previous.splitViewRatioRight != next.splitViewRatioRight ||
            // Opaque card list is free-form text — apply live; steward toggles rebuild instead.
            previous.wsStewardOpaqueCardLines != next.wsStewardOpaqueCardLines ||
            previous.wsStewardOpaqueCardLinesCustom != next.wsStewardOpaqueCardLinesCustom ||
            previous.wvProdMemoryFeaturesEnabled != next.wvProdMemoryFeaturesEnabled ||
            previous.wvProdFrameThrottleFeaturesEnabled != next.wvProdFrameThrottleFeaturesEnabled ||
            previous.browserPowerMode != next.browserPowerMode
    }

    /**
     * Rebuild-class settings are toggled in a list, and each flip costs a full teardown plus a
     * reload of both panes. Coalesce a burst so trying three switches in a row is one rebuild
     * instead of three white flashes.
     */
    private fun rebuildActiveBrowserForSettingsChange() {
        pendingSettingsRebuildRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingSettingsRebuildRunnable = null
            // The overlay can be hidden inside the coalesce window, and rebuilding then would
            // pop the browser back over the user's screen. Hand it to the deferred path, which
            // the next show already drains.
            if (isCreating || isBrowserHidden || !isOverlayVisible()) {
                pendingSettingsRebuild = true
                Log.d(TAG, "Browser hidden during settings coalesce; rebuild deferred")
                return@Runnable
            }
            rebuildFloatingBrowserContainer("browserSettingsChanged")
        }
        pendingSettingsRebuildRunnable = runnable
        mainHandler.postDelayed(runnable, SETTINGS_REBUILD_COALESCE_MS)
    }

    private fun cancelPendingSettingsRebuild() {
        pendingSettingsRebuildRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSettingsRebuildRunnable = null
    }

    // The old "sidebar toggle recovery" gesture lived here: three drawer open/close
    // flips within 5s tore down and rebuilt the whole floating container. On HA's
    // narrow layout the drawer is modal, so "open menu → pick item (auto-close) →
    // open menu again" is three flips — normal navigation cold-reloaded both panes.
    // Removed: drawer interaction must never cost a reload.

    /** Full overlay teardown + re-show with the current left/right URLs. */
    private fun rebuildFloatingBrowserContainer(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { rebuildFloatingBrowserContainer(reason) }
            return
        }
        if (containerRebuildInFlight) {
            Log.d(TAG, "Skip floating browser rebuild ($reason): already in flight")
            return
        }
        if (isCreating) {
            val restoreUrl = bestRestoreUrl(originalUrl)
            if (restoreUrl.isNotBlank()) {
                latchShowDuringCreate(restoreUrl)
            }
            Log.d(TAG, "Skip floating browser rebuild ($reason): create in flight")
            return
        }
        val restoreUrl = bestRestoreUrl(originalUrl)
        val restoreUrlRight = when {
            originalUrlRight.isNotBlank() -> originalUrlRight
            currentPageUrlRight.isNotBlank() -> currentPageUrlRight
            else -> lastKnownPageUrlRight.orEmpty()
        }
        if (restoreUrl.isBlank()) {
            Log.w(TAG, "Skip floating browser rebuild ($reason): no restore URL")
            return
        }
        containerRebuildInFlight = true
        Log.i(TAG, "Rebuilding floating browser container ($reason)")
        try {
            cleanupWebView(reason)
            if (restoreUrlRight.isNotBlank()) {
                pendingRestoreUrlRight = restoreUrlRight
            }
            WebViewService.show(this, restoreUrl)
        } finally {
            // showWebView is async; clear the guard so a later stuck state can recover again.
            containerRebuildInFlight = false
        }
    }

    private fun applyRuntimeBrowserSettings(previous: BrowserSettings, next: BrowserSettings) {
        if (previous.gestureNavigationEnabled != next.gestureNavigationEnabled) {
            updateGestureDetector(next.gestureNavigationEnabled)
            engineSurface?.setGestureNavigationEnabled(next.gestureNavigationEnabled)
        }
        if (previous.pullRefreshEnabled != next.pullRefreshEnabled) {
            syncOverlayNavHint()
            if (!next.pullRefreshEnabled) resetOverlayPullRefresh(dismiss = true)
        }

        if (previous.touchEnabled != next.touchEnabled || previous.dragEnabled != next.dragEnabled) {
            engineSurface?.setTouchEnabled(next.touchEnabled)
            applyTouchListener(webView, BrowserPane.LEFT)
            applyTouchListener(webViewRight, BrowserPane.RIGHT)
        }

        if (previous.initialScale != next.initialScale) {
            applyInitialScale(next.initialScale)
            // setInitialScale only sticks after a document load — refresh each pane.
            hardReloadAllPanes()
        }

        if (previous.fontSize != next.fontSize) {
            engineSurface?.setTextZoom(next.fontSize)
            webView?.settings?.textZoom = next.fontSize
            webViewRight?.settings?.textZoom = next.fontSize
        }

        if (previous.userAgentMode != next.userAgentMode) {
            if (EngineCapabilities.GECKO_BUNDLED) {
                // Gecko applies the mutable session settings and owns the required page reload.
                engineSurface?.setUserAgentMode(next.userAgentMode)
            } else {
                val ua = browserUserAgentForMode(next.userAgentMode)
                webView?.settings?.userAgentString = ua
                webViewRight?.settings?.userAgentString = ua
                // UA applies on the next document load — hard-reload every pane.
                hardReloadAllPanes()
            }
        }

        if (previous.followSystemDarkMode != next.followSystemDarkMode) {
            refreshDarkModeInternal()
        }

        if (previous.secureContextProxyEnabled != next.secureContextProxyEnabled) {
            applySecureContextProxySettingChange(next.secureContextProxyEnabled)
        }

        if (previous.keepScreenOnEnabled != next.keepScreenOnEnabled) {
            syncKeepScreenOnFromSettings()
        }

        if (previous.splitViewRatioLeft != next.splitViewRatioLeft ||
            previous.splitViewRatioRight != next.splitViewRatioRight
        ) {
            refreshSplitLayoutOrientation()
        }

        // Steward toggles rebuild the floating container (see requiresContainerRebuild).
        // Opaque card lines remain hot-applied so typing does not thrash the WebViews.
        if (next.wsStewardEnabled &&
            (previous.wsStewardOpaqueCardLines != next.wsStewardOpaqueCardLines ||
                previous.wsStewardOpaqueCardLinesCustom != next.wsStewardOpaqueCardLinesCustom)
        ) {
            applyWsStewardOpaqueCardLines(
                next.wsStewardOpaqueCardLines,
                next.wsStewardOpaqueCardLinesCustom,
            )
        }

        if (previous.wvProdFrameThrottleFeaturesEnabled != next.wvProdFrameThrottleFeaturesEnabled) {
            syncOffscreenPreRaster()
        }
        if (previous.wvProdMemoryFeaturesEnabled != next.wvProdMemoryFeaturesEnabled &&
            next.wvProdMemoryFeaturesEnabled &&
            isBrowserDormant()
        ) {
            purgeOverlayPageCache("wv-prod-memory-on")
        }

        if (previous.browserPowerMode != next.browserPowerMode) {
            applyVisiblePowerModeChange(
                BrowserPowerMode.fromStored(previous.browserPowerMode),
                BrowserPowerMode.fromStored(next.browserPowerMode),
                "settings",
            )
        }

        if (previous.legacyCompatEnabled != next.legacyCompatEnabled) {
            applyLegacyCompatEnabled(next.legacyCompatEnabled)
        }
    }

    private fun isStewardStreamOn(): Boolean =
        currentSettings.wsStewardEnabled && currentSettings.wsStewardStreamEnabled

    /**
     * `content-visibility` needs Chromium 85+. The page-side `CSS.supports` probe in
     * [BrowserWsStewardScripts] is the authoritative guard (it also grades 85–97 down
     * to a fixed placeholder); this pre-gate only skips installing the document-start
     * runtime on engines that can never chunk. Unknown version (0) stays permissive.
     * Gecko is not the system WebView, so its version gate is the page-side probe alone.
     */
    private fun chunkRenderingEngineSupported(): Boolean {
        if (engineSurface != null) return true
        val major = WebViewRuntime.cachedInfo(this).majorVersion
        return major == 0 || major >= 85
    }

    private fun isStewardChunkOn(): Boolean =
        currentSettings.wsStewardEnabled &&
            currentSettings.wsStewardChunkedRenderingEnabled &&
            chunkRenderingEngineSupported()

    private fun isStewardDormantQuietOn(): Boolean =
        currentSettings.wsStewardEnabled && currentSettings.wsStewardDormantQuietEnabled

    /**
     * Master-only must not inject bootJs. Stream needs the WS wrap / scroll gate;
     * chunk needs the shadow walk. Everything else is late-applied from Kotlin.
     */
    private fun isStewardPageRuntimeNeeded(): Boolean =
        BrowserWsStewardScripts.needsDocumentStart(
            stewardEnabled = currentSettings.wsStewardEnabled,
            streamEnabled = currentSettings.wsStewardStreamEnabled,
            chunkEnabled = currentSettings.wsStewardChunkedRenderingEnabled &&
                chunkRenderingEngineSupported(),
        )

    /** Live toggle: install/remove the steward runtime; sub-switches re-apply next. */
    private fun applyWsStewardEnabled(enabled: Boolean) {
        engineSurface?.setDocumentStartScripts(geckoDocumentStartScripts())
        if (enabled) {
            if (!isStewardPageRuntimeNeeded()) {
                // Master-only: do not wrap hassConnection or walk card shadows.
                return
            }
            webView?.let { BrowserWsStewardScripts.ensureInstalledOnPage(it) }
            webViewRight?.let { BrowserWsStewardScripts.ensureInstalledOnPage(it) }
            ensureGeckoStewardInstalled()
            evaluateStewardOnAllEngines(BrowserWsStewardScripts.enableJs)
            applyWsStewardStreamEnabled(currentSettings.wsStewardStreamEnabled)
            applyWsStewardOpaqueCardLines(
                currentSettings.wsStewardOpaqueCardLines,
                currentSettings.wsStewardOpaqueCardLinesCustom,
            )
            applyWsStewardEntityTrimEnabled(currentSettings.wsStewardEntityTrimEnabled)
            chunkLeftApplied = false
            chunkRightApplied = false
            chunkGeckoApplied = false
            syncChunkedRendering()
            syncFreezeAnimations()
            syncPauseMedia()
        } else {
            liteModeActive = false
            liteLeftActive = false
            liteRightActive = false
            liteGeckoActive = false
            liteLeftFlushMs = -1
            liteRightFlushMs = -1
            liteGeckoFlushMs = -1
            freezeAnimationsApplied = false
            pauseMediaApplied = false
            chunkLeftApplied = false
            chunkRightApplied = false
            chunkGeckoApplied = false
            evaluateStewardOnAllEngines(BrowserWsStewardScripts.disableJs)
        }
    }

    /**
     * Entity-stream sub-switch: scroll deferral, lite, park/suspend.
     * Chunked rendering and dormant quiet stay independently gated.
     */
    private fun applyWsStewardStreamEnabled(enabled: Boolean) {
        if (!currentSettings.wsStewardEnabled) return
        val script = if (enabled) {
            BrowserWsStewardScripts.streamOnJs
        } else {
            BrowserWsStewardScripts.streamOffJs
        }
        evaluateStewardOnAllEngines(script)
        if (!enabled) {
            // streamOffJs clears park on the page; also resume a suspended socket.
            if (dormancyStep >= DormancyStep.L4_SUSPENDED) {
                resumeHaWebSockets()
            }
            liteModeActive = false
            liteLeftActive = false
            liteRightActive = false
            liteGeckoActive = false
            liteLeftFlushMs = -1
            liteRightFlushMs = -1
            liteGeckoFlushMs = -1
            // Trim lives under stream — push off directly (isStewardStreamOn is already false).
            evaluateStewardOnAllEngines(BrowserWsStewardScripts.trimOffJs)
            evaluateStewardOnAllEngines(BrowserWsStewardConsole.removeJs)
        } else {
            applyWsStewardEntityTrimEnabled(currentSettings.wsStewardEntityTrimEnabled)
            // Re-assert the current dormancy rung's stream side-effects.
            if (isBrowserDormant()) {
                if (dormancyStep >= DormancyStep.L3_PARKED) setEntitiesParked(true)
                if (dormancyStep >= DormancyStep.L4_SUSPENDED) {
                    suspendHaWebSockets("stream-on")
                }
            }
            syncLiteMode()
        }
    }

    /** Push opaque card-type table used for entity-trim passthrough. Requires stream on. */
    private fun applyWsStewardOpaqueCardLines(lines: String, custom: Boolean) {
        if (!isStewardStreamOn()) return
        val types = BrowserSettings.opaqueCardTypesForApply(lines, custom)
        val script = BrowserWsStewardScripts.setUserOpaqueTypesJs(types)
        webView?.let {
            BrowserWsStewardScripts.ensureInstalledOnPage(it)
            it.evaluateJavascript(script, null)
        }
        webViewRight?.let {
            BrowserWsStewardScripts.ensureInstalledOnPage(it)
            it.evaluateJavascript(script, null)
        }
        ensureGeckoStewardInstalled()
        engineSurface?.evaluateJavascript(script)
    }

    /** Page-live `subscribe_entities` retarget. Requires stream scheduling on. */
    private fun applyWsStewardEntityTrimEnabled(enabled: Boolean) {
        if (!isStewardStreamOn()) return
        if (enabled) {
            applyWsStewardOpaqueCardLines(
                currentSettings.wsStewardOpaqueCardLines,
                currentSettings.wsStewardOpaqueCardLinesCustom,
            )
        }
        val script = if (enabled) {
            BrowserWsStewardScripts.trimOnJs
        } else {
            BrowserWsStewardScripts.trimOffJs
        }
        evaluateStewardOnAllEngines(script)
        evaluateStewardOnAllEngines(
            if (enabled) BrowserWsStewardConsole.installJs else BrowserWsStewardConsole.removeJs,
        )
        if (enabled) pushStewardHostSnapshot(force = true)
    }

    /**
     * Host identity for the in-HA steward console.
     * Throttled: [syncLiteMode] is hot, and a Service [Context.getDisplay] throw
     * here used to crash the overlay and flood logcat.
     */
    private var lastStewardHostPushMs = 0L

    private fun pushStewardHostSnapshot(force: Boolean = false) {
        if (!isStewardStreamOn() || !currentSettings.wsStewardEntityTrimEnabled) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastStewardHostPushMs < 1_500L) return
        lastStewardHostPushMs = now
        try {
            evaluateStewardOnAllEngines(BrowserWsStewardHost.snapshotJs(this, stewardHostInfo()))
        } catch (e: Exception) {
            Log.w(TAG, "steward host snapshot failed", e)
        }
    }

    private fun stewardHostInfo(): BrowserWsStewardHost.Info =
        BrowserWsStewardHost.Info(
            gecko = engineSurface != null,
            renderHardware = currentSettings.hardwareAcceleration,
            powerMode = visiblePowerMode(),
            pressure = pressureTier.name,
            dormancy = dormancyStep.name,
            dormantKind = dormantKind.name,
            liteOn = liteModeActive,
            liteMs = liteModeFlushMs,
            split = splitSharedSession ||
                (webViewRight != null && splitLeftPaneActive && splitRightPaneActive),
            touchBoost = touchPowerBoosted,
            stream = currentSettings.wsStewardStreamEnabled,
            chunkWanted = isStewardChunkOn(),
            quiet = isStewardDormantQuietOn(),
            trim = currentSettings.wsStewardEntityTrimEnabled,
            frameThrottle = currentSettings.wvProdFrameThrottleFeaturesEnabled,
            memoryFeatures = currentSettings.wvProdMemoryFeaturesEnabled,
            view = webView ?: webViewRight,
        )

    /**
     * Steward runtime toggles must reach every live engine: both WebView panes
     * and the Gecko surface (whichever exist; with Gecko active the panes are null).
     */
    private fun evaluateStewardOnAllEngines(script: String) {
        webView?.evaluateJavascript(script, null)
        webViewRight?.evaluateJavascript(script, null)
        engineSurface?.evaluateJavascript(script)
    }

    /**
     * WebView [BrowserWsStewardScripts.ensureInstalledOnPage] counterpart. Boot +
     * installJs must hit the live Gecko document, not only the next navigation.
     */
    private fun ensureGeckoStewardInstalled() {
        val surf = engineSurface ?: return
        if (!isStewardPageRuntimeNeeded()) return
        surf.evaluateJavascript(BrowserWsStewardScripts.bootJs)
        surf.evaluateJavascript(BrowserWsStewardScripts.installJs)
    }

    /**
     * WebView parity for the Gecko surface: stored for every navigation via the
     * console-bridge content script, then re-applied after onPageStop.
     */
    private fun geckoDocumentStartScripts(): List<String> = buildList {
        add(JS_ERROR_GUARD)
        addAll(BrowserPlatformCompat.pageCompatScripts())
        add(BrowserDownloadBridge.downloadInterceptScript())
        add(BrowserHaKioskScripts.scriptForSettings(currentSettings))
        if (isStewardPageRuntimeNeeded()) {
            add(BrowserWsStewardScripts.bootJs)
            add(BrowserWsStewardScripts.installJs)
        }
    }

    /**
     * Toggle HTTP page boost: stop/start loopback proxy and reload the **real**
     * URL so map/unmap stays consistent (never leave the WebView on a dead port).
     */
    private fun applySecureContextProxySettingChange(enabled: Boolean) {
        val leftRaw = bestRestoreUrl(originalUrl)
        val leftReal = SecureContextProxy.instance.unmapUrl(
            if (leftRaw.isNotBlank()) normalizeUrl(leftRaw) else "",
        )
        val rightRaw = when {
            originalUrlRight.isNotBlank() -> originalUrlRight
            currentPageUrlRight.isNotBlank() -> currentPageUrlRight
            else -> ""
        }
        val rightReal = SecureContextProxy.instance.unmapUrl(
            if (rightRaw.isNotBlank()) normalizeUrl(rightRaw) else "",
        )
        if (!enabled) {
            SecureContextProxy.instance.stop()
        }
        if (leftReal.isNotBlank()) {
            loadUrl(leftReal, BrowserPane.LEFT)
        }
        if (webViewRight != null && rightReal.isNotBlank()) {
            loadUrl(rightReal, BrowserPane.RIGHT)
        }
    }

    /**
     * Keep-screen-on only while the browser overlay is not hidden.
     * Gecko hide may leave the window attached, so hide must clear the flag explicitly.
     */
    private fun syncKeepScreenOnFromSettings() {
        applyKeepScreenOn(currentSettings.keepScreenOnEnabled && !isBrowserHidden)
    }

    /** Mirror of [VinylCoverService] window-flag toggle; no wake lock. */
    private fun applyKeepScreenOn(enabled: Boolean) {
        val host = containerView ?: return
        val params = windowParams ?: return
        val wm = windowManager ?: return
        val hasFlag = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (enabled == hasFlag) {
            host.keepScreenOn = enabled
            return
        }
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        host.keepScreenOn = enabled
        if (host.parent != null) {
            runCatching { wm.updateViewLayout(host, params) }
                .onFailure { Log.w(TAG, "Failed to update keep-screen-on=$enabled", it) }
        }
    }

    private fun updateGestureDetector(enabled: Boolean) {
        if (enabled) {
            gestureDetector = createGestureDetector()
        } else {
            gestureDetector = null
        }
        syncOverlayNavHint()
    }

    private fun createGestureDetector(): WebViewGestureDetector =
        WebViewGestureDetector(
            context = this,
            canGoBack = { engineCanGoBack() },
            canGoForward = { engineCanGoForward() },
            onGoBack = { engineGoBack() },
            onGoForward = { engineGoForward() },
            isInExcludedZone = { x, y -> browserSidebarOverlay?.isPointInHandleZone(x, y) == true },
            onHintProgress = { kind, progress ->
                val blocked = browserSidebarOverlay?.isSidebarOpen() == true ||
                    htmlFullscreenController?.isActive() == true
                if (blocked) {
                    navHintView?.dismiss(committed = false)
                } else {
                    (containerView as? FrameLayout)?.let { host ->
                        ensureNavHint(host).show(kind, progress)
                    }
                }
            },
            onHintEnd = { committed ->
                navHintView?.dismiss(committed)
            },
        )

    private fun ensureNavHint(container: FrameLayout): BrowserNavHintView {
        val existing = navHintView
        if (existing != null && existing.parent === container) return existing
        val hint = existing ?: BrowserNavHintView(this).also { navHintView = it }
        hint.attach(container)
        return hint
    }

    private fun syncOverlayNavHint() {
        val container = containerView as? FrameLayout ?: return
        if (currentSettings.gestureNavigationEnabled || currentSettings.pullRefreshEnabled) {
            ensureNavHint(container)
        } else {
            navHintView?.release()
            navHintView = null
        }
    }

    private fun browserUserAgentForMode(mode: Int): String {
        return when (mode) {
            1 -> desktopChromeUserAgent()
            2 -> EngineSurface.Companion.UserAgents.MACOS_SAFARI
            3 -> EngineSurface.Companion.UserAgents.IOS_SAFARI
            else -> {
                val baseUA = try {
                    android.webkit.WebSettings.getDefaultUserAgent(this)
                } catch (e: Exception) {
                    webView?.settings?.userAgentString?.removeSuffix(" AvaWebView").orEmpty()
                }
                if (baseUA.contains("AvaWebView")) baseUA else "$baseUA AvaWebView".trim()
            }
        }
    }

    /**
     * Desktop UA derived from the real engine UA (same transform Chromium applies for
     * "request desktop site"): swap the Android platform token, drop the WebView-only
     * `Version/4.0` and the `Mobile` token, keep the true `Chrome/<major>`.
     *
     * Keeping the real major matters both ways: Home Assistant serves its modern JS
     * bundle only to UAs from the last ~2 years, so a pinned Chrome/120 spoof already
     * downgrades new engines to the legacy ES5 build; and an old engine claiming a
     * current Chrome would receive the modern bundle if HA's feature-detect fallback
     * ever misses. Falls back to the pinned constant when the engine UA is unreadable.
     */
    private fun desktopChromeUserAgent(): String {
        val base = try {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        } catch (e: Exception) {
            null
        }
        if (base == null || !base.contains("Chrome/")) {
            return EngineSurface.Companion.UserAgents.DESKTOP_CHROME
        }
        return base
            .replace(Regex("""\(Linux;[^)]*\)"""), "(Windows NT 10.0; Win64; x64)")
            .replace(Regex(""" Version/[\d.]+"""), "")
            .replace(" Mobile Safari/", " Safari/")
    }

    private fun bestRestoreUrl(fallback: String = ""): String {
        val raw = currentPageUrl
            .ifBlank { lastKnownPageUrl.orEmpty() }
            .ifBlank { originalUrl }
            .ifBlank { fallback }
        // Prefer the real HA origin for restore/rebuild (never persist loopback).
        return if (raw.isNotBlank()) SecureContextProxy.instance.unmapUrl(raw) else raw
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        latchOverlayReceiptToken(intent)
        promoteForegroundIfNeeded()

        when (intent?.action) {
            "ACTION_SHOW" -> {
                val url = intent.getStringExtra("url") ?: ""
                showWebView(url)
            }
            "ACTION_HIDE" -> {
                hideWebView()
            }
            "ACTION_DESTROY" -> {
                destroyWebView()
            }
            "ACTION_RELAUNCH" -> {
                relaunchInternal()
            }
            "ACTION_UPDATE" -> {
                val url = intent.getStringExtra("url") ?: ""
                updateUrl(url)
            }
            "ACTION_COMMAND" -> {
                val command = intent.getStringExtra("command") ?: ""
                handleCommand(command)
            }
            "ACTION_SHOW_OR_REFRESH" -> {
                val url = intent.getStringExtra("url") ?: ""
                val pane = intent.getStringExtra(EXTRA_PANE)?.let { name ->
                    runCatching { BrowserPane.valueOf(name) }.getOrNull()
                } ?: BrowserPane.LEFT
                showOrRefreshWebView(url, pane)
            }
            "ACTION_APPLY_HA_REMOTE_URLS" -> {
                val leftUrl = intent.getStringExtra(EXTRA_LEFT_URL).orEmpty()
                val rightUrl = intent.getStringExtra(EXTRA_RIGHT_URL).orEmpty()
                lifecycleScope.launch {
                    if (::browserSettingsStore.isInitialized) {
                        currentSettings = applyHostDarkModeOverride(browserSettingsStore.get())
                    }
                    applyHaRemoteUrls(leftUrl, rightUrl)
                }
            }
            "ACTION_SET_SCALE" -> {
                applyInitialScale(intent.getIntExtra("scale", 0))
                // Scale takes effect on the next document load — refresh every live pane.
                hardReloadAllPanes()
            }
            "ACTION_REFRESH_IF_STUCK" -> {
                refreshIfStuckInternal()
            }
            "ACTION_FORCE_REFRESH" -> {
                forceRefreshInternal(
                    allActivePanes = intent.getBooleanExtra(EXTRA_ALL_ACTIVE_PANES, false),
                )
            }
            "ACTION_CLEAR_CACHE" -> {
                clearBrowserCacheInternal()
            }
            "ACTION_CLEAR_COOKIES_HISTORY", "ACTION_CLEAR_SITE_DATA" -> {
                clearCookiesAndHistoryInternal()
            }
            "ACTION_REFRESH_DARK_MODE" -> {
                refreshDarkModeInternal()
            }
            "ACTION_PAUSE" -> {
                enterCoveredDormant("action-pause")
            }
            "ACTION_RESUME" -> {
                pendingCoveredWhileWarming = false
                exitCoveredDormant("action-resume")
            }
            "ACTION_COVER_READY" -> {
                deepenCoveredDormant("action-cover-ready")
            }
            ACTION_RESTART_SELF -> {
                restartSelf()
            }
            ACTION_END_SELF -> {
                endSelf()
            }
        }
        
        // A browser overlay should NOT be auto-resurrected as an empty (intent==null)
        // zombie after a low-memory kill. The host (VoiceSatelliteService) re-shows it
        // from the persisted enableBrowserVisible state when appropriate.
        return START_NOT_STICKY
    }

    /**
     * The gecko engine pack is a headless APK with no VoiceSatelliteService keeping the process
     * alive. OEM background managers (e.g. BackgroundManagerService) kill it within ~1s unless
     * WebViewService is promoted immediately when a show intent arrives.
     */
    private fun promoteForegroundIfNeeded() {
        if (!EngineCapabilities.GECKO_BUNDLED || isForegroundPromoted) return
        createBrowserServiceNotificationChannel(this)
        val notification = createBrowserServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BROWSER_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(BROWSER_NOTIFICATION_ID, notification)
        }
        isForegroundPromoted = true
        Log.d(TAG, "Promoted browser service to foreground")
    }

    private fun releaseForegroundIfNeeded() {
        if (!isForegroundPromoted) return
        // Gecko pack is headless; dropping foreground while the session is still alive lets
        // OEM background killers stop the process within seconds.
        if (EngineCapabilities.GECKO_BUNDLED && engineSurface != null) {
            Log.d(TAG, "Keeping foreground while gecko renderer is alive")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForegroundPromoted = false
        Log.d(TAG, "Released browser foreground notification")
    }
    
    private fun showWebView(url: String) {
        // Invalidate any in-flight fade-out so it cannot detach after we re-show.
        hideEpoch += 1
        // Reserve the first seat before the window exists. Otherwise the next
        // overlay to attach becomes the pair and this one stays fullscreen.
        splitSeatReserved = true
        OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.BROWSER)
        containerView?.animate()?.cancel()
        val requestedUrl = url.trim().let { raw -> if (raw.isBlank()) "" else normalizeUrl(raw) }
        // Do not seed left URL from the create seed when applyHaRemoteUrls set an explicit
        // empty left restore (pendingRestoreUrl == "").
        if (originalUrl.isEmpty() && requestedUrl.isNotBlank() && pendingRestoreUrl == null) {
            originalUrl = requestedUrl
        }
        if (EngineCapabilities.GECKO_BUNDLED) {
            promoteForegroundIfNeeded()
        }
        if (containerView != null && (webView != null || engineSurface != null)) {
            if (isBrowserHidden) {
                if (pendingSettingsRebuild) {
                    pendingSettingsRebuild = false
                    val restoreUrl = bestRestoreUrl(requestedUrl)
                    cleanupWebView("showWebView-settings-rebuild-from-hidden")
                    WebViewService.show(this, restoreUrl)
                    return
                }
                if (shouldRebuildGeckoOverlayFromHide()) {
                    val restoreUrl = bestRestoreUrl(requestedUrl)
                    cleanupWebView("showWebView-gecko-rebuild-from-hide")
                    WebViewService.show(this, restoreUrl)
                    return
                }
                resumeBrowserFromHide(requestedUrl)
            } else if (OverlayLayerSplit.isColdStartHolding()) {
                // Already attached during cold start. Do not raise alpha on the
                // full-screen params; the held reveal runs after the pane is set.
                containerView?.alpha = 0f
                containerView?.visibility = View.VISIBLE
                if (EngineCapabilities.GECKO_BUNDLED) {
                    unparkGeckoOverlayWindow()
                }
                resumePausedRendererIfNeeded()
                applyActiveRendererPriority()
                applyDormancyStep(DormancyStep.L0_ACTIVE, "show-already-visible")
                updateUrl(requestedUrl)
                OverlayLayerSplit.sync()
                val reveal = containerView
                OverlayLayerSplit.runAfterColdStart {
                    if (containerView !== reveal || isBrowserHidden) return@runAfterColdStart
                    OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.BROWSER) {
                        if (containerView !== reveal || isBrowserHidden) return@fadeWhenPaneReady
                        reveal?.alpha = 1f
                    }
                }
            } else {
                // Already up: navigate only. Do not restack FAB / overlay.
                containerView?.alpha = 1f
                containerView?.visibility = View.VISIBLE
                if (EngineCapabilities.GECKO_BUNDLED) {
                    unparkGeckoOverlayWindow()
                }
                resumePausedRendererIfNeeded()
                applyActiveRendererPriority()
                applyDormancyStep(DormancyStep.L0_ACTIVE, "show-already-visible")
                updateUrl(requestedUrl)
            }
            return
        }
        
        if (isCreating) {
            latchShowDuringCreate(requestedUrl)
            return
        }
        hideRequestedDuringCreate = false
        isCreating = true
        val createToken = overlayGeneration.begin()

        lifecycleScope.launch {
            /**
             * A teardown can land on any suspension point below and hand the service fields to a
             * newer build, which also clears isCreating/hideRequestedDuringCreate. Once that
             * happens this coroutine owns nothing: it must not attach, and must not clean up
             * either, or it would destroy the overlay the new owner is building.
             */
            fun abandoned(stage: String): Boolean {
                if (!overlayGeneration.isStale(createToken)) return false
                Log.d(TAG, "Browser create abandoned at $stage; a newer build owns the overlay")
                return true
            }
            try {
                
                currentSettings = applyHostDarkModeOverride(browserSettingsStore.get())
                if (abandoned("settings-load")) return@launch
                val useGeckoEngine =
                    BrowserEngine.effectiveEngine(this@WebViewService, currentSettings.browserEngine) ==
                        BrowserEngine.GECKO

                if (currentSettings.gestureNavigationEnabled) {
                    gestureDetector = createGestureDetector()
                } else {
                    gestureDetector = null
                }

                // GeckoView engine: render through the surface; keep a system WebView when available
                // as a background object so existing WebView-based lifecycle code keeps working.
                if (useGeckoEngine) {
                    engineSurface = GeckoEngineFactory.create(this@WebViewService)?.also { surf ->
                        surf.onPageUrlChanged = { u ->
                            currentPageUrl = u
                            lastKnownPageUrl = u
                            setPaneLoaded(BrowserPane.LEFT, true)
                            syncCurrentBrowserUrl(u)
                            syncHaBrowserDarkMode()
                            injectSecureContextMediaRewrite(null)
                        }
                        // Same restore-retry arm/clear as system WebView; local pages never match.
                        surf.onPageLoadFinished = { success, url ->
                            mainHandler.post {
                                if (engineSurface !== surf) return@post
                                if (success) {
                                    if (!url.isNullOrBlank() && !isPlaceholderPageUrl(url)) {
                                        clearNetworkRetryAwaiting("gecko_page_ok")
                                    }
                                    reassertGeckoStewardAfterLoad()
                                } else {
                                    noteRemoteLoadFailure(url, "gecko_net_fail")
                                }
                            }
                        }
                        surf.onHaDarkModeChanged = { isDark ->
                            handleHaDarkModeChanged(isDark)
                        }
                        surf.onPrepareTextInput = {
                            mainHandler.post {
                                claimOverlayWindowFocus(engineSurface?.view)
                            }
                        }
                        surf.onScrollActivity = {
                            ScreensaverController.onUserActivity()
                        }
                        surf.onContentProcessCrash = {
                            mainHandler.post { handleGeckoContentCrash() }
                        }
                        surf.onConsoleMessage = { level, message ->
                            mainHandler.post {
                                if (engineSurface === surf) appendConsole(level, message)
                            }
                        }
                    }
                    // After the field assignment: geckoDocumentStartScripts() reads gates
                    // (chunk support) that check engineSurface itself.
                    engineSurface?.setDocumentStartScripts(geckoDocumentStartScripts())
                    startMemoryGuard()
                }

                if (!useGeckoEngine && isWebViewAvailable()) {
                    try {
                        val splitEnabled = currentSettings.splitViewEnabled
                        // Plain WebView — NestedScroll wrapper fought SwipeRefresh top-pull.
                        webView = WebView(this@WebViewService)
                        setupWebView(webView!!, BrowserPane.LEFT)
                        webView?.let { wv ->
                            readabilityExtractor = ReadabilityExtractor(wv)
                        }
                        if (splitEnabled) {
                            webViewRight = WebView(this@WebViewService)
                            setupWebView(webViewRight!!, BrowserPane.RIGHT)
                            focusedPane = BrowserPane.LEFT
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "System WebView init failed; continuing with Gecko-only renderer", e)
                        webView = null
                        webViewRight = null
                        readabilityExtractor = null
                    }
                } else if (!useGeckoEngine) {
                    Log.e(TAG, "WebView is not available on this device")
                    engineSurface?.destroy()
                    engineSurface = null
                    isCreating = false
                    return@launch
                }

                val renderView: View = when {
                    engineSurface?.view != null -> engineSurface!!.view
                    webViewRight != null && webView != null -> {
                        android.widget.LinearLayout(this@WebViewService).apply {
                            applySplitLayoutOrientation(this)
                            addView(webView, splitPaneLayoutParams(BrowserPane.LEFT))
                            addView(webViewRight, splitPaneLayoutParams(BrowserPane.RIGHT))
                            splitLayout = this
                        }
                    }
                    webView != null -> webView!!
                    else -> {
                        Log.e(TAG, "No browser renderer available")
                        isCreating = false
                        return@launch
                    }
                }
                renderView.isFocusable = true
                renderView.isFocusableInTouchMode = true
                
                // Apply settings to GeckoView if using Gecko engine
                engineSurface?.let { surf ->
                    surf.setTouchEnabled(currentSettings.touchEnabled)
                    surf.setInitialScale(currentSettings.initialScale)
                    surf.setTextZoom(currentSettings.fontSize)
                    surf.setUserAgentMode(currentSettings.userAgentMode)
                    surf.setGestureNavigationEnabled(currentSettings.gestureNavigationEnabled)
                    surf.setDarkMode(
                        BrowserDarkModeResolver.shouldUseDarkMode(
                            this@WebViewService,
                            currentSettings.followSystemDarkMode
                        )
                    )
                    surf.onGestureBack = { engineGoBack() }
                }
                
                // Pull-to-refresh is recognized on the overlay container, same as back/forward.
                val rootView: View = renderView
                
                val params = WindowManager.LayoutParams().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        type = WindowManager.LayoutParams.TYPE_PHONE
                    }
                    @Suppress("DEPRECATION")
                    var overlayFlags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                    if (useGeckoEngine) {
                        // GeckoView PanZoomController needs a focusable overlay window for
                        // click / long-press / scroll; NOT_FOCUSABLE breaks touch on Android 7.
                        overlayFlags = overlayFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    } else {
                        overlayFlags = overlayFlags or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    }
                    flags = overlayFlags
                    // WindowManager-added overlays are NOT hardware accelerated by default;
                    // without this the WebView composites in software (janky scroll on HA dashboards).
                    if (currentSettings.hardwareAcceleration) {
                        flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    }
                    if (currentSettings.keepScreenOnEnabled) {
                        flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    }
                    softInputMode = if (useGeckoEngine) {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                    }
                    format = PixelFormat.TRANSLUCENT
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    OverlayOrientation.apply(this)
                }
                
                
                val container = object : FrameLayout(this@WebViewService) {
                    private var touchDownX = 0f
                    private var touchDownY = 0f
                    private var touchMovedFar = false
                    private var lastActivityAtMs = 0L

                    /** True while the children own a touch stream (saw DOWN, no UP/CANCEL yet). */
                    private var childStreamActive = false

                    /**
                     * A gesture just claimed a stream whose DOWN (and early MOVEs) already
                     * reached the page. Ending it silently leaves the engine mid-gesture —
                     * pressed state stuck, next tap eaten re-synchronizing — so deliver the
                     * CANCEL the system would have sent.
                     */
                    private fun cancelChildTouchStream(event: MotionEvent) {
                        if (!childStreamActive) return
                        childStreamActive = false
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        try {
                            super.dispatchTouchEvent(cancel)
                        } finally {
                            cancel.recycle()
                        }
                    }

                    /** Idle-timer ping; throttled on MOVE so scroll frames stay free. */
                    private fun noteUserActivity(force: Boolean) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (!force && now - lastActivityAtMs < 800L) return
                        lastActivityAtMs = now
                        ScreensaverController.onUserActivity()
                        com.example.ava.sensor.ScreenTouchSensor.onUserTouch()
                    }

                    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                            event.repeatCount == 0
                        ) {
                            noteUserActivity(force = true)
                            onBrowserTouchPowerPulse()
                            // Focusable-overlay path (Gecko / IME) bypasses MainActivity,
                            // so the remote focus session must be armed here too or the
                            // sidebar rows navigate with no visible selection ring.
                            if (com.example.ava.ui.components.RemoteFocusSession
                                    .isRemoteNavKey(event.keyCode)
                            ) {
                                com.example.ava.ui.components.RemoteFocusSession.noteRemoteNav()
                            }
                        }
                        // Focusable overlay path (Gecko, or system WebView holding IME focus):
                        // hardware keys arrive here instead of MainActivity.
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                            if (event.action == android.view.KeyEvent.ACTION_UP) {
                                browserSidebarOverlay?.toggleOpen()
                            }
                            return true
                        }
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST ||
                            event.keyCode == android.view.KeyEvent.KEYCODE_ASSIST ||
                            event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH
                        ) {
                            if (event.action == android.view.KeyEvent.ACTION_UP) {
                                VoiceSatelliteService.getInstance()?.onAssistKeyPressed()
                            }
                            return true
                        }
                        // Focusable-overlay path: with the sidebar open, D-pad/BACK
                        // must drive its rows, not whatever WebView holds view focus.
                        browserSidebarOverlay?.let { sidebar ->
                            if (event.keyCode != android.view.KeyEvent.KEYCODE_MENU &&
                                sidebar.dispatchRemoteKey(event)
                            ) {
                                return true
                            }
                        }
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                            overlayImeFocusHeld &&
                            webConsoleCompose == null
                        ) {
                            if (event.action == android.view.KeyEvent.ACTION_UP) {
                                releaseSystemWebViewImeFocus()
                            }
                            return true
                        }
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                            if (webConsoleCompose != null) {
                                hideWebConsolePanel()
                                return true
                            }
                            if (engineSurface?.isHtmlFullscreenActive() == true) {
                                engineSurface?.exitHtmlFullscreen()
                                return true
                            }
                            if (htmlFullscreen().isActive()) {
                                htmlFullscreen().exit()
                                return true
                            }
                            if (currentSettings.backKeyHideEnabled) {
                                if (engineCanGoBack()) {
                                    engineGoBack()
                                } else {
                                    dismissBrowserFromUser()
                                }
                                return true
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }

                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val gestureConsumed = com.example.ava.sensor.ScreenGestureRecognizer.onTouchEvent(
                            this@WebViewService,
                            event,
                            width.coerceAtLeast(1),
                            height.coerceAtLeast(1),
                        )
                        if (!currentSettings.touchEnabled) {
                            val sidebar = browserSidebarOverlay
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    // Page taps are swallowed below, so this is all that is left
                                    // to re-present the edge handle after it auto-hides.
                                    sidebar?.notifyGlobalTouch()
                                    // Latch at DOWN: the pane must not receive a stray MOVE just
                                    // because the finger wandered into the handle strip.
                                    touchOffSidebarGesture = sidebar?.wantsTouchAt(event.x, event.y) == true
                                    noteUserActivity(force = true)
                                    onBrowserTouchPower(fingerDown = true)
                                }
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL -> {
                                    noteUserActivity(force = true)
                                    onBrowserTouchPower(fingerDown = false)
                                }
                            }
                            // The Ava sidebar lives in this same window, so swallowing everything
                            // strands the user: its handle is the way back to the switch that
                            // turns page touch on again.
                            if (!touchOffSidebarGesture || sidebar == null || gestureConsumed) {
                                return true
                            }
                            // Hand it to the sidebar alone, never through the container: normal
                            // dispatch would fall through to the page whenever the sidebar
                            // declines the event, and "touch off" must mean the page gets nothing.
                            event.offsetLocation(-sidebar.x, -sidebar.y)
                            try {
                                sidebar.dispatchTouchEvent(event)
                            } finally {
                                event.offsetLocation(sidebar.x, sidebar.y)
                            }
                            when (event.actionMasked) {
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL -> touchOffSidebarGesture = false
                            }
                            return true
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchDownX = event.x
                                touchDownY = event.y
                                touchMovedFar = false
                                // Same as SystemUI ACTION_GLOBAL_TOUCH_DOWN → showIndicator.
                                browserSidebarOverlay?.notifyGlobalTouch()
                                noteUserActivity(force = true)
                                onBrowserTouchPower(fingerDown = true)
                                VoiceSatelliteService.getInstance()?.onScreenTouch(true)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (!touchMovedFar) {
                                    val dx = kotlin.math.abs(event.x - touchDownX)
                                    val dy = kotlin.math.abs(event.y - touchDownY)
                                    if (dx > 24f || dy > 24f) touchMovedFar = true
                                }
                                noteUserActivity(force = false)
                                onBrowserTouchPower(fingerDown = true)
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                noteUserActivity(force = true)
                                onBrowserTouchPower(fingerDown = false)
                            }
                            MotionEvent.ACTION_SCROLL -> {
                                noteUserActivity(force = false)
                                onBrowserTouchPowerPulse()
                            }
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            VoiceSatelliteService.getInstance()?.onScreenTouch(false)
                        }
                        // Click sound only for taps — never after a scroll/drag.
                        if (event.actionMasked == MotionEvent.ACTION_UP && !touchMovedFar) {
                            TouchSoundHelper.playClick(this)
                        }
                        if (gestureConsumed) {
                            cancelChildTouchStream(event)
                            return true
                        }
                        if (dispatchOverlayPullRefresh(event)) {
                            cancelChildTouchStream(event)
                            return true
                        }
                        if (gestureDetector?.onTouchEvent(event) == true) {
                            cancelChildTouchStream(event)
                            return true
                        }
                        // Gecko receives gestures at the container; block page scroll/pinch when
                        // "允许滑动" is off while still allowing taps above.
                        if (shouldBlockPageDrag()) {
                            when (event.actionMasked) {
                                MotionEvent.ACTION_MOVE,
                                MotionEvent.ACTION_POINTER_DOWN,
                                MotionEvent.ACTION_POINTER_UP,
                                MotionEvent.ACTION_SCROLL -> return true
                            }
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> childStreamActive = true
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> childStreamActive = false
                        }
                        val dispatched = super.dispatchTouchEvent(event)
                        // Don't lift/restore IME flags while the finger is on the sidebar
                        // handle — updateViewLayout mid-gesture hitch the edge drag.
                        if (browserSidebarOverlay?.isPointInHandleZone(event.x, event.y) != true) {
                            maybeSyncSystemWebViewImeAfterTouch(event, !touchMovedFar)
                        }
                        return dispatched
                    }
                }.apply {
                    installComposeViewTreeOwners(this)
                    installOverlayImmersive(this)
                    installStatusBarInsetWatcher(this)
                    isFocusable = useGeckoEngine
                    isFocusableInTouchMode = useGeckoEngine
                    if (!useGeckoEngine) {
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }
                    isClickable = true
                    isLongClickable = true
                    addView(rootView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                }
                if (currentSettings.gestureNavigationEnabled || currentSettings.pullRefreshEnabled) {
                    ensureNavHint(container)
                }
                attachBrowserSidebar(container)
                if (abandoned("before-attach")) return@launch
                if (hideRequestedDuringCreate) {
                    Log.d(TAG, "Browser creation cancelled before overlay attach")
                    abortCreateAndReplayIfNeeded("showWebView-cancelled-before-attach")
                    return@launch
                }
                // First paint after addView must be invisible; fadeIn raises alpha.
                container.alpha = 0f
                container.keepScreenOn = currentSettings.keepScreenOnEnabled
                containerView = container
                windowParams = params
                overlayImeFocusHeld = false
                if (hideRequestedDuringCreate) {
                    Log.d(TAG, "Browser creation cancelled at overlay attach")
                    abortCreateAndReplayIfNeeded("showWebView-cancelled-at-attach")
                    return@launch
                }
                if (!attachOverlayIfNeeded()) {
                    throw IllegalStateException("Failed to attach browser overlay window")
                }
                if (hideRequestedDuringCreate || isBrowserHidden) {
                    Log.d(TAG, "Browser creation cancelled after overlay attach")
                    abortCreateAndReplayIfNeeded("showWebView-cancelled-after-attach")
                    return@launch
                }
                val restoreUrl = when {
                    pendingRestoreUrl != null -> pendingRestoreUrl.orEmpty()
                    else -> lastKnownPageUrl ?: requestedUrl
                }.takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
                pendingRestoreUrl = null
                if (restoreUrl.isNotBlank()) {
                    if (originalUrl.isEmpty()) originalUrl = normalizeUrl(restoreUrl)
                    loadUrl(restoreUrl, BrowserPane.LEFT)
                } else {
                    originalUrl = ""
                    currentPageUrl = ""
                }
                var rightRestoreBlank = true
                if (webViewRight != null) {
                    val rightRestore = (
                        pendingRestoreUrlRight
                            ?: lastKnownPageUrlRight
                            ?: try {
                                VoiceSatelliteSettingsStore(applicationContext.voiceSatelliteSettingsStore)
                                    .get().haRemoteUrlRight
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to read haRemoteUrlRight", e)
                                ""
                            }
                        ).takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
                    if (abandoned("right-restore-read")) return@launch
                    pendingRestoreUrlRight = null
                    if (rightRestore.isNotBlank()) {
                        rightRestoreBlank = false
                        if (restoreUrl.isNotBlank()) {
                            // First-entry pressure may hold the right tile 3s; otherwise now.
                            splitSharedSession = isSharedSplitSession(restoreUrl, rightRestore)
                            scheduleStaggeredRightLoad(rightRestore)
                            applySplitPaneVisibility(true, true)
                        } else {
                            splitSharedSession = false
                            originalUrlRight = normalizeUrl(rightRestore)
                            loadUrl(rightRestore, BrowserPane.RIGHT)
                            applySplitPaneVisibility(false, true)
                        }
                    } else {
                        originalUrlRight = ""
                        currentPageUrlRight = ""
                        splitSharedSession = false
                        cancelStaggeredRightLoad()
                        applySplitPaneVisibility(restoreUrl.isNotBlank(), false)
                    }
                    if (restoreUrl.isBlank() && rightRestoreBlank) {
                        // No HA URL on either tile — abort show instead of attaching an empty shell.
                        hideRequestedDuringCreate = true
                        isBrowserHidden = true
                    }
                }
                applyActiveRendererPriority()
                if (engineSurface != null) {
                    renderView.requestFocus()
                    claimOverlayWindowFocus(renderView)
                }
                
                // Keep browser window on top to avoid overlay layers intercepting dialog/pop-up interaction.
                // Overlay services manage their own z-order when explicitly shown/toggled.
                
                // Dismiss orphan idle cover + restart idle clock after dual-pane cold build.
                ScreensaverController.onUserInteraction()

                if (hideRequestedDuringCreate || isBrowserHidden) {
                    Log.d(TAG, "Browser creation aborted before visibility sync")
                    pendingCoveredWhileWarming = false
                    abortCreateAndReplayIfNeeded("showWebView-cancelled-before-sync")
                    return@launch
                }

                fadeInContainer()
                publishOverlayVisibility()
                val latchedShow = pendingShowDuringCreateUrl
                pendingShowDuringCreateUrl = null
                isCreating = false
                beginDisplaySettle("show")
                if (!latchedShow.isNullOrBlank()) {
                    updateUrl(latchedShow)
                }
                finishPendingCoveredAfterWarmup()
                if (EngineCapabilities.GECKO_BUNDLED) {
                    reportGeckoOverlay(visible = true, attached = true, updateHaSwitch = true)
                }
                if (pendingSettingsRebuild) {
                    pendingSettingsRebuild = false
                    rebuildActiveBrowserForSettingsChange()
                }
            } catch (e: android.util.AndroidRuntimeException) {
                // WebView package is disabled or corrupt
                Log.e(TAG, "WebView package unavailable or corrupt", e)
                if (!abandoned("webview-unavailable")) {
                    isCreating = false
                    cleanupWebView("showWebView-webview-unavailable")
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    AvaToast.show(this@WebViewService, getString(R.string.toast_webview_not_installed), durationMs = AvaToast.LONG_MS)
                }
            } catch (e: UnsatisfiedLinkError) {
                // Native library loading failed (rare but possible on some devices)
                Log.e(TAG, "WebView native library failed to load", e)
                if (!abandoned("native-error")) {
                    isCreating = false
                    cleanupWebView("showWebView-native-error")
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    AvaToast.show(this@WebViewService, getString(R.string.toast_webview_not_installed), durationMs = AvaToast.LONG_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show WebView", e)
                if (!abandoned("failure")) {
                    isCreating = false
                    // Tear down any half-built state so we never leak a detached WebView/window.
                    cleanupWebView("showWebView-failure")
                }
            }
        }
    }
    
    private var renderCrashCount = 0
    private var lastRenderCrashTime = 0L
    private val MAX_CRASH_COUNT = 3
    private val CRASH_RESET_INTERVAL = 60_000L

    private fun handleGeckoContentCrash() {
        val now = System.currentTimeMillis()
        Log.e(TAG, "Gecko content process crashed, crashCount=$renderCrashCount")
        runCatching {
            com.example.ava.crash.AvaIncidentLog.record(
                this,
                kind = com.example.ava.crash.AvaIncidentLog.KIND_RENDERER_CRASH,
                reason = "gecko_content_crash",
            )
        }
        if (now - lastRenderCrashTime > CRASH_RESET_INTERVAL) {
            renderCrashCount = 0
        }
        lastRenderCrashTime = now
        renderCrashCount++

        val lastUrl = SecureContextProxy.instance.unmapUrl(
            currentPageUrl
                .ifBlank { lastKnownPageUrl.orEmpty() }
                .ifBlank { originalUrl },
        )
        cleanupWebView("geckoContentCrash")

        if (renderCrashCount >= MAX_CRASH_COUNT) {
            Log.e(TAG, "Too many gecko crashes ($renderCrashCount), stopping rebuild to protect system")
            stopSelf()
            return
        }

        if (lastUrl.isNotEmpty()) {
            lifecycleScope.launch {
                val settings = browserSettingsStore.get()
                if (settings.enableBrowserVisible && settings.enableBrowserDisplay) {
                    scheduleRebuild(lastUrl, 2000L)
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayLock = Any()
    @Volatile private var isOverlayAttached = false
    // Pending delayed rebuild; tracked so cleanup can cancel it and never resurrect a hidden overlay.
    private var pendingRebuildRunnable: Runnable? = null
    /** Coalesces a burst of rebuild-class setting flips into one teardown. */
    private var pendingSettingsRebuildRunnable: Runnable? = null
    /** True while a page-touch-disabled gesture belongs to the sidebar, not the page. */
    private var touchOffSidebarGesture = false
    // Pending pressure-gated renderer release after a long hide; cancelled on show/cleanup.
    private var pendingHiddenDestroyRunnable: Runnable? = null
    // Defer COVERED cache purge / ORANGE+ immediate suspend until screensaver paints.
    private var pendingCoveredDeepenRunnable: Runnable? = null
    @Volatile private var coveredDeepenDone = false
    /** Screensaver pause arrived mid warm-up; apply once the panes paint, if still covered. */
    @Volatile private var pendingCoveredWhileWarming = false
    /** Per-pane first-paint tracking behind [isBrowserWarmingUp]. */
    private var leftPaneWarming = false
    private var rightPaneWarming = false
    private var warmupWatchdogRunnable: Runnable? = null
    private var pendingFirstPaintStewardLeft: Runnable? = null
    private var pendingFirstPaintStewardRight: Runnable? = null
    private var pendingFirstPaintSettleLeft: Runnable? = null
    private var pendingFirstPaintSettleRight: Runnable? = null

    /**
     * Browser pressure tiers (system-WebView path only). Ordered so `>=` comparisons
     * express "at least this much pressure".
     */
    private enum class PressureTier { GREEN, YELLOW, ORANGE, RED }

    private var pressureTier = PressureTier.GREEN
    /** Static floor from device class: weak devices never report better than YELLOW. */
    private var pressureFloor = PressureTier.GREEN
    private var pressureRelaxStreak = 0
    private var liteModeActive = false
    private var pressureMonitorRunnable: Runnable? = null
    /** Memory guard: page-heap probe + controlled reload before the renderer OOMs. */
    private var memoryGuardRunnable: Runnable? = null
    private val memoryGuardStreaks = mutableMapOf<String, Int>()
    private var lastMemoryGuardReloadMs = 0L
    /**
     * Unified dormant state for HA browser:
     * - [DormantKind.COVERED]: screensaver (or similar) is on top; overlay may still be attached
     * - [DormantKind.HIDDEN]: user/host hid the browser overlay
     */
    private enum class DormantKind { NONE, COVERED, HIDDEN }
    @Volatile private var dormantKind = DormantKind.NONE

    /**
     * Graded dormancy. Each rung is cheaper than the last *and* costlier to leave, so we
     * descend only as the cover/hide proves it is not a flicker. Everything above
     * [L4_SUSPENDED] keeps the HA WebSocket authenticated: waking costs one
     * `subscribe_entities` instead of reconnect + re-auth + whole-state resync, which is
     * what made uncovering the screensaver look like a blank or half-drawn dashboard.
     */
    private enum class DormancyStep { L0_ACTIVE, L1_LIGHT, L2_DEEP, L3_PARKED, L4_SUSPENDED }
    @Volatile private var dormancyStep = DormancyStep.L0_ACTIVE
    private var pendingLadderRunnable: Runnable? = null
    private var freezeAnimationsApplied = false
    private var pauseMediaApplied = false
    private var chunkLeftApplied = false
    private var chunkRightApplied = false
    private var chunkGeckoApplied = false
    /** Per-pane lite tracking so the focused tile can stay full-rate while the other batches. */
    private var liteLeftActive = false
    private var liteRightActive = false
    private var liteGeckoActive = false
    private var liteLeftFlushMs = -1
    private var liteRightFlushMs = -1
    private var liteGeckoFlushMs = -1
    private var liteModeFlushMs = BrowserWsStewardScripts.LITE_FAST_FLUSH_MS
    /**
     * Visible-idle CPU gate (pipeline B). Full renderer / JS while a finger
     * is down (or IME / HTML fullscreen / first-paint). Adaptive/low rest
     * lowers compositor and may [WebView.onPause]. Cover/hide is pipeline A.
     */
    private var touchPowerBoosted = true
    private var browserFingerDown = false
    /** Visible [WebView.onPause] only. Never shared with cover/hide [isPaused]. */
    private var visibleRestPaused = false
    /** CSS/media quiet for visible rest; independent of dormancy [freezeAnimationsApplied]. */
    private var visibleIdleQuietApplied = false
    private var pendingTouchIdleRunnable: Runnable? = null
    private var pendingVisiblePauseRunnable: Runnable? = null
    /** True while the dashboard is still allowed to finish its first full paint. */
    private var displaySettling = false
    private var pendingDisplaySettleRunnable: Runnable? = null
    /** Ownership token for the async container build; [cleanupWebView] invalidates it. */
    private val overlayGeneration = BrowserOverlayGeneration()
    /** Bumped on show/cleanup to invalidate an in-flight fade-out endAction. */
    private var hideEpoch = 0
    
    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, pane: BrowserPane) {
            // A renderer now exists, so memory-tier sampling is meaningful. Idempotent.
            startPressureMonitor()
            startMemoryGuard()

            val settings = wv.settings
            
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            
            settings.userAgentString = browserUserAgentForMode(currentSettings.userAgentMode)
            
            // One bridge per pane: a GmApi is bound to the WebView it was built for, so the
            // right tile needs its own or its userscripts would call into the left page.
            // Both share the "ava_scripts" store, which is what GM_getValue should see.
            if (isTampermonkeyActive()) {
                val api = GmApi(this@WebViewService, wv, "ava_scripts")
                if (pane == BrowserPane.LEFT) gmApi = api else gmApiRight = api
                wv.addJavascriptInterface(api, GmApi.JS_BRIDGE_NAME)
            }

            val scrollBridge = BrowserScrollBridge()
            if (pane == BrowserPane.LEFT) {
                scrollBridgeLeft = scrollBridge
            } else {
                scrollBridgeRight = scrollBridge
            }
            wv.addJavascriptInterface(scrollBridge, BrowserScrollBridge.JS_BRIDGE_NAME)

            // Dark-mode bridge on every pane: the theme listener is installed on both
            // tiles, and without this bridge the JS fallback does `location = ava-darkmode:1`
            // which navigates the right dashboard to ERR_UNSAFE_PORT.
            val darkBridge = haDarkModeBridge ?: HaDarkModeJsBridge { isDark ->
                handleHaDarkModeChanged(isDark)
            }.also { haDarkModeBridge = it }
            wv.addJavascriptInterface(darkBridge, BrowserDarkModeScripts.JS_BRIDGE_NAME)

            if (pane == BrowserPane.LEFT) {
                browserDownloadBridge = BrowserDownloadBridge(this@WebViewService)
                BrowserDownloadBridge.installOnWebView(wv, browserDownloadBridge!!)
            } else {
                // Right pane: downloads only (no second GM bridge).
                val downloadBridge = BrowserDownloadBridge(this@WebViewService)
                BrowserDownloadBridge.installOnWebView(wv, downloadBridge)
            }

            // Fallback for http(s) attachment downloads; data:/blob: go through [BrowserDownloadBridge].
            wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                com.example.ava.webcompat.BrowserDownloadHandler.handle(
                    this@WebViewService, url, userAgent, contentDisposition, mimeType
                )
            }
            
            settings.textZoom = currentSettings.fontSize
            
            
            // Prefer the default compositor path (LAYER_TYPE_NONE) over a forced HW layer —
            // an extra GL layer on an overlay window increases GPU churn and context-loss risk.
            if (currentSettings.hardwareAcceleration) {
                wv.setLayerType(View.LAYER_TYPE_NONE, null)
            } else {
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            // Scroll polish: skip glow overscroll; HA scrolls inside shadow roots so nested
            // scrolling to SwipeRefresh only fights the fling — leave it off.
            wv.overScrollMode = View.OVER_SCROLL_NEVER
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            wv.isNestedScrollingEnabled = false
            // Active priority from birth — BOUND at create made the first scrolls janky.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
            // Keep an off-screen raster so re-showing the overlay doesn't flash white.
            // Only meaningful when the window is hardware accelerated, and only while
            // the pressure tier is GREEN (it costs GPU memory the tier machine reclaims).
            if (currentSettings.hardwareAcceleration &&
                pressureTier == PressureTier.GREEN &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ) {
                settings.setOffscreenPreRaster(true)
            }

            if (pane == BrowserPane.LEFT) {
                val runtime = WebViewRuntime.cachedInfo(this@WebViewService)
                Log.i(TAG, "WebView ${runtime.versionName ?: "?"} (chrome ${runtime.majorVersion})")
            }
            ensureLegacyTranspiler()

            BrowserPlatformCompat.installOnWebView(wv)
            // Document-start: race HA's hassConnectionReady so subscribe_entities
            // can be trimmed before core.ts fires the full firehose.
            if (isStewardPageRuntimeNeeded()) {
                BrowserWsStewardScripts.installOnWebView(wv)
            }
            
            applyTouchListener(wv, pane)
            
            
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true

            // Apply dark mode if enabled and system is in dark mode
            applyDarkMode(wv)

            if (pane == BrowserPane.LEFT) {
                applyInitialScale(currentSettings.initialScale)
            } else {
                applyInitialScaleToWebView(wv, currentSettings.initialScale)
            }
            
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    // Legacy engines: lower custom-card JS the engine cannot parse.
                    if (request != null) {
                        legacyTranspiler?.intercept(request)?.let { return it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Theme-notify signal (legacy JS fallback). Never let it become a real navigation.
                    parseAvaDarkModeSignal(url)?.let { isDark ->
                        handleHaDarkModeChanged(isDark)
                        return true
                    }
                    if (BrowserDownloadBridge.handleNavigationUri(this@WebViewService, url)) {
                        return true
                    }
                    if (url.startsWith("intent://") || url.startsWith("intent:")) {
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
                                startActivity(intent)
                            } else {
                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                if (fallbackUrl != null) {
                                    view?.loadUrl(fallbackUrl)
                                }
                            }
                            return true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse intent: $url", e)
                            return true
                        }
                    }
                    if (isTampermonkeyActive() && url.endsWith(".user.js")) {
                        downloadAndInstallScript(url)
                        return true
                    }
                    // Keep absolute LAN http navigations on loopback while boost is on
                    // (HA/config may still emit the real origin in JS redirects).
                    if (currentSettings.secureContextProxyEnabled &&
                        SecureContextProxy.instance.isRunning &&
                        SecureContextProxy.isProxyableHttpUrl(url)
                    ) {
                        val mapped = SecureContextProxy.instance.mapUrl(url)
                        if (mapped != url) {
                            loadUrl(url, pane)
                            return true
                        }
                    }
                    return false
                }
                
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // If a theme signal already became the document URL (bridge missing on
                    // an older path), recover the real dashboard instead of recording it.
                    url?.let { parseAvaDarkModeSignal(it) }?.let { isDark ->
                        handleHaDarkModeChanged(isDark)
                        val restore = when (pane) {
                            BrowserPane.LEFT ->
                                originalUrl.ifBlank { lastKnownPageUrl.orEmpty() }
                            BrowserPane.RIGHT ->
                                originalUrlRight.ifBlank { lastKnownPageUrlRight.orEmpty() }
                        }.takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
                        if (restore.isNotBlank()) {
                            view?.stopLoading()
                            loadUrl(restore, pane)
                        }
                        return
                    }
                    // Placeholder loads (collapsed / staggered tile) are not navigations:
                    // recording them would clobber the restore cache and the HA entity.
                    url?.takeUnless { isPlaceholderPageUrl(it) }?.let {
                        if (pane == BrowserPane.RIGHT) {
                            currentPageUrlRight = it
                            lastKnownPageUrlRight = it
                        } else {
                            currentPageUrl = it
                            lastKnownPageUrl = it
                        }
                        markPaneWarming(pane)
                        setPaneLoaded(pane, false)
                        markPaneLoadStart(pane)
                        cancelFirstPaintFollowup(pane)
                        loadGeneration += 1
                    }
                    view?.evaluateJavascript(JS_ERROR_GUARD, null)
                    injectSecureContextMediaRewrite(view)
                    // Same early paint on both tiles (including the parked right
                    // about:blank). LEFT-only left the staggered pane white for the
                    // exclusive window, then its real HA document never got this.
                    if (resolveBrowserDarkMode() &&
                        BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)
                    ) {
                        view?.evaluateJavascript(BrowserDarkModeScripts.earlyDarkPaintJs, null)
                    }
                    // Legacy engines (no DOCUMENT_START_SCRIPT): commit time is the best
                    // remaining chance to run before HA core.js, so the steward boot and
                    // the document-start compat scripts go in here. All are idempotent and
                    // no-ops on modern engines; onPageFinished stays as the safety net.
                    if (url != null && !isPlaceholderPageUrl(url)) {
                        BrowserPlatformCompat.ensureInstalledOnPage(wv)
                        BrowserDownloadBridge.ensureInstalledOnPage(wv)
                        pushStatusBarInsetToPage(wv)
                        if (isStewardPageRuntimeNeeded()) {
                            BrowserWsStewardScripts.installEarlyOnPage(wv)
                        }
                    }
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.takeUnless { isPlaceholderPageUrl(it) }?.let {
                        if (pane == BrowserPane.RIGHT) {
                            currentPageUrlRight = it
                            lastKnownPageUrlRight = it
                            syncCurrentBrowserUrl(it, BrowserPane.RIGHT)
                        } else {
                            currentPageUrl = it
                            lastKnownPageUrl = it
                            syncCurrentBrowserUrl(it, BrowserPane.LEFT)
                        }
                    }
                    val realPage = !url.isNullOrBlank() && !isPlaceholderPageUrl(url)
                    if (realPage) setPaneLoaded(pane, true)
                    hideLoadingDialog()
                    // Placeholder/about:blank tiles are replaced right away — injecting the GM API
                    // into them is pure overhead, and the right tile starts on one.
                    if (realPage && isTampermonkeyActive() && url != null) {
                        injectMatchingScripts(url, pane)
                    }
                    if (pane == BrowserPane.LEFT) {
                        syncHaBrowserDarkMode()
                        // Do not start the right HA session here. onPageFinished is the
                        // empty shell; first-paint grace is also still mid HttpCache.
                        // Right joins after the 3s first-entry hold, if any.
                    } else if (realPage) {
                        // Left's 0/1/3/8s theme waves already ran against about:blank.
                        // Inherit the same settheme JS onto this document only — do
                        // not bump darkSyncEpoch / re-push the live left pane.
                        inheritHaDarkModeOnPane(wv)
                    }
                    // HTML finished ≠ Lovelace first paint. Keep the warm-up window
                    // open so screensaver cover / lite / stream deferral cannot starve
                    // the first entity burst that native cards need to size themselves.
                    schedulePaneFirstPaintSettled(pane)
                    // Chromium often still calls onPageFinished after a main-frame net error;
                    // only clear the await when this generation did not fail.
                    if (realPage && failedLoadGeneration != loadGeneration) {
                        clearNetworkRetryAwaiting("page_finished")
                    }
                    if (realPage && pane == BrowserPane.LEFT && url != null) {
                        notifyAiLoadSettled(url)
                    }
                    if (realPage) {
                        BrowserPlatformCompat.ensureInstalledOnPage(wv)
                        BrowserDownloadBridge.ensureInstalledOnPage(wv)
                        pushStatusBarInsetToPage(wv)
                        // Install the steward object now; do not arm stream/lite/trim yet —
                        // that waits for [scheduleFirstPaintSteward]. Master-only skips
                        // both — bootJs is what stalled native cards on cold start.
                        if (isStewardPageRuntimeNeeded()) {
                            BrowserWsStewardScripts.ensureInstalledOnPage(wv)
                            scheduleFirstPaintSteward(pane)
                        }
                        applyHaKioskModeScript(wv)
                    }
                }
                
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    if (!request.isForMainFrame) return
                    val code = error.errorCode
                    Log.e(TAG, "WebView main-frame error: $code - ${error.description}")
                    if (code in RECOVERABLE_MAIN_FRAME_ERROR_CODES) {
                        noteRemoteLoadFailure(request.url?.toString(), "net_$code")
                    }
                }

                @Deprecated("Deprecated WebView callback kept for older Android versions")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    // API 23+ also delivers the WebResourceRequest overload above.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
                    Log.e(TAG, "WebView error: $errorCode - $description")
                    if (errorCode in RECOVERABLE_MAIN_FRAME_ERROR_CODES) {
                        noteRemoteLoadFailure(failingUrl, "net_$errorCode")
                    }
                }
                
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) {
                    // LAN Home Assistant instances routinely serve self-signed certs, so those
                    // still proceed. A bad cert from a routable host is refused: proceeding would
                    // expose the JS bridges on this WebView to an injected page.
                    val failingUrl = error?.url ?: view?.url
                    if (com.example.ava.webcompat.BrowserSslPolicy.allowsCertificateError(failingUrl, TAG)) {
                        handler.proceed()
                    } else {
                        handler.cancel()
                    }
                }
                
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    val now = System.currentTimeMillis()
                    val didCrash = detail?.didCrash() ?: false
                    Log.e(TAG, "Renderer crashed, didCrash=$didCrash, crashCount=$renderCrashCount")
                    runCatching {
                        com.example.ava.crash.AvaIncidentLog.record(
                            this@WebViewService,
                            kind = com.example.ava.crash.AvaIncidentLog.KIND_RENDERER_CRASH,
                            reason = if (didCrash) "webview_renderer_crash" else "webview_renderer_gone",
                        )
                    }
                    

                    if (now - lastRenderCrashTime > CRASH_RESET_INTERVAL) {
                        renderCrashCount = 0
                    }
                    lastRenderCrashTime = now
                    renderCrashCount++
                    
                    val lastUrl = SecureContextProxy.instance.unmapUrl(
                        (webView?.url ?: currentPageUrl).ifEmpty { originalUrl },
                    )
                    // Full teardown (incl. container/dialogs) so the rebuild recreates a fresh WebView + window.
                    cleanupWebView("renderProcessGone")
                    
                    if (renderCrashCount >= MAX_CRASH_COUNT) {
                        Log.e(TAG, "Too many crashes ($renderCrashCount), stopping WebView rebuild to protect system")
                        stopSelf()
                        return true
                    }
                    
                    if (lastUrl.isNotEmpty()) {
                        lifecycleScope.launch {
                            val settings = browserSettingsStore.get()
                            if (settings.enableBrowserVisible && settings.enableBrowserDisplay) {
                                scheduleRebuild(lastUrl, 2000L)
                            }
                        }
                    }
                    return true
                }
            }
            
            wv.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                }

                override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                    if (view == null || callback == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    htmlFullscreen().show(view, callback)
                    beginTouchPowerHot("html-fullscreen")
                }

                override fun onHideCustomView() {
                    htmlFullscreen().exit()
                    scheduleTouchPowerRest()
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) {
                        return
                    }
                    if (WebViewPermissionCoordinator.handlePermissionRequest(this@WebViewService, request)) {
                        return
                    }
                    request.deny()
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    super.onPermissionRequestCanceled(request)
                    WebViewPermissionCoordinator.denyPendingRequest()
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (filePathCallback == null) return false
                    return com.example.ava.webcompat.WebViewFileChooserCoordinator.launch(
                        this@WebViewService, filePathCallback, fileChooserParams
                    )
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: android.webkit.GeolocationPermissions.Callback?,
                ) {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@WebViewService, android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this@WebViewService, android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    callback?.invoke(origin, granted, false)
                }

                // A service overlay can't host WebView's default JS dialogs (BadTokenException),
                // and an unanswered JsResult blocks all page scripts. Answer them non-interactively:
                // alert → toast + confirm; confirm/prompt → cancel (safe default, never destructive).
                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?,
                ): Boolean {
                    message?.takeIf { it.isNotBlank() }?.let {
                        AvaToast.show(this@WebViewService, it, durationMs = AvaToast.LONG_MS)
                    }
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?,
                ): Boolean {
                    Log.w(TAG, "Auto-cancelled js confirm(): $message")
                    result?.cancel()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?, url: String?, message: String?, defaultValue: String?,
                    result: android.webkit.JsPromptResult?,
                ): Boolean {
                    Log.w(TAG, "Auto-cancelled js prompt(): $message")
                    result?.cancel()
                    return true
                }

                override fun onJsBeforeUnload(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?,
                ): Boolean {
                    // Never let a page veto navigation in a kiosk-style browser.
                    result?.confirm()
                    return true
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val cm = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
                    val level = when (cm.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "error"
                        ConsoleMessage.MessageLevel.WARNING -> "warn"
                        ConsoleMessage.MessageLevel.DEBUG -> "debug"
                        ConsoleMessage.MessageLevel.TIP -> "tip"
                        else -> "log"
                    }
                    val src = cm.sourceId()?.takeIf { it.isNotBlank() }
                    val line = cm.lineNumber()
                    val body = cm.message().orEmpty()
                    val msg = if (src != null && line > 0) "$body ($src:$line)" else body
                    appendConsole(level, msg, pane)
                    // Mirror to logcat: returning true below suppresses chromium's own
                    // console logging, which made on-device diagnosis via adb impossible.
                    Log.i(TAG, "console[$level] $msg")
                    if (level == "error") {
                        maybeReportHaEs5CrashLoop(body, src)
                        maybeReportModernCardFailure(body, src)
                    }
                    return true
                }
            }
            
            
            wv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    if (webConsoleCompose != null) {
                        hideWebConsolePanel()
                        return@setOnKeyListener true
                    }
                    if (htmlFullscreen().isActive()) {
                        htmlFullscreen().exit()
                        return@setOnKeyListener true
                    }
                    if (currentSettings.backKeyHideEnabled) {
                        if (wv.canGoBack()) {
                            wv.goBack()
                        } else {
                            dismissBrowserFromUser()
                        }
                    } else if (wv.canGoBack()) {
                        wv.goBack()
                    }
                    true
                } else {
                    false
                }
            }
            if (engineSurface != null) {
                // Background WebView must not steal IME focus from GeckoView.
                wv.isFocusable = false
                wv.isFocusableInTouchMode = false
            } else {
                wv.isFocusable = true
                wv.isFocusableInTouchMode = true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Throttle — every scroll frame used to ping screensaver and hitch flings.
                var lastScrollActivityMs = 0L
                wv.setOnScrollChangeListener { _, _, _, _, _ ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastScrollActivityMs < 800L) return@setOnScrollChangeListener
                    lastScrollActivityMs = now
                    ScreensaverController.onUserActivity()
                }
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applyTouchListener(wv: WebView?, pane: BrowserPane) {
        if (wv == null) return
        // Lightweight scroll assist (not NestedWebView): protect mid-page flings.
        // Top-strip pull-to-refresh is owned by the overlay container.
        BrowserScrollAssist.install(
            webView = wv,
            pullZonePx = { pullRefreshTriggerZonePx() },
            pullRefreshEnabled = { currentSettings.pullRefreshEnabled },
            haCanScrollUp = { scrollBridgeFor(pane)?.haCanScrollUp() == true },
            onActionDown = { setFocusedPane(pane) },
            shouldConsume = consume@{ event ->
                if (!currentSettings.touchEnabled) return@consume true
                if (!currentSettings.dragEnabled) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN,
                        MotionEvent.ACTION_POINTER_UP,
                        MotionEvent.ACTION_SCROLL -> return@consume true
                    }
                }
                false
            },
        )
    }

    /** True when page scroll / pull-refresh / pinch should be ignored. */
    private fun shouldBlockPageDrag(): Boolean =
        currentSettings.touchEnabled && !currentSettings.dragEnabled

    private fun applyInitialScaleToWebView(wv: WebView, scale: Int) {
        val zoomLevel = scale.coerceIn(0, 500)
        if (zoomLevel == 0) {
            wv.settings.useWideViewPort = true
        } else {
            wv.settings.useWideViewPort = false
            wv.setInitialScale(zoomLevel)
        }
    }

    private fun applyInitialScale(scale: Int) {
        currentSettings = currentSettings.copy(initialScale = scale.coerceIn(0, 500))
        // Apply to GeckoView if active
        engineSurface?.setInitialScale(currentSettings.initialScale)
        // Apply to WebView(s)
        webView?.let { applyInitialScaleToWebView(it, currentSettings.initialScale) }
        webViewRight?.let { applyInitialScaleToWebView(it, currentSettings.initialScale) }
    }

    private fun resolveBrowserDarkMode(): Boolean =
        BrowserDarkModeResolver.shouldUseDarkMode(this, followEnabled = true)

    private fun applyHostDarkModeOverride(settings: BrowserSettings): BrowserSettings {
        val hostFollowOverride = BrowserDarkModeResolver.readHostFollowOverride(this)
        return if (hostFollowOverride == null) settings
        else settings.copy(followSystemDarkMode = hostFollowOverride)
    }

    private fun applyDarkMode(wv: WebView) {
        val shouldUseDarkMode = resolveBrowserDarkMode()
        // When HA theme sync is on, official and third-party modes.light/dark
        // already paint the page. Chromium force-dark would re-filter those
        // palettes and break theme colors.
        val algorithmic = shouldUseDarkMode &&
            !BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                wv.settings.isAlgorithmicDarkeningAllowed = algorithmic
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply dark mode setting", e)
            }
        } else {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                        wv.settings,
                        algorithmic
                    )
                } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    WebSettingsCompat.setForceDark(
                        wv.settings,
                        if (algorithmic) {
                            WebSettingsCompat.FORCE_DARK_ON
                        } else {
                            WebSettingsCompat.FORCE_DARK_OFF
                        }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply WebViewCompat dark mode setting", e)
            }
        }

        // Native backdrop while the document is blank (refresh / first paint).
        // Does not replace HA session theme sync — only hides the white flash.
        try {
            wv.setBackgroundColor(
                if (shouldUseDarkMode) {
                    android.graphics.Color.parseColor("#111111")
                } else {
                    android.graphics.Color.WHITE
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply WebView backdrop color", e)
        }

        engineSurface?.setDarkMode(shouldUseDarkMode)
    }

    private fun refreshDarkModeInternal() {
        lifecycleScope.launch {
            currentSettings = applyHostDarkModeOverride(browserSettingsStore.get())
            val shouldUseDarkMode = resolveBrowserDarkMode()

            engineSurface?.setDarkMode(shouldUseDarkMode)

            webView?.let { wv ->
                applyDarkMode(wv)
            }
            webViewRight?.let { wv ->
                applyDarkMode(wv)
            }

            if (BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)) {
                scheduleHaDarkModeSync(shouldUseDarkMode)
                installHaThemeListener()
            }
        }
    }

    private fun syncHaBrowserDarkMode() {
        val shouldUseDarkMode = resolveBrowserDarkMode()
        engineSurface?.setDarkMode(shouldUseDarkMode)
        webView?.let { applyDarkMode(it) }
        webViewRight?.let { applyDarkMode(it) }
        if (BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)) {
            scheduleHaDarkModeSync(shouldUseDarkMode)
            installHaThemeListener()
        }
    }

    /**
     * Same session-theme JS as [pushHaThemeMode], but only on the pane that just
     * became a real HA document. The script already retries until `hass.themes`
     * exists; do not go through [scheduleHaDarkModeSync] or the left tile gets
     * another `settheme` wave.
     */
    private fun inheritHaDarkModeOnPane(wv: WebView) {
        val shouldUseDarkMode = resolveBrowserDarkMode()
        applyDarkMode(wv)
        if (!BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)) return
        // Same mute as [pushHaThemeMode]: right settheme must not bounce into
        // handleHaDarkModeChanged and re-push the left pane.
        suppressHaToAvaSync = true
        val script = if (shouldUseDarkMode) {
            BrowserDarkModeScripts.homeAssistantDarkModeJs
        } else {
            BrowserDarkModeScripts.homeAssistantLightModeJs
        }
        wv.evaluateJavascript(script, null)
        wv.evaluateJavascript(BrowserDarkModeScripts.installHomeAssistantThemeListenerJs, null)
        mainHandler.postDelayed({ suppressHaToAvaSync = false }, 2000L)
    }

    private fun scheduleHaDarkModeSync(dark: Boolean) {
        val epoch = ++darkSyncEpoch
        val delays = if (EngineCapabilities.GECKO_BUNDLED) {
            listOf(0L, 3000L)
        } else {
            listOf(0L, 1000L, 3000L, 8000L)
        }
        delays.forEach { delayMs ->
            mainHandler.postDelayed({
                if (epoch == darkSyncEpoch &&
                    BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)
                ) {
                    pushHaThemeMode(dark)
                    installHaThemeListener()
                }
            }, delayMs)
        }
    }

    private fun pushHaThemeMode(dark: Boolean) {
        if (suppressAvaToHaSync ||
            !BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)
        ) return
        suppressHaToAvaSync = true
        val script = if (dark) {
            BrowserDarkModeScripts.homeAssistantDarkModeJs
        } else {
            BrowserDarkModeScripts.homeAssistantLightModeJs
        }
        webView?.evaluateJavascript(script, null)
        webViewRight?.evaluateJavascript(script, null)
        engineSurface?.evaluateJavascript(script)
        mainHandler.postDelayed({ suppressHaToAvaSync = false }, 2000L)
    }

    private fun installHaThemeListener() {
        if (!BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)) return
        val script = BrowserDarkModeScripts.installHomeAssistantThemeListenerJs
        webView?.evaluateJavascript(script, null)
        webViewRight?.evaluateJavascript(script, null)
        engineSurface?.evaluateJavascript(script)
    }

    private fun handleHaDarkModeChanged(isDark: Boolean) {
        if (suppressHaToAvaSync ||
            !BrowserDarkModeResolver.shouldSyncHaTheme(currentSettings.followSystemDarkMode)
        ) return
        val manager = DarkModeManager.getInstance(this)
        if (manager.isDarkMode() == isDark) return
        suppressAvaToHaSync = true
        manager.setDarkMode(isDark)
        mainHandler.postDelayed({ suppressAvaToHaSync = false }, 2000L)
    }
    
    private fun loadUrl(url: String, pane: BrowserPane = BrowserPane.LEFT) {
        val finalUrl = prepareUrlForBrowserLoad(normalizeUrl(url))
        val surf = engineSurface
        if (surf != null) {
            surf.loadUrl(finalUrl)
            return
        }
        when (pane) {
            BrowserPane.RIGHT -> webViewRight?.loadUrl(finalUrl) ?: webView?.loadUrl(finalUrl)
            BrowserPane.LEFT -> webView?.loadUrl(finalUrl)
        }
    }

    /**
     * When HTTP page boost is on, ensure the loopback proxy and rewrite onto
     * 127.0.0.1. Always unmap first so callers can pass either form safely.
     */
    private fun prepareUrlForBrowserLoad(normalized: String): String {
        val real = SecureContextProxy.instance.unmapUrl(normalized)
        // Stale loopback after process death / lost mapping: do not treat as HA.
        if (SecureContextProxy.isLoopbackHttpUrl(real)) {
            Log.w(TAG, "HTTP page boost: refusing to load unresolved loopback URL: $real")
            return BrowserHaKioskScripts.resolveLoadUrl(real, currentSettings)
        }
        if (!currentSettings.secureContextProxyEnabled) {
            return BrowserHaKioskScripts.resolveLoadUrl(real, currentSettings)
        }
        if (!SecureContextProxy.isProxyableHttpUrl(real)) {
            return BrowserHaKioskScripts.resolveLoadUrl(real, currentSettings)
        }
        if (!SecureContextProxy.instance.ensureRunningFor(real)) {
            Log.w(TAG, "HTTP page boost: proxy failed to start; loading real URL")
            return BrowserHaKioskScripts.resolveLoadUrl(real, currentSettings)
        }
        val mapped = SecureContextProxy.instance.mapUrl(real)
        Log.d(TAG, "HTTP page boost map: $real -> $mapped")
        return BrowserHaKioskScripts.resolveLoadUrl(mapped, currentSettings)
    }

    private fun applyHaKioskModeScript(wv: WebView?) {
        val script = BrowserHaKioskScripts.scriptForSettings(currentSettings)
        wv?.evaluateJavascript(script, null)
        if (wv == null || wv === webView) {
            engineSurface?.evaluateJavascript(script)
        }
    }

    private fun applyHaKioskModeToAllSurfaces() {
        val script = BrowserHaKioskScripts.scriptForSettings(currentSettings)
        webView?.evaluateJavascript(script, null)
        webViewRight?.evaluateJavascript(script, null)
        engineSurface?.evaluateJavascript(script)
    }

    /** Sidebar: flip HA kiosk chrome off ↔ last strategy; CSS applies live, no reload. */
    private fun toggleHaKioskModeInternal() {
        lifecycleScope.launch {
            try {
                browserSettingsStore.toggleHaKioskMode()
                currentSettings = applyHostDarkModeOverride(browserSettingsStore.get())
                // Shadow-DOM CSS is idempotent and live — do not loadUrl (would flash HA).
                applyHaKioskModeToAllSurfaces()
                // Pages loaded while `auto` still appended ?kiosk have a second
                // hider (HACS kiosk-mode) pinned to the URL; drop the params
                // live so one toggle controls exactly one injector. Never touch
                // panes whose CONFIGURED URL carries kiosk params — those are
                // user-authored plugin intent, not our leftovers.
                if (BrowserHaKioskScripts.usesCss(currentSettings.haKioskMode) ||
                    !BrowserHaKioskScripts.isEnabled(currentSettings.haKioskMode)
                ) {
                    val strip = BrowserHaKioskScripts.stripKioskParamsLiveJs
                    if (!BrowserHaKioskScripts.hasKioskParams(originalUrl)) {
                        webView?.evaluateJavascript(strip, null)
                        engineSurface?.evaluateJavascript(strip)
                    }
                    if (!BrowserHaKioskScripts.hasKioskParams(originalUrlRight)) {
                        webViewRight?.evaluateJavascript(strip, null)
                    }
                }
                browserSidebarOverlay?.refreshPanelContent()
            } catch (e: Exception) {
                Log.w(TAG, "toggleHaKioskMode failed", e)
            }
        }
    }

    /** Early page inject so media/fetch stay on loopback while boost is active. */
    private fun injectSecureContextMediaRewrite(view: WebView?) {
        if (!currentSettings.secureContextProxyEnabled) return
        val proxy = SecureContextProxy.instance
        if (!proxy.isRunning) return
        val target = proxy.targetOrigin() ?: return
        val loopback = proxy.loopbackOrigin() ?: return
        val script = SecureContextProxy.mediaRewriteScript(target, loopback)
        view?.evaluateJavascript(script, null)
        engineSurface?.evaluateJavascript(script)
    }

    /** Reload the focused renderer (gecko surface when present, otherwise the focused WebView). */
    private fun reloadActive() {
        reloadPane(focusedPane)
    }

    /** True for http(s) dashboard targets; local asset / about:blank never arm network retry. */
    private fun isRemoteDashboardUrl(url: String): Boolean {
        val mapped = SecureContextProxy.instance.unmapUrl(normalizeUrl(url)).trim()
        if (mapped.isEmpty() || isPlaceholderPageUrl(mapped)) return false
        if (NON_WEB_SCHEME.containsMatchIn(mapped)) return false
        return mapped.startsWith("http://", ignoreCase = true) ||
            mapped.startsWith("https://", ignoreCase = true)
    }

    /**
     * Arm a one-shot restore reload after a remote main-frame network failure.
     * Offline kiosk users with local pages never hit this path.
     */
    private fun noteRemoteLoadFailure(failingUrl: String?, reason: String) {
        val candidate = sequenceOf(
            failingUrl.orEmpty(),
            if (focusedPane == BrowserPane.RIGHT) originalUrlRight else originalUrl,
            if (focusedPane == BrowserPane.RIGHT) currentPageUrlRight else currentPageUrl,
            originalUrl,
            currentPageUrl,
        ).firstOrNull { isRemoteDashboardUrl(it) } ?: return
        failedLoadGeneration = loadGeneration
        if (!awaitingNetworkRetry) {
            Log.i(TAG, "Awaiting network restore to retry dashboard ($reason): $candidate")
        }
        awaitingNetworkRetry = true
        ensureNetworkRetryMonitor()
    }

    private fun clearNetworkRetryAwaiting(reason: String) {
        if (awaitingNetworkRetry) {
            awaitingNetworkRetry = false
            failedLoadGeneration = -1
            Log.d(TAG, "Cleared network-retry await ($reason)")
        }
        releaseNetworkRetryMonitor()
    }

    private fun ensureNetworkRetryMonitor() {
        if (networkRetryCallback != null) return
        // registerDefaultNetworkCallback is API 24; Class getSystemService is API 23.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { maybeRetryAfterNetworkRestore("onAvailable") }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    mainHandler.post { maybeRetryAfterNetworkRestore("validated") }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkRetryCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
    }

    private fun releaseNetworkRetryMonitor() {
        val callback = networkRetryCallback ?: return
        networkRetryCallback = null
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
        }
    }

    private fun maybeRetryAfterNetworkRestore(source: String) {
        if (!awaitingNetworkRetry) return
        if (isBrowserHidden || isCreating) return
        if (containerView == null || (webView == null && engineSurface == null)) return
        if (!sequenceOf(originalUrl, currentPageUrl, originalUrlRight, currentPageUrlRight)
                .any { isRemoteDashboardUrl(it) }
        ) {
            clearNetworkRetryAwaiting("no_remote_url")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNetworkRestoreRetryAtMs < NETWORK_RESTORE_RETRY_MIN_INTERVAL_MS) return
        lastNetworkRestoreRetryAtMs = now
        Log.i(TAG, "Network restored ($source) — retrying failed dashboard load")
        setPaneLoaded(focusedPane, false)
        markPaneLoadStart(focusedPane, now)
        reloadActive()
    }

    private fun reloadPane(pane: BrowserPane) {
        abortRightStaggerForReload()
        val surf = engineSurface
        if (surf != null) {
            surf.reload()
            return
        }
        when (pane) {
            BrowserPane.RIGHT -> webViewRight?.reload() ?: webView?.reload()
            BrowserPane.LEFT -> webView?.reload()
        }
    }

    /**
     * Bypass HTTP cache for one navigation on the target pane, so "clear cache / force refresh"
     * actually takes effect on the right/bottom tile too (not only the left WebView).
     */
    private fun hardReloadPane(pane: BrowserPane) {
        val surf = engineSurface
        if (surf != null) {
            surf.reload()
            return
        }
        val wv = webViewFor(pane) ?: return
        setPaneLoaded(pane, false)
        markPaneLoadStart(pane)
        val configured = if (pane == BrowserPane.RIGHT) {
            originalUrlRight.ifBlank { currentPageUrlRight }
        } else {
            originalUrl.ifBlank { currentPageUrl }
        }
        val live = wv.url?.takeIf { it.isNotBlank() && !isPlaceholderPageUrl(it) }
        val rawUrl = (live ?: configured).takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
        val url = SecureContextProxy.instance.unmapUrl(normalizeUrl(rawUrl))
        if (url.isBlank() ||
            isPlaceholderPageUrl(url) ||
            (SecureContextProxy.isLoopbackHttpUrl(url) && !currentSettings.secureContextProxyEnabled)
        ) {
            // Prefer a known-good configured URL over reloading a polluted document.
            val fallback = configured.takeUnless { isPlaceholderPageUrl(it) }.orEmpty()
            if (fallback.isNotBlank()) {
                loadUrl(fallback, pane)
            } else {
                wv.reload()
            }
            return
        }
        val settings = wv.settings
        val previousMode = settings.cacheMode
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        loadUrl(url, pane)
        mainHandler.postDelayed({
            try {
                settings.cacheMode = previousMode
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore WebView cacheMode", e)
            }
        }, 5000L)
    }

    private fun clearBrowserCacheInternal() {
        if (containerView == null || (webView == null && engineSurface == null)) {
            Log.d(TAG, "clearBrowserCache: no browser active")
            return
        }
        // Disk cache is process-wide; call on both for API completeness / main-thread safety.
        try {
            webView?.clearCache(true)
        } catch (e: Exception) {
            Log.w(TAG, "clearCache left failed", e)
        }
        try {
            webViewRight?.clearCache(true)
        } catch (e: Exception) {
            Log.w(TAG, "clearCache right failed", e)
        }
        engineSurface?.clearCacheOnly()
        // Sidebar "clear cache" must refresh every live tile — focused-only left the
        // other split pane on stale JS / a polluted restore URL after frequent reloads.
        Log.d(TAG, "clearBrowserCache: hard-reloading all active panes")
        abortRightStaggerForReload()
        hardReloadActivePanes()
    }

    private fun clearCookiesAndHistoryInternal() {
        webView?.apply {
            try {
                clearFormData()
                clearHistory()
            } catch (e: Exception) {
                Log.w(TAG, "clear cookies/history left failed", e)
            }
        }
        webViewRight?.apply {
            try {
                clearFormData()
                clearHistory()
            } catch (e: Exception) {
                Log.w(TAG, "clear cookies/history right failed", e)
            }
        }
        engineSurface?.clearCookiesAndSiteData()
        hardReloadActivePanes()
    }

    /** Hard-reload every live pane (used when settings like UA / scale must apply to both). */
    private fun hardReloadAllPanes() {
        abortRightStaggerForReload()
        if (engineSurface != null) {
            hardReloadPane(BrowserPane.LEFT)
            return
        }
        if (webView != null) hardReloadPane(BrowserPane.LEFT)
        if (splitRightPaneActive && webViewRight != null) {
            hardReloadPane(BrowserPane.RIGHT)
        }
    }

    /**
     * Hard-reload panes that currently have HA URLs. Skips collapsed/blank tiles so a single-pane
     * split layout does not revive an empty side.
     */
    private fun hardReloadActivePanes() {
        abortRightStaggerForReload()
        if (engineSurface != null) {
            hardReloadPane(BrowserPane.LEFT)
            return
        }
        if (!isSplitViewActive()) {
            hardReloadPane(BrowserPane.LEFT)
            return
        }
        var reloaded = false
        if (splitLeftPaneActive && webView != null) {
            hardReloadPane(BrowserPane.LEFT)
            reloaded = true
        }
        if (splitRightPaneActive && webViewRight != null) {
            hardReloadPane(BrowserPane.RIGHT)
            reloaded = true
        }
        if (!reloaded) {
            hardReloadPane(focusedPane)
        }
    }

    private fun engineCanGoBack(): Boolean =
        engineSurface?.canGoBack() ?: (focusedWebView()?.canGoBack() == true)

    private fun engineGoBack() {
        val surf = engineSurface
        if (surf != null) surf.goBack() else focusedWebView()?.goBack()
    }

    private fun engineCanGoForward(): Boolean =
        engineSurface?.canGoForward() ?: (focusedWebView()?.canGoForward() == true)

    private fun engineGoForward() {
        val surf = engineSurface
        if (surf != null) surf.goForward() else focusedWebView()?.goForward()
    }

    private fun extractCurrentPageText(): String {
        // This bridges a synchronous caller to async JS extraction by blocking on a latch.
        // On the main thread that would deadlock (await blocks Main while the result coroutine
        // needs Main), so fail fast instead of freezing the UI for the full timeout.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "must_call_off_main_thread")
                .toString()
        }
        val activeWebView = webView
        val extractor = readabilityExtractor
        if (activeWebView == null || extractor == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "browser_not_ready")
                .toString()
        }

        val latch = CountDownLatch(1)
        var result = JSONObject()
            .put("ok", false)
            .put("error", "extract_timeout")

        lifecycleScope.launch {
            try {
                val content = extractor.extract()
                result = if (content != null) {
                    val excerpt = content.excerpt?.let {
                        if (it.length > 280) it.substring(0, 280) + "..." else it
                    } ?: ""
                    val textContent = content.textContent?.let {
                        if (it.length > 1200) it.substring(0, 1200) + "..." else it
                    } ?: ""
                    JSONObject()
                        .put("ok", true)
                        .put("url", content.url)
                        .put("title", content.title)
                        .put("text", textContent)
                        .put("excerpt", excerpt)
                        .put("length", content.length)
                        .put("truncated", content.length > textContent.length)
                } else {
                    JSONObject()
                        .put("ok", false)
                        .put("error", "extract_failed")
                }
            } catch (e: Exception) {
                result = JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            return JSONObject()
                .put("ok", false)
                .put("error", "extract_timeout")
                .toString()
        }
        return result.toString()
    }
    
    private fun updateUrl(url: String) {
        updateUrl(url, BrowserPane.LEFT)
    }

    private fun updateUrl(url: String, pane: BrowserPane) {
        val normalizedUrl = normalizeUrl(url)
        if (containerView == null) {
            if (pane == BrowserPane.RIGHT) {
                pendingRestoreUrlRight = normalizedUrl
            }
            showWebView(if (pane == BrowserPane.RIGHT) originalUrl.ifBlank { normalizedUrl } else normalizedUrl)
            return
        }
        if (pane == BrowserPane.RIGHT && webViewRight == null) {
            // Split not active — ignore right-pane updates.
            return
        }
        if (isBrowserHidden) {
            if (pendingSettingsRebuild) {
                if (pane == BrowserPane.RIGHT) {
                    pendingRestoreUrlRight = normalizedUrl
                    originalUrlRight = normalizedUrl
                } else {
                    pendingRestoreUrl = normalizedUrl
                    originalUrl = normalizedUrl
                }
                setPaneLoaded(pane, false)
                Log.d(TAG, "Deferred hidden browser URL update until pending settings rebuild")
                return
            }
            if (pane == BrowserPane.RIGHT) {
                originalUrlRight = normalizedUrl
                val normalizedCurrentPage = currentPageUrlRight.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                if (normalizedCurrentPage != normalizedUrl) {
                    setPaneLoaded(BrowserPane.RIGHT, false)
                    loadUrl(normalizedUrl, BrowserPane.RIGHT)
                }
            } else {
                originalUrl = normalizedUrl
                val normalizedCurrentPage = currentPageUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                if (normalizedCurrentPage != normalizedUrl) {
                    setPaneLoaded(BrowserPane.LEFT, false)
                    loadUrl(normalizedUrl, BrowserPane.LEFT)
                }
            }
        } else {
            if (pane == BrowserPane.RIGHT) {
                val normalizedCurrentPage = currentPageUrlRight.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                val normalizedOriginal = originalUrlRight.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                val shouldKeepCurrentPage =
                    normalizedCurrentPage != null &&
                        normalizedUrl == normalizedOriginal &&
                        normalizedCurrentPage != normalizedUrl
                if (!shouldKeepCurrentPage && normalizedCurrentPage != normalizedUrl) {
                    originalUrlRight = normalizedUrl
                    loadUrl(normalizedUrl, BrowserPane.RIGHT)
                }
            } else {
                val normalizedCurrentPage = currentPageUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                val normalizedOriginal = originalUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                val shouldKeepCurrentPage =
                    normalizedCurrentPage != null &&
                        normalizedUrl == normalizedOriginal &&
                        normalizedCurrentPage != normalizedUrl

                if (!shouldKeepCurrentPage && normalizedCurrentPage != normalizedUrl) {
                    originalUrl = normalizedUrl
                    loadUrl(normalizedUrl, BrowserPane.LEFT)
                }
            }
        }
    }
    
    private fun showOrRefreshWebView(url: String, pane: BrowserPane = BrowserPane.LEFT) {
        if (url.isBlank()) {
            // Blank navigation belongs to applyHaRemoteUrls (collapse / close). Ignore here.
            return
        }
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isBlank()) return
        if (pane == BrowserPane.RIGHT) {
            if (originalUrlRight.isEmpty()) originalUrlRight = normalizedUrl
        } else if (originalUrl.isEmpty()) {
            originalUrl = normalizedUrl
        }
        if (containerView == null || (webView == null && engineSurface == null)) {
            if (pane == BrowserPane.RIGHT) {
                pendingRestoreUrlRight = normalizedUrl
                lifecycleScope.launch {
                    val left = try {
                        VoiceSatelliteSettingsStore(applicationContext.voiceSatelliteSettingsStore)
                            .get().haRemoteUrl
                    } catch (e: Exception) {
                        ""
                    }.ifBlank { normalizedUrl }
                    showWebView(left)
                }
            } else {
                showWebView(normalizedUrl)
            }
        } else if (isBrowserHidden) {
            if (pendingSettingsRebuild) {
                pendingSettingsRebuild = false
                if (pane == BrowserPane.RIGHT) pendingRestoreUrlRight = normalizedUrl
                val restoreUrl = bestRestoreUrl(if (pane == BrowserPane.LEFT) normalizedUrl else originalUrl)
                cleanupWebView("showOrRefresh-settings-rebuild-from-hidden")
                WebViewService.show(this, restoreUrl)
                return
            }
            if (pane == BrowserPane.RIGHT && webViewRight != null) {
                setFocusedPane(BrowserPane.RIGHT)
                originalUrlRight = normalizedUrl
            }
            resumeBrowserFromHide(
                if (pane == BrowserPane.RIGHT) originalUrl.ifBlank { normalizedUrl } else normalizedUrl
            )
            if (pane == BrowserPane.RIGHT && webViewRight != null) {
                loadUrl(normalizedUrl, BrowserPane.RIGHT)
            }
        } else {
            if (pane == BrowserPane.RIGHT) {
                if (webViewRight == null) return
                setFocusedPane(BrowserPane.RIGHT)
                val normalizedCurrentPage = currentPageUrlRight.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                val normalizedOriginal = originalUrlRight.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                val shouldKeepCurrentPage =
                    normalizedCurrentPage != null &&
                        normalizedUrl == normalizedOriginal &&
                        normalizedCurrentPage != normalizedUrl
                if (shouldKeepCurrentPage) return
                if (normalizedCurrentPage == normalizedUrl) {
                    webViewRight?.reload()
                    return
                }
                originalUrlRight = normalizedUrl
                loadUrl(normalizedUrl, BrowserPane.RIGHT)
                return
            }
            setFocusedPane(BrowserPane.LEFT)
            val normalizedCurrentPage = currentPageUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
            val normalizedOriginal = originalUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
            val shouldKeepCurrentPage =
                normalizedCurrentPage != null &&
                    normalizedUrl == normalizedOriginal &&
                    normalizedCurrentPage != normalizedUrl

            if (shouldKeepCurrentPage) {
                return
            }

            if (normalizedCurrentPage == normalizedUrl) {
                reloadActive()
                return
            }

            reloadActive()
            if (normalizedCurrentPage != normalizedUrl) {
                originalUrl = normalizedUrl
                loadUrl(normalizedUrl, BrowserPane.LEFT)
            }
        }
    }
    
    private fun refreshIfStuckInternal() {
        if (containerView == null || (webView == null && engineSurface == null) || isBrowserHidden) {
            Log.d(TAG, "refreshIfStuck: no active visible browser")
            return
        }
        
        val startedAt = paneLoadStart(focusedPane)
        val loadingTooLong = !isPageLoaded && startedAt > 0 &&
            (System.currentTimeMillis() - startedAt) > 10000

        if (!isPageLoaded || loadingTooLong) {
            Log.d(TAG, "refreshIfStuck: page not loaded or stuck (pane=$focusedPane, isPageLoaded=$isPageLoaded, loadingTime=${System.currentTimeMillis() - startedAt}ms), reloading")
            setPaneLoaded(focusedPane, false)
            markPaneLoadStart(focusedPane)
            reloadActive()
        } else {
            Log.d(TAG, "refreshIfStuck: page already loaded, no action needed")
        }
    }
    
    private fun forceRefreshInternal(allActivePanes: Boolean = false) {
        if (containerView == null || (webView == null && engineSurface == null) || isBrowserHidden) {
            Log.d(TAG, "forceRefresh: no browser active")
            return
        }
        abortRightStaggerForReload()
        if (allActivePanes && isSplitViewActive()) {
            Log.d(
                TAG,
                "forceRefresh: HA refresh — hard-reloading active panes " +
                    "(left=$splitLeftPaneActive right=$splitRightPaneActive)",
            )
            hardReloadActivePanes()
        } else {
            Log.d(TAG, "forceRefresh: hard-reloading focused pane=$focusedPane")
            hardReloadPane(focusedPane)
        }
    }
    
    private var isPaused = false
    @Volatile private var isBrowserHidden = false
    /** Show was requested. Holds the first pair seat until the window attaches. */
    @Volatile private var splitSeatReserved = false
    @Volatile private var overlayReceiptToken = 0L
    @Volatile private var hideRequestedDuringCreate = false
    /**
     * Latest SHOW while [isCreating]. A hide-during-create cancel must not drop
     * HA browser_display ON — [abortCreateAndReplayIfNeeded] replays this URL.
     */
    private var pendingShowDuringCreateUrl: String? = null

    private fun latchShowDuringCreate(url: String) {
        cancelDeferredDestroy()
        hideRequestedDuringCreate = false
        isBrowserHidden = false
        if (dormantKind == DormantKind.HIDDEN) {
            dormantKind = DormantKind.NONE
        }
        pendingShowDuringCreateUrl = url.ifBlank { originalUrl }.ifBlank { lastKnownPageUrl.orEmpty() }
        Log.d(TAG, "Show while creating — latch (${pendingShowDuringCreateUrl})")
    }

    private fun abortCreateAndReplayIfNeeded(reason: String) {
        val replayUrl = pendingShowDuringCreateUrl
        pendingShowDuringCreateUrl = null
        isCreating = false
        val finish = {
            cleanupWebView(reason)
            pendingShowDuringCreateUrl = replayUrl
            replayPendingShowAfterCreate()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish()
        } else {
            mainHandler.post(finish)
        }
    }

    private fun replayPendingShowAfterCreate() {
        val url = pendingShowDuringCreateUrl ?: return
        pendingShowDuringCreateUrl = null
        if (isCreating) return
        if (containerView != null && (webView != null || engineSurface != null) && !isBrowserHidden) {
            return
        }
        Log.i(TAG, "Replaying show after create cancellation")
        showWebView(url)
    }

    private fun resumePausedRendererIfNeeded() {
        if (!isPaused && !visibleRestPaused) return
        webView?.onResume()
        webViewRight?.onResume()
        engineSurface?.setHostPaused(false)
        isPaused = false
        visibleRestPaused = false
    }

    // Host-process death watchdog (gecko pack only): linkToDeath on a Binder the host passes in.
    @Volatile private var hostLivenessToken: IBinder? = null
    @Volatile private var hostDeathRecipient: IBinder.DeathRecipient? = null

    private fun resumeBrowserFromHide(url: String, navigate: Boolean = true) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            promoteForegroundIfNeeded()
        }
        // Cancel mid-flight fade-out / pending soft-hide destroy before anything else.
        hideEpoch += 1
        containerView?.animate()?.cancel()
        cancelDeferredDestroy()
        cancelCoveredDeepen()

        val normalizedUrl = if (navigate && url.isNotBlank()) normalizeUrl(url) else ""
        dismissAuxiliaryDialogs()
        val wasHidden = isBrowserHidden
        isBrowserHidden = false
        dormantKind = DormantKind.NONE
        coveredDeepenDone = false
        syncOffscreenPreRaster()

        // Start from transparent so resume never flashes opaque.
        // Gecko must not linger at VISIBLE+alpha 0: the focusable overlay steals
        // input and SurfaceFlinger shows Ava underneath — the "can't pull up" state.
        if (EngineCapabilities.GECKO_BUNDLED) {
            unparkGeckoOverlayWindow()
            containerView?.alpha = 0f
            containerView?.visibility = View.VISIBLE
            browserPaneFitW = -1
            browserPaneFitH = -1
            setOverlayCompositorOpaque(false, "resume")
            syncKeepScreenOnFromSettings()
            ensureOverlayOnTop()
            OverlayLayerSplit.sync()
            containerView?.post { dispatchBrowserViewportResize() }
            val reveal = containerView
            OverlayLayerSplit.runAfterColdStart {
                if (containerView !== reveal || isBrowserHidden) return@runAfterColdStart
                OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.BROWSER) {
                    if (containerView !== reveal || isBrowserHidden) return@fadeWhenPaneReady
                    reveal?.alpha = 1f
                }
            }
            bringOverlayToFront()
        } else {
            containerView?.alpha = 0f
            // Must precede ensureOverlayOnTop(): a re-attach reads windowParams.format.
            setOverlayCompositorOpaque(false, "resume")
            syncKeepScreenOnFromSettings()
            ensureOverlayOnTop()
            fadeInContainer()
        }

        webView?.onResume()
        webViewRight?.onResume()
        engineSurface?.setHostPaused(false)
        isPaused = false
        visibleRestPaused = false
        applyActiveRendererPriority()

        // Un-park the HA WebSocket before anything else touches the page; the
        // frontend reconnects on its own — no reload needed for a warm resume.
        applyDormancyStep(DormancyStep.L0_ACTIVE, "resume-from-hide")
        beginDisplaySettle("resume-from-hide")

        val urlChanged = if (navigate && normalizedUrl.isNotBlank()) {
            val normalizedCurrent = currentPageUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
            val normalizedOriginal = originalUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
            normalizedOriginal != null && normalizedUrl != normalizedOriginal &&
                normalizedUrl != normalizedCurrent
        } else {
            false
        }

        if (navigate && normalizedUrl.isNotBlank()) {
            when {
                urlChanged || originalUrl.isEmpty() -> {
                    originalUrl = normalizedUrl
                    setPaneLoaded(BrowserPane.LEFT, false)
                    markPaneLoadStart(BrowserPane.LEFT)
                    loadUrl(normalizedUrl)
                }
                // This branch navigates the left pane, so it must read the left flag —
                // a right tile mid-load must not trigger a left reload.
                !leftPageLoaded -> {
                    setPaneLoaded(BrowserPane.LEFT, false)
                    markPaneLoadStart(BrowserPane.LEFT)
                    if (currentPageUrl.isBlank()) {
                        loadUrl(normalizedUrl)
                    } else {
                        reloadActive()
                    }
                }
                else -> {
                    // Page is still loaded — only reattach the overlay. Reloading GeckoView here
                    // after hide/detach crashes on Android 7.x (Rockchip/Allwinner GPU drivers).
                }
            }
        }
        // A Chromium error page also fires onPageFinished and leaves the pane flagged as
        // loaded, so the branch above never retries a load that failed while hidden — and
        // maybeRetryAfterNetworkRestore() was gated on isBrowserHidden the whole time. If the
        // await flag is still armed, retry now that the renderer is visible and unthrottled;
        // the helper re-checks remote URL and the flap interval, and a still-dead network just
        // fails and re-arms as before. Loaded=true means no branch above started a load.
        if (awaitingNetworkRetry && leftPageLoaded) {
            maybeRetryAfterNetworkRestore("resume_from_hide")
        }
        Log.d(TAG, "Browser resumed from hide (wasHidden=$wasHidden, urlChanged=$urlChanged, leftLoaded=$leftPageLoaded rightLoaded=$rightPageLoaded)")
        if (engineSurface != null) {
            val renderView = engineSurface?.view
            renderView?.requestFocus()
            claimOverlayWindowFocus(renderView)
        }
        lifecycleScope.launch {
            currentSettings = applyHostDarkModeOverride(browserSettingsStore.get())
            syncHaBrowserDarkMode()
        }
        resetBrowserSidebarForShow()
        publishOverlayVisibility()
        if (EngineCapabilities.GECKO_BUNDLED && wasHidden) {
            reportGeckoOverlay(visible = true, attached = true, updateHaSwitch = true)
        }
    }

    /**
     * Screensaver (or similar) covers the HA browser: freeze renderer + schedule WS
     * suspend. Never uses process-global pauseTimers().
     *
     * Kept intentionally light until [deepenCoveredDormant]: dual-pane HA + screensaver
     * share Chromium cache/process budget; immediate clearCache / ORANGE+ socket kill
     * during screensaver cold load causes incomplete loads and a black reveal mask.
     */
    private fun enterCoveredDormant(reason: String) {
        if (webView == null && webViewRight == null && engineSurface == null) return
        if (isBrowserHidden || dormantKind == DormantKind.HIDDEN) {
            Log.d(TAG, "Covered dormant skipped (already hidden): $reason")
            return
        }
        if (dormantKind == DormantKind.COVERED) {
            Log.d(TAG, "Covered dormant already active: $reason")
            return
        }
        // onPause() + a lowered renderer priority mid-load is what leaves the dual-pane
        // dashboard half-painted under the screensaver. Wait for first paint, not just
        // for the view build — the HA bootstrap runs seconds after isCreating clears.
        if (isCreating || isWarmingUp) {
            pendingCoveredWhileWarming = true
            Log.d(TAG, "Covered dormant deferred until warm-up finishes ($reason)")
            return
        }
        parkVisiblePowerForCover()
        dormantKind = DormantKind.COVERED
        coveredDeepenDone = false
        if (!isPaused) {
            webView?.onPause()
            webViewRight?.onPause()
            engineSurface?.setHostPaused(true)
            applyPausedRendererPriority()
            isPaused = true
        }
        // L1 only: batching, nothing that costs a reconnect. The cover overlay is still
        // painting and shares this process — deepen once it signals ready.
        applyDormancyStep(DormancyStep.L1_LIGHT, reason)
        syncOffscreenPreRaster()
        scheduleCoveredDeepenFallback()
        Log.d(TAG, "Browser covered-dormant light ($reason)")
    }

    /**
     * After the screensaver has painted (or [COVERED_DEEPEN_FALLBACK_MS]), apply the
     * heavier COVERED actions that would starve a third WebView mid-load.
     */
    private fun deepenCoveredDormant(reason: String) {
        if (dormantKind != DormantKind.COVERED) {
            Log.d(TAG, "Covered deepen skipped (kind=$dormantKind): $reason")
            return
        }
        if (coveredDeepenDone) {
            Log.d(TAG, "Covered deepen already done: $reason")
            return
        }
        coveredDeepenDone = true
        cancelCoveredDeepen()
        // Cover has painted, so heavier gears are safe. Pressure skips straight to the
        // parked tier; otherwise L2 batches and the ladder parks after it proves durable.
        val step = if (pressureTier >= PressureTier.ORANGE) {
            DormancyStep.L3_PARKED
        } else {
            DormancyStep.L2_DEEP
        }
        applyDormancyStep(step, "covered-deepen-$reason")
        // No cache purge here: WebView.clearCache is application-wide, so reclaiming while
        // COVERED also empties the screensaver WebView that is running right now. Purging
        // stays HIDDEN-only (see applyPressureActions).
        Log.d(TAG, "Browser covered-dormant deepened ($reason, tier=$pressureTier)")
    }

    private fun scheduleCoveredDeepenFallback() {
        cancelCoveredDeepen()
        val runnable = Runnable {
            pendingCoveredDeepenRunnable = null
            deepenCoveredDormant("fallback-${COVERED_DEEPEN_FALLBACK_MS}ms")
        }
        pendingCoveredDeepenRunnable = runnable
        mainHandler.postDelayed(runnable, COVERED_DEEPEN_FALLBACK_MS)
    }

    private fun cancelCoveredDeepen() {
        pendingCoveredDeepenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingCoveredDeepenRunnable = null
    }

    /** Leave screensaver cover; no-op if the browser is still user-hidden. */
    private fun exitCoveredDormant(reason: String) {
        if (dormantKind != DormantKind.COVERED) {
            Log.d(TAG, "Exit covered dormant skipped (kind=$dormantKind): $reason")
            return
        }
        dormantKind = DormantKind.NONE
        coveredDeepenDone = false
        cancelCoveredDeepen()
        if (isPaused && !isBrowserHidden) {
            ensureOverlayOnTop()
            applyActiveRendererPriority()
            webView?.onResume()
            webViewRight?.onResume()
            engineSurface?.setHostPaused(false)
            isPaused = false
        }
        // Unwinds park/suspend and restores full-rate delivery in one step.
        applyDormancyStep(DormancyStep.L0_ACTIVE, reason)
        beginDisplaySettle(reason)
        Log.d(TAG, "Browser left covered-dormant ($reason)")
    }

    private fun isBrowserDormant(): Boolean =
        isBrowserHidden || dormantKind != DormantKind.NONE

    /**
     * Move to a rung of the dormancy ladder. Ascending happens immediately on
     * uncover/show; descending is timed by [scheduleLadderDescent] or forced by pressure.
     */
    private fun applyDormancyStep(target: DormancyStep, reason: String) {
        if (target == dormancyStep) {
            if (target == DormancyStep.L0_ACTIVE) releaseHaHiddenLatch()
            scheduleLadderDescent()
            return
        }
        val from = dormancyStep
        dormancyStep = target
        if (target < from) {
            // Unwind deepest first: the socket must be live before the subscription returns.
            if (from >= DormancyStep.L4_SUSPENDED) resumeHaWebSockets()
            if (from >= DormancyStep.L3_PARKED && target < DormancyStep.L3_PARKED) {
                setEntitiesParked(false)
            }
        } else {
            if (target >= DormancyStep.L3_PARKED && from < DormancyStep.L3_PARKED) {
                setEntitiesParked(true)
            }
            if (target == DormancyStep.L4_SUSPENDED) suspendHaWebSockets("ladder-$reason")
        }
        syncChunkedRendering()
        syncLiteMode()
        syncFreezeAnimations()
        syncPauseMedia()
        Log.d(TAG, "Dormancy ladder $from -> $target ($reason)")
        // Hide/onPause wakes HA's hidden path even at L2. Show must punch awake
        // on every L0, including L2→L0 where we never called park-on.
        if (target == DormancyStep.L0_ACTIVE) releaseHaHiddenLatch()
        scheduleLadderDescent()
    }

    /** Pair to park / WebView.onPause: overlay is on screen, HA must not stay hidden. */
    private fun releaseHaHiddenLatch() {
        if (isBrowserDormant()) return
        setEntitiesParked(false)
    }

    private fun scheduleLadderDescent() {
        cancelLadderDescent()
        val step: DormancyStep
        val delay: Long
        when (dormancyStep) {
            DormancyStep.L2_DEEP -> {
                step = DormancyStep.L3_PARKED
                delay = DORMANT_PARK_AFTER_MS
            }
            DormancyStep.L3_PARKED -> {
                step = DormancyStep.L4_SUSPENDED
                delay = DORMANT_SUSPEND_AFTER_MS
            }
            else -> return
        }
        val runnable = Runnable {
            pendingLadderRunnable = null
            if (!isBrowserDormant()) return@Runnable
            // A screensaver cover must not earn a reconnect-on-wake; only a user-hidden
            // browser or real memory pressure does. Otherwise re-arm and check later.
            if (step == DormancyStep.L4_SUSPENDED &&
                !isBrowserHidden &&
                pressureTier < PressureTier.RED
            ) {
                scheduleLadderDescent()
                return@Runnable
            }
            applyDormancyStep(step, "dwell")
        }
        pendingLadderRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun cancelLadderDescent() {
        pendingLadderRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLadderRunnable = null
    }

    /**
     * Zero entity traffic while the WebSocket stays connected — the gear that used to be
     * missing between "full rate" and "disconnected".
     */
    private fun setEntitiesParked(parked: Boolean) {
        // Parking is a stream feature. Unpark is always allowed so a hide/show
        // or steward-off still clears a previous park / visibility spoof.
        if (parked && !isStewardStreamOn()) return
        val script = if (parked) {
            BrowserWsStewardScripts.parkOnJs
        } else {
            BrowserWsStewardScripts.parkOffJs
        }
        fun handleParkResult(side: String, result: String?, retry: () -> Unit) {
            Log.d(TAG, "HA WS steward park=$parked ($side): $result")
            // Subscribe may not exist yet (cold HA boot). Retry once shortly if we
            // still want to be parked — otherwise L3 is a no-op until the next load.
            if (parked &&
                dormancyStep >= DormancyStep.L3_PARKED &&
                result != null &&
                result.contains("park-no-sub")
            ) {
                mainHandler.postDelayed({
                    if (dormancyStep < DormancyStep.L3_PARKED) return@postDelayed
                    retry()
                }, 2_500L)
            }
        }
        fun apply(wv: WebView?, side: String) {
            wv ?: return
            wv.evaluateJavascript(script) { result ->
                handleParkResult(side, result) {
                    val alive = if (side == "left") webView else webViewRight
                    alive?.evaluateJavascript(script, null)
                }
            }
        }
        apply(webView, "left")
        apply(webViewRight, "right")
        engineSurface?.evaluateJavascript(script) { result ->
            handleParkResult("gecko", result) {
                engineSurface?.evaluateJavascript(script)
            }
        }
    }

    /**
     * Chunked rendering: `content-visibility` lets the engine skip layout/paint/raster for
     * off-screen cards. Entity data keeps flowing, so nothing stops working — this only
     * removes work for pixels nobody can see.
     *
     * Armed only while the browser is dormant, never on a page someone is looking at. A card
     * that first lays out under the rule measures the intrinsic-size estimate instead of its
     * real box, and self-sizing cards (calendar, thermostat, charts) do not re-measure on
     * their own — the card stays visibly truncated. That is why it must not be tied to the
     * pressure tier: [computePressureFloor] pins weak panels at YELLOW forever, and a cold
     * boot reads YELLOW on any device, so every first dashboard render was hitting it.
     *
     * HIDDEN only, not COVERED: while covered the WebView is [WebView.onPause]d and paints
     * nothing, so skipping off-screen cards saves zero work — but disarming on uncover fires
     * a synthetic window-resize that re-measures every card right as the cover lifts (the
     * "page rebuilds itself the moment I touch it" report). Hidden's reveal starts from
     * alpha 0, so its re-measure hides behind the fade-in.
     */
    private fun syncChunkedRendering() {
        val want = isStewardChunkOn() &&
            (isBrowserHidden || dormantKind == DormantKind.HIDDEN)
        applyChunkedRenderingTo(webView, BrowserPane.LEFT, want)
        applyChunkedRenderingTo(webViewRight, BrowserPane.RIGHT, want)
        applyChunkedRenderingToGecko(want)
    }

    private fun applyChunkedRenderingToGecko(want: Boolean) {
        val surf = engineSurface ?: return
        if (chunkGeckoApplied == want) return
        chunkGeckoApplied = want
        val script = if (want) {
            BrowserWsStewardScripts.chunkedRenderingOnJs
        } else {
            BrowserWsStewardScripts.chunkedRenderingOffJs
        }
        surf.evaluateJavascript(script) { result ->
            Log.d(TAG, "Chunked rendering (gecko) -> $want: $result")
        }
    }

    private fun applyChunkedRenderingTo(wv: WebView?, pane: BrowserPane, want: Boolean) {
        if (wv == null) return
        val applied = if (pane == BrowserPane.LEFT) chunkLeftApplied else chunkRightApplied
        if (applied == want) return
        if (pane == BrowserPane.LEFT) chunkLeftApplied = want else chunkRightApplied = want
        val script = if (want) {
            BrowserWsStewardScripts.chunkedRenderingOnJs
        } else {
            BrowserWsStewardScripts.chunkedRenderingOffJs
        }
        wv.evaluateJavascript(script) { result ->
            Log.d(TAG, "Chunked rendering ($pane) -> $want: $result")
        }
    }

    /** A reload resets the page-side flag, so drop the cache before re-evaluating. */
    private fun reassertChunkedRenderingAfterLoad(pane: BrowserPane) {
        if (pane == BrowserPane.LEFT) chunkLeftApplied = false else chunkRightApplied = false
        syncChunkedRendering()
    }

    /** Pause CSS animations from L2 upward when dormant quieting is on. */
    private fun syncFreezeAnimations() {
        if (!isStewardDormantQuietOn()) {
            if (freezeAnimationsApplied) {
                freezeAnimationsApplied = false
                if (!visibleIdleQuietApplied) {
                    evaluateOnVisiblePanes(BrowserWsStewardScripts.freezeAnimationsOffJs)
                }
            }
            return
        }
        // L1 only batches; freezing every CSS transition on a flicker cover is a net loss.
        val want = dormancyStep >= DormancyStep.L2_DEEP
        if (want == freezeAnimationsApplied) return
        freezeAnimationsApplied = want
        if (!want && visibleIdleQuietApplied) return
        val script = if (want) {
            BrowserWsStewardScripts.freezeAnimationsOnJs
        } else {
            BrowserWsStewardScripts.freezeAnimationsOffJs
        }
        evaluateOnVisiblePanes(script)
    }

    /**
     * Pause video/audio from L2 upward when dormant quieting is on. Dormancy is
     * the sole owner of media pause ([applyVisibleIdleQuiet] never touches it),
     * so the Off script is always sent on unwind.
     */
    private fun syncPauseMedia() {
        if (!isStewardDormantQuietOn()) {
            if (pauseMediaApplied) {
                pauseMediaApplied = false
                evaluateOnVisiblePanes(BrowserWsStewardScripts.pauseMediaOffJs)
            }
            return
        }
        // L1 only batches; media pause starts at L2 so a flicker cover does not kill video.
        val want = dormancyStep >= DormancyStep.L2_DEEP
        if (want == pauseMediaApplied) return
        pauseMediaApplied = want
        val script = if (want) {
            BrowserWsStewardScripts.pauseMediaOnJs
        } else {
            BrowserWsStewardScripts.pauseMediaOffJs
        }
        evaluateOnVisiblePanes(script)
        if (want) {
            // Entering deep dormancy: reap any forgotten more-info dialog. Its history
            // charts keep subscriptions and leak (HA frontend #25888); the panel is
            // covered, so the user never sees it close.
            evaluateOnVisiblePanes(BrowserWsStewardScripts.closeDialogsJs)
        }
    }

    private fun evaluateOnVisiblePanes(script: String) {
        webView?.evaluateJavascript(script, null)
        webViewRight?.evaluateJavascript(script, null)
        engineSurface?.evaluateJavascript(script)
    }

    /**
     * Visible adaptive rest only: CSS animation freeze, never media. The panel is
     * still on show, so a camera stream or playing video must keep running even
     * when nobody has touched for a while (media pause belongs to covered/hidden
     * dormancy, [syncPauseMedia]). Independent of dormancy [freezeAnimationsApplied]
     * so uncover/L2 unwind does not clear this, and turning this off does not
     * clear an L2 freeze.
     */
    private fun applyVisibleIdleQuiet(want: Boolean) {
        if (want == visibleIdleQuietApplied) return
        visibleIdleQuietApplied = want
        if (want) {
            evaluateOnVisiblePanes(BrowserWsStewardScripts.freezeAnimationsOnJs)
            return
        }
        if (!freezeAnimationsApplied) {
            evaluateOnVisiblePanes(BrowserWsStewardScripts.freezeAnimationsOffJs)
        }
    }

    /**
     * Finger landed on the overlay. Immediate full power; MOVE while already hot is a no-op
     * so scroll frames do not cancel/reschedule the idle timers.
     */
    private fun onBrowserTouchPower(fingerDown: Boolean) {
        if (fingerDown) {
            if (browserFingerDown && touchPowerBoosted && !visibleRestPaused) {
                browserFingerDown = true
                return
            }
            browserFingerDown = true
            beginTouchPowerHot("touch")
            return
        }
        browserFingerDown = false
        scheduleTouchPowerRest()
    }

    private fun beginTouchPowerHot(reason: String) {
        cancelTouchPowerTimers()
        applyTouchPowerBoosted(reason)
        if (!browserFingerDown && !isTouchPowerLockedHot()) {
            scheduleTouchPowerRest()
        }
    }

    /**
     * Keep full-rate entity delivery until Lovelace has painted, then arm lite.
     * Finger-up during this window does not start rest. High skips settle.
     */
    private fun beginDisplaySettle(reason: String) {
        cancelTouchPowerTimers()
        cancelVisibleRestPauseTimer()
        cancelDisplaySettle()
        applyTouchPowerBoosted("display-settle-$reason")
        if (isCreating || isWarmingUp || isBrowserDormant()) {
            return
        }
        val settleMs = touchPowerSettleMs()
        if (settleMs <= 0L) {
            return
        }
        displaySettling = true
        val runnable = Runnable {
            pendingDisplaySettleRunnable = null
            displaySettling = false
            if (browserFingerDown || isTouchPowerLockedHot()) {
                return@Runnable
            }
            // Adaptive: drop at settle-end (cold start ≈ 15s). Low keeps the extra idle wait.
            when (visiblePowerMode()) {
                BrowserPowerMode.HIGH -> Unit
                BrowserPowerMode.ADAPTIVE -> applyTouchPowerIdle("display-settle-end")
                BrowserPowerMode.LOW -> scheduleTouchPowerRest()
            }
        }
        pendingDisplaySettleRunnable = runnable
        mainHandler.postDelayed(runnable, settleMs)
        Log.d(TAG, "Browser touch-power settle ${settleMs}ms ($reason)")
    }

    private fun cancelDisplaySettle() {
        pendingDisplaySettleRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDisplaySettleRunnable = null
        displaySettling = false
    }

    private fun isTouchPowerLockedHot(): Boolean =
        isCreating ||
            isWarmingUp ||
            displaySettling ||
            isBrowserDormant() ||
            overlayImeFocusHeld ||
            htmlFullscreen().isActive() ||
            engineSurface?.isHtmlFullscreenActive() == true

    private fun onBrowserTouchPowerPulse() {
        beginTouchPowerHot("input-pulse")
    }

    private fun visiblePowerMode(): BrowserPowerMode =
        BrowserPowerMode.fromStored(currentSettings.browserPowerMode)

    private fun touchPowerSettleMs(): Long = when (visiblePowerMode()) {
        BrowserPowerMode.HIGH -> 0L
        BrowserPowerMode.ADAPTIVE -> TOUCH_POWER_DISPLAY_SETTLE_MS
        BrowserPowerMode.LOW -> TOUCH_POWER_DISPLAY_SETTLE_LOW_MS
    }

    private fun touchPowerIdleMs(): Long = when (visiblePowerMode()) {
        BrowserPowerMode.HIGH -> TOUCH_POWER_IDLE_MS
        BrowserPowerMode.ADAPTIVE -> TOUCH_POWER_IDLE_MS
        BrowserPowerMode.LOW -> TOUCH_POWER_IDLE_LOW_MS
    }

    /**
     * Live remap of pipeline B only. Cover/hide (pipeline A) is left untouched;
     * if the overlay is dormant we just remember the new timings.
     */
    private fun applyVisiblePowerModeChange(
        previous: BrowserPowerMode,
        next: BrowserPowerMode,
        reason: String,
    ) {
        if (isBrowserDormant()) {
            cancelTouchPowerTimers()
            cancelVisibleRestPauseTimer()
            Log.d(TAG, "Browser power $previous→$next stored while dormant ($reason)")
            pushStewardHostSnapshot()
            return
        }
        cancelTouchPowerTimers()
        cancelVisibleRestPauseTimer()
        when (next) {
            BrowserPowerMode.HIGH -> {
                cancelDisplaySettle()
                applyTouchPowerBoosted("power-high-$reason")
            }
            BrowserPowerMode.ADAPTIVE,
            BrowserPowerMode.LOW,
            -> {
                if (browserFingerDown || isTouchPowerLockedHot()) {
                    applyTouchPowerBoosted("power-$reason")
                    if (!browserFingerDown && !isTouchPowerLockedHot()) {
                        scheduleTouchPowerRest()
                    }
                } else if (visibleRestPaused) {
                    if (next == BrowserPowerMode.ADAPTIVE) {
                        applyTouchPowerBoosted("power-resume-$reason")
                        scheduleTouchPowerRest()
                    }
                } else if (touchPowerBoosted && previous == BrowserPowerMode.HIGH) {
                    scheduleTouchPowerRest()
                } else {
                    applyTouchPowerIdle("power-$reason")
                }
            }
        }
        Log.d(TAG, "Browser power $previous→$next ($reason)")
        pushStewardHostSnapshot()
    }

    private fun scheduleTouchPowerRest() {
        cancelTouchPowerTimers()
        if (visiblePowerMode() == BrowserPowerMode.HIGH) return
        if (browserFingerDown || isTouchPowerLockedHot()) return
        val idleMs = touchPowerIdleMs()
        val idle = Runnable {
            pendingTouchIdleRunnable = null
            applyTouchPowerIdle("finger-idle")
        }
        pendingTouchIdleRunnable = idle
        mainHandler.postDelayed(idle, idleMs)
    }

    private fun cancelTouchPowerTimers() {
        pendingTouchIdleRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTouchIdleRunnable = null
    }

    private fun scheduleVisibleRestPause() {
        cancelVisibleRestPauseTimer()
        when (visiblePowerMode()) {
            BrowserPowerMode.HIGH,
            BrowserPowerMode.ADAPTIVE,
            -> return
            BrowserPowerMode.LOW -> Unit
        }
        if (browserFingerDown || isTouchPowerLockedHot() || isBrowserDormant()) return
        if (visibleRestPaused) return
        val runnable = Runnable {
            pendingVisiblePauseRunnable = null
            applyVisibleRestPause("idle-${TOUCH_POWER_VISIBLE_PAUSE_MS}ms")
        }
        pendingVisiblePauseRunnable = runnable
        mainHandler.postDelayed(runnable, TOUCH_POWER_VISIBLE_PAUSE_MS)
    }

    private fun cancelVisibleRestPauseTimer() {
        pendingVisiblePauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingVisiblePauseRunnable = null
    }

    private fun applyVisibleRestPause(reason: String) {
        when (visiblePowerMode()) {
            BrowserPowerMode.HIGH,
            BrowserPowerMode.ADAPTIVE,
            -> return
            BrowserPowerMode.LOW -> Unit
        }
        if (browserFingerDown || isTouchPowerLockedHot() || isBrowserDormant()) return
        if (visibleRestPaused || isPaused) return
        webView?.onPause()
        webViewRight?.onPause()
        engineSurface?.setHostPaused(true)
        visibleRestPaused = true
        Log.d(TAG, "Browser visible rest pause ($reason)")
    }

    /**
     * Park pipeline B before cover/hide takes the WebView. If visible rest already
     * called [WebView.onPause], transfer that into [isPaused] so cover/hide does
     * not drop the flag without a matching onResume.
     */
    private fun parkVisiblePowerForCover() {
        val alreadyVisiblyPaused = visibleRestPaused
        resetTouchPower()
        if (alreadyVisiblyPaused) {
            isPaused = true
        }
    }

    private fun resetTouchPower() {
        cancelTouchPowerTimers()
        cancelVisibleRestPauseTimer()
        cancelDisplaySettle()
        browserFingerDown = false
        touchPowerBoosted = false
        // Drop the visible-idle pause flag without onResume. Cover/hide owns the view.
        visibleRestPaused = false
        // Leave [visibleIdleQuietApplied] so uncover/boost can send the matching Off JS.
    }

    private fun applyTouchPowerBoosted(reason: String) {
        if (isBrowserDormant()) return
        cancelVisibleRestPauseTimer()
        val wasPaused = visibleRestPaused || isPaused
        resumePausedRendererIfNeeded()
        applyVisibleIdleQuiet(false)
        val alreadyHot = touchPowerBoosted && !wasPaused
        touchPowerBoosted = true
        applyActiveRendererPriority()
        syncLiteMode()
        if (!alreadyHot) {
            Log.d(TAG, "Browser touch-power full ($reason)")
        }
    }

    private fun applyTouchPowerIdle(reason: String) {
        when (visiblePowerMode()) {
            BrowserPowerMode.HIGH -> {
                applyTouchPowerBoosted("idle-blocked-high")
                return
            }
            BrowserPowerMode.ADAPTIVE,
            BrowserPowerMode.LOW,
            -> Unit
        }
        if (browserFingerDown || isTouchPowerLockedHot()) return
        if (!touchPowerBoosted && !visibleRestPaused) {
            scheduleVisibleRestPause()
            syncLiteMode()
            return
        }
        touchPowerBoosted = false
        // Low is a superset of adaptive savings: same bound renderer + frozen CSS
        // while idle, plus the visible rest pause that adaptive never takes.
        when (visiblePowerMode()) {
            BrowserPowerMode.HIGH -> Unit // unreachable, redirected above
            BrowserPowerMode.ADAPTIVE,
            BrowserPowerMode.LOW,
            -> {
                applyPausedRendererPriority()
                applyVisibleIdleQuiet(true)
            }
        }
        syncLiteMode()
        scheduleVisibleRestPause()
        Log.d(TAG, "Browser touch-power idle ($reason)")
    }

    private fun applyActiveRendererPriority() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        webViewRight?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    private fun applyPausedRendererPriority() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        webViewRight?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
    }

    private fun attachOverlayIfNeeded(): Boolean {
        val container = containerView ?: return false
        val params = windowParams ?: return false
        synchronized(overlayLock) {
            return attachOverlayWithinLock(container, params)
        }
    }

    /**
     * System WebView overlay stays [FLAG_NOT_FOCUSABLE] so dashboard taps do not steal
     * window focus from [MainActivity] / the wake-word pipeline. Only a real text field
     * (or the web console) lifts that flag, and [releaseSystemWebViewImeFocus] puts it
     * back. Do not call [View.requestFocus] inside [prepareSystemWebViewImeWindow] —
     * that runs before the tap is dispatched and can prevent the input from taking editor focus.
     *
     * @return true when the overlay window is already IME-ready; false when flags just
     * changed and the IME should be shown on the next frame.
     */
    private fun prepareSystemWebViewImeWindow(): Boolean {
        if (engineSurface != null) return true
        val container = containerView ?: return true
        val params = windowParams ?: return true
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            overlayImeFocusHeld = true
            beginTouchPowerHot("ime")
            return true
        }
        // Clearing FLAG_NOT_FOCUSABLE alone is not enough: with the window now focusable, a
        // residual FLAG_ALT_FOCUSABLE_IM inverts to "this window does not want the IME", so the
        // soft keyboard never rises. Drop both so the tapped input can attach the IME.
        params.flags = params.flags and
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM).inv()
        try {
            windowManager?.updateViewLayout(container, params)
            overlayImeFocusHeld = true
            beginTouchPowerHot("ime")
            return false
        } catch (e: Exception) {
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            Log.w(TAG, "Failed to prepare overlay window for WebView IME", e)
            return true
        }
    }

    /**
     * Give window focus back to the activity so the wake-word listener is not starved.
     * Restores the birth flags: [FLAG_NOT_FOCUSABLE] + [FLAG_ALT_FOCUSABLE_IM].
     */
    private fun releaseSystemWebViewImeFocus() {
        if (engineSurface != null) return
        if (!overlayImeFocusHeld) return
        overlayImeFocusHeld = false
        val container = containerView
        val params = windowParams
        if (params != null) {
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        if (container == null || params == null) {
            scheduleTouchPowerRest()
            return
        }
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(container.windowToken, 0)
            webView?.clearFocus()
            webViewRight?.clearFocus()
            if (container.parent != null) {
                windowManager?.updateViewLayout(container, params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore overlay NOT_FOCUSABLE after IME", e)
        }
        scheduleTouchPowerRest()
    }

    private fun webViewHitTestNeedsKeyboard(wv: WebView?): Boolean {
        if (wv == null) return false
        return try {
            wv.hitTestResult.type == WebView.HitTestResult.EDIT_TEXT_TYPE
        } catch (_: Exception) {
            false
        }
    }

    private fun viewContainsScreenPoint(view: View, x: Int, y: Int): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && y >= loc[1] &&
            x < loc[0] + view.width && y < loc[1] + view.height
    }

    private fun systemWebViewForTouch(event: MotionEvent): WebView? {
        val left = webView
        val right = webViewRight
        if (right == null || left == null) return left ?: right
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        if (viewContainsScreenPoint(right, x, y)) return right
        if (viewContainsScreenPoint(left, x, y)) return left
        return if (focusedPane == BrowserPane.RIGHT) right else left
    }

    /**
     * After the tap has been delivered, Chromium's hit test is current. Claim IME only for
     * [WebView.HitTestResult.EDIT_TEXT_TYPE]; a non-edit tap while IME is held gives focus back.
     */
    private fun maybeSyncSystemWebViewImeAfterTouch(event: MotionEvent, tapNotDrag: Boolean) {
        if (engineSurface != null) return
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return
        val wv = systemWebViewForTouch(event)
        if (webViewHitTestNeedsKeyboard(wv)) {
            prepareSystemWebViewImeWindow()
            showSystemWebViewIme(wv)
            return
        }
        if (action == MotionEvent.ACTION_UP &&
            overlayImeFocusHeld &&
            tapNotDrag &&
            webConsoleCompose == null
        ) {
            releaseSystemWebViewImeFocus()
        }
    }

    private fun showSystemWebViewIme(target: WebView?) {
        val view = target ?: webView ?: return
        val container = containerView ?: return
        container.post {
            val params = windowParams ?: return@post
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return@post
            if (!view.isFocusable) view.isFocusable = true
            if (!view.isFocusableInTouchMode) view.isFocusableInTouchMode = true
            view.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Overlay windows start with [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] so they do not
     * steal focus from voice/UI layers. [FLAG_ALT_FOCUSABLE_IM] lets descendants use the IME while
     * the window itself stays non-focusable; GeckoView still needs a fully focusable window.
     */
    private fun claimOverlayWindowFocus(focusTarget: View?) {
        if (engineSurface == null) {
            prepareSystemWebViewImeWindow()
            return
        }
        val container = containerView ?: return
        val params = windowParams ?: return
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            try {
                windowManager?.updateViewLayout(container, params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update overlay focus flags for IME", e)
            }
        }
        val target = focusTarget ?: engineSurface?.view ?: return
        if (!target.isFocusable) {
            target.isFocusable = true
        }
        if (!target.isFocusableInTouchMode) {
            target.isFocusableInTouchMode = true
        }
        target.requestFocus()
    }

    /**
     * Sticky bar policy for the overlay window. Follows [com.example.ava.ui.SystemBarsMode]:
     * only bars that mode hides are pressed back down. [WindowManager.LayoutParams.FLAG_FULLSCREEN]
     * stays on the window because it is the z-order bucket; it is not sticky, so a mode
     * that shows the status bar can keep it showing. The activity behind a
     * [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] overlay is what actually owns the bars.
     */
    @Suppress("DEPRECATION")
    private fun applyOverlayImmersive(view: View) {
        AvaSystemChrome.applyOverlayStyleSystemUi(view)
        AvaSystemChrome.trackOverlayRoot(view)
    }

    /**
     * Watch the real status-bar overlap over the overlay window. The window is laid out
     * from y=0 (LAYOUT_NO_LIMITS) and, being a non-focused overlay, cannot force the bar
     * away when the app behind (e.g. a third-party launcher) keeps it visible — the bar
     * then draws over HA's header while `env(safe-area-inset-top)` inside the page reads 0.
     * The measured overlap (all four sides) is pushed into every live page via the
     * [BrowserPlatformCompat] `__avaSetInsets` bridge, which feeds HA's own
     * `--app-safe-area-inset-*` slots: header, FABs and dialogs realign, card margins are
     * untouched. When the bars hide (immersive kiosk / tablet) the values return to 0 and
     * the page reclaims the space.
     */
    private fun installStatusBarInsetWatcher(container: View) {
        container.setOnApplyWindowInsetsListener { _, insets ->
            onStatusBarInsetsChanged(insets)
            insets
        }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post { publishAttachedRootInsets(v) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /**
     * Snapshot the insets once the overlay is attached. [View.getRootWindowInsets] is API 23;
     * calling it on Android 5.1 crashes browser startup. Below that, poke the API 20 listener
     * installed above instead of publishing a fake zero inset.
     */
    private fun publishAttachedRootInsets(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            view.requestApplyInsets()
            return
        }
        onStatusBarInsetsChanged(PlatformCapabilities.rootWindowInsets(view))
    }

    /**
     * All four sides, official-companion-app parity (its `InsetsUtil.applyInsets` pushes
     * systemBars ∪ displayCutout as dp into `--app-safe-area-inset-*`): the frontend
     * positions the header (top), add-integration-style FABs (bottom/right) and dialogs
     * (all sides) from these variables. Real values only — bar-less edges stay 0, so
     * card margins never move.
     */
    private fun onStatusBarInsetsChanged(insets: WindowInsets?) {
        val sidesPx = when {
            insets == null -> intArrayOf(0, 0, 0, 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                // FLAG_FULLSCREEN overlays get stripped inset SIZES on some ROMs while
                // visibility still reports truthfully — fall back to the framework's
                // status-bar height resource for the magnitude.
                val top = if (
                    bars.top == 0 && insets.isVisible(WindowInsets.Type.statusBars())
                ) {
                    statusBarHeightResourcePx()
                } else {
                    bars.top
                }
                intArrayOf(top, bars.right, bars.bottom, bars.left)
            }
            else ->
                @Suppress("DEPRECATION")
                intArrayOf(
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                    insets.systemWindowInsetLeft,
                )
        }
        val density = resources.displayMetrics.density.coerceAtLeast(0.5f)
        // Ceil so chrome clears the bars fully; CSS px ≈ dp at default page scale.
        val cssPx = IntArray(4) { i ->
            val px = sidesPx[i]
            if (px <= 0) 0 else ((px + density - 1f) / density).toInt()
        }
        if (cssPx.contentEquals(pageInsetsCssPx)) return
        pageInsetsCssPx = cssPx
        Log.i(
            TAG,
            "System-bar overlap over browser overlay now t=${cssPx[0]} r=${cssPx[1]} " +
                "b=${cssPx[2]} l=${cssPx[3]} css px — updating HA safe-area insets",
        )
        val js = pageInsetsJs()
        webView?.evaluateJavascript(js, null)
        webViewRight?.evaluateJavascript(js, null)
        pushHaSidebarEdgeToPages()
    }

    private fun statusBarHeightResourcePx(): Int = try {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    } catch (_: Exception) {
        0
    }

    /** Re-applies the current overlap on a freshly loaded page (bridge state resets per document). */
    private fun pushStatusBarInsetToPage(wv: WebView) {
        if (pageInsetsCssPx.all { it <= 0 }) {
            wv.evaluateJavascript(haSidebarEdgeJs(), null)
            return
        }
        wv.evaluateJavascript(pageInsetsJs(), null)
        wv.evaluateJavascript(haSidebarEdgeJs(), null)
    }

    private fun pageInsetsJs(): String {
        val (t, r, b, l) = pageInsetsCssPx.let {
            listOf(it[0], it[1], it[2], it[3])
        }
        return "window.__avaSetInsets&&window.__avaSetInsets($t,$r,$b,$l);"
    }

    private fun setHaSidebarHandleEdge(edge: String) {
        if (haSidebarHandleEdge == edge) {
            pushHaSidebarEdgeToPages()
            return
        }
        haSidebarHandleEdge = edge
        pushHaSidebarEdgeToPages()
    }

    private fun haSidebarEdgeJs(): String =
        "window.__avaSetSidebarEdge&&window.__avaSetSidebarEdge('$haSidebarHandleEdge');"

    private fun pushHaSidebarEdgeToPages() {
        val js = haSidebarEdgeJs()
        webView?.evaluateJavascript(js, null)
        webViewRight?.evaluateJavascript(js, null)
        engineSurface?.evaluateJavascript(js)
    }

    /** Sticky immersive that survives attach and any bar the platform reveals mid-session. */
    private fun installOverlayImmersive(container: View) {
        applyOverlayImmersive(container)
        val reassert = Runnable { applyOverlayImmersive(container) }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post(reassert)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(reassert)
            }
        })
        @Suppress("DEPRECATION")
        container.setOnSystemUiVisibilityChangeListener { visibility ->
            if (AvaSystemChrome.overlayBarsNeedReassert(container, visibility)) {
                container.removeCallbacks(reassert)
                container.postDelayed(reassert, IMMERSIVE_REASSERT_DELAY_MS)
            }
        }
    }

    /** Caller must hold [overlayLock]. */
    private fun attachOverlayWithinLock(container: ViewGroup, params: WindowManager.LayoutParams): Boolean {
        if (isOverlayAttached) {
            if (container.isAttachedToWindow) return true
            // The flag says attached but the view is not. Either isAttachedToWindow is lying (see
            // applyOverlayLayoutWithinLock) or the system tore our window down — and in the latter
            // case trusting the flag strands the browser: every later show short-circuits onto a
            // window that no longer exists. Ask WindowManager, which only rejects the update when
            // the window is genuinely gone.
            val stillRegistered =
                runCatching { windowManager?.updateViewLayout(container, params) }.isSuccess
            if (stillRegistered) return true
            Log.w(TAG, "Overlay window vanished while flagged attached; re-adding it")
            isOverlayAttached = false
        }
        if (container.isAttachedToWindow) {
            isOverlayAttached = true
            publishOverlayVisibility()
            return true
        }
        return try {
            windowManager?.addView(container, params)
            isOverlayAttached = true
            Log.d(TAG, "Overlay attached to WindowManager")
            // Fresh addView is topmost: bump the generation so the mic climbs
            // even if the full reassert below gets throttled by a boot burst.
            OverlayZOrderCoordinator.noteWindowAdded()
            reassertForegroundOverlaysAboveBrowser(fresh = true)
            publishOverlayVisibility()
            true
        } catch (e: IllegalStateException) {
            isOverlayAttached = container.isAttachedToWindow
            if (isOverlayAttached) {
                Log.w(TAG, "Overlay already attached, synced state")
                true
            } else {
                Log.e(TAG, "Failed to attach browser overlay", e)
                false
            }
        } catch (e: Exception) {
            isOverlayAttached = false
            Log.e(TAG, "Failed to attach browser overlay", e)
            false
        }
    }

    /** Caller must hold [overlayLock]. */
    private fun applyOverlayLayoutWithinLock(container: ViewGroup, params: WindowManager.LayoutParams) {
        container.visibility = View.VISIBLE
        if (container.isAttachedToWindow) {
            windowManager?.updateViewLayout(container, params)
        } else if (isOverlayAttached) {
            // Some Mali/EGL drivers report isAttachedToWindow=false briefly after addView.
            mainHandler.post {
                synchronized(overlayLock) {
                    val live = containerView ?: return@synchronized
                    val liveParams = windowParams ?: return@synchronized
                    if (!isOverlayAttached || live !== container) return@synchronized
                    if (live.isAttachedToWindow) {
                        live.visibility = View.VISIBLE
                        windowManager?.updateViewLayout(live, liveParams)
                    }
                }
            }
        }
    }

    private fun detachOverlayIfNeeded() {
        val container = containerView
        synchronized(overlayLock) {
            if (container == null) {
                isOverlayAttached = false
                return
            }
            if (!isOverlayAttached && !container.isAttachedToWindow) {
                return
            }
            try {
                if (container.isAttachedToWindow) {
                    windowManager?.removeView(container)
                    Log.d(TAG, "Overlay detached from WindowManager")
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay not attached to WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to detach browser overlay", e)
            } finally {
                isOverlayAttached = false
                publishOverlayVisibility()
            }
        }
    }

    // Raise z-order without remove+add — avoids tearing down the overlay Surface/GPU context.
    private fun ensureOverlayOnTop() {
        val container = containerView ?: return
        val params = windowParams ?: return
        if (!container.isAttachedToWindow) {
            attachOverlayIfNeeded()
        }
        if (OverlayLayerSplit.isPaneView(container)) {
            OverlayLayerSplit.sync()
            return
        }
        synchronized(overlayLock) {
            try {
                attachOverlayWithinLock(container, params)
                if (!isOverlayAttached) {
                    return
                }
                applyOverlayLayoutWithinLock(container, params)
                reassertForegroundOverlaysAboveBrowser()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ensure browser overlay on top", e)
            }
        }
    }

    /**
     * Explicit z-order bump (remove+add) when a peer fullscreen media layer must sit under us.
     * Prefer [ensureOverlayOnTop] for routine show; this matches voice-message / vinyl bringToFront.
     */
    private fun bringOverlayToFront() {
        if (!isOverlayVisible()) return
        val container = containerView ?: return
        if (OverlayLayerSplit.isPaneView(container)) return
        val params = windowParams ?: return
        val wm = windowManager ?: return
        OverlayZOrderCoordinator.bringToFront(wm, container, params, TAG)
    }

    /**
     * [fresh]: the window was just added and sits above everything — that pass
     * must not be swallowed by the 250ms reassert throttle.
     */
    private fun reassertForegroundOverlaysAboveBrowser(fresh: Boolean = false) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            OverlayZOrderCoordinator.requestHostReassert(this)
        } else if (fresh) {
            OverlayZOrderCoordinator.reassertForegroundOverlaysNow(this)
        } else {
            OverlayZOrderCoordinator.reassertForegroundOverlays(this)
        }
    }
    
    // Post a delayed re-show that is cancellable. cleanupWebView() removes any pending rebuild,
    // so hiding/destroying the browser can never be undone by a stale rebuild callback.
    private fun scheduleRebuild(url: String, delayMs: Long) {
        pendingRebuildRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingRebuildRunnable = null
            showWebView(url)
        }
        pendingRebuildRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    // enableBrowserVisible (HA browser_display) is user intent only — updated by HA/sidebar toggles,
    // never by overlay hide/destroy. Overlay runtime state is tracked separately in-memory.
    /** Pair membership. Visible is too late: the next overlay has already taken the seat. */
    private fun isBrowserSplitShowing(): Boolean {
        if (isBrowserHidden) return false
        val container = containerView
        if (container != null && isOverlayAttached && container.isAttachedToWindow) return true
        return splitSeatReserved
    }

    private fun isOverlayVisible(): Boolean {
        val container = containerView ?: return false
        return isOverlayAttached &&
            container.isAttachedToWindow &&
            !isBrowserHidden &&
            container.visibility == View.VISIBLE
    }

    /**
     * After L3 park the GeckoView Surface is dead; fade-resume leaves a focusable
     * hole over Ava. Recreate the overlay — the path that actually painted in adb.
     */
    private fun shouldRebuildGeckoOverlayFromHide(): Boolean {
        if (!EngineCapabilities.GECKO_BUNDLED) return false
        val container = containerView
        if (container == null || !isOverlayAttached || !container.isAttachedToWindow) return true
        return dormancyStep >= DormancyStep.L3_PARKED
    }

    /** Drop gecko input immediately so a cancelled fade cannot steal focus at alpha 0. */
    private fun parkGeckoOverlayWindow() {
        val container = containerView ?: return
        val params = windowParams ?: return
        container.animate().cancel()
        container.alpha = 0f
        container.visibility = View.GONE
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (container.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(container, params) }
                .onFailure { Log.w(TAG, "Failed to park gecko overlay input", it) }
        }
    }

    private fun unparkGeckoOverlayWindow() {
        val params = windowParams ?: return
        params.flags = params.flags and
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).inv()
        val container = containerView ?: return
        if (container.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(container, params) }
                .onFailure { Log.w(TAG, "Failed to unpark gecko overlay input", it) }
        }
    }

    private fun containsScreenPointInternal(x: Float, y: Float): Boolean {
        if (!isOverlayVisible()) return false
        val container = containerView ?: return false
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + container.width &&
            y >= loc[1] && y < loc[1] + container.height
    }

    private fun publishOverlayVisibility() {
        val visible = isOverlayVisible()
        WebViewService.publishBrowserOverlayVisible(visible)
        // Repeated attach/detach and show/hide all pass here. Visible ↔ awake.
        if (visible) releaseHaHiddenLatch()
    }

    private fun dismissAuxiliaryDialogs() {
        try { tampermonkeyDialog?.dismiss() } catch (e: Exception) { Log.w(TAG, "Error dismissing tampermonkeyDialog", e) }
        tampermonkeyDialog = null
        try { userAgentDialog?.dismiss() } catch (e: Exception) { Log.w(TAG, "Error dismissing userAgentDialog", e) }
        userAgentDialog = null
        try { remoteUrlDialog?.dismiss() } catch (e: Exception) { Log.w(TAG, "Error dismissing remoteUrlDialog", e) }
        remoteUrlDialog = null
        hideWebConsolePanel()
        try { scriptListDialog?.dismiss() } catch (e: Exception) { Log.w(TAG, "Error dismissing scriptListDialog", e) }
        scriptListDialog = null
        try { loadingDialog?.dismiss() } catch (e: Exception) { Log.w(TAG, "Error dismissing loadingDialog", e) }
        loadingDialog = null
    }

    private fun cleanupWebView(caller: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cleanupWebView(caller) }
            return
        }
        Log.d(TAG, "$caller called, containerView=$containerView, webView=$webView, overlayAttached=$isOverlayAttached")

        // An async container build in flight is now orphaned: it would otherwise finish, attach a
        // window around destroyed WebViews and clobber the fields of whatever build comes next.
        overlayGeneration.invalidate()
        // Tear down immediately — skip fade so settings rebuild / process death stays snappy.
        hideEpoch += 1
        containerView?.animate()?.cancel()
        overlayImeFocusHeld = false

        pendingRebuildRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRebuildRunnable = null
        cancelPendingSettingsRebuild()
        cancelDeferredDestroy()
        cancelCoveredDeepen()
        cancelLadderDescent()
        cancelStaggeredRightLoad()
        cancelWarmupWatchdog()
        cancelAllFirstPaintFollowup()
        clearNetworkRetryAwaiting("cleanup")
        dormancyStep = DormancyStep.L0_ACTIVE
        chunkLeftApplied = false
        chunkRightApplied = false
        chunkGeckoApplied = false
        liteLeftActive = false
        liteRightActive = false
        liteGeckoActive = false
        liteLeftFlushMs = -1
        liteRightFlushMs = -1
        liteGeckoFlushMs = -1
        liteModeFlushMs = BrowserWsStewardScripts.LITE_FAST_FLUSH_MS
        coveredDeepenDone = false
        pendingCoveredWhileWarming = false
        leftPaneWarming = false
        rightPaneWarming = false
        isWarmingUp = false
        coldStartIdleRefreshDone = false
        splitRightColdStaggerUsed = false
        splitSharedSession = false
        liteModeActive = false
        freezeAnimationsApplied = false
        pauseMediaApplied = false
        visibleIdleQuietApplied = false
        resetTouchPower()
        dormantKind = DormantKind.NONE

        // Dismiss overlay dialogs; their windows are independent and would otherwise leak.
        dismissAuxiliaryDialogs()
        htmlFullscreenController?.exit()
        htmlFullscreenController = null
        com.example.ava.webcompat.WebViewFileChooserCoordinator.cancel()

        // Release the JS bridge before the WebView so it can't retain the destroyed view/service.
        listOf(gmApi, gmApiRight).forEach { api ->
            try {
                api?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying gmApi during cleanup", e)
            }
        }
        gmApi = null
        gmApiRight = null
        haDarkModeBridge = null
        browserDownloadBridge = null
        scrollBridgeLeft = null
        scrollBridgeRight = null

        val wv = webView
        val wvRight = webViewRight
        fun stopAndBlank(target: WebView?) {
            if (target == null) return
            // Nulling the Kotlin fields above is not enough: the WebView's own JNI bridge
            // table keeps each object (and the WebViewService it captured) alive until the
            // native peer is collected. Unregister by name before the blank navigation so
            // about:blank cannot reach a bridge either.
            JS_BRIDGE_NAMES.forEach { name ->
                runCatching { target.removeJavascriptInterface(name) }
            }
            try {
                target.stopLoading()
                target.loadUrl("about:blank")
                target.onPause()
                target.setLayerType(View.LAYER_TYPE_NONE, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping WebView", e)
            }
        }
        stopAndBlank(wv)
        stopAndBlank(wvRight)
        // No renderer left to sample; the 10s loop would otherwise keep waking the
        // main thread (and retaining this service) for the rest of the process.
        stopPressureMonitor()
        stopMemoryGuard()
        stopLegacyTranspiler()

        // Detach the overlay window BEFORE destroying the WebView: stop compositing into the
        // overlay Surface first, then tear down the GL-backed WebView.
        detachOverlayIfNeeded()

        fun destroyWv(target: WebView?) {
            if (target == null) return
            try {
                (target.parent as? ViewGroup)?.removeView(target)
                target.removeAllViews()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing WebView from parent", e)
            }
            try {
                target.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying WebView", e)
            }
        }
        destroyWv(wv)
        destroyWv(wvRight)

        engineSurface?.let { surf ->
            try {
                (surf.view.parent as? ViewGroup)?.removeView(surf.view)
                surf.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying gecko surface", e)
            }
        }
        engineSurface = null

        containerView?.let { host ->
            browserPaneFit?.let { host.removeCallbacks(it) }
            browserPaneLayoutListener?.let { listener ->
                val observer = host.viewTreeObserver
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            }
        }
        containerView = null
        browserPaneLayoutListener = null
        browserPaneFit = null
        browserPaneFitW = -1
        browserPaneFitH = -1
        browserSidebarOverlay = null
        windowParams = null
        webView = null
        webViewRight = null
        splitLayout = null
        focusedPane = BrowserPane.LEFT
        splitLeftPaneActive = true
        splitRightPaneActive = true
        gestureDetector = null
        resetOverlayPullRefresh(dismiss = false)
        pullRefreshHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        pullRefreshHoldRunnable = null
        navHintView?.release()
        navHintView = null
        readabilityExtractor = null
        currentPageUrl = ""
        currentPageUrlRight = ""
        originalUrlRight = ""
        lastSyncedEntityUrl = ""
        lastSyncedEntityUrlRight = ""
        isPaused = false
        isBrowserHidden = false
        splitSeatReserved = false
        hideRequestedDuringCreate = false
        resetPaneLoadedFlags()
        isCreating = false
        // Keep lastTarget/lastLoopback so unmap still works after stop until next map.
        SecureContextProxy.instance.stop()
        publishOverlayVisibility()
    }
    
    private fun fadeInContainer() {
        val container = containerView ?: return
        container.animate().cancel()
        // Blend against the layers underneath for the whole fade; opaque again once settled.
        setOverlayCompositorOpaque(false, "fade-in")
        container.alpha = 0f
        container.visibility = View.VISIBLE
        browserPaneFitW = -1
        browserPaneFitH = -1
        OverlayLayerSplit.sync()
        container.post { dispatchBrowserViewportResize() }
        OverlayLayerSplit.runAfterColdStart {
            if (containerView !== container || isBrowserHidden) return@runAfterColdStart
            OverlayLayerSplit.fadeWhenPaneReady(OverlayLayerSplit.Layer.BROWSER) {
                if (containerView !== container || isBrowserHidden) return@fadeWhenPaneReady
                container.animate()
                    .alpha(1f)
                    .setDuration(currentFadeMs())
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        if (containerView !== container || isBrowserHidden) return@withEndAction
                        if (container.visibility != View.VISIBLE || container.alpha < 1f) return@withEndAction
                        setOverlayCompositorOpaque(true, "fade-in-end")
                    }
                    .start()
            }
        }
    }

    private fun fadeOutThen(onHidden: () -> Unit) {
        val container = containerView
        if (container == null || !isOverlayAttached || container.visibility != View.VISIBLE) {
            onHidden()
            return
        }
        val epoch = hideEpoch
        container.animate().cancel()
        // Drop the opaque hint before the first faded frame so the fade blends again.
        setOverlayCompositorOpaque(false, "fade-out")
        container.animate()
            .alpha(0f)
            .setDuration(currentFadeMs())
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (epoch != hideEpoch) return@withEndAction
                container.visibility = View.GONE
                // Keep transparent so the next attach cannot flash opaque before fade-in.
                container.alpha = 0f
                onHidden()
            }
            .start()
    }

    /**
     * Steady-state compositor hint for the system-WebView overlay window.
     *
     * The window is born [PixelFormat.TRANSLUCENT] so [fadeInContainer] / [fadeOutThen] can
     * crossfade with whatever sits underneath. Between those fades the page is a solid
     * full-screen surface, yet SurfaceFlinger still treats the layer as translucent: it
     * alpha-blends every pixel and keeps compositing MainActivity (and anything else below)
     * on every vsync. While the container is fully shown we declare the format as
     * [PixelFormat.RGBX_8888] (no alpha channel) so SurfaceFlinger flags the layer opaque,
     * skips the blend and culls the occluded layers; we switch back before any fade starts.
     *
     * Why this is cheap and safe:
     *  - The window carries FLAG_HARDWARE_ACCELERATED, so WindowManager allocates its buffers
     *    as RGBA regardless of the declared format and applies a format change in place
     *    (WindowStateAnimator.tryChangeFormatInPlaceLocked): only the layer's opaque flag flips,
     *    the Surface and GL context survive. Without hardware acceleration the change would
     *    rebuild the Surface, so it is skipped there.
     *  - RGBX_8888 rather than [PixelFormat.OPAQUE]: WindowManager treats a drawn full-screen
     *    OPAQUE window as obscuring everything below it and then ignores FLAG_KEEP_SCREEN_ON /
     *    brightness of the lower windows (e.g. the music overlay parked under the browser).
     *    RGBX only reaches SurfaceFlinger's blend decision and leaves that bookkeeping alone.
     *  - GeckoView renders through a SurfaceView; an opaque parent would black out its punched
     *    hole (ViewRootImpl forces TRANSLUCENT for such trees anyway), so Gecko is excluded.
     *  - Nothing else changes: same flags, same z-order, same focus model, same fades.
     */
    private fun setOverlayCompositorOpaque(opaque: Boolean, reason: String) {
        val params = windowParams ?: return
        val container = containerView ?: return
        val target = if (opaque) {
            if (!canDeclareOpaqueOverlay(params)) return
            PixelFormat.RGBX_8888
        } else {
            PixelFormat.TRANSLUCENT
        }
        if (params.format == target) return
        params.format = target
        synchronized(overlayLock) {
            if (!isOverlayAttached || !container.isAttachedToWindow) {
                // Not live. Translucent must stick so the next addView blends; opaque must not,
                // because a fresh Surface would show its first (alpha 0) frame as black.
                if (opaque) params.format = PixelFormat.TRANSLUCENT
                return
            }
            try {
                windowManager?.updateViewLayout(container, params)
                Log.d(TAG, "Overlay compositor format -> ${if (opaque) "opaque" else "translucent"} ($reason)")
                // RGBX can rebuild the surface and land this window on top
                // without a generation bump. The mic latch then skips and the
                // opaque layer culls the disc.
                if (opaque) OverlayZOrderCoordinator.noteWindowAdded()
            } catch (e: Exception) {
                if (opaque) params.format = PixelFormat.TRANSLUCENT
                Log.w(TAG, "Overlay compositor format change failed ($reason)", e)
            }
        }
    }

    private fun canDeclareOpaqueOverlay(params: WindowManager.LayoutParams): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            engineSurface == null &&
            webView != null &&
            (params.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0

    /**
     * Suspend the HA frontend WebSocket in every system-WebView pane (official
     * home-assistant-js-websocket suspend; see [BrowserWsStewardScripts]). Self-guarding:
     * non-HA pages and old frontends are no-ops. Gecko surfaces are left untouched.
     */
    private fun suspendHaWebSockets(reason: String) {
        if (!isStewardStreamOn()) return
        val script = BrowserWsStewardScripts.suspendJs
        webView?.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS steward suspend (left, $reason): $result")
        }
        webViewRight?.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS steward suspend (right, $reason): $result")
        }
        engineSurface?.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS steward suspend (gecko, $reason): $result")
        }
    }

    /** Resume a previously suspended HA WebSocket; reconnect is automatic (library backoff). */
    private fun resumeHaWebSockets() {
        if (!currentSettings.wsStewardEnabled) return
        val script = BrowserWsStewardScripts.resumeJs
        webView?.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS steward resume (left): $result")
        }
        webViewRight?.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS steward resume (right): $result")
        }
        engineSurface?.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS steward resume (gecko): $result")
        }
    }

    // ---------------------------------------------------------------------------
    // Pressure tier machine (system WebView only). Upgrades apply immediately; a
    // downgrade needs PRESSURE_RELAX_SAMPLES consecutive calm samples and steps down
    // one level at a time, so actions never flap.
    // ---------------------------------------------------------------------------

    private fun computePressureFloor(): PressureTier {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return PressureTier.GREEN
            // Same weak-device heuristic as FloatingWindowService.PerformanceProfile.
            if (am.isLowRamDevice || am.memoryClass <= 192) PressureTier.YELLOW
            else PressureTier.GREEN
        } catch (e: Exception) {
            PressureTier.GREEN
        }
    }

    private fun startPressureMonitor() {
        if (pressureMonitorRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                samplePressure()
                mainHandler.postDelayed(this, PRESSURE_SAMPLE_MS)
            }
        }
        pressureMonitorRunnable = runnable
        mainHandler.postDelayed(runnable, PRESSURE_SAMPLE_MS)
    }

    private fun stopPressureMonitor() {
        pressureMonitorRunnable?.let { mainHandler.removeCallbacks(it) }
        pressureMonitorRunnable = null
    }

    // ---------------------------------------------------------------------------
    // Legacy transpiler: cards written in syntax the frozen engine cannot parse
    // (Bubble-Card et al on Chromium 83) are lowered at fetch time by the native
    // oxc-transform library (see native/oxc-transpiler).
    // ---------------------------------------------------------------------------

    private var legacyTranspiler: BrowserLegacyTranspiler? = null

    private fun ensureLegacyTranspiler() {
        if (!currentSettings.legacyCompatEnabled) {
            stopLegacyTranspiler()
            return
        }
        if (legacyTranspiler != null) return
        val major = WebViewRuntime.cachedInfo(this).majorVersion
        if (major !in 1 until 96) return
        legacyTranspiler = BrowserLegacyTranspiler(applicationContext, major) { note ->
            mainHandler.post { appendConsole("log", note) }
        }
        Log.i(TAG, "Legacy card transpiler armed for chrome $major (native=${OxcTranspiler.available})")
    }

    private fun applyLegacyCompatEnabled(enabled: Boolean) {
        if (enabled) {
            ensureLegacyTranspiler()
        } else {
            stopLegacyTranspiler()
            Log.i(TAG, "Legacy card transpiler disarmed")
        }
        hardReloadAllPanes()
    }

    private fun stopLegacyTranspiler() {
        legacyTranspiler = null
    }

    // ---------------------------------------------------------------------------
    // Memory guard: HA's frontend leaks (icon until() chains, history-graph
    // dialogs, card-mod) grow until the renderer aborts — a crash-flicker loop on
    // 32-bit engines. Instead of kiosk-style blind timed reloads, probe the JS heap
    // and reload only when it nears the limit, preferring hidden/covered moments.
    // ---------------------------------------------------------------------------

    private fun startMemoryGuard() {
        if (memoryGuardRunnable != null) return
        // Deliberately NOT gated on wsStewardEnabled: this guard is the only
        // defense against HA frontend leaks (renderer OOM crash-flicker loop),
        // and users who turn the WS steward off need it just as much. It is
        // conservative by construction — acts only near the heap limit, after
        // confirming streaks, with a 10-minute reload cooldown.
        val runnable = object : Runnable {
            override fun run() {
                sampleMemoryGuard()
                mainHandler.postDelayed(this, BrowserMemoryGuard.SAMPLE_INTERVAL_MS)
            }
        }
        memoryGuardRunnable = runnable
        mainHandler.postDelayed(runnable, BrowserMemoryGuard.SAMPLE_INTERVAL_MS)
    }

    private fun stopMemoryGuard() {
        memoryGuardRunnable?.let { mainHandler.removeCallbacks(it) }
        memoryGuardRunnable = null
        memoryGuardStreaks.clear()
    }

    private fun sampleMemoryGuard() {
        // A warming page legitimately spikes while Lovelace hydrates.
        if (isCreating || isWarmingUp) return
        webView?.evaluateJavascript(BrowserMemoryGuard.probeJs) { result ->
            handleMemoryGuardSample("left", result) { webView?.reload() }
        }
        webViewRight?.evaluateJavascript(BrowserMemoryGuard.probeJs) { result ->
            handleMemoryGuardSample("right", result) { webViewRight?.reload() }
        }
        engineSurface?.evaluateJavascript(BrowserMemoryGuard.probeJs) { result ->
            handleMemoryGuardSample("gecko", result) { engineSurface?.reload() }
        }
    }

    private fun handleMemoryGuardSample(target: String, result: String?, reload: () -> Unit) {
        val sample = BrowserMemoryGuard.parse(result) ?: return
        if (!sample.elevated) {
            memoryGuardStreaks.remove(target)
            return
        }
        val streak = (memoryGuardStreaks[target] ?: 0) + 1
        memoryGuardStreaks[target] = streak
        val quietNow = isBrowserHidden || dormantKind != DormantKind.NONE
        val act = when {
            sample.critical && quietNow -> true
            sample.critical &&
                streak >= BrowserMemoryGuard.CRITICAL_STREAK_FOR_VISIBLE_RELOAD -> true
            quietNow && streak >= BrowserMemoryGuard.ELEVATED_STREAK_FOR_HIDDEN_RELOAD -> true
            else -> false
        }
        if (!act) {
            Log.i(TAG, "Memory guard ($target): $sample — waiting for a quiet moment (streak=$streak)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastMemoryGuardReloadMs < BrowserMemoryGuard.RELOAD_COOLDOWN_MS) return
        lastMemoryGuardReloadMs = now
        memoryGuardStreaks.remove(target)
        Log.w(TAG, "Memory guard ($target): $sample — controlled reload to avert a renderer OOM crash")
        runCatching {
            com.example.ava.crash.AvaIncidentLog.record(
                this,
                kind = com.example.ava.crash.AvaIncidentLog.KIND_RENDERER_CRASH,
                reason = "memory_guard_preempt_$target",
            )
        }
        appendConsole("warn", "[Ava] memory guard: $sample — reloading page to reclaim leaked memory")
        reload()
    }

    private fun samplePressure() {
        // Only meaningful while a Chromium renderer exists.
        if (webView == null && webViewRight == null) return
        val sampled = readMemoryTier() ?: return
        val target = maxOf(sampled, pressureFloor)
        when {
            target > pressureTier -> {
                pressureRelaxStreak = 0
                setPressureTier(target)
            }
            target < pressureTier -> {
                pressureRelaxStreak++
                if (pressureRelaxStreak >= PRESSURE_RELAX_SAMPLES) {
                    pressureRelaxStreak = 0
                    setPressureTier(PressureTier.entries[pressureTier.ordinal - 1])
                }
            }
            else -> pressureRelaxStreak = 0
        }
    }

    /** Tier from system memory state; thresholds are relative to the LMK threshold. */
    private fun readMemoryTier(): PressureTier? {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return null
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            when {
                info.lowMemory -> PressureTier.RED
                info.availMem < (info.threshold * 1.25).toLong() -> PressureTier.ORANGE
                info.availMem < info.threshold * 2 -> PressureTier.YELLOW
                else -> PressureTier.GREEN
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Immediate elevation from onTrimMemory callbacks; sampling relaxes it later. */
    private fun elevatePressureTier(minTier: PressureTier) {
        if (minTier > pressureTier) {
            pressureRelaxStreak = 0
            setPressureTier(minTier)
        }
    }

    private fun setPressureTier(newTier: PressureTier) {
        val old = pressureTier
        if (newTier == old) return
        pressureTier = newTier
        Log.i(TAG, "Browser pressure tier: $old -> $newTier (hidden=$isBrowserHidden)")
        applyPressureActions(old, newTier)
        pushStewardHostSnapshot()
    }

    private fun syncOffscreenPreRaster() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        // Reclaim while hidden under pressure; keep while visible (anti white-flash).
        // Frame-throttle policy always drops offscreen pre-raster to cut spare GPU work.
        val preRaster = currentSettings.hardwareAcceleration &&
            !currentSettings.wvProdFrameThrottleFeaturesEnabled &&
            (pressureTier == PressureTier.GREEN || !isBrowserHidden)
        try {
            webView?.settings?.setOffscreenPreRaster(preRaster)
            webViewRight?.settings?.setOffscreenPreRaster(preRaster)
        } catch (e: Exception) {
            Log.w(TAG, "offscreenPreRaster toggle failed", e)
        }
    }

    /** Best-effort page-cache drop for the floating browser (not a process kill). */
    /** Note: [WebView.clearCache] is application-wide — never call this while COVERED. */
    private fun purgeOverlayPageCache(reason: String) {
        try {
            webView?.clearCache(false)
            webViewRight?.clearCache(false)
            Log.d(TAG, "Purged overlay page cache ($reason)")
        } catch (e: Exception) {
            Log.w(TAG, "purgeOverlayPageCache failed ($reason)", e)
        }
    }

    private fun applyPressureActions(old: PressureTier, tier: PressureTier) {
        syncOffscreenPreRaster()
        syncChunkedRendering()
        syncLiteMode()
        // A hidden page's destroy fuse was armed at the old tier; escalation
        // re-arms it with the shorter fuse so pressure still tears down promptly.
        if (tier > old && isBrowserHidden && pendingHiddenDestroyRunnable != null) {
            scheduleDeferredDestroyAfterHide()
        }
        // Dormant under real pressure: jump the dwell timers. Parking is enough at
        // ORANGE — a full suspend is only worth its reconnect cost at RED while hidden.
        // COVERED before deepen still waits: the cover overlay is mid-paint.
        val coveredAwaitingDeepen =
            dormantKind == DormantKind.COVERED && !coveredDeepenDone
        if (isBrowserDormant() && !coveredAwaitingDeepen) {
            val forced = when {
                tier >= PressureTier.RED && isBrowserHidden -> DormancyStep.L4_SUSPENDED
                tier >= PressureTier.ORANGE -> DormancyStep.L3_PARKED
                else -> null
            }
            if (forced != null && forced > dormancyStep) {
                applyDormancyStep(forced, "pressure-$tier")
            }
        }
        syncFreezeAnimations()
        syncPauseMedia()
        // Entering RED while user-hidden: drop page cache.
        // Memory-tighten ORANGE+ purge is HIDDEN-only — never while COVERED (shared cache
        // with the screensaver WebView).
        val wantPurge =
            (tier == PressureTier.RED && old < PressureTier.RED && isBrowserHidden) ||
                (
                    currentSettings.wvProdMemoryFeaturesEnabled &&
                        tier >= PressureTier.ORANGE &&
                        isBrowserHidden &&
                        old < PressureTier.ORANGE
                    )
        if (wantPurge) {
            purgeOverlayPageCache("pressure-$tier")
        }
    }

    /**
     * Lite mode = batched entity-update delivery. Cadence follows dormancy/pressure;
     * in a live dual-pane layout the *unfocused* tile is always batched so the focused
     * dashboard keeps the full-rate budget.
     */
    private fun syncLiteMode() {
        if (!isStewardStreamOn()) {
            applyLiteTo(webView, BrowserPane.LEFT, want = false, flushMs = 0, chunk = 0)
            applyLiteTo(webViewRight, BrowserPane.RIGHT, want = false, flushMs = 0, chunk = 0)
            applyLiteToGecko(want = false, flushMs = 0, chunk = 0)
            liteModeActive = false
            return
        }
        // From L3 there is no entity traffic at all, so the flush timer is pure waste.
        val canBatch = dormancyStep < DormancyStep.L3_PARKED
        val dormantBatching = dormancyStep >= DormancyStep.L1_LIGHT && canBatch
        val pressureBatching = pressureTier >= PressureTier.ORANGE
        // First Lovelace paint must see the full entity firehose. Lite's shouldDefer
        // would queue that burst behind layout-scroll events and leave cards empty.
        if (isCreating || isWarmingUp) {
            applyLiteTo(webView, BrowserPane.LEFT, want = false, flushMs = 0, chunk = 0)
            applyLiteTo(webViewRight, BrowserPane.RIGHT, want = false, flushMs = 0, chunk = 0)
            applyLiteToGecko(want = false, flushMs = 0, chunk = 0)
            liteModeActive = false
            return
        }
        val always = currentSettings.wsStewardLiteAlwaysEnabled || splitSharedSession
        val splitLive = splitLeftPaneActive && splitRightPaneActive && webViewRight != null
        val powerMode = visiblePowerMode()
        val touchIdleBatching =
            !touchPowerBoosted &&
                dormantKind == DormantKind.NONE &&
                powerMode != BrowserPowerMode.HIGH
        val slow = dormancyStep >= DormancyStep.L2_DEEP || pressureBatching || touchIdleBatching
        val flushMs = if (slow) {
            BrowserWsStewardScripts.LITE_SLOW_FLUSH_MS
        } else {
            BrowserWsStewardScripts.LITE_FAST_FLUSH_MS
        }
        val chunk = if (slow) {
            BrowserWsStewardScripts.LITE_SLOW_CHUNK
        } else {
            BrowserWsStewardScripts.LITE_FAST_CHUNK
        }
        // Covered/hidden: both tiles share the ladder cadence.
        // Visible split: unfocused tile always batches; focused only under pressure/always.
        val globalWant = canBatch && (always || dormantBatching || pressureBatching || touchIdleBatching)
        val wantLeft = when {
            !canBatch || webView == null -> false
            dormantKind != DormantKind.NONE -> globalWant
            splitLive && focusedPane != BrowserPane.LEFT -> true
            else -> globalWant
        }
        val wantRight = when {
            !canBatch || webViewRight == null -> false
            dormantKind != DormantKind.NONE -> globalWant
            splitLive && focusedPane != BrowserPane.RIGHT -> true
            else -> globalWant
        }
        // Gecko renders a single surface — it follows the same ladder as a lone left pane.
        val wantGecko = engineSurface != null && canBatch && globalWant
        applyLiteTo(webView, BrowserPane.LEFT, wantLeft, flushMs, chunk)
        applyLiteTo(webViewRight, BrowserPane.RIGHT, wantRight, flushMs, chunk)
        applyLiteToGecko(wantGecko, flushMs, chunk)
        liteModeActive = wantLeft || wantRight || wantGecko
        liteModeFlushMs = flushMs
        if (pressureTier >= PressureTier.ORANGE &&
            !isBrowserHidden &&
            currentSettings.wsStewardEntityTrimEnabled
        ) {
            applyWsStewardEntityTrimEnabled(true)
        }
    }

    private fun applyLiteTo(
        wv: WebView?,
        pane: BrowserPane,
        want: Boolean,
        flushMs: Int,
        chunk: Int,
    ) {
        if (wv == null) {
            if (pane == BrowserPane.LEFT) {
                liteLeftActive = false
                liteLeftFlushMs = -1
            } else {
                liteRightActive = false
                liteRightFlushMs = -1
            }
            return
        }
        val wasActive = if (pane == BrowserPane.LEFT) liteLeftActive else liteRightActive
        val wasFlush = if (pane == BrowserPane.LEFT) liteLeftFlushMs else liteRightFlushMs
        if (want == wasActive && (!want || flushMs == wasFlush)) return
        if (pane == BrowserPane.LEFT) {
            liteLeftActive = want
            liteLeftFlushMs = if (want) flushMs else -1
        } else {
            liteRightActive = want
            liteRightFlushMs = if (want) flushMs else -1
        }
        val script = if (want) {
            BrowserWsStewardScripts.liteOnJs(flushMs, chunk)
        } else {
            BrowserWsStewardScripts.liteOffJs
        }
        wv.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS lite ($pane) -> $want@${flushMs}ms: $result")
        }
    }

    private fun applyLiteToGecko(want: Boolean, flushMs: Int, chunk: Int) {
        val surf = engineSurface ?: run {
            liteGeckoActive = false
            liteGeckoFlushMs = -1
            return
        }
        if (want == liteGeckoActive && (!want || flushMs == liteGeckoFlushMs)) return
        liteGeckoActive = want
        liteGeckoFlushMs = if (want) flushMs else -1
        val script = if (want) {
            BrowserWsStewardScripts.liteOnJs(flushMs, chunk)
        } else {
            BrowserWsStewardScripts.liteOffJs
        }
        surf.evaluateJavascript(script) { result ->
            Log.d(TAG, "HA WS lite (gecko) -> $want@${flushMs}ms: $result")
        }
    }

    /**
     * Gecko counterpart of [reassertStewardAfterLoad]: a navigation resets all
     * page-side steward state (the boot script itself is re-delivered at
     * document start by the console-bridge port).
     */
    private fun reassertGeckoStewardAfterLoad() {
        chunkGeckoApplied = false
        liteGeckoActive = false
        liteGeckoFlushMs = -1
        engineSurface?.setDocumentStartScripts(geckoDocumentStartScripts())
        ensureGeckoStewardInstalled()
        applyHaKioskModeScript(null)
        engineSurface?.evaluateJavascript(BrowserDownloadBridge.downloadInterceptScript())
        if (currentSettings.wsStewardEnabled && isStewardPageRuntimeNeeded()) {
            engineSurface?.evaluateJavascript(BrowserWsStewardScripts.enableJs)
            if (currentSettings.wsStewardStreamEnabled) {
                engineSurface?.evaluateJavascript(BrowserWsStewardScripts.streamOnJs)
                applyWsStewardOpaqueCardLines(
                    currentSettings.wsStewardOpaqueCardLines,
                    currentSettings.wsStewardOpaqueCardLinesCustom,
                )
                if (currentSettings.wsStewardEntityTrimEnabled) {
                    engineSurface?.evaluateJavascript(BrowserWsStewardScripts.trimOnJs)
                    engineSurface?.evaluateJavascript(BrowserWsStewardConsole.installJs)
                }
            }
        }
        syncChunkedRendering()
        syncLiteMode()
        if (dormancyStep >= DormancyStep.L3_PARKED) {
            setEntitiesParked(true)
        }
        syncFreezeAnimations()
        syncPauseMedia()
    }

    /** A reload resets page-side flags — re-arm lite/park/chunk for this pane. */
    private fun reassertStewardAfterLoad(pane: BrowserPane) {
        reassertChunkedRenderingAfterLoad(pane)
        // Force a re-evaluate even if the Kotlin-side cache thinks it is already on.
        if (pane == BrowserPane.LEFT) {
            liteLeftActive = false
            liteLeftFlushMs = -1
        } else {
            liteRightActive = false
            liteRightFlushMs = -1
        }
        syncLiteMode()
        if (dormancyStep >= DormancyStep.L3_PARKED) {
            setEntitiesParked(true)
        }
        syncFreezeAnimations()
        syncPauseMedia()
    }

    /** Overlay fade duration follows the tier so weak/pressured devices animate less. */
    private fun currentFadeMs(): Long =
        if (pressureTier >= PressureTier.YELLOW) PRESSURE_FADE_MS else FADE_MS

    /** Pressure-tier-graded fuse for the soft-hide destroy (see the constants' doc). */
    private fun hiddenDestroyDelayMs(): Long = when (pressureTier) {
        PressureTier.GREEN -> HIDDEN_DESTROY_GREEN_MS
        PressureTier.YELLOW -> HIDDEN_DESTROY_YELLOW_MS
        PressureTier.ORANGE -> HIDDEN_DESTROY_ORANGE_MS
        PressureTier.RED -> HIDDEN_DESTROY_RED_MS
    }

    /**
     * Soft-hide renderer release: armed on hide, cancelled on show. The dormancy
     * ladder (park/suspend) keeps a hidden page nearly free until the tier-graded
     * fuse burns down; escalating pressure re-arms it with the shorter fuse.
     */
    private fun scheduleDeferredDestroyAfterHide() {
        cancelDeferredDestroy()
        // Chromium only; the gecko path has its own trim-driven teardown.
        if (engineSurface != null) return
        val delayMs = hiddenDestroyDelayMs()
        val runnable = Runnable {
            pendingHiddenDestroyRunnable = null
            if (!isBrowserHidden || isCreating) return@Runnable
            Log.i(
                TAG,
                "Browser hidden ${delayMs}ms (tier=$pressureTier) — destroying soft-hidden WebView"
            )
            destroyWebView()
        }
        pendingHiddenDestroyRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelDeferredDestroy() {
        pendingHiddenDestroyRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingHiddenDestroyRunnable = null
    }

    private fun hideWebView() {
        if (containerView == null || (webView == null && engineSurface == null)) {
            if (isCreating) {
                pendingShowDuringCreateUrl = null
                hideRequestedDuringCreate = true
                isBrowserHidden = true
                splitSeatReserved = false
                dormantKind = DormantKind.HIDDEN
                Log.d(TAG, "Browser hide requested while overlay is still being created")
                // Do NOT sync visible=false to host during create: that feedback loop floods
                // SHOW/HIDE and SIGSEGVs libxul mid-[GeckoRuntime] init on the gecko pack.
                if (!EngineCapabilities.GECKO_BUNDLED) {
                    publishOverlayVisibility()
                }
                return
            }
            publishOverlayVisibility()
            if (EngineCapabilities.GECKO_BUNDLED) {
                reportGeckoOverlay(visible = false, attached = false, updateHaSwitch = false)
                releaseForegroundIfNeeded()
            }
            // Hide is removeView. Remaining overlays keep their add-order —
            // restacking vinyl/mic here only blanks the disc and races WM.
            return
        }
        try {
            dismissAuxiliaryDialogs()
            navHintView?.dismiss(committed = false)
            releaseSystemWebViewImeFocus()
            // Mark hidden immediately so a concurrent show takes the resume path and
            // invalidates this fade-out via hideEpoch.
            isBrowserHidden = true
            splitSeatReserved = false
            OverlayLayerSplit.sync()
            dormantKind = DormantKind.HIDDEN
            // Visible rest may already have onPause'd. Transfer that into [isPaused]
            // so a cancelled fade / already-visible SHOW still knows to onResume.
            parkVisiblePowerForCover()
            // Arm the 10s destroy clock at hide *intent* — if we wait for fade end,
            // a rapid show/hide race cancels the fade and never schedules destroy.
            scheduleDeferredDestroyAfterHide()
            syncOffscreenPreRaster()
            if (currentSettings.wvProdMemoryFeaturesEnabled) {
                purgeOverlayPageCache("hide")
            }
            // Gecko hide may leave the overlay attached; clear keep-on so timeout can resume.
            applyKeepScreenOn(false)
            if (EngineCapabilities.GECKO_BUNDLED) {
                parkGeckoOverlayWindow()
            }
            publishOverlayVisibility()
            fadeOutThen {
                try {
                    // Show already cancelled this hide epoch — do not pause/park a live page.
                    if (!isBrowserHidden) return@fadeOutThen
                    (containerView as? FrameLayout)?.let { teardownBrowserSidebar(it) }
                    webView?.onPause()
                    webViewRight?.onPause()
                    engineSurface?.setHostPaused(true)
                    applyPausedRendererPriority()
                    isPaused = true
                    if (engineSurface == null) {
                        detachOverlayIfNeeded()
                    }
                    // Warm-hide: the ladder parks/suspends until the tier-graded
                    // destroy fuse (see [hiddenDestroyDelayMs]) tears the renderer down.
                    applyDormancyStep(DormancyStep.L2_DEEP, "hide")
                    Log.d(
                        TAG,
                        "Browser hidden: renderer kept alive " +
                            "(destroy in ${hiddenDestroyDelayMs()}ms if still hidden)"
                    )
                    if (EngineCapabilities.GECKO_BUNDLED) {
                        reportGeckoOverlay(visible = false, attached = false, updateHaSwitch = false)
                        releaseForegroundIfNeeded()
                    }
                    // removeView does not bury what was under the browser. A
                    // hide-time restack was flashing both FABs and losing the
                    // disc mid-WM lookup (Failed looking up window).
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to finish hiding browser WebView", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide browser WebView", e)
        }
    }
    
    private fun destroyWebView() {
        pendingShowDuringCreateUrl = null
        cleanupWebView("destroyWebView")
        releaseForegroundIfNeeded()
        if (EngineCapabilities.GECKO_BUNDLED) {
            reportGeckoOverlay(visible = false, attached = false, updateHaSwitch = false)
        }
        originalUrl = ""
        currentPageUrl = ""
        lastSyncedEntityUrl = ""
        pendingRestoreUrl = null
        stopSelf()
    }

    private fun latchOverlayReceiptToken(intent: Intent?) {
        if (!EngineCapabilities.GECKO_BUNDLED) return
        val token = intent?.getLongExtra(BrowserEngine.EXTRA_RECEIPT_TOKEN, 0L) ?: 0L
        if (token != 0L) overlayReceiptToken = token
    }

    private fun reportGeckoOverlay(visible: Boolean, attached: Boolean, updateHaSwitch: Boolean) {
        if (!EngineCapabilities.GECKO_BUNDLED) return
        BrowserEngine.syncBrowserVisibleToHost(
            this,
            visible,
            updateHaSwitch = updateHaSwitch,
            receiptToken = overlayReceiptToken,
            attached = attached,
        )
    }

    private fun relaunchInternal() {
        if (EngineCapabilities.GECKO_BUNDLED) {
            restartSelf()
            return
        }
        rebuildFloatingBrowserContainer("shade-reload")
    }

    /** Shade action: tear the gecko renderer down and show the same page again. */
    private fun restartSelf() {
        if (!EngineCapabilities.GECKO_BUNDLED) return
        val url = bestRestoreUrl()
        Log.i(TAG, "Restart gecko engine service url=$url")
        cleanupWebView("restartSelf")
        if (url.isNotBlank()) {
            showWebView(url)
        }
    }

    /** Shade action: stop the gecko child service and persist browser off. */
    private fun endSelf() {
        pendingShowDuringCreateUrl = null
        cleanupWebView("endSelf")
        releaseForegroundIfNeeded()
        if (EngineCapabilities.GECKO_BUNDLED) {
            reportGeckoOverlay(visible = false, attached = false, updateHaSwitch = true)
        }
        originalUrl = ""
        currentPageUrl = ""
        lastSyncedEntityUrl = ""
        pendingRestoreUrl = null
        stopSelf()
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        // Already-absolute non-web URIs (about:blank, file:///android_asset/…) must keep
        // their scheme; prefixing would produce nonsense like "https://about:blank".
        if (NON_WEB_SCHEME.containsMatchIn(trimmed)) return trimmed
        // LAN hosts (Home Assistant, etc.) typically use plain HTTP.
        val host = trimmed.substringBefore('/').substringBefore(':')
        val isLocalHost = host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".local", ignoreCase = true) ||
            host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        return if (isLocalHost) "http://$trimmed" else "https://$trimmed"
    }

    /** Userscripts require the system WebView stack; GeckoView has no Tampermonkey bridge. */
    private fun isTampermonkeyActive(): Boolean {
        return currentSettings.tampermonkeyEnabled &&
            BrowserEngine.effectiveEngine(this, currentSettings.browserEngine) != BrowserEngine.GECKO
    }

    private fun syncCurrentBrowserUrl(url: String, pane: BrowserPane = BrowserPane.LEFT) {
        if (!currentSettings.syncBrowserUrlEnabled) {
            return
        }
        // Critical: a parked/collapsed tile's about:blank must never overwrite the
        // configured dashboard address in the HA entity.
        if (isPlaceholderPageUrl(url)) {
            return
        }
        // Critical: never write 127.0.0.1 loopback back to the HA URL entity.
        val realUrl = SecureContextProxy.instance.unmapUrl(normalizeUrl(url))
        // Fail closed: if unmap could not recover a real origin, skip write-back
        // rather than polluting prefs / the HA text entity.
        if (SecureContextProxy.isLoopbackHttpUrl(realUrl)) {
            Log.w(TAG, "HTTP page boost: skip URL sync; still loopback after unmap: $realUrl")
            return
        }
        // HA login / OAuth authorize pages are transitional — never clobber the
        // remote-URL entity (keep e.g. http://homeassistant.local:8123/...).
        if (isTransitionalHaBrowserUrl(realUrl)) {
            return
        }
        if (realUrl.length > HA_TEXT_ENTITY_STATE_MAX) {
            return
        }
        val lastSynced = if (pane == BrowserPane.RIGHT) lastSyncedEntityUrlRight else lastSyncedEntityUrl
        if (realUrl == lastSynced) {
            return
        }
        if (pane == BrowserPane.RIGHT) {
            lastSyncedEntityUrlRight = realUrl
        } else {
            lastSyncedEntityUrl = realUrl
        }
        VoiceSatelliteService.getInstance()?.syncBrowserUrlFromWebView(realUrl, pane)
    }

    /** True for HA auth redirect / callback URLs that must not be written back to the entity. */
    private fun isTransitionalHaBrowserUrl(url: String): Boolean {
        val lower = url.lowercase()
        return "/auth/authorize" in lower ||
            "/auth/login" in lower ||
            "auth_callback" in lower
    }
    
    
    private fun handleCommand(command: String) {
        val targetWv = focusedWebView()
        
        if (command.isEmpty()) {
            val clearJs = "document.querySelectorAll('[data-ava-injected]').forEach(e=>e.remove());"
            targetWv?.evaluateJavascript(clearJs, null)
            Log.d(TAG, "Cleared injected effects")
            return
        }
        
        try {
            val json = org.json.JSONObject(command)
            
            
            if (json.has("eval")) {
                val js = json.getString("eval")
                if (js.isEmpty()) {
                    
                    val clearJs = """
                        (function(){
                            var id = window.setTimeout(function(){}, 0);
                            while (id--) { window.clearTimeout(id); window.clearInterval(id); }
                            document.querySelectorAll('*').forEach(function(e){
                                var s = window.getComputedStyle(e);
                                if(s.position==='fixed' && parseInt(s.zIndex)>=9999) e.remove();
                            });
                            document.querySelectorAll('style').forEach(function(s){
                                if(s.innerHTML.indexOf('@keyframes')!==-1) s.remove();
                            });
                        })();
                    """.trimIndent()
                    targetWv?.evaluateJavascript(clearJs, null)
                    Log.d(TAG, "Cleared injected effects via empty eval")
                } else if (targetWv != null) {
                    targetWv.evaluateJavascript(js) { result ->
                        Log.d(TAG, "JS result: $result")
                    }
                    Log.d(TAG, "Executed JS: ${js.take(100)}...")
                } else {
                    Log.e(TAG, "WebView is null, cannot execute JS")
                }
            }
            
            
            if (json.has("clearCache") && json.getBoolean("clearCache")) {
                webView?.clearCache(true)
                webView?.clearHistory()
                webViewRight?.clearCache(true)
                webViewRight?.clearHistory()
                Log.d(TAG, "Cache cleared")
            }
            
            
            if (json.has("reload") && json.getBoolean("reload")) {
                reloadActive()
                Log.d(TAG, "Page reloaded")
            }
            
            
            if (json.has("settings") && json.getBoolean("settings") && !EngineCapabilities.GECKO_BUNDLED) {
                openSettingsFromBrowser(Screen.SETTINGS)
                Log.d(TAG, "Opening settings")
            }
            
            
            if (json.has("brightness")) {
                val brightness = json.getInt("brightness").coerceIn(0, 255)
                try {
                    android.provider.Settings.System.putInt(
                        contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        brightness
                    )
                    Log.d(TAG, "Brightness set to: $brightness")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set brightness", e)
                }
            }
            
            
            if (json.has("volume")) {
                val volume = json.getInt("volume").coerceIn(0, 100)
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val targetVolume = (volume * maxVolume / 100)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVolume, 0)
                    Log.d(TAG, "Volume set to: $volume%")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set volume", e)
                }
            }
            
            
            if (json.has("camera") && json.getBoolean("camera")) {
                try {
                    val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    cameraIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(cameraIntent)
                    Log.d(TAG, "Opening camera")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open camera", e)
                }
            }
            
            
            if (json.has("injectCSS")) {
                val css = json.getString("injectCSS")
                val escapedCss = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                val js = """
                    (function() {
                        var style = document.createElement('style');
                        style.type = 'text/css';
                        style.innerHTML = '$escapedCss';
                        document.head.appendChild(style);
                    })();
                """.trimIndent()
                targetWv?.evaluateJavascript(js, null)
                Log.d(TAG, "Injected CSS")
            }
            
            
            if (json.has("clickElement")) {
                val selector = json.getString("clickElement")
                val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                val js = """
                    (function() {
                        var el = document.querySelector('$escapedSelector');
                        if (el) { el.click(); return 'clicked'; }
                        return 'not found';
                    })();
                """.trimIndent()
                targetWv?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "clickElement result: $result")
                }
            }
            
            
            if (json.has("fillInput")) {
                val fillObj = json.getJSONObject("fillInput")
                val selector = fillObj.getString("selector")
                val value = fillObj.getString("value")
                val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'")
                val js = """
                    (function() {
                        var el = document.querySelector('$escapedSelector');
                        if (el) {
                            el.value = '$escapedValue';
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            return 'filled';
                        }
                        return 'not found';
                    })();
                """.trimIndent()
                targetWv?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "fillInput result: $result")
                }
            }
            
            if (json.has("extractContent") && json.getBoolean("extractContent")) {
                lifecycleScope.launch {
                    val content = readabilityExtractor?.extract()
                    if (content != null) {
                        Log.d(TAG, "Extracted content: title=${content.title}, length=${content.length}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command: $command", e)
        }
    }
    
    fun getReadabilityExtractor(): ReadabilityExtractor? = readabilityExtractor

    /**
     * Append to the single shared console buffer. Both left and right [WebChromeClient.onConsoleMessage]
     * paths call this — there is no per-pane console UI.
     */
    private fun appendConsole(level: String, message: String, pane: BrowserPane? = null) {
        val tagged = buildString {
            // Light source tag only while split has (or had) a right tile; still one transcript.
            if (pane != null && (webViewRight != null || isSplitViewActive())) {
                append(if (pane == BrowserPane.RIGHT) "[R] " else "[L] ")
            }
            if (message.length > 8192) {
                append(message.take(8192))
                append("… [truncated]")
            } else {
                append(message)
            }
        }
        consoleEntries.add(
            BrowserConsoleEntry(
                id = consoleEntrySeq.incrementAndGet(),
                timeMs = System.currentTimeMillis(),
                level = level,
                message = tagged,
            ),
        )
        while (consoleEntries.size > 300) {
            consoleEntries.removeAt(0)
        }
        consoleRevision.value = consoleRevision.value + 1
    }

    private fun clearConsoleBuffer() {
        consoleEntries.clear()
        consoleRevision.value = consoleRevision.value + 1
    }

    /**
     * One-shot detector for the Home Assistant legacy (ES5) bundle crash loop.
     *
     * HA's build pipeline has repeatedly miscompiled pre-minified lit-html when
     * transpiling the ES5 bundle it serves to older engines, leaving the dashboard
     * black with an endless `ReferenceError: _k is not defined` loop (HA 2026.1
     * betas, again mid-2026; root-caused upstream in frontend PR #52835, shipped
     * with HA Core 2026.7). Nothing app-side can repair a broken server bundle, so
     * surface the diagnosis and the fix instead of a silent black screen.
     */
    private var haEs5CrashHintShown = false
    private val haEs5CrashSignature = Regex("""ReferenceError: _\w{1,3} is not defined""")

    private fun maybeReportHaEs5CrashLoop(body: String, src: String?) {
        if (haEs5CrashHintShown) return
        if (!haEs5CrashSignature.containsMatchIn(body)) return
        // Signature errors surface either from the es5 chunk or as sourceless
        // unhandled-promise rejections; a named non-HA source is not this bug.
        if (src != null && !src.contains("frontend_es5")) return
        haEs5CrashHintShown = true
        Log.w(
            TAG,
            "HA legacy (ES5) bundle crash loop detected ($body) — known upstream bug, " +
                "fixed in HA Core 2026.7+ (frontend PR #52835). Advise updating Home Assistant.",
        )
        appendConsole(
            "warn",
            "[Ava] Home Assistant legacy (ES5) frontend is crashing — known HA bug, " +
                "update HA Core to 2026.7 or newer to fix.",
        )
        AvaToast.show(this, getString(R.string.browser_ha_es5_crash_hint), durationMs = AvaToast.LONG_MS)
    }

    /**
     * Custom Lovelace cards written in syntax this WebView cannot parse (class static
     * blocks / logical assignment need Chromium 85–94+). A card that fails to parse
     * never defines its element, so HA pops red "custom element doesn't exist" cards
     * 2s after every (re)render — perceived as flashing broken cards. Nothing
     * app-side can transpile them beyond [BrowserLegacyTranspiler]'s reach; the
     * fix is the bundled Gecko engine (or newer cards), so say that once instead
     * of failing silently. Only an explicit SyntaxError from a card resource
     * triggers this — module "failed to load" errors are usually plain 404s
     * (stale HACS registrations), which the transpiler reports separately.
     */
    private var modernCardHintShown = false

    private fun maybeReportModernCardFailure(body: String, src: String?) {
        if (modernCardHintShown) return
        // Only meaningful on engines below our modern-baseline; recent WebViews
        // parse these cards fine.
        val major = WebViewRuntime.cachedInfo(this).majorVersion
        if (major == 0 || major >= 96) return
        val cardResource = src != null && (src.contains("/hacsfiles/") || src.contains("/local/"))
        if (!cardResource || !body.contains("SyntaxError")) return
        modernCardHintShown = true
        Log.w(
            TAG,
            "Custom card needs a newer JS engine than WebView $major ($body) — " +
                "advise switching to the bundled Gecko engine or updating the card.",
        )
        appendConsole(
            "warn",
            "[Ava] Some custom cards need a newer browser engine than this WebView " +
                "($major) can provide — switch to the Gecko engine in browser settings, " +
                "or update those cards.",
        )
        AvaToast.show(this, getString(R.string.browser_modern_card_hint), durationMs = AvaToast.LONG_MS)
    }

    private fun decodeJsEvalResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
        return try {
            when (val value = org.json.JSONTokener(raw).nextValue()) {
                null, JSONObject.NULL -> null
                else -> value.toString().takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
            }
        } catch (_: Exception) {
            raw.trim().removeSurrounding("\"").takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
        }
    }

    /** Active split tiles that should feed / accept the shared web console. */
    private fun activeConsoleTargets(): List<Pair<BrowserPane, WebView>> {
        if (engineSurface != null) return emptyList()
        if (!isSplitViewActive()) {
            return listOfNotNull(webView?.let { BrowserPane.LEFT to it })
        }
        val out = ArrayList<Pair<BrowserPane, WebView>>(2)
        if (splitLeftPaneActive) {
            webView?.let { out += BrowserPane.LEFT to it }
        }
        if (splitRightPaneActive) {
            webViewRight?.let { out += BrowserPane.RIGHT to it }
        }
        return out
    }

    /**
     * Shared console REPL: one input, one transcript. In split view, evaluate on every pane that
     * currently has a URL so the right tile is included (not only the focused side).
     */
    private fun runConsoleEval(code: String) {
        val surface = engineSurface
        if (surface != null) {
            appendConsole("cmd", "> $code")
            surface.evaluateJavascript(code) { raw ->
                decodeJsEvalResult(raw)?.let { appendConsole("log", it) }
            }
            return
        }

        val targets = activeConsoleTargets()
        if (targets.isEmpty()) {
            appendConsole("error", "browser_not_running")
            return
        }

        // One command line in the shared buffer; per-pane results keep a light [L]/[R] tag.
        appendConsole("cmd", "> $code")
        val tagResults = targets.size > 1 || isSplitViewActive()
        for ((pane, wv) in targets) {
            try {
                wv.onResume()
            } catch (_: Exception) {
            }
            val paneTag = if (tagResults) pane else null
            wv.evaluateJavascript(code) { raw ->
                decodeJsEvalResult(raw)?.let { appendConsole("log", it, paneTag) }
            }
        }
    }

    /** Sidebar / API entry: open if closed, close if open (same as the panel Close button). */
    private fun toggleWebConsolePanel() {
        if (webConsoleCompose != null) {
            hideWebConsolePanel()
        } else {
            showWebConsolePanel()
        }
    }

    /** Bottom-docked console panel attached inside the browser overlay (kiosk layout). */
    private fun showWebConsolePanel() {
        val container = containerView as? FrameLayout ?: return
        engineSurface?.setConsoleCaptureEnabled(true)
        // Shared console: keep every active split WebView awake so right-pane console.* is captured.
        for ((_, wv) in activeConsoleTargets()) {
            try {
                wv.onResume()
            } catch (_: Exception) {
            }
        }
        claimOverlayWindowFocus(null)
        prepareSystemWebViewImeWindow()

        webConsoleCompose?.let { existing ->
            existing.visibility = View.VISIBLE
            existing.bringToFront()
            browserSidebarOverlay?.bringToFront()
            return
        }

        val density = resources.displayMetrics.density
        val panelHeight = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            installComposeViewTreeOwners(this)
            elevation = 24f * density
            setContent {
                val revision by consoleRevision.collectAsState()
                val dark = DarkModeManager.getInstance(this@WebViewService).isDarkMode()
                // Snapshot list for Compose; revision drives recomposition.
                val snapshot = remember(revision) { consoleEntries.toList() }
                AvaTheme(darkTheme = dark) {
                    BrowserWebConsolePanel(
                        entries = snapshot,
                        revision = revision,
                        isDarkMode = dark,
                        onRun = { runConsoleEval(it) },
                        onClear = { clearConsoleBuffer() },
                        onClose = { hideWebConsolePanel() },
                        onExpandedChange = { expanded ->
                            // Fullscreen code editor: grow the docked panel to the
                            // whole overlay while editing, restore on collapse.
                            webConsoleCompose?.let { v ->
                                (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                                    lp.height = if (expanded) {
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    } else {
                                        panelHeight
                                    }
                                    v.layoutParams = lp
                                }
                            }
                        },
                    )
                }
            }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            panelHeight,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        container.addView(compose, lp)
        webConsoleCompose = compose
        compose.bringToFront()
        browserSidebarOverlay?.bringToFront()
    }

    private fun hideWebConsolePanel() {
        val compose = webConsoleCompose ?: return
        engineSurface?.setConsoleCaptureEnabled(false)
        try {
            (compose.parent as? ViewGroup)?.removeView(compose)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing web console panel", e)
        }
        webConsoleCompose = null
        releaseSystemWebViewImeFocus()
    }
    
    /**
     * Browser tool dialog chrome (userscripts, UA picker, remote URL editor…): with the
     * Liquid Glass style on, the dialog root wears the rounded glass material; off keeps
     * the legacy flat #222222 panel so nothing else changes.
     */
    private fun styleBrowserToolDialogRoot(view: android.view.View, density: Float) {
        if (LiquidGlass.enabled) {
            LiquidGlass.applyTo(view, cornerRadiusPx = 24f * density, tint = 0xD9121824.toInt())
                .setDensity(density)
        } else {
            view.background = null
            view.setBackgroundColor(android.graphics.Color.parseColor("#222222"))
        }
    }

    private fun showTampermonkeyDialog() {
        tampermonkeyDialog?.dismiss()
        
        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
        }
        
        val scripts = userScriptManager.getAllScripts()
        val titleText = android.widget.TextView(this).apply {
            text = if (scripts.isNotEmpty()) "Tampermonkey (${scripts.size})" else "Tampermonkey"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        }
        dialogView.addView(titleText)
        
        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (10 * density).toInt(), 0, (10 * density).toInt()) }
        }
        dialogView.addView(divider)
        
        val addScriptBtn = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_add_script)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            setOnClickListener {
                tampermonkeyDialog?.dismiss()
                showAddScriptDialog()
            }
        }
        dialogView.addView(addScriptBtn)
        
        val installBtn = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_install_script)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            setOnClickListener {
                tampermonkeyDialog?.dismiss()
                showLoadingDialog()
                webView?.loadUrl("https://greasyfork.org/")
            }
        }
        dialogView.addView(installBtn)
        
        val manageBtn = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_manage_scripts)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            setOnClickListener {
                tampermonkeyDialog?.dismiss()
                showScriptListDialog()
            }
        }
        dialogView.addView(manageBtn)
        
        tampermonkeyDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .create()
        
        tampermonkeyDialog?.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        tampermonkeyDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        tampermonkeyDialog?.show()
    }

    /** Overlay UA picker — same chrome as [showTampermonkeyDialog]; persists mode and reloads. */
    private fun showUserAgentDialog() {
        userAgentDialog?.dismiss()

        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
        }

        val titleText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.settings_browser_useragent)
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        }
        dialogView.addView(titleText)

        val descText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.settings_browser_useragent_desc)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#999999"))
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        dialogView.addView(descText)

        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (10 * density).toInt(), 0, (6 * density).toInt()) }
        }
        dialogView.addView(divider)

        val labels = intArrayOf(
            com.example.ava.R.string.useragent_default,
            com.example.ava.R.string.useragent_desktop,
            com.example.ava.R.string.useragent_macos,
            com.example.ava.R.string.useragent_ios,
        )
        val selectedMode = currentSettings.userAgentMode.coerceIn(0, labels.lastIndex)
        labels.forEachIndexed { index, labelRes ->
            val row = android.widget.TextView(this).apply {
                text = getString(labelRes)
                textSize = 14f
                setTextColor(
                    if (index == selectedMode) android.graphics.Color.WHITE
                    else android.graphics.Color.parseColor("#CCCCCC")
                )
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                setOnClickListener {
                    userAgentDialog?.dismiss()
                    if (index == currentSettings.userAgentMode) return@setOnClickListener
                    if (EngineCapabilities.GECKO_BUNDLED) {
                        HostSidebarCommandBridge.send(
                            HostSidebarSettingsContract.CMD_SET_USER_AGENT_MODE,
                            JSONObject().put("mode", index).toString(),
                        )
                    } else {
                        lifecycleScope.launch {
                            browserSettingsStore.setUserAgentMode(index)
                        }
                    }
                }
            }
            dialogView.addView(row)
        }

        userAgentDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .create()

        userAgentDialog?.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        userAgentDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        userAgentDialog?.show()
    }

    /**
     * Overlay URL editor — same chrome as Tampermonkey / UA picker.
     * Prefills from the configured HA remote URL ([VoiceSatelliteSettings.haRemoteUrl]),
     * falling back to the live page URL. Confirm navigates + writes back via
     * [VoiceSatelliteService.applyHaRemoteUrlFromUi] (HA entity ↔ browser dual sync).
     */
    private fun showRemoteUrlDialog() {
        remoteUrlDialog?.dismiss()

        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
        }

        val titleText = android.widget.TextView(this).apply {
            text = getString(
                if (focusedPane == BrowserPane.RIGHT && webViewRight != null)
                    com.example.ava.R.string.settings_browser_ha_remote_url_right
                else
                    com.example.ava.R.string.settings_browser_ha_remote_url
            )
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        }
        dialogView.addView(titleText)

        val descText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.settings_browser_sync_url_desc)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#999999"))
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        dialogView.addView(descText)

        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (10 * density).toInt(), 0, (10 * density).toInt()) }
        }
        dialogView.addView(divider)

        // Never show 127.0.0.1 in the editor — always the real HA origin.
        val editPane = if (focusedPane == BrowserPane.RIGHT && webViewRight != null) {
            BrowserPane.RIGHT
        } else {
            BrowserPane.LEFT
        }
        val liveUrl = SecureContextProxy.instance.unmapUrl(
            if (editPane == BrowserPane.RIGHT) {
                currentPageUrlRight.ifBlank { originalUrlRight }
            } else {
                currentPageUrl.ifBlank { originalUrl }
            },
        )
        val editText = android.widget.EditText(this).apply {
            hint = "https://"
            setHintTextColor(android.graphics.Color.parseColor("#555555"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            textSize = 14f
            setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            // Provisional fill from the live page; replaced by configured HA URL when available.
            if (liveUrl.isNotBlank()) setText(liveUrl)
        }
        dialogView.addView(editText)

        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
        }

        val cancelBtn = android.widget.TextView(this).apply {
            text = getString(android.R.string.cancel)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        buttonRow.addView(cancelBtn)

        val okBtn = android.widget.TextView(this).apply {
            text = getString(android.R.string.ok)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        buttonRow.addView(okBtn)
        dialogView.addView(buttonRow)

        remoteUrlDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .create()

        cancelBtn.setOnClickListener { remoteUrlDialog?.dismiss() }
        okBtn.setOnClickListener {
            val raw = editText.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) return@setOnClickListener
            // Unmap first in case the user pasted a loopback URL while boost is on.
            val normalized = SecureContextProxy.instance.unmapUrl(normalizeUrl(raw))
            if (SecureContextProxy.isLoopbackHttpUrl(normalized)) {
                Log.w(TAG, "Remote URL editor: refusing loopback URL $normalized")
                return@setOnClickListener
            }
            remoteUrlDialog?.dismiss()
            // Avoid echo when syncBrowserUrlEnabled writes the same URL back.
            if (editPane == BrowserPane.RIGHT) {
                lastSyncedEntityUrlRight = normalized
            } else {
                lastSyncedEntityUrl = normalized
            }
            VoiceSatelliteService.getInstance()?.applyHaRemoteUrlFromUi(normalized, editPane)
                ?: showOrRefreshWebView(normalized, editPane)
        }

        remoteUrlDialog?.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        remoteUrlDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        remoteUrlDialog?.show()

        // Always read the configured HA remote URL; prefer live page when present (write-back).
        lifecycleScope.launch {
            val saved = try {
                val store = VoiceSatelliteSettingsStore(applicationContext.voiceSatelliteSettingsStore)
                val settings = store.get()
                if (editPane == BrowserPane.RIGHT) settings.haRemoteUrlRight else settings.haRemoteUrl
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read haRemoteUrl", e)
                ""
            }
            val fill = liveUrl.ifBlank { saved }
            if (fill.isBlank()) return@launch
            withContext(Dispatchers.Main) {
                editText.setText(fill)
                editText.setSelection(fill.length)
            }
        }
    }
    
    private var loadingDialog: android.app.AlertDialog? = null
    
    private fun showLoadingDialog() {
        loadingDialog?.dismiss()
        val density = resources.displayMetrics.density
        
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val progressBar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = android.widget.LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
        }
        dialogView.addView(progressBar)
        
        val loadingText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_installing)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        dialogView.addView(loadingText)
        
        loadingDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        loadingDialog?.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        loadingDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loadingDialog?.show()
    }
    
    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
    
    private fun downloadAndInstallScript(url: String) {
        showLoadingDialog()
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val scriptContent = connection.inputStream.bufferedReader().use { it.readText() }
                
                val script = UserScriptManager.parseUserScript(scriptContent)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    hideLoadingDialog()
                    if (script != null) {
                        userScriptManager.saveScript(script)
                        showScriptInstalledDialog(script.name)
                    } else {
                        showInstallFailedDialog()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download script: $url", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    hideLoadingDialog()
                    showInstallFailedDialog()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }
    
    private fun showInstallFailedDialog() {
        val density = resources.displayMetrics.density
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
            gravity = Gravity.CENTER
        }
        
        val msgText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_install_failed)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
        }
        dialogView.addView(msgText)
        
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .create()
        
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
    
    private fun showScriptInstalledDialog(scriptName: String) {
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(com.example.ava.R.string.tampermonkey_script_installed))
            .setMessage(scriptName)
            .setPositiveButton("OK", null)
            .create()
        
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    private fun injectMatchingScripts(url: String, pane: BrowserPane) {
        val target = when (pane) {
            BrowserPane.RIGHT -> webViewRight
            BrowserPane.LEFT -> webView
        } ?: return
        target.evaluateJavascript(GmApiInjector.getGmApiScript()) {
            Log.d(TAG, "GM API injected ($pane)")
        }
        
        val scripts = userScriptManager.getMatchingScripts(url)
        scripts.forEach { script ->
            val metadataEndMarker = "==/UserScript=="
            val metadataEnd = script.code.indexOf(metadataEndMarker)
            if (metadataEnd < 0) return@forEach
            
            var codeStart = metadataEnd + metadataEndMarker.length
            while (codeStart < script.code.length && script.code[codeStart] in listOf('\n', '\r', ' ', '\t')) {
                codeStart++
            }
            val pureCode = script.code.substring(codeStart)
            
            if (pureCode.isBlank()) return@forEach
            
            val wrappedCode = """
                (function() {
                    'use strict';
                    try {
                        $pureCode
                    } catch(e) {
                        console.error('[Tampermonkey] Script error:', e);
                    }
                })();
            """.trimIndent()
            
            target.evaluateJavascript(wrappedCode) { result ->
                Log.d(TAG, "Injected script: ${script.name} ($pane)")
            }
        }
    }
    
    private fun showAddScriptDialog() {
        val density = resources.displayMetrics.density
        
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
        }
        
        val titleText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_add_script)
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        dialogView.addView(titleText)
        
        val descText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_add_script_desc)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        dialogView.addView(descText)
        
        val editText = android.widget.EditText(this).apply {
            hint = "// ==UserScript==\n// @name  My Script\n// @match *://*/*\n// ==/UserScript==\n\nalert('Hello!');"
            setHintTextColor(android.graphics.Color.parseColor("#555555"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            textSize = 13f
            minLines = 8
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        dialogView.addView(editText)
        
        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        
        val cancelBtn = android.widget.TextView(this).apply {
            text = getString(android.R.string.cancel)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        buttonRow.addView(cancelBtn)
        
        val saveBtn = android.widget.TextView(this).apply {
            text = getString(android.R.string.ok)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        buttonRow.addView(saveBtn)
        dialogView.addView(buttonRow)
        
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .create()
        
        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val code = editText.text.toString()
            if (code.isNotBlank()) {
                val script = UserScriptManager.parseUserScript(code)
                if (script != null) {
                    userScriptManager.saveScript(script)
                    dialog.dismiss()
                    showScriptInstalledDialog(script.name)
                } else {
                    val simpleScript = com.example.ava.utils.UserScript(
                        id = System.currentTimeMillis().toString(),
                        name = "Custom Script",
                        namespace = "local",
                        version = "1.0",
                        description = "",
                        matchPatterns = listOf("*"),
                        code = "// ==UserScript==\n// @name Custom Script\n// @match *://*/*\n// ==/UserScript==\n\n$code",
                        enabled = true
                    )
                    userScriptManager.saveScript(simpleScript)
                    dialog.dismiss()
                    showScriptInstalledDialog("Custom Script")
                }
            }
        }
        
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
    
    private fun showScriptListDialog() {
        scriptListDialog?.dismiss()
        
        val density = resources.displayMetrics.density
        val scripts = userScriptManager.getAllScripts()
        
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            styleBrowserToolDialogRoot(this, density)
        }
        
        val titleText = android.widget.TextView(this).apply {
            text = getString(com.example.ava.R.string.tampermonkey_manage_scripts)
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        dialogView.addView(titleText)
        
        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, 0, 0, (8 * density).toInt()) }
        }
        dialogView.addView(divider)
        
        if (scripts.isEmpty()) {
            val emptyText = android.widget.TextView(this).apply {
                text = getString(com.example.ava.R.string.tampermonkey_no_scripts)
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#666666"))
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            }
            dialogView.addView(emptyText)
        } else {
            val scrollView = android.widget.ScrollView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { 
                    height = minOf((200 * density).toInt(), (scripts.size * 48 * density).toInt())
                }
            }
            val listContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            
            scripts.forEach { script ->
                val itemView = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                    gravity = Gravity.CENTER_VERTICAL
                }
                
                val statusDot = android.widget.TextView(this).apply {
                    text = if (script.enabled) "●" else "○"
                    textSize = 11f
                    setTextColor(if (script.enabled) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#555555"))
                    setPadding(0, 0, (6 * density).toInt(), 0)
                    setOnClickListener {
                        userScriptManager.toggleScript(script.id, !script.enabled)
                        showScriptListDialog()
                    }
                }
                itemView.addView(statusDot)
                
                val nameText = android.widget.TextView(this).apply {
                    text = script.name
                    textSize = 13f
                    setTextColor(if (script.enabled) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#666666"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, (16 * density).toInt(), 0)
                    }
                    setOnClickListener {
                        userScriptManager.toggleScript(script.id, !script.enabled)
                        showScriptListDialog()
                    }
                }
                itemView.addView(nameText)
                
                val deleteBtn = android.widget.TextView(this).apply {
                    text = "✕"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#555555"))
                    setPadding((10 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
                    setOnClickListener {
                        userScriptManager.deleteScript(script.id)
                        showScriptListDialog()
                    }
                }
                itemView.addView(deleteBtn)
                
                listContainer.addView(itemView)
            }
            scrollView.addView(listContainer)
            dialogView.addView(scrollView)
        }
        
        val closeBtn = android.widget.TextView(this).apply {
            text = "OK"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }
        dialogView.addView(closeBtn)
        
        scriptListDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(dialogView)
            .create()
        
        closeBtn.setOnClickListener { scriptListDialog?.dismiss() }
        
        scriptListDialog?.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        scriptListDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        scriptListDialog?.show()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!EngineCapabilities.GECKO_BUNDLED) {
            // System-WebView path: never destroy directly (availability first). Trim
            // callbacks elevate the pressure tier; the tier machine then applies the
            // matching reversible actions (WS suspend when hidden, lite mode when
            // visible, pre-raster off, cache trim) and relaxes them via sampling.
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                    elevatePressureTier(PressureTier.RED)
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
                    elevatePressureTier(PressureTier.ORANGE)
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                    elevatePressureTier(PressureTier.YELLOW)
            }
            return
        }
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                if (isBrowserHidden && engineSurface != null) {
                    Log.w(TAG, "Memory pressure while browser hidden — destroying gecko renderer")
                    cleanupWebView("onTrimMemory-hidden")
                } else {
                    engineSurface?.onTrimMemory(level)
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                engineSurface?.onTrimMemory(level)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents. Overlay windows are not bound to the task stack,
        // so tear everything down right now instead of relying on a possibly-delayed
        // onDestroy — this is the "service is going away, clean up immediately" guard.
        try {
            cleanupWebView("onTaskRemoved")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onTaskRemoved cleanup", e)
        }
        releaseForegroundIfNeeded()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun applyLayerSplit(frame: OverlayLayerSplit.Frame?) {
        val host = containerView ?: return
        if (frame == null && isBrowserHidden && host.visibility == View.VISIBLE) return
        OverlayLayerSplit.applyTo(
            OverlayLayerSplit.Layer.BROWSER,
            windowManager,
            host,
            windowParams,
            frame,
        )
        if (!host.isAttachedToWindow) return
        scheduleBrowserPaneFit(host)
    }

    /**
     * The overlay window can change size without the page noticing. System WebView
     * and GeckoView keep the previous surface until a layout pass runs after
     * [WindowManager.updateViewLayout], so the page stays full-screen and spills
     * out of the pane. Refit once the new window frame is laid out, then tell the
     * document after the size settles.
     */
    private fun scheduleBrowserPaneFit(host: View) {
        pokeBrowserPane(host)
        val observer = host.viewTreeObserver
        if (browserPaneLayoutListener == null && observer.isAlive) {
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                val live = containerView ?: return@OnGlobalLayoutListener
                if (live !== host || live.width <= 1 || live.height <= 1) return@OnGlobalLayoutListener
                if (live.width == browserPaneFitW && live.height == browserPaneFitH) return@OnGlobalLayoutListener
                browserPaneFitW = live.width
                browserPaneFitH = live.height
                if (webViewRight != null) refreshSplitLayoutOrientation()
                browserPaneFit?.let { live.removeCallbacks(it) }
                val notify = Runnable {
                    if (containerView === live &&
                        live.width == browserPaneFitW &&
                        live.height == browserPaneFitH
                    ) {
                        dispatchBrowserViewportResize()
                    }
                }
                browserPaneFit = notify
                live.postDelayed(notify, 80)
            }
            browserPaneLayoutListener = listener
            observer.addOnGlobalLayoutListener(listener)
        }
    }

    private fun pokeBrowserPane(host: View) {
        host.forceLayout()
        host.requestLayout()
        webView?.forceLayout()
        webView?.requestLayout()
        webViewRight?.forceLayout()
        webViewRight?.requestLayout()
        engineSurface?.view?.let { surface ->
            surface.forceLayout()
            surface.requestLayout()
        }
    }

    private fun dispatchBrowserViewportResize() {
        webView?.let { fitWebContent(it) }
        webViewRight?.let { fitWebContent(it) }
        val surface = engineSurface?.view
        if (surface != null && surface.width > 1 && surface.height > 1) {
            runCatching { engineSurface?.evaluateJavascript(browserFitJs(surface.width, surface.height)) }
        }
    }

    private fun fitWebContent(wv: WebView) {
        if (wv.width <= 1 || wv.height <= 1) return
        runCatching { wv.evaluateJavascript(browserFitJs(wv.width, wv.height), null) }
    }

    /**
     * Lay the document out in this pane. If the engine is still using the old
     * full-screen viewport, or the page is wider than the pane, zoom it down
     * so the whole page sits inside the window instead of being cropped.
     */
    private fun browserFitJs(panePxW: Int, panePxH: Int): String = """
        (function(){
          var dpr = window.devicePixelRatio || 1;
          var paneW = $panePxW / dpr;
          var paneH = $panePxH / dpr;
          var de = document.documentElement;
          if (!de) return;
          var body = document.body;
          var cur = parseFloat(de.style.zoom);
          if (!cur || cur <= 0) cur = 1;
          var vw = window.innerWidth || paneW;
          var vh = window.innerHeight || paneH;
          var sw = Math.max(de.scrollWidth || 0, body ? body.scrollWidth : 0, vw) / cur;
          var sh = Math.max(de.scrollHeight || 0, body ? body.scrollHeight : 0, vh) / cur;
          var z = Math.min(paneW / Math.max(sw, 1), paneH / Math.max(sh, 1), paneW / Math.max(vw, 1), paneH / Math.max(vh, 1), 1);
          if (z > 0.98) {
            if (de.style.zoom) de.style.zoom = '';
          } else {
            de.style.zoom = String(Math.max(z, 0.2));
          }
          if ((window.innerWidth || paneW) > paneW * 1.05) window.scrollTo(0, window.scrollY || 0);
          window.dispatchEvent(new Event('resize'));
        })();
    """.trimIndent()

    override fun onDestroy() {
        OverlayLayerSplit.unregister(OverlayLayerSplit.Layer.BROWSER)
        super.onDestroy()
        unlinkHostLiveness()
        stopPressureMonitor()
        stopMemoryGuard()
        stopLegacyTranspiler()
        browserSettingsObserverJob?.cancel()
        browserSettingsObserverJob = null
        appDarkModeObserverJob?.cancel()
        appDarkModeObserverJob = null
        sidebarSettingsObserverJob?.cancel()
        sidebarSettingsObserverJob = null
        try {
            cleanupWebView("onDestroy")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy cleanup", e)
        }
        viewModelStoreImpl.clear()
        releaseForegroundIfNeeded()
        instance = null
        publishBrowserOverlayVisible(false)
    }
    
    override fun onBind(intent: Intent): IBinder {
        if (EngineCapabilities.GECKO_BUNDLED) {
            linkHostLiveness(intent)
        }
        return Binder()
    }

    override fun onUnbind(intent: Intent): Boolean {
        // Only release the death link on a real host-initiated unbind. If the host process died,
        // the token is no longer alive and we must keep the link so binderDied() can fire.
        val token = hostLivenessToken
        if (token == null || token.isBinderAlive) {
            unlinkHostLiveness()
        }
        return false
    }

    /**
     * Watch the host process via the liveness Binder it passed in the bind intent. A dead token
     * means the host process is gone for good, so this orphaned overlay must tear itself down —
     * the host can no longer send HIDE/DESTROY. Event-driven (binderDied), never polled.
     */
    private fun linkHostLiveness(intent: Intent) {
        val token = intent.getBundleExtra(BrowserEngine.EXTRA_HOST_LIVENESS)
            ?.getBinder(BrowserEngine.KEY_HOST_LIVENESS_TOKEN) ?: return
        if (token === hostLivenessToken) return
        unlinkHostLiveness()
        val recipient = IBinder.DeathRecipient { tearDownOrphanedOverlay("host_process_died") }
        try {
            token.linkToDeath(recipient, 0)
            hostLivenessToken = token
            hostDeathRecipient = recipient
            Log.d(TAG, "Linked to host liveness token (orphan watchdog active)")
        } catch (e: Exception) {
            // Host already dead at bind time — tear down immediately.
            tearDownOrphanedOverlay("host_dead_at_bind")
        }
    }

    private fun unlinkHostLiveness() {
        val recipient = hostDeathRecipient
        val token = hostLivenessToken
        if (recipient != null && token != null) {
            try {
                token.unlinkToDeath(recipient, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unlink host liveness", e)
            }
        }
        hostDeathRecipient = null
        hostLivenessToken = null
    }

    private fun tearDownOrphanedOverlay(reason: String) {
        Log.w(TAG, "Host gone ($reason) — tearing down orphaned gecko overlay")
        mainHandler.post {
            try {
                unlinkHostLiveness()
                cleanupWebView(reason)
                releaseForegroundIfNeeded()
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to tear down after host death", e)
            }
        }
    }
}
