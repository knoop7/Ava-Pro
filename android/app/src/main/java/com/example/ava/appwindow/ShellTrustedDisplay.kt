package com.example.ava.appwindow

import android.os.Build
import android.util.Log
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import java.util.concurrent.Executors

/**
 * Pre-flight gate for the scrcpy mirror window ([AppWindowScrcpy]).
 *
 * On Android 13+ (API 33+) scrcpy's `new_display` requests a *trusted* virtual
 * display, which requires the shell to hold ADD_TRUSTED_DISPLAY. A 2025
 * Android security update removed that permission (and the other VDM
 * permissions) from the shell package on many Android 14/15 builds — restored
 * only in Android 16 — so the server dies instantly with a SecurityException
 * and the Shizuku mirror path can never come up (scrcpy issue #5523). Root
 * runs scrcpy as system uid 1000 with a FakeContext overlay (package
 * `android`) so DisplayManager accepts the virtual display. This gate
 * therefore only applies when Shizuku would be the backend (no su).
 *
 * Root uses system uid 1000 ([AppWindowRootIdentity]) rather than uid 0.
 * This gate keys on the remaining case: Shizuku-only, no root.
 *
 * The verdict comes from one `dumpsys package com.android.shell` grep, cached
 * for the process lifetime (the grant only changes with an OS update, which
 * means a reboot). An unreadable dump fails open — the launch proceeds and the
 * mirror window's own watchdog/retry path deals with any failure, exactly as
 * before this gate existed.
 */
object ShellTrustedDisplay {

    private const val TAG = "ShellTrustedDisplay"

    /** null = not yet resolved this process. */
    @Volatile
    private var blocked: Boolean? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ShellTrustedDisplay").apply { isDaemon = true }
    }

    /**
     * Cached verdict without any shell round-trip: `false` = mirror path is
     * clear (including "gate doesn't apply here"), `true` = shell verifiably
     * lacks ADD_TRUSTED_DISPLAY, `null` = not yet resolved — use
     * [resolveAsync] and route from its callback.
     */
    fun cachedBlocked(): Boolean? = if (!applies()) false else blocked

    /**
     * Resolve the verdict off the caller's thread and hand it to [onResolved]
     * (invoked on this object's worker thread). Cheap after the first call —
     * the result is cached.
     */
    fun resolveAsync(onResolved: (blocked: Boolean) -> Unit) {
        executor.execute { onResolved(isBlocked()) }
    }

    /** Blocking resolve (one shell round-trip on first use). Off the main thread. */
    fun isBlocked(): Boolean {
        if (!applies()) return false
        blocked?.let { return it }
        val verdict = readShellGrant()
        if (verdict != null) blocked = verdict // never cache "couldn't tell"
        return verdict ?: false
    }

    /**
     * The TRUSTED flag is only requested by scrcpy on API 33+, and only the
     * shell uid lost the permission. A root backend creates the display as
     * uid 1000 ([AppWindowRootIdentity]), so the gate does not apply while su
     * is available — even if Shizuku is also granted.
     */
    private fun applies(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ShizukuUtils.isShizukuPermissionGranted() &&
            !RootUtils.isRootAvailable()

    /**
     * true = blocked, false = granted, null = couldn't tell. The `userId=`
     * line always exists in a package dump, so its presence distinguishes
     * "dump worked but the grant is gone" (the 2025 patch removed it from the
     * manifest — no line at all) from "dump itself failed".
     */
    private fun readShellGrant(): Boolean? {
        val (code, out) = ShizukuUtils.executeCommandForOutput(
            "dumpsys package com.android.shell | grep -E 'ADD_TRUSTED_DISPLAY|userId='",
        )
        return when {
            out.contains("ADD_TRUSTED_DISPLAY: granted=true") -> false
            out.contains("userId=") -> {
                Log.w(TAG, "shell lacks ADD_TRUSTED_DISPLAY; mirror window unavailable")
                true
            }
            else -> {
                Log.w(TAG, "unreadable shell permission dump (code=$code), failing open")
                null
            }
        }
    }
}
