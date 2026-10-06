package com.example.ava.mods

import android.Manifest
import android.content.Context
import android.content.pm.PermissionInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Resolves mod manifest permission tokens into Android runtime permissions.
 *
 * Mod authors may use shorthand aliases (e.g. "gps") or short names without the
 * android.permission prefix. Unknown tokens are logged and skipped.
 */
object ModPermissions {
    private const val TAG = "ModPermissions"

    private fun aliasGroups(): Map<String, List<String>> = mapOf(
        "gps" to locationPermissions(),
        "location" to locationPermissions(),
        "fine_location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        "coarse_location" to listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        "background_location" to listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        "camera" to listOf(Manifest.permission.CAMERA),
        "microphone" to listOf(Manifest.permission.RECORD_AUDIO),
        "record_audio" to listOf(Manifest.permission.RECORD_AUDIO),
        "bluetooth" to bluetoothPermissions(),
        "bluetooth_scan" to bluetoothScanPermissions(),
        "bluetooth_connect" to bluetoothConnectPermissions(),
        "bluetooth_advertise" to bluetoothAdvertisePermissions(),
        "notifications" to listOf(Manifest.permission.POST_NOTIFICATIONS),
        "post_notifications" to listOf(Manifest.permission.POST_NOTIFICATIONS),
    )

    /**
     * Normal / install-time permissions. The host APK must already declare them;
     * [android.app.Activity.requestPermissions] cannot grant them. Treating a
     * missing grant as a runtime denial used to bounce users into app settings.
     */
    private val installTimePermissions = setOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA,
        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
        Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.VIBRATE,
        Manifest.permission.TRANSMIT_IR,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.USE_BIOMETRIC,
        Manifest.permission.USE_FINGERPRINT,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
    )

    /**
     * AppOps / settings-toggle permissions. Never pass these to
     * RequestMultiplePermissions — the platform returns denied immediately,
     * which the store classified as [DeniedPermanent] and opened app details.
     * Overlay mods already degrade via [android.provider.Settings.canDrawOverlays].
     */
    private val specialAppOpPermissions = setOf(
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.WRITE_SETTINGS,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.REQUEST_DELETE_PACKAGES,
    )

    private val privilegedPermissions = setOf(
        "android.permission.READ_LOGS",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
    )

    /**
     * Dangerous permissions used by published mods. Always eligible for a runtime
     * dialog even if [PackageManager.getPermissionInfo] is missing on a ROM.
     */
    private val knownDangerousRuntimePermissions = setOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun resolve(tokens: List<String>?): List<String> {
        if (tokens.isNullOrEmpty()) return emptyList()
        return tokens.flatMap(::resolveToken).distinct()
    }

    fun resolveForMod(modId: String, tokens: List<String>?): List<String> {
        val resolved = resolve(tokens)
        if (resolved.isNotEmpty()) return resolved
        return resolve(defaultTokensForMod(modId))
    }

    fun requiresPrivilegedGrant(permission: String): Boolean {
        return permission in privilegedPermissions
    }

    fun modRequiresPrivilegedShell(permissions: List<String>): Boolean {
        return resolve(permissions).any(::requiresPrivilegedGrant)
    }

    fun requiresRuntimeGrant(permission: String): Boolean {
        if (permission in installTimePermissions) return false
        if (permission in specialAppOpPermissions) return false
        if (permission in privilegedPermissions) return false
        if (permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ) {
            return false
        }
        if (permission == Manifest.permission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            return false
        }
        return true
    }

    /**
     * Same as [requiresRuntimeGrant] plus a platform protection-level check.
     * Unknown or non-dangerous tokens must not go through RequestMultiplePermissions.
     */
    fun requiresRuntimeGrant(context: Context, permission: String): Boolean {
        if (!requiresRuntimeGrant(permission)) return false
        if (permission in knownDangerousRuntimePermissions) return true
        return isPlatformDangerousRuntime(context, permission)
    }

    fun runtimePermissionsToRequest(context: Context, permissions: List<String>): List<String> {
        return permissions.filter { requiresRuntimeGrant(context, it) }.distinct()
    }

    fun missingRuntimePermissions(
        packageManager: (String) -> Int,
        permissions: List<String>
    ): List<String> {
        return permissions
            .filter(::requiresRuntimeGrant)
            .filter { packageManager(it) != PackageManager.PERMISSION_GRANTED }
    }

    fun missingRuntimePermissions(
        context: Context,
        packageManager: (String) -> Int,
        permissions: List<String>
    ): List<String> {
        return runtimePermissionsToRequest(context, permissions)
            .filter { packageManager(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun isPlatformDangerousRuntime(context: Context, permission: String): Boolean {
        val info = try {
            context.packageManager.getPermissionInfo(permission, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Unknown permission, not requesting at runtime: $permission")
            return false
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            Log.w(TAG, "Permission lookup failed, not requesting at runtime: $permission", t)
            return false
        }
        val level = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        return level == PermissionInfo.PROTECTION_DANGEROUS
    }

    private fun defaultTokensForMod(modId: String): List<String>? = when (modId) {
        "gps-mod" -> listOf("gps")
        else -> null
    }

    private fun resolveToken(token: String): List<String> {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return emptyList()

        val aliasKey = trimmed
            .removePrefix("android.permission.")
            .lowercase()

        aliasGroups()[aliasKey]?.let { return it }

        val fullName = when {
            trimmed.startsWith("android.permission.") -> trimmed
            else -> "android.permission.${trimmed.uppercase()}"
        }

        if (!isPlausiblePermission(fullName)) {
            Log.w(TAG, "Unrecognized mod permission token: $token")
            return emptyList()
        }
        return resolveFullPermissionName(fullName)
    }

    private fun isPlausiblePermission(permission: String): Boolean {
        return permission.startsWith("android.permission.") && permission.length > "android.permission.".length
    }

    private fun locationPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun bluetoothPermissions(): List<String> = buildList {
        addAll(bluetoothScanPermissions())
        addAll(bluetoothConnectPermissions())
    }

    /**
     * BLUETOOTH_SCAN / CONNECT / ADVERTISE are runtime-only from API 31.
     * On API 30 and below, requesting them opens a broken grant UI (result=denied)
     * and app info has no toggles — scan falls back to location, connect/advertise
     * use install-time legacy Bluetooth permissions declared in the main APK.
     */
    private fun bluetoothScanPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun bluetoothConnectPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }

    private fun bluetoothAdvertisePermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyList()
    }

    private fun resolveFullPermissionName(permission: String): List<String> = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> bluetoothScanPermissions()
        Manifest.permission.BLUETOOTH_CONNECT -> bluetoothConnectPermissions()
        Manifest.permission.BLUETOOTH_ADVERTISE -> bluetoothAdvertisePermissions()
        else -> listOf(permission)
    }
}
