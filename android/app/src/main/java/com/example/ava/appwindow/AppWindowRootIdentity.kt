package com.example.ava.appwindow

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root identity for the scrcpy mirror.
 *
 * DisplayManager rejects uid 0 claiming `com.android.shell` (`packageName
 * must match the calling uid` on Xiaomi). The working identity is **system
 * (uid 1000)** plus a FakeContext overlay that reports package `android`.
 * There is no uid-0 fallback — it cannot create the virtual display.
 */
object AppWindowRootIdentity {
    private const val TAG = "AvaRootCtx"
    private const val ASSET = "ava-scrcpy-root-context"
    private const val LOCAL = "ava-scrcpy-root-context.jar"
    const val REMOTE = "/data/local/tmp/ava-scrcpy-root-context.jar"
    const val SYSTEM_UID = 1000
    const val ROOT_UID = 0

    fun ensureStaged(context: Context, backend: String): Boolean {
        if (backend != "root") return false
        val staged = try {
            extract(context)
        } catch (e: Exception) {
            Log.e(TAG, "extract root FakeContext overlay failed", e)
            return false
        }
        val code = exec(ROOT_UID, "cp '${staged.absolutePath}' '$REMOTE' && chmod 644 '$REMOTE'")
        if (code != 0) {
            Log.w(TAG, "root FakeContext overlay cp failed: $code")
            return false
        }
        Log.i(TAG, "root FakeContext overlay staged ($REMOTE)")
        return true
    }

    /** Always system uid 1000. Xiaomi DisplayManager rejects uid 0 with
     *  `packageName must match the calling uid` whether the package is
     *  `com.android.shell` or `android`. Verified on MI 9 Android 12. */
    fun scrcpyUid(): Int = SYSTEM_UID

    fun suArgs(uid: Int, command: String): Array<String> =
        if (uid == ROOT_UID) {
            arrayOf("su", "-c", command)
        } else {
            arrayOf("su", uid.toString(), "-c", command)
        }

    fun exec(uid: Int, command: String, timeoutSec: Long = 8): Int = runCatching {
        val p = Runtime.getRuntime().exec(suArgs(uid, command))
        if (waitFor(p, timeoutSec)) p.exitValue() else {
            p.destroy()
            -1
        }
    }.getOrDefault(-1)

    fun execOutput(uid: Int, command: String, timeoutSec: Long = 8): String = runCatching {
        val p = Runtime.getRuntime().exec(suArgs(uid, command))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        if (!waitFor(p, timeoutSec)) {
            p.destroy()
            return@runCatching (out + err).trim()
        }
        (out + err).trim()
    }.getOrDefault("")

    private fun extract(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, LOCAL)
        val assetLen = runCatching { context.assets.openFd(ASSET).use { it.length } }.getOrDefault(-1L)
        if (!out.isFile || (assetLen > 0 && out.length() != assetLen)) {
            context.assets.open(ASSET).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setReadable(true, false)
        }
        return out
    }

    private fun waitFor(process: Process, timeoutSec: Long): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return process.waitFor(timeoutSec, TimeUnit.SECONDS)
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutSec * 1000L
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
            }
        }
        return false
    }
}
