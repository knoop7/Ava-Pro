package com.example.ava

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.ava.crash.AvaIncidentLog
import com.example.ava.fleet.FleetManager
import com.example.ava.notifications.FullscreenOverlayEscape
import com.example.ava.notifications.NotificationScenes
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.SettingsQuarantine
import com.example.ava.settings.localScenesSettingsStore
import com.example.ava.ui.screens.home.HomeLayoutSettingsMirror
import com.example.ava.mods.ModManager
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.screens.home.MinimalLauncherAppsCache
import com.example.ava.ui.screens.home.MinimalLauncherAppsWatcher
import com.example.ava.ui.screens.home.syncHideHomeChromePrefFromStores
import com.example.ava.utils.DirectBootHelper
import com.example.ava.utils.ScreenBlankOverlay
import com.example.ava.webcompat.HostSidebarSettingsPublisher
import com.example.ava.webcompat.WebViewProductionFeatures
import com.example.ava.utils.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AvaApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LocaleUtils.applyLocale(this)
        FullscreenOverlayEscape.attach(this)
        AvaIncidentLog.init(this)
        setupGlobalExceptionHandler()
        // Before any WebView starts: merge Chromium production features into
        // webview-command-line when the device allows it (userdebug / writable tmp).
        runCatching {
            WebViewProductionFeatures.applyDesired(
                BrowserSettingsStore.getCachedWvProdMemoryFeatures(this),
                BrowserSettingsStore.getCachedWvProdFrameThrottleFeatures(this),
            )
        }

        appScope.launch {
            NotificationScenes.loadFromAssets(this@AvaApplication)
            runCatching {
                val entries = com.example.ava.settings.LocalScenesStore(
                    this@AvaApplication.localScenesSettingsStore
                ).list()
                NotificationScenes.setLocalScenes(entries.map { it.toNotificationScene() })
            }
        }
        DirectBootHelper.runWhenUserUnlocked(this, appScope) {
            // A blank screen that outlived the process left the panel pinned at its dimmest, and
            // only the displaced value on disk can say what to put back. Before anything else
            // touches brightness.
            ScreenBlankOverlay.restoreSystemBrightnessAfterRestart(this@AvaApplication)
            syncHideHomeChromePrefFromStores(this@AvaApplication)
            BrowserSettingsStore.syncEngineMirrorFromDataStore(this@AvaApplication)
            appScope.launch {
                val settings = BrowserSettingsStore(this@AvaApplication).get()
                WebViewProductionFeatures.applyDesired(
                    settings.wvProdMemoryFeaturesEnabled,
                    settings.wvProdFrameThrottleFeaturesEnabled,
                )
            }
            HomeLayoutSettingsMirror.startWatcher(this@AvaApplication, appScope)
            // Reactive (not one-shot) so toggling the setting at runtime immediately starts/stops
            // the LauncherApps.Callback watcher that keeps desktop-icon-mode icons in sync with
            // installs/uninstalls - see MinimalLauncherAppsWatcher for why this is preferred over
            // a broadcast receiver scoped to a single screen's lifecycle.
            appScope.launch {
                this@AvaApplication.playerSettingsStore.data
                    .map { it.enableCrashSelfHeal }
                    .distinctUntilChanged()
                    .collectLatest { enabled ->
                        com.example.ava.crash.CrashSelfHeal.applyEnabled(
                            this@AvaApplication,
                            enabled,
                        )
                    }
            }
            appScope.launch {
                this@AvaApplication.playerSettingsStore.data
                    .map { it.enableMinimalLauncher }
                    .distinctUntilChanged()
                    .collectLatest { enabled ->
                        if (enabled) {
                            MinimalLauncherAppsCache.load(this@AvaApplication, packageName)
                            MinimalLauncherAppsWatcher.start(this@AvaApplication)
                        } else {
                            MinimalLauncherAppsWatcher.stop()
                            MinimalLauncherAppsCache.invalidate()
                        }
                    }
            }
            HostSidebarSettingsPublisher.start(this@AvaApplication)
            ModManager.getInstance(this@AvaApplication).ensureRegistryLoaded()
            val experimentalSettingsStore = ExperimentalSettingsStore(this@AvaApplication)
            appScope.launch {
                experimentalSettingsStore.getFlow()
                    .map { it.forceOrientationEnabled to it.forceOrientationMode }
                    .distinctUntilChanged()
                    .collect { (enabled, mode) ->
                        com.example.ava.services.OverlayOrientation.syncForceSettings(enabled, mode)
                    }
            }
            appScope.launch {
                experimentalSettingsStore.getFlow()
                    .map { it.deviceIncidentLogEnabled to it.mainThreadStallWatchdogEnabled }
                    .distinctUntilChanged()
                    .collect { (export, watchdog) ->
                        com.example.ava.crash.MainThreadStallWatchdog.applyFlags(
                            this@AvaApplication,
                            export,
                            watchdog,
                        )
                    }
            }
            appScope.launch {
                experimentalSettingsStore.getFlow()
                    .map { it.clusterManagementEnabled to it.webConsoleEnabled }
                    .distinctUntilChanged()
                    // Use collect (not collectLatest): cancelling mid start/stop can leave :8888 stuck.
                    // FleetManager.apply coalesces via desired* + mutex.
                    .collect { (agent, webConsole) ->
                        FleetManager.apply(this@AvaApplication, agent, webConsole)
                    }
            }
        }
    }
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtils.applyLocale(base))
        // Before any DataStore read in any process: a corrupt settings file must
        // be quarantined, not silently replaced with defaults.
        SettingsQuarantine.init(this)
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handleUncaughtException(throwable: Throwable) {
        // Internal store first: it is the one the Logs screen and the fleet API
        // read, and the cheapest thing to attempt on a dying (possibly
        // out-of-memory) process.
        runCatching {
            AvaIncidentLog.record(
                this,
                kind = AvaIncidentLog.KIND_CRASH_JAVA,
                reason = throwable.javaClass.name,
                detail = Log.getStackTraceString(throwable),
            )
        }
        val dir = getExternalFilesDir(null) ?: return
        val logFile = File(dir, "ava_crash_log.txt")
        try {
            // Unattended panels run for months; the report file used to grow
            // without a bound and nothing ever read it back.
            if (logFile.length() > MAX_CRASH_REPORT_BYTES) {
                FileWriter(logFile, false).use { it.write("") }
            }
            FileWriter(logFile, true).use { writer ->
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                writer.append("\n\n--- CRASH REPORT: ${sdf.format(Date())} ---\n")
                writer.append("Error: ${throwable.message}\n")
                writer.append("Stack Trace:\n")
                writer.append(Log.getStackTraceString(throwable))
                writer.append("\n--- END REPORT ---\n")
            }
        } catch (e: Throwable) {
            
        }
    }

    private companion object {
        const val MAX_CRASH_REPORT_BYTES = 256L * 1024L
    }
}
