package com.example.ava.backup

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-shot LAN settings-clone transfer.
 *
 * Discovery rides the existing Ava presence beacon (UDP 19848, `clonePort=<port>`
 * field — see [com.example.ava.voice.AvaVoiceDiscovery.setAdvertisedClonePort]);
 * this file is only the payload channel: a TCP socket that lives for at most
 * [AvaCloneServer.WINDOW_MS], authenticated by a six-digit pairing code.
 *
 * Wire protocol (UTF-8 lines, then raw bytes):
 * ```
 * server → AVA_CLONE|v1|<saltB64>|<challengeB64>\n
 * client → AUTH|<proofB64>\n
 * server → ERR|bad_code\n            (wrong code — connection closes)
 * server → ERR|locked\n              (too many wrong codes — window closes)
 * server → OK|<len>\n + <len> bytes  (12-byte GCM IV + ciphertext)
 * ```
 *
 * Keys are derived from the pairing code with PBKDF2 over a per-window salt, so the
 * payload (which may carry HA credentials) never crosses the LAN in plaintext. This is
 * a deliberate lightweight defense against passive sniffing on the home network — the
 * real gate is the online attempt limit ([AvaCloneServer.MAX_ATTEMPTS]) plus the
 * five-minute window, matching the threat model of a code the user reads off a screen.
 */
internal object AvaCloneCrypto {
    const val HELLO_PREFIX = "AVA_CLONE"
    const val PROTOCOL_VERSION = "v1"
    const val AUTH_PREFIX = "AUTH"
    const val OK_PREFIX = "OK"
    const val ERR_PREFIX = "ERR"
    const val ERR_BAD_CODE = "bad_code"
    const val ERR_LOCKED = "locked"

