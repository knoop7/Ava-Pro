package com.example.ava.ui.screens.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide cache for minimal-launcher app list so rotation/navigation does not re-query
 * PackageManager on the main thread.
 *
 * Kept in sync incrementally by [MinimalLauncherAppsWatcher] (LauncherApps.Callback) - the same
 * mechanism real home-screen launchers use - so an uninstalled app disappears immediately instead
 * of waiting for the next full reload/app restart. [load] remains the only place that performs a
 * full PackageManager scan; every other mutation here is a targeted, cheap update.
 */
object MinimalLauncherAppsCache {
    @Volatile
    private var cachedExcludePackage: String? = null

    private val _apps = MutableStateFlow<List<MinimalLauncherApp>>(emptyList())
    val appsFlow: StateFlow<List<MinimalLauncherApp>> = _apps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val loadMutex = Mutex()

    fun peek(excludePackage: String): List<MinimalLauncherApp>? =
        _apps.value.takeIf { cachedExcludePackage == excludePackage && it.isNotEmpty() }

    /**
     * Mark cache dirty without clearing the visible list — avoids All Apps spinning forever
     * while a forced [load] refreshes icons after an icon-pack change.
     */
    fun markStale() {
        cachedExcludePackage = null
    }

    /** Clears list + stale mark. Prefer [markStale] + force [load] for pack switches. */
    fun invalidate() {
        cachedExcludePackage = null
        _apps.value = emptyList()
    }

    /**
     * @param force when true, always re-scan PackageManager (e.g. after icon pack apply).
     */
    suspend fun load(
        context: Context,
        excludePackage: String,
        force: Boolean = false,
    ): List<MinimalLauncherApp> {
        if (!force) {
            peek(excludePackage)?.let { return it }
        }
        _isLoading.value = true
        return try {
            loadMutex.withLock {
                if (!force) {
                    peek(excludePackage)?.let { return it }
                }
                val apps = withContext(Dispatchers.IO) {
                    loadMinimalLauncherApps(context.applicationContext, excludePackage)
                }
                cachedExcludePackage = excludePackage
                _apps.value = apps
                apps
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Driven by [android.content.pm.LauncherApps.Callback.onPackageRemoved] /
     * onPackagesUnavailable. Pure in-memory filter, no PackageManager call.
     */
    fun removePackage(packageName: String) {
        val current = _apps.value
        val filtered = current.filterNot { it.packageName == packageName }
        if (filtered.size != current.size) {
            _apps.value = filtered
        }
    }

    /**
     * Driven by [android.content.pm.LauncherApps.Callback.onPackageAdded] / onPackageChanged /
     * onPackagesAvailable. Re-queries only [packageName] and splices the result back in.
     * No-op if the cache hasn't been primed yet - the next [load] will pick everything up.
     */
    suspend fun upsertPackage(context: Context, packageName: String, excludePackage: String) {
        if (cachedExcludePackage != excludePackage) return
        val freshEntries = withContext(Dispatchers.IO) {
            loadMinimalLauncherAppsForPackage(context.applicationContext, packageName, excludePackage)
        }
        val current = _apps.value
        _apps.value = sortMinimalLauncherApps(
            current.filterNot { it.packageName == packageName } + freshEntries
        )
    }

    /**
     * Lightweight ON_RESUME safety net (see [MinimalLauncherContent]) for the rare case a
     * LauncherApps callback was missed, e.g. the process was killed between the uninstall and
     * the next foreground. Only checks that already-cached packages still resolve
     * ([android.content.pm.PackageManager.getLaunchIntentForPackage] - cheap, no icon/label
     * loading) and trims stale ones. Never performs a full PackageManager re-scan.
     */
    suspend fun verifyInstalled(context: Context) {
        val current = _apps.value
        if (current.isEmpty()) return
        val pm = context.applicationContext.packageManager
        val stalePackages = withContext(Dispatchers.IO) {
            current.asSequence()
                .map { it.packageName }
                .distinct()
                .filter { pkg -> runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() == null }
                .toSet()
        }
        if (stalePackages.isEmpty()) return
        val filtered = _apps.value.filterNot { it.packageName in stalePackages }
        if (filtered.size != _apps.value.size) {
            _apps.value = filtered
        }
    }
}
