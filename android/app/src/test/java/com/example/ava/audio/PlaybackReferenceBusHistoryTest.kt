package com.example.ava.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The TTS-echo wake screen re-scores the trailing reference history — the audio this
 * device just played — to reject self-wakes that AEC, VAD, and the text screen cannot
 * (near-words like "hey Travis" on a hey_jarvis model). These tests pin the history's
 * contract: it returns exactly what [PlaybackReferenceBus.read] handed the AEC, and it
 * fails open (null) whenever the evidence would be incomplete.
 */
class PlaybackReferenceBusHistoryTest {

    @Before
    fun setUp() {
        PlaybackReferenceBus.bulkDelayMs = PlaybackReferenceBus.SPEEX_BULK_DELAY_MS
        PlaybackReferenceBus.acquire()
        PlaybackReferenceBus.resetReader()
    }

    @After
    fun tearDown() {
        PlaybackReferenceBus.resetReader()
        repeat(8) { PlaybackReferenceBus.release() }
    }

    private fun writePcm(writer: PlaybackReferenceBus.Writer, value: Short, samples: Int) {
        val buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { buf.putShort(value) }
        buf.flip()
        writer.write(buf)
    }

    @Test
    fun noHistoryBeforeAnyRead() {
        // No capture loop pulling the bus (no software AEC) → no evidence → null.
        assertNull(PlaybackReferenceBus.recentReferencePcm(500))
    }

    @Test
    fun requestLongerThanConsumedFailsOpen() {
        val writer = PlaybackReferenceBus.createWriter(PlaybackReferenceBus.SAMPLE_RATE, 1)
        writePcm(writer, 1000, 160)
        PlaybackReferenceBus.read(ShortArray(160))
        // A half-empty window must not be judged — the caller falls back to normal gates.
        assertNull(PlaybackReferenceBus.recentReferencePcm(3000))
    }

    @Test
    fun historyReturnsWhatTheAecConsumed() {
        val writer = PlaybackReferenceBus.createWriter(PlaybackReferenceBus.SAMPLE_RATE, 1)
        writePcm(writer, 1000, 16_000)
        // Consume past the writer's bulk-delay anchor plus the full second of PCM.
        val frame = ShortArray(160)
        repeat(110) { PlaybackReferenceBus.read(frame) }

        val history = PlaybackReferenceBus.recentReferencePcm(500)
        assertNotNull(history)
        assertEquals(PlaybackReferenceBus.SAMPLE_RATE / 2, history!!.size)
        // read() applies the −6 dB mix headroom; history must store the same values.
        assertEquals(500, history.max().toInt())
        assertEquals(500, history.min().toInt())
    }

    @Test
    fun resetReaderDropsHistory() {
        val writer = PlaybackReferenceBus.createWriter(PlaybackReferenceBus.SAMPLE_RATE, 1)
        writePcm(writer, 1000, 16_000)
        val frame = ShortArray(160)
        repeat(110) { PlaybackReferenceBus.read(frame) }
        assertNotNull(PlaybackReferenceBus.recentReferencePcm(500))

        PlaybackReferenceBus.resetReader()
        assertNull(PlaybackReferenceBus.recentReferencePcm(500))
    }
}
