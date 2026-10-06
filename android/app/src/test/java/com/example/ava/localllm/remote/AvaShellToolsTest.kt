package com.example.ava.localllm.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaShellToolsTest {

    @Test
    fun schemaHasNoFreeCommand() {
        val def = AvaShellTools.schema()
        assertEquals(AvaShellTools.NAME, def.name)
        assertTrue(def.description.contains("stdout"))
        assertTrue(def.description.contains("virtual display"))
        assertTrue(def.description.contains("here_ui"))
        assertFalse(def.description.contains("dumpsys window windows"))
        assertFalse(def.description.contains("adb控制"))
        assertFalse(def.params.any { it.name == "command" })
        assertTrue(def.params.any { it.name == "action" })
    }

    @Test
    fun dumpAndInputCommandsMatchTheConsole() {
        assertEquals("dumpsys window windows", AvaShellTools.dumpCommand(AvaShellTools.DumpTarget.WINDOW))
        assertEquals("dumpsys display", AvaShellTools.dumpCommand(AvaShellTools.DumpTarget.DISPLAY))
        assertEquals("input tap 10 20", AvaShellTools.tapCommand(10, 20, null))
        assertEquals("input -d 2 tap 10 20", AvaShellTools.tapCommand(10, 20, 2))
        assertEquals("input swipe 1 2 3 4 300", AvaShellTools.swipeCommand(1, 2, 3, 4, null))
        assertEquals("input -d 5 keyevent 4", AvaShellTools.keyCommand(4, 5))
    }

    @Test
    fun allowlistRejectsFreeAndDangerousCommands() {
        assertTrue(AvaShellTools.allowed("dumpsys window windows"))
        assertTrue(AvaShellTools.allowed("dumpsys activity activities"))
        assertTrue(AvaShellTools.allowed("dumpsys display"))
        assertTrue(AvaShellTools.allowed("uiautomator dump /data/local/tmp/ava-ui.xml >/dev/null && cat /data/local/tmp/ava-ui.xml"))
        assertTrue(AvaShellTools.allowed("input tap 1 2"))
        assertTrue(AvaShellTools.allowed("input -d 2 tap 1 2"))
        assertFalse(AvaShellTools.allowed("reboot"))
        assertFalse(AvaShellTools.allowed("rm -rf /"))
        assertFalse(AvaShellTools.allowed("am start -n x/.Y"))
        assertFalse(AvaShellTools.allowed("screencap -p /sdcard/x.png"))
        assertFalse(AvaShellTools.allowed("input text hello"))
        assertFalse(AvaShellTools.allowed("settings put secure enabled_accessibility_services x"))
        assertFalse(AvaShellTools.allowed("dumpsys window && rm -rf /"))
    }

    @Test
    fun compactKeepsFocusAndClipsTheRest() {
        val raw = """
            Window #1
            mCurrentFocus=Window{abc u0 com.tencent.mm/LauncherUI}
            junk line that must drop
            mDisplayId=2
            ${"x".repeat(5000)}
        """.trimIndent()
        val out = AvaShellTools.compactDump(AvaShellTools.DumpTarget.WINDOW, raw)
        assertTrue(out.contains("mCurrentFocus"))
        assertTrue(out.contains("mDisplayId=2"))
        assertFalse(out.contains("junk line that must drop"))
        assertTrue(out.length < 4_200)
    }

    @Test
    fun compactTreeReadsTextsNotXml() {
        val xml = """<?xml version='1.0'?>
            <node text="微信" package="com.tencent.mm">
              <node content-desc="搜索" package="com.tencent.mm"/>
            </node>
        """.trimIndent()
        val out = AvaShellTools.compactDump(AvaShellTools.DumpTarget.TREE, xml)
        assertTrue(out.contains("微信"))
        assertTrue(out.contains("搜索"))
        assertTrue(out.contains("com.tencent.mm"))
        assertFalse(out.contains("<?xml"))
    }

    @Test
    fun nextDumpFollowsVirtualDisplay() {
        val def = AvaShellTools.nextDump(null)
        assertEquals("ava_shell", def.getString("tool"))
        assertEquals("window", def.getJSONObject("arguments").getString("target"))
        assertEquals("display", AvaShellTools.nextDump(2).getJSONObject("arguments").getString("target"))
    }

    @Test
    fun parseKeyAndTarget() {
        assertEquals(AvaShellTools.DumpTarget.WINDOW, AvaShellTools.parseDumpTarget(""))
        assertEquals(AvaShellTools.DumpTarget.DISPLAY, AvaShellTools.parseDumpTarget("display"))
        assertEquals(4, AvaShellTools.parseKey("BACK"))
        assertEquals(3, AvaShellTools.parseKey("home"))
        assertEquals(66, AvaShellTools.parseKey("66"))
        assertEquals(null, AvaShellTools.parseDumpTarget("pm"))
        assertEquals(null, AvaShellTools.parseKey("rm"))
    }
}
