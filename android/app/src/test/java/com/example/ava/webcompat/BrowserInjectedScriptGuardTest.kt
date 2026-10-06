package com.example.ava.webcompat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay injects these scripts into every Home Assistant dashboard, where they run for the
 * lifetime of a wall tablet. The guards asserted here are the difference between "idle" and
 * "burning the main thread forever", and nothing else in the build can see inside the JS, so pin
 * them down. Behaviour (as opposed to shape) is covered by
 * `node tools/webviewjs/injected-scripts-probe.js --assert`.
 */
class BrowserInjectedScriptGuardTest {

    private val kioskJs: String =
        BrowserHaKioskScripts.applyScript(apply = true, hideHeader = true, hideSidebar = true)

    @Test
    fun kioskCssIsNotRewrittenWhenUnchanged() {
        val unconditionalWrite = kioskJs.lineSequence().any { it.trim() == "el.textContent = css;" }
        assertFalse(
            "styleInto must compare before writing: rewriting the <style> reparses the sheet " +
                "and invalidates the whole shadow root on every observed mutation",
            unconditionalWrite,
        )
        assertTrue(kioskJs.contains("if (el.textContent !== css)"))
    }

    @Test
    fun kioskMutationBurstsAreCoalesced() {
        assertFalse(
            "the MutationObserver watches HA's whole app subtree; calling applyNow() straight " +
                "from the callback runs it once per mutation batch",
            kioskJs.contains("new MutationObserver(function() { applyNow(); })"),
        )
        assertTrue(kioskJs.contains("new MutationObserver(scheduleApply)"))
        assertTrue(kioskJs.contains("requestAnimationFrame(run)"))
    }

    @Test
    fun darkModeBackstopPollBacksOff() {
        val js = BrowserDarkModeScripts.installHomeAssistantThemeListenerJs
        val foreverAtOneHz = js.lineSequence().any { it.trim() == "}, 1000);" }
        assertFalse(
            "the darkMode backstop must not be a permanent 1 Hz interval — it keeps waking the " +
                "page in every pane for as long as the dashboard is open",
            foreverAtOneHz,
        )
        assertTrue(js.contains("POLL_MAX_MS"))
        assertTrue("the poll must reset its cadence after a change", js.contains("pollDelay = POLL_MIN_MS"))
        assertTrue("a hidden pane should not poll at full rate", js.contains("document.hidden"))
    }

    @Test
    fun darkModeChangesAreEventDriven() {
        val js = BrowserDarkModeScripts.installHomeAssistantThemeListenerJs
        assertTrue(
            "HA applies every theme change with applyThemesOnElement(document.documentElement, " +
                "...), so the <html> style attribute is the signal — without it the backstop " +
                "poll's cadence becomes the detection latency",
            js.contains("attributeFilter: ['style']"),
        )
        assertTrue(js.contains(".observe(document.documentElement"))
    }

    @Test
    fun themePushRetiresOlderWaves() {
        listOf(
            BrowserDarkModeScripts.homeAssistantDarkModeJs,
            BrowserDarkModeScripts.homeAssistantLightModeJs,
        ).forEach { js ->
            assertTrue(
                "each push must claim a token, or two waves keep dispatching settheme with " +
                    "opposite dark values for 30s and the dashboard flips back and forth",
                js.contains("window.__avaThemeApplyToken = applyToken"),
            )
            assertTrue(js.contains("if (window.__avaThemeApplyTimer)"))
            assertTrue(js.contains("if (themeWaveRetired())"))
        }
    }

