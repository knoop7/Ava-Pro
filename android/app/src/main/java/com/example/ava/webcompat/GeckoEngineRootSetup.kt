package com.example.ava.webcompat

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils

/**
 * The separate gecko engine pack needs SYSTEM_ALERT_WINDOW. When Shizuku/root is available
 * (same privilege plane as fleet scrcpy), the lite host grants it via shell so users do not
 * have to re-run `adb shell appops set …` after every engine reinstall.
 *
 * Android 7 kiosks also get a vendor kill-list whitelist; that step stays N/N_MR1-only.
 */
object GeckoEngineRootSetup {

    private const val TAG = "GeckoEngineRootSetup"
    private const val PREFS = "gecko_engine_root_setup"
    private const val KEY_APPLIED_VERSION = "applied_gecko_version_code"
    private const val KEY_LAST_ATTEMPT_MS = "last_attempt_elapsed_ms"
    private const val KEY_LAST_ATTEMPT_VERSION = "last_attempt_version_code"
    private const val KEY_KILL_LIST_TRUSTED_VERSION = "kill_list_trusted_version_code"
    /** Avoid hammering su/shizuku when AppOps lag or Settings.System is unreadable. */
    private const val RETRY_BACKOFF_MS = 5L * 60L * 1000L

    @Volatile
    private var inProgress = false

    /** Call from the lite host when the gecko pack may have been installed or is about to show. */
    fun maybeApply(context: Context) {
        if (EngineCapabilities.GECKO_BUNDLED) return
        val appContext = context.applicationContext
        if (!BrowserEngine.isGeckoEnginePackInstalled(appContext)) {
            clearAppliedFlag(appContext)
            return
        }
        if (!hasPrivilegedShell()) return

        val geckoVersion = geckoVersionCode(appContext) ?: return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_APPLIED_VERSION, -1) == geckoVersion && isSetupComplete(appContext)) {
            return
        }
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L)
        val lastAttemptVersion = prefs.getInt(KEY_LAST_ATTEMPT_VERSION, -1)
        val now = SystemClock.elapsedRealtime()
        if (lastAttempt > 0L &&
            now - lastAttempt < RETRY_BACKOFF_MS &&
            lastAttemptVersion == geckoVersion
        ) {
            return
        }
        if (inProgress) return
        inProgress = true
        Thread {
            try {
                applyViaShell(appContext, geckoVersion)
            } finally {
                inProgress = false
            }
        }.start()
    }

    /**
     * Force-grant gecko SYSTEM_ALERT_WINDOW before the browser switch starts the pack overlay.
     * Always re-runs `appops set … allow` when Shizuku/root is available (same plane as scrcpy)
     * so a reinstall/OEM reset cannot leave the switch unable to show the floating window.
     */
    fun ensureOverlayForLaunch(context: Context): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) return true
        val appContext = context.applicationContext
        if (!BrowserEngine.isGeckoEnginePackInstalled(appContext)) return false

        val geckoPkg = BrowserEngine.GECKO_ENGINE_PACKAGE
        if (!hasPrivilegedShell()) {
            return isOverlayAllowed(appContext, geckoPkg)
        }

        // Force every launch path: MODE_DEFAULT after update is not "allowed".
        val ok = runShell("appops set $geckoPkg SYSTEM_ALERT_WINDOW allow")
        val allowed = isOverlayAllowed(appContext, geckoPkg)
        if (ok && allowed) {
            Log.i(TAG, "Forced SYSTEM_ALERT_WINDOW allow for $geckoPkg via ${shellBackend()}")
            maybeApply(appContext)
        } else {
            Log.w(
                TAG,
                "Forced SYSTEM_ALERT_WINDOW grant incomplete for $geckoPkg " +
                    "(shell=$ok, allowed=$allowed, via=${shellBackend()})"
            )
        }
        return allowed
    }

    fun clearAppliedFlag(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_APPLIED_VERSION)
            .remove(KEY_LAST_ATTEMPT_MS)
            .remove(KEY_LAST_ATTEMPT_VERSION)
            .remove(KEY_KILL_LIST_TRUSTED_VERSION)
            .apply()
    }

    private fun hasPrivilegedShell(): Boolean =
        ShizukuUtils.isShizukuPermissionGranted() || RootUtils.isRootAvailable()

    private fun shellBackend(): String = when {
        ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
        RootUtils.isRootAvailable() -> "root"
        else -> "none"
    }

    private fun isAndroid7(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1

    private fun geckoVersionCode(context: Context): Int? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(BrowserEngine.GECKO_ENGINE_PACKAGE, 0).versionCode
    } catch (e: Exception) {
        Log.w(TAG, "Could not read gecko pack version", e)
        null
    }

    private fun isSetupComplete(context: Context): Boolean {
        if (!isOverlayAllowed(context, BrowserEngine.GECKO_ENGINE_PACKAGE)) return false
        if (!isAndroid7()) return true
        // Settings.System.getString is often unreadable from the app UID even after a successful
        // root/Shizuku `settings put`. Trust a successful shell write recorded per version.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = geckoVersionCode(context) ?: return false
        if (prefs.getInt(KEY_KILL_LIST_TRUSTED_VERSION, -1) == version) return true
        return isKillListConfigured(context)
    }

    private fun isOverlayAllowed(context: Context, packageName: String): Boolean = try {
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                uid,
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                uid,
                packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun isKillListConfigured(context: Context): Boolean = try {
        val list = Settings.System.getString(
            context.contentResolver,
            "kill_background_services_list"
        )
        list?.contains(BrowserEngine.HOST_PACKAGE) == true &&
            list.contains(BrowserEngine.GECKO_ENGINE_PACKAGE)
    } catch (e: Exception) {
        false
    }

    private fun runShell(command: String): Boolean {
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            return ShizukuUtils.executeCommand(command).first == 0
        }
        if (RootUtils.isRootAvailable()) {
            return runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
            }.getOrDefault(false)
        }
        return false
    }

    private fun applyViaShell(context: Context, geckoVersion: Int) {
        val geckoPkg = BrowserEngine.GECKO_ENGINE_PACKAGE
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_MS, SystemClock.elapsedRealtime())
            .putInt(KEY_LAST_ATTEMPT_VERSION, geckoVersion)
            .apply()

        val overlayOk = runShell("appops set $geckoPkg SYSTEM_ALERT_WINDOW allow")
        val killOk = if (isAndroid7()) {
            val killListValue = "${BrowserEngine.HOST_PACKAGE},$geckoPkg"
            val wrote = runShell("settings put system kill_background_services_list $killListValue")
            if (wrote) {
                prefs.edit().putInt(KEY_KILL_LIST_TRUSTED_VERSION, geckoVersion).apply()
            }
            wrote
        } else {
            true
        }

        val overlayAllowed = isOverlayAllowed(context, geckoPkg)
        if (overlayOk && killOk && overlayAllowed) {
            prefs.edit()
                .putInt(KEY_APPLIED_VERSION, geckoVersion)
                .apply()
            Log.i(
                TAG,
                "Gecko pack shell setup applied for $geckoPkg " +
                    "(overlay=$overlayOk, killList=$killOk, via=${shellBackend()})"
            )
        } else {
            // Shell may have succeeded while AppOps is still MODE_DEFAULT briefly on some OEMs.
            // Backoff via KEY_LAST_ATTEMPT_MS so callers don't spam every settings poll.
            Log.w(
                TAG,
                "Gecko pack shell setup incomplete " +
                    "(overlay=$overlayOk, killList=$killOk, allowed=$overlayAllowed)"
            )
        }
    }
}
