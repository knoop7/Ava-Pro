package com.example.ava.esphome.voicesatellite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAssistMissDetectorTest {
    @Test fun stockHassilDeviceMissStillHandsOff() {
        assertTrue(HaAssistMissDetector.shouldHandoff("抱歉，找不到名为灯光一的设备"))
    }

    @Test fun clawNamedLightMissHandsOff() {
        val speech = "我没有找到名为灯光一的灯具您能再说一下具体的灯名或所在的位置吗"
        assertTrue(HaAssistMissDetector.isNamedTargetMiss(speech))
        assertTrue(HaAssistMissDetector.shouldHandoff(speech))
        assertFalse(HaAssistMissDetector.isPrimaryDeviceMiss(speech))
    }

    @Test fun longEssayWithFindIsNotAMiss() {
        val essay = "刚才我在想家里的布置。没有找到名为春天的诗，那是文学不是灯具。" +
            "接下来还可以聊聊天气和其他很多事情，这段话必须足够长才不算交接。"
        assertFalse(HaAssistMissDetector.isNamedTargetMiss(essay))
    }
}
