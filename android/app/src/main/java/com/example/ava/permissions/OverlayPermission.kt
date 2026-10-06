package com.example.ava.permissions

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.ava.mods.ModDeviceSupport
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.utils.EchoShowSupport
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils

/**
 * `SYSTEM_ALERT_WINDOW` is mandatory for Ava (screensaver, browser overlay, floating UI,
 * crash self-heal). This object owns the one hard case: ROMs where the system switch
 * cannot be toggled by the user.
 *
 * Android 10+ Settings hides "Display over other apps" whenever
 * `ro.config.low_ram=true` (AOSP `Utils.isSystemAlertWindowEnabled`). The LineageOS 18.1
 * builds for Echo Show 5/8 (checkers / cronos / crown) ship that flag, so the page opens
 * but the toggle is disabled. The app op itself still works — `appops set <pkg>
 * SYSTEM_ALERT_WINDOW allow` via root, Shizuku, or ADB is the only way in (issue #208).
 * `pm grant` does not work for this permission.
 */
object OverlayPermission {
    private const val TAG = "OverlayPermission"
    const val APP_OP = "SYSTEM_ALERT_WINDOW"

    enum class RequestPath {
        ALREADY_GRANTED,
        PRIVILEGED_GRANT,
        OPEN_SETTINGS,
        NEEDS_ADB,
    }

    fun isGranted(context: Context): Boolean =
        PlatformCapabilities.canDrawOverlays(context)

    /**
     * True when the system "Display over other apps" switch is disabled for the user,
     * so sending them to [settingsIntent] cannot succeed.
     */
    fun isSystemToggleBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (EchoShowSupport.isEchoShowDevice()) return true
        return isLowRamToggleBlocked(
            sdkInt = Build.VERSION.SDK_INT,
            lowRam = runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.isLowRamDevice == true
            }.getOrDefault(false),
        )
    }

    /** AOSP disables the switch on low-RAM devices from Android 10 (API 29). */
    internal fun isLowRamToggleBlocked(sdkInt: Int, lowRam: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.Q && lowRam

    internal fun requestPath(
        granted: Boolean,
        privilegedOk: Boolean,
        toggleBlocked: Boolean,
    ): RequestPath = when {
        granted -> RequestPath.ALREADY_GRANTED
        privilegedOk -> RequestPath.PRIVILEGED_GRANT
        toggleBlocked -> RequestPath.NEEDS_ADB
        else -> RequestPath.OPEN_SETTINGS
    }

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * `cmd appops` is what worked on the reporter's crown (issue #208) and is the binary
     * the `appops` shell script wraps; some minimal builds ship only the former. `cmd`
     * exists from Android 7 — older builds fall back to the script.
     */
    fun shellGrantCommand(packageName: String, sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (sdkInt >= Build.VERSION_CODES.N) {
            "cmd appops set $packageName $APP_OP allow"
        } else {
            "appops set $packageName $APP_OP allow"
        }

    /** Legacy wrapper — used only as a fallback when [shellGrantCommand] fails. */
    internal fun legacyShellGrantCommand(packageName: String): String =
        "appops set $packageName $APP_OP allow"

    fun adbGrantCommand(packageName: String, sdkInt: Int = Build.VERSION.SDK_INT): String =
        "adb shell ${shellGrantCommand(packageName, sdkInt)}"

    /**
     * Blocking; call off the main thread. Order: device-support mod hook, Echo Show
     * root hook, then any Shizuku / root shell. Returns the live grant state.
     */
    fun tryPrivilegedGrant(context: Context): Boolean {
        val appContext = context.applicationContext
        if (isGranted(appContext)) return true
        if (ModDeviceSupport.grantOverlayPermissionIfNeeded(appContext) && isGranted(appContext)) {
            Log.i(TAG, "Overlay granted via device support mod")
            return true
        }
        if (EchoShowSupport.grantOverlayPermissionIfNeeded(appContext) && isGranted(appContext)) {
            Log.i(TAG, "Overlay granted via Echo Show root hook")
            return true
        }
        if (grantViaPrivilegedShell(appContext) && isGranted(appContext)) {
            Log.i(TAG, "Overlay granted via privileged appops")
            return true
        }
        return isGranted(appContext)
    }

    /**
     * Blocking; call off the main thread. Silent grant first, then either the system page
     * or — when the switch is disabled by the ROM — tells the caller to show the ADB command.
     */
    fun requestBlocking(context: Context): RequestPath {
        if (isGranted(context)) return RequestPath.ALREADY_GRANTED
        val privilegedOk = tryPrivilegedGrant(context)
        val path = requestPath(
            granted = isGranted(context),
            privilegedOk = privilegedOk,
            toggleBlocked = isSystemToggleBlocked(context),
        )
        if (path == RequestPath.OPEN_SETTINGS) openSettings(context)
        return path
    }

    fun openSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val intent = settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    internal fun grantViaPrivilegedShell(context: Context): Boolean {
        val pkg = context.packageName
        val commands = listOf(shellGrantCommand(pkg), legacyShellGrantCommand(pkg)).distinct()
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            for (command in commands) {
                if (ShizukuUtils.executeCommand(command).first == 0 && isGranted(context)) return true
            }
        }
        if (!RootUtils.isRootBinaryPresent()) return false
        if (RootUtils.isRootAvailable()) {
            for (command in commands) {
                val ok = runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
                }.getOrDefault(false)
                if (ok && isGranted(context)) return true
            }
        }
        return runCatching {
            val process = Runtime.getRuntime().exec("su")
            java.io.DataOutputStream(process.outputStream).use { os ->
                commands.forEach { os.writeBytes("$it\n") }
                os.writeBytes("exit\n")
            }
            process.waitFor() == 0
        }.getOrDefault(false) && isGranted(context)
    }
}
