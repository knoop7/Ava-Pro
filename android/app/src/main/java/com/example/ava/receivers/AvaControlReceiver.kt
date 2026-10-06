package com.example.ava.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.example.ava.MainActivity
import com.example.ava.mods.AvaModControl
import com.example.ava.notifications.FullscreenOverlayEscape
import com.example.ava.services.OverlayZOrderCoordinator
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.services.WebViewService
import com.example.ava.settings.AvaSettingsApplier
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.ui.AvaToast
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils
import com.example.ava.webcompat.BrowserEngine
import com.example.ava.webcompat.HostSidebarCommandExecutor
import com.example.ava.webcompat.HostSidebarSettingsBridge
import com.example.ava.webcompat.HostSidebarSettingsContract
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通用广播接收器，允许外部应用控制 Ava 功能
 *
 * 使用示例 (adb):
 * adb shell am broadcast -a com.example.ava.ACTION_TOGGLE_MIC
 * adb shell am broadcast -a com.example.ava.ACTION_GRANT_BLUETOOTH
 * adb shell am broadcast -a com.example.ava.ACTION_GRANT_OVERLAY
 * adb shell am broadcast -a com.example.ava.ACTION_CHECK_UPDATE com.example.ava
 * adb shell am start -a com.example.ava.action.SHOW_UPDATE -n com.example.ava/.MainActivity
 * adb shell am broadcast -a com.example.ava.ACTION_APPLY_SETTINGS --es settings_json '{"microphone":{"voicePrintEnabled":true},"sendspin":{"enabled":true,"serverUrl":"ws://192.168.1.10:8927"}}'
 * adb shell am broadcast -a com.example.ava.ACTION_APPLY_SETTINGS --es setting_path microphone.voicePrintEnabled --ez setting_bool true
 * adb push ava_headless.json /sdcard/ava_headless.json
 * adb shell am broadcast -a com.example.ava.ACTION_APPLY_SETTINGS --es settings_file /sdcard/ava_headless.json
 * adb shell am broadcast -a com.example.ava.ACTION_SET_MOD_ENABLED --es mod_id echo_dot_led --ez mod_enabled true
 * adb shell am broadcast -a com.example.ava.ACTION_RELOAD_MOD --es mod_id echo_dot_led
 * adb shell am broadcast -a com.example.ava.ACTION_RELOAD_MOD
 * adb shell am broadcast -a com.example.ava.ACTION_EXIT_FULLSCREEN_OVERLAYS
 * adb shell am broadcast -a com.example.ava.ACTION_PRY_GECKO_CHILD -n com.example.ava/.receivers.AvaControlReceiver --es url "http://ha.local:8123/"
 * adb shell am broadcast --user 0 -n com.example.ava.gecko/com.example.ava.GeckoChildControlReceiver -a com.example.ava.gecko.action.SHOW_BROWSER --es url "http://ha.local:8123/"
 * adb shell am broadcast -a com.example.ava.ACTION_SHOW_TOAST -p com.example.ava --es message "Mic muted"
 * adb shell am broadcast -a com.example.ava.ACTION_SHOW_TOAST -p com.example.ava --es message "Downloading engine…" --es tag gecko --ez long true
 * # Long / spaced copy: prefer message_b64 (shell eats spaces inside --es message)
 * MSG=$(printf '%s' '长文带 空格' | base64 | tr -d '\n')
 * adb shell am broadcast -a com.example.ava.ACTION_SHOW_TOAST -p com.example.ava --es message_b64 "$MSG"
 */
class AvaControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AvaControlReceiver"

        const val ACTION_TOGGLE_MIC = "com.example.ava.ACTION_TOGGLE_MIC"
        const val ACTION_MUTE_MIC = "com.example.ava.ACTION_MUTE_MIC"
        const val ACTION_UNMUTE_MIC = "com.example.ava.ACTION_UNMUTE_MIC"
        const val ACTION_WAKE = "com.example.ava.ACTION_WAKE"
        const val ACTION_STOP = "com.example.ava.ACTION_STOP"
        const val ACTION_START_SERVICE = "com.example.ava.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.ava.ACTION_STOP_SERVICE"
        /** Soft Sliver toast preview / automation. Extras: message|text|message_b64, tag?, duration_ms?|long? */
        const val ACTION_SHOW_TOAST = "com.example.ava.ACTION_SHOW_TOAST"
        const val EXTRA_TOAST_MESSAGE = "message"
        const val EXTRA_TOAST_TEXT = "text"
        /** UTF-8 text, Base64 (no wraps). Survives adb/shell spaces that break --es message. */
        const val EXTRA_TOAST_MESSAGE_B64 = "message_b64"
        const val EXTRA_TOAST_TAG = "tag"
        const val EXTRA_TOAST_DURATION_MS = "duration_ms"
        const val EXTRA_TOAST_LONG = "long"

        const val ACTION_GRANT_RECORD_AUDIO = "com.example.ava.ACTION_GRANT_RECORD_AUDIO"
        const val ACTION_GRANT_CAMERA = "com.example.ava.ACTION_GRANT_CAMERA"
        const val ACTION_GRANT_LOCATION = "com.example.ava.ACTION_GRANT_LOCATION"
        const val ACTION_GRANT_BLUETOOTH = "com.example.ava.ACTION_GRANT_BLUETOOTH"
        const val ACTION_GRANT_NOTIFICATIONS = "com.example.ava.ACTION_GRANT_NOTIFICATIONS"
        const val ACTION_GRANT_OVERLAY = "com.example.ava.ACTION_GRANT_OVERLAY"
        const val ACTION_GRANT_WRITE_SETTINGS = "com.example.ava.ACTION_GRANT_WRITE_SETTINGS"
        const val ACTION_GRANT_SECURE_SETTINGS = "com.example.ava.ACTION_GRANT_SECURE_SETTINGS"
        const val ACTION_GRANT_READ_LOGS = "com.example.ava.ACTION_GRANT_READ_LOGS"
        const val ACTION_GRANT_INSTALL_PACKAGES = "com.example.ava.ACTION_GRANT_INSTALL_PACKAGES"
        const val ACTION_ACTIVATE_DEVICE_ADMIN = "com.example.ava.ACTION_ACTIVATE_DEVICE_ADMIN"
        const val ACTION_CHECK_UPDATE = "com.example.ava.ACTION_CHECK_UPDATE"
        const val ACTION_APPLY_SETTINGS = "com.example.ava.ACTION_APPLY_SETTINGS"
        const val ACTION_SET_MOD_ENABLED = "com.example.ava.ACTION_SET_MOD_ENABLED"
        const val ACTION_RELOAD_MOD = "com.example.ava.ACTION_RELOAD_MOD"
        const val ACTION_EXIT_FULLSCREEN_OVERLAYS = FullscreenOverlayEscape.ACTION_EXIT
        /** ADB / Fleet: punch the gecko child SHOW. Extra: url (optional, last SHOW url). */
        const val ACTION_PRY_GECKO_CHILD = "com.example.ava.ACTION_PRY_GECKO_CHILD"

        /* @debug-onboarding — ADB hooks for onboarding flow testing.
         * Use -n to target the component explicitly (required on Android 8+):
         * adb shell am broadcast -n com.example.ava/.receivers.AvaControlReceiver -a com.example.ava.ACTION_ONBOARDING_RESET
         * adb shell am broadcast -n com.example.ava/.receivers.AvaControlReceiver -a com.example.ava.ACTION_ONBOARDING_SET_SCALE --ef scale 1.2
         * adb shell am broadcast -n com.example.ava/.receivers.AvaControlReceiver -a com.example.ava.ACTION_ONBOARDING_SHOW
         * Remove these three constants + their when-branches when debug is no longer needed. */
        const val ACTION_ONBOARDING_RESET = "com.example.ava.ACTION_ONBOARDING_RESET"
        const val ACTION_ONBOARDING_SET_SCALE = "com.example.ava.ACTION_ONBOARDING_SET_SCALE"
        const val ACTION_ONBOARDING_SHOW = "com.example.ava.ACTION_ONBOARDING_SHOW"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun runShell(command: String): Boolean {
            if (ShizukuUtils.isShizukuPermissionGranted()) {
                return ShizukuUtils.executeCommand(command).first == 0
            }
            if (RootUtils.isRootAvailable()) {
                return runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
                }.getOrDefault(false)
            }
            return false
        }

        private fun grantPmPermission(packageName: String, permission: String): Boolean {
            return runShell("pm grant $packageName $permission")
        }

        private fun grantAppOp(packageName: String, op: String): Boolean {
            return runShell("appops set $packageName $op allow")
        }

        private fun grantBluetooth(packageName: String): Boolean {
            if (ShizukuUtils.isShizukuPermissionGranted()) {
                return ShizukuUtils.grantBluetoothPermissions(packageName)
            }
            if (!RootUtils.isRootAvailable()) return false
            val permissions = listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
            )
            return permissions.all { grantPmPermission(packageName, it) }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!AvaControlGate.shouldHandle(context, action)) return
        Log.i(TAG, "Received action: $action")
        
        when (action) {
            ACTION_TOGGLE_MIC -> {
                Log.d(TAG, "Toggling microphone mute state")
                VoiceSatelliteService.toggleMicMute()
            }
            ACTION_MUTE_MIC -> {
                Log.d(TAG, "Muting microphone")
                VoiceSatelliteService.setMicMute(true)
            }
            ACTION_UNMUTE_MIC -> {
                Log.d(TAG, "Unmuting microphone")
                VoiceSatelliteService.setMicMute(false)
            }
            ACTION_WAKE -> {
                Log.d(TAG, "Manual wake triggered")
                VoiceSatelliteService.manualWake()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping voice session")
                VoiceSatelliteService.stopVoiceSession()
            }
            ACTION_START_SERVICE -> {
                Log.d(TAG, "Starting VoiceSatelliteService")
                val serviceIntent = Intent(context, VoiceSatelliteService::class.java)
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service", e)
                }
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping VoiceSatelliteService")
                val serviceIntent = Intent(context, VoiceSatelliteService::class.java)
                context.stopService(serviceIntent)
            }
            ACTION_SHOW_TOAST -> {
                val message = resolveToastMessage(intent)
                if (message.isNullOrBlank()) {
                    Log.w(TAG, "ACTION_SHOW_TOAST missing message / text / message_b64")
                    return
                }
                val tag = intent.getStringExtra(EXTRA_TOAST_TAG)
                val durationMs = resolveToastDurationMs(intent)
                Log.d(
                    TAG,
                    "Showing AvaToast chars=${message.length} message=${message.take(80)} " +
                        "tag=$tag durationMs=$durationMs",
                )
                AvaToast.show(context, message, tag = tag, durationMs = durationMs)
            }
            ACTION_GRANT_RECORD_AUDIO -> runPrivileged(context, action) {
                grantPmPermission(it, "android.permission.RECORD_AUDIO")
            }
            ACTION_GRANT_CAMERA -> runPrivileged(context, action) {
                grantPmPermission(it, "android.permission.CAMERA")
            }
            ACTION_GRANT_LOCATION -> runPrivileged(context, action) {
                ShizukuUtils.grantLocationPermission(it) ||
                    RootUtils.grantLocationPermission(it)
            }
            ACTION_GRANT_BLUETOOTH -> runPrivileged(context, action) {
                grantBluetooth(it)
            }
            ACTION_GRANT_NOTIFICATIONS -> runPrivileged(context, action) {
                grantPmPermission(it, "android.permission.POST_NOTIFICATIONS")
            }
            ACTION_GRANT_OVERLAY -> runPrivileged(context, action) {
                grantAppOp(it, "SYSTEM_ALERT_WINDOW")
            }
            ACTION_GRANT_WRITE_SETTINGS -> runPrivileged(context, action) {
                grantAppOp(it, "WRITE_SETTINGS")
            }
            ACTION_GRANT_SECURE_SETTINGS -> runPrivileged(context, action) {
                grantPmPermission(it, "android.permission.WRITE_SECURE_SETTINGS")
            }
            ACTION_GRANT_READ_LOGS -> runPrivileged(context, action) {
                ShizukuUtils.grantReadLogsPermission(it)
                    || grantPmPermission(it, "android.permission.READ_LOGS")
            }
            ACTION_GRANT_INSTALL_PACKAGES -> runPrivileged(context, action) {
                grantAppOp(it, "REQUEST_INSTALL_PACKAGES")
            }
            ACTION_ACTIVATE_DEVICE_ADMIN -> {
                when {
                    ScreenControlUtils.isDeviceAdminActive(context) ->
                        Log.i(TAG, "Device admin already active")
                    ScreenControlUtils.requestDeviceAdmin(context, forceShellRetry = true) ->
                        Log.i(TAG, "Device admin active")
                    else ->
                        Log.w(TAG, "Device admin UI unavailable on this device")
                }
            }
            ACTION_CHECK_UPDATE -> {
                Log.d(TAG, "Opening update check UI")
                val launch = Intent(context, MainActivity::class.java).apply {
                    setAction(MainActivity.ACTION_SHOW_UPDATE)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                context.startActivity(launch)
            }
            ACTION_APPLY_SETTINGS -> {
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        val result = AvaSettingsApplier.applyFromIntent(
                            context = context.applicationContext,
                            settingsJson = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTINGS_JSON),
                            settingsFile = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTINGS_FILE),
                            settingStore = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTING_STORE),
                            settingKey = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTING_KEY),
                            settingPath = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTING_PATH),
                            settingBool = if (intent.hasExtra(AvaSettingsApplier.EXTRA_SETTING_BOOL)) {
                                intent.getBooleanExtra(AvaSettingsApplier.EXTRA_SETTING_BOOL, false)
                            } else {
                                null
                            },
                            settingInt = if (intent.hasExtra(AvaSettingsApplier.EXTRA_SETTING_INT)) {
                                intent.getIntExtra(AvaSettingsApplier.EXTRA_SETTING_INT, 0)
                            } else {
                                null
                            },
                            settingFloat = if (intent.hasExtra(AvaSettingsApplier.EXTRA_SETTING_FLOAT)) {
                                intent.getFloatExtra(AvaSettingsApplier.EXTRA_SETTING_FLOAT, 0f)
                            } else {
                                null
                            },
                            settingString = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTING_STRING),
                            settingJsonValue = intent.getStringExtra(AvaSettingsApplier.EXTRA_SETTING_JSON_VALUE),
                            noRestart = intent.getBooleanExtra(AvaSettingsApplier.EXTRA_NO_RESTART, false),
                            forceRestartSatellite = if (intent.hasExtra(AvaSettingsApplier.EXTRA_RESTART_SATELLITE)) {
                                intent.getBooleanExtra(AvaSettingsApplier.EXTRA_RESTART_SATELLITE, true)
                            } else {
                                null
                            },
                            forceRestartSendspin = if (intent.hasExtra(AvaSettingsApplier.EXTRA_RESTART_SENDSPIN)) {
                                intent.getBooleanExtra(AvaSettingsApplier.EXTRA_RESTART_SENDSPIN, true)
                            } else {
                                null
                            },
                        )
                        if (result.success) {
                            Log.i(TAG, "ACTION_APPLY_SETTINGS ok: ${result.changedStores.map { it.jsonKey }}")
                        } else {
                            Log.w(TAG, "ACTION_APPLY_SETTINGS failed: ${result.errors}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ACTION_APPLY_SETTINGS error", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_SET_MOD_ENABLED -> {
                val modId = intent.getStringExtra(AvaModControl.EXTRA_MOD_ID)
                val enabled = intent.getBooleanExtra(AvaModControl.EXTRA_MOD_ENABLED, true)
                val noRestart = intent.getBooleanExtra(AvaModControl.EXTRA_NO_RESTART, false)
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        val result = AvaModControl.setModEnabled(
                            context = context.applicationContext,
                            modId = modId,
                            enabled = enabled,
                            noRestart = noRestart,
                        )
                        if (result.success) {
                            Log.i(TAG, "ACTION_SET_MOD_ENABLED ok: mod=$modId enabled=$enabled")
                        } else {
                            Log.w(TAG, "ACTION_SET_MOD_ENABLED failed: ${result.message}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ACTION_SET_MOD_ENABLED error", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_EXIT_FULLSCREEN_OVERLAYS -> {
                Log.i(TAG, "Exiting fullscreen overlays")
                FullscreenOverlayEscape.exitAll(context)
            }
            ACTION_RELOAD_MOD -> {
                val modId = intent.getStringExtra(AvaModControl.EXTRA_MOD_ID)
                val noRestart = intent.getBooleanExtra(AvaModControl.EXTRA_NO_RESTART, false)
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        val result = AvaModControl.reloadMod(
                            context = context.applicationContext,
                            modId = modId,
                            noRestart = noRestart,
                        )
                        if (result.success) {
                            Log.i(TAG, "ACTION_RELOAD_MOD ok: mod=${modId ?: "all enabled"}")
                        } else {
                            Log.w(TAG, "ACTION_RELOAD_MOD failed: ${result.message}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ACTION_RELOAD_MOD error", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_PRY_GECKO_CHILD -> {
                val url = intent.getStringExtra("url")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { BrowserEngine.lastRequestedShowUrl().orEmpty() }
                if (url.isBlank()) {
                    Log.w(TAG, "ACTION_PRY_GECKO_CHILD missing url")
                    return
                }
                Log.i(TAG, "Prying gecko child SHOW url=$url")
                WebViewService.show(context.applicationContext, url)
            }
            BrowserEngine.ACTION_SYNC_BROWSER_VISIBLE -> {
                val visible = intent.getBooleanExtra(BrowserEngine.EXTRA_BROWSER_VISIBLE, false)
                val updateHaSwitch = intent.getBooleanExtra(BrowserEngine.EXTRA_UPDATE_HA_SWITCH, true)
                val token = intent.getLongExtra(BrowserEngine.EXTRA_RECEIPT_TOKEN, 0L)
                val attached = intent.getBooleanExtra(BrowserEngine.EXTRA_RECEIPT_ATTACHED, visible)
                BrowserEngine.noteGeckoOverlayReceipt(token, visible, attached)
                WebViewService.setPeerBrowserOverlayVisible(context, visible && attached)
                if (!updateHaSwitch) {
                    Log.d(TAG, "Peer browser overlay visible=$visible (HA switch unchanged)")
                    return
                }
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        val store = BrowserSettingsStore(context.applicationContext)
                        val settings = store.get()
                        if (settings.enableBrowserVisible != visible) {
                            store.enableBrowserVisible.set(visible)
                            Log.i(TAG, "Synced browser_display HA switch to $visible (from gecko pack)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to sync browser visible from gecko pack", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            BrowserEngine.ACTION_REASSERT_FOREGROUND_OVERLAYS -> {
                // The gecko pack just attached / raised its browser window in
                // another process. That window is now topmost — never let the
                // 250ms throttle drop this pass, or the mic stays under it.
                OverlayZOrderCoordinator.noteWindowAdded()
                OverlayZOrderCoordinator.reassertForegroundOverlaysNow(context)
            }
            HostSidebarSettingsContract.ACTION_REQUEST_SIDEBAR_SETTINGS -> {
                HostSidebarSettingsBridge.pushToGeckoPack(context.applicationContext)
            }
            HostSidebarSettingsContract.ACTION_SIDEBAR_COMMAND -> {
                val command = intent.getStringExtra(HostSidebarSettingsContract.EXTRA_SIDEBAR_COMMAND)
                    ?: return
                val payload = intent.getStringExtra(HostSidebarSettingsContract.EXTRA_SIDEBAR_COMMAND_PAYLOAD)
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        HostSidebarCommandExecutor.execute(context.applicationContext, command, payload)
                    } finally {
                        pending.finish()
                    }
                }
            }
            /* @debug-onboarding — three ADB handlers for onboarding field-tuning.
             * Remove this block (and the constants above) once onboarding is finalised. */
            ACTION_ONBOARDING_RESET -> {
                com.example.ava.ui.screens.onboarding.OnboardingPrefs.resetForDebug(context)
                Log.i(TAG, "Onboarding reset via ADB")
            }
            ACTION_ONBOARDING_SET_SCALE -> {
                val scale = intent.getFloatExtra("scale", 1.0f)
                com.example.ava.ui.screens.onboarding.OnboardingPrefs.setUiScale(context, scale)
                Log.i(TAG, "Onboarding UI scale set to $scale via ADB")
            }
            ACTION_ONBOARDING_SHOW -> {
                com.example.ava.ui.screens.onboarding.OnboardingPrefs.resetForDebug(context)
                Log.i(TAG, "Onboarding reset + relaunch via ADB")
                val launch = Intent(context, MainActivity::class.java).apply {
                    putExtra("navigate_to", "onboarding")
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
                context.startActivity(launch)
            }
            else -> {
                Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    private fun runPrivileged(context: Context, action: String, block: (String) -> Boolean) {
        val packageName = context.packageName
        Thread {
            val ok = block(packageName)
            Log.i(TAG, "$action: ${if (ok) "ok" else "failed"}")
        }.start()
    }

    private fun resolveToastDurationMs(intent: Intent): Long {
        if (intent.hasExtra(EXTRA_TOAST_DURATION_MS)) {
            val asLong = intent.getLongExtra(EXTRA_TOAST_DURATION_MS, 0L)
            if (asLong > 0L) return asLong
            val asInt = intent.getIntExtra(EXTRA_TOAST_DURATION_MS, 0)
            if (asInt > 0) return asInt.toLong()
        }
        if (intent.getBooleanExtra(EXTRA_TOAST_LONG, false)) {
            return AvaToast.LONG_MS
        }
        return 0L
    }

    private fun resolveToastMessage(intent: Intent): String? {
        val b64 = intent.getStringExtra(EXTRA_TOAST_MESSAGE_B64)?.trim().orEmpty()
        if (b64.isNotEmpty()) {
            return runCatching {
                String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            }.onFailure {
                Log.w(TAG, "ACTION_SHOW_TOAST bad message_b64", it)
            }.getOrNull()
        }
        return intent.getStringExtra(EXTRA_TOAST_MESSAGE)
            ?: intent.getStringExtra(EXTRA_TOAST_TEXT)
    }
}
