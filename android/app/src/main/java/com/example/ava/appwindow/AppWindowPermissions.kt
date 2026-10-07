package com.example.ava.appwindow

import android.util.Log
import com.example.ava.utils.ShizukuUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps runtime-permission dialogs from freezing every mirrored window.
 *
 * When an app inside a scrcpy virtual display requests a runtime permission,
 * `GrantPermissionsActivity` opens *on that virtual display* with the
 * `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` flag — Android's anti-tapjacking rule that
 * force-hides every app-owned `TYPE_APPLICATION_OVERLAY` in the system. That
 * includes the very window mirroring the dialog, so the prompt becomes
 * unseeable and undismissable while **all** floating windows sit at surface
 * alpha 0. Verified live on the vivo Android 11 device: three healthy mirrors
 * (`mPolicyVisibility=false mForceHideNonSystemOverlayWindow=true`) all went
 * invisible the moment the Voice Recorder app asked for a permission on display 4, and all
 * snapped back the instant that dialog died.
 *
 * Two layers of defense, both riding the privileged shell:
 *  - [grantAll] *prevents*: before an app is launched onto a virtual display,
 *    `pm grant` every requested-but-ungranted runtime permission so the dialog
 *    has no reason to appear.
 *  - [permissionDialogDisplay] + [dismissPermissionDialog] *rescue*: the
 *    service polls for a grant dialog sitting on a non-default display and
 *    kills it (after re-running [grantAll], so the app's retry succeeds
 *    silently instead of re-prompting into the same trap).
 */
object AppWindowPermissions {
    private const val TAG = "AppWinPerms"

    /** Packages already pre-granted this process run; resize/rotate restarts
     *  reuse the process, so they skip the ~1 s of `pm grant` round-trips. */
    private val grantedOnce = ConcurrentHashMap<String, Boolean>()

    /**
     * Grant every runtime permission [pkg] has requested but not been granted.
     * Failures per-permission are expected (policy-fixed, restricted, install
     * permissions caught by the same filter) and silently skipped by the shell
     * loop. [force] re-runs even for a cached package — used by the rescue
     * path, where the still-ungranted permission is exactly the one the
     * trapped dialog was asking about.
     */
    fun grantAll(pkg: String, backend: String, force: Boolean = false) {
        if (!force && grantedOnce.putIfAbsent(pkg, true) != null) return
        grantedOnce[pkg] = true
        val cmd = "for p in \$(dumpsys package '$pkg' 2>/dev/null" +
            " | grep ': granted=false' | cut -d: -f1 | tr -d ' \\t' | sort -u); do" +
            " pm grant '$pkg' \$p >/dev/null 2>&1 && echo \$p; done; true"
        val (code, out) = shellRead(backend, cmd)
        val granted = out.lineSequence().filter { it.startsWith("android.") || it.contains('.') }
            .joinToString(" ")
        Log.i(TAG, "pre-grant $pkg (code=$code): ${granted.ifBlank { "nothing to grant" }}")
    }

    /**
     * Display id of a currently-showing runtime-permission dialog, or -1 when
     * there is none. Any id > 0 means the dialog is on a virtual display —
     * i.e. trapped inside a force-hidden mirror window and needs rescuing. A
     * dialog on display 0 is in front of the user, who can answer it normally
     * (the overlays un-hide by themselves afterwards), so callers leave it be.
     */
    fun permissionDialogDisplay(backend: String): Int {
        val (_, out) = shellRead(
            backend,
            "dumpsys window windows 2>/dev/null | grep -A2 GrantPermissionsActivity" +
                " | grep -m1 -o 'mDisplayId=[0-9]*'",
        )
        return Regex("mDisplayId=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Kill a trapped grant dialog. Force-stopping the permission controller is
     * safe — it is a stateless UI process that respawns on demand — and it is
     * the only dismissal that works regardless of which display holds focus
     * (key injection follows focus; this doesn't). The requesting app sees an
     * ordinary denial and, because the rescue path pre-granted first, its next
     * permission check passes without a new prompt.
     */
    fun dismissPermissionDialog(backend: String) {
        shellRead(backend, "am force-stop com.android.permissioncontroller")
    }

    private fun shellRead(backend: String, command: String): Pair<Int, String> = when (backend) {
        "shizuku" -> ShizukuUtils.executeCommandForOutput(command)
        "root" -> runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            Pair(p.waitFor(), out)
        }.getOrElse { Pair(-1, it.message ?: "su_failed") }
        else -> Pair(-1, "no_backend")
    }
}
