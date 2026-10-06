package com.example.ava.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FabHaInjectTest {
    @Test fun notReadyUntilOldWindowSettled() {
        assertFalse(FabHaInject.canInsert(oldWindowSettled = false, sttOpen = true, processing = false))
    }

    @Test fun notReadyUntilSttOpen() {
        assertFalse(FabHaInject.canInsert(oldWindowSettled = true, sttOpen = false, processing = false))
    }

    @Test fun notReadyWhileProcessing() {
        assertFalse(FabHaInject.canInsert(oldWindowSettled = true, sttOpen = true, processing = true))
    }

    @Test fun readyInOrderWhenIdle() {
        assertTrue(FabHaInject.canInsert(oldWindowSettled = true, sttOpen = true, processing = false))
    }

    @Test fun pastCapInsertsEvenWhileProcessing() {
        assertTrue(
            FabHaInject.canInsert(
                oldWindowSettled = false,
                sttOpen = false,
                processing = true,
                pastHaCap = true,
            ),
        )
    }

    @Test fun retryWhenPastCapAndMissed() {
        assertTrue(FabHaInject.shouldRetry(pastHaCap = true, confirmed = false, hasText = true))
        assertFalse(FabHaInject.shouldRetry(pastHaCap = true, confirmed = true, hasText = true))
        assertFalse(FabHaInject.shouldRetry(pastHaCap = false, confirmed = false, hasText = true))
        assertFalse(FabHaInject.shouldRetry(pastHaCap = true, confirmed = false, hasText = false))
    }

    @Test fun holdWindowWhileInjectArmed() {
        assertTrue(
            FabHaInject.shouldHoldWindow(
                injectArmed = true,
                injectDone = false,
                listening = true,
                processing = false,
            ),
        )
    }

    @Test fun holdWindowWhileInjectDone() {
        assertTrue(
            FabHaInject.shouldHoldWindow(
                injectArmed = false,
                injectDone = true,
                listening = false,
                processing = true,
            ),
        )
    }

    @Test fun idleWindowDoesNotHold() {
        assertFalse(
            FabHaInject.shouldHoldWindow(
                injectArmed = false,
                injectDone = false,
                listening = true,
                processing = false,
            ),
        )
    }

    @Test fun idleUnconfirmedIsStillFirstWindow() {
        assertFalse(
            FabHaInject.shouldHoldWindow(
                injectArmed = false,
                injectDone = false,
                listening = true,
                processing = false,
                confirmed = false,
            ),
        )
    }
}
