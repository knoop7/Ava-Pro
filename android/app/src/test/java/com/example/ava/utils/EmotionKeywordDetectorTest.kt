package com.example.ava.utils

import com.example.ava.utils.EmotionKeywordDetector.Expression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the priority chain + fired-keyword capture of [EmotionKeywordDetector.detect]
 * (pure Kotlin, no Android runtime). The floating sphere's per-page eye swap and its
 * telemetry both depend on this exact behavior.
 */
class EmotionKeywordDetectorTest {

    @Test
    fun angryOutranksHappy() {
        assertEquals(Expression.ANGRY, EmotionKeywordDetector.detect("生气但是很开心").expression)
    }

    @Test
    fun capturesFiredKeyword() {
        val d = EmotionKeywordDetector.detect("恭喜你")
        assertEquals(Expression.HAPPY, d.expression)
        assertEquals("恭喜", d.keyword)
    }

    @Test
    fun englishKeywordAndCaseInsensitive() {
        assertEquals(Expression.SAD, EmotionKeywordDetector.detect("unfortunately").expression)
        assertEquals(Expression.HAPPY, EmotionKeywordDetector.detect("GREAT job").expression)
    }

    @Test
    fun blankAndNullAreNeutralWithNoKeyword() {
        val blank = EmotionKeywordDetector.detect("   ")
        assertEquals(Expression.NEUTRAL, blank.expression)
        assertNull(blank.keyword)
        assertEquals(Expression.NEUTRAL, EmotionKeywordDetector.detect(null).expression)
    }

    @Test
    fun noKeywordIsNeutral() {
        val d = EmotionKeywordDetector.detect("12345")
        assertEquals(Expression.NEUTRAL, d.expression)
        assertNull(d.keyword)
    }

    @Test
    fun loneContinueIsNotListening() {
        val d = EmotionKeywordDetector.detect("继续")
        assertEquals(Expression.NEUTRAL, d.expression)
        assertNull(d.keyword)
    }

    @Test
    fun detectExpressionDelegates() {
        assertEquals(Expression.HAPPY, EmotionKeywordDetector.detectExpression("恭喜你"))
    }
}
