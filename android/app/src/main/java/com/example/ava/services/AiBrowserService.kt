package com.example.ava.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import com.example.ava.homeassistant.HaManager
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.ava.touchpad.TouchPadMath
import com.example.ava.touchpad.TouchPadPageScroll
import com.example.ava.utils.ReadabilityExtractor
import com.example.ava.webcompat.EngineSurface
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AiBrowserService : LifecycleService() {
    private val browserUiEventAction = "com.example.ava.AI_BROWSER_UI_EVENT"
    private val desktopUserAgent = EngineSurface.Companion.UserAgents.DESKTOP_CHROME
    private val visibleTextJs: String by lazy {
        assets.open("ai_browser_viewport.js").bufferedReader().use { it.readText() }
    }
    private var windowManager: WindowManager? = null
    private var containerView: ViewGroup? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var padLiftActive = false
    @Volatile private var webView: WebView? = null
    private var readabilityExtractor: ReadabilityExtractor? = null
    @Volatile private var currentPageUrl: String = ""
    private var sessionId: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable {
        if (webView != null) return@Runnable
        Log.i(TAG, "idle empty shell — stopSelf")
        stopSelf()
    }

    companion object {
        private const val TAG = "AiBrowserService"
        const val ACTION_SHOW = "com.example.ava.services.AiBrowserService.SHOW"
        const val ACTION_HIDE = "com.example.ava.services.AiBrowserService.HIDE"
        const val ACTION_REFRESH = "com.example.ava.services.AiBrowserService.REFRESH"
        const val EXTRA_URL = "url"
        /** Empty service after hide. A new SHOW cancels this. */
        private const val IDLE_STOP_MS = 120_000L
        @Volatile private var instance: AiBrowserService? = null
        private val loadLock = Any()
        private var loadWaiter: CompletableDeferred<String>? = null
        @Volatile private var pageLoading = false
        @Volatile private var pageReady = false
        var windowGeneration = 0L
            private set

        fun show(context: Context, url: String) {
            val intent = Intent(context, AiBrowserService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_URL, url)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, AiBrowserService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        /**
         * Mini order step 1: tear the overlay down and wait until the WebView
         * is gone. Do not [stopSelf] here — a following SHOW would lose the
         * race with [onDestroy] and the new page would never stay up.
         */
        suspend fun hideAndAwait(context: Context, timeoutMs: Long = 2_500L): Boolean {
            if (instance?.webView == null) return true
            hide(context)
            return withTimeoutOrNull(timeoutMs) {
                while (instance?.webView != null) delay(40)
                true
            } ?: false
        }

        fun refresh(context: Context) {
            val intent = Intent(context, AiBrowserService::class.java).apply {
                action = ACTION_REFRESH
            }
            context.startService(intent)
        }

        @JvmStatic
        fun getCurrentPageText(context: Context): String {
            val active = instance ?: return JSONObject()
                .put("ok", false)
                .put("error", "browser_not_running")
                .toString()
            return active.extractCurrentPageText()
        }

        fun isShowing(): Boolean = instance?.webView != null

        fun beginPadPageScroll() {
            instance?.evalPadJs(TouchPadPageScroll.jsPin(true))
        }

        fun nudgePageScroll(@Suppress("UNUSED_PARAMETER") screenX: Float, @Suppress("UNUSED_PARAMETER") screenY: Float, dx: Float, dy: Float): Boolean {
            val svc = instance ?: return false
            if (svc.webView == null) return false
            svc.applyPadPageScroll(dx, dy)
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

        /**
         * Same remove+add raise [WebViewService.bringToFrontIfVisible] uses when
         * fullscreen music would cover the page. Voice chrome is restacked after
         * this by [OverlayZOrderCoordinator], so the mic and captions stay above.
         */
        fun bringToFrontIfVisible() {
            instance?.restackWindow()
        }

        fun hostUrl(): String = instance?.currentPageUrl.orEmpty()

        /**
         * Mini order: this overlay only — never [WebViewService].
         * Live WebView → navigate in place. Hidden or cold → SHOW.
         * Wait until [onPageFinished].
         */
        suspend fun openAndAwait(context: Context, url: String, timeoutMs: Long = 18_000L): String? {
            val waiter = CompletableDeferred<String>()
            synchronized(loadLock) {
                loadWaiter?.cancel()
                loadWaiter = waiter
            }
            val settled = try {
                val live = instance
                if (live?.webView != null) {
                    withContext(Dispatchers.Main) { live.loadUrl(url) }
                } else {
                    show(context, url)
                }
                withTimeoutOrNull(timeoutMs) { waiter.await() }
            } finally {
                synchronized(loadLock) {
                    if (loadWaiter === waiter) loadWaiter = null
                }
                // SHOW may still be queued when the voice turn is interrupted.
                // Always enqueue HIDE after it, even if no WebView exists yet.
                if (!currentCoroutineContext().isActive) withContext(NonCancellable) { hide(context) }
            }
            if (!settled.isNullOrBlank()) return settled
            return null
        }

        suspend fun awaitReady(): Boolean = withTimeoutOrNull(18_000L) {
            withContext(Dispatchers.Main) {
                while (pageLoading && instance?.webView != null) delay(40)
                instance?.webView != null && pageReady
            }
        } ?: false

        suspend fun evaluateJavascript(script: String): String? {
            val wv = instance?.webView ?: return null
            return withTimeoutOrNull(5_000L) {
                withContext(Dispatchers.Main) {
                    if (instance?.webView !== wv) return@withContext null
                    suspendCancellableCoroutine { cont ->
                        wv.evaluateJavascript(script) { raw ->
                            if (cont.isActive) cont.resume(unwrapJs(raw))
                        }
                    }
                }
            }
        }

        suspend fun extractArticle(): com.example.ava.utils.ExtractedContent? =
            instance?.readabilityExtractor?.extract()

        suspend fun navigateAndAwait(action: String): String? = withContext(Dispatchers.Main) {
            val wv = instance?.webView ?: return@withContext null
            if (action == "back" && !wv.canGoBack() || action == "forward" && !wv.canGoForward()) return@withContext null
            val waiter = CompletableDeferred<String>()
            synchronized(loadLock) { loadWaiter?.cancel(); loadWaiter = waiter }
            pageLoading = true
            try {
                when (action) {
                    "back" -> wv.goBack()
                    "forward" -> wv.goForward()
                    "refresh" -> wv.reload()
                    else -> return@withContext null
                }
                withTimeoutOrNull(18_000L) { waiter.await() }?.takeIf { it.isNotBlank() }
            } finally {
                synchronized(loadLock) { if (loadWaiter === waiter) loadWaiter = null }
            }
        }

        suspend fun viewport(): String? = instance?.let { evaluateJavascript(it.visibleTextJs) }

        fun goBack(): Boolean {
            val wv = instance?.webView ?: return false
            if (!wv.canGoBack()) return false
            wv.post { wv.goBack() }
            return true
        }

        fun goForward(): Boolean {
            val wv = instance?.webView ?: return false
            if (!wv.canGoForward()) return false
            wv.post { wv.goForward() }
            return true
        }

        fun reload() {
            instance?.webView?.post { instance?.webView?.reload() }
        }

        /**
         * Move the live page. [direction] is one of up / down / top / bottom.
         * [amountPx] 0 means one viewport step. Returns the new scroll position
         * so the model can see whether it hit the end.
         */
        suspend fun scroll(direction: String, amountPx: Int = 0): JSONObject? {
            if (instance?.webView == null) return null
            val dir = when (direction.trim().lowercase()) {
                "up", "down", "top", "bottom" -> direction.trim().lowercase()
                else -> "down"
            }
            val step = amountPx.coerceIn(0, 20_000)
            val raw = evaluateJavascript(
                """
                (function(){
                  var h = window.innerHeight || document.documentElement.clientHeight || 600;
                  var max = Math.max(0,
                    (document.documentElement.scrollHeight || document.body.scrollHeight || 0) - h);
                  var y = window.scrollY || document.documentElement.scrollTop || 0;
                  var step = $step > 0 ? $step : Math.floor(h * 0.85);
                  var next = y;
                  var dir = "$dir";
                  if (dir === "down") next = Math.min(max, y + step);
                  else if (dir === "up") next = Math.max(0, y - step);
                  else if (dir === "top") next = 0;
                  else if (dir === "bottom") next = max;
                  window.scrollTo(0, next);
                  var now = window.scrollY || document.documentElement.scrollTop || next;
                  return JSON.stringify({
                    scroll_y: Math.round(now),
                    viewport: Math.round(h),
                    max: Math.round(max),
                    at_top: now <= 0,
                    at_bottom: now >= max - 1
                  });
                })();
                """.trimIndent(),
            ) ?: return null
            return runCatching { JSONObject(raw) }.getOrNull()
        }

        private fun notifyLoadSettled(url: String) {
            synchronized(loadLock) {
                loadWaiter?.complete(url)
                loadWaiter = null
            }
        }

        private fun unwrapJs(raw: String?): String? {
            if (raw.isNullOrBlank() || raw == "null") return null
            val trimmed = raw.trim()
            return runCatching {
                org.json.JSONTokener(trimmed).nextValue() as? String ?: trimmed
            }.getOrDefault(trimmed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        cancelIdleStop()
        cleanup()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW -> {
                sessionId = intent.getStringExtra("sid") ?: sessionId
                showBrowser(intent.getStringExtra(EXTRA_URL) ?: "")
            }
            ACTION_HIDE -> hideBrowser()
            ACTION_REFRESH -> webView?.reload()
        }
        return if (intent?.action == ACTION_HIDE) START_NOT_STICKY else START_STICKY
    }

    private fun showBrowser(url: String) {
        cancelIdleStop()
        val targetUrl = normalizeUrl(url)
        if (targetUrl.isEmpty() || !allowedUrl(targetUrl)) {
            notifyLoadSettled("")
            return
        }
        if (containerView != null) {
            loadUrl(targetUrl)
            bringToFront()
            return
        }

        lifecycleScope.launch {
            try {
                val createdWebView = WebView(this@AiBrowserService)
                webView = createdWebView
                readabilityExtractor = ReadabilityExtractor(createdWebView)
                setupWebView(createdWebView)

                val density = resources.displayMetrics.density
                val buttonSize = (44 * density).toInt()
                val margin = (14 * density).toInt()
                val navGap = (10 * density).toInt()
                val buttonBackground = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(Color.argb(205, 20, 20, 20))
                    setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.argb(235, 255, 255, 255))
                }

                val container = FrameLayout(this@AiBrowserService).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        createdWebView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )

                    val backButton = ImageButton(this@AiBrowserService).apply {
                        setImageResource(android.R.drawable.ic_media_previous)
                        background = buttonBackground.constantState?.newDrawable()?.mutate()
                        setColorFilter(Color.WHITE)
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        elevation = 12f
                        contentDescription = "Back"
                        setOnClickListener {
                            val wv = webView
                            if (wv?.canGoBack() == true) {
                                wv.goBack()
                                reportUiAction("back")
                            }
                        }
                        setOnLongClickListener {
                            hideBrowser()
                            true
                        }
                    }
                    addView(
                        backButton,
                        FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                            gravity = Gravity.TOP or Gravity.START
                            setMargins(margin, margin, margin, margin)
                        }
                    )

                    val forwardButton = ImageButton(this@AiBrowserService).apply {
                        setImageResource(android.R.drawable.ic_media_next)
                        background = buttonBackground.constantState?.newDrawable()?.mutate()
                        setColorFilter(Color.WHITE)
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        elevation = 12f
                        contentDescription = "Forward"
                        setOnClickListener {
                            val wv = webView
                            if (wv?.canGoForward() == true) {
                                wv.goForward()
                                reportUiAction("forward")
                            }
                        }
                    }
                    addView(
                        forwardButton,
                        FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                            gravity = Gravity.TOP or Gravity.START
                            setMargins(margin + buttonSize + navGap, margin, margin, margin)
                        }
                    )

                    val hideButton = ImageButton(this@AiBrowserService).apply {
                        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        background = buttonBackground.constantState?.newDrawable()?.mutate()
                        setColorFilter(Color.WHITE)
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        elevation = 12f
                        contentDescription = "Hide"
                        setOnClickListener {
                            reportUiAction("hide")
                            hideBrowser()
                        }
                    }
                    addView(
                        hideButton,
                        FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                            gravity = Gravity.TOP or Gravity.END
                            setMargins(margin, margin, margin, margin)
                        }
                    )
                }

                val params = WindowManager.LayoutParams().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        type = WindowManager.LayoutParams.TYPE_PHONE
                    }
                    @Suppress("DEPRECATION")
                    // Same overlay bucket as HA Chromium / Esper / mic FAB.
                    // A focusable MATCH_PARENT page stays above FLAG_NOT_FOCUSABLE
                    // chrome on many ROMs, so remove+add never lifts the disc or
                    // captions. FLAG_FULLSCREEN is the Esper/FAB sublayer.
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
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

                containerView = container
                windowParams = params
                windowManager?.addView(container, params)
                windowGeneration++
                raiseVoiceAboveThis()
                loadUrl(targetUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show AI browser", e)
                cleanup()
                scheduleIdleStop()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        settings.userAgentString = desktopUserAgent
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true

        wv.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated WebView callback kept for older Android versions")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = !allowedUrl(url.orEmpty())

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                !allowedUrl(request?.url?.toString().orEmpty())

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (allowedUrl(url) && AiBrowserUrlPolicy.resolvesPublicly(url)) return null
                if (request?.isForMainFrame == true && url == currentPageUrl) {
                    pageLoading = false
                    pageReady = false
                    notifyLoadSettled("")
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (view !== webView) return
                pageReady = false
                pageLoading = true
                currentPageUrl = url.orEmpty()
                if (!allowedUrl(currentPageUrl)) {
                    pageLoading = false
                    view?.stopLoading()
                    notifyLoadSettled("")
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) { pageLoading = false; notifyLoadSettled("") }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) { pageLoading = false; notifyLoadSettled("") }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val done = url ?: currentPageUrl
                if (view !== webView || done != view?.url) return
                currentPageUrl = done
                if (pageLoading) {
                    pageLoading = false
                    pageReady = allowedUrl(done)
                    notifyLoadSettled(if (pageReady) done else "")
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) {
                    return
                }
                // Public pages never inherit Ava's microphone permission.
                request.deny()
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                super.onPermissionRequestCanceled(request)
                // This browser never owns a pending permission request.
            }
        }
    }

    private fun allowedUrl(url: String): Boolean {
        val configured = listOf(
            HaManager.get()?.settingsStore?.getCached()?.serverUrl.orEmpty(),
            VoiceSatelliteSettingsStore(applicationContext.voiceSatelliteSettingsStore).getCached().haRemoteUrl,
        )
        val hosts = configured.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }.toSet()
        return AiBrowserUrlPolicy.allows(url, hosts)
    }

    private fun loadUrl(url: String) {
        if (!allowedUrl(url)) { notifyLoadSettled(""); return }
        webView?.stopLoading()
        pageReady = false
        pageLoading = true
        currentPageUrl = url
        webView?.loadUrl(url)
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun evalPadJs(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    private fun applyPadPageScroll(dx: Float, dy: Float) {
        webView?.evaluateJavascript(TouchPadPageScroll.jsNudge(dx, dy), null)
    }

    private fun beginPadOverlayLift(): Boolean {
        val container = containerView ?: return false
        if (webView == null || !container.isAttachedToWindow) return false
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
            .start()
        return false
    }

    private fun tapPadPointer(screenX: Float, screenY: Float): Boolean {
        val target = webView ?: return false
        if (!target.isAttachedToWindow) return false
        return TouchPadPageScroll.dispatchScreenTap(target, screenX, screenY)
    }

    private fun secondaryTapPadPointer(screenX: Float, screenY: Float): Boolean {
        val target = webView ?: return false
        if (!target.isAttachedToWindow) return false
        return TouchPadPageScroll.dispatchScreenSecondaryTap(target, screenX, screenY)
    }

    private fun beginPadContentDrag(screenX: Float, screenY: Float): Boolean {
        if (!containsScreenPointInternal(screenX, screenY)) return false
        val target = webView ?: return false
        if (!target.isAttachedToWindow) return false
        return TouchPadPageScroll.beginViewDrag(target, screenX, screenY)
    }

    private fun containsScreenPointInternal(x: Float, y: Float): Boolean {
        val container = containerView ?: return false
        if (webView == null || !container.isAttachedToWindow) return false
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + container.width &&
            y >= loc[1] && y < loc[1] + container.height
    }

    private fun hideBrowser() {
        cleanup()
        scheduleIdleStop()
    }

    private fun bringToFront() {
        restackWindow()
        raiseVoiceAboveThis()
    }

    /** Remove+add only. The coordinator calls this, then raises voice itself. */
    private fun restackWindow() {
        val container = containerView ?: return
        val params = windowParams ?: return
        try {
            windowManager?.removeViewImmediate(container)
            windowManager?.addView(container, params)
            windowGeneration++
            OverlayZOrderCoordinator.noteWindowAdded()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bring AI browser to front", e)
        }
    }

    /**
     * Fresh addView sits on top of every same-type overlay, including the mic
     * and STT plate. Bump the stack generation so generation-gated raises
     * actually run, then restack voice chrome the way [WebViewService] does.
     * Clears the reassert throttle — a voice-turn restack often landed in the
     * last 250ms and would otherwise swallow this pass, leaving the FAB buried.
     */
    private fun raiseVoiceAboveThis() {
        OverlayZOrderCoordinator.noteWindowAdded()
        OverlayZOrderCoordinator.reassertForegroundOverlaysNow(this)
    }

    private fun cleanup() {
        try {
            containerView?.let { container ->
                windowManager?.removeViewImmediate(container)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed removing AI browser container", e)
        }
        try {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.clearHistory()
            webView?.removeAllViews()
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed destroying AI browser webview", e)
        }
        containerView = null
        windowParams = null
        webView = null
        readabilityExtractor = null
        currentPageUrl = ""
        pageReady = false
        pageLoading = false
        notifyLoadSettled("")
        sessionId = ""
        com.example.ava.localllm.remote.AvaBrowserTools.onBrowserClosed()
    }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStop)
        mainHandler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun cancelIdleStop() {
        mainHandler.removeCallbacks(idleStop)
    }

    private fun extractCurrentPageText(): String {
        val extractor = readabilityExtractor ?: return JSONObject()
            .put("ok", false)
            .put("error", "browser_not_ready")
            .toString()

        val latch = CountDownLatch(1)
        var result = JSONObject()
            .put("ok", false)
            .put("error", "extract_timeout")

        lifecycleScope.launch {
            try {
                val content = extractor.extract()
                val visible = extractVisibleViewportText()
                val visibleText = visible?.optString("text", "") ?: ""
                result = if (content != null) {
                    val excerpt = content.excerpt?.let {
                        if (it.length > 280) it.substring(0, 280) + "..." else it
                    } ?: ""
                    val textContent = when {
                        visibleText.isNotBlank() -> visibleText
                        content.textContent?.length ?: 0 > 1200 -> content.textContent.substring(0, 1200) + "..."
                        else -> content.textContent ?: ""
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("url", content.url)
                        .put("title", content.title)
                        .put("text", textContent)
                        .put("excerpt", excerpt)
                        .put("length", content.length)
                        .put("scroll_y", visible?.optInt("scrollY", 0) ?: 0)
                        .put("viewport_height", visible?.optInt("viewportHeight", 0) ?: 0)
                        .put("scan_top", visible?.optInt("scanTop", 0) ?: 0)
                        .put("scan_bottom", visible?.optInt("scanBottom", 0) ?: 0)
                        .put("extraction_mode", if (visibleText.isNotBlank()) "viewport" else "readability")
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

    private suspend fun extractVisibleViewportText(): JSONObject? {
        val wv = webView ?: return null
        return try {
            val raw = evaluateJavascript(wv, visibleTextJs) ?: return null
            val clean = raw.trim().let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    it.substring(1, it.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                } else {
                    it
                }
            }
            JSONObject(clean)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract visible text", e)
            null
        }
    }

    private suspend fun evaluateJavascript(wv: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { result ->
                cont.resume(result)
            }
        }

    private fun reportUiAction(action: String) {
        try {
            val payload = JSONObject()
                .put("action", action)
                .put("url", currentPageUrl)
                .put("session_id", sessionId)
                .put("can_go_back", webView?.canGoBack() == true)
                .put("can_go_forward", webView?.canGoForward() == true)
                .put("timestamp", System.currentTimeMillis())
            val intent = Intent(browserUiEventAction).apply {
                `package` = packageName
                putExtra("sid", sessionId)
                putExtra("payload", payload.toString())
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report AI browser UI action: $action", e)
        }
    }
}
