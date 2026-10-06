package com.example.ava.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseNotesFingerprintTest {
    @Test
    fun sameNewestRelease_sameFingerprint() {
        val cached = listOf(release("0.7.3", "2026-08-26T10:19:18Z", sha = "aa", size = 100))
        val probed = listOf(release("0.7.3", "2026-08-26T10:19:18Z", sha = "aa", size = 100))
        assertEquals(
            GitHubReleaseNotes.newestFingerprint(cached),
            GitHubReleaseNotes.newestFingerprint(probed),
        )
    }

    @Test
    fun newerPublish_changesFingerprint() {
        val cached = listOf(release("0.7.3", "2026-08-26T10:19:18Z", sha = "aa", size = 100))
        val probed = listOf(release("0.7.4", "2026-08-26T18:00:00Z", sha = "bb", size = 120))
        assertNotEquals(
            GitHubReleaseNotes.newestFingerprint(cached),
            GitHubReleaseNotes.newestFingerprint(probed),
        )
    }

    @Test
    fun sameTagRebuild_changesFingerprint() {
        val cached = listOf(release("0.7.3", "2026-08-26T10:19:18Z", sha = "aa", size = 100))
        val rebuilt = listOf(release("0.7.3", "2026-08-26T10:19:18Z", sha = "cc", size = 101))
        assertNotEquals(
            GitHubReleaseNotes.newestFingerprint(cached),
            GitHubReleaseNotes.newestFingerprint(rebuilt),
        )
    }

    @Test
    fun draftIsSkipped_forNewestFingerprint() {
        val withDraft = listOf(
            release("0.7.4", "2026-08-27T00:00:00Z", sha = "dd", size = 130, draft = true),
            release("0.7.3", "2026-08-26T10:19:18Z", sha = "aa", size = 100),
        )
        val withoutDraft = listOf(release("0.7.3", "2026-08-26T10:19:18Z", sha = "aa", size = 100))
        assertEquals(
            GitHubReleaseNotes.newestFingerprint(withDraft),
            GitHubReleaseNotes.newestFingerprint(withoutDraft),
        )
    }

    @Test
    fun emptyList_emptyFingerprint() {
        assertTrue(GitHubReleaseNotes.newestFingerprint(emptyList()).isEmpty())
    }

    private fun release(
        tag: String,
        publishedAt: String,
        sha: String,
        size: Long,
        draft: Boolean = false,
    ) = GitHubReleaseNotes.Release(
        tagName = tag,
        publishedAt = publishedAt,
        draft = draft,
        assets = listOf(
            GitHubReleaseNotes.Asset(
                name = "Ava-$tag-release.apk",
                browserDownloadUrl = "https://github.com/knoop7/Ava/releases/download/$tag/Ava-$tag-release.apk",
                digest = "sha256:$sha",
                size = size,
            ),
        ),
    )
}
