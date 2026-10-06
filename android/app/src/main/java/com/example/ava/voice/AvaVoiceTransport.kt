package com.example.ava.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

internal object AvaVoiceTransport {
    private const val TAG = "AvaVoiceTransport"
    private var controlSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var videoSocket: DatagramSocket? = null
    private val addressCache = ConcurrentHashMap<String, InetAddress>()

    private fun resolve(host: String): InetAddress =
        addressCache.getOrPut(host) { InetAddress.getByName(host) }

    suspend fun sendControl(host: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        sendControlSync(host, payload)
    }

    /** Called from the recorder IO thread only. */
    fun sendAudioSync(host: String, packet: ByteArray): Boolean = sendAudioSyncInternal(host, packet)

    private fun sendControlSync(host: String, payload: String): Boolean {
        return try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val socket = controlSocketSync()
            val datagram = DatagramPacket(
                bytes,
                bytes.size,
                resolve(host),
                AvaVoiceProtocol.PORT
            )
            socket.send(datagram)
            true
        } catch (e: Exception) {
            Log.w(TAG, "control send failed to $host: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** Called from the video capture thread only. */
    fun sendVideoSync(host: String, packet: ByteArray): Boolean = sendVideoSyncInternal(host, packet)

    private fun sendVideoSyncInternal(host: String, packet: ByteArray): Boolean {
        return try {
            val socket = videoSocketSync()
            val datagram = DatagramPacket(
                packet,
                packet.size,
                resolve(host),
                AvaVoiceProtocol.VIDEO_PORT
            )
            socket.send(datagram)
            true
        } catch (e: Exception) {
            Log.w(TAG, "video send failed to $host: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun sendAudioSyncInternal(host: String, packet: ByteArray): Boolean {
        return try {
            val socket = audioSocketSync()
            val datagram = DatagramPacket(
                packet,
                packet.size,
                resolve(host),
                AvaVoiceProtocol.AUDIO_PORT
            )
            socket.send(datagram)
            true
        } catch (e: Exception) {
            Log.w(TAG, "audio send failed to $host: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    @Synchronized
    private fun controlSocketSync(): DatagramSocket {
        return controlSocket?.takeIf { !it.isClosed } ?: DatagramSocket().also { controlSocket = it }
    }

    @Synchronized
    private fun audioSocketSync(): DatagramSocket {
        return audioSocket?.takeIf { !it.isClosed } ?: DatagramSocket().also { audioSocket = it }
    }

    @Synchronized
    private fun videoSocketSync(): DatagramSocket {
        return videoSocket?.takeIf { !it.isClosed } ?: DatagramSocket().also { videoSocket = it }
    }

    fun resetAddressCache() {
        addressCache.clear()
    }

    fun close() {
        try {
            controlSocket?.close()
        } catch (_: Exception) {
        }
        try {
            audioSocket?.close()
        } catch (_: Exception) {
        }
        try {
            videoSocket?.close()
        } catch (_: Exception) {
        }
        controlSocket = null
        audioSocket = null
        videoSocket = null
        // Peers may come back on a different IP (DHCP lease, interface switch); a cached
        // InetAddress would keep sending call media to the stale address.
        addressCache.clear()
    }
}
