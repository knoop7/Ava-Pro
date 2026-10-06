package com.example.ava.localllm

import com.example.ava.homeassistant.HaEntityNames
import com.example.ava.homeassistant.entity.HaEntitySummary
import org.junit.Assert.*
import org.junit.Test

class DeviceIndexTest {
    private fun light(id: String, name: String = "台灯") = HaEntitySummary(id, name, "light", "off")

    @Test fun sameNamesRequireClarificationButExactIdsStillResolve() {
        val index = DeviceIndex()
        index.add(light("light.desk")); index.add(light("light.bed"))
        assertNull(index.resolve("台灯"))
        assertEquals(2, index.candidates("台灯").size)
        assertEquals("light.bed", index.resolve("light.bed")?.entityId)
    }

    @Test fun sameAliasesCannotSilentlyChooseTheFirstRoom() {
        val index = DeviceIndex()
        index.add(light("light.a", "A")); index.add(light("light.b", "B"))
        index.addNames("light.a", HaEntityNames(listOf("reading lamp"), listOf("office")))
        index.addNames("light.b", HaEntityNames(listOf("reading lamp"), listOf("bedroom")))
        assertNull(index.resolve("reading lamp"))
        assertEquals("light.a", index.resolve("office reading lamp")?.entityId)
    }

    @Test fun domainCanDisambiguateButDoesNotOverrideAnExactId() {
        val index = DeviceIndex()
        index.add(light("light.a", "desk")); index.add(HaEntitySummary("switch.a", "desk", "switch", "off"))
        assertEquals("switch.a", index.resolve("desk", "switch")?.entityId)
        assertNull(index.resolve("light.a", "switch"))
    }

    @Test fun renameRemovesTheOldNameAndGenericNamesNeedDiscovery() {
        val index = DeviceIndex()
        index.add(light("light.a", "old desk")); index.add(light("light.a", "new desk"))
        assertNull(index.resolve("old desk"))
        index.add(light("light.b", "灯"))
        assertNull(index.resolve("灯"))
        assertEquals(2, index.discover("灯", domain = "light").size)
    }

    @Test fun clawOrdinalAndTurnOnPhrasingResolveTheLight() {
        val index = DeviceIndex()
        index.add(light("light.one", "灯光1"))
        assertEquals("light.one", index.resolve("灯光一")?.entityId)
        assertEquals("light.one", index.resolve("打开灯光一")?.entityId)
        assertEquals("light.one", index.resolve("把灯光一打开")?.entityId)
        index.addNames("light.one", HaEntityNames(listOf("灯光一"), emptyList()))
        assertEquals("light.one", index.resolve("灯光一")?.entityId)
    }

    @Test fun clawSplitsTypeFromOrdinalWhenTheNameIsJustTheNumber() {
        val index = DeviceIndex()
        index.add(light("light.a", "灯1"))
        assertEquals("light.a", index.resolve("打开灯光一")?.entityId)
        index.add(light("light.b", "灯2"))
        assertEquals("light.a", index.resolve("灯光一")?.entityId)
        index.add(light("light.c", "客厅灯1"))
        assertNull(index.resolve("灯光一"))
        assertEquals(2, index.discover("灯光一", domain = "light").size)
    }

    @Test fun spokenNearMissStillResolvesAUniqueName() {
        val index = DeviceIndex()
        index.add(light("light.lr", "客厅灯"))
        assertEquals("light.lr", index.resolve("客厅等")?.entityId)
        index.add(light("light.desk", "desk light"))
        assertEquals("light.desk", index.resolve("desk lite")?.entityId)
        assertNull(index.resolve("锁"))
    }

    @Test fun spokenCameraNameResolvesWithoutSearchingTheHouse() {
        val index = DeviceIndex()
        index.add(HaEntitySummary("camera.gate", "门口", "camera", "idle"))
        assertEquals("camera.gate", index.resolve("看看门口摄像头")?.entityId)
        assertEquals("camera.gate", index.resolve("门口", "camera")?.entityId)
        index.add(HaEntitySummary("camera.yard", "后院", "camera", "idle"))
        assertNull(index.resolve("摄像头"))
    }

    @Test fun houseScopeWithATypeListsEveryLightAndDoesNotDumpTheHouse() {
        val index = DeviceIndex()
        index.add(light("light.a", "台灯"))
        index.add(light("light.b", "吊灯"))
        index.add(light("light.c", "灯带"))
        index.add(HaEntitySummary("switch.a", "插座", "switch", "off"))
        assertEquals(3, index.discover("全屋", domain = "light").size)
        assertEquals(3, index.discover("全屋灯光").size)
        assertEquals(3, index.discover("所有灯", domain = "light").size)
        assertEquals(3, index.discover(domain = "light").size)
        assertNull(index.resolve("全屋"))
        assertTrue(index.discover("全屋").isEmpty())
    }

    @Test fun clawBedroomLightSplitDoesNotPickTheFirstSameName() {
        val index = DeviceIndex()
        index.add(light("light.a", "台灯")); index.add(light("light.b", "台灯"))
        assertNull(index.resolve("打开台灯"))
        assertEquals(2, index.candidates("打开台灯").size)
    }
}
