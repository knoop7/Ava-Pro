package com.example.ava.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseCoverArtExtractTest {

    @Test
    fun `html img wins when first - real 0_6_8 shape`() {
        val body = """
            <img width="580" height="400" alt="Ava Pro 0 6 8" src="https://github.com/user-attachments/assets/e88e4524" />

            # Ava 0.6.8 - protocol +
            ![later](https://github.com/user-attachments/assets/f14dd335)
        """.trimIndent()
        assertEquals(
            "https://github.com/user-attachments/assets/e88e4524",
            ReleaseCoverArt.extractCoverUrl(body),
        )
    }

    @Test
    fun `markdown image wins when it appears first`() {
        val body = "![cover](https://example.com/a.png)\n<img src=\"https://example.com/b.png\">"
        assertEquals("https://example.com/a.png", ReleaseCoverArt.extractCoverUrl(body))
    }

    @Test
    fun `markdown only`() {
        assertEquals(
            "https://example.com/x.png",
            ReleaseCoverArt.extractCoverUrl("hello\n![x](https://example.com/x.png)"),
        )
    }

    @Test
    fun `no image or blank returns null`() {
        assertNull(ReleaseCoverArt.extractCoverUrl("plain notes, [link](https://example.com) only"))
        assertNull(ReleaseCoverArt.extractCoverUrl(""))
        assertNull(ReleaseCoverArt.extractCoverUrl(null))
    }

    @Test
    fun `relative src rejected`() {
        assertNull(ReleaseCoverArt.extractCoverUrl("<img src=\"/local/path.png\">"))
    }
}
