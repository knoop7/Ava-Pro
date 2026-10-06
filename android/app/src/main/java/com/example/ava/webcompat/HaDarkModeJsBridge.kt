package com.example.ava.webcompat

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/** WebView bridge for Home Assistant theme change notifications from injected JS. */
class HaDarkModeJsBridge(
    // Must NOT share the member function's name: an unqualified call inside the
    // posted lambda resolves to the member fun (member beats property+invoke),
    // which re-posts itself forever and pins the main thread at 100% CPU.
    private val callback: (Boolean) -> Unit
) {
    @JavascriptInterface
    fun onHaDarkModeChanged(isDark: Boolean) {
        Handler(Looper.getMainLooper()).post {
            callback(isDark)
        }
    }
}
