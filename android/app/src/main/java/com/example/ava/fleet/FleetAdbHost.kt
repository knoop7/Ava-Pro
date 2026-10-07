package com.example.ava.fleet

import android.content.Context
import android.graphics.Bitmap
import com.example.ava.net.GithubProxyUrls
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * On-device ADB host (adbhelper-style): keep a 32-bit static `adb` under
 * [filesDir]/adbhost, run `start-server` / `pair` / `connect` / `devices`.
 *
 * Binary is fetched on demand from GitHub ([REMOTE_ADB_URL]) into the app-private
 * user directory — not packaged in the APK. zh/ru locales prefer proxy mirrors;
 * others direct first. Uses [GithubProxyUrls] for consistent mirror selection.
 *
 * Works on most arm64 phones that still allow 32-bit processes.
 */
object FleetAdbHost {
    private const val TAG = "FleetAdbHost"
    private const val REMOTE_ADB_URL =
        "https://raw.githubusercontent.com/knoop7/Ava/master/adbhost/adb"
    /** Known artifact (matches repo `adbhost/adb`). Reject tampered / truncated downloads. */
    private const val EXPECTED_SHA256 =
        "878f258d88e750095bcb8744e6bfc754c948fd7ce21b22a8822fa871ecbeb138"
    private const val EXPECTED_BYTES = 2_546_616L
    private const val MIN_BYTES = 1_000_000L
    private const val PREFS = "fleet_adb_host"
    private const val KEY_PORT = "server_port"
    private const val KEY_DEVICES = "remembered_devices"
    private const val KEY_IGNORED = "ignored_serials"
    /** Keep a lost wireless peer visible this long, then disconnect + drop. */
    const val LOST_GRACE_MS = 120_000L
    private const val DEFAULT_PORT = 5037
    private const val MAX_PORT = 5047
    private const val BIN_NAME = "adb"

