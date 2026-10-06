package com.example.ava.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordCatalogRevealTest {
    @Test
    fun revealsOnlyWhenScrollingDownNearBottom() {
        assertTrue(
            shouldRevealNextCatalogPage(
                scrollingDown = true,
                nearBottom = true,
                hasMore = true,
                elapsedSinceLastRevealMs = CATALOG_REVEAL_THROTTLE_MS,
            ),
        )
        assertFalse(
            shouldRevealNextCatalogPage(
                scrollingDown = false,
                nearBottom = true,
                hasMore = true,
                elapsedSinceLastRevealMs = CATALOG_REVEAL_THROTTLE_MS,
            ),
        )
        assertFalse(
            shouldRevealNextCatalogPage(
                scrollingDown = true,
                nearBottom = false,
                hasMore = true,
                elapsedSinceLastRevealMs = CATALOG_REVEAL_THROTTLE_MS,
            ),
        )
    }

    @Test
    fun throttleBlocksRapidReveals() {
        assertFalse(
            shouldRevealNextCatalogPage(
                scrollingDown = true,
                nearBottom = true,
                hasMore = true,
                elapsedSinceLastRevealMs = CATALOG_REVEAL_THROTTLE_MS - 1,
            ),
        )
        assertFalse(
            shouldRevealNextCatalogPage(
                scrollingDown = true,
                nearBottom = true,
                hasMore = false,
                elapsedSinceLastRevealMs = CATALOG_REVEAL_THROTTLE_MS,
            ),
        )
    }
}
