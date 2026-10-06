package com.example.ava.fleet

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceProtocol
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds read-only JSON for the cluster console (/v1/hello, /v1/status, SSE).
 * Settings writes remain gated until auth lands.
 */
object FleetStatusBuilder {
    const val PROTOCOL_VERSION = 1

    fun hello(context: Context, port: Int): String {
        val snapshot = snapshot(context, port)
        return JSONObject()
            .put("ok", true)
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("product", "ava-cluster")
            .put("identity", "ava")
            .put("deviceId", snapshot.optString("deviceId"))
            .put("deviceName", snapshot.optString("deviceName"))
            .put("appVersion", snapshot.optString("appVersion"))
            .put("port", port)
            .put("capabilities", capabilities())
            .put("auth", FleetAuth.statusJson(context))
            .toString()
    }

    fun status(context: Context, port: Int): String = snapshot(context, port).toString()

    private fun capabilities() = JSONArray().apply {
        put("status.read")
        put("events.sse")
        put("discovery.udp")
        put("telemetry.read")
        put("modules.read")
        put("screen.frame")
        put("screen.oneshot")
        put("screen.mjpeg")
        put("screen.scrcpy")
        put("input.tap")
        put("shell.exec")
        put("console.skeleton")
        put("settings.read")
        put("settings.write")
        put("settings.export")
        put("settings.import")
        put("cluster.probe")
        put("cluster.screen")
        put("cluster.transfer")
        put("cluster.shell")
        put("logs.read")
        put("incidents.read")
        put("cluster.pull-logs")
        put("shell.exec")
        put("cluster.shell")
        put("auth.token")
    }

    private fun snapshot(context: Context, port: Int): JSONObject {
        val ip = FleetNetwork.getLocalIpAddress(context)
        val accessUrl = FleetNetwork.buildAccessUrl(ip, port)
        val voice = runCatching {
            runBlocking { VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).get() }
        }.getOrNull()
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        val satelliteState = if (VoiceSatelliteService.isSatelliteStarted()) {
            when (VoiceSatelliteService.getInstance()?.getState()) {
                is Disconnected -> "disconnected"
                is Connected -> "running"
                is ServerError -> "error"
                is Stopped -> "stopped"
                else -> "running"
            }
        } else {
            "stopped"
        }
        val a11y = AccessibilityBridge.isServiceConnected()
        val scrcpy = FleetScrcpyServer.statusJson(context)
        val shot = FleetScreenShot.statusJson()
        val canShellCapture = scrcpy.optBoolean("canLaunch", false) || shot.optBoolean("canShellCapture", false)
        val canA11yCapture = shot.optBoolean("canAccessibilityCapture", false)
        val canScreen = canShellCapture || canA11yCapture
        val display = FleetScreenCoords.realDisplaySize(context)
        val displayW = display.width
        val displayH = display.height
        val deviceId = AvaVoiceDiscovery.localId().ifBlank {
            AvaVoiceDiscovery.resolveLocalDeviceId(context)
        }
        val devices = FleetDeviceDirectory.devicesArray(context, port)

        return JSONObject()
            .put("ok", true)
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("product", "ava-cluster")
            .put("identity", "ava")
            .put("deviceId", deviceId)
            .put("local", true)
            .put("deviceName", voice?.name?.ifBlank { null } ?: Build.MODEL.orEmpty())
            .put("ip", ip ?: "")
            .put("port", port)
            .put("accessUrl", accessUrl)
            .put("appVersion", appVersion)
            .put("androidVersion", "Android ${Build.VERSION.RELEASE ?: "?"} (SDK ${Build.VERSION.SDK_INT})")
            .put("model", Build.MODEL.orEmpty())
            .put("manufacturer", Build.MANUFACTURER.orEmpty())
            .put("voicePort", voice?.serverPort ?: JSONObject.NULL)
            .put("voiceSatellite", satelliteState)
            .put("uptimeMs", SystemClock.elapsedRealtime())
            .put("capabilities", capabilities())
            .put(
                "discovery",
                JSONObject()
                    .put("protocol", "ava-voice-udp")
                    .put("port", AvaVoiceProtocol.PORT)
                    .put("running", AvaVoiceDiscovery.isRunning())
                    .put("advertisedClusterPort", AvaVoiceDiscovery.advertisedClusterPort()),
            )
            .put(
                "screen",
                JSONObject()
                    .put("available", canScreen)
                    .put(
                        "mode",
                        when {
                            FleetScrcpyBridge.isActive() -> "scrcpy"
                            shot.optBoolean("hasShot", false) -> "oneshot"
                            canShellCapture -> "oneshot-ready"
                            canA11yCapture -> "accessibility-oneshot"
                            else -> "unavailable"
                        },
                    )
                    .put("displayWidth", displayW)
                    .put("displayHeight", displayH)
                    .put("tapViaAccessibility", a11y)
                    // Any privileged shell → we can `input tap`, no a11y required.
                    .put("tapViaShell", FleetShell.backend() != null)
                    .put("tapAvailable", a11y || FleetShell.backend() != null)
                    .put("captureViaAccessibility", canA11yCapture)
                    .put("captureViaShell", canShellCapture)
                    .put(
                        "accessibility",
                        runCatching { JSONObject(AccessibilityBridge.getStatus(context)) }
                            .getOrElse { JSONObject().put("connected", a11y) },
                    )
                    .put("oneshot", shot)
                    .put("scrcpy", scrcpy),
            )
            .put("shell", FleetShell.statusJson())
            .put("auth", FleetAuth.statusJson(context))
            .put("devices", devices)
            .put("deviceCount", devices.length())
            .put("telemetry", FleetTelemetry.snapshot(context, includeSlowSensors = false))
            .put("consoleSource", consoleSource(context))
            .put("ts", System.currentTimeMillis())
    }

    private fun consoleSource(context: Context): String {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val hot = File(base, "fleet-console/index.html")
        return if (hot.isFile) "hot" else "bundled"
    }
}