    const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024
    private const val PBKDF2_ITERATIONS = 16_384
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    fun newPairingCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))

    fun newRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

    /** PBKDF2WithHmacSHA1 — available since API 10; SHA256 variant needs API 26. */
    private fun deriveBaseKey(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    class SessionKeys(code: String, salt: ByteArray) {
        private val baseKey = deriveBaseKey(code, salt)
        val authKey: ByteArray = hmacSha256(baseKey, "ava-clone-auth".toByteArray())
        val encKey: ByteArray = hmacSha256(baseKey, "ava-clone-enc".toByteArray())

        fun proof(challenge: ByteArray): ByteArray = hmacSha256(authKey, challenge)

        fun proofMatches(challenge: ByteArray, claimed: ByteArray): Boolean =
            MessageDigest.isEqual(proof(challenge), claimed)

        /** @return 12-byte IV prepended to the AES-GCM ciphertext. */
        fun encrypt(plain: ByteArray): ByteArray {
            val iv = newRandomBytes(GCM_IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(encKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            return iv + cipher.doFinal(plain)
        }

        fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
            require(ivAndCiphertext.size > GCM_IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(encKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, ivAndCiphertext, 0, GCM_IV_BYTES),
            )
            return cipher.doFinal(ivAndCiphertext, GCM_IV_BYTES, ivAndCiphertext.size - GCM_IV_BYTES)
        }
    }

    fun encodeB64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeB64(text: String): ByteArray? = try {
        Base64.decode(text, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Read one short protocol line byte-by-byte so the raw payload that may follow
     * stays in the stream (a buffered reader would swallow it).
     */
    @Throws(IOException::class)
    fun readLine(input: InputStream, maxLength: Int = 512): String {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("stream closed mid-line")
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > maxLength) throw IOException("protocol line too long")
        }
    }

    @Throws(IOException::class)
    fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

/** Server-side lifecycle of the one-shot clone window, observable by the send screen. */
sealed class AvaCloneServerState {
    /** Window open, nobody transferring yet. */
    data object Waiting : AvaCloneServerState()

    /** A receiver authenticated and the payload is being sent. */
    data object Transferring : AvaCloneServerState()

    /** Payload delivered — window closed. */
    data object Done : AvaCloneServerState()

    /** Too many wrong codes — window closed without transferring. */
    data object Locked : AvaCloneServerState()

    /** Window closed (expired, screen left, or bind failure before start). */
    data object Stopped : AvaCloneServerState()
}

/**
 * One-shot TCP server for the clone send screen. Binds an ephemeral port, serves the
 * payload to the first receiver that presents the right pairing code, then closes.
 * The caller owns the beacon advertisement and must clear it when [state] leaves
 * [AvaCloneServerState.Waiting]/[AvaCloneServerState.Transferring].
 */
class AvaCloneServer(
    private val payload: ByteArray,
    val pairingCode: String = AvaCloneCrypto.newPairingCode(),
) {
    companion object {
        private const val TAG = "AvaCloneServer"
        const val WINDOW_MS = 5 * 60_000L
        const val MAX_ATTEMPTS = 5
        private const val CLIENT_IO_TIMEOUT_MS = 20_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val wrongAttempts = AtomicInteger(0)
    private val salt = AvaCloneCrypto.newRandomBytes(16)
    private val keys = AvaCloneCrypto.SessionKeys(pairingCode, salt)

    @Volatile private var serverSocket: ServerSocket? = null

    private val _state = MutableStateFlow<AvaCloneServerState>(AvaCloneServerState.Waiting)
    val state: StateFlow<AvaCloneServerState> = _state.asStateFlow()

    /** @return bound TCP port, or `null` when binding failed. */
    fun start(): Int? {
        if (!started.compareAndSet(false, true)) return serverSocket?.localPort
        val socket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            Log.w(TAG, "bind failed: ${e.message}")
            _state.value = AvaCloneServerState.Stopped
            return null
        }
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        scope.launch {
            delay(WINDOW_MS)
            if (_state.value == AvaCloneServerState.Waiting) stop()
        }
        Log.d(TAG, "clone window open on port ${socket.localPort} (${payload.size} bytes)")
        return socket.localPort
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (_state.value == AvaCloneServerState.Waiting ||
            _state.value == AvaCloneServerState.Transferring
        ) {
            _state.value = AvaCloneServerState.Stopped
        }
        scope.cancel()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return // socket closed by stop()
            }
            val finished = try {
                client.soTimeout = CLIENT_IO_TIMEOUT_MS
                client.tcpNoDelay = true
                handleClient(client)
            } catch (e: Exception) {
                Log.w(TAG, "clone client failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            } finally {
                runCatching { client.close() }
            }
            if (finished) {
                _state.value = AvaCloneServerState.Done
                stop()
                return
            }
            if (wrongAttempts.get() >= MAX_ATTEMPTS) {
                _state.value = AvaCloneServerState.Locked
                stop()
                return
            }
            // Receiver authenticated but dropped mid-transfer — reopen for a retry.
            if (_state.value == AvaCloneServerState.Transferring) {
                _state.value = AvaCloneServerState.Waiting
            }
        }
    }

    /** @return true when the payload was fully delivered. */
    private fun handleClient(client: Socket): Boolean {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val challenge = AvaCloneCrypto.newRandomBytes(16)
        AvaCloneCrypto.writeLine(
            output,
            listOf(
                AvaCloneCrypto.HELLO_PREFIX,
                AvaCloneCrypto.PROTOCOL_VERSION,
                AvaCloneCrypto.encodeB64(salt),
                AvaCloneCrypto.encodeB64(challenge),
            ).joinToString("|"),
        )

        val authLine = AvaCloneCrypto.readLine(input)
        val proof = authLine
            .takeIf { it.startsWith("${AvaCloneCrypto.AUTH_PREFIX}|") }
            ?.substringAfter('|')
            ?.let { AvaCloneCrypto.decodeB64(it) }
        if (proof == null || !keys.proofMatches(challenge, proof)) {
            val attempts = wrongAttempts.incrementAndGet()
            val error = if (attempts >= MAX_ATTEMPTS) AvaCloneCrypto.ERR_LOCKED else AvaCloneCrypto.ERR_BAD_CODE
            runCatching { AvaCloneCrypto.writeLine(output, "${AvaCloneCrypto.ERR_PREFIX}|$error") }
            Log.w(TAG, "pairing code rejected (attempt $attempts/$MAX_ATTEMPTS)")
            return false
        }

        _state.value = AvaCloneServerState.Transferring
        val encrypted = keys.encrypt(payload)
        AvaCloneCrypto.writeLine(output, "${AvaCloneCrypto.OK_PREFIX}|${encrypted.size}")
        output.write(encrypted)
        output.flush()
        Log.d(TAG, "payload delivered (${encrypted.size} bytes encrypted)")
        return true
    }
}

/** Receiver got `ERR|bad_code` — the pairing code was wrong. */
class AvaCloneBadCodeException : IOException("pairing code rejected")

/** Receiver got `ERR|locked` — the sender closed the window after too many wrong codes. */
class AvaCloneLockedException : IOException("clone window locked")

object AvaCloneClient {
    private const val TAG = "AvaCloneClient"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val IO_TIMEOUT_MS = 30_000

    /**
     * Fetch and decrypt a clone payload from [host]:[port] using [pairingCode].
     * [onProgress] reports `(receivedBytes, totalBytes)` of the encrypted stream.
     */
    suspend fun fetch(
        host: String,
        port: Int,
        pairingCode: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = IO_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val hello = AvaCloneCrypto.readLine(input).split('|')
                if (hello.size < 4 ||
                    hello[0] != AvaCloneCrypto.HELLO_PREFIX ||
                    hello[1] != AvaCloneCrypto.PROTOCOL_VERSION
                ) {
                    throw IOException("unexpected hello: ${hello.firstOrNull()}")
                }
                val salt = AvaCloneCrypto.decodeB64(hello[2]) ?: throw IOException("bad salt")
                val challenge = AvaCloneCrypto.decodeB64(hello[3]) ?: throw IOException("bad challenge")

                val keys = AvaCloneCrypto.SessionKeys(pairingCode, salt)
                AvaCloneCrypto.writeLine(
                    output,
                    "${AvaCloneCrypto.AUTH_PREFIX}|${AvaCloneCrypto.encodeB64(keys.proof(challenge))}",
                )

                val response = AvaCloneCrypto.readLine(input).split('|')
                when {
                    response.getOrNull(0) == AvaCloneCrypto.OK_PREFIX -> Unit
                    response.getOrNull(1) == AvaCloneCrypto.ERR_LOCKED -> throw AvaCloneLockedException()
                    else -> throw AvaCloneBadCodeException()
                }
                val total = response.getOrNull(1)?.toLongOrNull()
                    ?: throw IOException("missing payload length")
                if (total <= 0 || total > AvaCloneCrypto.MAX_PAYLOAD_BYTES) {
                    throw IOException("implausible payload length: $total")
                }

                val encrypted = ByteArray(total.toInt())
                var received = 0
                while (received < encrypted.size) {
                    val read = input.read(encrypted, received, encrypted.size - received)
                    if (read < 0) throw IOException("stream closed at $received/$total")
                    received += read
                    onProgress(received.toLong(), total)
                }
                Log.d(TAG, "payload received ($total bytes encrypted)")
                keys.decrypt(encrypted)
            }
        }
    }
}
