package com.example.ava.mods

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.example.ava.MainActivity
import com.example.ava.R
import com.example.ava.fleet.FleetAuth
import com.example.ava.fleet.FleetManager
import com.example.ava.fleet.FleetNetwork
import com.example.ava.fleet.FleetScreenShot
import com.example.ava.permissions.ManagedPermission
import com.example.ava.permissions.PermissionGuideCoordinator
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.AvaAccessibilityService
import com.example.ava.ui.AvaToast
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.Screen
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Host API for mods that capture the device screen (not the device camera).
 *
 * Capture order matches [FleetScreenShot]: Shizuku/root `screencap`, then Accessibility
 * [takeScreenshot] on API 30+. No MediaProjection.
 *
 * Mods reach this class via reflection (`Class.forName`) the same way they call
 * [com.example.ava.utils.ShizukuUtils].
 */
object ModScreenCapture {
    private const val TAG = "ModScreenCapture"
    private const val GUIDE_COOLDOWN_MS = 45_000L

    private val lastGuideAtElapsed = AtomicLong(0L)

    /**
     * JPEG bytes from a one-shot capture, or null on failure.
     *
     * @param maxWidth longest edge after resize (portrait or landscape). `<= 0` keeps native pixels.
     */
    @JvmStatic
    @JvmOverloads
    fun captureJpeg(
        context: Context,
        force: Boolean = true,
        maxWidth: Int = 720,
        quality: Int = 40,
    ): ByteArray? {
        val app = context.applicationContext
        val jpeg = FleetScreenShot.captureOnce(
            context = app,
            force = force,
            maxWidth = maxWidth,
            quality = quality,
        )?.jpeg
        if (jpeg != null && jpeg.size >= 64) {
            return jpeg
        }
        // HA button / automation pressed once and capture failed: guide only when the
        // missing piece is Accessibility (shell path already unavailable).
        if (FleetScreenShot.shellBackend() == null && !FleetScreenShot.canAccessibilityCapture()) {
            guideUserToGrantAccessibility(app)
        }
        return null
    }

