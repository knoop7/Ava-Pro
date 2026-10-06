package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.appwindow.AppWindowSocketRelay
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bundled Genymobile [scrcpy-server] (v3.3.4, ~89 KiB) launcher.
 *
 * Start server as **shell** via Shizuku/root (`tunnel_forward=true`).
 * [FleetScrcpyBridge] connects as LocalSocket client for the console screen path.
 */
object FleetScrcpyServer {
    private const val TAG = "FleetScrcpyServer"
    const val SERVER_VERSION = "3.3.4"
    /** Fixed abstract socket (scid omitted → "scrcpy"). */
    const val SOCKET_NAME = "scrcpy"
    private const val ASSET_NAME = "scrcpy-server"
    private const val REMOTE_PATH = "/data/local/tmp/ava-scrcpy-server.jar"
    private const val LOCAL_NAME = "scrcpy-server.jar"

    private val running = AtomicBoolean(false)
    private val lastError = AtomicReference<String?>(null)
    private val cachedAssetBytes = AtomicLong(-1L)

    fun statusJson(context: Context): JSONObject {
        val shell = shellBackend()
        val bridge = FleetScrcpyBridge.statusJson()
        return JSONObject()
            .put("bundled", true)
            .put("version", SERVER_VERSION)
            .put("assetBytes", cachedBundledBytes(context))
            .put("localPath", localJar(context).absolutePath)
            .put("remotePath", REMOTE_PATH)
            .put("socketName", SOCKET_NAME)
            .put("running", running.get() || FleetScrcpyBridge.isActive())
            .put("shellBackend", shell ?: JSONObject.NULL)
            .put("canLaunch", shell != null)
            .put("minSdk", 21)
            .put("maxSdkHint", 36)
            .put("protocol", "scrcpy-h264")
            .put("consoleBridge", bridge.optString("consoleBridge", "idle"))
            .put("bridge", bridge)
            .put("lastError", lastError.get() ?: bridge.opt("lastError") ?: JSONObject.NULL)
            .put(
                "adbHint",
                "adb push app-assets/scrcpy-server $REMOTE_PATH && " +
                    "adb shell CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION " +
                    "tunnel_forward=true audio=false control=false cleanup=false max_size=640",
            )
    }

    fun shellBackend(): String? = when {
        ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
        RootUtils.isRootAvailable() -> "root"
        else -> null
    }

