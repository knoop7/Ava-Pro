package com.example.ava.fleet

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.ava.appwindow.AppWindowSocketRelay
import com.example.ava.utils.ShizukuUtils
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process scrcpy **client** that connects to the shell-UID server via
 * `localabstract` and keeps a realtime refresh layer for the fleet console.
 *
 * Flow:
 * 1. [FleetScrcpyServer] starts Genymobile scrcpy-server (`tunnel_forward=true`)
 * 2. This bridge opens video LocalSocket, parses dummy/meta/packets
 * 3. MediaCodec decodes H.264 → latest JPEG for `GET /v1/screen/frame`
 *
 * Next step (not yet): proxy Annex-B / length-prefixed NALs to the browser
 * over WebSocket + WebCodecs (true zero-transcode path).
 */
object FleetScrcpyBridge {
    private const val TAG = "FleetScrcpyBridge"
    private const val DEVICE_NAME_FIELD_LENGTH = 64
    private const val CODEC_H264 = 0x68323634 // 'h264'
    private const val PACKET_FLAG_CONFIG = 1L shl 63
    private const val PACKET_FLAG_KEY = 1L shl 62
    /** Cap JPEG churn for console poll — keep scrcpy responsive. */
    private const val MIN_JPEG_INTERVAL_MS = 200L
    private const val MAX_PACKET_BYTES = 2 * 1024 * 1024
    private const val JPEG_QUALITY = 45

    data class LatestFrame(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val ptsUs: Long,
        val atMs: Long,
    )

    private val active = AtomicBoolean(false)
    private val latest = AtomicReference<LatestFrame?>(null)
    private val framesDecoded = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val lastError = AtomicReference<String?>(null)
    private val meta = AtomicReference(JSONObject())
    private val lastJpegAt = AtomicLong(0L)

    @Volatile private var worker: Thread? = null
    @Volatile private var videoConn: Conn? = null
    @Volatile private var controlConn: Conn? = null

    /** One connected scrcpy socket (read side only — the bridge never writes)
     *  plus how to release it. */
    private class Conn(val input: InputStream, val close: () -> Unit)

    fun isActive(): Boolean = active.get() && worker?.isAlive == true

    fun latestJpeg(): LatestFrame? = latest.get()

    fun statusJson(): JSONObject {
        val f = latest.get()
        return JSONObject()
            .put("active", isActive())
            .put("consoleBridge", if (isActive()) "mediacodec-jpeg" else "idle")
            .put("framesDecoded", framesDecoded.get())
            .put("framesDropped", framesDropped.get())
            .put("jpegIntervalMs", MIN_JPEG_INTERVAL_MS)
            .put("lastError", lastError.get() ?: JSONObject.NULL)
            .put("width", f?.width ?: 0)
            .put("height", f?.height ?: 0)
            .put("ageMs", f?.let { System.currentTimeMillis() - it.atMs } ?: JSONObject.NULL)
            .put("meta", meta.get())
            .put("socketName", FleetScrcpyServer.SOCKET_NAME)
            .put("next", "ws-h264-webcodecs")
    }