    @Test
    fun themePushStaysInPageAndLeavesTheUserThemeAlone() {
        listOf(
            BrowserDarkModeScripts.homeAssistantDarkModeJs,
            BrowserDarkModeScripts.homeAssistantLightModeJs,
        ).forEach { js ->
            assertFalse("theme flip must not reload the dashboard", js.contains("location.reload"))
            assertFalse("theme flip must not fire location-changed", js.contains("location-changed"))
            assertFalse(js.contains("history.go"))
            assertFalse(js.contains("hardReload"))
            assertFalse(
                "an opaque #view paint sits above hui-view-background (z-index:-1) and hides " +
                    "the user's wallpaper; HA owns <html>/body background, Ava paints nothing",
                js.contains("ava-theme-shell") || js.contains("#view") || js.contains("!important"),
            )
            assertFalse(
                "selectedTheme.theme is the user's choice; \"\" means backend-selected and " +
                    "frontend.set_theme relies on it — never fill it in",
                js.contains("sessionSelected.theme =") || js.contains("selectedTheme.theme ="),
            )
            assertTrue(
                "settheme must carry only dark, so HA merges it into the user's own selectedTheme",
                js.contains("detail: { dark: desiredDark }"),
            )
            assertTrue(
                "ha-theme-settings only offers Auto/Light/Dark when both modes exist; " +
                    "a one-mode theme must be left alone",
                js.contains("'light' in theme.modes && 'dark' in theme.modes"),
            )
            assertTrue(js.contains("if (!canSwitchMode(selected, themes))"))
            assertTrue(
                "backend-selected with both default_theme and default_dark_theme is switchable",
                js.contains("useDefault && themes.default_dark_theme && themes.default_theme"),
            )
            assertTrue(
                "fallback _applyTheme takes prefers-color-scheme, not Ava's desired mode",
                js.contains("ha._applyTheme(prefersColorSchemeDark())"),
            )
            assertFalse(js.contains("ha._applyTheme(desiredDark)"))
            assertTrue(
                "earlyDarkPaintJs leaves inline body colors HA never clears — that was the strip",
                js.contains("document.body.style.removeProperty('background-color')"),
            )
            assertTrue(
                "the profile save is suppressed so the sync stays session-only",
                js.contains("frontend/set_user_data"),
            )
        }
    }

    @Test
    fun themePushAlwaysReleasesTheSaveSuppressor() {
        val js = BrowserDarkModeScripts.homeAssistantDarkModeJs
        val suppressorRelease = js.indexOf("window.__avaSuppressThemePreferenceSave = false;")
        val retiredBailout = js.indexOf("if (themeWaveRetired()) return;")
        assertTrue(suppressorRelease > 0)
        assertTrue(
            "bailing out before releasing the suppressor would swallow the user's own theme saves",
            retiredBailout > suppressorRelease,
        )
    }

    @Test
    fun masterSwitchAloneDoesNotNeedDocumentStart() {
        assertFalse(
            "master-only must not wrap hassConnection or walk card shadows",
            BrowserWsStewardScripts.needsDocumentStart(
                stewardEnabled = true,
                streamEnabled = false,
                chunkEnabled = false,
            ),
        )
        assertTrue(
            BrowserWsStewardScripts.needsDocumentStart(
                stewardEnabled = true,
                streamEnabled = true,
                chunkEnabled = false,
            ),
        )
        assertTrue(
            BrowserWsStewardScripts.needsDocumentStart(
                stewardEnabled = true,
                streamEnabled = false,
                chunkEnabled = true,
            ),
        )
        assertFalse(
            BrowserWsStewardScripts.needsDocumentStart(
                stewardEnabled = false,
                streamEnabled = true,
                chunkEnabled = true,
            ),
        )
        val boot = BrowserWsStewardScripts.bootJs
        assertTrue(
            "card hide-scrollbar is late-loaded, not written during first measure",
            boot.contains("hideSbAllowed"),
        )
        assertTrue(boot.contains("window.__avaHideSbCards"))
        assertTrue(boot.contains("if (hideSbAllowed(node)) hideSbInRoot"))
    }

    @Test
    fun firstPaintDoesNotDeferEntityUpdates() {
        val boot = BrowserWsStewardScripts.bootJs
        assertTrue(
            "Lovelace first paint emits layout scroll; without a grace the gate queues " +
                "subscribe_entities and native cards (calendar, gauge) stay half-drawn",
            boot.contains("inFirstPaint"),
        )
        assertTrue(boot.contains("armFirstPaint"))
        assertTrue(
            "mark() during first paint must be a no-op, or the latch survives the grace",
            boot.contains("if (this.disabled || this.inFirstPaint()) return;"),
        )
        val streamOn = BrowserWsStewardScripts.streamOnJs
        assertTrue(
            "setStream(true) is what Kotlin fires after HTML finishes — that is still " +
                "too early for Lovelace, so it must start the first-paint grace",
            streamOn.contains("armFirstPaint"),
        )
    }

