package com.example.ava.utils

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaKeyDispatcherTest {

    @Test
    fun pauseIsPauseNotToggle() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, MediaKeyDispatcher.keyCodeFor("pause"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, MediaKeyDispatcher.keyCodeFor(" PAUSE "))
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            MediaKeyDispatcher.keyCodeFor("play_pause"),
        )
    }

    @Test
    fun knownCommandsMapToMediaKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, MediaKeyDispatcher.keyCodeFor("play"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, MediaKeyDispatcher.keyCodeFor("stop"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaKeyDispatcher.keyCodeFor("next"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaKeyDispatcher.keyCodeFor("previous"))
    }

    @Test
    fun unknownCommandsAreIgnored() {
        assertNull(MediaKeyDispatcher.keyCodeFor(""))
        assertNull(MediaKeyDispatcher.keyCodeFor("home"))
        assertNull(MediaKeyDispatcher.keyCodeFor("playpause"))
        assertNull(MediaKeyDispatcher.keyCodeFor("prev"))
    }
}
