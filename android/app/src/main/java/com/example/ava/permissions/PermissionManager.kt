package com.example.ava.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.mods.ModScreenCapture
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.utils.BatteryOptimizationHelper
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils

enum class ManagedPermission {
    MICROPHONE,
    BATTERY,
    CAMERA,
    NOTIFICATIONS,
    WRITE_SETTINGS,
    SYSTEM_UI,
    ACCESSIBILITY,
    DEVICE_ADMIN,
    LOCATION,
}

enum class PermissionUiStatus {
    GRANTED,
    MISSING,
    UNUSED,
}

enum class PermissionGrantAction {
    RUNTIME,
    BATTERY_SETTINGS,
    WRITE_SETTINGS,
    SECURE_SETTINGS,
    ACCESSIBILITY_SETTINGS,
    DEVICE_ADMIN,
    NONE,
}

data class PermissionRowState(
    val id: ManagedPermission,
    val titleRes: Int,
    val whyRes: Int,
    val status: PermissionUiStatus,
    val action: PermissionGrantAction,
    val actionLabelRes: Int,
    /** When true, the CTA stays enabled even after granted (re-open system UI). */
    val reenterable: Boolean = false,
)

data class PermissionNeedFlags(
    val voiceChannelEnabled: Boolean,
    val autoRestartEnabled: Boolean,
    val cameraEnabled: Boolean,
    val brightnessEnabled: Boolean,
    val screenPowerControlEnabled: Boolean,
)

object PermissionManager {

    fun visiblePermissions(): List<ManagedPermission> =
        ManagedPermission.entries.filter { perm ->
            when (perm) {
                ManagedPermission.NOTIFICATIONS ->
                    PlatformCapabilities.requiresPostNotificationsPermission
                else -> true
            }
        }

