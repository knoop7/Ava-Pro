package com.example.ava.server

import android.util.Log
import com.example.ava.server.noise.EspHomeNoiseResponder
import com.example.ava.server.noise.MacFailureException
import com.example.esphomeproto.MESSAGE_PARSERS
import com.example.esphomeproto.MESSAGE_TYPES
import com.example.esphomeproto.api.PingRequest
import com.google.protobuf.MessageLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ClientConnection(
    private val socket: Socket,
    private val noisePsk: ByteArray? = null,
    private val deviceName: String = "",
    private val macAddress: String = "",
) : AutoCloseable {
    private val isClosed = AtomicBoolean(false)
    private val sendMutex = Mutex()
    private val inputStream: InputStream = socket.getInputStream()
    private val outputStream: OutputStream = socket.getOutputStream()
    private val bufferedReader = BufferedInputStream(inputStream)
    @Volatile
    private var noise: EspHomeNoiseResponder? = null
    /** First decrypted API frame (HelloRequest). Don't send app traffic before that. */
    @Volatile
    private var inboundReady = false

    init {
        // Firmware sets TCP_NODELAY in init_common_(). Without it Nagle can hold
        // the tiny server-hello while HA is already sitting in HELLO.
        try {
            socket.tcpNoDelay = true
        } catch (_: Exception) {
        }
    }

    suspend fun completeHandshake() {
        if (noisePsk != null && noise == null) {
            completeNoiseHandshake()
        }
    }

    fun readMessages() = flow {
        while (true) {
            try {
                val message = readMessage()
                if (message != null) {
                    emit(message)
                }
            } catch (e: MacFailureException) {
                throw IOException("Noise transport MAC failure", e)
            } catch (e: Exception) {
                if (isClosed.get()) {
                    break
                }
                Log.e(TAG, "Error in read loop", e)
                throw e
            }
        }
    }.catch { e ->
        if (e !is IOException) {
            Log.e(TAG, "Non-IO error in readMessages", e)
            throw e
        }
        
        if (!isClosed.get()) {
            val afterHandshake = noise != null
            if (afterHandshake && e.message == "EOF reading indicator") {
                Log.w(
                    TAG,
                    "Peer closed after Noise handshake, before the next API frame. " +
                        "Home Assistant likely rejected our handshake reply (InvalidTag / wrong PSK). " +
                        "This core path does not pass expected_name / expected_mac.",
                )
            } else {
                Log.e(TAG, "IOException reading from socket: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Connection closed (expected)")
        }
    }

    private suspend fun completeNoiseHandshake() {
        val psk = noisePsk ?: return
        val hello = readNoiseFrame()
        Log.i(
            TAG,
            "Noise client hello ${hello.size} bytes; server hello name=$deviceName mac=$macAddress",
        )
        val responder = EspHomeNoiseResponder(psk, deviceName, macAddress)
        writeNoiseFrame(responder.serverHello(hello))
        val handshake = readNoiseFrame()
        try {
            val reply = responder.handshakeReply(handshake)
            Log.i(
                TAG,
                "Noise handshake reply ${reply.size} bytes (status=${reply.firstOrNull()?.toInt() ?: -1}, " +
                    "hello=${hello.size}, request=${handshake.size})",
            )
            writeNoiseFrame(reply)
            noise = responder
            Log.i(TAG, "ESPHome Noise handshake complete")
        } catch (e: MacFailureException) {
            writeNoiseFrame(EspHomeNoiseResponder.handshakeFailureFrame())
            throw IOException("Handshake MAC failure", e)
        } catch (e: IllegalArgumentException) {
            writeNoiseFrame(EspHomeNoiseResponder.handshakeFailureFrame(e.message ?: "Handshake error"))
            throw IOException("Handshake failure", e)
        }
    }

    private fun readMessage(): MessageLite? {
        var message: MessageLite?
        do {
            message = readMessageInternal()
        } while (message == null)
        return message
    }

    private fun readMessageInternal(): MessageLite? {
        val session = noise
        if (session != null) {
            val encrypted = readNoiseFrame()
            val (messageType, messageBytes) = try {
                session.decryptPacket(encrypted)
            } catch (e: MacFailureException) {
                throw IOException("Noise transport MAC failure", e)
            }
            inboundReady = true
            return parseMessage(messageType, messageBytes)
        }

        val indicator = bufferedReader.read()
        if (indicator == -1) {
            throw IOException("EOF reading indicator")
        }
        if (indicator == 0x01) {
            throw IOException("Encrypted client against plaintext API")
        }
        if (indicator != 0) {
            throw IOException("Bad indicator: $indicator")
        }

        
        // readVarUInt returns the full unsigned range, so anything above 2 GiB would make
        // ByteArray(length) throw NegativeArraySizeException after .toInt() wraps negative.
        // Bound it first: a peer sending a bogus length must drop the connection, not the app.
        val declaredLength = readVarUInt()
        if (declaredLength > MAX_MESSAGE_BYTES) {
            throw IOException("Message length $declaredLength exceeds limit $MAX_MESSAGE_BYTES")
        }
        val length = declaredLength.toInt()

        
        val messageType = readVarUInt().toInt()

        
        val messageBytes = ByteArray(length)
        readExact(messageBytes)
        return parseMessage(messageType, messageBytes)
    }

    private fun parseMessage(messageType: Int, messageBytes: ByteArray): MessageLite? {
        val parser = MESSAGE_PARSERS[messageType]
        if (parser == null) {
            Log.w(TAG, "Unknown message type: $messageType")
            return null
        }
        return parser.parseFrom(messageBytes) as MessageLite
    }

    private fun readVarUInt(): UInt {
        var result = 0u
        var shift = 0

        while (shift < 32) {
            val byteVal = bufferedReader.read()
            if (byteVal == -1) {
                throw IOException("EOF while reading varuint")
            }
            val unsignedByte = byteVal.toUInt()
            result = result or ((unsignedByte and 0x7Fu) shl shift)
            if (unsignedByte and 0x80u == 0u) {
                return result
            }
            shift += 7
        }

        throw IOException("VarUInt value too large")
    }

    private fun readNoiseFrame(): ByteArray {
        val indicator = bufferedReader.read()
        if (indicator == -1) {
            throw IOException("EOF reading indicator")
        }
        if (indicator != 0x01) {
            if (noise == null) {
                writeHandshakeRejectUnlocked("Bad indicator byte")
            }
            val reason = if (indicator == 0) {
                "Plaintext client against encrypted API"
            } else {
                "Bad noise indicator: $indicator"
            }
            throw IOException(reason)
        }
        val hi = bufferedReader.read()
        val lo = bufferedReader.read()
        if (hi == -1 || lo == -1) {
            throw IOException("EOF reading noise length")
        }
        val length = (hi shl 8) or lo
        val limit = if (noise == null) MAX_HANDSHAKE_BYTES else MAX_MESSAGE_BYTES.toInt()
        if (length > limit) {
            throw IOException("Noise frame length $length exceeds limit $limit")
        }
        if (length == 0) return ByteArray(0)
        val payload = ByteArray(length)
        readExact(payload)
        return payload
    }

    private fun readExact(into: ByteArray) {
        var bytesRead = 0
        while (bytesRead < into.size) {
            val read = bufferedReader.read(into, bytesRead, into.size - bytesRead)
            if (read == -1) {
                throw IOException("Connection closed while reading message")
            }
            bytesRead += read
        }
    }

    private suspend fun writeNoiseFrame(payload: ByteArray) {
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                writeNoiseBytesLocked(payload)
            }
        }
    }

    private fun writeNoiseBytesLocked(payload: ByteArray) {
        if (payload.size > EspHomeNoiseResponder.MAX_NOISE_FRAME) {
            Log.e(TAG, "Dropping Noise frame of ${payload.size} bytes (ESPHome max 65535)")
            return
        }
        outputStream.write(0x01)
        outputStream.write((payload.size ushr 8) and 0xff)
        outputStream.write(payload.size and 0xff)
        if (payload.isNotEmpty()) outputStream.write(payload)
        outputStream.flush()
    }

    private fun writeHandshakeRejectUnlocked(reason: String) {
        try {
            writeNoiseBytesLocked(EspHomeNoiseResponder.handshakeFailureFrame(reason))
        } catch (_: IOException) {
        }
    }

    suspend fun sendMessage(message: MessageLite) {
        sendMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    val messageType = MESSAGE_TYPES[message::class.java]
                        ?: throw IllegalArgumentException("Unknown message type: ${message::class}")

                    val messageBytes = message.toByteArray()
                    val session = noise
                    if (noisePsk != null && session == null) {
                        Log.w(
                            TAG,
                            "Dropping message type $messageType: Noise handshake is not finished",
                        )
                        return@withContext
                    }
                    if (session != null && !inboundReady && message !is PingRequest) {
                        Log.w(
                            TAG,
                            "Dropping message type $messageType: waiting for first HA API frame",
                        )
                        return@withContext
                    }
                    if (session != null) {
                        if (messageBytes.size > EspHomeNoiseResponder.MAX_PLAINTEXT) {
                            Log.e(
                                TAG,
                                "Dropping message type $messageType: ${messageBytes.size} bytes exceeds Noise uint16 limit",
                            )
                            return@withContext
                        }
                        writeNoiseBytesLocked(session.encryptPacket(messageType, messageBytes))
                        return@withContext
                    }

                    
                    val byteStream = ByteArrayOutputStream()

                    
                    byteStream.write(0)

                    
                    writeVarUInt(byteStream, messageBytes.size)

                    
                    writeVarUInt(byteStream, messageType)

                    
                    byteStream.write(messageBytes)

                    
                    outputStream.write(byteStream.toByteArray())
                    outputStream.flush()
                }
            } catch (e: IOException) {
                if (!isClosed.get())
                    Log.e(TAG, "Error writing to socket", e)
            }
        }
    }

    private fun writeVarUInt(stream: OutputStream, value: Int) {
        var v = value
        while ((v and 0xFFFFFF80.toInt()) != 0) {
            stream.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        stream.write(v and 0x7F)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                bufferedReader.close()
            } catch (e: IOException) { }
            try {
                outputStream.close()
            } catch (e: IOException) { }
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket", e)
            }
        }
    }
    
    fun getRemoteAddress(): String? {
        return try {
            val addr = socket.inetAddress
            addr?.hostAddress?.replace("/", "")
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val TAG = "ClientConnection"
        /** 16 MiB — generous ceiling for one ESPHome API frame; real ones are far smaller. */
        private const val MAX_MESSAGE_BYTES = 16_777_216u
        /** Handshake frames are tiny (empty hello + ~48-byte Noise messages). Firmware cap is 128. */
        private const val MAX_HANDSHAKE_BYTES = 128
    }
}
