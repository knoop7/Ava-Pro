package com.example.ava.utils

/**
 * Locate a complete JPEG in a byte buffer without slicing on an EXIF thumbnail.
 *
 * Camera JPEGs often embed a thumbnail that is itself `FFD8…FFD9`. A naive scan
 * for the first EOI cuts the main image in half. This parser skips APPn bodies
 * by length, then takes the EOI that follows SOS.
 *
 * [Cursor] lets the MJPEG reader resume after SOS instead of re-scanning the
 * entropy-coded body from the SOI on every socket chunk.
 */
object HaCameraJpeg {
    class Cursor {
        var pos: Int = 0
        var soi: Int = -1
        var inScan: Boolean = false

        fun reset() {
            pos = 0
            soi = -1
            inScan = false
        }

        fun compact(consumed: Int) {
            if (consumed <= 0) return
            pos = (pos - consumed).coerceAtLeast(0)
            soi = if (soi >= consumed) soi - consumed else -1
            if (soi < 0) inScan = false
        }
    }

    fun findCompleteJpeg(data: ByteArray, size: Int, start: Int = 0): IntRange? {
        val cursor = Cursor().apply { pos = start.coerceAtLeast(0) }
        return findCompleteJpeg(data, size, cursor)
    }

    fun findCompleteJpeg(data: ByteArray, size: Int, cursor: Cursor): IntRange? {
        val limit = size.coerceAtMost(data.size)
        if (limit < 4) return null
        val last = limit - 1

        if (cursor.soi < 0) {
            var index = cursor.pos.coerceAtLeast(0)
            while (index < last) {
                if (data[index] == SOI0 && data[index + 1] == SOI1) {
                    cursor.soi = index
                    cursor.pos = index + 2
                    cursor.inScan = false
                    break
                }
                index++
            }
            if (cursor.soi < 0) {
                cursor.pos = index
                return null
            }
        }

        if (!cursor.inScan) {
            var pos = cursor.pos
            markerLoop@ while (pos < last) {
                if (data[pos] != MARKER) {
                    pos++
                    continue
                }
                val markerStart = pos
                while (pos < limit && data[pos] == MARKER) pos++
                if (pos >= limit) {
                    cursor.pos = markerStart
                    return null
                }
                val marker = data[pos].toInt() and 0xFF
                pos++
                when (marker) {
                    EOI -> {
                        val range = cursor.soi until pos
                        cursor.pos = pos
                        cursor.soi = -1
                        cursor.inScan = false
                        return range
                    }
                    SOS -> {
                        if (pos + 1 >= limit) {
                            cursor.pos = markerStart
                            return null
                        }
                        val length = ((data[pos].toInt() and 0xFF) shl 8) or
                            (data[pos + 1].toInt() and 0xFF)
                        if (length < 2) {
                            cursor.pos = pos
                            return null
                        }
                        if (pos + length > limit) {
                            cursor.pos = markerStart
                            return null
                        }
                        pos += length
                        cursor.pos = pos
                        cursor.inScan = true
                        break@markerLoop
                    }
                    in RST0..RST7, TEM, 0x00 -> {
                        cursor.pos = pos
                    }
                    else -> {
                        if (pos + 1 >= limit) {
                            cursor.pos = markerStart
                            return null
                        }
                        val length = ((data[pos].toInt() and 0xFF) shl 8) or
                            (data[pos + 1].toInt() and 0xFF)
                        if (length < 2) {
                            cursor.pos = pos
                            return null
                        }
                        if (pos + length > limit) {
                            cursor.pos = markerStart
                            return null
                        }
                        pos += length
                        cursor.pos = pos
                    }
                }
            }
            if (!cursor.inScan) {
                cursor.pos = pos
                return null
            }
        }

        var pos = cursor.pos
        while (pos < last) {
            if (data[pos] == MARKER) {
                val next = data[pos + 1].toInt() and 0xFF
                when (next) {
                    0x00, in RST0..RST7 -> pos += 2
                    EOI -> {
                        val range = cursor.soi until (pos + 2)
                        cursor.pos = pos + 2
                        cursor.soi = -1
                        cursor.inScan = false
                        return range
                    }
                    else -> pos++
                }
            } else {
                pos++
            }
        }
        cursor.pos = pos
        return null
    }

    private const val MARKER: Byte = 0xFF.toByte()
    private const val SOI0: Byte = 0xFF.toByte()
    private const val SOI1: Byte = 0xD8.toByte()
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val TEM = 0x01
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
}
