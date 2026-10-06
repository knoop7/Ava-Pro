package com.example.ava.services

/**
 * Generation guard for the browser overlay's asynchronous build.
 *
 * [WebViewService.showWebView] assembles the container in a coroutine that writes straight into
 * the service fields (`webView`, `containerView`, `windowParams`). A teardown — render-process
 * crash recovery, `onTaskRemoved`, a settings rebuild — can land on any suspension point in
 * between, and `cleanupWebView` clears `isCreating` / `hideRequestedDuringCreate`, so without a
 * generation token the abandoned build keeps going and attaches a window around WebViews that were
 * already destroyed (or, worse, around the ones a newer build just created).
 *
 * Single-threaded by contract: every caller runs on the main looper.
 */
internal class BrowserOverlayGeneration {
    private var current = 0

    /** Claims ownership for a build that starts now. */
    fun begin(): Int {
        current++
        return current
    }

    /** Abandons whatever is being built; every outstanding token goes stale. */
    fun invalidate() {
        current++
    }

    /** True when [token] no longer owns the overlay and must stop touching service state. */
    fun isStale(token: Int): Boolean = token != current
}
