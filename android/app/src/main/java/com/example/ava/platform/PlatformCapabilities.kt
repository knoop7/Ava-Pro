package com.example.ava.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Single place for Android version branching.
 *
 * Policy:
 * - **minSdk 21 (Android 5.0)** — baseline; every path here must work on Lollipop.
 * - **targetSdk 36 (Android 16)** — newest platform rules apply at runtime on upgraded devices;
 *   gate *optional* APIs behind capability flags instead of scattering `SDK_INT` checks.
 *
 * Call sites should ask "can we do X?" (e.g. [supportsCommunicationDeviceRouting]) rather than
 * comparing raw API levels.
 */
object PlatformCapabilities {

    /** minSdk floor — Android 5.0 Lollipop. */
    const val MIN_SDK = 21

    /** compileSdk / targetSdk — Android 16. */
    const val TARGET_SDK = 36

    val sdkInt: Int get() = Build.VERSION.SDK_INT

    val isNougat: Boolean get() = sdkInt >= Build.VERSION_CODES.N
    val isOreoOrLater: Boolean get() = sdkInt >= Build.VERSION_CODES.O
    val isPieOrLater: Boolean get() = sdkInt >= Build.VERSION_CODES.P
    val isAndroid10OrLater: Boolean get() = sdkInt >= Build.VERSION_CODES.Q
    val isAndroid12OrLater: Boolean get() = sdkInt >= Build.VERSION_CODES.S
    val isAndroid13OrLater: Boolean get() = sdkInt >= Build.VERSION_CODES.TIRAMISU
    /** Android 16+ device (API 36). Named constant may lag in some SDK snapshots — keep raw int. */
    val isAndroid16OrLater: Boolean get() = sdkInt >= TARGET_SDK

    // --- Overlay window ---

    /**
     * Android 5–7 (API 21–25): [TYPE_PHONE] — still valid for minSdk.
     * Android 8+ (API 26+): [TYPE_APPLICATION_OVERLAY] — required for overlay permission model.
     */
    fun overlayWindowType(): Int =
        if (isOreoOrLater) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * API 23+ overlay permission check. On Android 5.x (API 21–22) overlays are granted at
     * install time, so this returns true without touching the API-23-only method.
     */
    fun canDrawOverlays(context: Context): Boolean =
        sdkInt < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * [View.getRootWindowInsets] is API 23. On Android 5.x the read lambda is not invoked.
     * Callers should fall back to [View.setOnApplyWindowInsetsListener] (API 20) and
     * [View.requestApplyInsets].
     */
    fun rootWindowInsets(view: View): WindowInsets? =
        rootWindowInsetsForSdk(sdkInt) { view.rootWindowInsets }

    internal fun rootWindowInsetsForSdk(sdkInt: Int, read: () -> WindowInsets?): WindowInsets? {
        if (sdkInt < Build.VERSION_CODES.M) return null
        return read()
    }

    /**
     * API 23+ WRITE_SETTINGS check. Granted at install time on Android 5.x (API 21–22).
     */
    fun canWriteSettings(context: Context): Boolean =
        sdkInt < Build.VERSION_CODES.M || Settings.System.canWrite(context)

    /**
     * API 31+ exact alarms. Before Android 12 the platform does not gate
     * [android.app.AlarmManager.setExactAndAllowWhileIdle].
     */
    fun canScheduleExactAlarms(context: Context): Boolean =
        canScheduleExactAlarmsForSdk(sdkInt) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarm?.canScheduleExactAlarms() == true
        }

    internal fun canScheduleExactAlarmsForSdk(sdkInt: Int, check: () -> Boolean): Boolean {
        if (sdkInt < Build.VERSION_CODES.S) return true
        return check()
    }

    /**
     * API 23+ Doze battery-optimization check. No Doze before Android 6, so treat as exempt.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (sdkInt < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** API 28+ — draw into display cutout (notch) short edges. No-op on Android 7–8. */
    fun applyDisplayCutoutShortEdges(params: WindowManager.LayoutParams) {
        if (isPieOrLater) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Read [WindowManager.LayoutParams.layoutInDisplayCutoutMode] without
     * [NoSuchFieldError] on API 21–27, where the instance field does not exist.
     */
    fun displayCutoutMode(params: WindowManager.LayoutParams): Int =
        displayCutoutModeForSdk(sdkInt) { params.layoutInDisplayCutoutMode }

    internal fun displayCutoutModeForSdk(sdkInt: Int, readMode: () -> Int): Int {
        if (sdkInt < Build.VERSION_CODES.P) return 0
        return readMode()
    }

    // --- Voice / audio ---

    /** API 31+ — [android.media.AudioManager.setCommunicationDevice]. */
    val supportsCommunicationDeviceRouting: Boolean
        get() = isAndroid12OrLater

    /** API 23+ — [android.media.AudioManager.getDevices]; unavailable on Android 5.x (API 21–22). */
    val supportsAudioDeviceEnumeration: Boolean
        get() = sdkInt >= Build.VERSION_CODES.M

    // --- Notifications / foreground service ---

    /** API 26+ — notification channels; below O use legacy notifications. */
    val requiresNotificationChannels: Boolean get() = isOreoOrLater

    /** API 33+ — runtime POST_NOTIFICATIONS permission. */
    val requiresPostNotificationsPermission: Boolean get() = isAndroid13OrLater

    /** API 24+ — [android.app.NotificationManager.areNotificationsEnabled]. */
    val supportsAreNotificationsEnabled: Boolean get() = isNougat

    /**
     * Android 16+ can pin an ongoing notification as a status-bar Live Update so the
     * shade stay reachable while a fullscreen overlay covers the screen.
     */
    val supportsPromotedOngoingNotifications: Boolean get() = isAndroid16OrLater

    /**
     * PendingIntent flags for a notification action.
     *
     * Android 5.x (API 21–22): no [PendingIntent.FLAG_IMMUTABLE].
     * Android 6–11: IMMUTABLE is available and harmless.
     * Android 12–16: IMMUTABLE (or MUTABLE) is required.
     */
    fun pendingIntentFlags(updateCurrent: Boolean = true): Int =
        pendingIntentFlagsForSdk(sdkInt, updateCurrent)

    internal fun pendingIntentFlagsForSdk(sdkInt: Int, updateCurrent: Boolean = true): Int {
        var flags = if (updateCurrent) PendingIntent.FLAG_UPDATE_CURRENT else 0
        if (sdkInt >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    // --- Bluetooth ---

    /** API 31+ — BLUETOOTH_SCAN / CONNECT runtime permissions. */
    val requiresBluetoothRuntimePermissions: Boolean get() = isAndroid12OrLater
}
