package com.example.ava.mods

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ava.services.AccessibilityBridge
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils

/**
 * Ensures mod permissions before enable: Shizuku/root pm grant first, runtime dialog as fallback.
 *
 * Privileged permissions listed only under manifest [ModManifest.optionalPermissions] never block
 * enable — the mod is expected to degrade. Privileged entries in required [permissions] still
 * need a shell grant (or a prior ADB grant); live Shizuku is not required once they are held.
 *
 * Mods with [ModManifest.needsAccessibility] (or a camera entity) also trigger a best-effort
 * Accessibility enable / settings jump — that path never blocks enable.
 */
object ModPermissionCoordinator {

    private const val TAG = "ModPermissionCoordinator"
    private const val PREFS = "mod_permission_requests"

    enum class Outcome {
        Granted,
        /** Runtime OK; optional privileged permissions still missing (mod may degrade). */
        GrantedPartial,
        DeniedTemporary,
        DeniedPermanent,
        NeedsShizuku,
    }

    suspend fun ensurePermissions(
        context: Context,
        modManager: ModManager,
        modId: String,
        requestRuntime: suspend (List<String>) -> Map<String, Boolean>,
    ): Outcome {
        val appContext = context.applicationContext
        val required = modManager.getRequiredPermissions(modId)
        val optional = modManager.getOptionalPermissions(modId)

        val missingRequiredPrivileged = missingPrivileged(appContext, required)
        if (missingRequiredPrivileged.isNotEmpty() && !canUsePrivilegedShell()) {
            Log.i(
                TAG,
                "mod $modId needs privileged grant for $missingRequiredPrivileged " +
                    "(no Shizuku/root; already-held grants are OK)",
            )
            return Outcome.NeedsShizuku
        }

        tryShellGrant(appContext, (required + optional).distinct())

        // Only dangerous runtime permissions may go through RequestMultiplePermissions.
        // Install-time / AppOps tokens (Wi-Fi state, overlay, …) are not grantable
        // this way; asking for them looked like a permanent denial and kicked the
        // user into app settings. Overlay mods already no-op without canDrawOverlays.
        val missing = modManager.getMissingPermissions(modId)
            .filter { ModPermissions.requiresRuntimeGrant(appContext, it) }
        if (missing.isNotEmpty()) {
            val results = requestRuntime(missing)
            val allGranted = missing.all { results[it] == true }
            if (!allGranted) {
                val stillMissing = modManager.getMissingPermissions(modId)
                    .filter { ModPermissions.requiresRuntimeGrant(appContext, it) }
                if (stillMissing.isNotEmpty()) {
                    val outcome = classifyRuntimeDenial(context, stillMissing)
                    markPermissionsRequested(appContext, stillMissing)
                    return outcome
                }
            }
        }

        // Accessibility is a settings toggle, not a runtime permission — prompt here so
        // screen-capture (and similar) mods do not silently enable with a dead capture path.
        ensureAccessibilityForMod(appContext, modId)

        val missingOptionalPrivileged = missingPrivileged(appContext, optional)
        if (missingOptionalPrivileged.isNotEmpty()) {
            Log.i(
                TAG,
                "mod $modId enabled with limited privileges; missing $missingOptionalPrivileged",
            )
            return Outcome.GrantedPartial
        }
        return Outcome.Granted
    }

    fun canUsePrivilegedShell(): Boolean {
        return ShizukuUtils.isShizukuPermissionGranted() || RootUtils.isRootAvailable()
    }

    /** @return true when accessibility was needed and is still missing after the prompt. */
    fun accessibilityStillMissing(context: Context, modId: String): Boolean {
        if (!ModScreenCapture.modDeclaresAccessibilityNeed(context, modId)) {
            return false
        }
        return !AccessibilityBridge.isEnabled(context.applicationContext)
    }

    private fun ensureAccessibilityForMod(context: Context, modId: String) {
        if (!ModScreenCapture.modDeclaresAccessibilityNeed(context, modId)) {
            return
        }
        val ok = ModScreenCapture.ensureAccessibilityForMod(context, modId)
        if (ok) {
            Log.i(TAG, "mod $modId accessibility ready")
        } else {
            Log.i(TAG, "mod $modId needs Accessibility — prompted system settings")
        }
    }

    private fun missingPrivileged(context: Context, permissions: List<String>): List<String> {
        return permissions
            .filter { ModPermissions.requiresPrivilegedGrant(it) }
            .filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
    }

    private fun tryShellGrant(context: Context, permissions: List<String>) {
        if (!canUsePrivilegedShell()) {
            return
        }
        ShizukuUtils.init(context.packageName)
        val pkg = context.packageName
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                continue
            }
            if (!ModPermissions.requiresPrivilegedGrant(permission) &&
                !ModPermissions.requiresRuntimeGrant(context, permission)
            ) {
                continue
            }
            val cmd = "pm grant $pkg $permission"
            val code = when {
                ShizukuUtils.isShizukuPermissionGranted() ->
                    ShizukuUtils.executeCommand(cmd).first
                RootUtils.isRootAvailable() ->
                    runShellAsRoot(cmd)
                else -> -1
            }
            if (code == 0) {
                Log.d(TAG, "shell granted $permission")
            } else {
                Log.d(TAG, "shell grant failed for $permission (exit=$code)")
            }
        }
    }

    private fun runShellAsRoot(command: String): Int {
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        }.getOrDefault(-1)
    }

    private fun classifyRuntimeDenial(context: Context, deniedPermissions: List<String>): Outcome {
        val runtimeDenied = deniedPermissions.filter { ModPermissions.requiresRuntimeGrant(context, it) }
        if (runtimeDenied.isEmpty()) {
            return Outcome.Granted
        }
        val activity = context.findActivity()
        val permanentlyDenied = runtimeDenied.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
                wasPermissionRequested(context, permission) &&
                (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission))
        }
        return if (permanentlyDenied) Outcome.DeniedPermanent else Outcome.DeniedTemporary
    }

    private fun wasPermissionRequested(context: Context, permission: String): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(permission, false)
    }

    private fun markPermissionsRequested(context: Context, permissions: List<String>) {
        if (permissions.isEmpty()) return
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        permissions.forEach { editor.putBoolean(it, true) }
        editor.apply()
    }

    private tailrec fun Context.findActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
