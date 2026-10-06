package com.example.ava.services

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Bare dark-cover view helper for overlay hosts.
 * Hosts own settings, i18n, and behavior (idle fade vs static).
 */
class SmartAodMaskOverlay(context: Context) {

    val view: View = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun attachTo(host: ViewGroup) {
        if (view.parent === host) return
        (view.parent as? ViewGroup)?.removeView(view)
        host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun detach() {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /**
     * @param enabled feature switch
     * @param maskPercent 5–100; 100 = fully opaque
     * @param showing host is currently displayed
     */
    fun sync(enabled: Boolean, maskPercent: Int, showing: Boolean) {
        if (enabled && showing) {
            view.visibility = View.VISIBLE
            view.alpha = maskPercent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f
        } else {
            view.visibility = View.GONE
            view.alpha = 0f
        }
    }

    companion object {
        const val MIN_PERCENT = 5
        const val MAX_PERCENT = 100
    }
}
