package com.example.ava.localllm

import com.example.ava.massapi.MassSearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MassSearchPlanTest {
    @Test fun bareNameAlsoSearchesArtist() {
        val plan = MassSearchPlan.of("Queen")
        assertTrue(plan.types.contains("artist"))
        assertFalse(plan.artistOnly)
        assertEquals("liked songs", HaPlayGuard.unwrapMusicQuery("playlist Liked Songs"))
    }

    @Test fun titleAndArtistStaySeparate() {
        val plan = MassSearchPlan.of("Imagine", "John Lennon")
        assertEquals("imagine", plan.searchName)
        assertEquals("imagine", plan.title)
        assertEquals("john lennon", plan.artist)
        assertEquals(listOf("track", "album", "playlist"), plan.types)
        assertFalse(plan.artistOnly)
    }

    @Test fun artistOnlySearchesArtistType() {
        val field = MassSearchPlan.of("", "Queen")
        assertTrue(field.artistOnly)
        assertEquals("queen", field.searchName)
        assertEquals(listOf("artist"), field.types)
        assertEquals(listOf("track", "album"), field.fallbackTypes)

        val spoken = MassSearchPlan.of("周杰伦的歌")
        assertTrue(spoken.artistOnly)
        assertEquals("周杰伦", spoken.searchName)
        assertEquals("周杰伦", spoken.artist)
        assertEquals(listOf("artist"), spoken.types)
    }

    @Test fun playlistCueSearchesPlaylists() {
        val plan = MassSearchPlan.of("喜欢你歌单")
        assertEquals("喜欢你", plan.searchName)
        assertEquals(listOf("playlist"), plan.types)
        assertTrue(plan.includeLibraryPlaylists)
    }

    @Test fun unwrapsPlayFillerForASong() {
        assertEquals("晴天", HaPlayGuard.unwrapMusicQuery("放首晴天"))
        assertTrue(HaPlayGuard.titleMatches("放首晴天", "晴天", "周杰伦"))
        assertTrue(HaPlayGuard.titleMatches("喜欢你歌单", "喜欢你"))
        assertTrue(HaPlayGuard.titleMatches("play Queen", "Queen"))
        assertTrue(HaPlayGuard.titleMatches("晴田", "晴天"))
        assertFalse(HaPlayGuard.titleMatches("灯", "锁"))
    }

    @Test fun preferExactTrackThenUniqueArtist() {
        val plan = MassSearchPlan.of("Queen")
        val artist = MassSearchItem(uri = "spotify://artist/q", name = "Queen", mediaType = "artist")
        val track = MassSearchItem(
            uri = "spotify://track/1",
            name = "Bohemian Rhapsody",
            mediaType = "track",
            artist = "Queen",
        )
        val exact = MassSearchItem(uri = "spotify://track/2", name = "Queen", mediaType = "track")
        assertEquals(
            listOf(exact),
            MassSearchPlan.prefer(listOf(track, exact, artist), plan),
        )
        val artistPlan = MassSearchPlan.of("", "Queen")
        assertEquals(
            listOf(artist),
            MassSearchPlan.prefer(listOf(track, artist), artistPlan),
        )
    }
}
