package com.example.ava.ui.screens.home

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps [MinimalLauncherAppsCache] in sync the way real home-screen launchers (Launcher3, Nova,
 * etc.) do: subscribe to [LauncherApps.Callback] for precise per-package add/remove/update
 * events - a public, event-driven API purpose-built for launchers - instead of a manifest-style
 * PACKAGE_ADDED/REMOVED broadcast receiver or polling.
 *
 * Every callback applies an incremental update (single-package re-query or list filter) so an
 * uninstall is reflected right away without a full PackageManager re-scan, and without depending
 * on any particular screen being in the composition (start/stop is tied to the
 * `enableMinimalLauncher` setting from [com.example.ava.AvaApplication], not to a Composable).
 */
object MinimalLauncherAppsWatcher {
    private const val TAG = "MinimalLauncherWatcher"

    @Volatile
    private var started = false
    private var launcherApps: LauncherApps? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watcherScope = CoroutineScope(Dispatchers.IO)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            MinimalLauncherAppsCache.removePackage(packageName)
            appContext?.let { MinimalLauncherIconsStore.removePackage(it, packageName) }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            refreshPackage(packageName)
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            refreshPackage(packageName)
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean
        ) {
            packageNames.forEach(::refreshPackage)
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean
        ) {
            packageNames.forEach { pkg ->
                MinimalLauncherAppsCache.removePackage(pkg)
                appContext?.let { MinimalLauncherIconsStore.removePackage(it, pkg) }
            }
        }

        private fun refreshPackage(packageName: String) {
            val ctx = appContext ?: return
            watcherScope.launch {
                MinimalLauncherAppsCache.upsertPackage(ctx, packageName, ctx.packageName)
            }
        }
    }

    fun start(context: Context) {
        if (started) return
        val appCtx = context.applicationContext
        val la = appCtx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (la == null) {
            Log.w(TAG, "LauncherApps service unavailable; skipping live icon updates")
            return
        }
        runCatching {
            la.registerCallback(callback, mainHandler)
        }.onSuccess {
            launcherApps = la
            appContext = appCtx
            started = true
        }.onFailure {
            Log.w(TAG, "Failed to register LauncherApps callback", it)
        }
    }

    fun stop() {
        if (!started) return
        runCatching { launcherApps?.unregisterCallback(callback) }
            .onFailure { Log.w(TAG, "Failed to unregister LauncherApps callback", it) }
        started = false
        launcherApps = null
        appContext = null
    }
}
