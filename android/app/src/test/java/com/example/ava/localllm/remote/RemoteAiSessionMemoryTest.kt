package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteAiSessionMemoryTest {
    private val transcript = JSONArray().put(JSONObject().put("role", "assistant").put("content", "untrusted page text"))

    @Test fun browserFollowUpRestoresTranscriptOnlyWithItsRestriction() {
        val memory = RemoteAiSessionMemory()
        memory.remember("查资料", transcript, true, false, true)
        val resume = memory.resumeFor("继续读第二个结果", true, true)!!
        assertTrue(resume.browserOnly)
        assertEquals("查资料", resume.originalUser)
        assertTrue(resume.messages.toString().contains("untrusted page text"))
    }

    @Test fun freshDeviceRequestDoesNotReceiveBrowserTranscript() {
        val memory = RemoteAiSessionMemory()
        memory.remember("查资料", transcript, true, false, true)
        assertNull(memory.resumeFor("turn this light on", true, true))
        assertNull(memory.resumeFor("打开浏览器", true, true))
        assertNull(memory.resumeFor("关掉浏览器", true, true))
    }

    @Test fun disabledHistoryOrBrowserNeverRestoresPageData() {
        val memory = RemoteAiSessionMemory()
        memory.remember("查资料", transcript, true, true, false)
        assertNull(memory.resumeFor("继续", true, true))
        memory.remember("查资料", transcript, true, true, true)
        assertNull(memory.resumeFor("继续", true, false))
    }

    @Test fun unfinishedDeviceTaskCanResumeButExpiredStateCannot() {
        var clock = 0L
        val memory = RemoteAiSessionMemory { clock }
        memory.remember("调两盏灯", transcript, false, true, true)
        assertFalse(memory.resumeFor("调两盏灯", true, true)!!.browserOnly)
        memory.remember("查资料", transcript, true, true, true)
        clock += 16 * 60_000L
        assertNull(memory.resumeFor("继续读第二个结果", true, true))
    }
    @Test fun negatedContinuationCancelsButFreshRequestsDoNotConsumeLookupState() {
        val memory = RemoteAiSessionMemory()
        memory.remember("查网页", transcript, true, true, true)
        assertNull(memory.resumeFor("不要继续网页了，打开客厅灯", true, true))
        assertNull(memory.resumeFor("继续读第二个结果", true, true))
        memory.remember("查网页", transcript, true, true, true)
        assertNull(memory.resumeFor("play the first song", true, true))
        assertNotNull(memory.resumeFor("继续读第二个结果", true, true))
        assertNotNull(memory.resumeFor("下一个", true, true))
    }

    @Test fun bareContinueDoesNotResumeABrowserTask() {
        val memory = RemoteAiSessionMemory()
        memory.remember("查网页", transcript, true, true, true, awaitingContinuation = true)
        assertNull(memory.resumeFor("好的", true, true))
        assertNull(memory.resumeFor("yes", true, true))
        assertNull(memory.resumeFor("继续", true, true))
        assertNotNull(memory.resumeFor("好的，继续读第二个结果", true, true))
    }

    @Test fun repeatingTheStoppedRequestResumesIt() {
        val memory = RemoteAiSessionMemory()
        memory.remember("门口有人吗", transcript, false, true, true, awaitingContinuation = true)
        val resume = memory.resumeFor("门口有人吗？", true, true)!!
        assertEquals("门口有人吗", resume.originalUser)
        assertTrue(resume.unfinished)
        assertNull(memory.resumeFor("客厅灯打开", true, true))
    }

    @Test fun bareYesIsNotAResumeCommand() {
        val memory = RemoteAiSessionMemory()
        memory.remember("把三楼的灯都关了", transcript, false, true, true, awaitingContinuation = true)
        assertNull(memory.resumeFor("好的", true, true))
        assertNull(memory.resumeFor("yes", true, true))
        assertNotNull(memory.resumeFor("把三楼的灯都关了", true, true))
    }

    @Test fun unfinishedDeviceTaskSurvivesHistoryOff() {
        val memory = RemoteAiSessionMemory()
        memory.remember("关三楼的灯", transcript, false, true, false, awaitingContinuation = true)
        val resume = memory.resumeFor("关三楼的灯", false, true)!!
        assertEquals("关三楼的灯", resume.originalUser)
        assertTrue(resume.unfinished)
    }

    @Test fun repeatingAFinishedRequestIsANewCommand() {
        val memory = RemoteAiSessionMemory()
        memory.remember("关灯", transcript, false, true, true, awaitingContinuation = false)
        assertNull(memory.resumeFor("关灯", true, true))
    }

    @Test fun lookupReturnsIndependentCopiesWithoutDestroyingCheckpoint() {
        val memory = RemoteAiSessionMemory()
        memory.remember("查网页", transcript, true, true, true)
        val first = memory.resumeFor("继续读第二个结果", true, true)!!
        first.messages.put(JSONObject().put("role", "user").put("content", "changed"))
        assertEquals(transcript.length(), memory.resumeFor("下一个", true, true)!!.messages.length())
    }

}
