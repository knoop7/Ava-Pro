package com.example.ava.webcompat

/**
 * Home Assistant frontend theme scripts.
 *
 * Scope: make the dashboard *in this Ava pane* follow Ava's light/dark for the
 * current session. Nothing here may redefine what the user chose in HA:
 *
 *  - `selectedTheme.theme` is never written. `""` means "backend-selected" and
 *    `frontend.set_theme` automations rely on it staying empty.
 *  - `settheme` is only fired when the active theme can actually switch, using
 *    the same rules as HA `themes-mixin._applyTheme` /
 *    `ha-theme-settings._supportsModeSelection`. A one-mode theme is left alone.
 *  - `frontend/set_user_data` for `theme` is swallowed while our `settheme`
 *    runs and `localStorage.selectedTheme` is restored, so the sync is
 *    session-only and the user's profile is untouched.
 *  - No shell paint. HA owns `<html>` inline background and `body` uses
 *    `var(--primary-background-color)`. Painting `#view` covered the
 *    `hui-view-background` wallpaper layer (z-index:-1 behind an in-flow box).
 *
 * Light↔dark must stay in-page: never reload, never `location-changed`.
 */
object BrowserDarkModeScripts {
    const val JS_BRIDGE_NAME = "AvaDarkModeBridge"
    const val DARK_MODE_NAV_SCHEME = "ava-darkmode:"

    val homeAssistantDarkModeJs: String = buildSessionThemeScript("true")

    val homeAssistantLightModeJs: String = buildSessionThemeScript("false")

    /**
     * Early paint only — does not call HA `settheme` / touch preferences.
     * Covers the blank document between navigation start and the existing
     * session theme sync so refresh does not flash a white page. The session
     * script clears these inline values once HA has applied its theme.
     */
    val earlyDarkPaintJs: String = """
        (function() {
            try {
                document.documentElement.style.colorScheme = 'dark';
                document.documentElement.style.backgroundColor = '#111111';
                if (document.body) {
                    document.body.style.colorScheme = 'dark';
                    document.body.style.backgroundColor = '#111111';
                }
                var meta = document.querySelector('meta[name="color-scheme"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'color-scheme';
                    (document.head || document.documentElement).appendChild(meta);
                }
                meta.content = 'dark';
            } catch (e) {}
        })();
    """.trimIndent()

    val readHomeAssistantDarkModeJs: String = """
        (function() {
            var ha = document.querySelector('home-assistant');
            if (!ha || !ha.hass || !ha.hass.themes) return null;
            return ha.hass.themes.darkMode ? 'true' : 'false';
        })();
    """.trimIndent()

