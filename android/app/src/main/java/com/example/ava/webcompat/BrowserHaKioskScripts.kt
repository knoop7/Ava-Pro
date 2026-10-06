package com.example.ava.webcompat

import android.net.Uri
import com.example.ava.settings.BrowserSettings

/**
 * Hide Home Assistant's header and/or sidebar for wall-tablet kiosk use.
 *
 * Strategies (stored in [BrowserSettings.haKioskMode]):
 * - `off` — normal HA chrome
 * - `css` / `auto` — pierce open shadow roots and inject hide styles
 * - `plugin` — HACS [kiosk-mode](https://github.com/NemesisRE/kiosk-mode)
 *   query params (`?kiosk` / `hide_header` / `hide_sidebar`) only; inert
 *   without the plugin
 *
 * `auto` used to send BOTH (CSS + plugin params). With kiosk-mode installed
 * that hid the header through two actors at once, and the sidebar drawer
 * toggle could only retract ours: toggling "expand" cleared the CSS live
 * while `?kiosk` stayed in the current URL, so the plugin kept the header
 * hidden and the toggle looked dead. One toggle, one injector: Ava drives
 * chrome via CSS unless the user explicitly picks `plugin`.
 *
 * Behaviour mirrors the common HA-kiosk approach used by wall browsers
 * (e.g. [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) drawer toggle).
 */
object BrowserHaKioskScripts {

    private val KIOSK_QUERY_KEYS = setOf("kiosk", "hide_header", "hide_sidebar")

    fun usesCss(mode: String): Boolean =
        mode == "auto" || mode == "css"

    fun usesPluginParams(mode: String): Boolean =
        mode == "plugin"

    fun isEnabled(mode: String): Boolean =
        mode != "off" && mode.isNotBlank()

    /**
     * Ava kiosk off / CSS-only: leave the URL alone so a user-written
     * `?kiosk` / `hide_header` / `hide_sidebar` still reaches HACS kiosk-mode.
     * Ava `auto` / `plugin`: replace those keys with the current settings.
     */
    fun resolveLoadUrl(url: String, settings: BrowserSettings): String {
        if (url.isBlank() || url.startsWith("about:")) return url
        if (!isEnabled(settings.haKioskMode) || !usesPluginParams(settings.haKioskMode)) {
            return url
        }
        return withKioskParams(
            stripKioskParams(url),
            hideHeader = settings.haKioskHideHeader,
            hideSidebar = settings.haKioskHideSidebar,
        )
    }

