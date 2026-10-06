package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AvaPhoneToolsTest {
    @Before fun resetPhoneSession() {
        AvaPhoneTools.beginTurn()
    }

    @Test fun compactTreeDropsEmptyNodesAndKeepsOriginalIndex() {
        val raw = JSONObject()
            .put("status", "ok")
            .put("nodeCount", 3)
            .put(
                "nodes",
                JSONArray()
                    .put(JSONObject().put("index", 0).put("text", "").put("contentDescription", "").put("clickable", false))
                    .put(JSONObject().put("index", 1).put("text", "微信").put("clickable", true))
                    .put(JSONObject().put("index", 2).put("text", "").put("contentDescription", "Search").put("clickable", false)),
            )
        val compact = AvaPhoneTools.compactTree(raw)
        assertEquals(2, compact.getInt("shown"))
        assertEquals(1, compact.getJSONArray("nodes").getJSONObject(0).getInt("index"))
        assertFalse(compact.has("warning"))
    }

    @Test fun findPrefersAnExactLabelOverAContainsHit() {
        val tree = JSONObject().put(
            "nodes",
            JSONArray()
                .put(JSONObject().put("index", 0).put("text", "微信登录"))
                .put(JSONObject().put("index", 4).put("text", "微信")),
        )
        val hits = AvaPhoneTools.findNodes(tree, "微信")
        assertEquals(1, hits.length())
        assertEquals(4, hits.getJSONObject(0).getInt("index"))
        assertTrue(AvaPhoneTools.findNodes(tree, "登录").getJSONObject(0).getString("text").contains("登录"))
    }

    @Test fun sanitizeDropsPasswordTextAndInternalFields() {
        val node = JSONObject()
            .put("index", 3)
            .put("text", "secret")
            .put("contentDescription", "password")
            .put("className", "android.widget.EditText")
            .put("password", true)
            .put("viewId", "com.app:id/pwd")
            .put("packageName", "com.app")
            .put("clickable", true)
            .put("editable", true)
            .put("bounds", JSONObject().put("centerX", 10).put("centerY", 20))
        val clean = AvaPhoneTools.sanitizeNode(node)
        assertEquals("", clean.getString("text"))
        assertFalse(clean.has("viewId"))
        assertFalse(clean.has("packageName"))
        assertTrue(clean.getBoolean("password"))
        val tree = JSONObject().put(
            "nodes",
            JSONArray()
                .put(node)
                .put(JSONObject().put("index", 4).put("text", "登录").put("clickable", true)),
        )
        assertEquals(0, AvaPhoneTools.findNodes(tree, "secret").length())
        assertFalse(AvaPhoneTools.compactTree(tree).toString().contains("com.app"))
    }

    @Test fun lastFindKeepsItsIndexAndRejectsAnOldOne() {
        AvaPhoneTools.rememberNodes(
            JSONArray().put(JSONObject().put("index", 4).put("text", "登录").put("clickable", true)),
        )
        assertNull(AvaPhoneTools.staleIndex(4))
        val stale = AvaPhoneTools.staleIndex(14)!!
        assertFalse(stale.ok)
        assertEquals("stale_ref", stale.error!!.type)
        assertTrue(stale.error!!.message.contains("Find again"))
        assertTrue(stale.error!!.message.contains("14"))
        assertEquals("tree", stale.error!!.details!!.getJSONObject("next_action").getJSONObject("arguments").getString("action"))
        assertEquals("read_elements_again", stale.toJson().getString("recovery"))
        assertNotNull(AvaPhoneTools.currentIncident())
    }

    @Test fun clickAsksForAFreshFindAndDropsTheOldIndex() {
        AvaPhoneTools.rememberNodes(
            JSONArray().put(JSONObject().put("index", 4).put("text", "登录").put("clickable", true)),
        )
        val seen = AvaPhoneTools.withObserve(JSONObject().put("ok", true), query = "登录")
        assertTrue(seen.getBoolean("requires_observation"))
        val next = seen.getJSONObject("next_action")
        assertEquals(AvaPhoneTools.NAME, next.getString("tool"))
        assertEquals("find", next.getJSONObject("arguments").getString("action"))
        assertEquals("登录", next.getJSONObject("arguments").getString("query"))
        assertTrue(seen.getString("hint").contains("Do not reuse"))
        AvaPhoneTools.invalidateNodes()
        val stale = AvaPhoneTools.staleIndex(4)!!
        assertEquals("stale_ref", stale.error!!.type)
    }

    @Test fun aFreshFindClearsTheIncident() {
        AvaPhoneTools.staleIndex(9)
        assertNotNull(AvaPhoneTools.currentIncident())
        AvaPhoneTools.rememberNodes(
            JSONArray().put(JSONObject().put("index", 2).put("text", "确定")),
        )
        assertNull(AvaPhoneTools.currentIncident())
        assertNull(AvaPhoneTools.staleIndex(2))
    }

    @Test fun lastFindKeepsTheClickCenter() {
        val tree = JSONObject().put(
            "nodes",
            JSONArray().put(
                JSONObject()
                    .put("index", 4)
                    .put("text", "微信")
                    .put("clickable", true)
                    .put("bounds", JSONObject().put("centerX", 120).put("centerY", 480)),
            ),
        )
        AvaPhoneTools.rememberNodes(AvaPhoneTools.findNodes(tree, "微信"))
        assertEquals(120 to 480, AvaPhoneTools.centerOf(4))
        AvaPhoneTools.invalidateNodes()
        assertNull(AvaPhoneTools.centerOf(4))
    }

    @Test fun aNewTurnForgetsIndexes() {
        AvaPhoneTools.rememberNodes(
            JSONArray().put(JSONObject().put("index", 1).put("text", "确定")),
        )
        AvaPhoneTools.beginTurn()
        assertNull(AvaPhoneTools.currentIncident())
        val stale = AvaPhoneTools.staleIndex(1)!!
        assertEquals("stale_ref", stale.error!!.type)
    }
}
