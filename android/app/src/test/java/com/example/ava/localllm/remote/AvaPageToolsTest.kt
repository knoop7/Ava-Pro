package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AvaPageToolsTest {
    private fun validate(tools: HaToolSet, name: String, json: String) =
        AvaToolCallback.validate(tools.tools.single { it.name == name }, JSONObject(json))

    @Test fun surfaceIsEmptyUntilReady() {
        assertTrue(AvaPageTools.surface(false).isEmpty)
        assertEquals(2, AvaPageTools.surface(true).tools.size)
    }

    @Test fun readTakesNoArguments() {
        val tools = AvaPageTools.surface(true)
        assertNull(validate(tools, AvaPageTools.READ, """{}"""))
        assertNotNull(validate(tools, AvaPageTools.READ, """{"mode":"page"}"""))
    }

    @Test fun tapNeedsIdxOrSelectorOrText() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"tap"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"tap","idx":3}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"tap","text":"设置"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"tap","selector":"ha-button"}"""))
    }

    @Test fun typeNeedsATarget() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"type","value":"厨房"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"type","idx":2,"value":"厨房"}"""))
    }

    @Test fun keyNeedsAKey() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"key"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"key","key":"Enter"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"key","key":"Escape","idx":2}"""))
    }

    @Test fun navigateNeedsHaPath() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"navigate"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"navigate","path":"/config"}"""))
        assertEquals("/config", AvaPageTools.normalizePath("/config"))
        assertNull(AvaPageTools.normalizePath("https://example.com"))
        assertNull(AvaPageTools.normalizePath("config"))
    }

    @Test fun backAndTextTakeNoTarget() {
        val tools = AvaPageTools.surface(true)
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"back"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"restore"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"text"}"""))
    }

    @Test fun trailRemembersEachHopAndRestore() {
        AvaPageTools.resetForTest()
        AvaPageTools.rememberPathForTest("/lovelace/home")
        AvaPageTools.arriveForTest("/config/dashboard")
        AvaPageTools.arriveForTest("/profile")
        assertEquals("/lovelace/home", AvaPageTools.originForTest())
        assertEquals(listOf("/lovelace/home", "/config/dashboard"), AvaPageTools.trailForTest())
        assertTrue(AvaPageTools.willRestoreForTest())
        assertEquals("/config/dashboard", AvaPageTools.popTrailForTest())
        assertEquals("/lovelace/home", AvaPageTools.popTrailForTest())
        val body = AvaPageTools.attachTrailForTest()
        assertEquals("/lovelace/home", body.optString("origin"))
    }

    @Test fun pageAgentKnowsPathAction() {
        val agent = AvaPageTools.pageAgentSource()
        assertTrue(agent.contains("a==='path'"))
        assertTrue(agent.contains("location.pathname"))
        assertTrue(agent.contains("location.href"))
    }

    @Test fun trailRemembersDocsHopWithFullUrl() {
        AvaPageTools.resetForTest()
        AvaPageTools.rememberPathForTest("http://ha.local:8123/lovelace/home")
        AvaPageTools.arriveForTest("https://rc.home-assistant.io/dashboard-home/home")
        assertEquals("http://ha.local:8123/lovelace/home", AvaPageTools.originForTest())
        assertEquals("https://rc.home-assistant.io/dashboard-home/home", AvaPageTools.hereForTest())
        assertEquals(listOf("http://ha.local:8123/lovelace/home"), AvaPageTools.trailForTest())
        assertTrue(AvaPageTools.willRestoreForTest())
        val body = AvaPageTools.attachTrailForTest()
        assertEquals("http://ha.local:8123/lovelace/home", body.optString("origin"))
        assertEquals("https://rc.home-assistant.io/dashboard-home/home", body.optString("href"))
        assertEquals("/dashboard-home/home", body.optString("path"))
        assertEquals(
            "https://rc.home-assistant.io/dashboard-home/home",
            AvaPageTools.normalizeHere("https://rc.home-assistant.io/dashboard-home/home"),
        )
    }

    @Test fun searchNeedsQuery() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"search"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"search","query":"客厅"}"""))
    }

    @Test fun execJsIsHostInjectedNotPageEval() {
        val wrapped = AvaPageTools.wrapExecJs("document.title")
        assertFalse(wrapped.contains("eval("))
        assertFalse(wrapped.contains("Function("))
        assertTrue(wrapped.contains("document.title"))
        assertEquals("document.title", AvaPageTools.stripJsFences("```js\ndocument.title\n```"))
    }

    @Test fun pageAgentIsResidentHostCallNotEval() {
        val agent = AvaPageTools.pageAgentSource()
        assertTrue(agent.contains("window.__avaPage"))
        assertTrue(agent.contains(".run"))
        assertFalse(agent.contains("eval("))
        assertFalse(agent.contains("Function("))
        assertTrue(agent.contains("hass-action") || AvaPageTools.interactSource().contains("hass-action"))
    }

    @Test fun tapUsesClawSequencePlusHassActionOnTiles() {
        val tap = AvaPageTools.interactSource()
        assertTrue(tap.contains("__ava_tap_overlay"))
        assertTrue(tap.contains("hass-action"))
        assertTrue(tap.contains("TouchEvent"))
        assertTrue(tap.contains("PointerEvent"))
        assertTrue(tap.contains("ha-switch"))
        val isel = tap.substringAfter("var ISEL = '").substringBefore("';")
        assertFalse(isel.contains("ha-card"))
        assertFalse(isel.contains("hui-card"))
        assertTrue(isel.contains("ha-pick-theme-row"))
        assertTrue(isel.contains("ha-settings-row"))
        assertTrue(isel.contains("ha-md-list-item"))
    }

    @Test fun execJsNeedsCode() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"exec_js"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"exec_js","js_code":"document.title"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"exec_js","js_code":"(() => document.title)()","force":true}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"exec_js","js_code":"document.title","offset":1500}"""))
    }

    @Test fun moreNeedsOffset() {
        val tools = AvaPageTools.surface(true)
        assertNotNull(validate(tools, AvaPageTools.ACT, """{"action":"more"}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"more","offset":1500}"""))
        assertNull(validate(tools, AvaPageTools.ACT, """{"action":"text","offset":1500}"""))
    }

    @Test fun hostDispatchMatchesSpokenKeyword() {
        AvaPageTools.resetForTest()
        AvaPageTools.indexForTest("prefetch", "客厅灯 开启 23度\n厨房灯 关闭", listOf("客厅灯", "厨房灯"))
        val hits = AvaPageTools.hitsForTest("客厅的灯怎么样")
        assertTrue(hits.length() >= 1)
        val row = hits.getJSONObject(0)
        assertTrue(row.optString("text").contains("客厅"))
        assertEquals("客厅灯", row.optString("query"))
    }

    @Test fun searchTokensMatchSplitWords() {
        AvaPageTools.resetForTest()
        AvaPageTools.indexForTest("text", "客厅温度 24")
        val hits = AvaPageTools.hitsForTest("客厅的温度")
        assertTrue(hits.length() >= 1)
        assertTrue(hits.getJSONObject(0).optString("text").contains("温度"))
    }

    @Test fun compactKeepsIdxAndDropsNoise() {
        val page = JSONObject(
            """{"interactables":[{"idx":1,"action":"tap","text":"设置","role":"button","placeholder":"x","value":"y","in":"card:系统"}]}""",
        )
        AvaPageTools.compactForTest(page, hasHits = false)
        val row = page.getJSONArray("interactables").getJSONObject(0)
        assertEquals(1, row.getInt("idx"))
        assertEquals("设置", row.getString("text"))
        assertFalse(row.has("placeholder"))
        assertFalse(row.has("role"))
    }

    @Test fun dispatchDropsVoiceFiller() {
        AvaPageTools.resetForTest()
        val q = AvaPageTools.dispatchQueries("帮我看看客厅怎么样")
        assertTrue(q.contains("客厅"))
        assertFalse(q.contains("看看"))
        assertFalse(q.contains("帮我"))
    }

    @Test fun indexIgnoresJsonKeys() {
        AvaPageTools.resetForTest()
        AvaPageTools.indexPageForTest(JSONObject("""{"interactables":[{"idx":1,"text":"厨房"}]}"""))
        assertEquals(0, AvaPageTools.hitsForTest("interactables").length())
        assertTrue(AvaPageTools.hitsForTest("厨房").length() >= 1)
    }

    @Test fun hitGroundsTheCachePageSlice() {
        AvaPageTools.resetForTest()
        val long = "x".repeat(800) + "客厅灯 开启 23度"
        AvaPageTools.indexForTest("prefetch", long, listOf("客厅灯"))
        val body = AvaPageTools.attachHitsForTest("客厅灯")
        assertTrue(body.optString("slice").contains("客厅灯"))
        assertEquals(2, body.optInt("page"))
        assertTrue(body.optJSONArray("hits")?.length() ?: 0 >= 1)
    }

    @Test fun compactDropsListsOnceGrounded() {
        AvaPageTools.resetForTest()
        val page = JSONObject(
            """{"nav":[{"title":"概览","path":"/lovelace"}],"cards":[{"title":"客厅","type":"entities"}],"interactables":[{"idx":1,"action":"tap","text":"设置"}]}""",
        )
        AvaPageTools.compactForTest(page, hasHits = true)
        assertFalse(page.has("nav"))
        assertFalse(page.has("cards"))
        assertEquals(1, page.getJSONArray("interactables").length())
    }

    @Test fun compactKeepsTapMapWhenSliceHits() {
        AvaPageTools.resetForTest()
        AvaPageTools.rememberUtterance("客厅灯")
        val page = JSONObject(
            """{"nav":[{"title":"概览","path":"/lovelace"}],"cards":[{"title":"客厅","type":"entities"},{"title":"车库","type":"entities"}],"interactables":[{"idx":1,"action":"tap","text":"设置"},{"idx":2,"action":"toggle","text":"客厅灯","entity":"light.living"},{"idx":3,"action":"tap","text":"厨房"}]}""",
        )
        AvaPageTools.compactForTest(page, hasHits = true)
        assertFalse(page.has("nav"))
        val acts = page.getJSONArray("interactables")
        assertEquals(3, acts.length())
        assertEquals(2, acts.getJSONObject(0).getInt("idx"))
        assertEquals("light.living", acts.getJSONObject(0).optString("entity"))
        val cards = page.optJSONArray("cards")
        assertEquals(1, cards?.length())
        assertEquals("客厅", cards?.getJSONObject(0)?.optString("title"))
    }

    @Test fun snapshotMarksTopControlWithEntity() {
        val js = AvaPageTools.snapshotSource()
        assertTrue(js.contains("isTopElement"))
        assertTrue(js.contains("entityIdOf"))
        assertTrue(js.contains("getPosition"))
        assertTrue(js.contains("hui-tile-card"))
        assertTrue(js.contains("isHaHost"))
        assertTrue(js.contains("ha-pick-theme-row"))
        assertTrue(js.contains("indexOf('dialog')"))
        assertTrue(js.contains("collectDropdowns"))
        assertTrue(js.contains("dropdowns:menus"))
        assertTrue(js.contains("wa-popup"))
        assertTrue(js.contains("ha-bottom-sheet") || js.contains("more-info"))
        assertFalse(js.contains("eval("))
    }

    @Test fun overlayCallbackCoversMoreInfoAndSheets() {
        val js = AvaPageTools.dialogSource()
        assertTrue(js.contains("extractMoreInfo"))
        assertTrue(js.contains("ha-adaptive-dialog"))
        assertTrue(js.contains("ha-bottom-sheet"))
        assertTrue(js.contains("more_info"))
        assertTrue(js.contains("overlays:out"))
        val agent = AvaPageTools.pageAgentSource()
        assertTrue(agent.contains("hass-more-info"))
        assertTrue(agent.contains("show-dialog"))
        assertTrue(agent.contains("lastOverlay"))
        assertTrue(agent.contains("overlayOpened"))
    }

    @Test fun pageAgentCollectsStateReceipt() {
        val agent = AvaPageTools.pageAgentSource()
        assertTrue(agent.contains("action==='state'") || agent.contains("a==='state'"))
        assertTrue(agent.contains("a==='ping'"))
        assertTrue(agent.contains("hass.states"))
    }
}
