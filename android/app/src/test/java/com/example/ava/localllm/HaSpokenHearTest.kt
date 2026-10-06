package com.example.ava.localllm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaSpokenHearTest {
    @Test fun hearsOneEditNamesAndRejectsShortGenerics() {
        assertTrue(HaSpokenHear.close("客厅灯", "客厅等"))
        assertTrue(HaSpokenHear.close("desk light", "desk lite"))
        assertTrue(HaSpokenHear.close("kitchen", "kichen"))
        assertFalse(HaSpokenHear.close("light", "lite"))
        assertFalse(HaSpokenHear.close("灯", "锁"))
        assertFalse(HaSpokenHear.close("on", "in"))
        assertFalse(HaSpokenHear.close("bedroom", "bathroom"))
        assertTrue(HaSpokenHear.meets("dream clok", "dream clock"))
        assertFalse(HaSpokenHear.meets("灯", "锁"))
    }
}
