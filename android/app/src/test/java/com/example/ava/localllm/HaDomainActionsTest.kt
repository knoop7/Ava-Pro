package com.example.ava.localllm

import org.junit.Assert.assertEquals
import org.junit.Test

class HaDomainActionsTest {
    @Test fun powerMapsNonLightDomains() {
        assertEquals("cover.open_cover", HaDomainActions.service("cover", true))
        assertEquals("valve.close_valve", HaDomainActions.service("valve", false))
        assertEquals("lock.unlock", HaDomainActions.service("lock", true))
        assertEquals("vacuum.start", HaDomainActions.service("vacuum", true))
        assertEquals("vacuum.return_to_base", HaDomainActions.service("vacuum", false))
        assertEquals("lawn_mower.start_mowing", HaDomainActions.service("lawn_mower", true))
        assertEquals("lawn_mower.dock", HaDomainActions.service("lawn_mower", false))
        assertEquals("humidifier.turn_on", HaDomainActions.service("humidifier", true))
        assertEquals("timer.start", HaDomainActions.service("timer", true))
        assertEquals("homeassistant.turn_on", HaDomainActions.service("siren_unknown", true))
    }

    @Test fun spokenTypeCuesIncludeMoreThanLights() {
        assertEquals("humidifier", HaDomainActions.hintDomain(DeviceIndex.normalize("客厅加湿器")))
        assertEquals("water_heater", HaDomainActions.hintDomain(DeviceIndex.normalize("热水器")))
        assertEquals("valve", HaDomainActions.hintDomain(DeviceIndex.normalize("阀门")))
        assertEquals("cover", HaDomainActions.hintDomain(DeviceIndex.normalize("拉开窗帘")))
        assertEquals("lawn_mower", HaDomainActions.hintDomain(DeviceIndex.normalize("割草机")))
        assertEquals("scene", HaDomainActions.hintDomain(DeviceIndex.normalize("回家场景")))
        assertEquals("camera", HaDomainActions.hintDomain(DeviceIndex.normalize("门口摄像头")))
    }
}
