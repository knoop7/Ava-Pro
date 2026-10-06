package com.example.ava.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationCoordinatorTest {
    @Test
    fun restorableRoutesCoverFilledAvaPages() {
        assertTrue(MainNavigationCoordinator.isRestorableRoute(Screen.HOME))
        assertTrue(MainNavigationCoordinator.isRestorableRoute(Screen.SETTINGS_HA))
        assertTrue(MainNavigationCoordinator.isRestorableRoute("settings/ha/pipeline/abc"))
        assertTrue(MainNavigationCoordinator.isRestorableRoute("mod_config/demo"))
        assertTrue(MainNavigationCoordinator.isRestorableRoute("quick_entity_edit/2"))
        assertTrue(MainNavigationCoordinator.isRestorableRoute("settings/interaction/scene/edit/new"))
    }

    @Test
    fun restorableRoutesRejectPatternsAndOnboarding() {
        assertFalse(MainNavigationCoordinator.isRestorableRoute(null))
        assertFalse(MainNavigationCoordinator.isRestorableRoute(""))
        assertFalse(MainNavigationCoordinator.isRestorableRoute(Screen.ONBOARDING))
        assertFalse(MainNavigationCoordinator.isRestorableRoute(Screen.SETTINGS_HA_PIPELINE_DETAIL))
        assertFalse(MainNavigationCoordinator.isRestorableRoute("mod_config/{modId}"))
    }

    @Test
    fun programHomeNeedsAResumedAvaActivity() {
        MainNavigationCoordinator.bindActivityResumed(false)
        MainNavigationCoordinator.bindCurrentRoute(Screen.HOME)
        assertFalse(MainNavigationCoordinator.isProgramHome())
        assertFalse(MainNavigationCoordinator.isActivityResumed())
        MainNavigationCoordinator.bindActivityResumed(true)
        assertTrue(MainNavigationCoordinator.isProgramHome())
        assertTrue(MainNavigationCoordinator.isActivityResumed())
        MainNavigationCoordinator.bindActivityResumed(false)
        MainNavigationCoordinator.bindCurrentRoute(null)
    }

    @Test
    fun settingsLikeIncludesSlotEditors() {
        assertTrue(MainNavigationCoordinator.isSettingsLikeRoute("quick_entity_edit/1"))
        assertTrue(MainNavigationCoordinator.isSettingsLikeRoute("simple_clock_status_edit/0"))
        assertTrue(MainNavigationCoordinator.isSettingsLikeRoute("dawn_entity_slot_edit/3"))
        assertFalse(MainNavigationCoordinator.isSettingsLikeRoute(Screen.HOME))
    }
}
