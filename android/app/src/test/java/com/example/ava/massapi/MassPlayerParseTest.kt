package com.example.ava.massapi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MassPlayerParseTest {

    @Test
    fun jsonNullSyncedToIsAbsent() {
        val player = MassApiClient.parsePlayer(
            JSONObject(
                """{"player_id":"ava-1","display_name":"A","synced_to":null,"active_source":null}""",
            ),
        )!!
        assertNull(player.syncedTo)
        assertNull(player.activeSource)
    }

    @Test
    fun literalNullStringSyncedToIsAbsent() {
        val player = MassApiClient.parsePlayer(
            JSONObject(
                """{"player_id":"ava-1","synced_to":"null","active_source":"undefined"}""",
            ),
        )!!
        assertNull(player.syncedTo)
        assertNull(player.activeSource)
    }

    @Test
    fun realSyncedToIsKept() {
        val player = MassApiClient.parsePlayer(
            JSONObject(
                """{"player_id":"ava-1","synced_to":"ava-2","active_source":"ava-2"}""",
            ),
        )!!
        assertEquals("ava-2", player.syncedTo)
        assertEquals("ava-2", player.activeSource)
    }
}
