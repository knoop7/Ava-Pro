package com.example.ava.voiceprint

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Circular PCM16 mono buffer for wake-window extraction.
 * Writes are short-lock; extract copies outside the lock so the mic loop is not blocked.
 */
class VoicePrintRingBuffer(
    val sampleRate: Int = 16_000,
    capacitySeconds: Float = 2.5f,
) {
    private val capacitySamples = (sampleRate * capacitySeconds).toInt().coerceAtLeast(sampleRate)
    private val buffer = ShortArray(capacitySamples)

    @Volatile
    private var writePos = 0

    @Volatile
    private var totalWritten = 0L

    @Volatile
    private var wakeMarkSample = -1L

    private data class ExtractPlan(
        val start: Long,
        val length: Int,
        val writePosSnapshot: Int,
        val totalWrittenSnapshot: Long,
    )

    fun write(pcm: ByteBuffer) {
        val input = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < 2) return

        synchronized(this) {
            while (input.remaining() >= 2) {
                buffer[writePos] = input.short
                writePos = (writePos + 1) % capacitySamples
                totalWritten++
            }
        }
    }

    fun markWake(leadMs: Int = 350) {
        val leadSamples = leadMs.toLong() * sampleRate / 1000L
        wakeMarkSample = (totalWritten - leadSamples).coerceAtLeast(0L)
    }

    /** Pin this sample index before async voiceprint work — later wakes must not move it. */
    fun currentWakeMark(): Long = wakeMarkSample

    fun clearWakeMark() {
        wakeMarkSample = -1L
    }

    fun samplesAvailableAfterMark(afterMs: Int): Boolean =
        samplesAvailableAfterMark(currentWakeMark(), afterMs)

    fun samplesAvailableAfterMark(mark: Long, afterMs: Int): Boolean {
        if (mark < 0) return false
        val end = mark + afterMs.toLong() * sampleRate / 1000L
        return totalWritten >= end
    }

    fun extractWindow(beforeMs: Int, afterMs: Int): ShortArray? =
        extractWindow(currentWakeMark(), beforeMs, afterMs)

    fun extractWindow(mark: Long, beforeMs: Int, afterMs: Int): ShortArray? {
        val plan = synchronized(this) {
            if (mark < 0) return null
            var start = mark - beforeMs.toLong() * sampleRate / 1000L
            var end = mark + afterMs.toLong() * sampleRate / 1000L
            if (totalWritten <= mark) return null
            if (totalWritten < end) {
                end = totalWritten
            }
            val oldestRetained = (totalWritten - capacitySamples).coerceAtLeast(0L)
            if (start < oldestRetained) start = oldestRetained
            if (start < 0) start = 0
            val length = (end - start).toInt()
            val minSamples = sampleRate * 550 / 1000
            if (length < minSamples) return null
            ExtractPlan(
                start = start,
                length = length,
                writePosSnapshot = writePos,
                totalWrittenSnapshot = totalWritten,
            )
        }

        val out = ShortArray(plan.length)
        for (i in 0 until plan.length) {
            out[i] = sampleAt(
                absoluteIndex = plan.start + i,
                writePosSnapshot = plan.writePosSnapshot,
                totalWrittenSnapshot = plan.totalWrittenSnapshot,
            )
        }
        return out
    }

    /** Most recent [durationMs] of buffered audio — for guided "tap then speak" enrollment. */
    fun extractTrailingWindow(durationMs: Int): ShortArray? {
        val plan = synchronized(this) {
            val length = (durationMs.toLong() * sampleRate / 1000L).toInt()
            val minSamples = sampleRate * 550 / 1000
            if (length < minSamples) return null
            val start = (totalWritten - length).coerceAtLeast(0L)
            val actualLength = (totalWritten - start).toInt()
            if (actualLength < minSamples) return null
            ExtractPlan(
                start = start,
                length = actualLength,
                writePosSnapshot = writePos,
                totalWrittenSnapshot = totalWritten,
            )
        }

        val out = ShortArray(plan.length)
        for (i in 0 until plan.length) {
            out[i] = sampleAt(
                absoluteIndex = plan.start + i,
                writePosSnapshot = plan.writePosSnapshot,
                totalWrittenSnapshot = plan.totalWrittenSnapshot,
            )
        }
        return out
    }

    fun debugSnapshot(): String =
        "total=$totalWritten mark=$wakeMarkSample cap=$capacitySamples"

    private fun sampleAt(
        absoluteIndex: Long,
        writePosSnapshot: Int,
        totalWrittenSnapshot: Long,
    ): Short {
        val offsetFromLatest = totalWrittenSnapshot - 1 - absoluteIndex
        val ringIndex =
            ((writePosSnapshot - 1 - offsetFromLatest) % capacitySamples + capacitySamples) % capacitySamples
        return buffer[ringIndex.toInt()]
    }
}