    val installHomeAssistantThemeListenerJs: String = """
        (function() {
            if (window.__avaHaThemeListener) return;
            window.__avaHaThemeListener = true;

            function readDarkMode() {
                var ha = document.querySelector('home-assistant');
                if (!ha || !ha.hass || !ha.hass.themes) return null;
                if (ha.hass.selectedTheme && typeof ha.hass.selectedTheme.dark === 'boolean') {
                    return null;
                }
                return !!ha.hass.themes.darkMode;
            }

            function notifyNative(dark) {
                if (window.$JS_BRIDGE_NAME && window.$JS_BRIDGE_NAME.onHaDarkModeChanged) {
                    window.$JS_BRIDGE_NAME.onHaDarkModeChanged(dark);
                    return;
                }
                // Never assign window.location. Dual-pane right tiles used to lack the
                // bridge and the fallback navigated the dashboard to ava-darkmode:1
                // (net::ERR_UNSAFE_PORT), then polluted the HA URL entity.
            }

            function hook() {
                var ha = document.querySelector('home-assistant');
                if (!ha || !ha.hass) return false;
                var last = readDarkMode();
                if (last === null) return false;

                // themes_updated carries theme edits; the poll below is only a backstop for
                // darkMode flips that arrive without an event (profile toggle). It used to run at
                // 1 Hz forever in every pane, which keeps waking the page on an idle dashboard, so
                // it backs off while nothing changes and snaps back when something does.
                var POLL_MIN_MS = 1000;
                var POLL_MAX_MS = 10000;
                var pollDelay = POLL_MIN_MS;
                var pollTimer = null;

                function schedulePoll(ms) {
                    if (pollTimer) clearTimeout(pollTimer);
                    pollTimer = setTimeout(pollOnce, ms);
                }

                function pollOnce() {
                    pollTimer = null;
                    if (document.hidden) {
                        schedulePoll(POLL_MAX_MS);
                        return;
                    }
                    var cur = readDarkMode();
                    if (cur !== null && cur !== last) {
                        last = cur;
                        pollDelay = POLL_MIN_MS;
                        notifyNative(cur);
                    } else {
                        pollDelay = Math.min(pollDelay * 2, POLL_MAX_MS);
                    }
                    schedulePoll(pollDelay);
                }

                var conn = ha.hass.connection;
                if (conn && conn.subscribeEvents) {
                    conn.subscribeEvents(function() {
                        var cur = readDarkMode();
                        if (cur !== null && cur !== last) {
                            last = cur;
                            pollDelay = POLL_MIN_MS;
                            notifyNative(cur);
                        }
                    }, 'themes_updated').catch(function() {});
                }

                // HA applies every theme and darkMode change through
                // applyThemesOnElement(document.documentElement, ...), which rewrites the <html>
                // style attribute. Watching that attribute detects a flip on the same frame it
                // happens and costs nothing while idle, so the poll above stays a pure safety net.
                if (typeof MutationObserver === 'function') {
                    var readQueued = false;
                    new MutationObserver(function() {
                        if (readQueued) return;
                        readQueued = true;
                        setTimeout(function() {
                            readQueued = false;
                            var cur = readDarkMode();
                            if (cur !== null && cur !== last) {
                                last = cur;
                                pollDelay = POLL_MIN_MS;
                                notifyNative(cur);
                            }
                        }, 0);
                    }).observe(document.documentElement, {
                        attributes: true,
                        attributeFilter: ['style']
                    });
                }

                schedulePoll(POLL_MIN_MS);
                document.addEventListener('visibilitychange', function() {
                    if (document.hidden) return;
                    pollDelay = POLL_MIN_MS;
                    schedulePoll(POLL_MIN_MS);
                });
                return true;
            }

            if (!hook()) {
                var retries = 0;
                var interval = setInterval(function() {
                    retries++;
                    if (hook() || retries > 20) clearInterval(interval);
                }, 500);
            }
        })();
    """.trimIndent()

