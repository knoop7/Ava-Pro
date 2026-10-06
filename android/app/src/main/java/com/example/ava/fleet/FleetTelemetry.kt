package com.example.ava.fleet

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.example.ava.mods.ModManager
import com.example.ava.platform.DisplayClockProbe
import com.example.ava.utils.DeviceCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot of real device telemetry for the cluster console.
 *
 * Sources (no invented values):
 * - CPU: `/proc/stat` deltas + `/proc/loadavg`
 * - Memory / storage: [ActivityManager.MemoryInfo], [StatFs]
 * - Battery: [BatteryManager] including [BatteryManager.EXTRA_TEMPERATURE]
 * - Thermal: [PowerManager.getCurrentThermalStatus] + readable thermal_zone temps
 * - Wi‑Fi: RSSI / link speed (same pattern as DiagnosticSensorManager)
 * - Sensors: one-shot light / ambient temp when hardware present
 * - Display clock: HAL vsync vs monotonic ([DisplayClockProbe])
 * - Mods: [ModManager.installedMods] + manifests
 */
object FleetTelemetry {
    private const val TAG = "FleetTelemetry"
    private const val HISTORY_SIZE = 60

    private data class CpuSample(val idle: Long, val total: Long)

    private val lastCpu = AtomicReference<CpuSample?>(null)
    private val cpuHistory = ArrayDeque<Pair<Long, Double>>(HISTORY_SIZE)
    private val memHistory = ArrayDeque<Pair<Long, Double>>(HISTORY_SIZE)

    fun snapshot(context: Context, includeSlowSensors: Boolean = true): JSONObject {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val cpu = readCpuPercent()
        val mem = readMemory(app)
        val storage = readStorage()
        val battery = readBattery(app)
        val wifi = readWifi(app)
        val thermal = readThermal(app)
        val process = readProcess(app)
        val sensors = if (includeSlowSensors) {
            readSensors(app)
        } else {
            JSONObject()
                .put("deferred", true)
                .put("hint", "use /v1/telemetry for sensor one-shot")
        }
        val load = readLoadAvg()

        cpu?.let { pushHistory(cpuHistory, now, it) }
        mem.optDouble("usedPercent", Double.NaN).takeIf { !it.isNaN() }?.let {
            pushHistory(memHistory, now, it)
        }

        return JSONObject()
            .put("ok", true)
            .put("ts", now)
            .put("uptimeMs", SystemClock.elapsedRealtime())
            .put("cpu", cpuJson(cpu, load))
            .put("memory", mem)
            .put("storage", storage)
            .put("battery", battery)
            .put("wifi", wifi)
            .put("thermal", thermal)
            .put("process", process)
            .put("displayClock", DisplayClockProbe.toJson(app))
            .put("sensors", sensors)
            .put("history", historyJson())
            .put("modules", modulesJson(app))
            .put(
                "capabilities",
                JSONObject()
                    .put("ambientTemperatureSensor", DeviceCapabilities.hasTemperatureSensor(app))
                    .put("humiditySensor", DeviceCapabilities.hasHumiditySensor(app))
                    .put("pressureSensor", DeviceCapabilities.hasPressureSensor(app)),
            )
    }

    private fun cpuJson(percent: Double?, load: Triple<Double, Double, Double>?): JSONObject {
        val o = JSONObject()
            .put("percent", percent ?: JSONObject.NULL)
            .put("source", if (percent != null) "proc_stat" else JSONObject.NULL)
        if (load != null) {
            o.put(
                "loadavg",
                JSONObject()
                    .put("m1", load.first)
                    .put("m5", load.second)
                    .put("m15", load.third),
            )
        }
        return o
    }

    private fun historyJson(): JSONObject {
        fun series(q: ArrayDeque<Pair<Long, Double>>): JSONArray {
            val arr = JSONArray()
            synchronized(q) {
                q.forEach { (ts, v) ->
                    arr.put(JSONObject().put("t", ts).put("v", v))
                }
            }
            return arr
        }
        return JSONObject()
            .put("cpuPercent", series(cpuHistory))
            .put("memoryUsedPercent", series(memHistory))
            .put("maxPoints", HISTORY_SIZE)
    }

