package com.example.ava.localllm

/**
 * A reply Ava speaks in order: each [Segment] is one synthesised phrase.
 * [next] suspends until the following phrase is ready and returns null
 * once the reply is complete. [spoken] is everything handed out so far, for
 * captions and the TTS entity.
 */
interface LocalReplySource {
    data class Segment(val text: String, val url: String?)

    suspend fun next(): Segment?

    val spoken: String
}
