package com.example.ava.ui.screens.home

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MinimalLauncherAppSectionsTest {

    private lateinit var previous: Locale

    @Before
    fun setUp() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previous)
    }

    @Test
    fun emptyListStillPaintsFullLatinAlphabetDisabled() {
        val sections = sectionsForDrawerLabels(emptyList())
        assertEquals(26, sections.size)
        assertEquals(('A'..'Z').map { it.toString() }, sections.map { it.label })
        assertTrue(sections.none { it.enabled })
    }

    @Test
    fun englishNamesKeepFullAlphabetAndGrayEmptyLetters() {
        val sections = sectionsForDrawerLabels(
            listOf("Chrome", "Facebook", "Google", "Settings", "YouTube"),
        )
        assertEquals(26, sections.size)
        assertEquals(('A'..'Z').map { it.toString() }, sections.map { it.label })
        val live = sections.filter { it.enabled }.map { it.label }
        assertEquals(listOf("C", "F", "G", "S", "Y"), live)
        assertEquals(0, sections.first { it.label == "C" }.firstIndex)
        assertFalse(sections.first { it.label == "A" }.enabled)
        assertFalse(sections.any { it.label == SECTION_SYMBOL_LABEL })
    }

    @Test
    fun leadingDigitsGetHashSlot() {
        val sections = sectionsForDrawerLabels(listOf("1Password", "Chrome"))
        assertEquals(SECTION_SYMBOL_LABEL, sections.first().label)
        assertTrue(sections.first().enabled)
        assertEquals(0, sections.first().firstIndex)
        assertTrue(sections.first { it.label == "C" }.enabled)
    }

    @Test
    fun englishCollatorKeepsCjkOnTheStripInsteadOfHidingIt() {
        val sections = sectionsForDrawerLabels(
            listOf("微信", "设置", "相机", "电话", "信息"),
        )
        assertEquals(26, sections.size)
        assertTrue(sections.any { it.enabled })
        assertTrue(sections.any { !it.enabled })
        assertEquals(('A'..'Z').map { it.toString() }, sections.map { it.label })
        assertTrue(sections.none { section -> section.label.any { it.isCjkIdeograph() } })
    }

    @Test
    fun occupiedCyrillicAndGreekAppendAfterAz() {
        val sections = sectionsForDrawerLabels(
            listOf("ВКонтакте", "Chrome", "Χάρτες", "Яндекс"),
        )
        val latin = sections.take(26).map { it.label }
        assertEquals(('A'..'Z').map { it.toString() }, latin)
        assertEquals(listOf("В", "Я"), sections.filter { it.label in setOf("В", "Я") }.map { it.label })
        assertEquals(0, sections.first { it.label == "В" }.firstIndex)
        assertEquals(3, sections.first { it.label == "Я" }.firstIndex)
        assertEquals("Χ", sections.first { it.label == "Χ" }.label)
        assertEquals(2, sections.first { it.label == "Χ" }.firstIndex)
        assertFalse(sections.any { it.label == "Б" })
        assertFalse(sections.any { it.label == "Α" })
    }

    @Test
    fun yoFoldsIntoIe() {
        assertEquals("Е", cyrillicIndexLetter("Ёлка"))
        assertEquals("Е", cyrillicIndexLetter("ёлка"))
    }

    @Test
    fun hangulUsesChoseongNotTheSyllable() {
        assertEquals("ㅋ", hangulIndexLetter("카카오톡"))
        assertEquals("ㄴ", hangulIndexLetter("네이버"))
        assertEquals("ㄱ", hangulIndexLetter("가계부"))
        val sections = sectionsForDrawerLabels(listOf("네이버", "카카오톡"))
        assertEquals(0, sections.first { it.label == "ㄴ" }.firstIndex)
        assertEquals(1, sections.first { it.label == "ㅋ" }.firstIndex)
        assertFalse(sections.any { it.label == "카" })
        assertEquals(('A'..'Z').map { it.toString() }, sections.take(26).map { it.label })
    }

    @Test
    fun kanaMapsHiraganaAndKatakanaToTheSameRow() {
        assertEquals("か", japaneseIndexLetter("カメラ"))
        assertEquals("さ", japaneseIndexLetter("さくら"))
        assertEquals("か", japaneseIndexLetter("ぎんざ"))
        val sections = sectionsForDrawerLabels(listOf("カメラ", "さくら"))
        assertEquals(0, sections.first { it.label == "か" }.firstIndex)
        assertEquals(1, sections.first { it.label == "さ" }.firstIndex)
        assertFalse(sections.any { it.label == "カ" })
    }

    @Test
    fun hebrewAndArabicAppendOnlyOccupiedLetters() {
        val sections = sectionsForDrawerLabels(
            listOf("واتساب", "וואטסאפ"),
        )
        assertEquals("ו", sections.first { it.label == "ו" }.label)
        assertEquals("و", sections.first { it.label == "و" }.label)
        assertFalse(sections.any { it.label == "א" })
        assertFalse(sections.any { it.label == "ا" })
        assertEquals(('A'..'Z').map { it.toString() }, sections.take(26).map { it.label })
    }

    @Test
    fun alefHamzaFoldsToAlef() {
        assertEquals("ا", arabicIndexLetter("أحمد"))
        assertEquals("ه", arabicIndexLetter("ة"))
    }

    private fun Char.isCjkIdeograph(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }
}
