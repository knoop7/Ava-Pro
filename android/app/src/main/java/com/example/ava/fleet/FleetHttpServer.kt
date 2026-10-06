package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.services.AccessibilityBridge
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight LAN HTTP server for Ava cluster management.
 *
 * Static console load order (first hit wins):
 * 1. hot dir `…/files/fleet-console/`
 * 2. assets/fleet/
 */
class FleetHttpServer(
    private val context: Context,
    private val port: Int,
    serveWebConsole: Boolean = false,
) {
    companion object {
        private const val TAG = "FleetHttpServer"
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val ASSET_PREFIX = "fleet"
        private const val HOT_DIR_NAME = "fleet-console"
        private val ALLOWED_STATIC = setOf(
            "index.html",
            "app.css",
            "app.js",
            "i18n.js",
            "ava-icon.webp",
            "ava-icon-light.webp",
            "xterm.js",
            "xterm.css",
            "xterm-fit.js",
        )
        private const val MJPEG_BOUNDARY = "avaframe"
        private const val MAX_BODY_BYTES = 128 * 1024 * 1024
    }

    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val activeClients = AtomicInteger(0)
    @Volatile
    private var executor = newWorkerPool()
    @Volatile
    private var serveWebConsole: Boolean = serveWebConsole

    fun isRunning(): Boolean = running.get() && isListenSocketOpen()

    fun isListenSocketOpen(): Boolean {
        val ss = serverSocket
        return ss != null && !ss.isClosed
    }

    /** True when the accept loop is still alive (socket open alone is not enough). */
    fun isHealthy(): Boolean {
        val thread = acceptThread
        return isRunning() && thread != null && thread.isAlive
    }

    fun setServeWebConsole(enabled: Boolean) {
        serveWebConsole = enabled
    }

    fun isServingWebConsole(): Boolean = serveWebConsole

    /**
     * Bind on the calling thread, then accept in a daemon thread.
     * Retries briefly when the port is still releasing after a prior stop.
     * @return true if listening; false if bind failed (port still held, etc.).
     */
    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (running.get() && isListenSocketOpen()) return true
            // Previous instance half-dead — clean before re-bind.
            if (running.get() || serverSocket != null || acceptThread != null) {
                stopLocked(joinMs = 1_000L, log = false)
            }
            if (executor.isShutdown) executor = newWorkerPool()

            var bound: ServerSocket? = null
            var lastError: Exception? = null
            for (attempt in 0 until 8) {
                try {
                    // reuseAddress MUST be set before bind; ServerSocket(port) binds in the ctor.
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    // Wake accept() periodically so stop() never depends on close() alone.
                    ss.soTimeout = 1_000
                    ss.bind(InetSocketAddress(port))
                    bound = ss
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "bind :$port attempt ${attempt + 1}/8 failed: ${e.message}")
                    try {
                        Thread.sleep(50L + attempt * 50L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            val ss = bound
            if (ss == null) {
                Log.e(TAG, "Failed to start fleet server on port $port", lastError)
                running.set(false)
                return false
            }

            running.set(true)
            serverSocket = ss
            Log.i(TAG, "Listening on port $port")

            val thread = Thread(
                {
                    try {
                        while (running.get()) {
                            val client = try {
                                serverSocket?.accept()
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            } catch (_: Exception) {
                                // Closed during stop, or transient listen error.
                                if (!running.get()) break
                                // Don't kill the whole server on a single accept glitch —
                                // that forced replace→stop and previously hung fleet apply.
                                Log.w(TAG, "accept() failed while running; retrying")
                                try {
                                    Thread.sleep(50)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                                continue
                            } ?: break
                            if (!running.get()) {
                                client.closeQuietly()
                                break
                            }
                            if (activeClients.get() >= 12) {
                                client.closeQuietly()
                                continue
                            }
                            activeClients.incrementAndGet()
                            val pool = executor
                            if (pool.isShutdown) {
                                client.closeQuietly()
                                activeClients.decrementAndGet()
                                continue
                            }
                            pool.execute {
                                try {
                                    handleClient(client)
                                } finally {
                                    activeClients.decrementAndGet()
                                }
                            }
                        }
                    } finally {
                        // Only release the listen socket here. Heavy cleanup stays in stop()
                        // so join() is not blocked by scrcpy/shell and we never drop the
                        // ServerSocket reference without closing it.
                        running.set(false)
                        closeListenSocket()
                    }
                },
                "FleetHttpServer",
            )
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
            return true
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopLocked(joinMs = 5_000L, log = true)
        }
    }

    private fun stopLocked(joinMs: Long, log: Boolean) {
        running.set(false)
        val thread = acceptThread
        // Close listen socket first so accept() unblocks / soTimeout loop exits.
        closeListenSocket()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(joinMs) }
                .onFailure { Log.w(TAG, "accept thread join: ${it.message}") }
            if (thread.isAlive) {
                Log.w(TAG, "accept thread still alive after ${joinMs}ms; listen socket closed")
            }
        }
        acceptThread = null
        // Drop any leaked ref only after close attempts.
        closeListenSocket()
        serverSocket = null
        runCatching { executor.shutdownNow() }
        if (executor.isShutdown) {
            executor = newWorkerPool()
        }
        // Drop listen socket / accept thread only. Scrcpy teardown belongs to FleetManager /
        // screen stop — never hang HTTP stop on su/pkill.
        runCatching { FleetScreenShot.clear() }
        if (log) Log.i(TAG, "Stopped fleet server on port $port")
    }

    private fun closeListenSocket() {
        val ss = serverSocket
        if (ss == null) return
        try {
            ss.close()
        } catch (e: Exception) {
            Log.w(TAG, "listen socket close: ${e.message}")
        }
    }

    private fun newWorkerPool() = Executors.newCachedThreadPool { r ->
        Thread(r, "FleetHttpWorker").apply { isDaemon = true }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 12_000
                val input = BufferedInputStream(socket.getInputStream())
                val request = readRequest(input) ?: return
                val pathOnly = request.path.substringBefore('?')
                val query = parseQuery(request.path.substringAfter('?', ""))
                when (pathOnly) {
                    "/v1/events" -> {
                        requireFleetAuth(request, query, pathOnly)?.let {
                            writeResponse(socket, it)
                            return
                        }
                        // Finite read timeout so half-open peers don't pin a worker forever.
                        socket.soTimeout = 45_000
                        writeSse(socket, request)
                    }
                    "/v1/screen/mjpeg" -> {
                        requireFleetAuth(request, query, pathOnly)?.let {
                            writeResponse(socket, it)
                            return
                        }
                        socket.soTimeout = 45_000
                        writeMjpeg(socket, request)
                    }
                    else -> {
                        val response = route(request)
                        writeResponse(socket, response)
                    }
                }
            } catch (e: SocketException) {
                // client disconnected
            } catch (e: Exception) {
                Log.w(TAG, "Fleet request failed", e)
            }
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.path.substringBefore('?')
        val query = parseQuery(request.path.substringAfter('?', ""))

        if (request.method == "OPTIONS") {
            return corsPreflightResponse()
        }

        requireFleetAuth(request, query, path)?.let { return it }

        if (request.method == "POST") {
            return routePost(path, query, request)
        }
        if (request.method != "GET" && request.method != "HEAD") {
            return textResponse(405, "Method Not Allowed", "Method Not Allowed")
        }

        val response = when (path) {
            "/v1/hello" -> jsonResponse(200, FleetStatusBuilder.hello(context, port))
            "/v1/auth/check" -> jsonResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("auth", true)
                    .put("scheme", "password_b64_16")
                    .toString(),
            )
            "/v1/status", "/api/status" ->
                jsonResponse(200, FleetStatusBuilder.status(context, port))
            "/v1/devices" ->
                jsonResponse(200, FleetDeviceDirectory.devicesJson(context, port).toString())
            "/v1/telemetry", "/v1/metrics" ->
                jsonResponse(200, FleetTelemetry.snapshot(context, includeSlowSensors = true).toString())
            "/v1/screen/frame.jpg", "/v1/screen/frame" -> handleFrame(query)
            "/v1/cluster/screen", "/v1/cluster/screen.jpg" -> handleClusterScreen(query)
            "/v1/screen/last.jpg", "/v1/screen/last" -> handleLastScreenShot()
            "/v1/settings" -> handleGetSettings()
            "/v1/settings/schema" -> handleGetSettingsSchema()
            "/v1/settings/export" -> handleGetSettingsExport()
            "/v1/settings/ha" -> handleGetSettingsHa()
            "/v1/logs" -> handleGetLogs(query)
            "/v1/shell" -> jsonResponse(200, FleetShell.statusJson().put("ok", true).toString())
            "/v1/adb" -> jsonResponse(200, FleetAdbHost.statusJson(context).toString())
            "/v1/adb/devices" -> jsonResponse(200, FleetAdbHost.devicesJson(context).toString())
            "/v1/adb/screen", "/v1/adb/screen.jpg" -> handleAdbScreen(query)
            "/v1/adb/apps" -> handleAdbAppsList(query)
            "/v1/adb/apps/detail" -> handleAdbAppDetail(query)
            "/v1/adb/apps/apk" -> handleAdbAppApk(query)
            "/v1/adb/apps/icon" -> handleAdbAppIcon(query)
            "/v1/adb/files/roots" -> handleAdbFilesRoots(query)
            "/v1/adb/files/list" -> handleAdbFilesList(query)
            "/v1/adb/files/download" -> handleAdbFilesDownload(query)
            "/v1/adb/logs" -> handleAdbLogs(query)
            "/v1/adb/telemetry", "/v1/adb/metrics" -> handleAdbTelemetry(query)
            "/v1/screen/scrcpy" -> jsonResponse(200, FleetScrcpyServer.statusJson(context).toString())
            "/", "/index", "/index.html" -> if (serveWebConsole) {
                assetResponse("index.html")
            } else {
                jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("agent", true)
                        .put("console", false)
                        .put("message", "Ava fleet agent (website console off)")
                        .toString(),
                )
            }
            "/app.css", "/app.js", "/i18n.js",
            "/ava-icon.webp", "/ava-icon-light.webp",
            "/xterm.js", "/xterm.css", "/xterm-fit.js",
            -> if (serveWebConsole) {
                assetResponse(path.removePrefix("/"))
            } else {
                textResponse(404, "Not Found", "Website console disabled")
            }
            else -> textResponse(404, "Not Found", "Not Found")
        }
        return if (request.method == "HEAD") response.copy(body = ByteArray(0)) else response
    }

    /**
     * Sensitive routes require [FleetAuth] when a token is configured.
     * Always open: hello, static console assets, OPTIONS.
     * Status/events/devices stay readable so discovery works; writes + screen + shell + export are gated.
     */
    private fun requireFleetAuth(request: HttpRequest, query: Map<String, String>, path: String): HttpResponse? {
        if (path == "/v1/hello" || path == "/" || path == "/index" || path == "/index.html") return null
        if (path in setOf(
            "/app.css", "/app.js", "/i18n.js",
            "/ava-icon.webp", "/ava-icon-light.webp",
            "/xterm.js", "/xterm.css", "/xterm-fit.js",
        )) return null
        val openReads = setOf(
            "/v1/status", "/api/status", "/v1/devices", "/v1/telemetry", "/v1/metrics",
            "/v1/events", "/v1/settings/schema",
        )
        if (request.method == "GET" || request.method == "HEAD") {
            if (path in openReads) return null
        }
        return when (val result = FleetAuth.check(context, request.headers, query)) {
            is FleetAuth.AuthResult.Ok -> null
            is FleetAuth.AuthResult.Denied -> jsonResponse(
                401,
                JSONObject()
                    .put("ok", false)
                    .put("error", result.error)
                    .put("auth", FleetAuth.statusJson(context))
                    .toString(),
            )
        }
    }

    private fun routePost(path: String, query: Map<String, String>, request: HttpRequest): HttpResponse {
        return when (path) {
            "/v1/input/tap" -> handleTap(query)
            "/v1/input/swipe" -> handleSwipe(query)
            "/v1/input/key" -> handleKey(query)
            "/v1/settings/import" -> handlePostSettingsImport(request, query)
            "/v1/settings/apply" -> handlePostSettingsApply(request)
            "/v1/cluster/probe" -> handleClusterProbe(request)
            "/v1/cluster/pull-settings" -> handleClusterPullSettings(request)
            "/v1/cluster/push-settings" -> handleClusterPushSettings(request)
            "/v1/cluster/pull-logs" -> handleClusterPullLogs(request)
            "/v1/screen/scrcpy/start" -> handleScrcpyStart()
            "/v1/screen/scrcpy/stop" -> handleScrcpyStop()
            "/v1/screen/capture" -> handleScreenCapture(query)
            // User-initiated only — never call from auto-retry loops.
            "/v1/accessibility/open-settings" -> handleAccessibilityOpenSettings()
            "/v1/shell/exec" -> handleShellExec(request)
            "/v1/cluster/shell" -> handleClusterShell(request)
            "/v1/adb/start" -> handleAdbStart()
            "/v1/adb/download" -> handleAdbDownload()
            "/v1/adb/pair" -> handleAdbPair(request)
            "/v1/adb/connect" -> handleAdbConnect(request)
            "/v1/adb/disconnect" -> handleAdbDisconnect(request)
            "/v1/adb/forget" -> handleAdbForget(request)
            "/v1/adb/install" -> handleAdbInstall(request)
            "/v1/adb/apps/action" -> handleAdbAppAction(request)
            "/v1/adb/apps/install" -> handleAdbAppsInstall(request, query)
            "/v1/adb/files/mkdir" -> handleAdbFilesMkdir(request)
            "/v1/adb/files/delete" -> handleAdbFilesDelete(request)
            "/v1/adb/files/rename" -> handleAdbFilesRename(request)
            "/v1/adb/files/upload" -> handleAdbFilesUpload(request, query)
            "/v1/adb/shell" -> handleAdbShell(request)
            "/v1/adb/input/tap" -> handleAdbInputTap(query, request)
            "/v1/adb/input/swipe" -> handleAdbInputSwipe(query, request)
            "/v1/adb/input/key" -> handleAdbInputKey(query, request)
            else -> textResponse(404, "Not Found", "Not Found")
        }
    }

    private fun adbSerialFrom(query: Map<String, String>, body: JSONObject?): String? {
        val q = query["serial"]?.trim()?.ifBlank { null }
        if (!q.isNullOrBlank()) return q
        val b = body?.optString("serial")?.trim()?.ifBlank { null }
        return b
    }

    private fun handleAdbShell(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val command = body.optString("command").trim()
            if (command.isEmpty()) {
                return jsonResponse(400, """{"ok":false,"error":"empty_command"}""")
            }
            val serial = body.optString("serial").trim().ifBlank { null }
            val res = FleetAdbHost.shell(context, command, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .put("serial", serial ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/shell failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "shell_failed").toString())
        }
    }

    private fun handleAdbInputTap(query: Map<String, String>, request: HttpRequest): HttpResponse {
        return try {
            val body = runCatching { JSONObject(request.bodyText() ?: "{}") }.getOrNull()
            val serial = adbSerialFrom(query, body)
            if (serial.isNullOrBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_serial"}""")
            }
            val x = query["x"]?.toIntOrNull() ?: body?.optInt("x", -1) ?: -1
            val y = query["y"]?.toIntOrNull() ?: body?.optInt("y", -1) ?: -1
            val res = FleetAdbHost.inputTap(context, x, y, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("x", x)
                    .put("y", y)
                    .put("via", "adb")
                    .put("serial", serial)
                    .put("error", res.error ?: res.stderr.ifBlank { JSONObject.NULL })
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/input/tap failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "tap_failed").toString())
        }
    }

    private fun handleAdbInputSwipe(query: Map<String, String>, request: HttpRequest): HttpResponse {
        return try {
            val body = runCatching { JSONObject(request.bodyText() ?: "{}") }.getOrNull()
            val serial = adbSerialFrom(query, body)
            if (serial.isNullOrBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_serial"}""")
            }
            val x1 = query["x1"]?.toIntOrNull() ?: body?.optInt("x1", -1) ?: -1
            val y1 = query["y1"]?.toIntOrNull() ?: body?.optInt("y1", -1) ?: -1
            val x2 = query["x2"]?.toIntOrNull() ?: body?.optInt("x2", -1) ?: -1
            val y2 = query["y2"]?.toIntOrNull() ?: body?.optInt("y2", -1) ?: -1
            val durationMs = query["durationMs"]?.toIntOrNull()
                ?: body?.optInt("durationMs", 300)
                ?: 300
            val res = FleetAdbHost.inputSwipe(context, x1, y1, x2, y2, durationMs, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("via", "adb")
                    .put("serial", serial)
                    .put("error", res.error ?: res.stderr.ifBlank { JSONObject.NULL })
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/input/swipe failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "swipe_failed").toString())
        }
    }

    private fun handleAdbInputKey(query: Map<String, String>, request: HttpRequest): HttpResponse {
        return try {
            val body = runCatching { JSONObject(request.bodyText() ?: "{}") }.getOrNull()
            val serial = adbSerialFrom(query, body)
            if (serial.isNullOrBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_serial"}""")
            }
            val key = query["key"]?.trim()?.ifBlank { null }
                ?: body?.optString("key")?.trim()?.ifBlank { null }
                ?: "BACK"
            val res = FleetAdbHost.inputKey(context, key, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("key", key)
                    .put("via", "adb")
                    .put("serial", serial)
                    .put("error", res.error ?: res.stderr.ifBlank { JSONObject.NULL })
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/input/key failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "key_failed").toString())
        }
    }

    private fun handleAdbScreen(query: Map<String, String>): HttpResponse {
        return try {
            val serial = query["serial"]?.trim().orEmpty()
            if (serial.isBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_serial"}""")
            }
            val maxWidth = query["maxWidth"]?.toIntOrNull()?.coerceIn(160, 960) ?: 480
            val quality = query["quality"]?.toIntOrNull()?.coerceIn(25, 80) ?: 40
            val force = query["force"] == "1"
            val shot = FleetAdbHost.captureScreenJpeg(
                context = context,
                serial = serial,
                maxWidth = maxWidth,
                quality = quality,
                force = force,
            )
            if (shot == null) {
                return jsonResponse(
                    503,
                    JSONObject()
                        .put("ok", false)
                        .put("error", FleetAdbHost.statusJson(context).opt("lastError") ?: "screencap_failed")
                        .toString(),
                )
            }
            HttpResponse(
                status = 200,
                reason = "OK",
                contentType = "image/jpeg",
                body = shot.jpeg,
                cacheControl = "no-store",
                extraHeaders = mapOf(
                    "X-Ava-Screen-Width" to shot.width.toString(),
                    "X-Ava-Screen-Height" to shot.height.toString(),
                    "X-Ava-Screen-Mode" to if (shot.cached) "adb-screencap-cache" else "adb-screencap",
                    "X-Ava-Adb-Serial" to shot.serial,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/screen failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "screencap_failed").toString())
        }
    }

    private fun handleAdbTelemetry(query: Map<String, String>): HttpResponse {
        return try {
            val serial = query["serial"]?.trim().orEmpty()
            if (serial.isEmpty()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_serial"}""")
            }
            val deep = query["deep"] == "1" || query["deep"] == "true"
            val lite = query["lite"] == "1" || query["lite"] == "true"
            val uptimeOnly = query["uptime"] == "1" || query["uptime"] == "true"
            val snap = FleetAdbTelemetry.snapshot(
                context,
                serial,
                deep = deep,
                lite = lite && !deep && !uptimeOnly,
                uptimeOnly = uptimeOnly && !deep,
            )
            val ok = snap.optBoolean("ok", false)
            jsonResponse(if (ok) 200 else 503, snap.toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/telemetry failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "telemetry_failed").toString())
        }
    }

    private fun handleAdbAppsList(query: Map<String, String>): HttpResponse {
        return try {
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val filter = query["filter"]?.trim()?.ifBlank { "user" } ?: "user"
            jsonResponse(200, FleetAdbHost.listApps(context, serial, filter).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/apps failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "list_failed").toString())
        }
    }

    private fun handleAdbAppDetail(query: Map<String, String>): HttpResponse {
        return try {
            val pkg = query["package"]?.trim().orEmpty()
            if (pkg.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_package"}""")
            val serial = query["serial"]?.trim()?.ifBlank { null }
            jsonResponse(200, FleetAdbHost.appDetail(context, pkg, serial).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/apps/detail failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "detail_failed").toString())
        }
    }

    private fun handleAdbAppApk(query: Map<String, String>): HttpResponse {
        return try {
            val pkg = query["package"]?.trim().orEmpty()
            if (pkg.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_package"}""")
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val (res, file) = FleetAdbHost.pullApk(context, pkg, serial)
            if (file == null || !file.isFile) {
                return jsonResponse(
                    404,
                    JSONObject()
                        .put("ok", false)
                        .put("error", res.error ?: "pull_failed")
                        .put("stdout", res.stdout)
                        .put("stderr", res.stderr)
                        .toString(),
                )
            }
            val bytes = file.readBytes()
            HttpResponse(
                status = 200,
                reason = "OK",
                contentType = "application/vnd.android.package-archive",
                body = bytes,
                cacheControl = "no-store",
                extraHeaders = mapOf(
                    "Content-Disposition" to "attachment; filename=\"${pkg}.apk\"",
                    "X-Ava-Apk-Package" to pkg,
                    "X-Ava-Apk-Bytes" to bytes.size.toString(),
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/apps/apk failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "apk_failed").toString())
        }
    }

    private fun handleAdbAppIcon(query: Map<String, String>): HttpResponse {
        return try {
            val pkg = query["package"]?.trim().orEmpty()
            if (pkg.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_package"}""")
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val result = FleetAdbHost.appIcon(context, pkg, serial)
            if (result == null) {
                // 204: missing icon is normal (adaptive-only APKs etc.) — avoid console 404 spam.
                HttpResponse(
                    status = 204,
                    reason = "No Content",
                    contentType = "image/png",
                    body = ByteArray(0),
                    cacheControl = "no-store",
                )
            } else {
                val (bytes, mime) = result
                HttpResponse(
                    status = 200,
                    reason = "OK",
                    contentType = mime,
                    body = bytes,
                    cacheControl = "public, max-age=86400",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/apps/icon failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "icon_failed").toString())
        }
    }

    private fun handleAdbAppAction(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val pkg = body.optString("package").trim()
            val action = body.optString("action").trim()
            val serial = body.optString("serial").trim().ifBlank { null }
            val res = FleetAdbHost.appAction(context, pkg, action, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("action", action)
                    .put("package", pkg)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/apps/action failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "action_failed").toString())
        }
    }

    private fun handleAdbAppsInstall(request: HttpRequest, query: Map<String, String>): HttpResponse {
        return try {
            val bytes = request.body
            val ct = request.headers["content-type"]?.lowercase().orEmpty()
            val looksJson = ct.contains("json") ||
                (bytes != null && bytes.isNotEmpty() && bytes[0] == '{'.code.toByte())
            if (looksJson || bytes == null || bytes.isEmpty()) {
                val body = JSONObject(request.bodyText() ?: "{}")
                val path = body.optString("path").trim()
                val serial = query["serial"]?.trim()?.ifBlank { null }
                    ?: body.optString("serial").trim().ifBlank { null }
                if (path.isBlank()) {
                    return jsonResponse(400, """{"ok":false,"error":"missing_apk_body_or_path"}""")
                }
                val res = FleetAdbHost.install(context, path, serial)
                return jsonResponse(
                    if (res.ok) 200 else 400,
                    JSONObject()
                        .put("ok", res.ok)
                        .put("code", res.code)
                        .put("stdout", res.stdout)
                        .put("stderr", res.stderr)
                        .put("error", res.error ?: JSONObject.NULL)
                        .toString(),
                )
            }
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val name = query["name"]?.trim()?.ifBlank { "upload.apk" } ?: "upload.apk"
            val res = FleetAdbHost.installBytes(context, bytes, serial, name)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .put("bytes", bytes.size)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/apps/install failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "install_failed").toString())
        }
    }

    private fun handleAdbFilesRoots(query: Map<String, String>): HttpResponse {
        return try {
            val serial = query["serial"]?.trim()?.ifBlank { null }
            jsonResponse(200, FleetAdbHost.listStorageRoots(context, serial).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/files/roots failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "roots_failed").toString())
        }
    }

    private fun handleAdbFilesList(query: Map<String, String>): HttpResponse {
        return try {
            val path = query["path"]?.trim().orEmpty().ifBlank { "/sdcard" }
            val serial = query["serial"]?.trim()?.ifBlank { null }
            jsonResponse(200, FleetAdbHost.listDir(context, path, serial).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/files/list failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "list_failed").toString())
        }
    }

    private fun handleAdbFilesDownload(query: Map<String, String>): HttpResponse {
        return try {
            val path = query["path"]?.trim().orEmpty()
            if (path.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_path"}""")
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val (res, bytes) = FleetAdbHost.pullBytes(context, path, serial)
            if (bytes == null) {
                return jsonResponse(
                    404,
                    JSONObject()
                        .put("ok", false)
                        .put("error", res.error ?: "download_failed")
                        .put("stdout", res.stdout)
                        .put("stderr", res.stderr)
                        .toString(),
                )
            }
            val name = path.substringAfterLast('/').ifBlank { "file.bin" }
            val mime = when {
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                name.endsWith(".webp", true) -> "image/webp"
                name.endsWith(".gif", true) -> "image/gif"
                name.endsWith(".mp4", true) -> "video/mp4"
                name.endsWith(".txt", true) || name.endsWith(".log", true) -> "text/plain; charset=utf-8"
                name.endsWith(".json", true) -> "application/json"
                name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
            HttpResponse(
                status = 200,
                reason = "OK",
                contentType = mime,
                body = bytes,
                cacheControl = "no-store",
                extraHeaders = mapOf(
                    "Content-Disposition" to "attachment; filename=\"$name\"",
                    "X-Ava-File-Path" to path,
                    "X-Ava-File-Bytes" to bytes.size.toString(),
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/files/download failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "download_failed").toString())
        }
    }

    private fun handleAdbLogs(query: Map<String, String>): HttpResponse {
        return try {
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val limit = query["limit"]?.toIntOrNull() ?: 200
            jsonResponse(200, FleetAdbHost.dumpLogcat(context, serial, limit).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/adb/logs failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "logs_failed").toString())
        }
    }

    private fun handleAdbFilesMkdir(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val path = body.optString("path").trim()
            if (path.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_path"}""")
            val serial = body.optString("serial").trim().ifBlank { null }
            val res = FleetAdbHost.mkdir(context, path, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("path", path)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/files/mkdir failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "mkdir_failed").toString())
        }
    }

    private fun handleAdbFilesDelete(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val path = body.optString("path").trim()
            if (path.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_path"}""")
            val serial = body.optString("serial").trim().ifBlank { null }
            val res = FleetAdbHost.deletePath(context, path, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("path", path)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/files/delete failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "delete_failed").toString())
        }
    }

    private fun handleAdbFilesRename(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val from = body.optString("from").ifBlank { body.optString("path") }.trim()
            val to = body.optString("to").ifBlank { body.optString("newPath") }.trim()
            if (from.isBlank() || to.isBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_from_or_to"}""")
            }
            val serial = body.optString("serial").trim().ifBlank { null }
            val res = FleetAdbHost.renamePath(context, from, to, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("from", from)
                    .put("to", to)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/files/rename failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "rename_failed").toString())
        }
    }

    private fun handleAdbFilesUpload(request: HttpRequest, query: Map<String, String>): HttpResponse {
        return try {
            val bytes = request.body
            if (bytes == null || bytes.isEmpty()) {
                return jsonResponse(400, """{"ok":false,"error":"empty_body"}""")
            }
            val serial = query["serial"]?.trim()?.ifBlank { null }
            val path = query["path"]?.trim().orEmpty()
            if (path.isBlank()) return jsonResponse(400, """{"ok":false,"error":"missing_path"}""")
            val name = query["name"]?.trim()?.ifBlank { path.substringAfterLast('/') } ?: path.substringAfterLast('/')
            val res = FleetAdbHost.pushBytes(context, bytes, path, serial, name)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("path", path)
                    .put("name", name)
                    .put("bytes", bytes.size)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/files/upload failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "upload_failed").toString())
        }
    }

    private fun handleAdbDownload(): HttpResponse {
        return try {
            jsonResponse(200, FleetAdbHost.requestDownload(context).toString())
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/download failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "download_failed").toString())
        }
    }

    private fun handleAdbStart(): HttpResponse {
        return try {
            val res = FleetAdbHost.ensureReady(context)
            jsonResponse(
                if (res.ok) 200 else 500,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .put("adb", FleetAdbHost.statusJson(context))
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/start failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "start_failed").toString())
        }
    }

    private fun handleAdbPair(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val host = body.optString("host").trim().replace('：', ':')
            val port = body.optInt("port", 0)
            val hostPort = body.optString("hostPort").trim().ifBlank {
                if (host.isNotBlank() && port in 1..65535) "$host:$port" else ""
            }
            val code = body.optString("code").ifBlank { body.optString("pairingCode") }.trim()
            val res = FleetAdbHost.pair(context, hostPort, code)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/pair failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "pair_failed").toString())
        }
    }

    private fun handleAdbConnect(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val host = body.optString("host").trim().replace('：', ':')
            val port = body.optInt("port", 5555)
            val hostPort = body.optString("hostPort").trim().ifBlank {
                if (host.isNotBlank()) "$host:${port.coerceIn(1, 65535)}" else ""
            }
            val res = FleetAdbHost.connect(context, hostPort)
            val snap = FleetAdbHost.devicesJson(context)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .put("hostPort", hostPort)
                    .put("devices", snap.optJSONArray("devices"))
                    .put("selfDevices", snap.optJSONArray("selfDevices"))
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/connect failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "connect_failed").toString())
        }
    }

    private fun handleAdbDisconnect(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val serial = body.optString("serial").ifBlank { body.optString("hostPort") }.trim()
                .ifBlank { null }
            val res = FleetAdbHost.disconnect(context, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/disconnect failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "disconnect_failed").toString())
        }
    }

    private fun handleAdbForget(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val serial = body.optString("serial").ifBlank { body.optString("hostPort") }.trim()
            if (serial.isBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_serial"}""")
            }
            val res = FleetAdbHost.forget(context, serial)
            jsonResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("devices", FleetAdbHost.devicesJson(context).optJSONArray("devices"))
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/forget failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "forget_failed").toString())
        }
    }

    private fun handleAdbInstall(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val path = body.optString("path").trim()
            val serial = body.optString("serial").trim().ifBlank { null }
            if (path.isBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_path"}""")
            }
            val res = FleetAdbHost.install(context, path, serial)
            jsonResponse(
                if (res.ok) 200 else 400,
                JSONObject()
                    .put("ok", res.ok)
                    .put("code", res.code)
                    .put("stdout", res.stdout)
                    .put("stderr", res.stderr)
                    .put("error", res.error ?: JSONObject.NULL)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/adb/install failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "install_failed").toString())
        }
    }

    private fun handleScreenCapture(query: Map<String, String>): HttpResponse {
        val maxWidth = query["maxWidth"]?.toIntOrNull() ?: 720
        val quality = query["quality"]?.toIntOrNull() ?: 40
        val shot = FleetScreenShot.captureOnce(
            context = context,
            force = true,
            maxWidth = maxWidth,
            quality = quality,
        )
        return if (shot != null) {
            jsonResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("mode", "screencap-oneshot")
                    .put("width", shot.width)
                    .put("height", shot.height)
                    .put("bytes", shot.jpeg.size)
                    .put("backend", shot.backend)
                    .put("shot", FleetScreenShot.statusJson())
                    .toString(),
            )
        } else {
            jsonResponse(
                503,
                JSONObject()
                    .put("ok", false)
                    .put("error", FleetScreenShot.statusJson().opt("lastError") ?: "capture_failed")
                    .put("shot", FleetScreenShot.statusJson())
                    .toString(),
            )
        }
    }

    private fun handleScrcpyStart(): HttpResponse {
        val ok = FleetScrcpyServer.start(context)
        return jsonResponse(
            if (ok) 200 else 503,
            FleetScrcpyServer.statusJson(context).put("ok", ok).toString(),
        )
    }

    private fun handleScrcpyStop(): HttpResponse {
        val ok = FleetScrcpyServer.stop()
        return jsonResponse(200, FleetScrcpyServer.statusJson(context).put("ok", ok).toString())
    }

    // ------------------------------------------------------------------
    // Logs (Ava process logcat)
    // ------------------------------------------------------------------

    private fun handleGetLogs(query: Map<String, String>): HttpResponse {
        return try {
            val limit = query["limit"]?.toIntOrNull() ?: FleetLogService.MAX_LINES
            val force = query["force"] == "1" || query["force"] == "true"
            jsonResponse(200, FleetLogService.snapshot(context, limit, force).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/logs failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    private fun handleClusterPullLogs(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val host = body.optString("host").trim()
            if (host.isBlank()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_host"}""")
            }
            rejectBadPeerHost(host)?.let { return it }
            val peerPort = requirePeerPort(body)
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_or_invalid_port"}""")
            val limit = body.optInt("limit", FleetLogService.MAX_LINES).coerceIn(1, FleetLogService.MAX_LINES)
            val raw = fetchPeerJson("http://$host:$peerPort/v1/logs?limit=$limit")
                ?: return jsonResponse(502, """{"ok":false,"error":"peer_unreachable"}""")
            val peer = JSONObject(raw)
            peer.put("peerHost", host)
            peer.put("peerPort", peerPort)
            jsonResponse(200, peer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/cluster/pull-logs failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    // ------------------------------------------------------------------
    // Settings GET endpoints
    // ------------------------------------------------------------------

    private fun handleGetSettings(): HttpResponse {
        return try {
            jsonResponse(200, FleetSettingsService.getSettingsSnapshot(context).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/settings failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    private fun handleGetSettingsSchema(): HttpResponse {
        return jsonResponse(200, FleetSettingsCatalog.schemaJson(context).toString())
    }

    private fun handleGetSettingsExport(): HttpResponse {
        return try {
            val json = FleetSettingsService.exportBackupJson(context)
            HttpResponse(
                status = 200,
                reason = "OK",
                contentType = "application/json; charset=utf-8",
                body = json.toByteArray(StandardCharsets.UTF_8),
                cacheControl = "no-store",
                extraHeaders = mapOf(
                    "Content-Disposition" to
                        "attachment; filename=\"${com.example.ava.backup.AvaBackupManager.formatBackupFileName()}\""
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/settings/export failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    private fun handleGetSettingsHa(): HttpResponse {
        return try {
            jsonResponse(200, FleetSettingsService.listHaPublishedSwitches(context).toString())
        } catch (e: Exception) {
            Log.e(TAG, "GET /v1/settings/ha failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    // ------------------------------------------------------------------
    // Settings POST endpoints
    // ------------------------------------------------------------------

    private fun handlePostSettingsImport(request: HttpRequest, query: Map<String, String>): HttpResponse {
        val body = request.bodyText()
        if (body.isNullOrBlank()) {
            return jsonResponse(400, """{"ok":false,"error":"empty_body"}""")
        }
        val dryRun = query["dryRun"] == "1" || query["dry_run"] == "1"
        return try {
            val result = runBlocking { FleetSettingsService.importBackupJson(context, body, dryRun) }
            if (result.success && !dryRun) {
                FleetAuth.invalidateCache()
                FleetSettingsRevision.bump()
            }
            jsonResponse(
                if (result.success) 200 else 400,
                JSONObject()
                    .put("ok", result.success)
                    .put("applied", result.success && !dryRun)
                    .put("message", result.message)
                    .put("errors", org.json.JSONArray(if (result.success) emptyList<String>() else listOf(result.message)))
                    .put("revision", FleetSettingsRevision.current())
                    .put("restartRequired", result.needsSatelliteRestart)
                    .put("needsSatelliteRestart", result.needsSatelliteRestart)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/settings/import failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    private fun handlePostSettingsApply(request: HttpRequest): HttpResponse {
        val body = request.bodyText()
        if (body.isNullOrBlank()) {
            return jsonResponse(400, """{"ok":false,"error":"empty_body"}""")
        }
        return try {
            val root = JSONObject(body)
            val settingsObj = root.optJSONObject("settings")
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_settings_key"}""")
            val result = runBlocking { FleetSettingsService.applyPatch(context, settingsObj.toString()) }
            if (result.success) {
                FleetAuth.invalidateCache()
                FleetSettingsRevision.bump()
            }
            jsonResponse(
                if (result.success) 200 else 400,
                JSONObject()
                    .put("ok", result.success)
                    .put("applied", result.success)
                    .put("changedStores", org.json.JSONArray(result.changedStores))
                    .put("errors", org.json.JSONArray(result.errors))
                    .put("revision", FleetSettingsRevision.current())
                    .put("restartRequired", result.needsSatelliteRestart)
                    .put("needsSatelliteRestart", result.needsSatelliteRestart)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/settings/apply failed", e)
            jsonResponse(500, """{"ok":false,"error":"${e.message}"}""")
        }
    }

    // ------------------------------------------------------------------
    // Cluster endpoints
    // ------------------------------------------------------------------

    private fun handleClusterProbe(request: HttpRequest): HttpResponse {
        val body = request.bodyText()
        if (body.isNullOrBlank()) {
            return jsonResponse(400, """{"ok":false,"error":"empty_body"}""")
        }
        return try {
            val root = JSONObject(body)
            val host = root.optString("host", "").ifBlank {
                return jsonResponse(400, """{"ok":false,"error":"missing_host"}""")
            }
            rejectBadPeerHost(host)?.let { return it }
            val peerPort = requirePeerPort(root)
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_or_invalid_port"}""")

            val hello = fetchPeerJson("http://$host:$peerPort/v1/hello")
            val status = fetchPeerJson("http://$host:$peerPort/v1/status")
            val helloObj = hello?.let { runCatching { JSONObject(it) }.getOrNull() }
            val statusObj = status?.let { runCatching { JSONObject(it) }.getOrNull() }
            val ok = helloObj != null

            jsonResponse(
                if (ok) 200 else 502,
                JSONObject()
                    .put("ok", ok)
                    .put("hello", helloObj ?: JSONObject.NULL)
                    .put("status", statusObj ?: JSONObject.NULL)
                    .put("deviceName", helloObj?.optString("deviceName").orEmpty())
                    .put("deviceId", helloObj?.optString("deviceId").orEmpty())
                    .put("error", if (ok) JSONObject.NULL else "peer_unreachable")
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/cluster/probe failed", e)
            jsonResponse(502, JSONObject().put("ok", false).put("error", e.message ?: "probe_failed").toString())
        }
    }

    /**
     * Hub-side JPEG proxy of a peer Ava agent `GET /v1/screen/frame?oneshot=1`.
     * Always oneshot so the wall never starts peer scrcpy. Works when the peer
     * website console is off (agent-only) and when the peer has no ADB.
     */
    private fun handleClusterScreen(query: Map<String, String>): HttpResponse {
        val host = query["host"]?.trim().orEmpty()
        if (host.isBlank()) {
            return jsonResponse(400, """{"ok":false,"error":"missing_host"}""")
        }
        rejectBadPeerHost(host)?.let { return it }
        val peerPort = query["port"]?.toIntOrNull() ?: 0
        if (peerPort !in 1..65535) {
            return jsonResponse(400, """{"ok":false,"error":"missing_or_invalid_port"}""")
        }
        val maxWidth = query["maxWidth"]?.toIntOrNull()?.coerceIn(160, 960) ?: 560
        val quality = query["quality"]?.toIntOrNull()?.coerceIn(20, 80) ?: 48
        val force = query["force"] == "1" || query["refresh"] == "1"
        val qs = buildString {
            append("oneshot=1")
            append("&maxWidth=").append(maxWidth)
            append("&quality=").append(quality)
            if (force) append("&force=1")
        }
        val fetched = fetchPeerBytes("http://$host:$peerPort/v1/screen/frame?$qs", timeoutMs = 12_000)
            ?: return jsonResponse(502, """{"ok":false,"error":"peer_screen_unavailable"}""")
        val jpeg = fetched.contentType.contains("jpeg", ignoreCase = true) || looksLikeJpeg(fetched.body)
        if (!jpeg || fetched.body.size < 64) {
            return jsonResponse(502, """{"ok":false,"error":"peer_screen_not_jpeg"}""")
        }
        return HttpResponse(
            status = 200,
            reason = "OK",
            contentType = "image/jpeg",
            body = fetched.body,
            cacheControl = "no-store",
            extraHeaders = fetched.extraHeaders + mapOf(
                "X-Ava-Cluster-Host" to host,
                "X-Ava-Cluster-Port" to peerPort.toString(),
            ),
        )
    }

    private fun handleClusterPullSettings(request: HttpRequest): HttpResponse {
        val body = request.bodyText()
        if (body.isNullOrBlank()) {
            return jsonResponse(400, """{"ok":false,"error":"empty_body"}""")
        }
        return try {
            val root = JSONObject(body)
            val host = root.optString("host", "").ifBlank {
                return jsonResponse(400, """{"ok":false,"error":"missing_host"}""")
            }
            rejectBadPeerHost(host)?.let { return it }
            val peerPort = requirePeerPort(root)
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_or_invalid_port"}""")

            val exportJson = fetchPeerJson("http://$host:$peerPort/v1/settings/export")
                ?: return jsonResponse(502, """{"ok":false,"error":"peer_export_unavailable"}""")

            jsonResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("backup", JSONObject(exportJson))
                    .toString(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/cluster/pull-settings failed", e)
            jsonResponse(502, JSONObject().put("ok", false).put("error", e.message ?: "pull_failed").toString())
        }
    }

    private fun handleClusterPushSettings(request: HttpRequest): HttpResponse {
        val body = request.bodyText()
        if (body.isNullOrBlank()) {
            return jsonResponse(400, """{"ok":false,"error":"empty_body"}""")
        }
        return try {
            val root = JSONObject(body)
            val host = root.optString("host", "").ifBlank {
                return jsonResponse(400, """{"ok":false,"error":"missing_host"}""")
            }
            rejectBadPeerHost(host)?.let { return it }
            val peerPort = requirePeerPort(root)
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_or_invalid_port"}""")
            val payload = root.optJSONObject("backup")
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_backup_key"}""")

            val responseText = postJsonToPeer(
                "http://$host:$peerPort/v1/settings/import",
                payload.toString(),
            ) ?: return jsonResponse(502, """{"ok":false,"error":"peer_import_endpoint_unreachable"}""")

            jsonResponse(200, JSONObject().put("ok", true).put("peerResponse", JSONObject(responseText)).toString())
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/cluster/push-settings failed", e)
            jsonResponse(502, JSONObject().put("ok", false).put("error", e.message ?: "push_failed").toString())
        }
    }

    // ------------------------------------------------------------------
    // Peer HTTP helpers (real HTTP calls to other Ava devices on LAN)
    // ------------------------------------------------------------------

    /** Block SSRF to non-LAN / metadata endpoints from unauthenticated cluster proxies. */
    private fun peerHostAllowed(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.contains('/') || h.contains('@') || h.contains('\\')) return false
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h.endsWith(".local")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return when {
                a == 10 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                a == 127 -> true
                else -> false
            }
        }
        // Hostnames: allow only simple LAN-ish names (no IP literal public ranges).
        return h.matches(Regex("^[a-z0-9][a-z0-9._-]{0,63}$")) && !h.contains("metadata")
    }

    private fun rejectBadPeerHost(host: String): HttpResponse? {
        if (peerHostAllowed(host)) return null
        return jsonResponse(400, """{"ok":false,"error":"peer_host_not_allowed"}""")
    }

    /** Explicit peer port only — never invent 8888. */
    private fun requirePeerPort(body: JSONObject): Int? {
        val p = body.optInt("port", 0)
        return if (p in 1..65535) p else null
    }

    private fun fetchPeerJson(url: String, timeoutMs: Int = 5_000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val token = FleetAuth.configuredToken(context)
            if (token.isNotEmpty()) {
                conn.setRequestProperty(FleetAuth.headerName(), token)
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPeerJson($url) failed: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private data class PeerBytes(
        val body: ByteArray,
        val contentType: String,
        val extraHeaders: Map<String, String>,
    )

    private fun looksLikeJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    private fun fetchPeerBytes(url: String, timeoutMs: Int = 10_000): PeerBytes? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "image/jpeg, */*")
            val token = FleetAuth.configuredToken(context)
            if (token.isNotEmpty()) {
                conn.setRequestProperty(FleetAuth.headerName(), token)
            }
            if (conn.responseCode != 200) return null
            val type = conn.contentType.orEmpty()
            val body = conn.inputStream.use { it.readBytes() }
            val headers = linkedMapOf<String, String>()
            for (name in listOf(
                "X-Ava-Screen-Width",
                "X-Ava-Screen-Height",
                "X-Ava-Screen-Mode",
                "X-Ava-Screen-Backend",
            )) {
                val v = conn.getHeaderField(name)
                if (!v.isNullOrBlank()) headers[name] = v
            }
            PeerBytes(body = body, contentType = type, extraHeaders = headers)
        } catch (e: Exception) {
            Log.w(TAG, "fetchPeerBytes($url) failed: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun postJsonToPeer(url: String, json: String, timeoutMs: Int = 8_000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val token = FleetAuth.configuredToken(context)
            if (token.isNotEmpty()) {
                conn.setRequestProperty(FleetAuth.headerName(), token)
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "postJsonToPeer($url) failed: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun corsPreflightResponse(): HttpResponse {
        return HttpResponse(
            status = 204,
            reason = "No Content",
            contentType = "text/plain",
            body = ByteArray(0),
            extraHeaders = mapOf(
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "Content-Type, Accept, X-Ava-Fleet-Token, Authorization",
                "Access-Control-Max-Age" to "86400",
            ),
        )
    }

    private fun handleFrame(query: Map<String, String>): HttpResponse {
        val force = query["force"] == "1" || query["refresh"] == "1"
        val oneshotOnly = query["oneshot"] == "1" || query["mode"] == "oneshot"
        val maxWidth = query["maxWidth"]?.toIntOrNull() ?: 720
        val quality = query["quality"]?.toIntOrNull() ?: 40

        // Live scrcpy is the primary ongoing path. force=1 + oneshot=1 = first-frame only.
        if (!oneshotOnly) {
            val live = FleetScrcpyBridge.latestJpeg()
            if (live != null && FleetScrcpyBridge.isActive()) {
                return HttpResponse(
                    status = 200,
                    reason = "OK",
                    contentType = "image/jpeg",
                    body = live.jpeg,
                    cacheControl = "no-store",
                    extraHeaders = mapOf(
                        "X-Ava-Screen-Width" to live.width.toString(),
                        "X-Ava-Screen-Height" to live.height.toString(),
                        "X-Ava-Screen-Mode" to "scrcpy-mediacodec-jpeg",
                        "X-Ava-Screen-Pts" to live.ptsUs.toString(),
                    ),
                )
            }
            if (FleetScrcpyBridge.isActive() && !force) {
                return jsonResponse(
                    503,
                    JSONObject()
                        .put("ok", false)
                        .put("error", "waiting_first_frame")
                        .put("scrcpy", FleetScrcpyServer.statusJson(context))
                        .put("bridge", FleetScrcpyBridge.statusJson())
                        .toString(),
                )
            }
        }

        // One-shot screencap: wall thumbs + explicit first frame before scrcpy attaches.
        // Wall sends oneshot=1 without force: first miss captures, then reuse for ~40s.
        if (oneshotOnly || force) {
            if (oneshotOnly && !force) {
                val cached = FleetScreenShot.latest()
                if (cached != null && cached.jpeg.size >= 64 &&
                    System.currentTimeMillis() - cached.atMs < 40_000L
                ) {
                    return oneshotJpegResponse(cached)
                }
            }
            val shot = FleetScreenShot.captureOnce(
                context = context,
                force = true,
                maxWidth = maxWidth,
                quality = quality,
            )
            if (shot != null) {
                return oneshotJpegResponse(shot)
            }
        }

        val shotStatus = FleetScreenShot.statusJson()
        val err = when {
            FleetScrcpyBridge.isActive() || FleetScrcpyServer.statusJson(context).optBoolean("running", false) ->
                "waiting_first_frame"
            shotStatus.optBoolean("canCapture", false) ->
                shotStatus.optString("lastError", "capture_failed")
            else ->
                "need_shizuku_root_or_accessibility"
        }
        return jsonResponse(
            503,
            JSONObject()
                .put("ok", false)
                .put("error", err)
                .put("shot", shotStatus)
                .put("scrcpy", FleetScrcpyServer.statusJson(context))
                .put(
                    "hint",
                    "oneshot=1 for first frame (shell or accessibility); or POST /v1/screen/scrcpy/start when Shizuku/root available",
                )
                .toString(),
        )
    }

    private fun oneshotJpegResponse(shot: FleetScreenShot.Shot): HttpResponse {
        val mode = if (shot.backend == "accessibility") {
            "accessibility-oneshot"
        } else {
            "screencap-oneshot"
        }
        return HttpResponse(
            status = 200,
            reason = "OK",
            contentType = "image/jpeg",
            body = shot.jpeg,
            cacheControl = "no-store",
            extraHeaders = mapOf(
                "X-Ava-Screen-Width" to shot.width.toString(),
                "X-Ava-Screen-Height" to shot.height.toString(),
                "X-Ava-Screen-Mode" to mode,
                "X-Ava-Screen-Backend" to shot.backend,
                "X-Ava-Screen-Age-Ms" to (System.currentTimeMillis() - shot.atMs).toString(),
            ),
        )
    }

    /** Serve the most recent one-shot JPEG without capturing again (screen-capture mod URL). */
    private fun handleLastScreenShot(): HttpResponse {
        val shot = FleetScreenShot.latest()
        if (shot == null || shot.jpeg.size < 64) {
            return jsonResponse(
                404,
                JSONObject()
                    .put("ok", false)
                    .put("error", "no_screenshot")
                    .put("hint", "Press Take Screenshot on the device first")
                    .toString(),
            )
        }
        return HttpResponse(
            status = 200,
            reason = "OK",
            contentType = "image/jpeg",
            body = shot.jpeg,
            cacheControl = "no-store",
            extraHeaders = mapOf(
                "X-Ava-Screen-Width" to shot.width.toString(),
                "X-Ava-Screen-Height" to shot.height.toString(),
                "X-Ava-Screen-Mode" to "last-shot",
                "X-Ava-Screen-Backend" to shot.backend,
                "X-Ava-Screen-Age-Ms" to (System.currentTimeMillis() - shot.atMs).toString(),
            ),
        )
    }

    /** Opens system Accessibility settings — only from an explicit console button. */
    private fun handleAccessibilityOpenSettings(): HttpResponse {
        val opened = AccessibilityBridge.openSettings(context)
        return jsonResponse(
            if (opened) 200 else 500,
            JSONObject()
                .put("ok", opened)
                .put("opened", opened)
                .put(
                    "accessibility",
                    runCatching { JSONObject(AccessibilityBridge.getStatus(context)) }
                        .getOrElse { JSONObject() },
                )
                .toString(),
        )
    }

    private fun handleShellExec(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val command = body.optString("command", body.optString("cmd", "")).trim()
            val timeoutSec = body.optInt("timeoutSec", 15)
            val result = FleetShell.exec(context, command, timeoutSec)
            val status = when {
                result.error == "need_shizuku_or_root" -> 503
                result.error == "blocked_command" || result.error == "empty_command" ||
                    result.error == "command_too_long" -> 400
                else -> 200
            }
            jsonResponse(status, FleetShell.toJson(result).toString())
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/shell/exec failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "shell_failed").toString())
        }
    }

    private fun handleClusterShell(request: HttpRequest): HttpResponse {
        return try {
            val body = JSONObject(request.bodyText() ?: "{}")
            val host = body.optString("host", "").trim()
            val peerPort = requirePeerPort(body)
                ?: return jsonResponse(400, """{"ok":false,"error":"missing_or_invalid_port"}""")
            val command = body.optString("command", body.optString("cmd", "")).trim()
            val timeoutSec = body.optInt("timeoutSec", 15)
            if (host.isEmpty() || command.isEmpty()) {
                return jsonResponse(400, """{"ok":false,"error":"missing_host_or_command"}""")
            }
            rejectBadPeerHost(host)?.let { return it }
            val payload = JSONObject()
                .put("command", command)
                .put("timeoutSec", timeoutSec)
                .toString()
            val raw = postJsonToPeer("http://$host:$peerPort/v1/shell/exec", payload, timeoutMs = 20_000)
                ?: return jsonResponse(
                    502,
                    JSONObject()
                        .put("ok", false)
                        .put("error", "peer_unreachable")
                        .put("peerHost", host)
                        .put("peerPort", peerPort)
                        .toString(),
                )
            val peer = runCatching { JSONObject(raw) }.getOrElse {
                return jsonResponse(502, """{"ok":false,"error":"peer_bad_json"}""")
            }
            peer.put("peerHost", host).put("peerPort", peerPort).put("proxied", true)
            jsonResponse(200, peer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "POST /v1/cluster/shell failed", e)
            jsonResponse(500, JSONObject().put("ok", false).put("error", e.message ?: "cluster_shell_failed").toString())
        }
    }

    private data class DisplayPoint(val x: Int, val y: Int, val displayWidth: Int, val displayHeight: Int)

    /** Resolve absolute display coords from normalized and/or raw query params. */
    private fun resolveDisplayPoint(
        query: Map<String, String>,
        nxKey: String,
        nyKey: String,
        xKey: String,
        yKey: String,
    ): DisplayPoint? {
        val nx = query[nxKey]?.toDoubleOrNull()
        val ny = query[nyKey]?.toDoubleOrNull()
        val rawX = query[xKey]?.toIntOrNull()
        val rawY = query[yKey]?.toIntOrNull()
        if (rawX == null && nx == null) return null
        if (rawY == null && ny == null) return null

        val size = FleetScreenCoords.realDisplaySize(context)
        val frame = latestInputFrameSize()
        val point = FleetScreenCoords.resolve(
            nx, ny, rawX, rawY,
            size.width, size.height,
            frame.first, frame.second,
        ) ?: return null
        return DisplayPoint(point.x, point.y, point.displayWidth, point.displayHeight)
    }

    private fun latestInputFrameSize(): Pair<Int, Int> {
        FleetScrcpyBridge.latestJpeg()?.let { return it.width to it.height }
        FleetScreenShot.latest()?.let { return it.width to it.height }
        return 0 to 0
    }

    private fun handleTap(query: Map<String, String>): HttpResponse {
        val point = resolveDisplayPoint(query, "nx", "ny", "x", "y")
            ?: return jsonResponse(400, """{"ok":false,"error":"missing_xy"}""")
        val cx = point.x
        val cy = point.y
        val dw = point.displayWidth
        val dh = point.displayHeight

        // 1. In-process AccessibilityBridge — no fork, no permission dialog.
        if (AccessibilityBridge.isServiceConnected()) {
            if (AccessibilityBridge.tap(cx, cy)) {
                return jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true).put("x", cx).put("y", cy)
                        .put("displayWidth", dw).put("displayHeight", dh)
                        .put("via", "accessibility")
                        .toString(),
                )
            }
        }

        // 2. `input tap` via Shizuku/root
        val backend = FleetShell.backend()
        if (backend != null) {
            val res = FleetShell.exec(context, "input tap $cx $cy", timeoutSec = 4)
            if (res.ok) {
                return jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true).put("x", cx).put("y", cy)
                        .put("displayWidth", dw).put("displayHeight", dh)
                        .put("via", "shell-$backend")
                        .toString(),
                )
            }
            return jsonResponse(
                503,
                JSONObject()
                    .put("ok", false)
                    .put("error", res.error ?: "tap_failed")
                    .put("code", res.code)
                    .put("via", "shell-$backend")
                    .put("x", cx).put("y", cy)
                    .toString(),
            )
        }
        return jsonResponse(
            503,
            JSONObject()
                .put("ok", false)
                .put("error", "no_input_backend")
                .put("hint", "enable Accessibility service or grant Shizuku/root")
                .toString(),
        )
    }

    private fun handleSwipe(query: Map<String, String>): HttpResponse {
        val start = resolveDisplayPoint(query, "nx1", "ny1", "x1", "y1")
            ?: return jsonResponse(400, """{"ok":false,"error":"missing_start"}""")
        val end = resolveDisplayPoint(query, "nx2", "ny2", "x2", "y2")
            ?: return jsonResponse(400, """{"ok":false,"error":"missing_end"}""")
        val durationMs = (query["durationMs"]?.toIntOrNull() ?: 280).coerceIn(50, 2500)
        val x1 = start.x
        val y1 = start.y
        val x2 = end.x
        val y2 = end.y
        val dw = start.displayWidth.takeIf { it > 0 } ?: end.displayWidth
        val dh = start.displayHeight.takeIf { it > 0 } ?: end.displayHeight

        if (AccessibilityBridge.isServiceConnected()) {
            if (AccessibilityBridge.swipe(x1, y1, x2, y2, durationMs)) {
                return jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
                        .put("durationMs", durationMs)
                        .put("displayWidth", dw).put("displayHeight", dh)
                        .put("via", "accessibility")
                        .toString(),
                )
            }
        }

        val backend = FleetShell.backend()
        if (backend != null) {
            val res = FleetShell.exec(
                context,
                "input swipe $x1 $y1 $x2 $y2 $durationMs",
                timeoutSec = 6,
            )
            if (res.ok) {
                return jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
                        .put("durationMs", durationMs)
                        .put("displayWidth", dw).put("displayHeight", dh)
                        .put("via", "shell-$backend")
                        .toString(),
                )
            }
            return jsonResponse(
                503,
                JSONObject()
                    .put("ok", false)
                    .put("error", res.error ?: "swipe_failed")
                    .put("code", res.code)
                    .put("via", "shell-$backend")
                    .toString(),
            )
        }
        return jsonResponse(
            503,
            JSONObject()
                .put("ok", false)
                .put("error", "no_input_backend")
                .put("hint", "enable Accessibility service or grant Shizuku/root")
                .toString(),
        )
    }

    private fun handleKey(query: Map<String, String>): HttpResponse {
        val raw = (query["key"] ?: query["code"] ?: "").trim()
        if (raw.isEmpty()) {
            return jsonResponse(400, """{"ok":false,"error":"missing_key"}""")
        }
        val keyCode = when (raw.uppercase()) {
            "BACK", "4" -> 4
            "HOME", "3" -> 3
            "RECENTS", "APP_SWITCH", "187" -> 187
            else -> raw.toIntOrNull()
        } ?: return jsonResponse(400, """{"ok":false,"error":"bad_key"}""")

        // Prefer a11y global Back when asked for BACK — matches nav bar semantics.
        if (keyCode == 4 && AccessibilityBridge.isServiceConnected()) {
            if (AccessibilityBridge.back()) {
                return jsonResponse(
                    200,
                    JSONObject().put("ok", true).put("key", "BACK").put("keyCode", 4)
                        .put("via", "accessibility").toString(),
                )
            }
        }

        val backend = FleetShell.backend()
        if (backend != null) {
            val res = FleetShell.exec(context, "input keyevent $keyCode", timeoutSec = 4)
            if (res.ok) {
                return jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("key", raw)
                        .put("keyCode", keyCode)
                        .put("via", "shell-$backend")
                        .toString(),
                )
            }
            return jsonResponse(
                503,
                JSONObject()
                    .put("ok", false)
                    .put("error", res.error ?: "key_failed")
                    .put("code", res.code)
                    .put("via", "shell-$backend")
                    .put("keyCode", keyCode)
                    .toString(),
            )
        }
        return jsonResponse(
            503,
            JSONObject()
                .put("ok", false)
                .put("error", "no_input_backend")
                .put("hint", "enable Accessibility service or grant Shizuku/root")
                .toString(),
        )
    }

    private fun writeSse(socket: Socket, request: HttpRequest) {
        if (request.method != "GET") {
            writeResponse(socket, textResponse(405, "Method Not Allowed", "Method Not Allowed"))
            return
        }
        val out = socket.getOutputStream()
        writeHeaders(
            out,
            status = 200,
            reason = "OK",
            contentType = "text/event-stream; charset=utf-8",
            contentLength = null,
            extra = mapOf(
                "Cache-Control" to "no-store",
                "Connection" to "keep-alive",
                "Access-Control-Allow-Origin" to "*",
            ),
        )
        var seq = 0L
        while (running.get() && !socket.isClosed) {
            seq++
            try {
                val payload = FleetStatusBuilder.status(context, port)
                val event = buildString {
                    append("id: ").append(seq).append("\n")
                    append("event: status\n")
                    append("data: ").append(payload).append("\n\n")
                }
                out.write(event.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
                break // client gone — free worker thread
            }
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun writeMjpeg(socket: Socket, request: HttpRequest) {
        if (request.method != "GET") {
            writeResponse(socket, textResponse(405, "Method Not Allowed", "Method Not Allowed"))
            return
        }
        val query = parseQuery(request.path.substringAfter('?', ""))
        // Accessibility takeScreenshot is rate-limited (~1.5s); allow slower polls.
        val intervalMs = (query["intervalMs"]?.toLongOrNull() ?: 250L).coerceIn(80L, 2_500L)
        val allowOneshot = query["oneshot"] == "1"
        val maxWidth = query["maxWidth"]?.toIntOrNull() ?: 720
        val quality = query["quality"]?.toIntOrNull() ?: 40

        val out = socket.getOutputStream()
        writeHeaders(
            out,
            status = 200,
            reason = "OK",
            contentType = "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY",
            contentLength = null,
            extra = mapOf(
                "Cache-Control" to "no-store",
                "Connection" to "close",
                "Access-Control-Allow-Origin" to "*",
                "X-Ava-Screen-Mode" to if (allowOneshot) "oneshot-or-scrcpy" else "scrcpy-mediacodec-jpeg",
            ),
        )

        while (running.get() && !socket.isClosed) {
            try {
                val live = FleetScrcpyBridge.latestJpeg()
                val jpeg: ByteArray?
                val w: Int
                val h: Int
                when {
                    live != null && FleetScrcpyBridge.isActive() -> {
                        jpeg = live.jpeg; w = live.width; h = live.height
                    }
                    allowOneshot -> {
                        // Capture each tick (shell or a11y); interval-short returns cache.
                        val shot = FleetScreenShot.captureOnce(
                            context = context,
                            force = true,
                            maxWidth = maxWidth,
                            quality = quality,
                        ) ?: FleetScreenShot.latest()
                        if (shot != null) {
                            jpeg = shot.jpeg; w = shot.width; h = shot.height
                        } else {
                            jpeg = null; w = 0; h = 0
                        }
                    }
                    else -> {
                        jpeg = null; w = 0; h = 0
                    }
                }
                if (jpeg != null) {
                    val header = buildString {
                        append("--").append(MJPEG_BOUNDARY).append("\r\n")
                        append("Content-Type: image/jpeg\r\n")
                        append("Content-Length: ").append(jpeg.size).append("\r\n")
                        append("X-Ava-Screen-Width: ").append(w).append("\r\n")
                        append("X-Ava-Screen-Height: ").append(h).append("\r\n\r\n")
                    }.toByteArray(StandardCharsets.UTF_8)
                    out.write(header)
                    out.write(jpeg)
                    out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
                break // client gone
            }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        try {
            out.write("--$MJPEG_BOUNDARY--\r\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun writeHeaders(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Int?,
        extra: Map<String, String> = emptyMap(),
    ) {
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            if (contentLength != null) {
                append("Content-Length: $contentLength\r\n")
            }
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun assetResponse(name: String): HttpResponse {
        if (name !in ALLOWED_STATIC) {
            return textResponse(404, "Not Found", "Not Found")
        }
        val bytes = loadHotFile(name) ?: loadBundledAsset(name)
            ?: return textResponse(404, "Not Found", "Not Found")
        return HttpResponse(
            status = 200,
            reason = "OK",
            contentType = contentTypeFor(name),
            body = bytes,
            cacheControl = "no-store",
        )
    }

    private fun hotConsoleDir(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, HOT_DIR_NAME)
    }

    private fun loadHotFile(name: String): ByteArray? {
        val file = File(hotConsoleDir() ?: return null, name)
        if (!file.isFile) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read hot console file $name", e)
            null
        }
    }

    private fun loadBundledAsset(name: String): ByteArray? = try {
        context.assets.open("$ASSET_PREFIX/$name").use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun contentTypeFor(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun jsonResponse(status: Int, body: String): HttpResponse =
        HttpResponse(
            status = status,
            reason = reasonFor(status),
            contentType = "application/json; charset=utf-8",
            body = body.toByteArray(StandardCharsets.UTF_8),
            cacheControl = "no-store",
        )

    private fun textResponse(status: Int, reason: String, body: String): HttpResponse =
        HttpResponse(
            status = status,
            reason = reason,
            contentType = "text/plain; charset=utf-8",
            body = body.toByteArray(StandardCharsets.UTF_8),
        )

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val extra = LinkedHashMap<String, String>()
        response.cacheControl?.let { extra["Cache-Control"] = it }
        extra["Connection"] = "close"
        extra["Access-Control-Allow-Origin"] = "*"
        response.extraHeaders.forEach { (k, v) -> extra[k] = v }
        val out = socket.getOutputStream()
        writeHeaders(
            out,
            status = response.status,
            reason = response.reason,
            contentType = response.contentType,
            contentLength = response.body.size,
            extra = extra,
        )
        if (response.body.isNotEmpty()) {
            out.write(response.body)
        }
        out.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            try {
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                key to value
            }
        }.toMap()
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) break
            headerBytes.write(b)
            when (state) {
                0 -> state = if (b == '\r'.code) 1 else 0
                1 -> state = if (b == '\n'.code) 2 else 0
                2 -> state = if (b == '\r'.code) 3 else 0
                3 -> {
                    if (b == '\n'.code) break
                    state = 0
                }
            }
        }
        if (headerBytes.size() == 0) return null
        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0 && contentLength <= MAX_BODY_BYTES) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        } else {
            null
        }

        return HttpRequest(method = method, path = rawTarget, headers = headers, body = body)
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
    ) {
        fun bodyText(): String? = body?.toString(StandardCharsets.UTF_8)
    }

    private data class HttpResponse(
        val status: Int,
        val reason: String,
        val contentType: String,
        val body: ByteArray,
        val cacheControl: String? = null,
        val extraHeaders: Map<String, String> = emptyMap(),
    )
}
