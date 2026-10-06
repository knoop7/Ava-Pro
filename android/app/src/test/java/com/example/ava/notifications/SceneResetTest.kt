package com.example.ava.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneResetTest {

    @Test
    fun matchesIdleResetAndEllipsis() {
        assertTrue(SceneReset.matchesToken("idle"))
        assertTrue(SceneReset.matchesToken("IDLE"))
        assertTrue(SceneReset.matchesToken(" reset "))
        assertTrue(SceneReset.matchesToken("..."))
    }

    @Test
    fun ignoresNormalScenes() {
        assertFalse(SceneReset.matchesToken(""))
        assertFalse(SceneReset.matchesToken("dishwasher"))
        assertFalse(SceneReset.matchesToken("Trockner ist fertig"))
        assertFalse(SceneReset.matchesToken("…"))
    }

    @Test
    fun hideCommandIsIdleOrLegacyReset() {
        assertTrue(SceneReset.isHideCommand(""))
        assertTrue(SceneReset.isHideCommand("idle"))
        assertTrue(SceneReset.isHideCommand("reset"))
        assertTrue(SceneReset.isHideCommand("..."))
        assertFalse(SceneReset.isHideCommand("dishwasher"))
        assertFalse(SceneReset.isHideCommand("Trockner ist fertig"))
    }

    @Test
    fun selectOptionsStartWithIdleAndDropHideTitles() {
        assertEquals(
            listOf("idle", "Doorbell", "Dryer is finished"),
            SceneReset.selectOptions(listOf("...", "Doorbell", "idle", "Dryer is finished", "reset")),
        )
    }
}
