package com.example.ava.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun getBatteryOptimizationSettingsIntent(context: Context): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Root/Shizuku fallback when the system battery UI is missing (common on Android 7 kiosk ROMs). */
    fun tryShellWhitelist(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (isIgnoringBatteryOptimizations(context)) return true
        return tryWhitelistViaShell(context.packageName)
    }

    /**
     * Best-effort battery whitelist from a Service/background context.
     * Many Android 7 kiosk ROMs have no Settings activity for [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
     */
    fun tryRequestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (isIgnoringBatteryOptimizations(context)) return true

        val pm = context.packageManager
        val candidates = listOfNotNull(
            requestIgnoreBatteryOptimizations(context),
            getBatteryOptimizationSettingsIntent(context)
        )
        for (base in candidates) {
            val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) == null) continue
            return try {
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "Battery optimization UI unavailable: ${intent.action}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open battery optimization UI: ${intent.action}", e)
                false
            }
        }

        if (tryWhitelistViaShell(context.packageName)) {
            return isIgnoringBatteryOptimizations(context)
        }

        Log.d(
            TAG,
            "No battery optimization UI on this device; skipping (common on Android 7 kiosk ROMs)"
        )
        return false
    }

    private fun tryWhitelistViaShell(packageName: String): Boolean {
        val command = "dumpsys deviceidle whitelist +$packageName"
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            return ShizukuUtils.executeCommand(command).first == 0
        }
        if (!RootUtils.isRootAvailable()) return false
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
        }.getOrDefault(false)
    }
}
