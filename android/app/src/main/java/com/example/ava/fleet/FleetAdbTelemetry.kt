package com.example.ava.fleet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer telemetry via hub wireless ADB — best-effort shell probes shaped like [FleetTelemetry].
 *
 * Not a full SensorManager path: light/ambient are usually unavailable over ADB.
 * Modules / Ava process metrics stay local-only.
 *
 * Modes:
 * - [lite]: getprop + uptime only (display identity; cheap, soft-cached)
 * - full / deep: dumpsys + cpu sample (user-triggered "Refresh sensors")
 */
object FleetAdbTelemetry {
    private const val LITE_CACHE_MS = 90_000L
    private val liteCache = ConcurrentHashMap<String, Pair<JSONObject, Long>>()

    private val SCRIPT_LITE = """
echo '===ID==='
getprop ro.product.manufacturer
getprop ro.product.model
getprop ro.product.brand
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.product.device
echo '===UP==='
cat /proc/uptime 2>/dev/null
echo '===END==='
""".trimIndent()

    private val SCRIPT = """
echo '===ID==='
getprop ro.product.manufacturer
getprop ro.product.model
getprop ro.product.brand
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.product.device
echo '===UP==='
cat /proc/uptime 2>/dev/null
echo '===BAT==='
dumpsys battery 2>/dev/null | head -n 80
echo '===MEM==='
cat /proc/meminfo 2>/dev/null | head -n 12
echo '===LOAD==='
cat /proc/loadavg 2>/dev/null
echo '===CPU1==='
head -n 1 /proc/stat 2>/dev/null
sleep 0.4
echo '===CPU2==='
head -n 1 /proc/stat 2>/dev/null
echo '===DF==='
df -k /data 2>/dev/null | tail -n 1
echo '===THERM==='
for z in /sys/class/thermal/thermal_zone*/temp; do if [ -r "${'$'}z" ]; then echo "${'$'}z:$(cat "${'$'}z" 2>/dev/null)"; fi; done | head -n 16
echo '===WIFI==='
dumpsys wifi 2>/dev/null | grep -iE 'mRssi|RSSI:|Link speed|SSID:' | head -n 20
echo '===END==='
""".trimIndent()

