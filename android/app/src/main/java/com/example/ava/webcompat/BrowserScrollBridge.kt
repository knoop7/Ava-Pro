package com.example.ava.webcompat

import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JS ↔ native scroll signals for HA (shadow-DOM scrollers).
 *
 * WebView.[canScrollVertically] is almost always false for Lovelace because the
 * page scrolls inside open shadow roots — SwipeRefresh then thinks the child is
 * forever at the top and steals mid-page drags. JS reports the real position here.
 */
class BrowserScrollBridge {
    private val canScrollUp = AtomicBoolean(false)

    /** True when HA content is scrolled down (child can scroll up). */
    fun haCanScrollUp(): Boolean = canScrollUp.get()

    @JavascriptInterface
    fun setCanScrollUp(can: Boolean) {
        canScrollUp.set(can)
    }

    companion object {
        const val JS_BRIDGE_NAME = "AvaScrollBridge"
    }
}
