package com.example.ava.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class AvaVoiceMessageBoardEntry(
    val sessionId: Int,
    val sampleRate: Int,
    val totalBytes: Long,
    val durationMs: Long,
    val frames: List<ByteArray>
)

object AvaVoiceMessageBoard {
    private const val TAG = "AvaVoiceMessageBoard"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<Int, AvaVoiceMessageBoardEntry>()
    private val players = ConcurrentHashMap<Int, AvaVoiceStreamPlayer>()

    fun put(entry: AvaVoiceMessageBoardEntry) {
        entries.keys
            .filter { it != entry.sessionId }
            .forEach { remove(it) }
        entries[entry.sessionId] = entry
    }

    fun get(sessionId: Int): AvaVoiceMessageBoardEntry? = entries[sessionId]

    fun remove(sessionId: Int) {
        stop(sessionId)
        entries.remove(sessionId)
    }

    fun clear() {
        players.keys.forEach { stop(it) }
        entries.clear()
    }

    fun replay(sessionId: Int) {
        val entry = entries[sessionId] ?: return
        scope.launch {
            stop(sessionId)
            val player = AvaVoiceStreamPlayer(sessionId, entry.sampleRate)
            players[sessionId] = player
            try {
                AvaVoiceInboundBus.resetPlaybackProgress()
                if (!player.start()) return@launch
                player.markEnded(entry.totalBytes, entry.durationMs)
                entry.frames.forEach { frame -> player.writePcm(frame) }
                player.awaitDrain()
            } catch (e: Exception) {
                Log.w(TAG, "replay failed session=$sessionId: ${e.message}")
            } finally {
                player.release()
                players.remove(sessionId, player)
            }
        }
    }

    fun stop(sessionId: Int) {
        players.remove(sessionId)?.release()
    }
}
