package com.example.ava.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils

/**
 * Built-in device profile for background camera access on kiosk hardware (e.g. Meta Portal).
 *
 * Portal-class devices block third-party camera open from background services unless the process
 * is considered visible — see [CameraVisibilityOverlay] (pattern from portal-ha-bridge).
 */
data class DeviceCameraProfile(
    val id: String,
    val requiresVisibilityOverlay: Boolean,
    val forceFrontCamera: Boolean,
) {
    companion object {
        private const val TAG = "DeviceCameraProfile"
        private const val PORTAL_LAUNCHER = "com.facebook.alohaapps.launcher"

        /** Codenames referenced by portal-ha-bridge and Meta Portal family devices. */
        private val PORTAL_DEVICES = setOf(
            "aloha",   // Portal+ 1st gen
            "cipher",  // Portal+ 2nd gen
            "argos",   // Portal 10"
            "billie",  // Portal Mini
        )

        val STANDARD = DeviceCameraProfile(
            id = "standard",
            requiresVisibilityOverlay = false,
            forceFrontCamera = false,
        )

        val PORTAL = DeviceCameraProfile(
            id = "portal",
            requiresVisibilityOverlay = true,
            forceFrontCamera = true,
        )

        fun resolve(context: Context): DeviceCameraProfile {
            return if (isPortalFamily(context)) PORTAL else STANDARD
        }

        /**
         * Best-effort grant of overlay (+ camera) for Portal/kiosk devices via Shizuku or root.
         * No-op on standard phones or when permissions are already granted.
         */
        fun grantKioskCameraPermissionsIfNeeded(context: Context): Boolean {
            if (!resolve(context).requiresVisibilityOverlay) return false
            val appContext = context.applicationContext
            var granted = false
            if (!PlatformCapabilities.canDrawOverlays(appContext)) {
                granted = grantAppOp(appContext, "SYSTEM_ALERT_WINDOW") || granted
            } else {
                granted = true
            }
            if (!hasCameraPermission(appContext)) {
                granted = grantPmPermission(appContext, "android.permission.CAMERA") || granted
            }
            if (granted) {
                Log.i(TAG, "Kiosk camera permissions prepared (overlay=${PlatformCapabilities.canDrawOverlays(appContext)})")
            } else {
                Log.w(TAG, "Kiosk camera permissions missing — grant SYSTEM_ALERT_WINDOW and CAMERA via ADB/Shizuku")
            }
            return granted
        }

        private fun isPortalFamily(context: Context): Boolean {
            val device = Build.DEVICE?.lowercase().orEmpty()
            if (device in PORTAL_DEVICES) return true
            if (isPackageInstalled(context, PORTAL_LAUNCHER)) return true
            val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
            val model = Build.MODEL?.lowercase().orEmpty()
            return (manufacturer.contains("facebook") || manufacturer.contains("meta")) &&
                model.contains("portal")
        }

        private fun isPackageInstalled(context: Context, packageName: String): Boolean {
            return runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }

        private fun hasCameraPermission(context: Context): Boolean {
            // Context.checkSelfPermission is API 23+. On 21–22 CAMERA is
            // install-time; PackageManager.checkPermission exists from API 1.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                context.packageManager.checkPermission(
                    android.Manifest.permission.CAMERA,
                    context.packageName,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

        private fun grantAppOp(context: Context, op: String): Boolean {
            val pkg = context.packageName
            if (ShizukuUtils.isShizukuPermissionGranted()) {
                val (code, _) = ShizukuUtils.executeCommand("appops set $pkg $op allow")
                if (code == 0) return true
            }
            if (RootUtils.isRootAvailable()) {
                return runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "appops set $pkg $op allow")).waitFor() == 0
                }.getOrDefault(false)
            }
            return runCatching {
                val process = Runtime.getRuntime().exec("su")
                java.io.DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("appops set $pkg $op allow\n")
                    os.writeBytes("exit\n")
                }
                process.waitFor() == 0
            }.getOrDefault(false)
        }

        private fun grantPmPermission(context: Context, permission: String): Boolean {
            val pkg = context.packageName
            if (ShizukuUtils.isShizukuPermissionGranted()) {
                val (code, _) = ShizukuUtils.executeCommand("pm grant $pkg $permission")
                if (code == 0) return true
            }
            if (RootUtils.isRootAvailable()) {
                return runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant $pkg $permission")).waitFor() == 0
                }.getOrDefault(false)
            }
            return false
        }
    }
}
