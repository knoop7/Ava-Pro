package com.example.ava.appwindow

import android.content.Context
import android.util.Log
import com.example.ava.utils.ShizukuUtils
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges a shell-owned abstract scrcpy socket to the app across the SELinux
 * wall, using the [SocketRelay] dex shipped as the `ava-socket-relay` asset.
 *
 * scrcpy's server runs under the shell UID (Shizuku/root), so its sockets live
 * in the shell domain. An enforcing device denies this untrusted_app a direct
 * `connectto`, and on ROMs where Shizuku's user service can't bind the
 * fd-over-binder handover ([ShizukuUtils.openLocalSocketFd]) is dead too — that
 * combination is what left a permanent black window on real hardware (verified
 * on a vivo Android 11 device: `userServiceUnavailable=true` + `avc: denied …
 * unix_stream_socket`). The relay sidesteps both: it is started *in the shell
 * domain itself* through [ShizukuUtils.newShellProcess] (or `su`), so its own
 * connect is a same-domain one SELinux allows, and it copies the socket to its
 * stdio — which the app then reads/writes as ordinary pipe fds under its own
 * label. Bytes pass through verbatim, so the caller performs the scrcpy
 * handshake exactly as it would on a direct socket.
 */
object AppWindowSocketRelay {
    private const val TAG = "AppWinRelay"
    private const val ASSET = "ava-socket-relay"
    private const val LOCAL = "ava-socket-relay.jar"
    const val REMOTE = "/data/local/tmp/ava-socket-relay.jar"
    private const val RELAY_CLASS = "com.example.ava.relay.SocketRelay"
    /** Cover the relay's own connect retry window (80×100 ms) plus slack. */
    private const val OK_TIMEOUT_MS = 9_000L

    /**
     * Extract the dex asset and copy it into `/data/local/tmp` (where the shell
     * can read it). Idempotent; safe to call before every session start.
     */
    fun ensureStaged(context: Context, backend: String): Boolean {
        val staged = try {
            extract(context)
        } catch (e: Exception) {
            Log.e(TAG, "extract relay asset failed", e)
            return false
        }
        val code = shellExec(backend, "cp '${staged.absolutePath}' '$REMOTE' && chmod 644 '$REMOTE'")
        if (code != 0) {
            Log.w(TAG, "relay stage cp failed: $code")
            return false
        }
        return true
    }

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

    /**
     * Start one relay for [socketName]. [read]=true copies socket→stdout (read
     * the returned process's [Process.getInputStream]); false copies
     * stdin→socket (write its [Process.getOutputStream]). Blocks until the relay
     * signals a live connection, so a server that never listened fails fast
     * instead of hanging the decoder. Null on spawn failure or connect timeout;
     * the caller then falls back and, failing that, degrades off the mirror.
     */
    fun open(backend: String, socketName: String, read: Boolean, rootUid: Int = 0): Process? {
        val mode = if (read) "read" else "write"
        val inner = "CLASSPATH=$REMOTE exec app_process / $RELAY_CLASS $socketName $mode"
        val proc = when (backend) {
            "shizuku" -> ShizukuUtils.newShellProcess(arrayOf("sh", "-c", inner))
            "root" -> runCatching {
                Runtime.getRuntime().exec(AppWindowRootIdentity.suArgs(rootUid, inner))
            }.getOrNull()
            else -> null
        }
        if (proc == null) {
            Log.w(TAG, "relay spawn failed (backend=$backend socket=$socketName)")
            return null
        }
        if (!awaitOk(proc, socketName)) {
            runCatching { proc.destroy() }
            return null
        }
        Log.i(TAG, "relay ready socket=$socketName mode=$mode")
        return proc
    }

    /**
     * Wait for the relay's single `OK` readiness line on stderr, then keep
     * draining stderr in the background so a chatty relay can never block on a
     * full pipe. Distinguishes "socket is live" from "server never listened".
     */
    private fun awaitOk(proc: Process, socketName: String): Boolean {
        val latch = CountDownLatch(1)
        val first = AtomicReference<String?>(null)
        Thread {
            val reader = proc.errorStream.bufferedReader()
            runCatching {
                val line = reader.readLine()
                first.set(line)
                latch.countDown()
                if (line != null && line.startsWith("OK")) {
                    while (reader.readLine() != null) { /* discard */ }
                }
            }.onFailure { latch.countDown() }
        }.apply { isDaemon = true; start() }

        val signalled = latch.await(OK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val line = first.get()
        if (!signalled || line == null || !line.startsWith("OK")) {
            Log.w(TAG, "relay not ready socket=$socketName (signalled=$signalled line=$line)")
            return false
        }
        return true
    }

    private fun shellExec(backend: String, command: String): Int = when (backend) {
        "shizuku" -> ShizukuUtils.executeCommand(command).first
        "root" -> runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        }.getOrDefault(-1)
        else -> -1
    }
}