    /** True when the URL itself carries kiosk-mode plugin params (user-authored intent). */
    fun hasKioskParams(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = Uri.parse(url)
            !uri.isOpaque && uri.queryParameterNames.any { it in KIOSK_QUERY_KEYS }
        } catch (_: Exception) {
            false
        }
    }

    fun stripKioskParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            if (uri.isOpaque) return url
            val kept = uri.queryParameterNames
                .filter { it !in KIOSK_QUERY_KEYS }
                .associateWith { key -> uri.getQueryParameters(key) }
            if (kept.size == uri.queryParameterNames.size) return url
            val b = uri.buildUpon().clearQuery()
            for ((key, values) in kept) {
                for (v in values) b.appendQueryParameter(key, v)
            }
            b.build().toString()
        } catch (_: Exception) {
            url
        }
    }

    fun withKioskParams(
        url: String,
        hideHeader: Boolean,
        hideSidebar: Boolean,
    ): String {
        if (!hideHeader && !hideSidebar) return url
        return try {
            val uri = Uri.parse(url)
            if (uri.isOpaque) return url
            val param = when {
                hideHeader && hideSidebar -> "kiosk"
                hideHeader -> "hide_header"
                else -> "hide_sidebar"
            }
            if (uri.queryParameterNames.contains(param)) return url
            uri.buildUpon().appendQueryParameter(param, "").build().toString()
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Strip kiosk-mode query params from the LIVE document (no reload) and
     * fire `location-changed` so the HACS plugin re-evaluates and releases
     * the chrome it hid. Needed for pages loaded while `auto` still sent
     * plugin params: our CSS toggles off live, but the plugin only lets go
     * once the param is gone.
     */
    val stripKioskParamsLiveJs: String = """
        (function() {
          try {
            var l = location;
            if (!/[?&](kiosk|hide_header|hide_sidebar)(=|&|${'$'})/.test(l.search)) return;
            var kept = l.search.replace(/^\?/, '').split('&').filter(function(p) {
              var k = p.split('=')[0];
              return k !== 'kiosk' && k !== 'hide_header' && k !== 'hide_sidebar' && k !== '';
            }).join('&');
            history.replaceState(history.state, '',
              l.pathname + (kept ? '?' + kept : '') + l.hash);
            window.dispatchEvent(new Event('location-changed'));
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Inject / tear down CSS inside HA open shadow roots.
     * Idempotent; re-applies on `location-changed` and a light MutationObserver.
     */
    fun applyScript(
        apply: Boolean,
        hideHeader: Boolean,
        hideSidebar: Boolean,
    ): String {
        val applyLit = if (apply) "true" else "false"
        val headerLit = if (hideHeader) "true" else "false"
        val sidebarLit = if (hideSidebar) "true" else "false"
        return """
        (function() {
          var ID = 'ava-ha-kiosk-mode';
          var APPLY = $applyLit;
          var HIDE_HEADER = $headerLit;
          var HIDE_SIDEBAR = $sidebarLit;

          function styleInto(root, css) {
            if (!root) return;
            var el = root.getElementById ? root.getElementById(ID) : null;
            if (!css) { if (el) el.remove(); return; }
            if (!el) {
              el = document.createElement('style');
              el.id = ID;
              root.appendChild(el);
            }
            // Rewriting a <style> reparses the sheet and invalidates every element in that
            // shadow root. applyNow() runs on every observed mutation, so the CSS is almost
            // always already correct — compare before touching it.
            if (el.textContent !== css) el.textContent = css;
          }

          function applyNow() {
            var ha = document.querySelector('home-assistant');
            var main = ha && ha.shadowRoot &&
              ha.shadowRoot.querySelector('home-assistant-main');
            if (!main || !main.shadowRoot) return false;

            styleInto(main.shadowRoot, (APPLY && HIDE_SIDEBAR)
              ? ':host{--mdc-drawer-width:0px!important;}' +
                'ha-drawer{--mdc-drawer-width:0px!important;}' +
                'ha-sidebar{display:none!important;}'
              : '');

            var drawer = main.shadowRoot.querySelector('ha-drawer');
            if (drawer && drawer.shadowRoot) {
              styleInto(drawer.shadowRoot, (APPLY && HIDE_SIDEBAR)
                ? '.mdc-drawer,.sidebar-shell{display:none!important;' +
                  'width:0!important;min-width:0!important;border:0!important;}' +
                  '.mdc-drawer-app-content,.app-content{margin-left:0!important;' +
                  'margin-inline-start:0!important;padding-left:0!important;' +
                  'padding-inline-start:0!important;}'
                : '');
            }

            var roots = main.shadowRoot.querySelectorAll('ha-panel-lovelace');
            var styled = false;
            for (var i = 0; i < roots.length; i++) {
              var panel = roots[i];
              var huiRoot = panel.shadowRoot &&
                panel.shadowRoot.querySelector('hui-root');
              if (huiRoot && huiRoot.shadowRoot) {
                styleInto(huiRoot.shadowRoot, (APPLY && HIDE_HEADER)
                  ? '.header,.toolbar,app-header,ch-header{display:none!important;}' +
                    '#view,hui-view{padding-top:0!important;min-height:100vh!important;}'
                  : '');
                styled = true;
              }
            }
            return styled;
          }

          var prev = window.__avaHaKiosk;
          if (prev) {
            if (prev.timer) clearInterval(prev.timer);
            if (prev.observer) prev.observer.disconnect();
            if (prev.onLocation) {
              window.removeEventListener('location-changed', prev.onLocation);
            }
          }
          var state = window.__avaHaKiosk =
            { timer: null, observer: null, onLocation: null };

          if (!APPLY || (!HIDE_HEADER && !HIDE_SIDEBAR)) {
            applyNow();
            return 'off';
          }

          // The observer watches HA's whole app subtree, so a view switch or an incremental
          // card render fires it many times in a row. Collapse those into one pass per frame.
          var scheduled = false;
          function scheduleApply() {
            if (scheduled) return;
            scheduled = true;
            var run = function() { scheduled = false; applyNow(); };
            if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
            else setTimeout(run, 16);
          }

          function ensureObserver() {
            if (state.observer) return;
            var ha = document.querySelector('home-assistant');
            if (!ha || !ha.shadowRoot) return;
            state.observer = new MutationObserver(scheduleApply);
            state.observer.observe(ha.shadowRoot, { childList: true, subtree: true });
          }

          var n = 0;
          state.timer = setInterval(function() {
            ensureObserver();
            if ((applyNow() && state.observer) || ++n > 240) clearInterval(state.timer);
          }, 250);
          state.onLocation = function() { setTimeout(applyNow, 80); };
          window.addEventListener('location-changed', state.onLocation);
          ensureObserver();
          applyNow();
          return 'on';
        })();
        """.trimIndent()
    }

    fun scriptForSettings(settings: BrowserSettings): String =
        applyScript(
            apply = isEnabled(settings.haKioskMode) && usesCss(settings.haKioskMode),
            hideHeader = settings.haKioskHideHeader,
            hideSidebar = settings.haKioskHideSidebar,
        )
}
