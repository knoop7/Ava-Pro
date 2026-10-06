package com.example.ava

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.ui.AvaToast
import com.example.ava.services.WebViewService
import com.example.ava.webcompat.BrowserDarkModeResolver
import com.example.ava.webcompat.BrowserEngine

/**
 * Headless bridge activity for the gecko engine pack. This has no UI and is not visible
 * in the launcher. The lite Ava app starts this activity via Intent when the user has
 * selected GeckoView as the render engine.
 *
 * Intent actions:
 * - ACTION_SHOW_BROWSER: Show the floating browser at the given URL (extra "url")
 * - ACTION_HIDE_BROWSER: Hide the floating browser
 * - ACTION_DESTROY_BROWSER: Tear down the floating browser (service lifecycle only)
 * - ACTION_NAVIGATE: Navigate to a new URL
 */
class GeckoBrowserBridge : Activity() {

    companion object {
        private const val TAG = "GeckoBrowserBridge"
        const val ACTION_SHOW_BROWSER = "com.example.ava.gecko.action.SHOW_BROWSER"
        const val ACTION_HIDE_BROWSER = "com.example.ava.gecko.action.HIDE_BROWSER"
        const val ACTION_DESTROY_BROWSER = "com.example.ava.gecko.action.DESTROY_BROWSER"
        const val ACTION_NAVIGATE = "com.example.ava.gecko.action.NAVIGATE"
        const val ACTION_REFRESH_DARK_MODE = "com.example.ava.gecko.action.REFRESH_DARK_MODE"
        const val ACTION_RESTORE_FROM_SETTINGS = "com.example.ava.gecko.action.RESTORE_FROM_SETTINGS"
        const val ACTION_CLEAR_CACHE = "com.example.ava.gecko.action.CLEAR_CACHE"
        const val ACTION_FORCE_REFRESH = "com.example.ava.gecko.action.FORCE_REFRESH"
        const val EXTRA_URL = "url"
        private const val DUPLICATE_SHOW_WINDOW_MS = 500L
        @Volatile private var lastShowUrl: String? = null
        @Volatile private var lastShowAtMs = 0L
    }

