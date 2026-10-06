package com.example.ava.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaVinylExpandPacketTest {

    @Test
    fun buildThenParseOpen() {
        val packet = AvaSyncOffsetPeer.parseVinylExpand(
            AvaSyncOffsetPeer.buildVinylExpand("ava_peer", 1_700_000_000_000L, true),
        )
        assertEquals("ava_peer", packet?.fromId)
        assertEquals(1_700_000_000_000L, packet?.streamKey)
        assertEquals(true, packet?.expanded)
    }

    @Test
    fun buildThenParseClosed() {
        val packet = AvaSyncOffsetPeer.parseVinylExpand(
            AvaSyncOffsetPeer.buildVinylExpand("ava_peer", 1_700_000_000_000L, false),
        )
        assertEquals(false, packet?.expanded)
    }

    @Test
    fun missingFlagMeansOpen() {
        val packet = AvaSyncOffsetPeer.parseVinylExpand("AVA_VINYL_EXPAND|ava_peer|12")
        assertEquals(true, packet?.expanded)
    }

    @Test
    fun blankSenderIsRejected() {
        assertNull(AvaSyncOffsetPeer.parseVinylExpand("AVA_VINYL_EXPAND||12|1"))
    }

    @Test
    fun zeroStreamKeyIsRejected() {
        assertNull(AvaSyncOffsetPeer.parseVinylExpand("AVA_VINYL_EXPAND|ava_peer|0|1"))
    }

    @Test
    fun badFlagIsRejected() {
        assertNull(AvaSyncOffsetPeer.parseVinylExpand("AVA_VINYL_EXPAND|ava_peer|12|2"))
    }

    @Test
    fun unknownPrefixIsNotVinylExpand() {
        assertFalse(AvaSyncOffsetPeer.isVinylExpand("AVA_MEDIA_META|ava_peer|12|song"))
        assertTrue(AvaSyncOffsetPeer.isVinylExpand("AVA_VINYL_EXPAND|ava_peer|12|0"))
    }
}
