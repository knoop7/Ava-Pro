package com.example.ava.localllm.remote

import android.util.Log
import com.example.ava.localllm.LocalReplySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

/**
 * Speech queue between a streaming model turn and the speaker.
 *
 * Deltas are only counted ([push]); nothing is cut into sentences. A partial
 * reply fed into TTS, captions, and history went wrong more often than it
 * saved time. The turn hands over whole phrases with [say] — the final
 * answer, or the host's failure line — each synthesised once and played in
 * order by [next].
 */
internal class RemoteAiSpeechStream(
    private val scope: CoroutineScope,
    private val synthesize: suspend (String) -> String?,
) : LocalReplySource {
    private val queue = Channel<Deferred<LocalReplySource.Segment>>(Channel.UNLIMITED)
    private val transcript = StringBuilder()
    private val lock = Any()
    @Volatile private var closed = false

    /** Phrases handed out so far; grows as [next] is consumed. */
    override val spoken: String
        get() = synchronized(lock) { transcript.toString() }

    /** Text the model has produced in this turn, for logs. */
    @Volatile var producedChars: Int = 0
        private set

    /** Model delta; safe to call from an OkHttp thread. Counted, never spoken on its own. */
    fun push(delta: String) {
        if (closed || delta.isEmpty()) return
        producedChars += delta.length
    }

    /** A whole phrase, spoken after whatever is already queued. */
    fun say(text: String) {
        if (closed) return
        val clean = TtsMdFilter.apply(text)
        if (clean.isBlank()) return
        val job = scope.async {
            val url = try {
                synthesize(clean)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tts failed: ${e.message}")
                null
            }
            LocalReplySource.Segment(clean, url)
        }
        if (queue.trySend(job).isFailure) job.cancel()
    }

    /** No more phrases will come; [next] ends once the queue drains. */
    fun close() {
        if (closed) return
        closed = true
        queue.close()
    }

    override suspend fun next(): LocalReplySource.Segment? {
        val deferred = queue.receiveCatching().getOrNull() ?: return null
        val segment = deferred.await()
        synchronized(lock) {
            if (transcript.isNotEmpty()) transcript.append(' ')
            transcript.append(segment.text)
        }
        return segment
    }

    companion object {
        private const val TAG = "RemoteAiSpeechStream"
    }
}
