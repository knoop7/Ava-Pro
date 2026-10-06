package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged shell for the Ava cluster console (ADB-equivalent via Shizuku / root).
 *
 * Shizuku's [IShellService] only returns an exit code, so stdout/stderr are captured
 * by redirecting into a temp file the app can read — same pattern as screencap oneshot.
 */
object FleetShell {
    private const val TAG = "FleetShell"
    private const val MAX_CMD_CHARS = 4_000
    private const val MAX_OUT_CHARS = 64_000
    private const val MIN_INTERVAL_MS = 80L

    private val lastExecAt = AtomicLong(0L)
    private val lastError = AtomicReference<String?>(null)

    fun backend(): String? = when {
        ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
        RootUtils.isRootAvailable() -> "root"
        else -> null
    }

    fun statusJson(): JSONObject {
        val b = backend()
        return JSONObject()
            .put("available", b != null)
            .put("backend", b ?: JSONObject.NULL)
            .put("adbEquivalent", true)
            .put("via", if (b == "shizuku") "shizuku-user-service" else if (b == "root") "su" else JSONObject.NULL)
            .put("hint", "POST /v1/shell/exec {\"command\":\"id\"} — same privilege plane as adb shell")
            .put("lastError", lastError.get() ?: JSONObject.NULL)
            .put("maxCommandChars", MAX_CMD_CHARS)
            .put("maxOutputChars", MAX_OUT_CHARS)
    }

    data class ExecResult(
        val ok: Boolean,
        val code: Int,
        val stdout: String,
        val stderr: String,
        val backend: String?,
        val error: String? = null,
        val elapsedMs: Long = 0L,
    )

    fun exec(context: Context, rawCommand: String, timeoutSec: Int = 15): ExecResult {
        val command = rawCommand.trim()
        if (command.isEmpty()) {
            return ExecResult(false, -1, "", "", backend(), "empty_command")
        }
        if (command.length > MAX_CMD_CHARS) {
            return ExecResult(false, -1, "", "", backend(), "command_too_long")
        }
        if (isDangerous(command)) {
            lastError.set("blocked_command")
            return ExecResult(false, -1, "", "", backend(), "blocked_command")
        }

        val now = System.currentTimeMillis()
        val since = now - lastExecAt.get()
        if (since in 0 until MIN_INTERVAL_MS) {
            Thread.sleep(MIN_INTERVAL_MS - since)
        }
        lastExecAt.set(System.currentTimeMillis())
        lastError.set(null)

        val b = backend()
        if (b == null) {
            lastError.set("need_shizuku_or_root")
            return ExecResult(false, -1, "", "", null, "need_shizuku_or_root")
        }

        val started = System.currentTimeMillis()
        return try {
            when (b) {
                "root" -> execRoot(command, timeoutSec.coerceIn(1, 60))
                else -> execShizuku(command, timeoutSec)
            }.copy(backend = b, elapsedMs = System.currentTimeMillis() - started)
        } catch (e: Exception) {
            Log.e(TAG, "shell exec failed", e)
            lastError.set(e.message ?: "exec_exception")
            ExecResult(false, -1, "", "", b, e.message ?: "exec_exception", System.currentTimeMillis() - started)
        }
    }

    private fun execRoot(command: String, timeoutSec: Int): ExecResult {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val finished = waitForProcess(process, timeoutSec.coerceIn(1, 60) * 1000L)
        if (!finished) {
            runCatching { process.destroy() }
            lastError.set("timeout")
            return ExecResult(false, -1, "", "", "root", "timeout")
        }
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.exitValue()
        return ExecResult(
            ok = code == 0,
            code = code,
            stdout = clip(out),
            stderr = clip(err),
            backend = "root",
        )
    }

    private fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
            }
        }
        return try {
            process.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }

    private fun execShizuku(command: String, timeoutSec: Int): ExecResult {
        // Capture output in-process via the Shizuku user service (see [ShellService.
        // executeCommandForOutput]). This avoids the old temp-file + `cp` dance, which
        // failed on scoped storage (shell uid 2000 can't write the app's external files
        // dir) and on stale file ownership — surfacing as spurious `shell_failed_1` for
        // both the terminal and `input tap`.
        //
        // Wrap in `timeout … sh -c '<escaped>' </dev/null` so interactive tools can't
        // hang the binder call and nothing pins a worker forever.
        val escaped = command.replace("'", "'\\''")
        val guard = timeoutSec.coerceIn(1, 60)
        val wrapped = "timeout ${guard}s sh -c '$escaped' </dev/null 2>&1"
        val (code, output) = ShizukuUtils.executeCommandForOutput(wrapped)
        if (code < 0 && output.isBlank()) {
            lastError.set("shizuku_unavailable")
            return ExecResult(false, code, "", "", "shizuku", "shizuku_unavailable")
        }
        val trailingErr = when {
            code == 0 -> null
            code == 124 -> "timeout"
            output.isBlank() -> "shell_failed_$code"
            else -> null // exit != 0 with output is normal shell behaviour.
        }
        return ExecResult(
            ok = code == 0,
            code = code,
            stdout = clip(output),
            stderr = "",
            backend = "shizuku",
            error = trailingErr,
        )
    }

    private fun clip(s: String): String {
        if (s.length <= MAX_OUT_CHARS) return s
        return s.take(MAX_OUT_CHARS) + "\n…[truncated]"
    }

    /** Block reboot/wipe/fork bombs — keep console useful without being a footgun. */
    private fun isDangerous(command: String): Boolean {
        val c = command.lowercase()
        val blocked = listOf(
            "rm -rf /",
            "rm -rf /*",
            "mkfs",
            "dd if=/dev/zero",
            "reboot",
            "fastboot",
            ":(){", // fork bomb
            "wipe ",
            "format ",
            "recovery --wipe",
        )
        return blocked.any { c.contains(it) }
    }

    fun toJson(result: ExecResult): JSONObject = JSONObject()
        .put("ok", result.ok)
        .put("code", result.code)
        .put("stdout", result.stdout)
        .put("stderr", result.stderr)
        .put("backend", result.backend ?: JSONObject.NULL)
        .put("error", result.error ?: JSONObject.NULL)
        .put("elapsedMs", result.elapsedMs)
        .put("adbEquivalent", true)
}
