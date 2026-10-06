package com.example.ava.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SendspinAudioJitterBufferTest {
    @Test
    fun constructsAndOrdersByServerTimestamp() {
        val buffer = SendspinAudioJitterBuffer(SendspinTimeFilter())
        buffer.offer(3_000L, byteArrayOf(3))
        buffer.offer(1_000L, byteArrayOf(1))
        buffer.offer(2_000L, byteArrayOf(2))

        assertEquals(3, buffer.size())
        assertEquals(1_000L, buffer.pollHead()?.serverTimestampUs)
        assertEquals(2_000L, buffer.pollHead()?.serverTimestampUs)
        assertEquals(3_000L, buffer.pollHead()?.serverTimestampUs)
        assertEquals(0, buffer.size())
    }

    @Test
    fun constructsInLowMemoryMode() {
        val buffer = SendspinAudioJitterBuffer(
            SendspinTimeFilter(),
            lowMemoryMode = true,
            maxBufferBytes = 64_000L,
        )
        buffer.offer(10L, byteArrayOf(1, 2, 3))
        assertEquals(1, buffer.size())
        assertNotNull(buffer.peekFirst())
    }
}