    fun ensureExtracted(context: Context): File {
        val out = localJar(context)
        if (!out.isFile || out.length() < 10_000L) {
            context.assets.open(ASSET_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setReadable(true, false)
        }
        val staged = stagedJar(context)
        if (!staged.isFile || staged.length() != out.length()) {
            out.copyTo(staged, overwrite = true)
            staged.setReadable(true, false)
        }
        Log.i(TAG, "scrcpy-server ready (${out.length()} bytes)")
        return staged
    }

    /**
     * Push jar, start server under shell/root, then attach [FleetScrcpyBridge].
     */
    fun start(context: Context): Boolean {
        lastError.set(null)
        if (FleetScrcpyBridge.isActive()) {
            running.set(true)
            return true
        }
        val backends = buildList {
            if (ShizukuUtils.isShizukuPermissionGranted()) add("shizuku")
            if (RootUtils.isRootAvailable()) add("root")
        }
        if (backends.isEmpty()) {
            lastError.set("need_shizuku_or_root")
            return false
        }
        return try {
            val staged = ensureExtracted(context)
            var lastFail = "push_failed"
            for (backend in backends) {
                // Clean previous server so LocalServerSocket can bind.
                shellExec(backend, "pkill -f 'com.genymobile.scrcpy.Server' || true")
                Thread.sleep(120)

                val push = shellExec(
                    backend,
                    "cp '${staged.absolutePath}' '$REMOTE_PATH' && chmod 644 '$REMOTE_PATH'",
                )
                if (push != 0) {
                    lastFail = "push_failed_${backend}_$push"
                    Log.w(TAG, "scrcpy push via $backend failed: $push")
                    continue
                }

                // Stage the socket relay so the bridge can read the server's
                // sockets on enforcing / no-user-service ROMs. Best-effort.
                AppWindowSocketRelay.ensureStaged(context, backend)

                // Video-only: console taps use Accessibility, not scrcpy control.
                // control=true blocks the server on a 2nd LocalSocket accept and was
                // starving device-meta → bridge EOF / UI stuck on "starting scrcpy".
                val cmd =
                    "CLASSPATH=$REMOTE_PATH nohup app_process / com.genymobile.scrcpy.Server $SERVER_VERSION " +
                        "log_level=info tunnel_forward=true audio=false control=false cleanup=false " +
                        "video_bit_rate=400000 max_size=640 max_fps=5 " +
                        ">/data/local/tmp/ava-scrcpy.log 2>&1 & echo \$!"
                val (code, out) = shellExecWithOutput(backend, cmd)
                if (code != 0) {
                    lastFail = "start_failed_${backend}_$code"
                    Log.w(TAG, "scrcpy start via $backend failed: $code $out")
                    continue
                }
                running.set(true)
                lastError.set(null)
                Log.i(TAG, "scrcpy-server start via $backend: $out")
                // Attach realtime refresh layer (MediaCodec → JPEG cache).
                FleetScrcpyBridge.start(SOCKET_NAME, withControl = false)
                return true
            }
            lastError.set(lastFail)
            running.set(false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            lastError.set(e.message ?: "start_exception")
            running.set(false)
            false
        }
    }

    fun stop(): Boolean {
        FleetScrcpyBridge.stop()
        val backend = shellBackend()
        if (backend != null) {
            // Never block fleet HTTP lifecycle forever on a hung su/pkill.
            shellExec(backend, "pkill -f 'com.genymobile.scrcpy.Server' || true", timeoutSec = 2)
        }
        running.set(false)
        return true
    }

    private fun localJar(context: Context): File = File(context.filesDir, LOCAL_NAME)

    private fun stagedJar(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, LOCAL_NAME)
    }

    private fun cachedBundledBytes(context: Context): Long {
        val cached = cachedAssetBytes.get()
        if (cached >= 0L) return cached
        val bytes = bundledBytes(context)
        cachedAssetBytes.compareAndSet(-1L, bytes)
        return cachedAssetBytes.get().coerceAtLeast(0L)
    }

    private fun bundledBytes(context: Context): Long = try {
        context.assets.openFd(ASSET_NAME).use { it.length }
    } catch (_: Exception) {
        try {
            context.assets.open(ASSET_NAME).use { it.available().toLong() }
        } catch (_: Exception) {
            0L
        }
    }

    private fun shellExec(backend: String, command: String, timeoutSec: Long = 8): Int {
        return when (backend) {
            "shizuku" -> ShizukuUtils.executeCommand(command).first
            "root" -> runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                if (!waitForProcess(p, timeoutSec)) {
                    Log.w(TAG, "shellExec timed out after ${timeoutSec}s: $command")
                    destroyProcess(p)
                    -1
                } else {
                    p.exitValue()
                }
            }.getOrDefault(1)
            else -> -1
        }
    }

    private fun shellExecWithOutput(backend: String, command: String): Pair<Int, String> {
        return when (backend) {
            "shizuku" -> ShizukuUtils.executeCommand(command)
            "root" -> runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                if (!waitForProcess(p, 8)) {
                    destroyProcess(p)
                    return@runCatching 1 to "root_timeout"
                }
                val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
                val err = p.errorStream.bufferedReader().use { it.readText() }.trim()
                p.exitValue() to (out.ifBlank { err })
            }.getOrDefault(1 to "root_failed")
            else -> -1 to "no_backend"
        }
    }

    /** API 21-safe process wait with timeout. */
    private fun waitForProcess(process: Process, timeoutSec: Long): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        }
        val done = AtomicBoolean(false)
        val t = Thread {
            runCatching { process.waitFor() }
            done.set(true)
        }
        t.isDaemon = true
        t.start()
        t.join((timeoutSec.coerceAtLeast(1) * 1000L))
        return done.get()
    }

    private fun destroyProcess(process: Process) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) process.destroyForcibly()
            else process.destroy()
        }
    }
}