    private var pendingAction: String? = null
    private var pendingUrl: String? = null
    private var pendingFollowDarkMode = true
    private var pendingDarkMode = false
    private var awaitingOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finishUnlessAwaitingOverlayPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        // Never drop hide/destroy while waiting for overlay permission — host must stay in sync.
        if (awaitingOverlayPermission &&
            action != ACTION_HIDE_BROWSER &&
            action != ACTION_DESTROY_BROWSER &&
            action != ACTION_REFRESH_DARK_MODE &&
            action != ACTION_RESTORE_FROM_SETTINGS &&
            action != ACTION_CLEAR_CACHE &&
            action != ACTION_FORCE_REFRESH
        ) {
            return
        }
        handleIntent(intent)
        finishUnlessAwaitingOverlayPermission()
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingOverlayPermission) return
        if (PlatformCapabilities.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission granted, showing browser for pending action")
            awaitingOverlayPermission = false
            dispatchPending()
            finish()
        } else {
            Log.d(TAG, "Overlay permission still missing after settings")
            awaitingOverlayPermission = false
            finish()
        }
    }

    private fun finishUnlessAwaitingOverlayPermission() {
        if (!awaitingOverlayPermission) {
            finish()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        try {
            syncDarkModeFromIntent(intent)
            when (intent.action) {
                ACTION_SHOW_BROWSER -> {
                    val url = intent.getStringExtra(EXTRA_URL) ?: return
                    Log.d(TAG, "Received SHOW_BROWSER: $url")
                    if (shouldSkipDuplicateShow(url)) {
                        Log.d(TAG, "Ignoring duplicate SHOW_BROWSER within ${DUPLICATE_SHOW_WINDOW_MS}ms")
                        return
                    }
                    if (!ensureOverlayPermission(ACTION_SHOW_BROWSER, url)) return
                    val token = intent.getLongExtra(BrowserEngine.EXTRA_RECEIPT_TOKEN, 0L)
                    WebViewService.show(applicationContext, url, token)
                }
                ACTION_HIDE_BROWSER -> {
                    Log.d(TAG, "Received HIDE_BROWSER")
                    clearShowDedup()
                    pendingAction = null
                    pendingUrl = null
                    awaitingOverlayPermission = false
                    WebViewService.hide(applicationContext)
                }
                ACTION_DESTROY_BROWSER -> {
                    Log.d(TAG, "Received DESTROY_BROWSER")
                    clearShowDedup()
                    pendingAction = null
                    pendingUrl = null
                    awaitingOverlayPermission = false
                    WebViewService.destroy(applicationContext)
                }
                ACTION_NAVIGATE -> {
                    val url = intent.getStringExtra(EXTRA_URL) ?: return
                    Log.d(TAG, "Received NAVIGATE: $url")
                    if (!ensureOverlayPermission(ACTION_NAVIGATE, url)) return
                    WebViewService.showOrRefresh(applicationContext, url)
                }
                ACTION_REFRESH_DARK_MODE -> {
                    Log.d(TAG, "Received REFRESH_DARK_MODE")
                    WebViewService.refreshDarkMode(applicationContext)
                }
                ACTION_RESTORE_FROM_SETTINGS -> {
                    Log.d(TAG, "Received RESTORE_FROM_SETTINGS")
                    clearShowDedup()
                    WebViewService.restoreIfPending(applicationContext)
                }
                ACTION_CLEAR_CACHE -> {
                    Log.d(TAG, "Received CLEAR_CACHE")
                    WebViewService.clearCache(applicationContext)
                }
                ACTION_FORCE_REFRESH -> {
                    Log.d(TAG, "Received FORCE_REFRESH")
                    WebViewService.forceRefresh(applicationContext)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle gecko bridge intent", e)
        }
    }

    private fun dispatchPending() {
        BrowserDarkModeResolver.writeHostStateOverride(
            this,
            pendingFollowDarkMode,
            pendingDarkMode
        )
        when (pendingAction) {
            ACTION_SHOW_BROWSER -> pendingUrl?.let { WebViewService.show(applicationContext, it) }
            ACTION_NAVIGATE -> pendingUrl?.let { WebViewService.showOrRefresh(applicationContext, it) }
        }
        pendingAction = null
        pendingUrl = null
    }

    /**
     * The engine pack is a separate app, so it needs its own "draw over other apps" permission to
     * render the floating browser. If missing, guide the user to grant it (one-time setup) instead
     * of silently failing. Returns true only when the overlay can be shown right now.
     *
     * Android 5.x grants the overlay at install time. [Settings.canDrawOverlays] is API 23 and
     * must not be called here — that crash-looped the gecko process on API 22.
     */
    private fun ensureOverlayPermission(action: String, url: String?): Boolean {
        if (PlatformCapabilities.canDrawOverlays(this)) return true
        pendingAction = action
        pendingUrl = url
        awaitingOverlayPermission = true
        try {
            AvaToast.show(this, getString(R.string.gecko_engine_overlay_permission_needed), durationMs = AvaToast.LONG_MS)
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request overlay permission", e)
            awaitingOverlayPermission = false
            pendingAction = null
            pendingUrl = null
        }
        return false
    }

    private fun syncDarkModeFromIntent(intent: Intent) {
        if (!intent.hasExtra(BrowserEngine.EXTRA_FOLLOW_DARK_MODE) &&
            !intent.hasExtra(BrowserEngine.EXTRA_DARK_MODE)
        ) {
            return
        }
        pendingFollowDarkMode = intent.getBooleanExtra(
            BrowserEngine.EXTRA_FOLLOW_DARK_MODE,
            pendingFollowDarkMode
        )
        pendingDarkMode = intent.getBooleanExtra(
            BrowserEngine.EXTRA_DARK_MODE,
            pendingDarkMode
        )
        BrowserDarkModeResolver.writeHostStateOverride(
            this,
            pendingFollowDarkMode,
            pendingDarkMode
        )
    }

    private fun shouldSkipDuplicateShow(url: String): Boolean {
        if (WebViewService.isBrowserHidden()) return false
        val now = System.currentTimeMillis()
        val duplicate = url == lastShowUrl && now - lastShowAtMs < DUPLICATE_SHOW_WINDOW_MS
        if (!duplicate) {
            lastShowUrl = url
            lastShowAtMs = now
        }
        return duplicate
    }

    private fun clearShowDedup() {
        lastShowUrl = null
        lastShowAtMs = 0L
    }
}