    private fun buildSessionThemeScript(darkLiteral: String): String = """
        (function() {
            var desiredDark = $darkLiteral;

            // Every native push starts its own retry wave. Without a token the waves stack:
            // each one keeps dispatching `settheme` for up to 30s, and two waves with different
            // desiredDark flip the dashboard back and forth while both are alive.
            var applyToken = (window.__avaThemeApplyToken || 0) + 1;
            window.__avaThemeApplyToken = applyToken;
            if (window.__avaThemeApplyTimer) {
                clearInterval(window.__avaThemeApplyTimer);
                window.__avaThemeApplyTimer = null;
            }
            function themeWaveRetired() {
                return window.__avaThemeApplyToken !== applyToken;
            }

            function prefersColorSchemeDark() {
                try {
                    return !!(window.matchMedia &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                } catch (e) {
                    return false;
                }
            }

            // Same name HA resolves in themes-mixin._applyTheme. An empty
            // selectedTheme.theme means "backend-selected" — do not fill it in.
            function activeThemeName(selected, themes) {
                if (selected && selected.theme) return selected.theme;
                if (desiredDark && themes.default_dark_theme) return themes.default_dark_theme;
                return themes.default_theme;
            }

            // ha-theme-settings only offers Auto/Light/Dark when this is true. If HA
            // does not let the user switch this theme, Ava must not either.
            function canSwitchMode(selected, themes) {
                var useDefault = !(selected && selected.theme);
                if (useDefault && themes.default_dark_theme && themes.default_theme) return true;
                var name = activeThemeName(selected, themes);
                if (!name || name === 'default') return true;
                var theme = themes.themes && themes.themes[name];
                if (!theme) return true;
                return !!(theme.modes && 'light' in theme.modes && 'dark' in theme.modes);
            }

            // earlyDarkPaintJs writes inline colors that HA never clears: HA only
            // resets <html> background in _applyTheme; body relies on
            // `body { background-color: var(--primary-background-color) }`.
            // Left in place, body kept the previous mode's color (the old strip).
            function clearEarlyPaint() {
                try {
                    document.documentElement.style.removeProperty('color-scheme');
                    if (document.body) {
                        document.body.style.removeProperty('background-color');
                        document.body.style.removeProperty('color-scheme');
                    }
                } catch (e) {}
            }

            function installThemeSaveSuppressor(hass) {
                var conn = hass && hass.connection;
                if (!conn || typeof conn.sendMessagePromise !== 'function') return false;
                if (conn.__avaThemeSaveSuppressorInstalled) return true;

                var originalSendMessagePromise = conn.sendMessagePromise;
                conn.sendMessagePromise = function(message) {
                    if (
                        window.__avaSuppressThemePreferenceSave &&
                        message &&
                        message.type === 'frontend/set_user_data' &&
                        message.key === 'theme'
                    ) {
                        return Promise.resolve(undefined);
                    }
                    return originalSendMessagePromise.apply(this, arguments);
                };
                conn.__avaThemeSaveSuppressorInstalled = true;
                return true;
            }

            function readStoredSelectedTheme() {
                try {
                    return {
                        exists: window.localStorage.getItem('selectedTheme') !== null,
                        value: window.localStorage.getItem('selectedTheme')
                    };
                } catch (e) {
                    return { exists: false, value: null };
                }
            }

            function restoreStoredSelectedTheme(stored) {
                try {
                    if (stored.exists) {
                        window.localStorage.setItem('selectedTheme', stored.value);
                    } else {
                        window.localStorage.removeItem('selectedTheme');
                    }
                } catch (e) {}
            }

            // Same event the profile page fires. HA merges detail into selectedTheme,
            // re-runs _applyTheme(mql.matches), then storeState + saveThemePreferences —
            // the last two are what the suppressor / localStorage restore undo.
            function dispatchSessionSetTheme(ha, hass) {
                if (!installThemeSaveSuppressor(hass)) return false;

                var storedSelectedTheme = readStoredSelectedTheme();
                window.__avaSuppressThemePreferenceSave = true;
                ha.dispatchEvent(new CustomEvent('settheme', {
                    detail: { dark: desiredDark },
                    bubbles: true,
                    composed: true
                }));

                setTimeout(function() {
                    // Always restore storage / release the suppressor, or a retired
                    // wave would swallow the user's own later theme saves.
                    restoreStoredSelectedTheme(storedSelectedTheme);
                    window.__avaSuppressThemePreferenceSave = false;
                    if (themeWaveRetired()) return;
                    clearEarlyPaint();
                }, 0);

                return true;
            }

            // No connection to guard: change the session object only and let HA
            // repaint. Never fall back to an unguarded settheme — it would persist.
            function applySessionOnly(ha, hass) {
                if (typeof ha._updateHass !== 'function') return;
                var sessionSelected = {};
                var selected = hass.selectedTheme || {};
                for (var key in selected) {
                    if (Object.prototype.hasOwnProperty.call(selected, key)) {
                        sessionSelected[key] = selected[key];
                    }
                }
                sessionSelected.dark = desiredDark;
                ha._updateHass({ selectedTheme: sessionSelected });
                if (typeof ha._applyTheme === 'function') {
                    try {
                        ha._applyTheme(prefersColorSchemeDark());
                    } catch (e) {}
                }
                clearEarlyPaint();
            }

            function applyThemeMode() {
                var ha = document.querySelector('home-assistant');
                if (!ha || !ha.hass || !ha.hass.themes) {
                    return false;
                }
                var hass = ha.hass;
                var themes = hass.themes;
                var selected = hass.selectedTheme || {};

                if (!canSwitchMode(selected, themes)) {
                    // One-mode theme: HA pins it regardless of dark. Leave the
                    // user's theme exactly as configured.
                    clearEarlyPaint();
                    return true;
                }

                var alreadyThere = selected.dark === desiredDark ||
                    (selected.dark === undefined && themes.darkMode === desiredDark);
                if (alreadyThere) {
                    clearEarlyPaint();
                    return true;
                }

                if (!dispatchSessionSetTheme(ha, hass)) {
                    applySessionOnly(ha, hass);
                }

                // HA handles settheme synchronously: selectedTheme.dark is the
                // confirmation. themes.darkMode may legitimately differ (HA clamps it
                // when the backend dark theme has no modes), so do not gate on it.
                var after = ha.hass && ha.hass.selectedTheme;
                return !!(after && after.dark === desiredDark);
            }

            var retries = 0;
            var interval = setInterval(function() {
                if (themeWaveRetired()) {
                    clearInterval(interval);
                    return;
                }
                retries++;
                if (applyThemeMode() || retries > 60) {
                    clearInterval(interval);
                    if (window.__avaThemeApplyTimer === interval) {
                        window.__avaThemeApplyTimer = null;
                    }
                }
            }, 500);
            window.__avaThemeApplyTimer = interval;
            if (applyThemeMode()) {
                clearInterval(interval);
                window.__avaThemeApplyTimer = null;
            }
        })();
    """.trimIndent()
}
