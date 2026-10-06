package com.example.ava.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Healing of device names polluted by Android's mDNS conflict rename (" (2)")
 * that older builds persisted as permanent identity (issue #201).
 *
 * Only stacked suffix chains (two or more " (N)" groups) are repaired: a
 * single " (2)" can be a deliberate user naming scheme and is never touched.
 */
class NsdConflictRenameRepairTest {

    @Test
    fun stackedConflictSuffixes_strippedToOriginal() {
        // One suffix per service restart — the exact shape from issue #201.
        assertEquals(
            "pixel_9_pro_25d2_voice_assistant",
            stripNsdConflictRename("pixel_9_pro_25d2_voice_assistant (2) (2) (2) (2) (2)"),
        )
    }

    @Test
    fun twoStackedSuffixes_stripped() {
        assertEquals("living_room", stripNsdConflictRename("living_room (2) (2)"))
    }

    @Test
    fun mixedDigitsInStackedSuffixes_stripped() {
        assertEquals("living_room", stripNsdConflictRename("living_room (2) (3)"))
    }

    @Test
    fun trailingWhitespaceAfterStackedSuffixes_stripped() {
        assertEquals("living_room", stripNsdConflictRename("living_room (2) (2)  "))
    }

    @Test
    fun singleConflictSuffix_keptForUserRenameSafety() {
        // Indistinguishable from a deliberate "Tablet (2)" sibling-device scheme.
        assertEquals("living_room (2)", stripNsdConflictRename("living_room (2)"))
        assertEquals("Tablet (2)", stripNsdConflictRename("Tablet (2)"))
        assertEquals("living_room (12)", stripNsdConflictRename("living_room (12)"))
    }

    @Test
    fun cleanSlugName_untouched() {
        assertEquals("kitchen_panel", stripNsdConflictRename("kitchen_panel"))
    }

    @Test
    fun nonNumericParens_untouched() {
        assertEquals("kitchen (main) panel", stripNsdConflictRename("kitchen (main) panel"))
    }

    @Test
    fun numericParensNotAtEnd_untouched() {
        assertEquals("room (2) light", stripNsdConflictRename("room (2) light"))
    }

    @Test
    fun nameThatIsOnlyStackedSuffixes_keptAsIs() {
        // Stripping would leave nothing usable; keep the stored value.
        assertEquals("(2) (2)", stripNsdConflictRename("(2) (2)"))
    }
}
