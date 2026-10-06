package com.example.ava.appwindow

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.example.ava.fleet.FleetScrcpyServer
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One windowed-app session.
 *
 * Runs the bundled Genymobile scrcpy-server on a freshly created **virtual
 * display** (`new_display=WxH/dpi`), decodes its H.264 stream straight onto
 * [Surface], starts the target app on that display, and forwards touches back
 * over the scrcpy control socket.
 *
 * The server runs under the shell UID (Shizuku), so its scrcpy sockets live in
 * the shell SELinux domain. An enforcing device denies this untrusted_app the
 * `connectto` needed to open them directly — that was the real cause of the
 * black window on real hardware (`avc: denied … tclass=unix_stream_socket`),
 * independent of the display-permission story above. [connectPipe] fixes it via
 * [AppWindowSocketRelay]: a tiny dex run in the shell domain (through
 * `Shizuku.newProcess`/`su`) connects to the socket and bridges it to stdio,
 * which the app reads/writes as ordinary pipe fds. That works even on ROMs where
 * Shizuku's user service can't bind (so the older fd-over-binder handover is
 * dead); the fd path and a plain direct connect remain as fallbacks.
 *
 * Requirements (see the settings copy): a privileged shell — **root is
 * preferred** when available, otherwise Shizuku in ADB mode — and Android 10+
 * (scrcpy's `new_display` hard floor). On Magisk/Xiaomi, uid 0 claiming
 * `com.android.shell` is rejected (`packageName must match the calling uid`).
 * The working identity, verified on-device, is **system uid 1000** plus
 * [AppWindowRootIdentity]'s FakeContext overlay (package `android`). Shizuku
 * stays on the upstream shell identity. Only Android 13+ gets a *trusted*
 * virtual display under shell; on 10–12 the display is untrusted. A 2025
 * security patch removed ADD_TRUSTED_DISPLAY from the shell on many Android
 * 14/15 builds — [ShellTrustedDisplay] steps those Shizuku-only devices down
 * to [FreeformAppWindow] unless root is present. Reuses the same push/launch
 * mechanism and bundled jar as [FleetScrcpyServer], but on its own socket id
 * so it never collides with the fleet console mirror.
 */
class AppWindowScrcpy(
    private val context: Context,
    private val packageName: String,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val displayDpi: Int,
) {
    /**
     * Random per session (the fleet console omits scid → plain "scrcpy", so no
     * collision there either). A kill aimed at a previous session — stop() runs
     * its shell round-trip asynchronously — can then never match this server.
     */
    private val scidHex = "%08x".format(Random.nextInt(0x10000000, 0x7fffffff))
    private val socketName = "scrcpy_$scidHex"
    private val remoteJar = "/data/local/tmp/ava-appwin-server.jar"
    // Per-scid log so concurrent windows don't clobber each other's output.
    private val logPath = "/data/local/tmp/ava-appwin-$scidHex.log"

    private val active = AtomicBoolean(false)
    @Volatile private var stopping = false
    /** Fired from the worker thread when the session dies without [stop]/[stopBlocking]. */
    @Volatile var onSessionEnded: (() -> Unit)? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var preGrantThread: Thread? = null
    @Volatile private var videoPipe: Pipe? = null
    @Volatile private var controlPipe: Pipe? = null
    @Volatile private var controlOut: DataOutputStream? = null
    @Volatile private var srcW: Int = displayWidth
    @Volatile private var srcH: Int = displayHeight
    /** Uid the server/relay run as under the root backend (1000 system, else 0). */
    @Volatile private var rootRunUid: Int = AppWindowRootIdentity.ROOT_UID
    /** Magisk/KSU kills `su -c 'nohup … &'` children when the su session
     *  exits. Keep this Process so the server stays in the live cgroup. */
    @Volatile private var serverProc: Process? = null

    fun isActive(): Boolean = active.get() && worker?.isAlive == true

    /** Push + launch the server, then decode video onto [surface] on a worker thread. */
    fun start(surface: Surface): Boolean {
        if (isActive()) return true
        val backend = shellBackend() ?: run {
            Log.w(TAG, "no privileged shell (need Shizuku ADB or root)")
            return false
        }
        val staged = try {
            FleetScrcpyServer.ensureExtracted(context)
        } catch (e: Exception) {
            Log.e(TAG, "extract scrcpy-server failed", e)
            return false
        }
        // No blanket pkill here: scids are random per session, so each window's
        // server is unique and independent. Killing every "ava-appwin-server.jar"
        // would take down the *other* live windows too. Orphans from a previous
        // process are cleared once by the service at startup, and this window's
        // own server is stopped by scid in [killServer].
        val pushCmd = "cp '${staged.absolutePath}' '$remoteJar' && chmod 644 '$remoteJar'"
        var push = shellExec(backend, pushCmd)
        if (push != 0) {
            Log.w(TAG, "push failed: $push; retrying")
            Thread.sleep(250)
            push = shellExec(backend, pushCmd)
        }
        if (push != 0) {
            Log.w(TAG, "push failed: $push")
            return false
        }

        // Stage the socket relay too: on enforcing devices where the user
        // service can't bind, it's the only way to read the server's sockets.
        // Best-effort — [connectPipe] falls back to fd/direct if it's missing.
        AppWindowSocketRelay.ensureStaged(context, backend)

        // Root: overlay FakeContext AND run the server as system uid 1000
        // (the identity that actually holds ADD_TRUSTED_DISPLAY). Uid 0
        // claiming com.android.shell is what made new_display die after video
        // meta. See [AppWindowRootIdentity].
        val rootOverlay = AppWindowRootIdentity.ensureStaged(context, backend)
        if (backend == "root") {
            rootRunUid = AppWindowRootIdentity.SYSTEM_UID
            if (!rootOverlay) {
                Log.e(TAG, "root FakeContext overlay missing; aborting (uid 0 cannot create the display)")
                return false
            }
            Log.i(TAG, "root path: su ${rootRunUid} + FakeContext overlay")
        }
        val classpath = if (rootOverlay) {
            "${AppWindowRootIdentity.REMOTE}:$remoteJar"
        } else {
            remoteJar
        }

        // Pre-grant the app's runtime permissions in parallel with server
        // startup ([runSession] joins this before launching the app). A
        // permission dialog opening on the virtual display would force-hide
        // every overlay window in the system — the mirror included — leaving
        // all small windows invisible with no way to answer the trapped
        // prompt. See [AppWindowPermissions].
        preGrantThread = Thread(
            { AppWindowPermissions.grantAll(packageName, backend) },
            "AppWinPreGrant",
        ).apply {
            isDaemon = true
            start()
        }

        // scrcpy's virtual display inherits the system windowing mode at
        // creation. This device carries enable_freeform_support=1 (the freeform
        // fallback path turns it on), which makes the new display default to
        // *freeform*: the app then opens as a small ~660x770 freeform window
        // with black all around instead of filling the mirror — the "很小一块"
        // reported on the vivo Android 11 device. Turn it off so the display is
        // born fullscreen and the app fills it. Verified on-device: the same
        // recorder came up freeform 660x770 with the flag on, fullscreen
        // 770x1372 with it off. Must precede new_display below (the mode is
        // fixed when the display is created); a plain per-launch
        // `--windowingMode 1` does not override a freeform-mode display here.
        // The freeform fallback ([FreeformAppWindow]) flips this back on for
        // itself if the mirror ever degrades to it.
        shellExec(backend, "settings put global enable_freeform_support 0")

        val inner = buildString {
            append("CLASSPATH=$classpath app_process / com.genymobile.scrcpy.Server ")
            append(FleetScrcpyServer.SERVER_VERSION)
            append(" scid=").append(scidHex)
            append(" log_level=info tunnel_forward=true audio=false control=true cleanup=true")
            append(" video_bit_rate=8000000 max_fps=60")
            append(" new_display=").append(displayWidth).append("x").append(displayHeight)
            append("/").append(displayDpi)
            append(" >").append(logPath).append(" 2>&1")
        }
        if (backend == "root") {
            // system uid 1000 can read world-readable jars but cannot create
            // files in /data/local/tmp (0771 shell:shell). Pre-create the log.
            AppWindowRootIdentity.exec(
                AppWindowRootIdentity.ROOT_UID,
                "chmod 755 /data/local/tmp; " +
                    "chmod 644 '$remoteJar' '${AppWindowRootIdentity.REMOTE}' " +
                    "'${AppWindowSocketRelay.REMOTE}'; " +
                    "touch '$logPath' && chmod 666 '$logPath'",
            )
            val proc = runCatching {
                Runtime.getRuntime().exec(AppWindowRootIdentity.suArgs(rootRunUid, inner))
            }.getOrNull()
            if (proc == null) {
                Log.w(TAG, "server spawn failed uid=$rootRunUid")
                return false
            }
            drainQuietly(proc)
            serverProc = proc
            Thread.sleep(80)
            if (!processAlive(proc)) {
                Log.w(TAG, "server process died immediately uid=$rootRunUid")
                dumpServerLog()
                return false
            }
        } else {
            val code = shellExec(backend, "$inner &")
            if (code != 0) {
                Log.w(TAG, "server start failed: $code")
                return false
            }
        }
        Log.i(
            TAG,
            "server start backend=$backend overlay=$rootOverlay uid=$rootRunUid socket=$socketName",
        )

        active.set(true)
        worker = Thread({
            try {
                runSession(surface)
            } catch (e: Exception) {
                // Closing the window (or any [stop]) tears the socket down
                // mid-read, which surfaces here as EOF/interrupt — that is normal
                // teardown, not a crash, so it must not be logged as a red
                // error+stack trace that reads like one. Only a genuinely
                // unexpected end (server died while the window was live) is worth
                // a warning, and still without the noisy trace for a plain EOF.
                when {
                    stopping || !active.get() -> Log.i(TAG, "session closed ($socketName)")
                    e is EOFException || e is InterruptedException -> {
                        Log.w(TAG, "session ended early ($socketName): ${e.javaClass.simpleName}")
                        dumpServerLog()
                    }
                    else -> {
                        Log.w(TAG, "session ended ($socketName)", e)
                        dumpServerLog()
                    }
                }
            } finally {
                active.set(false)
                closeSockets()
                if (!stopping) onSessionEnded?.invoke()
            }
        }, "AppWindowScrcpy").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun runSession(surface: Surface) {
        val vPipe = connectPipe(Dir.INPUT, retries = 60, delayMs = 100L)
            ?: throw IOException("connect_video_timeout")
        videoPipe = vPipe
        val vin = vPipe.input ?: throw IOException("no_video_stream")

        // tunnel_forward: the first socket to connect receives one dummy byte.
        vin.readUnsignedByte()

        // Control socket connects second; device meta on the video socket only
        // arrives once the server has accepted it.
        val cPipe = connectPipe(Dir.OUTPUT, retries = 40, delayMs = 50L)
            ?: throw IOException("connect_control_timeout")
        controlPipe = cPipe
        controlOut = cPipe.output

        // Device meta: 64-byte name, then codecId + width + height.
        val nameBuf = ByteArray(64)
        vin.readFully(nameBuf)
        val codecId = vin.readInt()
        val w = vin.readInt()
        val h = vin.readInt()
        srcW = w.coerceAtLeast(1)
        srcH = h.coerceAtLeast(1)
        Log.i(TAG, "video meta ${srcW}x$srcH codec=0x${Integer.toHexString(codecId)}")
        if (codecId != CODEC_H264) throw IOException("unsupported_codec_$codecId")

        // The app must not launch before its permissions are pre-granted, or
        // it may still throw a grant dialog onto the virtual display (which
        // force-hides every overlay window — see [AppWindowPermissions]). The
        // grants ran concurrently with the connect/handshake above, so this
        // join is usually instant; the timeout just keeps a wedged pm from
        // holding the window black forever.
        preGrantThread?.let { runCatching { it.join(6_000L) } }
        preGrantThread = null

        // Launch the target app onto the just-created virtual display. The "+"
        // prefix force-stops any existing instance first, so the app is
        // (re)created *on this display* instead of the already-running task
        // being brought to the front on the real screen — that stray real-screen
        // window is exactly the "leak" we must prevent. The display was created
        // fullscreen (see the enable_freeform_support=0 write in [start]), so a
        // plain start fills it; no per-launch windowing-mode override is needed
        // (and `--windowingMode 1` is ignored on this device anyway once the
        // display itself is freeform-mode).
        controlOut?.let {
            try {
                ScrcpyControl.writeStartApp(it, "+$packageName")
            } catch (e: Exception) {
                Log.w(TAG, "start_app failed", e)
            }
        }

        decodeLoop(vin, surface, srcW, srcH)
    }

    private fun decodeLoop(vin: DataInputStream, surface: Surface, w: Int, h: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, surface, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        try {
            while (active.get()) {
                val ptsFlags = vin.readLong()
                val size = vin.readInt()
                if (size <= 0 || size > MAX_PACKET) throw IOException("bad_packet_$size")
                val payload = ByteArray(size)
                vin.readFully(payload)

                val isConfig = ptsFlags and PACKET_FLAG_CONFIG != 0L
                val pts = ptsFlags and (PACKET_FLAG_CONFIG or PACKET_FLAG_KEY).inv()

                val inIdx = codec.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null && buf.capacity() >= size) {
                        buf.clear()
                        buf.put(payload)
                        var flags = 0
                        if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        if (ptsFlags and PACKET_FLAG_KEY != 0L) {
                            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        codec.queueInputBuffer(inIdx, 0, size, pts.coerceAtLeast(0L), flags)
                    } else if (buf != null) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    }
                }

                var outIdx = codec.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true) // render straight to the surface
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Forward a touch. [viewX]/[viewY] are pixels inside the mirror view of size
     * [viewW]×[viewH]; they are scaled into the virtual-display resolution.
     */
    fun sendTouch(action: Int, viewX: Float, viewY: Float, viewW: Int, viewH: Int) {
        val out = controlOut ?: return
        val w = srcW
        val h = srcH
        val x = (viewX / viewW.coerceAtLeast(1) * w).toInt().coerceIn(0, w)
        val y = (viewY / viewH.coerceAtLeast(1) * h).toInt().coerceIn(0, h)
        val pressure = if (action == ScrcpyControl.ACTION_UP) 0f else 1f
        try {
            synchronized(out) {
                ScrcpyControl.writeTouch(out, action, 0L, x, y, w, h, pressure)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendTouch failed", e)
        }
    }

    fun sendKey(action: Int, keycode: Int) {
        val out = controlOut ?: return
        try {
            synchronized(out) {
                ScrcpyControl.writeKeycode(out, action, keycode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendKey failed", e)
        }
    }

    fun sendBack() {
        sendKey(ScrcpyControl.ACTION_DOWN, ScrcpyControl.KEYCODE_BACK)
        sendKey(ScrcpyControl.ACTION_UP, ScrcpyControl.KEYCODE_BACK)
    }

    fun sendMouseClick(viewX: Float, viewY: Float, viewW: Int, viewH: Int, button: Int) {
        val out = controlOut ?: return
        val w = srcW
        val h = srcH
        val x = (viewX / viewW.coerceAtLeast(1) * w).toInt().coerceIn(0, w)
        val y = (viewY / viewH.coerceAtLeast(1) * h).toInt().coerceIn(0, h)
        try {
            synchronized(out) {
                ScrcpyControl.writeTouch(
                    out,
                    ScrcpyControl.ACTION_DOWN,
                    ScrcpyControl.POINTER_ID_MOUSE,
                    x,
                    y,
                    w,
                    h,
                    1f,
                    button,
                    button,
                )
                ScrcpyControl.writeTouch(
                    out,
                    ScrcpyControl.ACTION_UP,
                    ScrcpyControl.POINTER_ID_MOUSE,
                    x,
                    y,
                    w,
                    h,
                    0f,
                    button,
                    0,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMouseClick failed", e)
        }
    }

    fun sendScroll(viewX: Float, viewY: Float, viewW: Int, viewH: Int, hScroll: Float, vScroll: Float) {
        val out = controlOut ?: return
        val w = srcW
        val h = srcH
        val x = (viewX / viewW.coerceAtLeast(1) * w).toInt().coerceIn(0, w)
        val y = (viewY / viewH.coerceAtLeast(1) * h).toInt().coerceIn(0, h)
        try {
            synchronized(out) {
                ScrcpyControl.writeScroll(out, x, y, w, h, hScroll, vScroll)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendScroll failed", e)
        }
    }

    fun stop() {
        stopping = true
        active.set(false)
        closeSockets()
        worker?.interrupt()
        worker = null
        // Killing the server tears down the virtual display (and the app on it).
        // Callers are on the main thread (surfaceDestroyed/onDestroy), so the
        // shell round-trip must not run inline.
        Thread({ killServer() }, "AppWindowScrcpyStop").apply {
            isDaemon = true
            start()
        }
    }

    /** [stop] with the server kill inline: for background callers that must
     *  guarantee the old server is dead before launching a replacement. */
    fun stopBlocking() {
        stopping = true
        active.set(false)
        closeSockets()
        worker?.interrupt()
        worker = null
        killServer()
    }

    private fun killServer() {
        // Killing the server releases its virtual display, which is created with
        // REMOVE_MODE_DESTROY_CONTENT (verified on-device via `dumpsys display`),
        // so the app's activities are destroyed with it rather than migrating to
        // the real screen — no separate `am force-stop` needed. "[s]cid" keeps
        // the pkill pattern from matching the pkill shell's own command line.
        // The second pkill reaps this session's relay processes (their cmdline
        // carries "scrcpy_<scid>"); the bracket keeps it from matching itself.
        runCatching { serverProc?.destroy() }
        serverProc = null
        shellBackend()?.let {
            shellExec(it, "pkill -f '[s]cid=$scidHex' || true")
            shellExec(it, "pkill -f '[s]crcpy_$scidHex' || true")
        }
    }

    private fun drainQuietly(proc: Process) {
        Thread({ runCatching { proc.inputStream.copyTo(NullOutputStream) } }, "AppWinSrvOut").apply {
            isDaemon = true
            start()
        }
        Thread({ runCatching { proc.errorStream.copyTo(NullOutputStream) } }, "AppWinSrvErr").apply {
            isDaemon = true
            start()
        }
    }

    private fun processAlive(proc: Process): Boolean = try {
        proc.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun closeSockets() {
        runCatching { controlPipe?.close?.invoke() }
        runCatching { videoPipe?.close?.invoke() }
        controlOut = null
        controlPipe = null
        videoPipe = null
    }

    /** One scrcpy socket, read- or write-only, plus how to release it. */
    private class Pipe(
        val input: DataInputStream?,
        val output: DataOutputStream?,
        val close: () -> Unit,
    )

    private enum class Dir { INPUT, OUTPUT }

    /**
     * Open one scrcpy socket. The primary transport is the shell-domain
     * [AppWindowSocketRelay]: SELinux forbids an untrusted_app from `connectto`
     * a shell-owned abstract socket, and on ROMs where the Shizuku user service
     * can't bind the fd-over-binder handover is unavailable too — that pair is
     * exactly what left a black window on real hardware. The relay connects in
     * the shell domain and pipes the socket to stdio, which the app reads/writes
     * as its own fds; it self-retries while the server comes up, so no outer
     * retry loop is needed on that path. Fallbacks, for permissive/old ROMs or
     * when the relay can't spawn: the fd handover
     * ([ShizukuUtils.openLocalSocketFd], needs the user service) then a plain
     * direct connect. If every path fails the session dies and
     * [com.example.ava.services.AppWindowService] degrades off the mirror.
     */
    private fun connectPipe(dir: Dir, retries: Int, delayMs: Long): Pipe? {
        val backend = shellBackend()
        Log.i(TAG, "connectPipe dir=$dir socket=$socketName backend=$backend")
        if (backend != null) {
            openViaRelay(backend, dir)?.let {
                Log.i(TAG, "connectPipe dir=$dir OK via relay")
                return it
            }
            Log.w(TAG, "connectPipe dir=$dir relay unavailable; trying fd/direct")
        }
        val viaFd = ShizukuUtils.isShizukuPermissionGranted()
        var lastFd = "n/a"
        var lastDirect = "n/a"
        repeat(retries) { attempt ->
            if (!active.get()) return null
            if (viaFd) {
                when (val r = openViaFd(dir)) {
                    is OpenResult.Ok -> {
                        Log.i(TAG, "connectPipe dir=$dir OK via fd (attempt ${attempt + 1})")
                        return r.pipe
                    }
                    is OpenResult.Err -> lastFd = r.why
                }
            }
            when (val r = openDirect(dir)) {
                is OpenResult.Ok -> {
                    Log.i(TAG, "connectPipe dir=$dir OK via direct (attempt ${attempt + 1})")
                    return r.pipe
                }
                is OpenResult.Err -> lastDirect = r.why
            }
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        Log.w(TAG, "connectPipe dir=$dir FAILED after $retries; lastFd=$lastFd lastDirect=$lastDirect")
        return null
    }

    private sealed class OpenResult {
        class Ok(val pipe: Pipe) : OpenResult()
        class Err(val why: String) : OpenResult()
    }

    private fun openViaRelay(backend: String, dir: Dir): Pipe? {
        val proc = AppWindowSocketRelay.open(
            backend,
            socketName,
            read = dir == Dir.INPUT,
            rootUid = if (backend == "root") rootRunUid else AppWindowRootIdentity.ROOT_UID,
        ) ?: return null
        return if (dir == Dir.INPUT) {
            Pipe(DataInputStream(proc.inputStream), null) { runCatching { proc.destroy() } }
        } else {
            Pipe(null, DataOutputStream(proc.outputStream)) { runCatching { proc.destroy() } }
        }
    }

    private fun openViaFd(dir: Dir): OpenResult {
        val pfd = ShizukuUtils.openLocalSocketFd(socketName)
            ?: return OpenResult.Err("fd_null")
        return if (dir == Dir.INPUT) {
            val s = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            OpenResult.Ok(Pipe(DataInputStream(s), null) { runCatching { s.close() } })
        } else {
            val s = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            OpenResult.Ok(Pipe(null, DataOutputStream(s)) { runCatching { s.close() } })
        }
    }

    private fun openDirect(dir: Dir): OpenResult = runCatching {
        val sock = LocalSocket()
        sock.connect(LocalSocketAddress(socketName))
        if (dir == Dir.INPUT) {
            OpenResult.Ok(Pipe(DataInputStream(sock.inputStream), null) { runCatching { sock.close() } })
        } else {
            OpenResult.Ok(Pipe(null, DataOutputStream(sock.outputStream)) { runCatching { sock.close() } })
        }
    }.getOrElse { OpenResult.Err(it.javaClass.simpleName + ":" + (it.message ?: "")) }

    private fun dumpServerLog() {
        val fileDump = when (shellBackend()) {
            "shizuku" -> ShizukuUtils.executeCommandForOutput("tail -n 40 '$logPath' 2>/dev/null").second
            "root" -> AppWindowRootIdentity.execOutput(
                AppWindowRootIdentity.ROOT_UID,
                "tail -n 40 '$logPath' 2>/dev/null; echo '--- logcat ---'; " +
                    "logcat -d -t 80 -s scrcpy:V AvaRootCtx:I AndroidRuntime:E",
            )
            else -> ""
        }.trim()
        Log.w(TAG, "server log ($socketName uid=$rootRunUid):\n${fileDump.ifBlank { "(empty)" }}")
    }

    /** Root first: uid 0 can create a trusted virtual display. Shizuku is the
     *  shell-identity fallback when this device has no su. */
    private fun shellBackend(): String? = when {
        RootUtils.isRootAvailable() -> "root"
        ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
        else -> null
    }

    private fun shellExec(backend: String, command: String): Int = when (backend) {
        "shizuku" -> ShizukuUtils.executeCommand(command).first
        "root" -> runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        }.getOrDefault(-1)
        else -> -1
    }

    companion object {
        private const val TAG = "AppWindowScrcpy"
        private const val CODEC_H264 = 0x68323634
        private const val PACKET_FLAG_CONFIG = 1L shl 63
        private const val PACKET_FLAG_KEY = 1L shl 62
        private const val MAX_PACKET = 2 * 1024 * 1024
    }
}
