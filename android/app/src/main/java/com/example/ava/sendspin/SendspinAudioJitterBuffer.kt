package com.example.ava.sendspin

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

class SendspinAudioJitterBuffer(
    private val clockSync: SendspinTimeFilter,
    private val lowMemoryMode: Boolean = false,
    private val maxBufferBytes: Long = 4_000_000L,
) {

    data class Snapshot(
        val queuedChunks: Int,
        val bufferAheadMs: Long,
        val lateDrops: Long,
        val headServerUs: Long?
    )

    data class Chunk(
        val serverTimestampUs: Long,
        val data: ByteArray,
        val offset: Int = 0,
        val length: Int = data.size
    ) {
        /** Contiguous copy — use only for codecs that require a standalone buffer (FLAC/PCM). */
        fun copyPayload(): ByteArray =
            if (offset == 0 && length == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + length)
            }
    }

    companion object {
        // Safety net only; the primary cap is bytes, matching the advertised buffer_capacity.
        // Small-chunk codecs (Opus ~150B/chunk) legitimately queue tens of thousands of chunks.
        private const val NORMAL_MAX_BUFFER_CHUNKS = 40_000
        private const val LOW_MEMORY_MAX_BUFFER_CHUNKS = 20_000
    }

    private val maxBufferChunks =
        if (lowMemoryMode) LOW_MEMORY_MAX_BUFFER_CHUNKS else NORMAL_MAX_BUFFER_CHUNKS

    // (int, Comparator) exists on API 21; PriorityQueue(Comparator) is API 24+
    // and throws NoSuchMethodError on Android 5 (github.com/knoop7/Ava/issues/212).
    private val q = PriorityQueue<Chunk>(11, compareBy { it.serverTimestampUs })
    private val lateDropsCounter = AtomicLong(0L)
    private var queuedBytes = 0L

    fun clear() {
        synchronized(q) {
            q.clear()
            queuedBytes = 0L
        }
        lateDropsCounter.set(0L)
    }

    fun isEmpty(): Boolean = synchronized(q) { q.isEmpty() }

    fun peekFirst(): Chunk? = synchronized(q) { q.peek() }

    private fun pollLocked(): Chunk? {
        val chunk = q.poll() ?: return null
        queuedBytes -= chunk.length
        return chunk
    }

    fun trimTo(maxChunks: Int): Int {
        var dropped = 0
        synchronized(q) {
            while (q.size > maxChunks) {
                pollLocked()
                lateDropsCounter.incrementAndGet()
                dropped++
            }
        }
        return dropped
    }

    fun size(): Int = synchronized(q) { q.size }

    fun queuedByteCount(): Long = synchronized(q) { queuedBytes }

    fun lateDropCount(): Long = lateDropsCounter.get()

    fun offer(serverTsUs: Long, pcm: ByteArray) {
        offer(serverTsUs, pcm, 0, pcm.size)
    }

    /** Slice into an existing array — avoids a second copy on the WebSocket hot path. */
    fun offer(serverTsUs: Long, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || offset < 0 || offset + length > data.size) return
        synchronized(q) {
            while (q.size >= maxBufferChunks || (queuedBytes + length > maxBufferBytes && q.isNotEmpty())) {
                pollLocked()
                lateDropsCounter.incrementAndGet()
            }
            q.add(Chunk(serverTsUs, data, offset, length))
            queuedBytes += length
        }
    }

    fun snapshot(): Snapshot {
        val nowLocalUs = System.nanoTime() / 1000L
        val nowServerUs = clockSync.convertClientToServer(nowLocalUs)

        return synchronized(q) {
            val head = q.peek()?.serverTimestampUs
            val aheadMs = if (head != null) ((head - nowServerUs) / 1000L) else 0L
            Snapshot(
                queuedChunks = q.size,
                bufferAheadMs = aheadMs,
                lateDrops = lateDropsCounter.get(),
                headServerUs = head
            )
        }
    }

    fun dropUntilHeadAheadAtLeast(
        nowLocalUs: Long,
        minAheadMs: Long,
        maxDrops: Int = maxBufferChunks,
    ): Int {
        val nowServerUs = clockSync.convertClientToServer(nowLocalUs)
        var dropped = 0
        synchronized(q) {
            while (dropped < maxDrops) {
                val head = q.peek() ?: break
                val aheadMs = (head.serverTimestampUs - nowServerUs) / 1000L
                if (aheadMs >= minAheadMs) break
                pollLocked()
                lateDropsCounter.incrementAndGet()
                dropped++
            }
        }
        return dropped
    }

    fun dropWhileLate(nowLocalUs: Long, keepWithinUs: Long): Int {
        val nowServerUs = clockSync.convertClientToServer(nowLocalUs)
        var dropped = 0
        synchronized(q) {
            while (true) {
                val head = q.peek() ?: break
                val latenessUs = nowServerUs - head.serverTimestampUs
                if (latenessUs > keepWithinUs) {
                    pollLocked()
                    lateDropsCounter.incrementAndGet()
                    dropped++
                    continue
                }
                break
            }
        }
        return dropped
    }

    fun pollHead(): Chunk? = synchronized(q) { pollLocked() }

    /**
     * Returns the next chunk that is not too late. Does not remove chunks that are
     * still within the lateness window but scheduled in the future — those are
     * returned immediately for the playout loop to schedule.
     */
    fun pollPlayable(nowLocalUs: Long, lateDropUs: Long): Chunk? {
        val nowServerUs = clockSync.convertClientToServer(nowLocalUs)

        synchronized(q) {
            while (true) {
                val head = q.peek() ?: return null

                val latenessUs = nowServerUs - head.serverTimestampUs
                if (latenessUs > lateDropUs) {
                    pollLocked()
                    lateDropsCounter.incrementAndGet()
                    continue
                }

                return pollLocked()
            }
        }
    }
}