    private val serverPort = AtomicInteger(DEFAULT_PORT)
    private val lastError = AtomicReference<String?>(null)
    private val ready = AtomicReference(false)
    private val downloading = AtomicBoolean(false)
    private val downloadPercent = AtomicInteger(-1)
    private val downloadError = AtomicReference<String?>(null)
    private val downloadLock = Any()
    private val downloadExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fleet-adb-dl").apply { isDaemon = true }
    }
    /** Serialize start/connect/devices — concurrent kill-server drops wireless peers. */
    private val adbLock = Any()
    /** Short-lived wall JPEG cache: "serial|w|q" → (jpeg, atMs). */
    private val screenCache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
    /** One in-flight background refresh per cache key (stale-while-revalidate). */
    private val screenRefreshBusy = ConcurrentHashMap<String, AtomicBoolean>()
    private val screenRefreshExec = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "fleet-adb-screen").apply { isDaemon = true }
    }
    /** Soft hit window — serve instantly without re-capturing. */
    private const val SCREEN_CACHE_MS = 25_000L
    /** Keep returning last frame while a refresh fails (stale-while-error). */
    private const val SCREEN_STALE_MS = 180_000L
    private const val REMOTE_WALL_PNG = "/data/local/tmp/ava-adb-wall.png"

    data class CmdResult(
        val ok: Boolean,
        val code: Int,
        val stdout: String,
        val stderr: String,
        val elapsedMs: Long = 0L,
        val error: String? = null,
    )

    data class Device(
        val serial: String,
        val state: String,
        val model: String = "",
        val product: String = "",
        val transportId: String = "",
        val lostAtMs: Long = 0L,
    )

    fun statusJson(context: Context): JSONObject {
        // Never block HTTP status on network download — kick async if missing.
        if (!isBinaryReady(context)) {
            ensureBinaryAsync(context)
        }
        return statusJsonUnlocked(context)
    }

    /**
     * Fire-and-forget install into [filesDir]/adbhost. Safe to call repeatedly
     * (web console enable, SPA poll, GET /v1/adb).
     */
    fun ensureBinaryAsync(context: Context) {
        val app = context.applicationContext
        if (isBinaryReady(app)) return
        if (downloading.get()) return
        downloadExec.execute {
            ensureBinary(app)
        }
    }

    /** Explicit download request for POST /v1/adb/download — returns current status. */
    fun requestDownload(context: Context): JSONObject {
        ensureBinaryAsync(context)
        return statusJsonUnlocked(context.applicationContext)
            .put("ok", true)
            .put("accepted", true)
    }

    fun isBinaryReady(context: Context): Boolean {
        val bin = adbFile(context)
        if (!bin.isFile || bin.length() < MIN_BYTES) return false
        if (EXPECTED_BYTES > 0L && bin.length() != EXPECTED_BYTES) return false
        return bin.canExecute()
    }

    fun ensureReady(context: Context): CmdResult {
        val extract = ensureBinary(context)
        if (!extract.ok) return extract
        synchronized(adbLock) {
            // Reuse a live daemon — kill/restart only when probe fails (forget/disconnect must stay fast).
            // IMPORTANT: `adb version` does NOT need a running daemon, so it cannot prove readiness.
            if (ready.get() && daemonAlive(context)) {
                return CmdResult(ok = true, code = 0, stdout = "ready", stderr = "")
            }
            ready.set(false)
            return startServerLocked(context)
        }
    }

    fun startServer(context: Context): CmdResult {
        val extract = ensureBinary(context)
        if (!extract.ok) return extract
        synchronized(adbLock) {
            return startServerLocked(context)
        }
    }

    private fun startServerLocked(context: Context): CmdResult {
        var port = prefs(context).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(DEFAULT_PORT, MAX_PORT)
        serverPort.set(port)
        var last = CmdResult(false, -1, "", "", error = "start_failed")
        while (port <= MAX_PORT) {
            serverPort.set(port)
            prefs(context).edit().putInt(KEY_PORT, port).apply()
            // Match adbhelper: kill then start; bump port on "cannot bind"
            runAdb(context, listOf("kill-server"), timeoutSec = 8)
            last = runAdb(context, listOf("start-server"), timeoutSec = 12)
            val text = (last.stdout + "\n" + last.stderr).lowercase()
            if (text.contains("cannot bind") || text.contains("address already in use")) {
                port++
                continue
            }
            if (last.ok || text.contains("daemon started") || text.isBlank()) {
                // Verify with a daemon-backed probe (not `version`).
                val alive = daemonAlive(context)
                ready.set(alive)
                if (!alive) {
                    lastError.set("daemon_not_alive")
                    return CmdResult(
                        ok = false,
                        code = -1,
                        stdout = last.stdout,
                        stderr = last.stderr,
                        elapsedMs = last.elapsedMs,
                        error = "daemon_not_alive",
                    )
                }
                lastError.set(null)
                // Wireless peers die with kill-server — reconnect remembered network serials.
                reconnectRememberedLocked(context)
                return CmdResult(
                    ok = true,
                    code = 0,
                    stdout = last.stdout,
                    stderr = last.stderr,
                    elapsedMs = last.elapsedMs,
                )
            }
            break
        }
        ready.set(false)
        lastError.set(last.error ?: last.stderr.ifBlank { "start_server_failed" })
        return last
    }

    /** True when the adb server on [serverPort] answers `devices`. */
    private fun daemonAlive(context: Context): Boolean {
        val probe = runAdb(context, listOf("devices"), timeoutSec = 8)
        val text = probe.stdout + "\n" + probe.stderr
        if (text.contains("daemon not running", ignoreCase = true)) return false
        if (text.contains("cannot connect to daemon", ignoreCase = true)) return false
        return probe.ok || text.contains("List of devices", ignoreCase = true)
    }

    /**
     * Wireless debugging pair (Android 11+): [hostPort] like `192.168.1.8:37123`,
     * then send [code] on stdin after a short delay (adbhelper/LADB pattern).
     */
    fun pair(context: Context, hostPort: String, code: String): CmdResult {
        val target = hostPort.trim().replace('：', ':')
        val pin = code.trim()
        if (target.isEmpty() || !target.contains(':') || pin.isEmpty()) {
            return CmdResult(false, -1, "", "", error = "missing_host_port_or_code")
        }
        ensureReady(context)
        val started = System.currentTimeMillis()
        return try {
            val pb = processBuilder(context, listOf("pair", target))
            val proc = pb.start()
            Thread.sleep(500L)
            proc.outputStream.bufferedWriter().use { w ->
                w.write(pin)
                w.newLine()
                w.flush()
            }
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val finished = proc.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroy()
                return CmdResult(false, -1, out, err, System.currentTimeMillis() - started, "timeout")
            }
            val text = out + err
            val ok = text.contains("Successfully paired", ignoreCase = true) ||
                text.contains("success", ignoreCase = true)
            if (!ok) lastError.set(text.ifBlank { "pair_failed" })
            else lastError.set(null)
            CmdResult(ok, proc.exitValue(), out, err, System.currentTimeMillis() - started)
        } catch (e: Exception) {
            Log.e(TAG, "pair failed", e)
            lastError.set(e.message)
            CmdResult(false, -1, "", "", System.currentTimeMillis() - started, e.message)
        }
    }

    fun connect(context: Context, hostPort: String): CmdResult {
        val target = hostPort.trim().replace('：', ':')
        if (target.isEmpty() || !target.contains(':')) {
            return CmdResult(false, -1, "", "", error = "missing_host_port")
        }
        val host = target.substringBeforeLast(':')
        // Connecting to this phone's own LAN IP is not a peer — adb may print "connected to"
        // but the target never appears as a manageable device (or is folded into this device).
        if (isLoopbackOrLocalHost(host, context)) {
            return CmdResult(false, -1, "", "", error = "self_connect")
        }
        val extract = ensureBinary(context)
        if (!extract.ok) return extract
        synchronized(adbLock) {
            if (!ready.get() || !daemonAlive(context)) {
                ready.set(false)
                val started = startServerLocked(context)
                if (!started.ok) return started
            }
            val res = runAdb(context, listOf("connect", target), timeoutSec = 15)
            val text = res.stdout + "\n" + res.stderr
            val ok = text.contains("connected to", ignoreCase = true) ||
                text.contains("already connected", ignoreCase = true)
            if (!ok) {
                lastError.set(text.ifBlank { "connect_failed" })
                return res.copy(ok = false)
            }
            lastError.set(null)
            clearIgnored(context, target)
            // Wireless connect often needs a beat before `devices -l` lists the peer.
            val listed = waitForListedLocked(context, target, attempts = 10, delayMs = 300L)
            if (listed != null && isPresentAdbState(listed.state)) {
                remember(context, listOf(listed))
            }
            replaceRememberedWithLiveLocked(context)
            if (listed == null || !isPresentAdbState(listed.state)) {
                return res.copy(
                    ok = false,
                    stdout = (res.stdout + "\n" + "connected_pending_list:$target").trim(),
                    error = listed?.state ?: "not_listed",
                )
            }
            return res.copy(ok = true)
        }
    }

    private fun waitForListedLocked(
        context: Context,
        serial: String,
        attempts: Int,
        delayMs: Long,
    ): Device? {
        val key = serial.trim()
        repeat(attempts) { i ->
            val res = runAdb(context, listOf("devices", "-l"), timeoutSec = 10)
            val hit = parseDevices(res.stdout + "\n" + res.stderr)
                .firstOrNull { it.serial.equals(key, ignoreCase = true) }
            if (hit != null && (hit.state == "device" || hit.state == "unauthorized")) {
                return hit
            }
            // offline: keep waiting — common right after wireless connect
            if (i < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return hit
                }
            } else if (hit != null) {
                return hit
            }
        }
        return null
    }

    /**
     * `adb disconnect` only accepts network targets (`host:port`).
     * USB / `emulator-5554` style serials cannot be disconnected that way — [forget] hides them instead.
     * Never append `:5555` to an existing serial.
     */
    fun disconnect(context: Context, serial: String? = null): CmdResult {
        val key = serial?.trim()?.replace('：', ':')?.ifBlank { null }
        if (key != null && !isNetworkSerial(key)) {
            return CmdResult(ok = true, code = 0, stdout = "", stderr = "not_network_serial")
        }
        val extract = ensureBinary(context)
        if (!extract.ok) return extract
        synchronized(adbLock) {
            if (!ready.get() || !daemonAlive(context)) {
                ready.set(false)
                val started = startServerLocked(context)
                if (!started.ok) return started
            }
            val args = if (key == null) listOf("disconnect") else listOf("disconnect", key)
            val res = runAdb(context, args, timeoutSec = 10)
            val text = res.stdout + "\n" + res.stderr
            val ok = res.ok ||
                text.contains("disconnected", ignoreCase = true) ||
                text.contains("no such device", ignoreCase = true)
            if (key != null) {
                dropRememberedSerialLocked(context, key)
            } else {
                reconcileRememberedLocked(context)
            }
            return res.copy(ok = ok)
        }
    }

    fun devices(context: Context): List<Device> {
        if (!isBinaryReady(context)) {
            ensureBinaryAsync(context)
            return emptyList()
        }
        ensureReady(context)
        synchronized(adbLock) {
            val res = runAdb(context, listOf("devices", "-l"), timeoutSec = 10)
            val ignored = ignoredSerials(context)
            val all = parseDevices(res.stdout + "\n" + res.stderr)
            val list = all
                .filter { it.serial !in ignored }
                .filter { !isSelfDevice(context, it) }
            reconcileRememberedLocked(context, list)
            return list
        }
    }

    fun devicesJson(context: Context): JSONObject {
        if (!isBinaryReady(context)) {
            ensureBinaryAsync(context)
            return emptyDevicesJson(context, binaryPending = true)
        }
        // Listing must not start the daemon or reconnect remembered peers.
        // Overview/wall polls this; ADB comes up on POST /v1/adb/start or connect.
        if (!ready.get()) {
            return emptyDevicesJson(context, binaryPending = false)
        }
        synchronized(adbLock) {
            if (!ready.get() || !daemonAlive(context)) {
                ready.set(false)
                return emptyDevicesJson(context, binaryPending = false)
            }
            val res = runAdb(context, listOf("devices", "-l"), timeoutSec = 10)
            val ignored = ignoredSerials(context)
            val all = parseDevices(res.stdout + "\n" + res.stderr)
            val selfHits = all.filter { isSelfDevice(context, it) }
            val live = all
                .filter { it.serial !in ignored }
                .filter { !isSelfDevice(context, it) }
            val list = reconcileRememberedLocked(context, live)
            val arr = JSONArray()
            for (d in list) {
                arr.put(deviceToJson(d, self = false))
            }
            val selfArr = JSONArray()
            for (d in selfHits) {
                selfArr.put(deviceToJson(d, self = true))
            }
            return JSONObject()
                .put("ok", true)
                .put("count", list.size)
                .put("devices", arr)
                .put("selfDevices", selfArr)
                .put("selfDiscovered", selfHits.isNotEmpty())
                .put("selfCount", selfHits.size)
                .put("selfSerials", JSONArray(selfHits.map { it.serial }))
                .put("serverPort", serverPort.get())
                .put("adb", statusJsonUnlocked(context))
                .put("daemonReady", true)
        }
    }

    private fun emptyDevicesJson(context: Context, binaryPending: Boolean): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("count", 0)
            .put("devices", JSONArray())
            .put("selfDevices", JSONArray())
            .put("selfDiscovered", false)
            .put("selfCount", 0)
            .put("selfSerials", JSONArray())
            .put("serverPort", serverPort.get())
            .put("adb", statusJsonUnlocked(context))
            .put("binaryPending", binaryPending)
            .put("daemonReady", false)
    }

    private fun statusJsonUnlocked(context: Context): JSONObject {
        val bin = adbFile(context)
        val pct = downloadPercent.get()
        return JSONObject()
            .put("ok", true)
            .put("ready", ready.get() && isBinaryReady(context))
            .put("binary", bin.absolutePath)
            .put("binaryExists", bin.isFile && bin.length() >= MIN_BYTES)
            .put("binaryBytes", if (bin.isFile) bin.length() else 0L)
            .put("binaryReady", isBinaryReady(context))
            .put("downloading", downloading.get())
            .put("downloadPercent", if (pct >= 0) pct else JSONObject.NULL)
            .put("downloadError", downloadError.get() ?: JSONObject.NULL)
            .put("downloadUrl", REMOTE_ADB_URL)
            .put("abiHint", "armeabi-v7a-static")
            .put("serverPort", serverPort.get())
            .put("lastError", lastError.get() ?: JSONObject.NULL)
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
    }

    private fun deviceToJson(d: Device, self: Boolean): JSONObject =
        JSONObject()
            .put("serial", d.serial)
            .put("state", d.state)
            .put("model", d.model)
            .put("product", d.product)
            .put("transportId", d.transportId)
            .put("self", self)
            .put("lostAtMs", if (d.lostAtMs > 0L) d.lostAtMs else JSONObject.NULL)

    /** Last live wireless peers — used only to reconnect after `kill-server`. */
    fun rememberedEntries(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_DEVICES, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    /**
     * Remove a device from the managed ADB list.
     * Network serial → `adb disconnect host:port`; local/USB serial → hide via ignore list.
     */
    fun forget(context: Context, serial: String): CmdResult {
        val key = serial.trim().replace('：', ':')
        if (key.isBlank()) return CmdResult(false, -1, "", "", error = "missing_serial")
        addIgnored(context, key)
        val disc = if (isNetworkSerial(key)) {
            disconnect(context, key)
        } else {
            CmdResult(ok = true, code = 0, stdout = "", stderr = "ignored_local_serial")
        }
        val arr = rememberedEntries(context)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("serial").ifBlank { o.optString("id").removePrefix("adb:") }
            if (s != key) next.put(o)
        }
        prefs(context).edit().putString(KEY_DEVICES, next.toString()).apply()
        return CmdResult(
            ok = true,
            code = 0,
            stdout = disc.stdout,
            stderr = disc.stderr,
            elapsedMs = disc.elapsedMs,
        )
    }

    /** `device` / `unauthorized` are real transports. `offline` is a leftover after the peer died. */
    fun isPresentAdbState(state: String): Boolean {
        val s = state.trim().lowercase(Locale.US)
        return s == "device" || s == "unauthorized"
    }

    fun shouldAutoCleanLost(lostAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        return lostAtMs > 0L && nowMs - lostAtMs >= LOST_GRACE_MS
    }

    /** True for `192.168.1.8:5555` / `host.local:5555`; false for `emulator-5554` / USB serials. */
    fun isNetworkSerial(serial: String): Boolean {
        val t = serial.trim().replace('：', ':')
        if (':' !in t) return false
        val host = t.substringBeforeLast(':')
        val port = t.substringAfterLast(':')
        if (port.toIntOrNull() == null) return false
        if (host.startsWith("[")) return true
        return host.contains('.')
    }

    /**
     * Host phone itself often appears in `adb devices` as `emulator-5554` (or own IP:5555).
     * Discoverable for diagnostics, but never listed as a peer to manage.
     */
    fun isSelfDevice(context: Context, d: Device): Boolean {
        val serial = d.serial.trim()
        if (serial.startsWith("emulator-", ignoreCase = true)) return true
        val localModel = normalizeModel(Build.MODEL)
        val remoteModel = normalizeModel(d.model)
        val localProduct = Build.PRODUCT.orEmpty().trim()
        val localDevice = Build.DEVICE.orEmpty().trim()
        val product = d.product.trim()
        val modelMatch = localModel.isNotEmpty() && localModel.equals(remoteModel, ignoreCase = true)
        val productMatch = (localProduct.isNotEmpty() && localProduct.equals(product, ignoreCase = true)) ||
            (localDevice.isNotEmpty() && localDevice.equals(product, ignoreCase = true))
        if (!isNetworkSerial(serial)) {
            return modelMatch || productMatch
        }
        val host = serial.substringBeforeLast(':')
        if (isLoopbackOrLocalHost(host, context) && (modelMatch || productMatch || remoteModel.isEmpty())) {
            return true
        }
        return false
    }

    private fun normalizeModel(raw: String?): String =
        (raw ?: "").replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    private fun isLoopbackOrLocalHost(host: String, context: Context): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0.0.0.0") return true
        val local = FleetNetwork.getLocalIpAddress(context)?.trim().orEmpty()
        if (local.isNotEmpty() && local.equals(h, ignoreCase = true)) return true
        return false
    }

    private fun ignoredSerials(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_IGNORED, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun addIgnored(context: Context, serial: String) {
        val next = ignoredSerials(context).toMutableSet()
        next.add(serial)
        val arr = JSONArray()
        next.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_IGNORED, arr.toString()).apply()
    }

    private fun clearIgnored(context: Context, serial: String) {
        val next = ignoredSerials(context).toMutableSet()
        if (!next.remove(serial)) return
        val arr = JSONArray()
        next.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_IGNORED, arr.toString()).apply()
    }

    fun install(context: Context, localApkPath: String, serial: String? = null): CmdResult {
        val apk = File(localApkPath)
        if (!apk.isFile) return CmdResult(false, -1, "", "", error = "apk_not_found")
        ensureReady(context)
        val args = buildList {
            if (!serial.isNullOrBlank()) {
                add("-s")
                add(serial.trim())
            }
            add("install")
            add("-r")
            add(apk.absolutePath)
        }
        return runAdb(context, args, timeoutSec = 120)
    }

    fun shell(context: Context, command: String, serial: String? = null, timeoutSec: Int = 30): CmdResult {
        val cmd = command.trim()
        if (cmd.isEmpty()) return CmdResult(false, -1, "", "", error = "empty_command")
        ensureReady(context)
        val args = buildList {
            if (!serial.isNullOrBlank()) {
                add("-s")
                add(serial.trim())
            }
            add("shell")
            add(cmd)
        }
        return runAdb(context, args, timeoutSec = timeoutSec.coerceIn(2, 120))
    }

    /** `adb shell input tap x y` (device pixels). */
    fun inputTap(context: Context, x: Int, y: Int, serial: String? = null): CmdResult {
        if (x < 0 || y < 0) return CmdResult(false, -1, "", "", error = "bad_coords")
        return shell(context, "input tap $x $y", serial)
    }

    /** `adb shell input swipe x1 y1 x2 y2 durationMs`. */
    fun inputSwipe(
        context: Context,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 300,
        serial: String? = null,
    ): CmdResult {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return CmdResult(false, -1, "", "", error = "bad_coords")
        }
        val dur = durationMs.coerceIn(1, 5000)
        return shell(context, "input swipe $x1 $y1 $x2 $y2 $dur", serial)
    }

    /** `adb shell input keyevent KEYCODE_*` — accepts BACK/HOME/ENTER or numeric code. */
    fun inputKey(context: Context, key: String, serial: String? = null): CmdResult {
        val raw = key.trim()
        if (raw.isEmpty()) return CmdResult(false, -1, "", "", error = "missing_key")
        val code = when (raw.uppercase()) {
            "BACK" -> "4"
            "HOME" -> "3"
            "ENTER", "RETURN" -> "66"
            "APP_SWITCH", "RECENTS" -> "187"
            else -> if (raw.all { it.isDigit() }) raw else return CmdResult(false, -1, "", "", error = "bad_key")
        }
        return shell(context, "input keyevent $code", serial)
    }

    fun pull(context: Context, remotePath: String, localFile: File, serial: String? = null): CmdResult {
        val remote = remotePath.trim()
        if (remote.isEmpty()) return CmdResult(false, -1, "", "", error = "missing_remote")
        ensureReady(context)
        localFile.parentFile?.mkdirs()
        val args = buildList {
            if (!serial.isNullOrBlank()) {
                add("-s")
                add(serial.trim())
            }
            add("pull")
            add(remote)
            add(localFile.absolutePath)
        }
        return runAdb(context, args, timeoutSec = 120)
    }

    fun push(context: Context, localFile: File, remotePath: String, serial: String? = null): CmdResult {
        val remote = sanitizeStoragePath(remotePath) ?: return CmdResult(false, -1, "", "", error = "bad_path")
        if (!localFile.isFile) return CmdResult(false, -1, "", "", error = "missing_local")
        ensureReady(context)
        val args = buildList {
            if (!serial.isNullOrBlank()) {
                add("-s")
                add(serial.trim())
            }
            add("push")
            add(localFile.absolutePath)
            add(remote)
        }
        return runAdb(context, args, timeoutSec = 180)
    }

    /** Discover SD / shared storage roots on the peer. */
    fun listStorageRoots(context: Context, serial: String? = null): JSONObject {
        ensureReady(context)
        // IMPORTANT: never use unquoted `echo a|b|c` — device shell treats `|` as pipe.
        val cmd = buildString {
            append("printf '%s\\n' '__ROOT__|/sdcard|sdcard'; ")
            append("if [ -d /storage/emulated/0 ]; then printf '%s\\n' '__ROOT__|/storage/emulated/0|emulated'; fi; ")
            append("for d in /storage/*; do ")
            append("[ -d \"\$d\" ] || continue; ")
            append("b=\"\${d##*/}\"; ")
            append("case \"\$b\" in self|emulated|.*) continue ;; esac; ")
            append("printf '__ROOT__|%s|%s\\n' \"\$d\" \"\$b\"; ")
            append("done; ")
            append("for d in /mnt/media_rw/*; do ")
            append("[ -d \"\$d\" ] || continue; ")
            append("b=\"\${d##*/}\"; ")
            append("printf '__ROOT__|%s|%s\\n' \"\$d\" \"\$b\"; ")
            append("done")
        }
        val res = shell(context, cmd, serial, timeoutSec = 20)
        val roots = JSONArray()
        val seen = linkedSetOf<String>()
        for (line in res.stdout.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("__ROOT__|")) continue
            val parts = t.split('|')
            if (parts.size < 3) continue
            val path = sanitizeReadPath(parts[1]) ?: continue
            if (!seen.add(path)) continue
            roots.put(
                JSONObject()
                    .put("path", path)
                    .put("name", parts[2].ifBlank { path.substringAfterLast('/') })
                    .put("id", path),
            )
        }
        if (roots.length() == 0) {
            sanitizeReadPath("/sdcard")?.let { p ->
                roots.put(JSONObject().put("path", p).put("name", "sdcard").put("id", p))
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("serial", serial ?: JSONObject.NULL)
            .put("count", roots.length())
            .put("roots", roots)
            .put("error", res.error ?: JSONObject.NULL)
    }

    /**
     * List one directory under shared storage (`/sdcard`, `/storage/…`).
     * Entries: `{ name, path, dir, size, mtime }`.
     */
    fun listDir(context: Context, path: String, serial: String? = null): JSONObject {
        val remote = sanitizeReadPath(path) ?: return JSONObject()
            .put("ok", false)
            .put("error", "bad_path")
            .put("entries", JSONArray())
        ensureReady(context)
        val q = shQuote(remote)
        val cmd = buildString {
            append("P=$q; ")
            append("if [ ! -e \"\$P\" ]; then printf '%s\\n' '__ERR|missing'; exit 0; fi; ")
            append("if [ ! -d \"\$P\" ]; then printf '%s\\n' '__ERR|not_dir'; exit 0; fi; ")
            append("for f in \"\$P\"/* \"\$P\"/.[!.]* \"\$P\"/..?*; do ")
            append("[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; ")
            append("b=\"\${f##*/}\"; ")
            append("case \"\$b\" in .|..) continue ;; esac; ")
            append("if [ -d \"\$f\" ]; then t=d; elif [ -L \"\$f\" ]; then t=l; else t=f; fi; ")
            append("sm=\$(stat -c '%s|%Y' \"\$f\" 2>/dev/null || printf '0|0'); ")
            append("printf '__ENT__|%s|%s|%s\\n' \"\$t\" \"\$sm\" \"\$b\"; ")
            append("done")
        }
        val used = shell(context, cmd, serial, timeoutSec = 45)
        for (line in used.stdout.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("__ERR|")) {
                return JSONObject()
                    .put("ok", false)
                    .put("path", remote)
                    .put("error", t.removePrefix("__ERR|"))
                    .put("entries", JSONArray())
                    .put("stderr", used.stderr)
            }
        }
        val entries = JSONArray()
        for (line in used.stdout.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("__ENT__|")) continue
            val parts = t.split('|', limit = 5)
            if (parts.size < 5) continue
            val kind = parts[1]
            val size = parts[2].toLongOrNull() ?: 0L
            val mtime = parts[3].toLongOrNull() ?: 0L
            val name = parts[4]
            if (name.isBlank() || name == "." || name == "..") continue
            val child = if (remote == "/") "/$name" else "$remote/$name"
            entries.put(
                JSONObject()
                    .put("name", name)
                    .put("path", child)
                    .put("dir", kind == "d")
                    .put("link", kind == "l")
                    .put("size", size)
                    .put("mtime", mtime),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("path", remote)
            .put("serial", serial ?: JSONObject.NULL)
            .put("count", entries.length())
            .put("entries", entries)
            .put("error", used.error ?: JSONObject.NULL)
    }

    fun mkdir(context: Context, path: String, serial: String? = null): CmdResult {
        val remote = sanitizeStoragePath(path) ?: return CmdResult(false, -1, "", "", error = "bad_path")
        return shell(context, "mkdir -p ${shQuote(remote)}", serial, timeoutSec = 20)
    }

    private val PROTECTED_DIRS = setOf(
        "Android", "DCIM", "Download", "Downloads", "Documents",
        "Music", "Movies", "Pictures", "Ringtones", "Alarms",
        "Notifications", "Podcasts", "Audiobooks",
    )

    private fun isProtectedDeletePath(remote: String): Boolean {
        if (
            remote == "/" ||
            remote == "/sdcard" ||
            remote == "/storage" ||
            remote == "/storage/emulated" ||
            remote == "/mnt" ||
            remote == "/mnt/media_rw"
        ) {
            return true
        }
        if (Regex("^/storage/emulated/\\d+$").matches(remote)) return true
        if (Regex("^/storage/[^/]+$").matches(remote)) return true
        if (Regex("^/mnt/media_rw/[^/]+$").matches(remote)) return true
        val parts = remote.trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (parts.size <= 1) return true
        val name = parts.lastOrNull().orEmpty()
        if (!PROTECTED_DIRS.contains(name)) return false
        if (remote.startsWith("/sdcard/") && parts.size == 2) return true
        if (Regex("^/storage/emulated/\\d+/").containsMatchIn(remote) && parts.size == 4) return true
        if (
            remote.startsWith("/storage/") &&
            !remote.startsWith("/storage/emulated/") &&
            parts.size == 3
        ) {
            return true
        }
        if (Regex("^/mnt/media_rw/[^/]+/").containsMatchIn(remote) && parts.size == 4) return true
        return false
    }

    fun deletePath(context: Context, path: String, serial: String? = null): CmdResult {
        val remote = sanitizeStoragePath(path) ?: return CmdResult(false, -1, "", "", error = "bad_path")
        if (isProtectedDeletePath(remote)) {
            return CmdResult(false, -1, "", "", error = "refuse_protected")
        }
        return shell(context, "rm -rf -- ${shQuote(remote)}", serial, timeoutSec = 60)
    }

    fun renamePath(context: Context, from: String, to: String, serial: String? = null): CmdResult {
        val src = sanitizeStoragePath(from) ?: return CmdResult(false, -1, "", "", error = "bad_from")
        val dst = sanitizeStoragePath(to) ?: return CmdResult(false, -1, "", "", error = "bad_to")
        return shell(context, "mv -f -- ${shQuote(src)} ${shQuote(dst)}", serial, timeoutSec = 30)
    }

    fun pushBytes(
        context: Context,
        bytes: ByteArray,
        remotePath: String,
        serial: String? = null,
        fileName: String = "upload.bin",
    ): CmdResult {
        val remote = sanitizeStoragePath(remotePath) ?: return CmdResult(false, -1, "", "", error = "bad_path")
        if (bytes.isEmpty()) return CmdResult(false, -1, "", "", error = "empty_body")
        if (bytes.size > 512 * 1024 * 1024) return CmdResult(false, -1, "", "", error = "too_large")
        ensureReady(context)
        val dir = File(context.cacheDir, "adb-files")
        dir.mkdirs()
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload.bin" }
        val local = File(dir, "up_${System.currentTimeMillis()}_$safe")
        return try {
            local.writeBytes(bytes)
            push(context, local, remote, serial)
        } catch (e: Exception) {
            CmdResult(false, -1, "", "", error = e.message ?: "write_failed")
        } finally {
            runCatching { local.delete() }
        }
    }

    fun pullBytes(context: Context, remotePath: String, serial: String? = null): Pair<CmdResult, ByteArray?> {
        val remote = sanitizeReadPath(remotePath) ?: return CmdResult(false, -1, "", "", error = "bad_path") to null
        ensureReady(context)
        val dir = File(context.cacheDir, "adb-files")
        dir.mkdirs()
        val local = File(dir, "dl_${System.currentTimeMillis()}_${remote.substringAfterLast('/').ifBlank { "file" }}")
        return try {
            val res = pull(context, remote, local, serial)
            if (!res.ok || !local.isFile) return res to null
            res to local.readBytes()
        } catch (e: Exception) {
            CmdResult(false, -1, "", "", error = e.message ?: "read_failed") to null
        } finally {
            runCatching { local.delete() }
        }
    }

    /**
     * Dump recent device logcat via ADB (`logcat -d -t N -v threadtime`).
     * Same multi-device pattern as apps/files — pass peer [serial].
     */
    fun dumpLogcat(context: Context, serial: String? = null, limit: Int = 200): JSONObject {
        ensureReady(context)
        val n = limit.coerceIn(1, 500)
        val res = shell(context, "logcat -d -t $n -v threadtime", serial, timeoutSec = 30)
        val lines = JSONArray()
        val re = Regex(
            """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$""",
        )
        for (raw in res.stdout.lineSequence()) {
            val t = raw.trimEnd()
            if (t.isBlank()) continue
            val m = re.find(t)
            if (m != null) {
                val g = m.groupValues
                lines.put(
                    JSONObject()
                        .put("time", g[1])
                        .put("pid", g[2].toIntOrNull() ?: JSONObject.NULL)
                        .put("tid", g[3].toIntOrNull() ?: JSONObject.NULL)
                        .put("level", g[4])
                        .put("tag", g[5].trim())
                        .put("message", g[6])
                        .put("raw", t),
                )
            } else {
                lines.put(
                    JSONObject()
                        .put("time", "")
                        .put("pid", JSONObject.NULL)
                        .put("tid", JSONObject.NULL)
                        .put("level", "?")
                        .put("tag", "")
                        .put("message", t)
                        .put("raw", t),
                )
            }
        }
        return JSONObject()
            .put("ok", res.ok || lines.length() > 0)
            .put("serial", serial ?: JSONObject.NULL)
            .put("source", "adb-logcat")
            .put("limit", n)
            .put("count", lines.length())
            .put("lines", lines)
            .put("error", res.error ?: JSONObject.NULL)
            .put("stderr", res.stderr.ifBlank { JSONObject.NULL })
    }

    /** Normalize absolute path; reject empty / relative / `..`. */
    private fun normalizeAbsPath(raw: String): String? {
        var p = raw.trim().replace('\\', '/')
        if (p.isEmpty()) return null
        if (!p.startsWith("/")) p = "/$p"
        while ("//" in p) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) return null
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    /** Read paths (list / pull): any absolute path ADB can see, including `/`. */
    private fun sanitizeReadPath(raw: String): String? = normalizeAbsPath(raw)

    /** Write paths (push / mkdir / delete / rename): shared storage only. */
    private fun sanitizeStoragePath(raw: String): String? {
        val norm = normalizeAbsPath(raw) ?: return null
        val ok = norm == "/sdcard" || norm.startsWith("/sdcard/") ||
            norm == "/storage" || norm.startsWith("/storage/") ||
            norm.startsWith("/mnt/media_rw/")
        return if (ok) norm else null
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    data class ScreenJpeg(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val serial: String,
        val atMs: Long,
        val cached: Boolean,
    )

    /**
     * One-shot wall thumb for a wireless ADB peer.
     * Prefer `exec-out screencap -p` (single hop, no pull/rm); fall back to file pull.
     * Soft cache + stale-while-revalidate so the wall never blanks while refreshing.
     */
    fun captureScreenJpeg(
        context: Context,
        serial: String,
        maxWidth: Int = 480,
        quality: Int = 40,
        force: Boolean = false,
    ): ScreenJpeg? {
        val serialKey = serial.trim().replace('：', ':')
        if (serialKey.isBlank()) {
            lastError.set("missing_serial")
            return null
        }
        val now = System.currentTimeMillis()
        val w = maxWidth.coerceIn(160, 960)
        val q = quality.coerceIn(25, 80)
        val cacheKey = "$serialKey|$w|$q"
        val cached = screenCache[cacheKey]
        if (!force) {
            cached?.let { (bytes, at) ->
                if (bytes.isEmpty()) return@let
                if (now - at < SCREEN_CACHE_MS) {
                    return ScreenJpeg(bytes, 0, 0, serialKey, at, cached = true)
                }
                // Soft expired: keep previous frame, refresh in background (rate-limited).
                if (now - at < SCREEN_STALE_MS) {
                    scheduleScreenRefresh(context, serialKey, cacheKey, w, q)
                    return ScreenJpeg(bytes, 0, 0, serialKey, at, cached = true)
                }
            }
        }
        return captureScreenJpegSync(context, serialKey, cacheKey, w, q, cached)
    }

    private fun scheduleScreenRefresh(
        context: Context,
        serial: String,
        cacheKey: String,
        maxWidth: Int,
        quality: Int,
    ) {
        val flag = screenRefreshBusy.getOrPut(cacheKey) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        screenRefreshExec.execute {
            try {
                captureScreenJpegSync(appCtx, serial, cacheKey, maxWidth, quality, screenCache[cacheKey])
            } finally {
                flag.set(false)
            }
        }
    }

    private fun captureScreenJpegSync(
        context: Context,
        serial: String,
        cacheKey: String,
        maxWidth: Int,
        quality: Int,
        cached: Pair<ByteArray, Long>?,
    ): ScreenJpeg? {
        ensureReady(context)
        val now = System.currentTimeMillis()
        return try {
            var jpeg = captureViaExecOut(context, serial, maxWidth, quality)
            if (jpeg == null) {
                jpeg = captureViaPull(context, serial, maxWidth, quality)
            }
            if (jpeg == null) {
                cached?.let { (bytes, at) ->
                    if (bytes.isNotEmpty() && now - at < SCREEN_STALE_MS) {
                        return ScreenJpeg(bytes, 0, 0, serial, at, cached = true)
                    }
                }
                return null
            }
            val at = System.currentTimeMillis()
            screenCache[cacheKey] = jpeg.bytes to at
            lastError.set(null)
            ScreenJpeg(jpeg.bytes, jpeg.width, jpeg.height, serial, at, cached = false)
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenJpeg failed", e)
            lastError.set(e.message ?: "screencap_exception")
            cached?.let { (bytes, at) ->
                if (bytes.isNotEmpty() && now - at < SCREEN_STALE_MS) {
                    return ScreenJpeg(bytes, 0, 0, serial, at, cached = true)
                }
            }
            null
        }
    }

    /** Fast path: stream PNG over adb stdout (no remote temp / pull / rm). */
    private fun captureViaExecOut(
        context: Context,
        serial: String,
        maxWidth: Int,
        quality: Int,
    ): EncodedJpeg? {
        val (png, res) = runAdbBytes(
            context,
            listOf("-s", serial, "exec-out", "screencap", "-p"),
            timeoutSec = 10,
        )
        if (png.size < 64 || !isPng(png)) {
            if (!res.ok) {
                Log.w(TAG, "exec-out screencap failed code=${res.code} stderr=${res.stderr}")
            }
            return null
        }
        return pngBytesToJpeg(png, maxWidth, quality)
    }

    /** Fallback for devices that break exec-out binary streams. */
    private fun captureViaPull(
        context: Context,
        serial: String,
        maxWidth: Int,
        quality: Int,
    ): EncodedJpeg? {
        val shotRes = runAdb(
            context,
            listOf("-s", serial, "shell", "screencap", "-p", REMOTE_WALL_PNG),
            timeoutSec = 12,
        )
        if (!shotRes.ok && shotRes.code != 0) {
            Log.w(TAG, "screencap shell code=${shotRes.code} stderr=${shotRes.stderr}")
        }
        val local = File(context.cacheDir, "adb-wall-${serial.hashCode()}.png")
        runCatching { if (local.exists()) local.delete() }
        val pulled = pull(context, REMOTE_WALL_PNG, local, serial)
        if (!local.isFile || local.length() < 64L) {
            lastError.set(pulled.error ?: pulled.stderr.ifBlank { "screencap_pull_failed" })
            runAdb(context, listOf("-s", serial, "shell", "rm", "-f", REMOTE_WALL_PNG), timeoutSec = 4)
            return null
        }
        val jpeg = pngFileToJpeg(local, maxWidth, quality)
        runCatching { local.delete() }
        runAdb(context, listOf("-s", serial, "shell", "rm", "-f", REMOTE_WALL_PNG), timeoutSec = 4)
        if (jpeg == null) lastError.set("jpeg_encode_failed")
        return jpeg
    }

    private fun isPng(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
    }

    private data class EncodedJpeg(val bytes: ByteArray, val width: Int, val height: Int)

    private fun pngBytesToJpeg(png: ByteArray, maxWidth: Int, quality: Int): EncodedJpeg? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        // Decode closer to target width — fewer pixels = faster encode.
        while (w / sample > maxWidth * 1.35f) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return null
        try {
            if (bmp.width > maxWidth) {
                val h = (bmp.height * (maxWidth.toDouble() / bmp.width)).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
                if (scaled !== bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }
            val baos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)) return null
            return EncodedJpeg(baos.toByteArray(), bmp.width, bmp.height)
        } finally {
            bmp.recycle()
        }
    }

    private fun pngFileToJpeg(file: File, maxWidth: Int, quality: Int): EncodedJpeg? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return pngBytesToJpeg(bytes, maxWidth, quality)
    }

    /**
     * List packages on an ADB target (adbhelper-style app management).
     * @param filter `all` | `user` (-3) | `system` (-s) | `disabled` (-d)
     */
    fun listApps(context: Context, serial: String? = null, filter: String = "user"): JSONObject {
        ensureReady(context)
        val flag = when (filter.trim().lowercase()) {
            "system", "sys" -> "-s"
            "disabled", "d" -> "-d"
            "all" -> ""
            else -> "-3" // user / third-party
        }
        val listCmd = if (flag.isEmpty()) "pm list packages -f" else "pm list packages -f $flag"
        val disabledCmd = "pm list packages -d"
        val listRes = shell(context, listCmd, serial)
        val disabledRes = shell(context, disabledCmd, serial)
        val disabled = parsePackageNames(disabledRes.stdout + "\n" + disabledRes.stderr).toHashSet()
        val apps = JSONArray()
        for (entry in parsePackagesWithPath(listRes.stdout + "\n" + listRes.stderr)) {
            val pkg = entry.first
            val path = entry.second
            val systemish = path.startsWith("/system") || path.startsWith("/product") ||
                path.startsWith("/vendor") || path.startsWith("/apex") || path.startsWith("/system_ext")
            apps.put(
                JSONObject()
                    .put("package", pkg)
                    .put("path", path)
                    .put("disabled", disabled.contains(pkg))
                    .put("system", systemish)
                    .put("user", !systemish),
            )
        }
        return JSONObject()
            .put("ok", listRes.ok || apps.length() > 0)
            .put("filter", filter)
            .put("serial", serial ?: JSONObject.NULL)
            .put("count", apps.length())
            .put("apps", apps)
            .put("error", if (apps.length() == 0) (listRes.error ?: listRes.stderr.ifBlank { JSONObject.NULL }) else JSONObject.NULL)
            .put("stdout", listRes.stdout.take(2_000))
    }

    fun appDetail(context: Context, packageName: String, serial: String? = null): JSONObject {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "missing_package")
        }
        ensureReady(context)
        val pathRes = shell(context, "pm path $pkg", serial)
        val paths = pathRes.stdout.lineSequence()
            .map { it.trim().removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val dump = shell(
            context,
            "dumpsys package $pkg | grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|userId=|enabled=|pkgFlags=|codePath=' | head -n 40",
            serial,
        )
        val props = JSONObject()
        for (line in dump.stdout.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("versionName=") -> props.put("versionName", t.substringAfter('='))
                t.startsWith("versionCode=") -> props.put("versionCode", t.substringAfter('=').substringBefore(' '))
                t.startsWith("firstInstallTime=") -> props.put("firstInstallTime", t.substringAfter('='))
                t.startsWith("lastUpdateTime=") -> props.put("lastUpdateTime", t.substringAfter('='))
                t.contains("enabled=") -> props.put("enabledLine", t)
                t.startsWith("codePath=") -> props.put("codePath", t.substringAfter('='))
            }
        }
        val disabledRes = shell(context, "pm list packages -d $pkg", serial)
        val disabled = parsePackageNames(disabledRes.stdout).contains(pkg)
        return JSONObject()
            .put("ok", paths.isNotEmpty() || dump.ok)
            .put("package", pkg)
            .put("paths", JSONArray(paths))
            .put("path", paths.firstOrNull() ?: "")
            .put("disabled", disabled)
            .put("props", props)
            .put("dump", dump.stdout.take(4_000))
    }

    fun appAction(
        context: Context,
        packageName: String,
        action: String,
        serial: String? = null,
    ): CmdResult {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return CmdResult(false, -1, "", "", error = "missing_package")
        if (!pkg.matches(Regex("^[A-Za-z0-9._]+$"))) {
            return CmdResult(false, -1, "", "", error = "invalid_package")
        }
        val cmd = when (action.trim().lowercase()) {
            "launch", "start" ->
                "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
            "force-stop", "stop", "forcestop" ->
                "am force-stop $pkg"
            "disable" ->
                "pm disable-user --user 0 $pkg"
            "enable" ->
                "pm enable $pkg"
            "clear", "clear-data" ->
                "pm clear $pkg"
            "uninstall" ->
                "pm uninstall --user 0 $pkg"
            else -> return CmdResult(false, -1, "", "", error = "unknown_action")
        }
        return shell(context, cmd, serial)
    }

    /** Pull remote APK(s) for [packageName] into app cache; returns local file of primary APK. */
    fun pullApk(context: Context, packageName: String, serial: String? = null): Pair<CmdResult, File?> {
        val detail = appDetail(context, packageName, serial)
        val path = detail.optString("path").ifBlank {
            detail.optJSONArray("paths")?.optString(0).orEmpty()
        }
        if (path.isBlank()) {
            return CmdResult(false, -1, "", "", error = "apk_path_not_found") to null
        }
        val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val local = File(File(context.cacheDir, "adb-apks"), "$safe.apk")
        val res = pull(context, path, local, serial)
        return res to if (res.ok && local.isFile) local else null
    }

    /**
     * App icon bytes. Local/self serials use PackageManager (adaptive icons).
     * Remote peers extract png/webp from the APK over adb.
     * Returns (bytes, mimeType) or null on any failure.
     */
    fun appIcon(context: Context, packageName: String, serial: String? = null): Pair<ByteArray, String>? {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || !pkg.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$"))) return null
        if (isLocalIconSerial(context, serial)) {
            appIconFromPackageManager(context, pkg)?.let { return it }
        }
        return appIconFromApkViaAdb(context, pkg, serial)
    }

    private fun isLocalIconSerial(context: Context, serial: String?): Boolean {
        if (serial.isNullOrBlank()) return true
        val s = serial.trim()
        if (s.startsWith("emulator-", ignoreCase = true)) return true
        return isSelfDevice(context, Device(serial = s, state = "device"))
    }

    private fun appIconFromPackageManager(context: Context, pkg: String): Pair<ByteArray, String>? {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val drawable = pm.getApplicationIcon(info)
            val bmp = drawableToBitmap(drawable, 96)
            try {
                val baos = ByteArrayOutputStream()
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)) return null
                val bytes = baos.toByteArray()
                if (bytes.isEmpty()) null else bytes to "image/png"
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (e: Exception) {
            Log.d(TAG, "PM icon miss for $pkg: ${e.message}")
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val src = drawable.bitmap
            if (src != null && !src.isRecycled) {
                return Bitmap.createScaledBitmap(src, size, size, true)
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    private fun appIconFromApkViaAdb(
        context: Context,
        pkg: String,
        serial: String?,
    ): Pair<ByteArray, String>? {
        ensureReady(context)
        val pathRes = shell(context, "pm path $pkg", serial)
        val apkPath = pathRes.stdout.lineSequence()
            .map { it.trim().removePrefix("package:").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null

        val badging = shell(
            context,
            "(aapt dump badging \"$apkPath\" 2>/dev/null || aapt2 dump badging \"$apkPath\" 2>/dev/null) " +
                "| grep -oE \"application-icon-[0-9]+:'[^']+'\" | tail -n 1",
            serial,
        )
        val badgingEntry = badging.stdout.trim()
            .substringAfter(":'", "")
            .trim('\'', '"', ' ')
            .takeIf { it.endsWith(".png") || it.endsWith(".webp") }

        val listRes = shell(
            context,
            "unzip -l \"$apkPath\" 2>/dev/null | awk '{print \$NF}' | " +
                "grep -E '^res/(mipmap|drawable)[^/]*/.+' | " +
                "grep -iE '(ic_launcher|ic_app|app_icon|icon).*\\.(png|webp)\$' || true",
            serial,
        )
        val entries = buildList {
            if (!badgingEntry.isNullOrBlank()) add(badgingEntry)
            listRes.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".png") || it.endsWith(".webp") }
                .forEach { add(it) }
        }.distinct()
        if (entries.isEmpty()) return null
        val best = pickBestIconEntry(entries)
        val args = buildList {
            if (!serial.isNullOrBlank()) {
                add("-s")
                add(serial.trim())
            }
            add("exec-out")
            add("unzip")
            add("-p")
            add(apkPath)
            add(best)
        }
        val (bytes, res) = runAdbBytes(context, args, timeoutSec = 15)
        if (!res.ok || bytes.isEmpty() || bytes.size < 64) return null
        val mime = if (best.endsWith(".webp")) "image/webp" else "image/png"
        return bytes to mime
    }

    private fun pickBestIconEntry(entries: List<String>): String =
        entries.maxByOrNull { e ->
            val density = when {
                e.contains("xxxhdpi") -> 6
                e.contains("xxhdpi") -> 5
                e.contains("xhdpi") -> 4
                e.contains("hdpi") -> 3
                e.contains("mdpi") -> 2
                e.contains("nodpi") -> 1
                else -> 0
            }
            val nameBonus = when {
                e.contains("ic_launcher", ignoreCase = true) -> 30
                e.contains("ic_app", ignoreCase = true) -> 20
                e.contains("app_icon", ignoreCase = true) -> 15
                else -> 0
            }
            val format = if (e.endsWith(".webp")) 5 else 0
            density * 10 + nameBonus + format
        } ?: entries.first()

    fun installBytes(
        context: Context,
        apkBytes: ByteArray,
        serial: String? = null,
        fileName: String = "upload.apk",
    ): CmdResult {
        if (apkBytes.isEmpty()) return CmdResult(false, -1, "", "", error = "empty_apk")
        if (apkBytes.size > 512 * 1024 * 1024) {
            return CmdResult(false, -1, "", "", error = "apk_too_large")
        }
        ensureReady(context)
        val dir = File(context.cacheDir, "adb-install")
        dir.mkdirs()
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload.apk" }
        val local = File(dir, safe.let { if (it.endsWith(".apk")) it else "$it.apk" })
        return try {
            local.writeBytes(apkBytes)
            install(context, local.absolutePath, serial)
        } catch (e: Exception) {
            CmdResult(false, -1, "", "", error = e.message ?: "write_failed")
        } finally {
            runCatching { local.delete() }
        }
    }

    private fun parsePackageNames(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("package:")) continue
            val rest = t.removePrefix("package:")
            // package:com.foo  OR  package:/path=com.foo
            val pkg = if ('=' in rest) rest.substringAfterLast('=') else rest
            if (pkg.isNotBlank()) out.add(pkg.trim())
        }
        return out
    }

    private fun parsePackagesWithPath(raw: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("package:")) continue
            val rest = t.removePrefix("package:")
            if ('=' in rest) {
                val path = rest.substringBefore('=')
                val pkg = rest.substringAfter('=')
                if (pkg.isNotBlank()) out.add(pkg.trim() to path.trim())
            } else if (rest.isNotBlank()) {
                out.add(rest.trim() to "")
            }
        }
        return out.distinctBy { it.first }.sortedBy { it.first.lowercase() }
    }

    // ------------------------------------------------------------------

    private fun rememberFromDevices(context: Context) {
        synchronized(adbLock) {
            replaceRememberedWithLiveLocked(context)
        }
    }

    /**
     * Live transports stay. Missing / offline wireless peers stay for [LOST_GRACE_MS]
     * then `adb disconnect` + drop from prefs. USB leftovers are not resurrected.
     */
    private fun replaceRememberedWithLiveLocked(context: Context, live: List<Device>? = null) {
        reconcileRememberedLocked(context, live)
    }

    private fun dropRememberedSerialLocked(context: Context, serial: String) {
        val key = serial.trim()
        if (key.isBlank()) return
        val arr = rememberedEntries(context)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("serial").ifBlank { o.optString("id").removePrefix("adb:") }.trim()
            if (s != key) next.put(o)
        }
        prefs(context).edit().putString(KEY_DEVICES, next.toString()).apply()
    }

    private fun reconcileRememberedLocked(context: Context, live: List<Device>? = null): List<Device> {
        val snapshot = live ?: run {
            val res = runAdb(context, listOf("devices", "-l"), timeoutSec = 8)
            val ignored = ignoredSerials(context)
            parseDevices(res.stdout + "\n" + res.stderr)
                .filter { it.serial !in ignored && !isSelfDevice(context, it) }
        }
        val now = System.currentTimeMillis()
        val ignored = ignoredSerials(context)
        val liveBySerial = snapshot.associateBy { it.serial }
        val present = snapshot.filter { isPresentAdbState(it.state) }
        val seen = present.map { it.serial }.toHashSet()
        val next = JSONArray()
        val out = ArrayList<Device>()
        for (d in present) {
            if (isNetworkSerial(d.serial)) {
                next.put(rememberedJson(d, now, lostAtMs = 0L))
            }
            out.add(d.copy(lostAtMs = 0L))
            seen.add(d.serial)
        }
        val mem = rememberedEntries(context)
        for (i in 0 until mem.length()) {
            val o = mem.optJSONObject(i) ?: continue
            val serial = o.optString("serial").ifBlank { o.optString("id").removePrefix("adb:") }.trim()
            if (serial.isBlank() || serial in ignored || serial in seen) continue
            if (!isNetworkSerial(serial)) continue
            val host = serial.substringBeforeLast(':')
            if (isLoopbackOrLocalHost(host, context)) continue
            val liveHit = liveBySerial[serial]
            if (liveHit != null && isPresentAdbState(liveHit.state)) continue
            var lostAt = o.optLong("lostAtMs", 0L)
            if (lostAt <= 0L) lostAt = now
            if (shouldAutoCleanLost(lostAt, now)) {
                runAdb(context, listOf("disconnect", serial), timeoutSec = 6)
                continue
            }
            val model = o.optString("model").ifBlank { liveHit?.model.orEmpty() }
            val lost = Device(
                serial = serial,
                state = liveHit?.state?.ifBlank { "offline" } ?: "offline",
                model = model,
                product = liveHit?.product.orEmpty(),
                lostAtMs = lostAt,
            )
            next.put(rememberedJson(lost, o.optLong("lastSeenMs", now), lostAtMs = lostAt))
            out.add(lost)
            seen.add(serial)
        }
        prefs(context).edit().putString(KEY_DEVICES, next.toString()).apply()
        return out
    }

    private fun reconnectRememberedLocked(context: Context) {
        val arr = rememberedEntries(context)
        if (arr.length() == 0) return
        val ignored = ignoredSerials(context)
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val serial = o.optString("serial").ifBlank { o.optString("id").removePrefix("adb:") }.trim()
            if (serial.isBlank() || serial in ignored) continue
            if (!isNetworkSerial(serial)) continue
            val host = serial.substringBeforeLast(':')
            if (isLoopbackOrLocalHost(host, context)) continue
            val lostAt = o.optLong("lostAtMs", 0L)
            if (shouldAutoCleanLost(lostAt, now)) continue
            runAdb(context, listOf("connect", serial), timeoutSec = 12)
        }
        reconcileRememberedLocked(context)
    }

    private fun rememberedJson(d: Device, now: Long, lostAtMs: Long = d.lostAtMs): JSONObject {
        val host = d.serial.substringBefore(':', missingDelimiterValue = "")
        val name = d.model.ifBlank { d.product }.ifBlank { d.serial }
        return JSONObject()
            .put("id", "adb:${d.serial}")
            .put("name", name)
            .put("host", host.ifBlank { d.serial })
            .put("serial", d.serial)
            .put("type", "adb")
            .put("identity", "adb")
            .put("local", false)
            .put("clusterEnabled", false)
            .put("consoleEnabled", false)
            .put("clusterPort", 0)
            .put("accessUrl", "")
            .put("lastSeenMs", now)
            .put("lostAtMs", if (lostAtMs > 0L) lostAtMs else 0L)
            .put("source", "adb")
            .put("state", d.state)
            .put("model", d.model)
    }

    /** Merge a currently-live peer into prefs. */
    private fun remember(context: Context, devices: List<Device>) {
        if (devices.isEmpty()) return
        val bySerial = linkedMapOf<String, JSONObject>()
        val existing = rememberedEntries(context)
        for (i in 0 until existing.length()) {
            val o = existing.optJSONObject(i) ?: continue
            val s = o.optString("serial").ifBlank { o.optString("id").removePrefix("adb:") }.trim()
            if (s.isNotBlank()) bySerial[s] = o
        }
        val now = System.currentTimeMillis()
        for (d in devices) {
            if (!isPresentAdbState(d.state) || !isNetworkSerial(d.serial)) continue
            bySerial[d.serial] = rememberedJson(d, now)
        }
        val out = JSONArray()
        for (o in bySerial.values) out.put(o)
        prefs(context).edit().putString(KEY_DEVICES, out.toString()).apply()
    }

    fun parseDevices(raw: String): List<Device> {
        val out = ArrayList<Device>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("List of devices")) continue
            // serial state [usb:…] [product:…] [model:…] [device:…] [transport_id:…]
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val serial = parts[0]
            val state = parts[1]
            if (serial == "adb" || serial.startsWith("*")) continue
            fun prop(key: String): String {
                val p = parts.find { it.startsWith("$key:") } ?: return ""
                return p.substringAfter(':', "")
            }
            out.add(
                Device(
                    serial = serial,
                    state = state,
                    model = prop("model").replace('_', ' '),
                    product = prop("product"),
                    transportId = prop("transport_id"),
                ),
            )
        }
        return out
    }

    /**
     * Ensure [filesDir]/adbhost/adb exists, is verified, and is executable.
     * Downloads from GitHub when missing (zh/ru use proxy mirrors first).
     */
    private fun ensureBinary(context: Context): CmdResult {
        synchronized(downloadLock) {
            val dest = adbFile(context)
            val home = homeDir(context)
            if (!home.isDirectory && !home.mkdirs()) {
                return CmdResult(false, -1, "", "", error = "mkdir_failed")
            }
            if (isBinaryReady(context)) {
                return CmdResult(true, 0, dest.absolutePath, "")
            }
            // Present but not executable / unverified — chmod + sha before re-fetch.
            if (dest.isFile && dest.length() == EXPECTED_BYTES) {
                val localSha = runCatching { sha256Hex(dest) }.getOrNull()
                if (localSha != null && localSha.equals(EXPECTED_SHA256, ignoreCase = true) &&
                    markExecutable(dest)
                ) {
                    downloadError.set(null)
                    lastError.set(null)
                    return CmdResult(true, 0, dest.absolutePath, "")
                }
            }
            // Stale / partial leftover — remove before fetch.
            if (dest.exists()) {
                runCatching { dest.delete() }
            }
            downloading.set(true)
            downloadPercent.set(0)
            downloadError.set(null)
            try {
                val downloaded = downloadAdbBinary(context, dest)
                if (!downloaded.ok) {
                    lastError.set(downloaded.error)
                    downloadError.set(downloaded.error)
                    return downloaded
                }
                if (!markExecutable(dest)) {
                    lastError.set("not_executable")
                    downloadError.set("not_executable")
                    return CmdResult(false, -1, "", "", error = "not_executable")
                }
                downloadPercent.set(100)
                downloadError.set(null)
                lastError.set(null)
                Log.i(TAG, "adb binary ready at ${dest.absolutePath} (${dest.length()} bytes)")
                return CmdResult(true, 0, dest.absolutePath, "")
            } finally {
                downloading.set(false)
                if (downloadPercent.get() < 100 && downloadError.get() == null) {
                    downloadPercent.set(-1)
                }
            }
        }
    }

    private fun markExecutable(dest: File): Boolean {
        if (!dest.setExecutable(true, false)) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
            }
        }
        return dest.canExecute()
    }

    private fun downloadAdbBinary(context: Context, dest: File): CmdResult {
        val part = File(dest.parentFile, "${dest.name}.part")
        runCatching { if (part.exists()) part.delete() }
        var lastFail: String? = null
        for (url in downloadCandidateUrls(context)) {
            try {
                Log.i(TAG, "fetching adb binary from $url")
                downloadFile(url, part) { read, total ->
                    val pct = if (total > 0L) {
                        ((read * 100L) / total).toInt().coerceIn(0, 99)
                    } else {
                        // Unknown length — pulse by megabytes read.
                        ((read / (256L * 1024L)) % 90L).toInt().coerceIn(1, 90)
                    }
                    downloadPercent.set(pct)
                }
                if (part.length() < MIN_BYTES) {
                    lastFail = "download_too_small:${part.length()}"
                    part.delete()
                    continue
                }
                if (EXPECTED_BYTES > 0L && part.length() != EXPECTED_BYTES) {
                    lastFail = "download_size_mismatch:${part.length()}"
                    part.delete()
                    continue
                }
                val sha = sha256Hex(part)
                if (!sha.equals(EXPECTED_SHA256, ignoreCase = true)) {
                    lastFail = "download_sha256_mismatch"
                    Log.e(TAG, "sha256 mismatch got=$sha expect=$EXPECTED_SHA256")
                    part.delete()
                    continue
                }
                if (dest.exists() && !dest.delete()) {
                    lastFail = "replace_failed"
                    part.delete()
                    continue
                }
                if (!part.renameTo(dest)) {
                    // Cross-filesystem rename fallback.
                    part.inputStream().use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    part.delete()
                    if (dest.length() != EXPECTED_BYTES) {
                        lastFail = "finalize_failed"
                        dest.delete()
                        continue
                    }
                }
                return CmdResult(true, 0, dest.absolutePath, "")
            } catch (e: Exception) {
                Log.w(TAG, "download failed from $url: ${e.message}")
                lastFail = e.message ?: "download_failed"
                runCatching { if (part.exists()) part.delete() }
            }
        }
        return CmdResult(false, -1, "", "", error = lastFail ?: "download_failed")
    }

    private fun downloadCandidateUrls(context: Context): List<String> =
        GithubProxyUrls.candidates(context, REMOTE_ADB_URL)

    private fun downloadFile(
        urlString: String,
        destination: File,
        onProgress: (read: Long, total: Long) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = openDownloadConnection(urlString)
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Ava-FleetAdbHost/1.0")
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $urlString")
            }
            val total = connection.contentLengthLong.coerceAtLeast(-1L)
            val buffer = ByteArray(64 * 1024)
            var readTotal = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        onProgress(readTotal, total)
                    }
                    output.fd.sync()
                }
            }
            if (destination.length() <= 0L) {
                throw IllegalStateException("empty download")
            }
        } finally {
            connection?.disconnect()
        }
    }

    /** Trust-all for legacy CA gaps on proxy mirrors. */
    private fun openDownloadConnection(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            val trustAll = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return connection
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun runAdb(context: Context, args: List<String>, timeoutSec: Int): CmdResult {
        val started = System.currentTimeMillis()
        return try {
            val pb = processBuilder(context, args)
            val proc = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            // Must swallow IO errors: destroy/timeout/peer-drop interrupts pipe reads.
            // Uncaught InterruptedIOException on these threads kills the whole process.
            val outT = drainThread("fleet-adb-out") {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    r.lineSequence().forEach { stdout.append(it).append('\n') }
                }
            }
            val errT = drainThread("fleet-adb-err") {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach { stderr.append(it).append('\n') }
                }
            }
            outT.start(); errT.start()
            val finished = proc.waitFor(timeoutSec.toLong().coerceIn(1, 180), TimeUnit.SECONDS)
            if (!finished) {
                terminateProcess(proc, outT, errT)
                lastError.set("timeout")
                return CmdResult(
                    false, -1, stdout.toString(), stderr.toString(),
                    System.currentTimeMillis() - started, "timeout",
                )
            }
            joinDrain(outT, errT)
            val code = proc.exitValue()
            CmdResult(
                ok = code == 0,
                code = code,
                stdout = stdout.toString().trimEnd(),
                stderr = stderr.toString().trimEnd(),
                elapsedMs = System.currentTimeMillis() - started,
            )
        } catch (e: Exception) {
            Log.e(TAG, "adb ${args.joinToString(" ")} failed", e)
            lastError.set(e.message)
            CmdResult(false, -1, "", "", System.currentTimeMillis() - started, e.message)
        }
    }

    /** Run adb capturing raw stdout bytes (for binary output like `exec-out cat`). */
    private fun runAdbBytes(context: Context, args: List<String>, timeoutSec: Int): Pair<ByteArray, CmdResult> {
        val started = System.currentTimeMillis()
        return try {
            val proc = processBuilder(context, args).start()
            val stdout = ByteArrayOutputStream()
            val stderr = StringBuilder()
            val outT = drainThread("fleet-adb-bytes-out") {
                proc.inputStream.use { it.copyTo(stdout) }
            }
            val errT = drainThread("fleet-adb-bytes-err") {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach { stderr.append(it).append('\n') }
                }
            }
            outT.start(); errT.start()
            val finished = proc.waitFor(timeoutSec.toLong().coerceIn(1, 180), TimeUnit.SECONDS)
            if (!finished) {
                terminateProcess(proc, outT, errT)
                return ByteArray(0) to CmdResult(
                    false, -1, "", stderr.toString(),
                    System.currentTimeMillis() - started, "timeout",
                )
            }
            joinDrain(outT, errT)
            val code = proc.exitValue()
            stdout.toByteArray() to CmdResult(
                ok = code == 0,
                code = code,
                stdout = "",
                stderr = stderr.toString().trimEnd(),
                elapsedMs = System.currentTimeMillis() - started,
            )
        } catch (e: Exception) {
            Log.e(TAG, "adb-bytes ${args.joinToString(" ")} failed", e)
            lastError.set(e.message)
            ByteArray(0) to CmdResult(false, -1, "", "", System.currentTimeMillis() - started, e.message)
        }
    }

    /** Reader threads must never throw — Android treats that as a fatal process crash. */
    private fun drainThread(name: String, block: () -> Unit): Thread =
        Thread({
            try {
                block()
            } catch (_: Exception) {
                // Closed pipe / interrupt / process death — expected during timeout & peer drop.
            }
        }, name).apply { isDaemon = true }

    private fun joinDrain(vararg threads: Thread) {
        for (t in threads) {
            try {
                t.join(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun terminateProcess(proc: Process, vararg drains: Thread) {
        runCatching { proc.destroy() }
        runCatching {
            if (!proc.waitFor(500L, TimeUnit.MILLISECONDS) && Build.VERSION.SDK_INT >= 26) {
                proc.destroyForcibly()
            }
        }
        for (t in drains) {
            runCatching { t.interrupt() }
        }
        joinDrain(*drains)
        runCatching {
            proc.inputStream.close()
            proc.errorStream.close()
            proc.outputStream.close()
        }
    }

    private fun processBuilder(context: Context, args: List<String>): ProcessBuilder {
        val bin = adbFile(context)
        val home = homeDir(context)
        val port = serverPort.get()
        val cmd = ArrayList<String>(args.size + 3)
        cmd.add(bin.absolutePath)
        cmd.add("-P")
        cmd.add(port.toString())
        cmd.addAll(args)
        return ProcessBuilder(cmd).apply {
            directory(home)
            environment()["HOME"] = home.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            // Prefer app-private keys; avoid colliding with system adb if any.
            environment()["ADB_VENDOR_KEYS"] = File(home, "adb_keys").absolutePath
            redirectErrorStream(false)
        }
    }

    private fun homeDir(context: Context): File = File(context.filesDir, "adbhost")

    private fun adbFile(context: Context): File = File(homeDir(context), BIN_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
