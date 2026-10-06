package com.example.ava.esphome.voicesatellite

import com.example.ava.esphome.voicesatellite.VoiceSatelliteAudioInput.Companion.ttsTextMentionsWakePhrase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-wake screen: a TTS response that mentions the wake phrase utters it through the
 * speaker, and its echo is a genuine wake utterance no AEC or re-score can reject.
 * These tests pin the text matcher that arms the wake-dispatch hold.
 */
class TtsWakeEchoScreenTest {
    private val heyJarvis = listOf("Hey Jarvis")

    @Test
    fun nameAloneMatches() {
        // Streaming models score the name: "I'm Jarvis" self-wakes a "hey jarvis" model.
        assertTrue(ttsTextMentionsWakePhrase("I'm Jarvis, your personal assistant.", heyJarvis))
    }

    @Test
    fun fullPhraseWithPunctuationMatches() {
        assertTrue(ttsTextMentionsWakePhrase("Just say: Hey, Jarvis!", heyJarvis))
    }

    @Test
    fun nameEmbeddedInCjkTextMatches() {
        // CJK sets Latin names without surrounding spaces.
        assertTrue(ttsTextMentionsWakePhrase("我是Jarvis，有什么可以帮你？", heyJarvis))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(ttsTextMentionsWakePhrase("JARVIS is my name", heyJarvis))
    }

    @Test
    fun ordinaryResponseDoesNotMatch() {
        assertFalse(ttsTextMentionsWakePhrase("The weather today is sunny, 25 degrees.", heyJarvis))
    }

    @Test
    fun nameInsideAnotherWordDoesNotMatch() {
        // "available" must not match a wake word "Ava".
        assertFalse(ttsTextMentionsWakePhrase("The service is available now.", listOf("Ava")))
        assertTrue(ttsTextMentionsWakePhrase("Ava is online.", listOf("Ava")))
    }

    @Test
    fun underscoreIdFallbackMatches() {
        // When a phrase is unresolved the caller passes the id with underscores replaced,
        // but a raw id must still split on underscores to find the name token.
        assertTrue(ttsTextMentionsWakePhrase("say jarvis to wake me", listOf("hey_jarvis")))
    }

    @Test
    fun blankTextAndEmptyPhrasesDoNotMatch() {
        assertFalse(ttsTextMentionsWakePhrase("", heyJarvis))
        assertFalse(ttsTextMentionsWakePhrase("   ", heyJarvis))
        assertFalse(ttsTextMentionsWakePhrase("hello jarvis", emptyList()))
    }

    @Test
    fun shortTokensAreIgnored() {
        // A 1-2 letter token would match everywhere; the screen must ignore it.
        assertFalse(ttsTextMentionsWakePhrase("go to bed", listOf("Go")))
    }

    @Test
    fun cjkTransliterationMatches() {
        // Chinese responses transliterate the name; its TTS echo measured 0.57
        // against hey_jarvis at a −12 dB residual, so the screen must catch it.
        assertTrue(ttsTextMentionsWakePhrase("你好，我是贾维斯。", heyJarvis))
        assertTrue(ttsTextMentionsWakePhrase("嘿，贾维斯已经准备好了。", heyJarvis))
        assertTrue(ttsTextMentionsWakePhrase("佳维斯为您服务。", heyJarvis))
        assertTrue(ttsTextMentionsWakePhrase("加维斯已上线。", listOf("hey_jarvis")))
    }

    @Test
    fun unrelatedCjkNamesDoNotMatch() {
        // 哈维斯 is not an alias and never fired the model in the echo stress corpus.
        assertFalse(ttsTextMentionsWakePhrase("哈维斯先生刚刚来过电话。", heyJarvis))
        assertFalse(ttsTextMentionsWakePhrase("今天多云，气温二十八度。", heyJarvis))
    }
}