    fun snapshot(
        context: Context,
        serial: String,
        deep: Boolean = false,
        lite: Boolean = false,
        uptimeOnly: Boolean = false,
    ): JSONObject {
        val key = serial.trim()
        if (key.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "missing_serial")
        }
        if (uptimeOnly && !deep) {
            return readUptimeOnly(context, key)
        }
        if (lite && !deep) {
            return identityLite(context, key)
        }
        return fullSnapshot(context, key, deep)
    }

    /**
     * Wall OSD: single `cat /proc/uptime` — world-readable, no root, soft-cached.
     */
    fun readUptimeOnly(context: Context, serial: String): JSONObject {
        val key = serial.trim()
        if (key.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "missing_serial")
        }
        val now = System.currentTimeMillis()
        liteCache[key]?.let { (cached, at) ->
            if (now - at < LITE_CACHE_MS && cached.optBoolean("ok", false) && !cached.isNull("uptimeMs")) {
                return JSONObject()
                    .put("ok", true)
                    .put("source", "adb")
                    .put("serial", key)
                    .put("uptimeOnly", true)
                    .put("cached", true)
                    .put("ts", cached.optLong("ts", at))
                    .put("uptimeMs", cached.opt("uptimeMs"))
            }
        }
        val res = FleetAdbHost.shell(context, "cat /proc/uptime", key, timeoutSec = 8)
        val raw = res.stdout.ifBlank { res.stderr }
        val uptimeMs = parseUptimeMs(raw)
        if (uptimeMs == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", res.error ?: "uptime_unavailable")
                .put("code", res.code)
                .put("stderr", res.stderr.take(200))
                .put("serial", key)
                .put("source", "adb")
                .put("uptimeOnly", true)
        }
        val out = JSONObject()
            .put("ok", true)
            .put("source", "adb")
            .put("serial", key)
            .put("uptimeOnly", true)
            .put("lite", true)
            .put("ts", now)
            .put("uptimeMs", uptimeMs)
            .put("identity", JSONObject())
            .put("modules", JSONArray())
            .put("capabilities", JSONObject().put("viaAdb", true).put("uptimeOnly", true))
        // Keep / refresh lite cache entry so params identity can reuse soft window.
        val prev = liteCache[key]?.first
        if (prev != null && prev.optBoolean("ok", false)) {
            prev.put("uptimeMs", uptimeMs)
            prev.put("ts", now)
            liteCache[key] = prev to now
        } else {
            liteCache[key] = out to now
        }
        return JSONObject(out.toString())
    }

    /** Display-only: manufacturer / model / Android / uptime. Soft-cached 90s. */
    fun identityLite(context: Context, serial: String): JSONObject {
        val key = serial.trim()
        if (key.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "missing_serial")
        }
        val now = System.currentTimeMillis()
        liteCache[key]?.let { (cached, at) ->
            if (now - at < LITE_CACHE_MS && cached.optBoolean("ok", false)) {
                return JSONObject(cached.toString()).put("cached", true)
            }
        }
        val quoted = "'" + SCRIPT_LITE.replace("'", "'\\''") + "'"
        val res = FleetAdbHost.shell(context, "sh -c $quoted", key, timeoutSec = 12)
        if (!res.ok && res.stdout.isBlank()) {
            // Prefer last lite identity over empty dashes when ADB blips.
            liteCache[key]?.let { (cached, at) ->
                if (now - at < LITE_CACHE_MS * 3 && cached.optBoolean("ok", false)) {
                    return JSONObject(cached.toString()).put("cached", true).put("stale", true)
                }
            }
            return JSONObject()
                .put("ok", false)
                .put("error", res.error ?: "adb_shell_failed")
                .put("code", res.code)
                .put("stderr", res.stderr.take(400))
                .put("serial", key)
                .put("source", "adb")
                .put("lite", true)
        }
        val sections = parseSections(res.stdout)
        val identity = parseIdentity(sections["ID"].orEmpty())
        val uptimeMs = parseUptimeMs(sections["UP"].orEmpty())
        val out = JSONObject()
            .put("ok", true)
            .put("source", "adb")
            .put("serial", key)
            .put("lite", true)
            .put("ts", now)
            .put("uptimeMs", uptimeMs ?: JSONObject.NULL)
            .put("identity", identity)
            .put(
                "sensors",
                JSONObject()
                    .put("deferred", true)
                    .put("hint", "metrics_via_refresh_sensors"),
            )
            .put("modules", JSONArray())
            .put(
                "capabilities",
                JSONObject().put("viaAdb", true).put("lite", true),
            )
        liteCache[key] = out to now
        return JSONObject(out.toString())
    }

    private fun fullSnapshot(context: Context, key: String, deep: Boolean): JSONObject {
        val quoted = "'" + SCRIPT.replace("'", "'\\''") + "'"
        val res = FleetAdbHost.shell(context, "sh -c $quoted", key, timeoutSec = 30)
        if (!res.ok && res.stdout.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", res.error ?: "adb_shell_failed")
                .put("code", res.code)
                .put("stderr", res.stderr.take(400))
                .put("serial", key)
                .put("source", "adb")
        }
        val sections = parseSections(res.stdout)
        val now = System.currentTimeMillis()
        val identity = parseIdentity(sections["ID"].orEmpty())
        val uptimeMs = parseUptimeMs(sections["UP"].orEmpty())
        val battery = parseBattery(sections["BAT"].orEmpty())
        val memory = parseMeminfo(sections["MEM"].orEmpty())
        val load = parseLoadavg(sections["LOAD"].orEmpty())
        val cpuPct = parseCpuPercent(sections["CPU1"].orEmpty(), sections["CPU2"].orEmpty())
        val storage = parseDf(sections["DF"].orEmpty())
        val thermal = parseThermal(sections["THERM"].orEmpty())
        val wifi = parseWifi(sections["WIFI"].orEmpty())

        val sensors = if (deep) {
            JSONObject()
                .put("deferred", false)
                .put("available", false)
                .put("hint", "sensors_not_via_adb")
        } else {
            JSONObject()
                .put("deferred", true)
                .put("hint", "use deep=1; sensors usually unavailable via adb")
        }

        val out = JSONObject()
            .put("ok", true)
            .put("source", "adb")
            .put("serial", key)
            .put("lite", false)
            .put("ts", now)
            .put("uptimeMs", uptimeMs ?: JSONObject.NULL)
            .put("identity", identity)
            .put(
                "cpu",
                JSONObject()
                    .put("percent", cpuPct ?: JSONObject.NULL)
                    .put("source", if (cpuPct != null) "proc_stat_adb" else JSONObject.NULL)
                    .put(
                        "loadavg",
                        if (load != null) {
                            JSONObject().put("m1", load.first).put("m5", load.second).put("m15", load.third)
                        } else {
                            JSONObject.NULL
                        },
                    ),
            )
            .put("memory", memory)
            .put("storage", storage)
            .put("battery", battery)
            .put("wifi", wifi)
            .put("thermal", thermal)
            .put("process", JSONObject())
            .put("sensors", sensors)
            .put(
                "history",
                JSONObject()
                    .put("cpuPercent", JSONArray())
                    .put("memoryUsedPercent", JSONArray()),
            )
            .put("modules", JSONArray())
            .put(
                "capabilities",
                JSONObject()
                    .put("ambientTemperatureSensor", false)
                    .put("humiditySensor", false)
                    .put("pressureSensor", false)
                    .put("viaAdb", true),
            )
        // Keep lite identity warm after a full pull.
        liteCache[key] = JSONObject()
            .put("ok", true)
            .put("source", "adb")
            .put("serial", key)
            .put("lite", true)
            .put("ts", now)
            .put("uptimeMs", uptimeMs ?: JSONObject.NULL)
            .put("identity", identity)
            .put("sensors", JSONObject().put("deferred", true))
            .put("modules", JSONArray())
            .put("capabilities", JSONObject().put("viaAdb", true).put("lite", true)) to now
        return out
    }

    private fun parseSections(raw: String): Map<String, String> {
        val out = linkedMapOf<String, StringBuilder>()
        var cur: String? = null
        for (line in raw.lineSequence()) {
            val m = Regex("^===([A-Z0-9]+)===$").find(line.trim())
            if (m != null) {
                cur = m.groupValues[1]
                out.getOrPut(cur) { StringBuilder() }
                continue
            }
            val key = cur ?: continue
            out[key]?.append(line)?.append('\n')
        }
        return out.mapValues { it.value.toString().trim() }
    }

    private fun parseIdentity(block: String): JSONObject {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return JSONObject()
            .put("manufacturer", lines.getOrNull(0).orEmpty())
            .put("model", lines.getOrNull(1).orEmpty())
            .put("brand", lines.getOrNull(2).orEmpty())
            .put("androidVersion", lines.getOrNull(3)?.let { "Android $it" }.orEmpty())
            .put("sdk", lines.getOrNull(4)?.toIntOrNull() ?: JSONObject.NULL)
            .put("device", lines.getOrNull(5).orEmpty())
    }

    private fun parseUptimeMs(block: String): Long? {
        // Prefer first line that starts with a digit (ignore adb/shell noise).
        val line = block.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it[0].isDigit() }
            ?: return null
        val first = line.substringBefore(' ').toDoubleOrNull() ?: return null
        if (first < 0) return null
        return (first * 1000.0).toLong()
    }

    private fun parseBattery(block: String): JSONObject {
        fun intField(name: String): Int? =
            Regex("""(?im)^\s*$name\s*:\s*(-?\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()

        val level = intField("level")
        val tempTenths = intField("temperature")
        val voltageMv = intField("voltage")
        val status = intField("status")
        val plugged = intField("plugged")
        val charging = when {
            status == 2 || status == 5 -> true
            plugged != null && plugged > 0 -> true
            else -> false
        }
        val chargeSource = when (plugged) {
            1 -> "ac"
            2 -> "usb"
            4 -> "wireless"
            else -> if (charging) "unknown" else "none"
        }
        return JSONObject()
            .put("levelPercent", level ?: JSONObject.NULL)
            .put("temperatureC", tempTenths?.let { it / 10.0 } ?: JSONObject.NULL)
            .put("voltageV", voltageMv?.let { it / 1000.0 } ?: JSONObject.NULL)
            .put("charging", charging)
            .put("chargeSource", chargeSource)
    }

    private fun parseMeminfo(block: String): JSONObject {
        fun kb(name: String): Long? =
            Regex("""(?im)^$name:\s+(\d+)\s*kB""").find(block)?.groupValues?.get(1)?.toLongOrNull()

        val totalKb = kb("MemTotal")
        val availKb = kb("MemAvailable") ?: kb("MemFree")
        if (totalKb == null || totalKb <= 0L) {
            return JSONObject()
        }
        val total = totalKb * 1024L
        val avail = (availKb ?: 0L) * 1024L
        val used = (total - avail).coerceAtLeast(0L)
        val usedPct = used.toDouble() * 100.0 / total.toDouble()
        return JSONObject()
            .put("totalBytes", total)
            .put("availBytes", avail)
            .put("usedBytes", used)
            .put("usedPercent", (usedPct * 10).toInt() / 10.0)
            .put("lowMemory", JSONObject.NULL)
    }

    private fun parseLoadavg(block: String): Triple<Double, Double, Double>? {
        val parts = block.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val a = parts[0].toDoubleOrNull() ?: return null
        val b = parts[1].toDoubleOrNull() ?: return null
        val c = parts[2].toDoubleOrNull() ?: return null
        return Triple(a, b, c)
    }

    private fun parseCpuLine(line: String): Pair<Long, Long>? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || !parts[0].startsWith("cpu") || parts.size < 5) return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val idle = nums.getOrNull(3) ?: 0L
        val total = nums.sum()
        return idle to total
    }

    private fun parseCpuPercent(first: String, second: String): Double? {
        val a = parseCpuLine(first.lineSequence().firstOrNull().orEmpty()) ?: return null
        val b = parseCpuLine(second.lineSequence().firstOrNull().orEmpty()) ?: return null
        val dIdle = b.first - a.first
        val dTotal = b.second - a.second
        if (dTotal <= 0L) return null
        val busy = 1.0 - (dIdle.toDouble() / dTotal.toDouble())
        return ((busy * 1000.0).toInt() / 10.0).coerceIn(0.0, 100.0)
    }

    private fun parseDf(block: String): JSONObject {
        val parts = block.trim().split(Regex("\\s+"))
        if (parts.size < 5) return JSONObject()
        val totalK = parts.getOrNull(1)?.toLongOrNull() ?: return JSONObject()
        val usedK = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val availK = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val usedPct = parts.getOrNull(4)?.removeSuffix("%")?.toDoubleOrNull()
            ?: if (totalK > 0) usedK * 100.0 / totalK else null
        return JSONObject()
            .put("totalBytes", totalK * 1024L)
            .put("usedBytes", usedK * 1024L)
            .put("freeBytes", availK * 1024L)
            .put("freeGb", ((availK / 1024.0 / 1024.0) * 10).toInt() / 10.0)
            .put("usedPercent", usedPct ?: JSONObject.NULL)
            .put("path", parts.getOrNull(5) ?: "/data")
    }

    private fun parseThermal(block: String): JSONObject {
        val zones = JSONArray()
        for (line in block.lineSequence()) {
            val idx = line.lastIndexOf(':')
            if (idx <= 0) continue
            val path = line.substring(0, idx)
            val raw = line.substring(idx + 1).trim().toLongOrNull() ?: continue
            val celsius = when {
                raw > 1000 -> raw / 1000.0
                else -> raw.toDouble()
            }
            if (celsius < -40 || celsius > 120) continue
            val name = path.substringAfterLast('/').ifBlank { path }
            zones.put(
                JSONObject()
                    .put("name", name)
                    .put("type", name)
                    .put("celsius", (celsius * 10).toInt() / 10.0),
            )
        }
        return JSONObject()
            .put("status", JSONObject.NULL)
            .put("statusLabel", if (zones.length() > 0) "adb" else JSONObject.NULL)
            .put("zones", zones)
    }

    private fun parseWifi(block: String): JSONObject {
        val rssi = Regex("""(?i)(?:mRssi|RSSI)\s*[:=]\s*(-?\d+)""")
            .find(block)?.groupValues?.get(1)?.toIntOrNull()
        val speed = Regex("""(?i)Link speed\s*[:=]\s*(\d+)""")
            .find(block)?.groupValues?.get(1)?.toIntOrNull()
        val ssid = Regex("""(?i)SSID:\s*"?([^"\n]+)"?""")
            .find(block)?.groupValues?.get(1)?.trim()
        return JSONObject()
            .put("rssiDbm", rssi ?: JSONObject.NULL)
            .put("linkSpeedMbps", speed ?: JSONObject.NULL)
            .put("ssid", ssid ?: JSONObject.NULL)
    }
}
