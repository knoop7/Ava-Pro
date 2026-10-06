package com.example.ava.localllm.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteAiPromptTest {
    private fun env(extra: String = "", phoneTools: Boolean = true) = RemoteAiPrompt.Env(
        "zh-TW", "Ava", "客厅", extra,
        RemoteAiMind.Facts(null, "Ava", "客厅", true), haTools = true, musicPlay = true,
        musicTransport = false, webTools = true, selfTools = true, phoneTools = phoneTools,
    )

    @Test fun customStyleDoesNotRemoveHostPolicy() {
        val text = RemoteAiPrompt.build(env("Use a cheerful tone."))
        assertTrue(text.contains("Use a cheerful tone."))
        assertTrue(text.contains("## Host policy"))
        assertTrue(text.contains("Locks, doors and house alarm panels require a device explicitly named"))
        assertTrue(text.contains("status=observed"))
        assertTrue(text.contains("History is memory, not a language to copy"))
        assertTrue(text.contains("the host keeps the turn"))
        assertTrue(text.contains("must not share one batch"))
        assertTrue(text.contains("not a music or media command"))
        assertTrue(text.contains("action=settings"))
        assertTrue(text.contains("action=mods"))
        assertTrue(text.contains("file picker"))
        assertTrue(text.contains("ha_search domain=light"))
        assertTrue(text.contains("Do not ha_state each"))
        assertTrue(text.contains("Ava设置"))
        assertTrue(text.contains("buried points"))
        assertTrue(text.contains("this page as text"))
        assertTrue(text.contains("live on/off"))
        assertTrue(text.contains("visible[]"))
        assertTrue(text.contains("do not leave them in Ava settings"))
        assertTrue(text.contains("here/path/crumbs"))
        assertTrue(text.contains("opened_overlays"))
        assertTrue(text.contains("this device's own overlay"))
        assertTrue(text.contains("here_ui"))
        assertTrue(text.contains("system, ava, hass, or overlay"))
        assertTrue(text.contains("visible[] taps only when here_ui.top is ava"))
        assertTrue(text.contains("speak here_ui name and texts"))
        assertTrue(text.contains("not a screenshot"))
    }

    @Test fun musicOffPointsAtTheSettingsDoor() {
        val text = RemoteAiPrompt.build(env().copy(musicPlay = false))
        assertFalse(text.contains("ava_music_play"))
        assertTrue(text.contains("music feature is off"))
        assertTrue(text.contains("target=music"))
        assertTrue(text.contains("Do not ha_search a song"))
    }

    @Test fun musicWithoutTransportDoesNotAdvertiseTransportTool() {
        val text = RemoteAiPrompt.build(env())
        assertTrue(text.contains("ava_music_play"))
        assertTrue(text.contains("ava_volume"))
        assertTrue(text.contains("target=tts"))
        assertTrue(text.contains("target=device"))
        assertTrue(text.contains("Do not guess"))
        assertTrue(text.contains("Do not call ava_volume"))
        assertFalse(text.contains("ava_music_control"))
    }

    @Test fun browserPhaseRemovesUnavailableFamilyInstructions() {
        val text = RemoteAiPrompt.build(env().forPhase(true))
        assertTrue(text.contains("Current phase: browser only"))
        assertTrue(text.contains("ha_guide"))
        assertFalse(text.contains("ava_music_play"))
        assertFalse(text.contains("target=tts"))
        assertFalse(text.contains("ava_self action="))
        assertFalse(text.contains("ava_phone"))
        assertFalse(text.contains("ha_call_service with"))
    }

    @Test fun phoneFamilyTeachesSpeakToEnableAccessibility() {
        val on = RemoteAiPrompt.build(env())
        assertTrue(on.contains("ava_phone"))
        assertTrue(on.contains("need_accessibility"))
        assertTrue(on.contains("ask_enable_accessibility"))
        assertTrue(on.contains("details.adb"))
        assertTrue(on.contains("follow next_action"))
        assertTrue(on.contains("stale_ref"))
        assertTrue(on.contains("do not reuse the old index"))
        assertFalse(RemoteAiPrompt.build(env(phoneTools = false)).contains("ava_phone"))
        assertFalse(RemoteAiPrompt.build(env(phoneTools = false)).contains("ava_shell"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, false, false, true),
            "ava_phone",
        )!!.getString("body")
        assertTrue(guide.contains("the page is already open"))
        assertTrue(guide.contains("Tell the user"))
        assertTrue(guide.contains("Android intent"))
        assertTrue(guide.contains("does not need to be showing"))
        assertTrue(guide.contains("Inside THIS app"))
        assertTrue(guide.contains("follow next_action"))
        assertTrue(on.contains("Inside THIS app"))
        assertTrue(guide.contains("Do not reuse an old index"))
        assertTrue(on.contains("does not need to be open"))
        assertNull(RemoteAiGuide.get(RemoteAiGuide.Ready(false, false, false, true, false, false), "ava_phone"))
        assertFalse(on.contains("ava_shell"))
    }

    @Test fun shellTerminalIsTaughtWhenReady() {
        assertFalse(RemoteAiPrompt.build(env()).contains("ava_shell"))
        val on = RemoteAiPrompt.build(env().copy(shellTools = true))
        assertTrue(on.contains("ava_shell"))
        assertTrue(on.contains("stdout is the callback"))
        assertTrue(on.contains("virtual display"))
        assertTrue(on.contains("here_ui"))
        assertTrue(on.contains("not a spoken token"))
        assertTrue(on.contains("Not ava_phone"))
        assertFalse(on.contains("dumpsys window windows"))
        assertFalse(RemoteAiPrompt.build(env().copy(shellTools = true).forPhase(true)).contains("ava_shell"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, false, false, shell = true),
            "ava_shell",
        )!!.getString("body")
        assertTrue(guide.contains("stdout is the callback"))
        assertTrue(guide.contains("virtual display"))
        assertTrue(guide.contains("next_action"))
        assertTrue(guide.contains("here_ui"))
        assertTrue(guide.contains("not a spoken token"))
        assertFalse(guide.contains("dumpsys window windows"))
        assertNull(RemoteAiGuide.get(RemoteAiGuide.Ready(false, false, false, false, false, false), "ava_shell"))
    }

    @Test fun voiceMessageIsTextNotARecording() {
        val on = RemoteAiPrompt.build(env().copy(voiceTools = true))
        assertTrue(on.contains("ava_voice"))
        assertTrue(on.contains("you write text"))
        assertTrue(on.contains("do not splice"))
        assertTrue(on.contains("If they only said the intent, compose it"))
        assertTrue(on.contains("Only if they asked to leave a message"))
        assertTrue(on.contains("that fragment only"))
        assertTrue(on.contains("Do not use target=all"))
        assertTrue(on.contains("Wait for the tool result"))
        assertTrue(on.contains("fuzzy match on the roster"))
        assertTrue(on.contains("Do not ask which device"))
        assertTrue(on.contains("Ask which one only if nothing fits or two lines fit"))
        assertTrue(on.contains("every nearby Ava"))
        assertTrue(on.contains("is not ambiguous — act"))
        assertTrue(on.contains("Speak the name only"))
        assertTrue(on.contains("type= and model="))
        assertFalse(on.contains("名字（类型，型号）"))
        assertFalse(on.contains("Vivo"))
        assertFalse(on.contains("全屋"))
        assertFalse(on.contains("穷发"))
        assertFalse(on.contains("你的声音"))
        assertFalse(on.contains("If they did not name a device"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, false, true),
            "ava_voice",
        )!!.getString("body")
        assertTrue(guide.contains("\"text\""))
        assertTrue(guide.contains("text you write"))
        assertTrue(guide.contains("not a splice"))
        assertTrue(guide.contains("that fragment only"))
        assertTrue(guide.contains("Never target=all"))
        assertTrue(guide.contains("Only if the user asked to leave a message"))
        assertTrue(guide.contains("type= and model="))
        assertTrue(guide.contains("Speak the name only"))
        assertFalse(guide.contains("名字（类型，型号）"))
        assertTrue(guide.contains("fuzzy match on the roster"))
        assertTrue(guide.contains("Do not ask which device"))
        assertTrue(guide.contains("Do not ask after a unique hit"))
        assertFalse(guide.contains("Vivo"))
        assertFalse(guide.contains("全屋"))
        assertFalse(guide.contains("If they named nothing"))
        assertFalse(guide.contains("message_seconds"))
    }

    @Test fun voiceRosterUsesKeysNotCaption() {
        val text = RemoteAiPrompt.build(
            env().copy(
                voiceTools = true,
                fleetSelf = "客厅",
                fleetPeers = listOf("次卧  type=tablet  model=RK3588"),
            ),
        )
        assertTrue(text.contains("on the local network: 客厅"))
        assertTrue(text.contains("- 次卧  type=tablet  model=RK3588"))
        assertTrue(text.contains("Speak the name only"))
        assertFalse(text.contains("次卧（"))
        assertFalse(text.contains("名字（类型，型号）"))
    }

    @Test fun nowCarriesOwnVolumes() {
        val with = RemoteAiPrompt.build(env().copy(ttsVolume = 70, deviceVolume = 40))
        assertTrue(with.contains("tts volume: 70"))
        assertTrue(with.contains("device volume: 40"))
        assertTrue(with.contains("Current percents are in ## Now"))
        assertFalse(RemoteAiPrompt.build(env()).contains("tts volume:"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, false, false),
            "ava_volume",
        )!!.getString("body")
        assertTrue(guide.contains("## Now"))
        assertTrue(guide.contains("just to read"))
    }

    @Test fun nowCarriesOwnScreen() {
        val with = RemoteAiPrompt.build(
            env().copy(
                screenBrightness = 42,
                screenOn = true,
                micMuted = false,
                micLevel = 80,
                battery = 67,
            ),
        )
        assertTrue(with.contains("screen brightness: 42"))
        assertTrue(with.contains("screen: on"))
        assertTrue(with.contains("mic: 80"))
        assertTrue(with.contains("battery: 67"))
        assertTrue(with.contains("do not call ava_self read just for them"))
        assertFalse(RemoteAiPrompt.build(env()).contains("screen brightness:"))
        assertFalse(RemoteAiPrompt.build(env().forPhase(true).copy(screenBrightness = 42)).contains("screen brightness:"))
        val muted = RemoteAiPrompt.build(env().copy(micMuted = true, micLevel = 80))
        assertTrue(muted.contains("mic: muted"))
        assertFalse(muted.contains("mic: 80"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, true, false),
            "ava_self",
        )!!.getString("body")
        assertTrue(guide.contains("screen brightness"))
        assertTrue(guide.contains("Do not call read just for them"))
    }

    @Test fun nowCarriesPlayingTrack() {
        val with = RemoteAiPrompt.build(
            env().copy(
                musicTitle = "Song",
                musicArtist = "Band",
                musicAlbum = "LP",
                musicPlaying = true,
                musicVolume = 35,
                musicMuted = false,
            ),
        )
        assertTrue(with.contains("playing: Song — Band (LP)"))
        assertTrue(with.contains("music volume: 35"))
        assertTrue(with.contains("The current title is in ## Now"))
        assertFalse(RemoteAiPrompt.build(env()).contains("playing:"))
        assertFalse(
            RemoteAiPrompt.build(env().forPhase(true).copy(musicTitle = "Song"))
                .contains("playing:"),
        )
        val muted = RemoteAiPrompt.build(env().copy(musicTitle = "Song", musicMuted = true))
        assertTrue(muted.contains("music volume: muted"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, true, false, false, false, false),
            "ava_music",
        )!!.getString("body")
        assertTrue(guide.contains("Do not call ava_music_now just to name the song"))
    }

    @Test fun guidesDoNotHardcodeHouseNames() {
        val ready = RemoteAiGuide.Ready(true, true, true, true, true, true, true, timer = true, browserDisplay = true, shell = true)
        val bodies = listOf("query", "control", "recipes", "ava_music", "ava_self", "ava_phone", "ava_shell", "ava_voice", "ava_volume", "ava_web")
            .map { RemoteAiGuide.get(ready, it)!!.getString("body") }
            .joinToString("\n")
        assertFalse(bodies.contains("bedroom"))
        assertFalse(bodies.contains("kitchen"))
        assertFalse(bodies.contains("Queen"))
        assertFalse(bodies.contains("WeChat"))
        assertFalse(bodies.contains("Dream Clock"))
        assertFalse(bodies.contains("你的声音"))
        assertFalse(bodies.contains("全屋"))
        assertTrue(bodies.contains("<spoken"))
    }

    @Test fun flipClockTimerIsSilentUntilEnabled() {
        val off = RemoteAiPrompt.build(env())
        assertFalse(off.contains("flip-clock"))
        assertFalse(off.contains("action=timer"))
        assertFalse(off.contains("timer:"))
        assertFalse(off.contains("duration with no house"))
        assertFalse(off.contains("This device's countdown"))
        assertTrue(off.contains("unnamed clock time"))
        assertTrue(off.contains("action=alarm"))
        assertTrue(off.contains("days="))
        assertTrue(off.contains("not a clock time"))
        assertTrue(off.contains("do not ask"))
        assertFalse(off.contains("whole spoken reply"))
        assertFalse(off.contains("how much is left"))
        val on = RemoteAiPrompt.build(env().copy(timerTools = true))
        assertTrue(on.contains("ava_self action=timer"))
        assertTrue(on.contains("duration_s"))
        assertTrue(on.contains("duration with no house"))
        assertTrue(on.contains("timer-entity"))
        assertTrue(on.contains("timer: ringing"))
        assertTrue(on.contains("whatever is ringing"))
        assertTrue(on.contains("shows this countdown running"))
        assertTrue(on.contains("not a house alarm"))
        assertTrue(on.contains("whole spoken reply"))
        assertTrue(on.contains("confirm it ran"))
        assertTrue(on.contains("how much is left"))
        assertTrue(on.contains("snapshots remaining_s"))
        assertTrue(on.contains("明天定时10秒钟"))
        assertTrue(on.contains("明天12点"))
        assertFalse(on.contains("timer:"))
        val running = RemoteAiPrompt.build(env().copy(timerTools = true, timerRemainingSec = 90))
        assertTrue(running.contains("timer: 1:30"))
        val paused = RemoteAiPrompt.build(env().copy(timerTools = true, timerRemainingSec = 65, timerPaused = true))
        assertTrue(paused.contains("timer: 1:05 paused"))
        val ringing = RemoteAiPrompt.build(env().copy(timerTools = true, timerRinging = true, timerRemainingSec = 0))
        assertTrue(ringing.contains("timer: ringing"))
        val hidden = RemoteAiPrompt.build(env().forPhase(true).copy(timerTools = true, timerRemainingSec = 90))
        assertFalse(hidden.contains("flip-clock"))
        assertFalse(hidden.contains("timer:"))
        val guideOff = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, true, false),
            "ava_self",
        )!!.getString("body")
        assertFalse(guideOff.contains("flip-clock countdown"))
        val guideOn = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, true, false, timer = true),
            "ava_self",
        )!!.getString("body")
        assertTrue(guideOn.contains("flip-clock countdown"))
        assertTrue(guideOn.contains("duration_s"))
        assertTrue(guideOn.contains("duration with no house"))
        assertTrue(guideOn.contains("whatever is ringing"))
        assertTrue(guideOn.contains("shows this countdown running"))
        assertTrue(guideOn.contains("whole spoken reply"))
        assertTrue(guideOn.contains("confirm it ran"))
        assertTrue(guideOn.contains("how much is left"))
        assertTrue(guideOn.contains("snapshots remaining_s"))
        assertFalse(guideOn.contains("Dream Clock"))
        assertFalse(guideOn.contains("kitchen"))
        val toolsOff = RemoteAiGuide.get(
            RemoteAiGuide.Ready(true, false, false, false, false, false),
            "tools",
        )!!.getString("body")
        assertFalse(toolsOff.contains("This device's own countdown"))
        val toolsOn = RemoteAiGuide.get(
            RemoteAiGuide.Ready(true, false, false, false, false, false, timer = true),
            "tools",
        )!!.getString("body")
        assertTrue(toolsOn.contains("This device's own countdown"))
        assertTrue(toolsOn.contains("ava_self action=timer"))
        val recipesOff = RemoteAiGuide.get(
            RemoteAiGuide.Ready(true, false, false, false, false, false),
            "recipes",
        )!!.getString("body")
        assertFalse(recipesOff.contains("duration with no house"))
        val recipesOn = RemoteAiGuide.get(
            RemoteAiGuide.Ready(true, false, false, false, false, false, timer = true),
            "recipes",
        )!!.getString("body")
        assertTrue(recipesOn.contains("duration with no house"))
        assertTrue(recipesOn.contains("ava_self action=timer"))
        assertTrue(recipesOn.contains("Several on a named device"))
        assertTrue(recipesOn.contains("skip unread"))
        assertFalse(recipesOn.contains("kitchen"))
        val queryOn = RemoteAiGuide.get(
            RemoteAiGuide.Ready(true, false, false, false, false, false),
            "query",
        )!!.getString("body")
        assertTrue(queryOn.contains("{\"domain\":\"light\"}"))
        assertTrue(queryOn.contains("do not ha_state each hit"))
    }

    @Test fun browserDisplayIsSilentUntilTheFeatureExists() {
        val off = RemoteAiPrompt.build(env())
        assertFalse(off.contains("browser display:"))
        assertFalse(off.contains("target=browser_display"))
        assertTrue(off.contains("research page"))
        val on = RemoteAiPrompt.build(env().copy(browserDisplay = true))
        assertTrue(on.contains("browser display: on"))
        assertTrue(on.contains("target=browser_display"))
        assertTrue(on.contains("already matches"))
        assertTrue(on.contains("not the browser display overlay"))
        val hidden = RemoteAiPrompt.build(env().copy(browserDisplay = false))
        assertTrue(hidden.contains("browser display: off"))
        val phase = RemoteAiPrompt.build(env().forPhase(true).copy(browserDisplay = true))
        assertFalse(phase.contains("browser display:"))
        assertFalse(phase.contains("target=browser_display"))
        val guideOff = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, true, false),
            "ava_self",
        )!!.getString("body")
        assertFalse(guideOff.contains("target=browser_display"))
        val guideOn = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, true, true, false, browserDisplay = true),
            "ava_self",
        )!!.getString("body")
        assertTrue(guideOn.contains("target=browser_display"))
        assertTrue(guideOn.contains("ava_web"))
        val webOn = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, true, false, false, browserDisplay = true),
            "ava_web",
        )!!.getString("body")
        assertTrue(webOn.contains("research page"))
        assertTrue(webOn.contains("ava_self"))
        assertFalse(webOn.contains("Dream Clock"))
        assertFalse(webOn.contains("kitchen"))
    }

    @Test fun homeAssistantPageIsRoutedSeparatelyFromResearch() {
        val off = RemoteAiPrompt.build(env())
        assertFalse(off.contains("ava_page_read"))
        val on = RemoteAiPrompt.build(env().copy(pageTools = true, browserDisplay = true))
        assertTrue(on.contains("ava_page_read"))
        assertTrue(on.contains("ava_page_act"))
        assertTrue(on.contains("Looking at or changing the open page is ava_page"))
        assertTrue(on.contains("not the research page"))
        val phase = RemoteAiPrompt.build(env().copy(pageTools = true).forPhase(true))
        assertFalse(phase.contains("ava_page_read"))
        val guide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, false, false, page = true),
            "ava_page",
        )!!
        assertTrue(guide.getString("body").contains("browser display overlay"))
        assertTrue(guide.getString("body").contains("ava_page_act"))
        assertTrue(on.contains("path=/config/dashboard"))
        assertTrue(on.contains("path=/profile"))
        assertTrue(on.contains("Never domain=frontend"))
        assertTrue(on.contains("goto"))
        assertTrue(on.contains("official HA paths"))
        assertTrue(on.contains("dropdowns[]"))
        assertTrue(guide.getString("body").contains("dropdowns[]"))
        assertTrue(guide.getString("body").contains("other sites"))
        assertTrue(on.contains("opened_overlays"))
        assertTrue(on.contains("设置 alone"))
        assertTrue(on.contains("no clock time, no task"))
        assertTrue(on.contains("HA设置 opens the overlay"))
        val overlayOn = RemoteAiPrompt.build(env().copy(pageTools = true, haPageOpen = true))
        assertTrue(overlayOn.contains("设置 alone"))
        assertTrue(overlayOn.contains("HA path=/config/dashboard"))
        assertTrue(overlayOn.contains("Ava设置 is still ava_self action=settings"))
        assertTrue(guide.getString("body").contains("opened_overlays"))
        val selfGuide = RemoteAiGuide.get(
            RemoteAiGuide.Ready(false, false, false, false, true, false),
            "ava_self",
        )!!.getString("body")
        assertTrue(selfGuide.contains("action=settings"))
        assertTrue(selfGuide.contains("action=mods"))
        assertTrue(selfGuide.contains("live on/off"))
        assertTrue(selfGuide.contains("no screenshot"))
        assertTrue(selfGuide.contains("visible[]"))
        assertTrue(selfGuide.contains("opened_overlays"))
        assertTrue(selfGuide.contains("restores origin"))
        assertTrue(selfGuide.contains("gates[]"))
        assertTrue(selfGuide.contains("here/path/crumbs"))
        assertTrue(selfGuide.contains("here_ui"))
        assertTrue(guide.getString("body").contains("here_ui"))
        assertNotNull(RemoteAiHaTools.pageUiServiceMessage("frontend", "set_theme", ""))
        assertNotNull(RemoteAiHaTools.pageUiServiceMessage("light", "turn_on", "frontend"))
        assertNull(RemoteAiHaTools.pageUiServiceMessage("media_player", "media_pause", "客厅音箱"))
        assertTrue(guide.getString("body").contains("path=/profile"))
    }

    @Test fun guideIsOneCallOnAVoiceTurn() {
        val text = RemoteAiPrompt.build(env())
        assertTrue(text.contains("one call: get"))
        assertTrue(text.contains("search if you do not"))
        assertTrue(text.contains("Do not call overview first"))
        assertFalse(text.contains("Call overview for the section names, then get"))
        val ready = RemoteAiGuide.Ready(true, false, false, false, false, false)
        val index = RemoteAiGuide.overview(ready).getJSONArray("docs")
        var recipesUse = ""
        for (i in 0 until index.length()) {
            val doc = index.getJSONObject(i)
            assertTrue(doc.getString("use").isNotBlank())
            if (doc.getString("name") == "recipes") recipesUse = doc.getString("use")
        }
        assertEquals("which call next when stuck", recipesUse)
        val query = RemoteAiGuide.get(ready, "camera")!!
        assertEquals("query", query.getString("name"))
        val one = RemoteAiGuide.search(ready, "playbook", 3)
        assertEquals(1, one.length())
        assertTrue(one.getJSONObject(0).has("body"))
        assertTrue(one.getJSONObject(0).getString("body").contains("voice turn"))
        val many = RemoteAiGuide.search(ready, "ha_search", 8)
        assertTrue(many.length() > 1)
        assertFalse(many.getJSONObject(0).has("body"))
    }

    @Test fun obsoleteOneCallAndMandatoryHideRulesAreGone() {
        val text = RemoteAiPrompt.build(env())
        assertFalse(text.contains("one tool call, then speak"))
        assertFalse(text.contains("Call ava_web_hide before you speak"))
        assertTrue(text.contains("keep_visible"))
        val guide = RemoteAiGuide.get(RemoteAiGuide.Ready(false, false, false, true, false, false), "ava_web")!!.getString("body")
        assertTrue(guide.contains("copy takes exactly one of ref or text"))
        assertFalse(guide.contains("A raw url is hide then show"))
    }

    @Test fun houseRoutingUsesMiniCameraSnapshot() {
        val text = RemoteAiPrompt.build(env())
        assertTrue(text.contains("ha_camera_snapshot"))
        assertTrue(text.contains("Never ha_turn_on/off a camera"))
        assertTrue(text.contains("current values (brightness, temperature, fan, swing, position, tilt, volume, mute, source, color, effect"))
        assertTrue(text.contains("not the HA attribute bag"))
        val guide = RemoteAiGuide.get(RemoteAiGuide.Ready(true, false, false, false, false, false), "query")!!.getString("body")
        assertTrue(guide.contains("ha_camera_snapshot"))
        assertTrue(guide.contains("<spoken>"))
    }
}
