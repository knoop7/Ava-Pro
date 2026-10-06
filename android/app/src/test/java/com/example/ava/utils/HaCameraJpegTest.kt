package com.example.ava.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaCameraJpegTest {

    @Test
    fun skipsExifThumbnailEoi() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08,
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(),
            0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x00, 0x01,
            0xFF.toByte(), 0xD9.toByte(),
        )
        assertEquals(0 until jpeg.size, HaCameraJpeg.findCompleteJpeg(jpeg, jpeg.size))
    }

    @Test
    fun incompleteScanReturnsNull() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x00, 0x01,
        )
        assertNull(HaCameraJpeg.findCompleteJpeg(jpeg, jpeg.size))
    }

    @Test
    fun stuffedFf00InScanIsNotEoi() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0xFF.toByte(), 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
        assertEquals(0 until jpeg.size, HaCameraJpeg.findCompleteJpeg(jpeg, jpeg.size))
    }

    @Test
    fun cursorResumesInsideSosScan() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x00, 0x01, 0x02, 0x03, 0x04,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val cursor = HaCameraJpeg.Cursor()
        assertNull(HaCameraJpeg.findCompleteJpeg(jpeg, 8, cursor))
        assertEquals(0 until jpeg.size, HaCameraJpeg.findCompleteJpeg(jpeg, jpeg.size, cursor))
    }

    @Test
    fun cursorFindsLatestOfTwoJpegsThenCompacts() {
        val one = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val two = one + one
        val cursor = HaCameraJpeg.Cursor()
        val first = HaCameraJpeg.findCompleteJpeg(two, two.size, cursor)
        assertEquals(0 until one.size, first)
        val second = HaCameraJpeg.findCompleteJpeg(two, two.size, cursor)
        assertEquals(one.size until two.size, second)
        cursor.compact(one.size)
        assertEquals(one.size, cursor.pos)
    }

    @Test
    fun extractResetsCursorSoNextJpegInLeftoverIsFound() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x01, 0x02, 0x03, 0x04,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val thirdStart = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
        )
        val buffer = jpeg + jpeg + thirdStart
        val cursor = HaCameraJpeg.Cursor()
        var lastSpan: IntRange? = null
        while (true) {
            val span = HaCameraJpeg.findCompleteJpeg(buffer, buffer.size, cursor) ?: break
            lastSpan = span
        }
        val consumed = lastSpan!!.last + 1
        val leftover = buffer.copyOfRange(consumed, buffer.size)
        cursor.reset()
        val thirdRest = byteArrayOf(0x05, 0xFF.toByte(), 0xD9.toByte())
        val next = leftover + thirdRest
        assertEquals(0 until next.size, HaCameraJpeg.findCompleteJpeg(next, next.size, cursor))
    }
}
