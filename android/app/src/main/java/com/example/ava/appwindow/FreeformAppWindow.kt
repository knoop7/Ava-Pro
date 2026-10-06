package com.example.ava.appwindow

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils

/**
 * Small-window launcher for **Android 7.0–9 (API 24–28)**, where scrcpy's
 * `new_display` virtual display is unavailable (that path,
 * [com.example.ava.services.AppWindowService], runs on API 29+ and is left
 * untouched). Instead of mirroring, the target app is launched directly into
 * the system's native **freeform** windowing mode — the same technique the
 * open-source Taskbar app proved out on these versions, ported to reuse Ava's
 * existing Shizuku/root shell and [AppWindowGeometryStore].
 *
 * Second role: the **step-down engine whenever the scrcpy mirror can't come
 * up** on Android 10+. Two ways in: [ShellTrustedDisplay] pre-flags Android
 * 13+ builds whose 2025 security patch removed the shell's virtual-display
 * permission (so the mirror is skipped entirely), and
 * [com.example.ava.services.AppWindowService] calls in after a mirror that
 * never rendered a frame (e.g. SELinux denies the app the shell socket, as on
 * many enforcing devices). Either way these callers invoke [launch] directly,
 * bypassing [isSupported] (which only picks the *primary* engine by version),
 * so no version guard may ever be added inside [launch] itself.
 *
 * Three moving parts:
 *  1. Enable freeform system-wide: `settings put global enable_freeform_support 1`
 *     (plus `force_resizable_activities` on 7.x). Done via shell since only the
 *     shell uid may write these. A device that ships the freeform feature needs
 *     no write. A fresh enable usually needs one reboot to fully take — the
 *     caller is told via toast.
 *  2. Keep the freeform workspace alive with a tiny off-screen, never-drawn
 *     activity ([FreeformKeepAliveActivity]); without it a lone freeform window
 *     collapses back to fullscreen on focus changes (Taskbar's hard-won lesson,
 *     most critical on 7.x).
 *  3. Launch the target into the freeform stack. On API 24–27 the hidden
 *     `ActivityOptions.setLaunchStackId(2)` is reflectable (non-SDK interface
 *     restrictions only start at API 28) and `setLaunchBounds` is public, so we
 *     stay in-process with no extra dependency. On API 28 that reflection is
 *     sealed, so we drive the shell instead: `am start --windowingMode 5`.
 *
 * Deliberately no maximize/caption chrome of our own: the window frame, drag,
 * resize and close affordances are all drawn by the system in freeform mode.
 */
object FreeformAppWindow {

    private const val TAG = "FreeformAppWindow"

    /** From android.app.WindowConfiguration (hidden). Freeform stack/mode id. */
    private const val WINDOWING_MODE_FREEFORM = 5

    /** From android.app.ActivityManager.StackId (hidden, pre-P). */
    private const val FREEFORM_WORKSPACE_STACK_ID = 2

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The range where freeform is the *primary* engine: Android 7.0–9.
     * Android 10+ (API 29+) keeps using the scrcpy mirror window
     * ([com.example.ava.services.AppWindowService]) — except mirror-blocked
     * 13+ devices, which call [launch] directly without consulting this —
     * and below API 24 the system has no freeform mode at all.
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT in 24..28

    /** Whether a privileged shell exists (required to enable freeform + launch). */
    fun hasShell(): Boolean =
        ShizukuUtils.isShizukuPermissionGranted() || RootUtils.isRootAvailable()