    private fun pushHistory(q: ArrayDeque<Pair<Long, Double>>, ts: Long, value: Double) {
        synchronized(q) {
            q.addLast(ts to value)
            while (q.size > HISTORY_SIZE) q.removeFirst()
        }
    }

    /** First call returns null (needs a prior sample); later calls return 0–100. */
    private fun readCpuPercent(): Double? {
        val sample = readProcStat() ?: return null
        val prev = lastCpu.getAndSet(sample) ?: return null
        val idleDelta = (sample.idle - prev.idle).coerceAtLeast(0)
        val totalDelta = (sample.total - prev.total).coerceAtLeast(0)
        if (totalDelta == 0L) return null
        val busy = 1.0 - (idleDelta.toDouble() / totalDelta.toDouble())
        return (busy * 100.0).coerceIn(0.0, 100.0).let { (it * 10).toInt() / 10.0 }
    }

    private fun readProcStat(): CpuSample? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            // cpu user nice system idle iowait irq softirq steal ...
            if (!line.startsWith("cpu ")) return null
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) return null
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return null
            val idle = nums[3] + nums.getOrElse(4) { 0L } // idle + iowait
            val total = nums.sum()
            CpuSample(idle = idle, total = total)
        } catch (e: Exception) {
            Log.d(TAG, "proc/stat: ${e.message}")
            null
        }
    }

    private fun readLoadAvg(): Triple<Double, Double, Double>? {
        return try {
            val line = File("/proc/loadavg").bufferedReader().use { it.readLine() } ?: return null
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return null
            Triple(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
        } catch (_: Exception) {
            null
        }
    }

    private fun readMemory(context: Context): JSONObject {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val used = info.totalMem - info.availMem
            val usedPercent = if (info.totalMem > 0) {
                (used.toDouble() / info.totalMem.toDouble() * 1000).toInt() / 10.0
            } else {
                0.0
            }
            JSONObject()
                .put("totalBytes", info.totalMem)
                .put("availBytes", info.availMem)
                .put("usedBytes", used)
                .put("usedPercent", usedPercent)
                .put("lowMemory", info.lowMemory)
                .put("thresholdBytes", info.threshold)
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }
    }

    private fun readStorage(): JSONObject {
        return try {
            val path = Environment.getDataDirectory().path
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - avail
            val usedPercent = if (total > 0) {
                (used.toDouble() / total.toDouble() * 1000).toInt() / 10.0
            } else {
                0.0
            }
            JSONObject()
                .put("path", path)
                .put("totalBytes", total)
                .put("availBytes", avail)
                .put("usedBytes", used)
                .put("usedPercent", usedPercent)
                .put("freeGb", ((avail / (1024.0 * 1024.0 * 1024.0)) * 10).toInt() / 10.0)
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }
    }

    private fun readBattery(context: Context): JSONObject {
        val o = JSONObject()
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level >= 0) o.put("levelPercent", level)

            @Suppress("UnspecifiedRegisterReceiverFlag")
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (voltage > 0) o.put("voltageV", voltage / 1000.0)
                // Tenths of a degree Celsius — real Android field, previously unused by DiagnosticSensorManager.
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tempTenths != Int.MIN_VALUE) {
                    o.put("temperatureC", tempTenths / 10.0)
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                o.put("charging", charging)
                o.put(
                    "chargeSource",
                    when {
                        !charging -> "none"
                        plugged == BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                        plugged == BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                        plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                        else -> "charging"
                    },
                )
            }
        } catch (e: Exception) {
            o.put("error", e.message)
        }
        return o
    }

    private fun readWifi(context: Context): JSONObject {
        val o = JSONObject()
        try {
            var rssi = Int.MIN_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    val s = caps.signalStrength
                    if (s != Int.MIN_VALUE) rssi = s
                }
            }
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = wifi?.connectionInfo
            if (rssi == Int.MIN_VALUE) {
                @Suppress("DEPRECATION")
                rssi = info?.rssi ?: -100
            }
            o.put("rssiDbm", rssi)
            @Suppress("DEPRECATION")
            val link = info?.linkSpeed ?: -1
            if (link >= 0) o.put("linkSpeedMbps", link)
            @Suppress("DEPRECATION")
            val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            if (ssid != null) o.put("ssid", ssid)
        } catch (e: Exception) {
            o.put("error", e.message)
        }
        return o
    }

    private fun readThermal(context: Context): JSONObject {
        val o = JSONObject()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val status = pm.currentThermalStatus
                o.put("status", status)
                o.put(
                    "statusLabel",
                    when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> "none"
                        PowerManager.THERMAL_STATUS_LIGHT -> "light"
                        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                        else -> "unknown"
                    },
                )
            }
            val zones = JSONArray()
            val dir = File("/sys/class/thermal")
            if (dir.isDirectory) {
                dir.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.sortedBy { it.name }
                    ?.take(8)
                    ?.forEach { zone ->
                        val tempFile = File(zone, "temp")
                        val typeFile = File(zone, "type")
                        if (!tempFile.canRead()) return@forEach
                        val raw = tempFile.readText().trim().toLongOrNull() ?: return@forEach
                        // Usually millidegrees C
                        val celsius = when {
                            raw > 1000 -> raw / 1000.0
                            else -> raw.toDouble()
                        }
                        zones.put(
                            JSONObject()
                                .put("name", zone.name)
                                .put("type", typeFile.takeIf { it.canRead() }?.readText()?.trim().orEmpty())
                                .put("celsius", (celsius * 10).toInt() / 10.0),
                        )
                    }
            }
            if (zones.length() > 0) o.put("zones", zones)
        } catch (e: Exception) {
            o.put("error", e.message)
        }
        return o
    }

    private fun readProcess(context: Context): JSONObject {
        return try {
            val rt = Runtime.getRuntime()
            val pssKb = Debug.getPss()
            JSONObject()
                .put("pid", android.os.Process.myPid())
                .put("pssKb", pssKb)
                .put("javaHeapUsedBytes", rt.totalMemory() - rt.freeMemory())
                .put("javaHeapMaxBytes", rt.maxMemory())
                .put("packageName", context.packageName)
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }
    }

    private fun readSensors(context: Context): JSONObject {
        val o = JSONObject()
            .put("hasLight", true) // filled below
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return o.put("error", "no_sensor_manager")
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val ambient = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        o.put("hasLight", light != null)
        o.put("hasAmbientTemperature", ambient != null)
        light?.let { oneShotSensor(sm, it, 120)?.let { v -> o.put("lightLux", (v * 10).toInt() / 10.0) } }
        ambient?.let { oneShotSensor(sm, it, 120)?.let { v -> o.put("ambientTemperatureC", (v * 10).toInt() / 10.0) } }
        return o
    }

    private fun oneShotSensor(sm: SensorManager, sensor: Sensor, timeoutMs: Long): Float? {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Float?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val v = event?.values?.firstOrNull() ?: return
                value.compareAndSet(null, v)
                latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            value.get()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { sm.unregisterListener(listener) }
        }
    }

    private fun modulesJson(context: Context): JSONArray {
        val arr = JSONArray()
        return try {
            val mm = ModManager.getInstance(context)
            mm.installedMods.value.forEach { mod ->
                val manifest = mm.getCachedManifest(mod.id)
                val missing = runCatching { mm.getMissingPermissions(mod.id).size }.getOrDefault(0)
                val hasUpdate = runCatching { mm.hasUpdate(mod.id) }.getOrDefault(false)
                arr.put(
                    JSONObject()
                        .put("id", mod.id)
                        .put("name", manifest?.name?.ifBlank { mod.id } ?: mod.id)
                        .put("version", mod.version.ifBlank { manifest?.version.orEmpty() })
                        .put("enabled", mod.enabled)
                        .put("author", manifest?.author.orEmpty())
                        .put("description", manifest?.description.orEmpty())
                        .put("icon", manifest?.icon.orEmpty())
                        .put("hasUpdate", hasUpdate)
                        .put("missingPermissions", missing)
                        .put("installedAt", mod.installedAt),
                )
            }
            arr
        } catch (e: Exception) {
            Log.w(TAG, "modules: ${e.message}")
            arr
        }
    }
}
