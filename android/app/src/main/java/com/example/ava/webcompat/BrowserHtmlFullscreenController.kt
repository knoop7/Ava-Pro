package com.example.ava.webcompat

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout

/**
 * Standard WebView HTML5 fullscreen ([WebChromeClient.onShowCustomView]) for overlay browsers.
 *
 * Required for `Element.requestFullscreen()` used by Lovelace cards (e.g. WebRTC camera `ui: true`).
 */
class BrowserHtmlFullscreenController(
    private val tag: String,
    private val getHostContainer: () -> FrameLayout?,
    private val setMainContentVisible: (visible: Boolean) -> Unit,
) {
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    fun isActive(): Boolean = fullscreenView != null

    fun show(view: View, callback: WebChromeClient.CustomViewCallback) {
        val container = getHostContainer()
        if (container == null) {
            callback.onCustomViewHidden()
            return
        }
        if (fullscreenView != null) {
            exit()
        }
        fullscreenView = view
        fullscreenCallback = callback
        setMainContentVisible(false)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun exit() {
        val view = fullscreenView ?: return
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (e: Exception) {
            Log.w(tag, "Failed to remove HTML fullscreen view", e)
        }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        setMainContentVisible(true)
    }
}