    fun isGranted(context: Context, perm: ManagedPermission): Boolean =
        when (perm) {
            ManagedPermission.MICROPHONE ->
                hasRuntime(context, Manifest.permission.RECORD_AUDIO)
            ManagedPermission.BATTERY ->
                BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            ManagedPermission.CAMERA ->
                hasRuntime(context, Manifest.permission.CAMERA)
            ManagedPermission.NOTIFICATIONS ->
                !PlatformCapabilities.requiresPostNotificationsPermission ||
                    hasRuntime(context, Manifest.permission.POST_NOTIFICATIONS)
            ManagedPermission.WRITE_SETTINGS ->
                PlatformCapabilities.canWriteSettings(context)
            ManagedPermission.SYSTEM_UI ->
                hasRuntime(context, Manifest.permission.WRITE_SECURE_SETTINGS)
            ManagedPermission.ACCESSIBILITY ->
                AccessibilityBridge.isEnabled(context)
            ManagedPermission.DEVICE_ADMIN ->
                ScreenControlUtils.isDeviceAdminActive(context)
            ManagedPermission.LOCATION ->
                hasRuntime(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasRuntime(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun isNeeded(
        context: Context,
        perm: ManagedPermission,
        flags: PermissionNeedFlags,
    ): Boolean {
        val satelliteRunning = VoiceSatelliteService.getInstance() != null
        return when (perm) {
            ManagedPermission.MICROPHONE ->
                flags.voiceChannelEnabled || satelliteRunning
            ManagedPermission.BATTERY ->
                satelliteRunning || flags.autoRestartEnabled
            ManagedPermission.CAMERA ->
                flags.cameraEnabled
            ManagedPermission.NOTIFICATIONS ->
                PlatformCapabilities.requiresPostNotificationsPermission &&
                    (flags.voiceChannelEnabled || flags.cameraEnabled ||
                        flags.autoRestartEnabled || satelliteRunning)
            ManagedPermission.WRITE_SETTINGS ->
                flags.brightnessEnabled
            ManagedPermission.SYSTEM_UI ->
                false
            ManagedPermission.ACCESSIBILITY ->
                flags.autoRestartEnabled || ModScreenCapture.modNeedsAccessibility(context)
            ManagedPermission.DEVICE_ADMIN ->
                flags.screenPowerControlEnabled &&
                    ScreenControlUtils.shouldRequestDeviceAdmin(context)
            ManagedPermission.LOCATION ->
                BluetoothPresenceManager.getInstance(context).trackedDevices.value.isNotEmpty()
        }
    }

    fun statusOf(
        context: Context,
        perm: ManagedPermission,
        flags: PermissionNeedFlags,
    ): PermissionUiStatus {
        if (isGranted(context, perm)) return PermissionUiStatus.GRANTED
        return if (isNeeded(context, perm, flags)) {
            PermissionUiStatus.MISSING
        } else {
            PermissionUiStatus.UNUSED
        }
    }

    fun buildRows(context: Context, flags: PermissionNeedFlags): List<PermissionRowState> {
        val order = mapOf(
            PermissionUiStatus.MISSING to 0,
            PermissionUiStatus.UNUSED to 1,
            PermissionUiStatus.GRANTED to 2,
        )
        val rows = visiblePermissions()
            .map { perm ->
                val status = statusOf(context, perm, flags)
                PermissionRowState(
                    id = perm,
                    titleRes = titleRes(perm),
                    whyRes = whyRes(perm),
                    status = status,
                    action = grantAction(perm),
                    actionLabelRes = actionLabelRes(perm, status),
                    reenterable = isReenterable(perm),
                )
            }
            .sortedWith(
                compareBy<PermissionRowState> { order.getValue(it.status) }
                    .thenBy { it.id.ordinal },
            )
        // Trailing block: Accessibility → Location → Device admin (last).
        val trailing = listOf(
            ManagedPermission.ACCESSIBILITY,
            ManagedPermission.LOCATION,
            ManagedPermission.DEVICE_ADMIN,
        )
        val pinned = trailing.mapNotNull { id -> rows.find { it.id == id } }
        val rest = rows.filterNot { it.id in trailing }
        return rest + pinned
    }

    fun runtimePermissionsFor(perm: ManagedPermission): Array<String> =
        when (perm) {
            ManagedPermission.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            ManagedPermission.CAMERA -> arrayOf(Manifest.permission.CAMERA)
            ManagedPermission.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray()
                }
            ManagedPermission.LOCATION -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            else -> emptyArray()
        }

    fun openSpecialSettings(context: Context, perm: ManagedPermission): Boolean =
        when (perm) {
            ManagedPermission.BATTERY ->
                BatteryOptimizationHelper.tryRequestIgnoreBatteryOptimizations(context)
            ManagedPermission.WRITE_SETTINGS -> openWriteSettings(context)
            ManagedPermission.ACCESSIBILITY -> {
                // Permission Manager taps must always open system Accessibility settings —
                // even when Ava is already enabled — so the user can review or turn it off.
                // Silent WRITE_SECURE_SETTINGS enable stays on the mod auto-prompt path only.
                AccessibilityBridge.openSettings(context)
            }
            ManagedPermission.DEVICE_ADMIN ->
                if (isGranted(context, ManagedPermission.DEVICE_ADMIN)) {
                    openDeviceAdminUninstall(context)
                } else {
                    openDeviceAdminUi(context)
                }
            ManagedPermission.SYSTEM_UI -> tryGrantSecureSettings(context)
            else -> false
        }

    /** Activate device admin (only useful when not yet active). */
    fun openDeviceAdminUi(context: Context): Boolean {
        if (!ScreenControlUtils.canShowDeviceAdminUi(context)) return false
        return runCatching {
            context.startActivity(
                ScreenControlUtils.buildDeviceAdminIntent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    fun launchDeviceAdmin(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
    ): Boolean {
        if (!ScreenControlUtils.canShowDeviceAdminUi(activity)) return false
        return runCatching {
            launcher.launch(ScreenControlUtils.buildDeviceAdminIntent(activity))
            true
        }.getOrDefault(false)
    }

    /**
     * When already active, many ROMs block re-entry to the add-admin page.
     * Launching package uninstall surfaces the system "deactivate admin" dialog instead.
     */
    fun openDeviceAdminUninstall(context: Context): Boolean =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)

    fun tryGrantSecureSettings(context: Context): Boolean {
        if (isGranted(context, ManagedPermission.SYSTEM_UI)) return true
        val cmd = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            return ShizukuUtils.executeCommand(cmd).first == 0 &&
                isGranted(context, ManagedPermission.SYSTEM_UI)
        }
        if (RootUtils.isRootAvailable()) {
            return runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
            }.getOrDefault(false) && isGranted(context, ManagedPermission.SYSTEM_UI)
        }
        return false
    }

    private fun openWriteSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun hasRuntime(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun titleRes(perm: ManagedPermission): Int = when (perm) {
        ManagedPermission.MICROPHONE -> R.string.settings_permission_mic_name
        ManagedPermission.BATTERY -> R.string.settings_permission_battery_name
        ManagedPermission.CAMERA -> R.string.settings_permission_camera_name
        ManagedPermission.NOTIFICATIONS -> R.string.settings_permission_notifications_name
        ManagedPermission.WRITE_SETTINGS -> R.string.settings_permission_write_settings_name
        ManagedPermission.SYSTEM_UI -> R.string.settings_permission_system_ui_name
        ManagedPermission.ACCESSIBILITY -> R.string.settings_permission_accessibility_name
        ManagedPermission.DEVICE_ADMIN -> R.string.settings_permission_device_admin_name
        ManagedPermission.LOCATION -> R.string.settings_permission_location_name
    }

    private fun whyRes(perm: ManagedPermission): Int = when (perm) {
        ManagedPermission.MICROPHONE -> R.string.settings_permission_mic_why
        ManagedPermission.BATTERY -> R.string.settings_permission_battery_why
        ManagedPermission.CAMERA -> R.string.settings_permission_camera_why
        ManagedPermission.NOTIFICATIONS -> R.string.settings_permission_notifications_why
        ManagedPermission.WRITE_SETTINGS -> R.string.settings_permission_write_settings_why
        ManagedPermission.SYSTEM_UI -> R.string.settings_permission_system_ui_why
        ManagedPermission.ACCESSIBILITY -> R.string.settings_permission_accessibility_why
        ManagedPermission.DEVICE_ADMIN -> R.string.settings_permission_device_admin_why
        ManagedPermission.LOCATION -> R.string.settings_permission_location_why
    }

    private fun grantAction(perm: ManagedPermission): PermissionGrantAction = when (perm) {
        ManagedPermission.MICROPHONE,
        ManagedPermission.CAMERA,
        ManagedPermission.NOTIFICATIONS,
        ManagedPermission.LOCATION,
        -> PermissionGrantAction.RUNTIME
        ManagedPermission.BATTERY -> PermissionGrantAction.BATTERY_SETTINGS
        ManagedPermission.WRITE_SETTINGS -> PermissionGrantAction.WRITE_SETTINGS
        ManagedPermission.SYSTEM_UI -> PermissionGrantAction.SECURE_SETTINGS
        ManagedPermission.ACCESSIBILITY -> PermissionGrantAction.ACCESSIBILITY_SETTINGS
        ManagedPermission.DEVICE_ADMIN -> PermissionGrantAction.DEVICE_ADMIN
    }

    private fun isReenterable(perm: ManagedPermission): Boolean =
        perm == ManagedPermission.DEVICE_ADMIN || perm == ManagedPermission.ACCESSIBILITY

    private fun actionLabelRes(
        perm: ManagedPermission,
        status: PermissionUiStatus,
    ): Int {
        if (status == PermissionUiStatus.GRANTED && !isReenterable(perm)) {
            return R.string.settings_permission_status_granted
        }
        return when (perm) {
            ManagedPermission.BATTERY,
            ManagedPermission.WRITE_SETTINGS,
            ManagedPermission.ACCESSIBILITY,
            -> R.string.settings_permission_action_open_settings
            ManagedPermission.DEVICE_ADMIN ->
                if (status == PermissionUiStatus.GRANTED) {
                    R.string.settings_permission_action_deactivate_uninstall
                } else {
                    R.string.settings_permission_action_activate
                }
            else -> R.string.settings_permission_action_grant
        }
    }
}
