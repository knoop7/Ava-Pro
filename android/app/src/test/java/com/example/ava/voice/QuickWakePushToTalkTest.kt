package com.example.ava.voice

import com.example.ava.voice.QuickWakePushToTalk.BeginAction
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickWakePushToTalkTest {
    @Test fun idleOpensAWake() {
        assertEquals(BeginAction.WAKE, QuickWakePushToTalk.decideBegin(false, false))
    }

    @Test fun liveListenLatches() {
        assertEquals(BeginAction.LATCH, QuickWakePushToTalk.decideBegin(true, true))
    }

    @Test fun replyDoesNotShutUp() {
        assertEquals(BeginAction.SKIP, QuickWakePushToTalk.decideBegin(true, false))
    }
}
