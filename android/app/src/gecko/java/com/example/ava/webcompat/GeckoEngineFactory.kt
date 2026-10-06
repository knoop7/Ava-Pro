package com.example.ava.webcompat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import com.example.ava.ui.AvaToast
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** gecko flavor: builds a GeckoView-backed [EngineSurface]. */
object GeckoEngineFactory {
    fun create(context: Context): EngineSurface = GeckoEngineSurface(context)
}

private class GeckoEngineSurface(private val context: Context) : EngineSurface {

    companion object {
        private const val TAG = "GeckoEngineSurface"
        private const val CONSOLE_EXTENSION_LOCATION =
            "resource://android/assets/ava_web_console/"
        private const val CONSOLE_EXTENSION_ID = "ava-web-console@ava.local"
        private const val CONSOLE_NATIVE_APP = "avaWebConsole"

        @Volatile
        private var runtime: GeckoRuntime? = null

        private fun runtime(context: Context): GeckoRuntime {
            return runtime ?: synchronized(this) {
                runtime ?: GeckoRuntime.create(
                    context.applicationContext,
                    GeckoRuntimeSettings.Builder()
                        .javaScriptEnabled(true)
                        .webFontsEnabled(true)
                        .build()
                ).also { runtime = it }
            }
        }
    }

    private var session = GeckoSession()
    private val geckoView = GeckoView(context)
    private val selectionActionDelegate =
        (context as? Activity)?.let { BasicSelectionActionDelegate(it) }
    private val pendingScripts = ArrayDeque<String>()
    private val pendingPortEvals = ArrayDeque<PendingEval>()
    private var javascriptUriInFlight = false
    private var canGoBackFlag = false
    private var currentScale = 0
    private var currentTextZoom = 100
    private var currentUserAgentMode = 0
    private var pageLoaded = false
    private var firstComposite = false
    private var currentUrl: String? = null
    private var darkModeEnabled = false
    private var consoleExtension: WebExtension? = null
    private var consolePort: WebExtension.Port? = null
    private var consoleCaptureEnabled = false
    private val consoleEvalSequence = AtomicLong(0)
    private val pendingConsoleEvals = mutableMapOf<Long, (String?) -> Unit>()
    private var documentStartScripts: List<String> = emptyList()

    private data class PendingEval(
        val script: String,
        val onResult: ((String?) -> Unit)?,
    )

    override var onPageUrlChanged: ((String) -> Unit)? = null
    override var onConsoleMessage: ((String, String) -> Unit)? = null
    override var onGestureBack: (() -> Unit)? = null
    override var onPrepareTextInput: (() -> Unit)? = null
    override var onHaDarkModeChanged: ((Boolean) -> Unit)? = null
    override var onScrollActivity: (() -> Unit)? = null
    override var onContentProcessCrash: (() -> Unit)? = null
    override var onPageLoadFinished: ((Boolean, String?) -> Unit)? = null

    private var canGoForwardFlag = false
    private var scrollY = 0
    private var hostPaused = false
    private var htmlFullscreen = false

    init {
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true
        geckoView.isClickable = true
        geckoView.isLongClickable = true
        openSession()
    }

    private fun openSession() {
        val userAgent = getUserAgentString(currentUserAgentMode)
        val sessionSettings = GeckoSessionSettings.Builder().apply {
            userAgentMode(geckoUserAgentMode(currentUserAgentMode))
            viewportMode(geckoViewportMode(currentUserAgentMode))
            if (!userAgent.isNullOrEmpty()) {
                userAgentOverride(userAgent)
            }
        }.build()
        session = GeckoSession(sessionSettings)
        session.open(runtime(context))
        geckoView.setSession(session)
        setupSessionDelegates()
        registerConsoleBridge()
        applyPreferredColorScheme()
    }