    /**
     * Launch [packageName] into a system freeform window. Returns true when the
     * launch was accepted (a launchable app plus a privileged shell), false
     * when a hard requirement was missing so the caller falls back to a normal
     * fullscreen launch. Deliberately no version guard: the API 28+ shell
     * branch below serves both Android 9 and the mirror-blocked 13+ fallback.
     * [onNeedsReboot] fires once when freeform support had to be turned on
     * this call — the user should reboot once for it to fully take effect on
     * most devices.
     *
     * On success the app is *always* brought up: windowed if freeform takes,
     * otherwise this method itself falls back to a plain fullscreen start, so a
     * failed windowing attempt never leaves the user with nothing (the same
     * graceful degradation the caller would have done for a false return). All
     * shell work runs off the caller's thread; only the in-process activity
     * starts are posted back to the main thread.
     */
    fun launch(context: Context, packageName: String, onNeedsReboot: () -> Unit): Boolean {
        if (!hasShell()) return false
        val appContext = context.applicationContext
        val component = appContext.packageManager
            .getLaunchIntentForPackage(packageName)?.component
            ?: return false
        val flatComponent = component.flattenToShortString()

        Thread({
            // Turn freeform on if needed (shell writes; only the shell uid may).
            if (!isFreeformEnabled(appContext)) {
                if (!enableFreeform(appContext)) {
                    launchFullscreen(appContext, packageName)
                    return@Thread
                }
                // Newly flipped: live for new tasks on some ROMs, but most need a
                // reboot (Taskbar's guidance, esp. 8.0/8.1). Tell the user, but
                // still attempt the launch — it works outright on some.
                onNeedsReboot()
            }

            // Fresh state: a still-running task would just be brought to the
            // front fullscreen instead of re-created in freeform. Mirrors what
            // the scrcpy path already does. Single-quote so a component/package
            // with a '$' (inner-class activity) isn't mangled by the shell.
            execShell("am force-stop '$packageName'")

            if (Build.VERSION.SDK_INT >= 28) {
                // Reflection onto setLaunchWindowingMode is sealed at API 28;
                // drive the shell instead. "-S" force-stops for a guaranteed
                // fresh start into the freeform stack. If it can't start the
                // activity at all, fall back to a plain fullscreen launch.
                val code = execShell(
                    "am start -S --windowingMode $WINDOWING_MODE_FREEFORM -n '$flatComponent'",
                )
                if (code != 0) launchFullscreen(appContext, packageName)
            } else {
                // API 24–27: keep-alive plus in-process reflected launch, both
                // on the main thread.
                mainHandler.post {
                    FreeformKeepAliveActivity.ensureRunning(appContext)
                    mainHandler.postDelayed(
                        { launchInProcessFreeform(appContext, packageName) },
                        KEEP_ALIVE_SETTLE_MS,
                    )
                }
            }
        }, "FreeformLaunch").apply { isDaemon = true }.start()
        return true
    }

    /**
     * API 24–27 launch: stay in-process. `setLaunchStackId` is hidden but not
     * yet restricted (non-SDK limits start at API 28) and `setLaunchBounds` is
     * public since API 24. On any failure, fall back to a fullscreen start so
     * the app still opens. Main thread only.
     */
    private fun launchInProcessFreeform(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        } ?: return

        val geometry = AppWindowGeometryStore.read(context, packageName)
        val options = ActivityOptions.makeBasic()
        try {
            val setStackId = ActivityOptions::class.java
                .getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
            setStackId.invoke(options, FREEFORM_WORKSPACE_STACK_ID)
        } catch (e: Exception) {
            Log.w(TAG, "setLaunchStackId reflection failed", e)
        }
        options.setLaunchBounds(
            Rect(
                geometry.x,
                geometry.y,
                geometry.x + geometry.width,
                geometry.y + geometry.height,
            ),
        )
        try {
            context.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "freeform startActivity failed, opening fullscreen", e)
            launchFullscreen(context, packageName)
        }
    }

    /** Plain fullscreen launch — the graceful fallback when windowing fails. */
    private fun launchFullscreen(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        runCatching { context.startActivity(intent) }
    }

    /** Freeform is live if the OEM ships the feature or the global flag is set. */
    private fun isFreeformEnabled(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature("android.software.freeform_window_management")) return true
        val cr = context.contentResolver
        val global = Settings.Global.getInt(cr, "enable_freeform_support", 0) != 0
        if (global) return true
        // On 7.x, force_resizable_activities alone is enough to float windows.
        return Build.VERSION.SDK_INT <= 25 &&
            Settings.Global.getInt(cr, "force_resizable_activities", 0) != 0
    }

    /**
     * Write the freeform flags via shell. Returns whether the write succeeded.
     * `force_resizable_activities` lives in Settings.Global (not Secure) and
     * only matters on 7.x, so it's written there and only on ≤25 — matching
     * exactly what [isFreeformEnabled] reads back, and keeping the system-wide
     * "force resizable" side effect off newer versions that don't need it.
     */
    private fun enableFreeform(context: Context): Boolean {
        val ok = execShell("settings put global enable_freeform_support 1") == 0
        if (Build.VERSION.SDK_INT <= 25) {
            execShell("settings put global force_resizable_activities 1")
        }
        return ok
    }

    private fun execShell(command: String): Int = when {
        ShizukuUtils.isShizukuPermissionGranted() -> ShizukuUtils.executeCommand(command).first
        RootUtils.isRootAvailable() -> runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        }.getOrDefault(-1)
        else -> -1
    }

    private const val KEEP_ALIVE_SETTLE_MS = 300L
}
