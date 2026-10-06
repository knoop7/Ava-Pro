package com.example.ava.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaSpokenTargetTest {
    @Test fun unwrapsTurnOnAndSplitsLightOrdinal() {
        val t = HaSpokenTarget.parse("打开灯光一")
        assertEquals("light", t.domain)
        assertEquals("1", t.name)
    }

    @Test fun englishBedroomLight() {
        val t = HaSpokenTarget.parse("bedroom light")
        assertEquals("light", t.domain)
        assertEquals("bedroom", t.name)
    }

    @Test fun bareTypeHasDomainAndEmptyName() {
        val t = HaSpokenTarget.parse("灯光")
        assertEquals("light", t.domain)
        assertEquals("", t.name)
    }

    @Test fun trailingLightTypeBecomesDomain() {
        val t = HaSpokenTarget.parse("台灯")
        assertEquals("light", t.domain)
        assertEquals("台", t.name)
    }

    @Test fun foldDigitsMakesOrdinalsEqual() {
        assertEquals("灯光1", DeviceIndex.normalize("灯光一"))
        assertEquals("灯光1", DeviceIndex.normalize("灯光1"))
        assertNull(HaSpokenTarget.parse("").domain)
    }

    @Test fun stripsWakeWordAndEnglishFiller() {
        val mentioned = HaSpokenTarget.parse("Ava打开灯光一")
        assertEquals("light", mentioned.domain)
        assertEquals("1", mentioned.name)
        val eva = HaSpokenTarget.parse("hey eva turn on the bedroom light")
        assertEquals("light", eva.domain)
        assertEquals("bedroom", eva.name)
        val please = HaSpokenTarget.parse("please open the curtain")
        assertEquals("cover", please.domain)
        assertEquals("", please.name)
    }

    @Test fun unwrapsLookAtCamera() {
        val t = HaSpokenTarget.parse("看看门口摄像头")
        assertEquals("camera", t.domain)
        assertEquals("门口", t.name)
    }

    @Test fun houseScopePlusLightTypeIsAListNotAName() {
        val whole = HaSpokenTarget.parse("全屋灯光")
        assertEquals("light", whole.domain)
        assertEquals("", whole.name)
        val all = HaSpokenTarget.parse("所有灯")
        assertEquals("light", all.domain)
        assertEquals("", all.name)
        val bare = HaSpokenTarget.parse("全屋")
        assertNull(bare.domain)
        assertEquals("全屋", bare.name)
    }

    @Test fun unwrapsCoverAndVacuumPhrasing() {
        val cover = HaSpokenTarget.parse("拉开窗帘")
        assertEquals("cover", cover.domain)
        assertEquals("", cover.name)
        val vacuum = HaSpokenTarget.parse("回充扫地机")
        assertEquals("vacuum", vacuum.domain)
    }
}