    private val consolePortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            if (port !== consolePort || message !is JSONObject) return
            when (message.optString("type")) {
                "console" -> {
                    val level = when (val incoming = message.optString("level")) {
                        "log", "info", "warn", "error", "debug" -> incoming
                        else -> "log"
                    }
                    val body = message.optString("message").take(8192)
                    if (body.isNotBlank()) onConsoleMessage?.invoke(level, body)
                }

                "eval-result" -> {
                    val id = message.optLong("id", -1L)
                    val value = if (message.isNull("value")) null else message.optString("value")
                    pendingConsoleEvals.remove(id)?.invoke(value)
                }

                "eval-error" -> {
                    val id = message.optLong("id", -1L)
                    val error = message.optString("message", "JavaScript evaluation failed").take(8192)
                    pendingConsoleEvals.remove(id)?.invoke(null)
                    onConsoleMessage?.invoke("error", error)
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (port !== consolePort) return
            consolePort = null
            completePendingConsoleEvals()
        }
    }

    private val consoleMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val sender = port.sender
            val trusted = sender.session === session &&
                sender.isTopLevel &&
                sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
            if (!trusted) {
                port.disconnect()
                return
            }
            completePendingConsoleEvals()
            consolePort?.disconnect()
            consolePort = port
            port.setDelegate(consolePortDelegate)
            postConsoleCaptureState()
            // Content script connects at document_start; this is still one IPC hop
            // later than WebView addDocumentStartJavaScript. Boot first, then any
            // toggles that queued while the previous port was down.
            postDocumentStartScripts()
            flushPendingPortEvals()
        }
    }

    private fun registerConsoleBridge() {
        val targetSession = session
        runtime(context).webExtensionController
            .ensureBuiltIn(CONSOLE_EXTENSION_LOCATION, CONSOLE_EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        Log.e(TAG, "Web console bridge registration returned no extension")
                    } else if (session === targetSession && targetSession.isOpen) {
                        consoleExtension = extension
                        targetSession.webExtensionController.setMessageDelegate(
                            extension,
                            consoleMessageDelegate,
                            CONSOLE_NATIVE_APP,
                        )
                    }
                },
                { error -> Log.e(TAG, "Failed to register web console bridge", error) },
            )
    }

    private fun unregisterConsoleBridge() {
        consoleExtension?.let { extension ->
            try {
                session.webExtensionController.setMessageDelegate(
                    extension,
                    null,
                    CONSOLE_NATIVE_APP,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister web console bridge", e)
            }
        }
        consoleExtension = null
        consolePort?.disconnect()
        consolePort = null
        completePendingConsoleEvals()
    }

    private fun completePendingConsoleEvals() {
        val callbacks = pendingConsoleEvals.values.toList()
        pendingConsoleEvals.clear()
        callbacks.forEach { it(null) }
    }

    private fun postConsoleCaptureState() {
        try {
            consolePort?.postMessage(
                JSONObject()
                    .put("type", "set-enabled")
                    .put("enabled", consoleCaptureEnabled),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update console capture state", e)
        }
    }

    private fun postDocumentStartScripts() {
        val port = consolePort ?: return
        val scripts = documentStartScripts
        if (scripts.isEmpty()) return
        // One script per message so a large bootJs cannot blow a single native-messaging
        // payload, and a failed shim does not skip the rest.
        for (script in scripts) {
            try {
                port.postMessage(
                    JSONObject()
                        .put("type", "init-scripts")
                        .put("scripts", JSONArray().put(script)),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deliver document-start script", e)
            }
        }
    }

    private fun isJavascriptUri(url: String?): Boolean =
        url != null && url.regionMatches(0, "javascript:", 0, 11, ignoreCase = true)

    private fun setupSessionDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackFlag = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canGoForwardFlag = canGoForward
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (uri.startsWith(BrowserDarkModeScripts.DARK_MODE_NAV_SCHEME)) {
                    val isDark = uri.endsWith("1")
                    onHaDarkModeChanged?.invoke(isDark)
                    return GeckoResult.deny()
                }
                if (BrowserDownloadBridge.handleNavigationUri(context, uri)) {
                    return GeckoResult.deny()
                }
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                Log.e(
                    TAG,
                    "Gecko load error category=${error.category} code=${error.code} uri=$uri",
                )
                // Only network/proxy failures arm host restore-retry; cert/content errors stay local.
                val networkish =
                    error.category == WebRequestError.ERROR_CATEGORY_NETWORK ||
                        error.category == WebRequestError.ERROR_CATEGORY_PROXY
                if (networkish) {
                    val failUrl = uri ?: currentUrl
                    geckoView.post {
                        onPageLoadFinished?.invoke(false, failUrl)
                    }
                }
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (isJavascriptUri(url)) return
                pageLoaded = false
                firstComposite = false
                pendingScripts.clear()
                // Keep pendingPortEvals. First-load boot/enable is queued before
                // loadUri; wiping here dropped them and the current page never
                // got steward. onConnect posts boots first, then flushes this queue.
                currentUrl = url
                onPageUrlChanged?.invoke(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (javascriptUriInFlight) {
                    javascriptUriInFlight = false
                    if (consolePort == null) flushJavascriptUriFallback()
                    return
                }
                if (success) {
                    pageLoaded = true
                    // Re-push stored boots in case connect raced an empty script list.
                    postDocumentStartScripts()
                    applyZoomAndTextSize()
                    flushPendingPortEvals()
                    flushPendingScripts()
                    val url = currentUrl
                    geckoView.post {
                        onPageLoadFinished?.invoke(true, url)
                    }
                }
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstComposite(session: GeckoSession) {
                firstComposite = true
                flushPendingPortEvals()
                flushPendingScripts()
                if (consolePort == null) flushJavascriptUriFallback()
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "Gecko content process crashed")
                geckoView.post {
                    onContentProcessCrash?.invoke()
                }
            }

            // Element.requestFullscreen (e.g. WebRTC camera card `ui: true`). Gecko renders the
            // content fullscreen inside the GeckoView itself; the overlay is already edge-to-edge,
            // so we only track state for back-key exit.
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                htmlFullscreen = fullScreen
            }

            // Downloads (`<a download>`, attachment responses) — save into system Downloads.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val body = response.body
                if (body == null) {
                    Log.w(TAG, "External response without body: ${response.uri}")
                    return
                }
                val mimeType = headerValue(response, "Content-Type")
                    ?.substringBefore(';')?.trim()?.ifBlank { null }
                    ?: "application/octet-stream"
                val fileName = URLUtil.guessFileName(
                    response.uri, headerValue(response, "Content-Disposition"), mimeType
                )
                BrowserDownloadHandler.saveStream(context, fileName, mimeType, body)
            }
        }
        session.promptDelegate = createPromptDelegate()
        session.permissionDelegate = createPermissionDelegate()
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                this@GeckoEngineSurface.scrollY = scrollY
                onScrollActivity?.invoke()
            }
        }
        session.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun restartInput(session: GeckoSession, reason: Int) {
                geckoView.post {
                    prepareTextInputFocus()
                    inputMethodManager()?.restartInput(geckoView)
                }
            }

            override fun showSoftInput(session: GeckoSession) {
                geckoView.post {
                    prepareTextInputFocus()
                    val imm = inputMethodManager() ?: return@post
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                        geckoView.hasFocus() &&
                        !imm.isActive(geckoView)
                    ) {
                        geckoView.clearFocus()
                        geckoView.requestFocus()
                    }
                    imm.showSoftInput(geckoView, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            override fun hideSoftInput(session: GeckoSession) {
                geckoView.post {
                    val imm = inputMethodManager() ?: return@post
                    imm.hideSoftInputFromWindow(geckoView.windowToken, 0)
                }
            }
        })
        session.setSelectionActionDelegate(selectionActionDelegate)
    }

    private fun headerValue(response: WebResponse, name: String): String? {
        for ((key, value) in response.headers) {
            if (key.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    // The overlay browser has no Activity, so Gecko's default prompt UI can't be hosted.
    // Mirror the WebView path: alert → toast + dismiss, confirm/prompt → dismiss (safe,
    // never destructive), beforeunload → always allow leaving, file picker → trampoline activity.
    private fun createPromptDelegate() = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            prompt.message?.takeIf { it.isNotBlank() }?.let { msg ->
                geckoView.post { AvaToast.show(context, msg, durationMs = AvaToast.LONG_MS) }
            }
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            Log.w(TAG, "Auto-dismissed js confirm(): ${prompt.message}")
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            Log.w(TAG, "Auto-dismissed js prompt(): ${prompt.message}")
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            // Never let a page veto navigation in a kiosk-style browser.
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                val mimes = prompt.mimeTypes?.filterNotNull()?.filter { it.isNotBlank() }
                if (!mimes.isNullOrEmpty()) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            val launched = WebViewFileChooserCoordinator.launchWithIntent(context, picker) { uris ->
                try {
                    when {
                        uris.isNullOrEmpty() -> result.complete(prompt.dismiss())
                        uris.size == 1 -> result.complete(prompt.confirm(context, uris[0]))
                        else -> result.complete(prompt.confirm(context, uris))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to complete file prompt", e)
                }
            }
            if (!launched) result.complete(prompt.dismiss())
            return result
        }
    }

    private fun createPermissionDelegate() = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> hasLocationPermission()
                // Camera cards rely on autoplaying streams; there is no user prompt surface here.
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> true
                else -> false
            }
            return GeckoResult.fromValue(
                if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            )
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            // No Activity to prompt from; grant only what the app already holds.
            val allGranted = permissions?.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } ?: false
            if (allGranted) callback.grant() else callback.reject()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun prepareTextInputFocus() {
        onPrepareTextInput?.invoke()
        if (!geckoView.isFocusableInTouchMode) {
            geckoView.isFocusable = true
            geckoView.isFocusableInTouchMode = true
        }
        if (!geckoView.hasFocus()) {
            geckoView.requestFocus()
        }
    }

    private fun inputMethodManager(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun getUserAgentString(mode: Int): String? {
        return when (mode) {
            1 -> EngineSurface.Companion.UserAgents.DESKTOP_CHROME
            2 -> EngineSurface.Companion.UserAgents.MACOS_SAFARI
            3 -> EngineSurface.Companion.UserAgents.IOS_SAFARI
            else -> null
        }
    }

    private fun geckoUserAgentMode(mode: Int): Int =
        if (mode == 1 || mode == 2) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }

    private fun geckoViewportMode(mode: Int): Int =
        if (mode == 1 || mode == 2) {
            GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }

    /**
     * Prefer the console-bridge port (page.eval / script-tag). javascript: session.load
     * is last-resort only: each load() cancels the previous one, and REPLACE_HISTORY
     * can abort a real navigation.
     */
    private fun evaluateJavaScript(script: String, onResult: ((String?) -> Unit)? = null) {
        if (!session.isOpen) {
            onResult?.invoke(null)
            return
        }
        val port = consolePort
        if (port != null) {
            postEval(port, script, onResult)
            return
        }
        pendingPortEvals.addLast(PendingEval(script, onResult))
        if (pageLoaded && firstComposite) {
            flushJavascriptUriFallback()
        }
    }

    private fun postEval(
        port: WebExtension.Port,
        script: String,
        onResult: ((String?) -> Unit)?,
    ) {
        val id = consoleEvalSequence.incrementAndGet()
        if (onResult != null) pendingConsoleEvals[id] = onResult
        try {
            port.postMessage(
                JSONObject()
                    .put("type", "eval")
                    .put("id", id)
                    .put("code", script),
            )
        } catch (e: Exception) {
            pendingConsoleEvals.remove(id)
            Log.w(TAG, "Failed to send console evaluation", e)
            pendingPortEvals.addLast(PendingEval(script, onResult))
            if (pageLoaded && firstComposite) flushJavascriptUriFallback()
        }
    }

    private fun flushPendingPortEvals() {
        val port = consolePort ?: return
        while (pendingPortEvals.isNotEmpty()) {
            val next = pendingPortEvals.removeFirst()
            postEval(port, next.script, next.onResult)
        }
    }

    private fun clearPendingPortEvals() {
        val callbacks = pendingPortEvals.mapNotNull { it.onResult }
        pendingPortEvals.clear()
        callbacks.forEach { it(null) }
    }

    private fun flushPendingScripts() {
        if (consolePort != null) return
        if (!pageLoaded || !firstComposite || pendingScripts.isEmpty()) return
        val generationUrl = currentUrl
        geckoView.post {
            if (consolePort != null) return@post
            if (!pageLoaded || !firstComposite) return@post
            if (currentUrl != generationUrl) return@post
            if (pendingScripts.isEmpty()) return@post
            if (javascriptUriInFlight) return@post
            val script = pendingScripts.removeFirst()
            injectJavaScriptNow(script)
        }
    }

    private fun flushJavascriptUriFallback() {
        if (consolePort != null || javascriptUriInFlight) return
        if (!pageLoaded || !firstComposite) return
        val next = pendingPortEvals.pollFirst() ?: return
        javascriptUriInFlight = true
        pendingScripts.addLast(next.script)
        next.onResult?.invoke(null)
        flushPendingScripts()
    }

    private fun injectJavaScriptNow(script: String) {
        if (!session.isOpen) return
        val uri = if (script.startsWith("javascript:")) {
            script
        } else {
            "javascript:${Uri.encode(script)}"
        }
        try {
            session.load(
                GeckoSession.Loader()
                    .uri(uri)
                    .flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY)
            )
        } catch (e: Exception) {
            javascriptUriInFlight = false
            Log.w(TAG, "JavaScript injection failed", e)
        }
    }

    private fun applyPreferredColorScheme() {
        val scheme = if (darkModeEnabled) {
            GeckoRuntimeSettings.COLOR_SCHEME_DARK
        } else {
            GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        }
        runtime(context).settings.setPreferredColorScheme(scheme)
    }

    private fun applyZoomAndTextSize() {
        if (!pageLoaded) return
        val scalePercent = if (currentScale == 0) 100 else currentScale
        val textZoomPercent = currentTextZoom
        val js = """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.content = 'width=device-width, initial-scale=${scalePercent / 100.0}, user-scalable=yes';
                document.body.style.fontSize = '${textZoomPercent}%';
                document.body.style.webkitTextSizeAdjust = '${textZoomPercent}%';
            })();
        """.trimIndent()
        evaluateJavaScript(js)
        applyDarkModeScript()
        evaluateJavaScript(BrowserDownloadBridge.downloadInterceptScript())
    }

    private fun applyDarkModeScript() {
        if (!pageLoaded) return
        val script = if (darkModeEnabled) {
            BrowserDarkModeScripts.homeAssistantDarkModeJs
        } else {
            BrowserDarkModeScripts.homeAssistantLightModeJs
        }
        evaluateJavaScript(script)
    }

    override val view: View get() = geckoView

    override fun loadUrl(url: String) {
        session.loadUri(url)
    }

    override fun reload() {
        session.reload()
    }

    override fun canGoBack(): Boolean = canGoBackFlag

    override fun goBack() {
        session.goBack()
    }

    override fun canGoForward(): Boolean = canGoForwardFlag

    override fun goForward() {
        session.goForward()
    }

    @SuppressLint("ApplySharedPref")
    override fun evaluateJavascript(script: String) {
        evaluateJavaScript(script, onResult = null)
    }

    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        evaluateJavaScript(script, onResult)
    }

    override fun setConsoleCaptureEnabled(enabled: Boolean) {
        if (consoleCaptureEnabled == enabled) return
        consoleCaptureEnabled = enabled
        postConsoleCaptureState()
    }

    override fun setDocumentStartScripts(scripts: List<String>) {
        documentStartScripts = scripts
        // Push whenever the port is live — do not wait for pageLoaded. Connect can
        // happen at document_start before onPageStop; waiting lost the current page.
        postDocumentStartScripts()
    }

    override fun setTouchEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Touch blocking is handled at the overlay container so GeckoView PanZoomController
        // always receives the full gesture stream (click / long-press / scroll).
    }

    override fun setGestureNavigationEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Edge-swipe back is handled by WebViewService's overlay gesture detector.
    }

    override fun canScrollVerticallyUp(): Boolean = scrollY > 0

    override fun setInitialScale(scale: Int) {
        currentScale = scale.coerceIn(0, 500)
        applyZoomAndTextSize()
    }

    override fun setTextZoom(percent: Int) {
        currentTextZoom = percent.coerceIn(50, 300)
        applyZoomAndTextSize()
    }

    override fun setUserAgentMode(mode: Int) {
        val normalizedMode = mode.coerceIn(0, 3)
        if (normalizedMode == currentUserAgentMode) return
        currentUserAgentMode = normalizedMode
        if (!session.isOpen) return

        // These GeckoSessionSettings keys are runtime-mutable. Keep the existing session bound
        // to GeckoView so switching UA cannot lose its display, history, delegates, or extension port.
        session.settings.apply {
            setUserAgentMode(geckoUserAgentMode(normalizedMode))
            setViewportMode(geckoViewportMode(normalizedMode))
            setUserAgentOverride(getUserAgentString(normalizedMode))
        }
        if (!currentUrl.isNullOrBlank()) {
            session.reload()
        }
    }

    override fun setDarkMode(enabled: Boolean) {
        if (darkModeEnabled == enabled) return
        darkModeEnabled = enabled
        applyPreferredColorScheme()
        applyDarkModeScript()
    }

    override fun setHostPaused(paused: Boolean) {
        if (hostPaused == paused) return
        hostPaused = paused
        applySessionActiveState()
    }

    override fun onTrimMemory(level: Int) {
        if (!session.isOpen || !hostPaused) return
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> applySessionActiveState(forceInactive = true)
        }
    }

    private fun applySessionActiveState(forceInactive: Boolean = false) {
        if (!session.isOpen) return
        val active = !forceInactive && !hostPaused
        try {
            session.setActive(active)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set gecko session active=$active", e)
        }
    }

    override fun supportsCapability(capability: String): Boolean {
        return when (capability) {
            EngineSurface.Companion.Capabilities.INITIAL_SCALE -> true
            EngineSurface.Companion.Capabilities.TEXT_ZOOM -> true
            EngineSurface.Companion.Capabilities.USER_AGENT -> true
            EngineSurface.Companion.Capabilities.USER_SCRIPTS -> false
            EngineSurface.Companion.Capabilities.GESTURE_NAVIGATION -> true
            else -> false
        }
    }

    override fun clearData() {
        clearGeckoData(StorageController.ClearFlags.ALL, "all browsing data")
    }

    override fun clearCacheOnly() {
        clearGeckoData(
            StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE,
            "cache",
        )
    }

    override fun clearCookiesAndSiteData() {
        clearGeckoData(
            StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.DOM_STORAGES or
                StorageController.ClearFlags.AUTH_SESSIONS,
            "cookies and site data",
        )
    }

    private fun clearGeckoData(flags: Long, label: String) {
        try {
            val rt = runtime(context)
            rt.storageController.clearData(flags).then { _ ->
                Log.d(TAG, "Gecko $label cleared")
                GeckoResult.fromValue(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear gecko $label", e)
        }
    }

    override fun isHtmlFullscreenActive(): Boolean = htmlFullscreen

    override fun exitHtmlFullscreen() {
        if (!htmlFullscreen) return
        try {
            session.exitFullScreen()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to exit gecko fullscreen", e)
        }
    }

    override fun destroy() {
        pendingScripts.clear()
        clearPendingPortEvals()
        javascriptUriInFlight = false
        htmlFullscreen = false
        consoleCaptureEnabled = false
        WebViewFileChooserCoordinator.cancel()
        try {
            unregisterConsoleBridge()
            session.setSelectionActionDelegate(null)
            session.close()
            geckoView.releaseSession()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing gecko session", e)
        }
    }
}
