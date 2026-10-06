package com.example.ava.webcompat

import android.view.View

/**
 * Engine-agnostic rendering surface. The system-WebView path uses WebView directly; the gecko
 * flavor provides a GeckoView-backed implementation via [GeckoEngineFactory].
 */
interface EngineSurface {
    /** The actual Android view to attach into the overlay container. */
    val view: View

    fun loadUrl(url: String)
    fun reload()
    fun canGoBack(): Boolean
    fun goBack()
    fun canGoForward(): Boolean = false
    fun goForward() {}
    fun evaluateJavascript(script: String)

    /** Evaluate JavaScript and return a displayable result when the engine supports it. */
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        evaluateJavascript(script)
        onResult(null)
    }

    /**
     * Idempotent page-world scripts run at document start on every navigation
     * (WebView parity for addDocumentStartJavaScript). Engines wire this through
     * their own channel — Gecko delivers via the console-bridge content script.
     */
    fun setDocumentStartScripts(scripts: List<String>) {}

    fun destroy()

    /** Invoked when the displayed page URL changes. */
    var onPageUrlChanged: ((String) -> Unit)?

    /** Page console output emitted by engines that expose a content bridge. */
    var onConsoleMessage: ((level: String, message: String) -> Unit)?
        get() = null
        set(value) {}

    /** Enable console forwarding only while its UI is in use. */
    fun setConsoleCaptureEnabled(enabled: Boolean) {}

    /** Enable/disable touch events on the surface. Default implementation does nothing. */
    fun setTouchEnabled(enabled: Boolean) {}

    /** True when the page can scroll further up (used by pull-to-refresh in the overlay). */
    fun canScrollVerticallyUp(): Boolean = false

    /** Invoked when the user scrolls the page (screensaver idle reset). */
    var onScrollActivity: (() -> Unit)?
        get() = null
        set(value) {}

    /** 
     * Set the initial zoom scale (0 = auto, 100 = 100%, etc). 
     * Not all engines support this (e.g., GeckoView has limited zoom control).
     */
    fun setInitialScale(scale: Int) {}

    /**
     * Set the text zoom percentage (100 = normal).
     * Not all engines support this.
     */
    fun setTextZoom(percent: Int) {}

    /**
     * Set the user agent mode.
     * 0 = default, 1 = desktop Chrome, 2 = macOS Safari, 3 = iOS Safari
     */
    fun setUserAgentMode(mode: Int) {}

    /**
     * Enable/disable gesture navigation (swipe from left edge to go back).
     */
    fun setGestureNavigationEnabled(enabled: Boolean) {}

    /**
     * Set dark mode for the browser.
     * When enabled, forces dark theme on web content.
     */
    fun setDarkMode(enabled: Boolean) {}

    /** Callback for gesture navigation back action */
    var onGestureBack: (() -> Unit)?
        get() = null
        set(value) {}

    /** Home Assistant profile theme changed inside the page (HA → Ava sync). */
    var onHaDarkModeChanged: ((Boolean) -> Unit)?
        get() = null
        set(value) {}

    /**
     * Host hook before GeckoView shows the soft keyboard. The overlay window must drop
     * [android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] here. System WebView
     * claims IME only after an edit-text hit test, not on every touch-down.
     */
    var onPrepareTextInput: (() -> Unit)?
        get() = null
        set(value) {}

    /** Gecko content process crashed; host should tear down and optionally rebuild. */
    var onContentProcessCrash: (() -> Unit)?
        get() = null
        set(value) {}

    /**
     * Top-level page load finished. [success] false means the navigation failed
     * (unreachable host, etc.). Default no-op so system-WebView callers stay unchanged.
     */
    var onPageLoadFinished: ((success: Boolean, url: String?) -> Unit)?
        get() = null
        set(value) {}

    /** Pause/resume compositing when the host overlay is hidden or screensaver pauses the browser. */
    fun setHostPaused(paused: Boolean) {}

    /** Respond to system memory pressure (Gecko path only). */
    fun onTrimMemory(level: Int) {}

    /** Clear all browsing data (cache, cookies, storage). */
    fun clearData() {}

    /** HTTP / image caches only — keep cookies and DOM storage. */
    fun clearCacheOnly() {}

    /** Cookies, DOM storage, and auth sessions — keep HTTP cache when possible. */
    fun clearCookiesAndSiteData() {
        clearData()
    }

    /** True while the page holds engine-level HTML fullscreen (Element.requestFullscreen). */
    fun isHtmlFullscreenActive(): Boolean = false

    /** Ask the engine to leave HTML fullscreen (back key handling). */
    fun exitHtmlFullscreen() {}

    companion object {
        /** Features that may not be supported by all engines. */
        object Capabilities {
            const val INITIAL_SCALE = "initial_scale"
            const val TEXT_ZOOM = "text_zoom"
            const val USER_AGENT = "user_agent"
            const val USER_SCRIPTS = "user_scripts"
            const val GESTURE_NAVIGATION = "gesture_navigation"
        }

        /**
         * Spoofed user agent strings.
         *
         * Sites like Home Assistant gate their modern JS bundle on UA age ("browser
         * released in the last 2 years") with only a feature-detect fallback, so a
         * pinned UA that ages out silently downgrades every pane to the legacy/ES5
         * build. [DESKTOP_CHROME] is therefore only a fallback — the live desktop UA
         * is derived from the installed engine so the Chrome/<major> token never lies
         * (see WebViewService.desktopChromeUserAgent). The Safari strings cannot be
         * derived from a Chromium engine and need a periodic version bump.
         * Real Safari freezes the macOS token at 10_15_7; anything else flags the UA
         * as synthetic to fingerprinting sites.
         */
        object UserAgents {
            const val DESKTOP_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
            const val MACOS_SAFARI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15"
            const val IOS_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
        }
    }

    /** Returns true if the engine supports the given capability. */
    fun supportsCapability(capability: String): Boolean = false
}
