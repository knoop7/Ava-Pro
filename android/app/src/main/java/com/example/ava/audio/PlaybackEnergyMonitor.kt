package com.example.ava.audio

import kotlin.math.exp

/**
 * Smoothed playback RMS for TTS visuals (edge glow, Quick Wake disc) and the
 * voiceprint playback gate.
 *
 * Feeders hand over a level when audio is written, which is ahead of the speaker
 * by the output buffer. Each sample is therefore queued with the time span it is
 * heard, and [currentLevel] returns the newest audible one. After its span a sample
 * is held for [HOLD_MS], then decays with [RELEASE_TAU_MS], and reads 0 once its
 * age passes [SILENT_AFTER_MS]: when feeding stops (end of reply, rebuffer, a gap between
 * sentences) the level falls on its own instead of freezing on the last buffer.
 *
 * [generation] moves on every [setEnabled] (true) and [reset]; a feeder that
 * captured an older generation is dropped, so a late write from a stopped stream
 * cannot light the next turn.
 */
object PlaybackEnergyMonitor {

    const val HOLD_MS = 80L
    const val RELEASE_TAU_MS = 160f
    const val SILENT_AFTER_MS = 400L

    /**
     * URL TTS (ExoPlayer tee): the tee sees a buffer when the audio sink takes it,
     * then it waits in the AudioTrack buffer. ExoPlayer sizes that buffer to at
     * least 250 ms for PCM, so a fixed delay is the simple, stable alignment.
     */
    const val URL_SINK_DELAY_MS = 250L

    private const val MAX_PENDING = 128

    private class Sample(val audibleAtNanos: Long, val endNanos: Long, val level: Float)

    /** Test seam; production uses the monotonic clock. */
    @Volatile
    internal var clock: () -> Long = System::nanoTime

    @Volatile
    private var enabled = false

    @Volatile
    private var currentGen = 0

    private val lock = Any()
    private var fedLevel = 0f
    private var nextFreeAtNanos = 0L
    private val pending = ArrayDeque<Sample>()
    private var audible: Sample? = null

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            enabled = value
            if (value) currentGen++ else clearLocked()
        }
    }

    fun isEnabled(): Boolean = enabled

    /** Current feeder generation; capture it when a stream starts and pass it to [onLevel]. */
    fun generation(): Int = currentGen

    /** True when a feeder holding [gen] may still publish. */
    fun accepts(gen: Int): Boolean = enabled && gen == currentGen

    /** URL path (ExoPlayer tee): fixed sink delay, no generation check. */
    fun onLevel(level: Float) = onLevel(level, URL_SINK_DELAY_MS, 0L, currentGen)

    /**
     * @param audibleInMs how long until this audio reaches the speaker.
     * @param durationMs length of the buffer: it is heard for that long (age counts
     *   from its end), and consecutive buffers stay back to back (a prefill burst does
     *   not collapse onto one instant). 0 = a point sample.
     * @param gen generation captured by the feeder; a stale one is dropped.
     */
    fun onLevel(level: Float, audibleInMs: Long, durationMs: Long, gen: Int) {
        synchronized(lock) {
            if (!enabled || gen != currentGen) return
            fedLevel = (fedLevel * 0.5f + level.coerceIn(0f, 1f) * 0.5f).coerceIn(0f, 1f)
            val at = audibleAtNanos(clock(), audibleInMs, nextFreeAtNanos, durationMs)
            nextFreeAtNanos = if (durationMs > 0L) at + durationMs * 1_000_000L else 0L
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(Sample(at, at + durationMs.coerceAtLeast(0L) * 1_000_000L, fedLevel))
        }
    }

    fun currentLevel(): Float {
        if (!enabled) return 0f
        synchronized(lock) {
            val now = clock()
            val s = advanceLocked(now) ?: return 0f
            return decayedLevel(s.level, ageMs(now, s.endNanos))
        }
    }

    /**
     * Age of the sample now audible, in ms after its span ended (0 while it is still
     * being heard); [Long.MAX_VALUE] when there is none.
     */
    fun sampleAgeMs(): Long {
        if (!enabled) return Long.MAX_VALUE
        synchronized(lock) {
            val now = clock()
            val s = advanceLocked(now) ?: return Long.MAX_VALUE
            return ageMs(now, s.endNanos)
        }
    }

    fun reset() {
        synchronized(lock) {
            currentGen++
            clearLocked()
        }
    }

    private fun advanceLocked(now: Long): Sample? {
        while (pending.isNotEmpty() && pending.first().audibleAtNanos <= now) {
            audible = pending.removeFirst()
        }
        return audible
    }

    private fun clearLocked() {
        fedLevel = 0f
        nextFreeAtNanos = 0L
        pending.clear()
        audible = null
    }

    private fun ageMs(nowNanos: Long, endNanos: Long): Long =
        ((nowNanos - endNanos) / 1_000_000L).coerceAtLeast(0L)

    /** Hold, then exponential release, then silence. Pure. */
    fun decayedLevel(level: Float, ageMs: Long): Float = when {
        ageMs <= HOLD_MS -> level
        ageMs >= SILENT_AFTER_MS -> 0f
        else -> level * exp(-(ageMs - HOLD_MS) / RELEASE_TAU_MS)
    }

    /** When a buffer becomes audible: [delayMs] from now, but never before the previous buffer ended. Pure. */
    fun audibleAtNanos(nowNanos: Long, delayMs: Long, nextFreeAtNanos: Long, durationMs: Long): Long {
        val at = nowNanos + delayMs.coerceAtLeast(0L) * 1_000_000L
        return if (durationMs > 0L && nextFreeAtNanos > at) nextFreeAtNanos else at
    }

    /**
     * PCM path: the chunk starting at [chunkStartBytes] is heard once the AudioTrack
     * playback head reaches it. [headBytes] is the head in bytes. Pure.
     */
    fun pcmAudibleInMs(chunkStartBytes: Long, headBytes: Long, bytesPerSecond: Int): Long {
        if (bytesPerSecond <= 0 || headBytes < 0L) return 0L
        val ahead = (chunkStartBytes - headBytes).coerceAtLeast(0L)
        return ahead * 1000L / bytesPerSecond
    }
}
