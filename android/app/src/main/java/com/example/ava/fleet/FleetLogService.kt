package com.example.ava.fleet

import android.content.Context
import android.os.Process
import android.util.Log
import com.example.ava.crash.AvaIncidentLog
import com.example.ava.voice.AvaVoiceDiscovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads **this Ava process** logcat dump for the cluster console.
 *
 * Hard limits:
 * - at most [MAX_LINES] lines per response
 * - min [MIN_INTERVAL_MS] between real logcat executions (cached otherwise)
 */
object FleetLogService {
    private const val TAG = "FleetLogService"
    const val MAX_LINES = 50
    const val MIN_INTERVAL_MS = 1_500L

    private val lastFetchAt = AtomicLong(0L)
    private val cachedPayload = AtomicReference<JSONObject?>(null)

    fun snapshot(
        context: Context,
        limit: Int = MAX_LINES,
        force: Boolean = false,
    ): JSONObject {
        val capped = limit.coerceIn(1, MAX_LINES)
        val now = System.currentTimeMillis()
        val last = lastFetchAt.get()
        val age = now - last
        if (!force && last > 0L && age < MIN_INTERVAL_MS) {
            val cached = cachedPayload.get()
            if (cached != null) {
                return JSONObject(cached.toString())
                    .put("throttled", true)
                    .put("retryAfterMs", (MIN_INTERVAL_MS - age).coerceAtLeast(0L))
                    .put("limit", capped)
            }
        }

        val lines = readLogcat(capped)
        // Abnormal-end records ride along on the request the console already
        // makes: no extra endpoint, no push, no polling of its own. Empty while
        // the device-side switch is off.
        val incidentsEnabled = AvaIncidentLog.exportEnabled
        val incidents = JSONArray()
        if (incidentsEnabled) {
            runCatching { AvaIncidentLog.snapshotJson(context) }
                .getOrDefault(emptyList())
                .forEach { incidents.put(it) }
        }
        val deviceId = AvaVoiceDiscovery.localId().ifBlank {
            AvaVoiceDiscovery.resolveLocalDeviceId(context)
        }
        val deviceName = AvaVoiceDiscovery.localName().ifBlank {
            android.os.Build.MODEL.orEmpty()
        }
        val payload = JSONObject()
            .put("ok", true)
            .put("throttled", false)
            .put("retryAfterMs", 0)
            .put("source", "logcat")
            .put("scope", "ava-process")
            .put("pid", Process.myPid())
            .put("packageName", context.packageName)
            .put("deviceId", deviceId)
            .put("deviceName", deviceName)
            .put("limit", capped)
            .put("count", lines.length())
            .put("minIntervalMs", MIN_INTERVAL_MS)
            .put("ts", now)
            .put("lines", lines)
            .put("incidentsEnabled", incidentsEnabled)
            .put("incidents", incidents)

        lastFetchAt.set(now)
        cachedPayload.set(JSONObject(payload.toString()))
        return payload
    }

    private fun readLogcat(limit: Int): JSONArray {
        val pid = Process.myPid()
        // Dump only this process; -t N returns the last N lines.
        val cmd = arrayOf(
            "logcat",
            "-d",
            "--pid=$pid",
            "-t", limit.toString(),
            "-v", "threadtime",
        )
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val out = BufferedReader(InputStreamReader(process.inputStream))
            val err = BufferedReader(InputStreamReader(process.errorStream))
            val raw = mutableListOf<String>()
            out.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) raw += line
                }
            }
            err.use { reader ->
                // Drain stderr so the process cannot block on a full pipe.
                while (reader.readLine() != null) { /* discard */ }
            }
            val code = process.waitFor()
            if (code != 0 && raw.isEmpty()) {
                Log.w(TAG, "logcat exited $code; trying uid-filtered dump")
                return readLogcatFallback(limit)
            }
            toLineArray(raw.takeLast(limit))
        } catch (e: Exception) {
            Log.w(TAG, "logcat --pid failed: ${e.message}")
            readLogcatFallback(limit)
        }
    }

    /**
     * Fallback when `--pid` is unsupported: dump a small buffer and keep Ava-looking lines.
     */
    private fun readLogcatFallback(limit: Int): JSONArray {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", (limit * 4).coerceAtMost(200).toString(), "-v", "threadtime"),
            )
            val out = BufferedReader(InputStreamReader(process.inputStream))
            val raw = mutableListOf<String>()
            out.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank() && looksLikeAva(line)) raw += line
                }
            }
            process.waitFor()
            toLineArray(raw.takeLast(limit))
        } catch (e: Exception) {
            Log.e(TAG, "logcat fallback failed", e)
            JSONArray()
        }
    }

    private fun looksLikeAva(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("com.example.ava")) return true
        // Common Ava Log tags from this package.
        return Regex("""\s[VDIWEF]\s+\S*(Ava|Fleet|VoiceSatellite|ModManager)\S*:""")
            .containsMatchIn(line)
    }

    private fun toLineArray(rawLines: List<String>): JSONArray {
        val arr = JSONArray()
        for (raw in rawLines) {
            arr.put(parseLine(raw))
        }
        return arr
    }

    /**
     * threadtime format:
     * `01-02 15:04:05.678  1234  5678 I Tag: message`
     */
    private fun parseLine(raw: String): JSONObject {
        val re = Regex(
            """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$""",
        )
        val m = re.find(raw)
        if (m != null) {
            val g = m.groupValues
            return JSONObject()
                .put("time", g[1])
                .put("pid", g[2].toIntOrNull() ?: JSONObject.NULL)
                .put("tid", g[3].toIntOrNull() ?: JSONObject.NULL)
                .put("level", g[4])
                .put("tag", g[5].trim())
                .put("message", g[6])
                .put("raw", raw)
        }
        return JSONObject()
            .put("time", "")
            .put("pid", JSONObject.NULL)
            .put("tid", JSONObject.NULL)
            .put("level", "?")
            .put("tag", "")
            .put("message", raw)
            .put("raw", raw)
    }
}