    @JvmStatic
    fun canCapture(context: Context): Boolean {
        val app = context.applicationContext
        return FleetScreenShot.shellBackend() != null || FleetScreenShot.canAccessibilityCapture() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AccessibilityBridge.isEnabled(app))
    }

    @JvmStatic
    fun lastError(): String? = FleetScreenShot.statusJson().optString("lastError").ifBlank { null }

    /**
     * LAN URL for the last one-shot JPEG via fleet HTTP (`GET /v1/screen/last.jpg`).
     * Includes `?password=` for fleet auth. Empty when no IP or no capture yet.
     * Always ≤ 255 characters for Home Assistant text sensors.
     */
    @JvmStatic
    fun lastImagePublicUrl(context: Context): String {
        if (FleetScreenShot.latest() == null) return ""
        val app = context.applicationContext
        val ip = FleetNetwork.getLocalIpAddress(app) ?: return ""
        val base = FleetNetwork.buildAccessUrl(ip, FleetManager.DEFAULT_PORT)
        val plain = FleetAuth.configuredPlain(app)
        val enc = runCatching {
            URLEncoder.encode(plain, Charsets.UTF_8.name())
        }.getOrElse { plain }
        val url = "$base/v1/screen/last.jpg?password=$enc"
        return if (url.length <= 255) url else url.take(255)
    }

    @JvmStatic
    fun isAccessibilityEnabled(context: Context): Boolean =
        AccessibilityBridge.isEnabled(context.applicationContext)

    @JvmStatic
    fun isAccessibilityReadyForScreenshot(context: Context): Boolean =
        FleetScreenShot.canAccessibilityCapture()

    /**
     * Best-effort enable Ava accessibility for screen capture / UI hooks.
     *
     * 1. Already enabled → true
     * 2. Try silent enable via [WRITE_SECURE_SETTINGS] when already granted
     * 3. Optionally open Ava's Accessibility details page for the user
     */
    @JvmStatic
    @JvmOverloads
    fun ensureAccessibility(context: Context, openSettingsIfNeeded: Boolean = true): Boolean {
        val app = context.applicationContext
        if (AccessibilityBridge.isEnabled(app)) {
            return true
        }
        if (tryEnableViaSecureSettings(app)) {
            if (AccessibilityBridge.isEnabled(app)) {
                Log.i(TAG, "accessibility enabled via WRITE_SECURE_SETTINGS")
                return true
            }
        }
        if (openSettingsIfNeeded) {
            guideUserToGrantAccessibility(app)
        }
        return AccessibilityBridge.isEnabled(app)
    }

    /**
     * Bring Ava to the Permission Manager, scroll to Accessibility, pulse a ripple.
     * User taps Authorize there → system Accessibility list. Debounced.
     */
    @JvmStatic
    fun guideUserToGrantAccessibility(context: Context): Boolean {
        val app = context.applicationContext
        if (AccessibilityBridge.isEnabled(app)) {
            return true
        }
        val now = SystemClock.elapsedRealtime()
        val prev = lastGuideAtElapsed.get()
        if (now - prev < GUIDE_COOLDOWN_MS) {
            Log.d(TAG, "accessibility guide suppressed (cooldown)")
            return false
        }
        if (!lastGuideAtElapsed.compareAndSet(prev, now)) {
            return false
        }
        AvaToast.show(
            app,
            app.getString(R.string.mod_permission_accessibility_hint),
            tag = "mod_accessibility_guide",
            durationMs = AvaToast.LONG_MS,
        )
        PermissionGuideCoordinator.requestHighlight(ManagedPermission.ACCESSIBILITY)
        MainNavigationCoordinator.requestNavigation(Screen.SETTINGS_PERMISSION_MANAGER)
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    putExtra("navigate_to", Screen.SETTINGS_PERMISSION_MANAGER)
                },
            )
        }.onFailure {
            Log.w(TAG, "failed to open Permission Manager: ${it.message}")
            AccessibilityBridge.openSettings(app)
        }
        return AccessibilityBridge.isEnabled(app)
    }

    /**
     * Whether [modId]'s manifest asks for Ava accessibility (flag or camera entity).
     * Checks the installed package even when the mod is still disabled — enable flow
     * requests permissions before flipping the switch.
     */
    @JvmStatic
    fun modDeclaresAccessibilityNeed(context: Context, modId: String): Boolean {
        if (modId.isBlank()) return false
        return runCatching {
            val modManager = ModManager.getInstance(context.applicationContext)
            val manifest = modManager.getCachedManifest(modId)
                ?: modManager.reloadManifestFromDisk(modId)
                ?: modManager.getModManifest(modId)
                ?: return false
            manifest.needsAccessibility ||
                manifest.entities.any { it.type.equals("camera", ignoreCase = true) }
        }.getOrDefault(false)
    }

    /**
     * True when an enabled mod declares [ModManifest.needsAccessibility] or exposes a
     * camera entity (screen-frame camera, not Camera2).
     */
    @JvmStatic
    fun modNeedsAccessibility(context: Context): Boolean {
        return runCatching {
            val modManager = ModManager.getInstance(context.applicationContext)
            modManager.getEnabledManifests().any { manifest ->
                manifest.needsAccessibility ||
                    manifest.entities.any { it.type.equals("camera", ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /**
     * If [modId] needs accessibility and it is not on yet, try silent enable then open
     * system Accessibility settings. Does not block mod enable.
     *
     * @return true when accessibility is already usable after this call
     */
    @JvmStatic
    fun ensureAccessibilityForMod(context: Context, modId: String): Boolean {
        if (!modDeclaresAccessibilityNeed(context, modId)) {
            return true
        }
        return ensureAccessibility(context, openSettingsIfNeeded = true)
    }

    private fun tryEnableViaSecureSettings(context: Context): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.packageManager.checkPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            return false
        }
        return runCatching {
            val target = ComponentName(context, AvaAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            if (!current.split(':').any { it.equals(target, ignoreCase = true) }) {
                val updated = if (current.isBlank()) target else "$current:$target"
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated,
                )
            }
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            true
        }.onFailure {
            Log.w(TAG, "secure-settings accessibility enable failed: ${it.message}")
        }.getOrDefault(false)
    }
}