    @Test
    fun entityTrimExposesDashboardSnapshotAndHaConsole() {
        val boot = BrowserWsStewardScripts.bootJs
        assertTrue(boot.contains("window.__avaCollectCurrentPageEntityIds"))
        assertTrue(boot.contains("writeSnapshot"))
        assertTrue(
            "trim snapshot must tell the in-HA console how many ids are live",
            boot.contains("watching"),
        )
        val setTrim = BrowserWsStewardScripts.trimOnJs
        assertTrue(setTrim.contains("__avaStewardConsole"))
        val console = BrowserWsStewardConsole.installJs
        assertTrue(console.contains("dashboardmenu"))
        assertTrue(console.contains("ha-dropdown-item"))
        assertTrue(console.contains("ui.panel.lovelace.menu.add"))
        assertTrue(console.contains("ava-steward-menu"))
        assertTrue(console.contains("hideMenu") && console.contains("overflowIsOpen"))
        assertTrue(console.contains("subscribed"))
        assertTrue(console.contains("订阅"))
        assertTrue(!console.contains("正在监视") && !console.contains("正在套用"))
        assertTrue(!console.contains("Watching") && !console.contains("Applying"))
        assertTrue(!console.contains("injectNav") && !console.contains("ha-list-item-button"))
        assertTrue(console.contains("action-items"))
        assertTrue(
            "click must not raise HA's label tooltip",
            !console.contains("ha-tooltip") && console.contains("ava-sc-iconbtn"),
        )
        assertTrue(console.contains("mask-image") && console.contains("webkitMaskImage"))
        assertTrue(console.contains("touch-action:pan-y"))
        assertTrue(console.contains("hydrateSnapshot"))
        assertTrue(console.contains("ava-sc-fit-compact"))
        assertTrue(console.contains("fitScreen"))
        assertTrue(!console.contains("Filter state machine") && !console.contains("过滤状态机"))
        assertTrue(!console.contains("ava-sc-svg"))
        assertTrue(console.contains("__avaHost"))
        assertTrue(console.contains("ava-sc-x"))
        assertTrue(console.contains("navigator.userAgent"))
        assertTrue(console.contains("position:absolute") && console.contains("top:8px;right:8px"))
        assertTrue(console.contains("ava-sc-health") && console.contains("ava-sc-spark"))
        assertTrue(console.contains("requestAnimationFrame") && console.contains("usedJSHeapSize"))
        assertTrue(console.contains("健康侦测") || console.contains("'Health'"))
        assertTrue(
            "landscape must keep an equal 2x2 health grid, not stack to one column",
            console.contains("ava-sc-fit-land") &&
                !console.contains(".ava-sc-fit-short .ava-sc-health{grid-template-columns:1fr;}"),
        )
        assertTrue(
            "landscape spark must be a fixed 40px, not the 72px portrait height",
            console.contains(".ava-sc-fit-land .ava-sc-spark{height:40px;"),
        )
        assertTrue(
            "frame spark plots at ~8 Hz locked to display frames, not every rAF",
            console.contains("Math.round(hz / 8)") && console.contains("sampleMs()"),
        )
        assertTrue(!console.contains("dpsNote") && !console.contains("tickDps"))
        assertTrue(!console.contains("ava-sc-diag") && !console.contains("ava-sc-caps"))
        assertTrue(!console.contains("滚动门") && !console.contains("scroll gate"))
        assertTrue(!console.contains("滑动时暂缓") && !console.contains("Paused while scrolling"))
        assertTrue(!console.contains("配置 {n}") && !console.contains("Config {n}"))
        assertTrue(!console.contains("已分析本页 Lovelace 配置与已渲染卡片"))
        assertTrue(!console.contains("cpuMap") && !console.contains("CPU 映射"))
        assertTrue(!console.contains("WebView runtime") && !console.contains("WebView 运行时"))
        assertTrue(console.contains("buildSnapshot(null)"))
        assertTrue(boot.contains("__avaNoteEntityEvent"))
        assertTrue(boot.contains("__avaStewardMetrics"))
        assertTrue(BrowserWsStewardConsole.removeJs.contains("setEnabled(false)"))
    }
}
