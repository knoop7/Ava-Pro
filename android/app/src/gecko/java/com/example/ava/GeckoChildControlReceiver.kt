package com.example.ava

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ava.services.WebViewService
import com.example.ava.webcompat.BrowserEngine

/**
 * Child-pack door for ADB / Fleet shell / host broadcast.
 *
 * Host [Context.startForegroundService] into this package is dropped on many
 * OEMs after the child has gone idle. An explicit broadcast wakes the process;
 * this receiver then starts [WebViewService] in-process.
 */
class GeckoChildControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val app = context.applicationContext
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val token = intent.getLongExtra(BrowserEngine.EXTRA_RECEIPT_TOKEN, 0L)
        Log.i(TAG, "Child punch action=${intent.action} url=$url token=$token")
        when (intent.action) {
            GeckoBrowserBridge.ACTION_SHOW_BROWSER -> {
                if (url.isBlank()) {
                    WebViewService.restoreIfPending(app)
                } else {
                    WebViewService.show(app, url, token)
                }
            }
            GeckoBrowserBridge.ACTION_NAVIGATE -> {
                if (url.isNotBlank()) WebViewService.showOrRefresh(app, url)
            }
            GeckoBrowserBridge.ACTION_HIDE_BROWSER -> WebViewService.hide(app)
            GeckoBrowserBridge.ACTION_DESTROY_BROWSER -> WebViewService.destroy(app)
            GeckoBrowserBridge.ACTION_REFRESH_DARK_MODE -> WebViewService.refreshDarkMode(app)
            GeckoBrowserBridge.ACTION_RESTORE_FROM_SETTINGS -> WebViewService.restoreIfPending(app)
            GeckoBrowserBridge.ACTION_CLEAR_CACHE -> WebViewService.clearCache(app)
            GeckoBrowserBridge.ACTION_FORCE_REFRESH -> WebViewService.forceRefresh(app)
        }
    }

    companion object {
        private const val TAG = "GeckoChildPunch"
        private const val EXTRA_URL = "url"
    }
}