    /** Connect after server listen is up. Safe to call repeatedly. */
    @Synchronized
    fun start(socketName: String = FleetScrcpyServer.SOCKET_NAME, withControl: Boolean = true) {
        if (isActive()) return
        stop()
        lastError.set(null)
        framesDecoded.set(0)
        framesDropped.set(0)
        lastJpegAt.set(0L)
        active.set(true)
        worker = Thread(
            {
                try {
                    runSession(socketName, withControl)
                } catch (e: Exception) {
                    Log.e(TAG, "bridge session ended", e)
                    lastError.set(e.message ?: "bridge_failed")
                } finally {
                    active.set(false)
                    closeSockets()
                }
            },
            "FleetScrcpyBridge",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        active.set(false)
        closeSockets()
        val t = worker
        worker = null
        t?.interrupt()
        try {
            t?.join(1_500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        latest.set(null)
        lastJpegAt.set(0L)
    }

    private fun closeSockets() {
        try { videoConn?.close?.invoke() } catch (_: Exception) {}
        try { controlConn?.close?.invoke() } catch (_: Exception) {}
        videoConn = null
        controlConn = null
    }

    private fun runSession(socketName: String, withControl: Boolean) {
        val video = connectLocal(socketName, retries = 40, delayMs = 100L)
            ?: throw IOException("connect_video_timeout:$socketName")
        videoConn = video
        val vin = DataInputStream(video.input)

        // tunnel_forward: first socket gets a dummy byte, then server blocks on
        // further accepts (audio/control) BEFORE sending device meta.
        val dummy = vin.readUnsignedByte()
        if (dummy != 0) {
            Log.w(TAG, "unexpected dummy byte: $dummy")
        }

        if (withControl) {
            // Must connect control synchronously or video meta never arrives.
            val conn = connectLocal(socketName, retries = 30, delayMs = 50L)
                ?: throw IOException("connect_control_timeout:$socketName")
            controlConn = conn
            Log.i(TAG, "control socket connected")
        }

        val deviceNameBytes = ByteArray(DEVICE_NAME_FIELD_LENGTH)
        vin.readFully(deviceNameBytes)
        val deviceName = deviceNameBytes.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }

        val codecId = vin.readInt()
        val width = vin.readInt()
        val height = vin.readInt()
        meta.set(
            JSONObject()
                .put("deviceName", deviceName)
                .put("codecId", codecId)
                .put("codec", if (codecId == CODEC_H264) "h264" else "0x${Integer.toHexString(codecId)}")
                .put("width", width)
                .put("height", height),
        )
        Log.i(TAG, "video meta device=$deviceName ${width}x$height codec=0x${Integer.toHexString(codecId)}")

        if (codecId != CODEC_H264) {
            throw IOException("unsupported_codec_$codecId")
        }

        decodeLoop(vin, width.coerceAtLeast(2), height.coerceAtLeast(2))
    }

    /**
     * Open one scrcpy socket. Primary transport is the shell-domain
     * [AppWindowSocketRelay] (same fix as `AppWindowScrcpy.connectPipe`): SELinux
     * denies this untrusted_app a direct `connectto` on the shell-owned socket,
     * and the fd-over-binder handover is dead on ROMs where the user service
     * can't bind — the relay works in both cases and self-retries while the
     * server comes up. Fd handover then a plain direct connect stay as
     * fallbacks.
     */
    private fun connectLocal(name: String, retries: Int, delayMs: Long): Conn? {
        val backend = FleetScrcpyServer.shellBackend()
        if (backend != null && active.get()) {
            AppWindowSocketRelay.open(backend, name, read = true)?.let { proc ->
                Log.i(TAG, "connected localabstract:$name via shell relay")
                return Conn(proc.inputStream) { runCatching { proc.destroy() } }
            }
            Log.w(TAG, "relay unavailable for $name; trying fd/direct")
        }
        repeat(retries) { attempt ->
            if (!active.get()) return null
            if (ShizukuUtils.isShizukuPermissionGranted()) {
                ShizukuUtils.openLocalSocketFd(name)?.let { pfd ->
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    Log.i(TAG, "connected localabstract:$name via shell fd (try ${attempt + 1})")
                    return Conn(stream) { runCatching { stream.close() } }
                }
            }
            try {
                val sock = LocalSocket()
                sock.connect(LocalSocketAddress(name))
                Log.i(TAG, "connected localabstract:$name (try ${attempt + 1})")
                return Conn(sock.inputStream) { runCatching { sock.close() } }
            } catch (e: Exception) {
                if (attempt == retries - 1) {
                    lastError.set("connect_failed:${e.message}")
                }
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun decodeLoop(vin: DataInputStream, width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        try {
            while (active.get()) {
                val ptsFlags = vin.readLong()
                val size = vin.readInt()
                if (size <= 0 || size > MAX_PACKET_BYTES) {
                    throw IOException("bad_packet_size_$size")
                }
                val payload = ByteArray(size)
                vin.readFully(payload)

                val isConfig = ptsFlags and PACKET_FLAG_CONFIG != 0L
                val pts = ptsFlags and (PACKET_FLAG_CONFIG or PACKET_FLAG_KEY).inv()

                val inIndex = codec.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    if (inBuf != null && inBuf.capacity() >= size) {
                        inBuf.clear()
                        inBuf.put(payload)
                        var flags = 0
                        if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        if (ptsFlags and PACKET_FLAG_KEY != 0L) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        codec.queueInputBuffer(inIndex, 0, size, pts.coerceAtLeast(0L), flags)
                    } else {
                        if (inBuf != null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        }
                        framesDropped.incrementAndGet()
                    }
                } else {
                    framesDropped.incrementAndGet()
                }

                drainOutputs(codec, info, width, height)
            }
        } catch (e: EOFException) {
            lastError.set("stream_eof")
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private fun drainOutputs(codec: MediaCodec, info: MediaCodec.BufferInfo, width: Int, height: Int) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (outIndex < 0) break
            try {
                if (info.size <= 0) continue
                val now = System.currentTimeMillis()
                // Skip YUV→JPEG most frames: encode is the GC/CPU killer.
                if (now - lastJpegAt.get() < MIN_JPEG_INTERVAL_MS) {
                    framesDropped.incrementAndGet()
                    continue
                }
                val image = codec.getOutputImage(outIndex) ?: continue
                try {
                    val jpeg = imageToJpeg(image, quality = JPEG_QUALITY) ?: continue
                    val w = runCatching {
                        codec.outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                    }.getOrDefault(width).coerceAtLeast(2)
                    val h = runCatching {
                        codec.outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    }.getOrDefault(height).coerceAtLeast(2)
                    latest.set(
                        LatestFrame(
                            jpeg = jpeg,
                            width = w,
                            height = h,
                            ptsUs = info.presentationTimeUs,
                            atMs = now,
                        ),
                    )
                    lastJpegAt.set(now)
                    framesDecoded.incrementAndGet()
                } finally {
                    image.close()
                }
            } finally {
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val nv21 = yuv420888ToNv21(image) ?: return null
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality.coerceIn(30, 90), out)) {
                return null
            }
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "jpeg encode failed", e)
            null
        }
    }

    private fun yuv420888ToNv21(image: Image): ByteArray? {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)

        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        var pos = 0
        for (row in 0 until height) {
            yBuf.position(row * yRow)
            yBuf.get(out, pos, width)
            pos += width
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        val vRow = vPlane.rowStride
        val uRow = uPlane.rowStride
        val vPix = vPlane.pixelStride
        val uPix = uPlane.pixelStride
        var offset = ySize
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRow + col * vPix
                val uIndex = row * uRow + col * uPix
                out[offset++] = vBuf.get(vIndex)
                out[offset++] = uBuf.get(uIndex)
            }
        }
        return out
    }

    /** Drain leftover bytes helper for tests. */
    fun readFullyOrThrow(input: InputStream, buf: ByteArray) {
        DataInputStream(input).readFully(buf)
    }
}
