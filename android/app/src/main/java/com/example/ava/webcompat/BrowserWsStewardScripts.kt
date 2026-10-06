package com.example.ava.webcompat

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Home Assistant WebSocket steward.
 *
 * Graded, not binary:
 * 1. Lite — batch entity callbacks (cadence/chunk set by Kotlin per pressure/dormancy).
 * 2. Chunked rendering — `content-visibility:auto` skips off-screen card layout/paint.
 * 3. Page-live entity trim (opt-in) — first subscribe stays full; after settle retarget
 *    to current-page ids. Opaque cards fall back to *domain* narrowing before the
 *    full firehose, so one auto-entities card cannot undo the whole win.
 * 4. Park — retarget onto a sentinel id (zero traffic, socket stays authenticated).
 * 5. Suspend/resume — official `suspendReconnectUntil` + `suspend` (last resort).
 * 6. Optional freeze animations / pause media while deeply dormant.
 */
object BrowserWsStewardScripts {

    /**
     * Document-start boot wraps HA's WebSocket and walks every card shadow root.
     * The master switch alone must not do that — only stream (defer/trim/lite)
     * or chunked rendering actually need the page runtime. Dormant-quiet is
     * late-applied from Kotlin and does not justify a cold-start interceptor.
     */
    fun needsDocumentStart(
        stewardEnabled: Boolean,
        streamEnabled: Boolean,
        chunkEnabled: Boolean,
    ): Boolean = stewardEnabled && (streamEnabled || chunkEnabled)

    /** Batching cadence while the page is visible (pressure / duplicated split tile). */
    const val LITE_FAST_FLUSH_MS = 400
    const val LITE_FAST_CHUNK = 3
    /** Deep-dormancy cadence: a handful of coalesced applies per minute, not per frame. */
    const val LITE_SLOW_FLUSH_MS = 2000
    const val LITE_SLOW_CHUNK = 1

    private const val LITE_FLUSH_MS = LITE_FAST_FLUSH_MS
    private const val LITE_MAX_QUEUE = 400
    /** Fallback idle when only JS events fire (native pin owns the main path). */
    private const val SCROLL_GATE_IDLE_MS = 480
    /**
     * Hard bound on an un-pinned "scrolling" state. A dashboard that emits scroll events
     * faster than [SCROLL_GATE_IDLE_MS] (virtualizers, auto-scrolling logbook/map cards)
     * would otherwise keep the gate closed forever and starve entity delivery.
     */
    private const val SCROLL_GATE_MAX_FREEZE_MS = 2500
    /** Deliver a queued entity batch even mid-scroll once it has waited this long. */
    private const val SCROLL_GATE_MAX_DEFER_MS = 2000
    /**
     * Lovelace first paint emits layout "scroll" events for seconds after the HTML
     * document finishes. Deferring subscribe_entities there leaves native cards
     * (calendar, gauge, …) half-drawn. Gate stays open for this window after stream-on.
     */
    private const val FIRST_PAINT_GRACE_MS = 4000
    /**
     * Card shadow roots get the hide-scrollbar sheet after this, not during
     * Lovelace's first measure. Chrome (drawer / hui-view) is safe earlier.
     */
    private const val HIDE_SB_CARDS_AFTER_MS = FIRST_PAINT_GRACE_MS + 2000
    /** Max entity callbacks applied per animation frame when flushing. */
    private const val SCROLL_GATE_FLUSH_CHUNK = LITE_FAST_CHUNK
    /** Nodes visited per (shadow) DOM walk slice — the steward must not become the load. */
    private const val WALK_SLICE_NODES = 400
    /** Scroller re-hook cadence: starts here, doubles to the cap, resets on navigation. */
    private const val HOOK_MIN_MS = 3000
    private const val HOOK_MAX_MS = 30000
    /** Full re-verification of already-walked subtrees every N passes (catches lazy cards). */
    private const val WALK_REVERIFY_PASSES = 10
    /**
     * Parked tier: a syntactically valid entity id that never exists, so the subscription
     * yields no traffic while the WebSocket itself stays connected.
     */
    private const val PARK_SENTINEL = "ava_parked.none"
    /** Config larger than this is not digested for trim — keep the full subscribe instead. */
    private const val TRIM_MAX_CONFIG_CHARS = 400000
    /** DOM discovery budget; exceeding it means the id set is incomplete → keep full. */
    private const val DOM_SCAN_MAX_NODES = 6000
    private const val TRIM_MIN_IDS = 1
    private const val TRIM_TIMEOUT_MS = 2000
    /** Cards (auto-entities etc.) need a beat after states land. */
    private const val TRIM_WARMUP_SETTLE_MS = 2500
    private const val TRIM_WARMUP_POLL_MS = 250
    private const val TRIM_WARMUP_MAX_TRIES = 48
    /** Debounce when Lovelace fires location-changed / view switches. */
    private const val TRIM_RETARGET_DEBOUNCE_MS = 900

    /**
     * Must run before HA `core.js`. Uses the official `hassConnectionReady` race hook
     * so our wrap is registered ahead of `subscribeEntities(conn, noop)`.
     */
    val bootJs: String = """
        (function() {
          if (window.__avaWsBoot) return;
          window.__avaWsBoot = true;

          window.__avaEntityTrim = window.__avaEntityTrim || {
            // Nested under stream scheduling (wsStewardEntityTrimEnabled).
            enabled: false,
            lastStatus: 'boot',
            minIds: $TRIM_MIN_IDS,
            timeoutMs: $TRIM_TIMEOUT_MS,
            // First subscribe = full; after settle retarget to current-page ids.
            warmedUp: false,
            pendingIds: null,
            _mode: 'full'
          };
          window.__avaStewardMetrics = window.__avaStewardMetrics || {
            ev: 0,
            ch: 0,
            last: 0,
            seriesEv: [],
            seriesCh: [],
            seriesFps: [],
            log: [],
            phase: 'boot'
          };
          window.__avaNoteEntityEvent = function(event) {
            try {
              var m = window.__avaStewardMetrics;
              if (!m) return;
              m.ev += 1;
              var n = 0;
              if (event && typeof event === 'object') {
                if (event.a) { try { n += Object.keys(event.a).length; } catch (e1) {} }
                if (event.c) { try { n += Object.keys(event.c).length; } catch (e2) {} }
                if (event.r) n += Array.isArray(event.r) ? event.r.length : 0;
              }
              m.ch += n;
              m.last = Date.now();
            } catch (e) {}
          };

          // Scroll freeze: native pin owns the gesture; JS scroll/wheel is backup.
          // While frozen: defer subscribe_entities callbacks only (never patch Lit —
          // that made taps succeed with a frozen UI).
          // Start disabled: Lovelace layout "scrolls" during first paint. Deferring
          // subscribe_entities there leaves cards empty until streamOffJs (too late).
          // Kotlin setStream(true) is what arms the gate after the page is up.
          window.__avaScrollGate = {
            scrolling: false,
            pinned: false,
            bypass: false,
            disabled: true,
            timer: null,
            pinWatch: null,
            holdUntil: 0,
            scrollingSince: 0,
            queuedAt: 0,
            queue: [],
            maxQ: $LITE_MAX_QUEUE,
            flushChunk: $SCROLL_GATE_FLUSH_CHUNK,
            firstPaintUntil: 0,
            _fpTimer: null,
            _cssOn: false,
            inFirstPaint: function() {
              return this.firstPaintUntil > 0 && Date.now() < this.firstPaintUntil;
            },
            armFirstPaint: function(ms) {
              var self = this;
              var until = Date.now() + (ms || $FIRST_PAINT_GRACE_MS);
              if (until > this.firstPaintUntil) this.firstPaintUntil = until;
              if (this._fpTimer) clearTimeout(this._fpTimer);
              this._fpTimer = setTimeout(function() {
                self._fpTimer = null;
                self.firstPaintUntil = 0;
                // Layout-scroll during the grace must not latch deferral the moment it ends.
                if (!self.pinned) self._release();
              }, Math.max(16, this.firstPaintUntil - Date.now()));
            },
            _applyCss: function(on) {
              if (this._cssOn === on) return;
              this._cssOn = on;
              try {
                var root = document.documentElement;
                if (on) root.classList.add('ava-scroll-freeze');
                else root.classList.remove('ava-scroll-freeze');
              } catch (e) {}
            },
            _clearPinWatch: function() {
              if (this.pinWatch) { clearTimeout(this.pinWatch); this.pinWatch = null; }
            },
            _release: function() {
              this.pinned = false;
              this.scrolling = false;
              this.holdUntil = 0;
              this.scrollingSince = 0;
              this._clearPinWatch();
              if (this.timer) { clearTimeout(this.timer); this.timer = null; }
              this._applyCss(false);
              this.flush();
            },
            _endSoon: function(holdMs) {
              var self = this;
              var now = Date.now();
              // Not reachable mid-drag (pin owns that), so a long continuous "scrolling"
              // here means repeating scroll events, not a finger. Cut it loose.
              if (this.scrollingSince &&
                  now - this.scrollingSince > $SCROLL_GATE_MAX_FREEZE_MS) {
                this._release();
                return;
              }
              var ms = holdMs || $SCROLL_GATE_IDLE_MS;
              var until = now + ms;
              if (this.holdUntil > until) {
                ms = Math.max(16, this.holdUntil - now);
              } else {
                this.holdUntil = until;
              }
              if (this.timer) clearTimeout(this.timer);
              this.timer = setTimeout(function() {
                self.timer = null;
                if (self.pinned) return;
                self.scrolling = false;
                self.holdUntil = 0;
                self._applyCss(false);
                if (typeof requestAnimationFrame === 'function') {
                  requestAnimationFrame(function() { self.flush(); });
                } else {
                  self.flush();
                }
              }, ms);
            },
            /** Native gesture: pin(true) on drag, pin(false, holdMs) on finger-up. */
            pin: function(down, holdMs) {
              if (this.disabled || this.inFirstPaint()) return;
              var self = this;
              if (down) {
                this.pinned = true;
                this.scrolling = true;
                this.scrollingSince = Date.now();
                this.holdUntil = 0;
                if (this.timer) { clearTimeout(this.timer); this.timer = null; }
                this._applyCss(true);
                // UP can be stolen by SwipeRefresh — never stay pinned forever.
                this._clearPinWatch();
                this.pinWatch = setTimeout(function() {
                  self.pinWatch = null;
                  if (self.pinned) self.pin(false, $SCROLL_GATE_IDLE_MS);
                }, 1800);
                return;
              }
              this.pinned = false;
              this._clearPinWatch();
              this.scrolling = true;
              // Finger-up starts a fresh fling hold; do not inherit the drag's age.
              this.scrollingSince = Date.now();
              this._applyCss(true);
              this._endSoon(holdMs || $SCROLL_GATE_IDLE_MS);
            },
            mark: function() {
              if (this.disabled || this.inFirstPaint()) return;
              if (!this.scrolling) this.scrollingSince = Date.now();
              this.scrolling = true;
              this._applyCss(true);
              if (this.pinned) return;
              this._endSoon($SCROLL_GATE_IDLE_MS);
            },
            shouldDefer: function() {
              if (this.disabled || this.bypass) return false;
              if (this.inFirstPaint()) return false;
              return this.scrolling ||
                (window.__avaWsSteward && window.__avaWsSteward.lite);
            },
            // HA entity stream is compressed {a,c,r}. Shallow merge of two events
            // replaces `c` wholesale and drops earlier entity changes → UI stuck.
            _mergeDiff: function(a, b) {
              if (!a) return b;
              if (!b) return a;
              if (typeof a !== 'object' || typeof b !== 'object') return b;
              var ca = !!(a.a || a.c || a.r);
              var cb = !!(b.a || b.c || b.r);
              if (ca || cb) {
                return {
                  a: Object.assign({}, a.a || {}, b.a || {}),
                  c: Object.assign({}, a.c || {}, b.c || {}),
                  r: [].concat(a.r || [], b.r || [])
                };
              }
              return Object.assign({}, a, b);
            },
            /** HA coalesces into one callback, so maxQ alone never trips — age does. */
            overdue: function(maxMs) {
              return this.queue.length > 0 &&
                this.queuedAt > 0 &&
                (Date.now() - this.queuedAt) > maxMs;
            },
            enqueue: function(cb, ev) {
              if (!this.queue.length) this.queuedAt = Date.now();
              for (var i = this.queue.length - 1; i >= 0; i--) {
                if (this.queue[i].cb === cb) {
                  this.queue[i].ev = this._mergeDiff(this.queue[i].ev, ev);
                  return;
                }
              }
              this.queue.push({ cb: cb, ev: ev });
              if (this.queue.length >= this.maxQ) this.flush();
            },
            flush: function() {
              var q = this.queue;
              this.queuedAt = 0;
              if (!q.length) return;
              this.queue = [];
              var i = 0;
              var chunk = this.flushChunk || 3;
              var step = function() {
                var end = Math.min(i + chunk, q.length);
                for (; i < end; i++) {
                  try { q[i].cb(q[i].ev); } catch (e) {}
                }
                if (i < q.length) {
                  if (typeof requestAnimationFrame === 'function') {
                    requestAnimationFrame(step);
                  } else {
                    setTimeout(step, 16);
                  }
                }
              };
              step();
            }
          };
          // ---- Chunked rendering -------------------------------------------------
          // content-visibility lets the engine skip layout/paint/raster for off-screen
          // cards entirely — incremental by construction, and data keeps flowing, so
          // nothing stops working. Styles are injected disabled; Kotlin arms them per
          // dormancy/pressure tier via setChunkedRendering.
          // Cards that measure themselves break when their box is skipped: they lay out
          // against the intrinsic-size estimate and, unlike plain cards, never re-measure
          // on their own. Their shadow roots are never given the rule. This covers native
          // HA cards too — hui-calendar-card (FullCalendar) built a truncated grid, which is
          // what "the calendar card loads half-drawn" was.
          var CHUNK_EXCLUDE = [
            'apexcharts', 'plotly', 'sankey', 'power-flow', 'power-wheel', 'sunsynk',
            'floorplan', 'camera', 'map', 'frigate', 'history-explorer',
            'xiaomi-vacuum-map', 'graph', 'gauge',
            'calendar', 'thermostat', 'humidifier', 'energy', 'statistic',
            'picture-elements', 'media-control', 'weather', 'chart'
          ];
          window.__avaChunkRender = window.__avaChunkRender || { on: false };
          window.__avaChunkStyles = window.__avaChunkStyles || [];
          function chunkExcluded(host) {
            try {
              var t = host && host.tagName ? host.tagName.toLowerCase() : '';
              if (!t) return false;
              for (var i = 0; i < CHUNK_EXCLUDE.length; i++) {
                if (t.indexOf(CHUNK_EXCLUDE[i]) >= 0) return true;
              }
            } catch (e) {}
            return false;
          }
          // Engine-graded chunk rule. `contain-intrinsic-size: auto <length>` only
          // parses on Chromium 98+; on 85–97 the whole declaration is dropped,
          // ha-card collapses to 0 height, and the Lovelace masonry / overlay
          // compositor can native-crash. Those engines get a fixed placeholder
          // (Chromium 83+ syntax). Engines without content-visibility (<85) get
          // no rule at all and setChunkedRendering reports unsupported.
          function chunkRuleCss() {
            var r = window.__avaChunkRender;
            if (r.rule !== undefined) return r.rule;
            var rule = null;
            try {
              if (window.CSS && CSS.supports &&
                  CSS.supports('content-visibility', 'auto')) {
                if (CSS.supports('contain-intrinsic-size', 'auto 180px')) {
                  rule =
                    'ha-card{content-visibility:auto;contain-intrinsic-size:auto 180px;}';
                } else if (CSS.supports('contain-intrinsic-size', '180px')) {
                  rule =
                    'ha-card{content-visibility:auto;contain-intrinsic-size:180px;}';
                }
              }
            } catch (e) {}
            r.rule = rule;
            return rule;
          }
          function armChunkCss(root, host) {
            if (!root || root.__avaChunkCss) return;
            if (chunkExcluded(host)) return;
            var chunkCss = chunkRuleCss();
            if (!chunkCss) return;
            try { root.__avaChunkCss = true; } catch (e) { return; }
            try {
              var s = document.createElement('style');
              s.id = 'ava-chunk-cv';
              // Pair is load-bearing: content-visibility without intrinsic size
              // collapses ha-card to 0 height and the Lovelace masonry / overlay
              // compositor can native-crash. Do not delete this rule — the
              // switch only sets s.disabled (styles inject off until armed).
              s.textContent = chunkCss;
              s.disabled = !window.__avaChunkRender.on;
              root.appendChild(s);
              window.__avaChunkStyles.push(s);
            } catch (e) {}
          }
          /**
           * Lifting the rule restores the boxes, but a widget that already measured itself
           * against the intrinsic-size estimate keeps its wrong layout: FullCalendar, chart.js
           * and HA's circular sliders only re-measure on a resize. Without this nudge the card
           * stays truncated until the page reloads.
           */
          function remeasureAfterChunkOff() {
            var fire = function() {
              try {
                window.dispatchEvent(new Event('resize'));
              } catch (e) {
                try {
                  var ev = document.createEvent('Event');
                  ev.initEvent('resize', true, false);
                  window.dispatchEvent(ev);
                } catch (e2) {}
              }
            };
            // Two frames: one for the engine to lay the skipped boxes back out, one for the
            // widgets to read a real size.
            if (typeof requestAnimationFrame === 'function') {
              requestAnimationFrame(function() { requestAnimationFrame(fire); });
            } else {
              setTimeout(fire, 32);
            }
          }
          window.__avaSetChunkedRendering = function(on) {
            if (on && !chunkRuleCss()) {
              window.__avaChunkRender.on = false;
              return 'chunk-unsupported:engine';
            }
            var was = !!window.__avaChunkRender.on;
            window.__avaChunkRender.on = !!on;
            var list = window.__avaChunkStyles || [];
            var n = 0;
            for (var i = 0; i < list.length; i++) {
              try { list[i].disabled = !on; n++; } catch (e) {}
            }
            if (was && !on) remeasureAfterChunkOff();
            return 'chunk-render:' + (on ? 'on' : 'off') + ':' + n;
          };

          if (!window.__avaScrollGate._hooked) {
            window.__avaScrollGate._hooked = true;
            var lastScrollEl = null;
            function reportCanScrollUp(el) {
              try {
                var can = false;
                if (window.scrollY > 1 ||
                    (document.documentElement && document.documentElement.scrollTop > 1) ||
                    (document.body && document.body.scrollTop > 1)) {
                  can = true;
                } else if (el && typeof el.scrollTop === 'number' && el.scrollTop > 1) {
                  can = true;
                } else if (lastScrollEl && typeof lastScrollEl.scrollTop === 'number' &&
                    lastScrollEl.scrollTop > 1) {
                  can = true;
                }
                if (window.AvaScrollBridge &&
                    typeof window.AvaScrollBridge.setCanScrollUp === 'function') {
                  window.AvaScrollBridge.setCanScrollUp(can);
                }
              } catch (e) {}
            }
            var markScroll = function(ev) {
              try {
                if (ev && ev.target && typeof ev.target.scrollTop === 'number') {
                  lastScrollEl = ev.target;
                  reportCanScrollUp(ev.target);
                }
                window.__avaScrollGate.mark();
              } catch (e) {}
            };
            // Touch freeze is driven by native pin(); JS only covers fling scroll + wheel.
            window.addEventListener('scroll', markScroll, { capture: true, passive: true });
            window.addEventListener('wheel', markScroll, { capture: true, passive: true });
            // Hide scrollbars inside shadow roots (HA draws them there).
            // Chrome first; cards only after [HIDE_SB_CARDS_AFTER_MS] so
            // FullCalendar / gauge have already measured a real box.
            function isCardHost(host) {
              var t = host && host.tagName ? host.tagName.toLowerCase() : '';
              return !!t && t.indexOf('card') >= 0;
            }
            function hideSbAllowed(host) {
              if (!isCardHost(host)) return true;
              return !!window.__avaHideSbCards;
            }
            function hideSbInRoot(root) {
              if (!root || root.__avaHideSb) return;
              try { root.__avaHideSb = true; } catch (e) { return; }
              try {
                if (root.querySelector && root.querySelector('#ava-hide-sb')) return;
                var s = document.createElement('style');
                s.id = 'ava-hide-sb';
                s.textContent = [
                  '*,*::before,*::after{scrollbar-width:none!important;-ms-overflow-style:none!important;',
                  'scrollbar-gutter:auto!important;}',
                  '*::-webkit-scrollbar{width:0!important;height:0!important;',
                  'display:none!important;background:transparent!important;}'
                ].join('');
                root.appendChild(s);
              } catch (e) {}
            }
            // Light shadow hook for canScrollUp + fling backup (no MutationObserver).
            // Budgeted + generation-stamped: a pass visits at most WALK_SLICE_NODES new
            // nodes and skips subtrees it already completed this generation, so a large
            // dashboard is walked incrementally instead of fully re-scanned every 3s.
            var walkGen = 1;
            var walkBudget = 0;
            var hookedThisPass = 0;
            /** @returns {boolean} true when this subtree was fully walked within budget */
            function hookScrollTree(node) {
              if (!node) return true;
              if (node.__avaWalkGen === walkGen) return true;
              if (walkBudget <= 0) return false;
              walkBudget--;
              if (!node.__avaScrollHooked) {
                try {
                  node.__avaScrollHooked = true;
                  node.addEventListener('scroll', markScroll, {
                    capture: true, passive: true
                  });
                  hookedThisPass++;
                } catch (e) {}
              }
              var done = true;
              try {
                if (node.shadowRoot) {
                  if (hideSbAllowed(node)) hideSbInRoot(node.shadowRoot);
                  armChunkCss(node.shadowRoot, node);
                  if (!hookScrollTree(node.shadowRoot)) done = false;
                }
              } catch (e) {}
              try {
                var kids = node.children;
                if (kids) {
                  for (var i = 0; i < kids.length; i++) {
                    if (!hookScrollTree(kids[i])) { done = false; break; }
                  }
                }
              } catch (e) {}
              if (done) {
                try { node.__avaWalkGen = walkGen; } catch (e) {}
              }
              return done;
            }
            function hookHaScrollers() {
              if (window.__avaScrollGate && window.__avaScrollGate.scrolling) return;
              walkBudget = $WALK_SLICE_NODES;
              try {
                var ha = document.querySelector('home-assistant');
                if (ha) hookScrollTree(ha);
              } catch (e) {}
            }
            // Settled tree → back off toward HOOK_MAX_MS; new nodes reset the cadence.
            var hookDelay = $HOOK_MIN_MS;
            var hookTimer = null;
            var passCount = 0;
            function scheduleHookPass(delay) {
              if (hookTimer) clearTimeout(hookTimer);
              hookTimer = setTimeout(runHookPass, delay);
            }
            function runHookPass() {
              hookTimer = null;
              hookedThisPass = 0;
              if (++passCount % $WALK_REVERIFY_PASSES === 0) walkGen++;
              hookHaScrollers();
              hookDelay = hookedThisPass > 0
                ? $HOOK_MIN_MS
                : Math.min(hookDelay * 2, $HOOK_MAX_MS);
              scheduleHookPass(hookDelay);
            }
            /** Navigation invalidates the walk generation and restores the fast cadence. */
            window.__avaResetScrollerHooks = function() {
              walkGen++;
              hookDelay = $HOOK_MIN_MS;
              scheduleHookPass(200);
            };
            // Chrome walk after first paint; card hide-sb is a second, later pass.
            setTimeout(hookHaScrollers, $FIRST_PAINT_GRACE_MS);
            scheduleHookPass($FIRST_PAINT_GRACE_MS);
            setTimeout(function() {
              window.__avaHideSbCards = true;
              walkGen++;
              hookHaScrollers();
            }, $HIDE_SB_CARDS_AFTER_MS);
          }
          (function armChunkCssOnHead() {
            function go() { armChunkCss(document.head || document.documentElement, null); }
            if (document.head || document.documentElement) go();
            else document.addEventListener('DOMContentLoaded', go);
          })();
          (function injectScrollCss() {
            function go() {
              if (document.getElementById('ava-scroll-css')) return;
              var s = document.createElement('style');
              s.id = 'ava-scroll-css';
              s.textContent = [
                'html,body{overscroll-behavior-y:contain;-webkit-tap-highlight-color:transparent;}',
                /* Scroll works; scrollbar never stays on screen. */
                'html,body,*,*::before,*::after{scrollbar-width:none!important;',
                '-ms-overflow-style:none!important;scrollbar-gutter:auto!important;}',
                'html::-webkit-scrollbar,body::-webkit-scrollbar,*::-webkit-scrollbar{',
                'width:0!important;height:0!important;display:none!important;}',
                'html.ava-scroll-freeze *,html.ava-scroll-freeze *::before,html.ava-scroll-freeze *::after{',
                'animation-play-state:paused!important;transition:none!important;}'
              ].join('');
              (document.head || document.documentElement).appendChild(s);
            }
            if (document.head || document.documentElement) go();
            else document.addEventListener('DOMContentLoaded', go);
          })();

          // Panels we never trim for. `/config/lovelace*` is carved out below —
          // that is where dashboards live and must be digested, not ignored.
          var NON_LOVELACE = {
            'config': 1, 'history': 1, 'logbook': 1, 'media-browser': 1,
            'developer-tools': 1, 'profile': 1, 'todo': 1, 'energy': 1,
            'map': 1, 'calendar': 1, 'my': 1, 'auth': 1, 'hassio': 1,
            'supervisor': 1, 'updates': 1, 'repairs': 1, 'hacs': 1
          };

          /**
           * @returns {{kind:'lovelace',urlPath:string|null}|
           *           {kind:'config-lovelace'}|{kind:'ignore'}}
           */
          function classifyPath() {
            try {
              var p = location.pathname || '/';
              // Dashboard manager / Lovelace config — highest-value source.
              if (p.indexOf('/config/lovelace') === 0 ||
                  p.indexOf('/config/dashboard') === 0) {
                return { kind: 'config-lovelace' };
              }
              if (p === '/' || p.indexOf('/lovelace') === 0) {
                return { kind: 'lovelace', urlPath: null };
              }
              var seg = p.replace(/^\//, '').split('/')[0];
              if (!seg) return { kind: 'lovelace', urlPath: null };
              if (NON_LOVELACE[seg]) return { kind: 'ignore' };
              // Custom dashboard url_path (e.g. /dashboard-home).
              return { kind: 'lovelace', urlPath: seg };
            } catch (e) {
              return { kind: 'ignore' };
            }
          }

          /** @deprecated use classifyPath — kept for warmup helpers */
          function urlPathFromLocation() {
            var c = classifyPath();
            if (c.kind === 'ignore') return undefined;
            if (c.kind === 'config-lovelace') return null;
            return c.urlPath;
          }

          function isEntityId(s) {
            return typeof s === 'string' && /^[a-z_][a-z0-9_]*\.[a-z0-9_]+${'$'}/.test(s);
          }

          /**
           * Opaque / indirect-entity cards: when present on the current view,
           * page-live trim must keep a full subscribe (passthrough).
           * Bubble Card alone is NOT listed — entities live in YAML; UIX Forge
           * wrapping Bubble is covered by uix-forge / foundry rules.
           */
          // Built-in table; Kotlin may replace the working copy via setUserOpaqueTypes.
          var BUILTIN_OPAQUE_TYPE_INCLUDES = [
            // Tier 1 — external template / second store
            'uix-forge', 'decluttering-card', 'streamline-card',
            'linked-lovelace', 'config-template-card', 'card-templater',
            'template-entity-row', 'html-template', 'jinja2-template',
            'tailwindcss-template-card', 'auto-entities', 'flex-table-card',
            'battery-state-card',
            // Tier 2 — strategy / heavy / dynamic
            'state-switch', 'apexcharts-card', 'plotly-graph', 'sankey',
            'power-flow', 'power-wheel', 'sunsynk-power', 'home-feed-card',
            'search-card', 'floorplan', 'ha-floorplan', 'advanced-camera',
            'frigate', 'xiaomi-vacuum-map', 'upcoming-media', 'sonos-card',
            'yet-another-media-player', 'yamp', 'history-explorer',
            'logbook-card', 'scheduler-card', 'mushroom-strategy'
          ];
          var OPAQUE_TYPE_INCLUDES = BUILTIN_OPAQUE_TYPE_INCLUDES.slice();
          var OPAQUE_KEYS = ['foundry'];

          function syncOpaqueDiag() {
            try { window.__avaOpaqueCardRules = OPAQUE_TYPE_INCLUDES.slice(); } catch (e) {}
          }

          /**
           * null/undefined → restore built-in table.
           * Array (including empty) → replace working table; empty means no type passthrough.
           */
          function setUserOpaqueTypes(types) {
            if (types == null) {
              OPAQUE_TYPE_INCLUDES = BUILTIN_OPAQUE_TYPE_INCLUDES.slice();
            } else {
              var next = [];
              var seen = {};
              if (Array.isArray(types)) {
                for (var i = 0; i < types.length; i++) {
                  var s = typeof types[i] === 'string' ? types[i].toLowerCase().trim() : '';
                  if (!s || seen[s]) continue;
                  seen[s] = 1;
                  next.push(s);
                }
              }
              OPAQUE_TYPE_INCLUDES = next;
            }
            syncOpaqueDiag();
            try {
              if (window.__avaEntityTrim && window.__avaEntityTrim.enabled &&
                  typeof window.__avaArmEntityTrim === 'function') {
                window.__avaArmEntityTrim();
              }
            } catch (e) {}
            return 'opaque-types:' + OPAQUE_TYPE_INCLUDES.length;
          }

          /** @returns {string|null} rule id when view must not be trimmed */
          function findOpaqueReason(node) {
            if (!node) return null;
            if (Array.isArray(node)) {
              for (var i = 0; i < node.length; i++) {
                var ra = findOpaqueReason(node[i]);
                if (ra) return ra;
              }
              return null;
            }
            if (typeof node !== 'object') return null;

            if (node.strategy) return 'strategy';

            var t = typeof node.type === 'string' ? node.type.toLowerCase() : '';
            if (t) {
              for (var ti = 0; ti < OPAQUE_TYPE_INCLUDES.length; ti++) {
                if (t.indexOf(OPAQUE_TYPE_INCLUDES[ti]) >= 0) {
                  return 'type:' + OPAQUE_TYPE_INCLUDES[ti];
                }
              }
              // button-card template library without a plain local entity
              if (t.indexOf('button-card') >= 0 && node.template != null &&
                  !(typeof node.entity === 'string' && isEntityId(node.entity))) {
                return 'button-card-template';
              }
            }

            for (var ki = 0; ki < OPAQUE_KEYS.length; ki++) {
              if (Object.prototype.hasOwnProperty.call(node, OPAQUE_KEYS[ki])) {
                return 'key:' + OPAQUE_KEYS[ki];
              }
            }

            for (var k in node) {
              if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
              if (typeof k === 'string' && k.length >= 11 &&
                  k.slice(-11) === '_javascript') {
                return 'js-template-key';
              }
              var v = node[k];
              if (typeof v === 'string') {
                if (v.indexOf('{{') >= 0) return 'jinja';
                if (v.indexOf('[[') >= 0 && v.indexOf(']]') >= 0) {
                  return 'declutter-var';
                }
                if (v === 'this.entity_id') return 'this-entity-id';
              } else if (v && typeof v === 'object') {
                var rc = findOpaqueReason(v);
                if (rc) return rc;
              }
            }
            return null;
          }

          function extractEntities(config) {
            var found = {};
            function add(id) { if (isEntityId(id)) found[id] = 1; }
            function walk(node) {
              if (!node) return;
              if (Array.isArray(node)) { node.forEach(walk); return; }
              if (typeof node !== 'object') return;
              if (typeof node.entity === 'string') add(node.entity);
              if (typeof node.camera_image === 'string') add(node.camera_image);
              if (typeof node.camera_entity === 'string') add(node.camera_entity);
              if (typeof node.entity_id === 'string') add(node.entity_id);
              else if (Array.isArray(node.entity_id)) node.entity_id.forEach(add);
              if (Array.isArray(node.entities)) {
                for (var i = 0; i < node.entities.length; i++) {
                  var it = node.entities[i];
                  if (typeof it === 'string') add(it);
                }
              }
              for (var k in node) {
                if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
                if (k === 'filter') continue;
                var v = node[k];
                if (v && typeof v === 'object') walk(v);
              }
            }
            if (!config) return [];
            if (config.strategy && !config.views) return [];
            var views = Array.isArray(config.views) ? config.views : [];
            views.forEach(walk);
            var walked = Object.keys(found);
            // Walk already found real ids — skip the megabyte stringify. Template bodies
            // that the walk cannot reach are handled by the opaque → domain-narrow path.
            if (walked.length >= $TRIM_MIN_IDS) return walked;
            // Empty walk only: try a cheap catch-all on small configs. Huge configs bail
            // empty so the caller keeps (or domain-narrows) rather than freeze the UI.
            try {
              var text = JSON.stringify(config);
              if (!text || text.length > $TRIM_MAX_CONFIG_CHARS) return walked;
              var re = /[a-z_][a-z0-9_]*\.[a-z0-9_]+/g;
              var m;
              while ((m = re.exec(text)) !== null) add(m[0]);
            } catch (e) {}
            return Object.keys(found);
          }

          function wrapCallback(callback) {
            if (typeof callback !== 'function') return callback;
            return function(event) {
              if (typeof window.__avaNoteEntityEvent === 'function') {
                window.__avaNoteEntityEvent(event);
              }
              var gate = window.__avaScrollGate;
              if (gate && gate.shouldDefer()) {
                gate.enqueue(callback, event);
                return;
              }
              callback(event);
            };
          }

          function collectRenderedEntityIds() {
            var found = {};
            // Per-call visited set — never stamp DOM (second scan must still work).
            var visited = typeof WeakSet !== 'undefined' ? new WeakSet() : [];
            function seen(node) {
              if (visited.add) {
                if (visited.has(node)) return true;
                visited.add(node);
                return false;
              }
              for (var i = 0; i < visited.length; i++) {
                if (visited[i] === node) return true;
              }
              visited.push(node);
              return false;
            }
            function add(id) { if (isEntityId(id)) found[id] = 1; }
            var scanBudget = $DOM_SCAN_MAX_NODES;
            var truncated = false;
            function visit(node) {
              if (!node || seen(node)) return;
              if (scanBudget-- <= 0) { truncated = true; return; }
              if (node.nodeType !== 1) return;
              try {
                var attrs = ['entity-id', 'entityId', 'data-entity', 'data-entity-id'];
                for (var a = 0; a < attrs.length; a++) {
                  var v = node.getAttribute && node.getAttribute(attrs[a]);
                  if (v) add(v);
                }
                if (typeof node.entity === 'string') add(node.entity);
                if (node.stateObj && typeof node.stateObj.entity_id === 'string') {
                  add(node.stateObj.entity_id);
                }
                if (node.hassObject && typeof node.hassObject.entity_id === 'string') {
                  add(node.hassObject.entity_id);
                }
              } catch (e) {}
              try { if (node.shadowRoot) visit(node.shadowRoot); } catch (e) {}
              try {
                var kids = node.children;
                if (kids) {
                  for (var i = 0; i < kids.length; i++) visit(kids[i]);
                }
              } catch (e) {}
            }
            try { visit(document.body || document.documentElement); } catch (e) {}
            var out = Object.keys(found);
            // Budget hit: discovery is incomplete, so the caller must not trim on it.
            out.truncated = truncated;
            return out;
          }

          async function fetchLovelaceConfig(conn, urlPath) {
            if (!conn || typeof conn.sendMessagePromise !== 'function') return null;
            try {
              return await conn.sendMessagePromise({
                type: 'lovelace/config',
                url_path: urlPath,
                force: false
              });
            } catch (e) {
              return null;
            }
          }

          function currentViewIndex() {
            try {
              var path = location.pathname || '';
              var parts = path.split('/');
              var last = parts[parts.length - 1];
              if (/^\d+$/.test(last)) return parseInt(last, 10);
              var hash = location.hash || '';
              var m = hash.match(/(?:view|\/)(\d+)/);
              if (m) return parseInt(m[1], 10);
            } catch (e) {}
            return 0;
          }

          /**
           * Current page only: active Lovelace view config ∪ rendered DOM.
           * @returns {{ids:string[], fromConfig:string[], fromDom:string[],
           *            opaqueReason:string|null}}
           */
          async function collectCurrentPageEntityIds(conn) {
            var fromConfig = [];
            var fromDom = [];
            var opaqueReason = null;
            // Sticky: a truncated DOM scan can never be cleared by a later config verdict.
            var scanTruncated = false;
            try {
              fromDom = collectRenderedEntityIds();
              scanTruncated = !!(fromDom && fromDom.truncated);
            } catch (e) {}
            try {
              var cls = classifyPath();
              if (cls.kind === 'lovelace') {
                var cfg = null;
                if (window.llConfProm) {
                  cfg = await Promise.resolve(window.llConfProm).catch(function() {
                    return null;
                  });
                }
                if (!cfg) cfg = await fetchLovelaceConfig(conn, cls.urlPath);
                if (cfg && cfg.strategy && !cfg.views) {
                  opaqueReason = 'strategy';
                } else if (cfg && Array.isArray(cfg.views) && cfg.views.length) {
                  var idx = currentViewIndex();
                  if (idx < 0 || idx >= cfg.views.length) idx = 0;
                  var view = cfg.views[idx];
                  try { opaqueReason = findOpaqueReason(view); } catch (e) {}
                  fromConfig = extractEntities({ views: [view] });
                  // Cards present but no discoverable entity ids → treat as opaque.
                  if (!opaqueReason && !fromConfig.length) {
                    var hasCards = !!(view && (
                      (Array.isArray(view.cards) && view.cards.length) ||
                      (Array.isArray(view.sections) && view.sections.length) ||
                      (Array.isArray(view.badges) && view.badges.length)
                    ));
                    if (hasCards) opaqueReason = 'empty-entities';
                  }
                } else if (cfg) {
                  try { opaqueReason = findOpaqueReason(cfg); } catch (e) {}
                  fromConfig = extractEntities(cfg);
                }
              }
            } catch (e) {}
            if (scanTruncated) opaqueReason = opaqueReason || 'dom-scan-truncated';
            var merged = {};
            var i;
            for (i = 0; i < fromConfig.length; i++) merged[fromConfig[i]] = 1;
            for (i = 0; i < fromDom.length; i++) merged[fromDom[i]] = 1;
            try {
              window.__avaEntityTrim = window.__avaEntityTrim || {};
              window.__avaEntityTrim.lastOpaque = opaqueReason;
            } catch (e) {}
            return {
              ids: Object.keys(merged),
              fromConfig: fromConfig,
              fromDom: fromDom,
              opaqueReason: opaqueReason
            };
          }

          function sameIdSet(a, b) {
            if (!a || !b || a.length !== b.length) return false;
            var m = {};
            for (var i = 0; i < a.length; i++) m[a[i]] = 1;
            for (var j = 0; j < b.length; j++) {
              if (!m[b[j]]) return false;
            }
            return true;
          }

          function writeSnapshot(extra) {
            var trim = window.__avaEntityTrim;
            if (!trim) return null;
            extra = extra || {};
            var hass = null;
            try {
              var ha = document.querySelector('home-assistant');
              hass = ha && ha.hass;
            } catch (e) {}
            var total = 0;
            var connected = false;
            try {
              if (hass && hass.states) total = Object.keys(hass.states).length;
              connected = !!(hass && hass.connection && hass.connected !== false);
            } catch (e2) {}
            var ids = Array.isArray(extra.ids)
              ? extra.ids.slice()
              : (Array.isArray(trim.pendingIds) ? trim.pendingIds.slice() : []);
            var mode = extra.mode || trim._mode || 'full';
            var watching;
            if (mode === 'parked') watching = 0;
            else if (mode === 'page' || mode === 'domain') watching = ids.length;
            else watching = total;
            var prev = trim.snapshot || {};
            trim.snapshot = {
              mode: mode,
              status: trim.lastStatus || '',
              opaque: extra.opaque != null ? extra.opaque : (trim.lastOpaque || null),
              ids: ids,
              fromConfig: extra.fromConfig || trim._lastFromConfig || prev.fromConfig || [],
              fromDom: extra.fromDom || trim._lastFromDom || prev.fromDom || [],
              watching: watching,
              total: total,
              connected: connected,
              path: extra.path || trim._pathKey || '',
              at: Date.now()
            };
            try {
              if (window.__avaStewardConsole &&
                  typeof window.__avaStewardConsole.onSnapshot === 'function') {
                window.__avaStewardConsole.onSnapshot(trim.snapshot);
              }
            } catch (e3) {}
            return trim.snapshot;
          }

          function setTrimStatus(s) {
            try { window.__avaEntityTrim.lastStatus = s; } catch (e) {}
            try {
              var m = window.__avaStewardMetrics || (window.__avaStewardMetrics = { log: [] });
              m.phase = s;
              m.log = m.log || [];
              m.log.push({ t: Date.now(), s: String(s || '') });
              if (m.log.length > 16) m.log.shift();
            } catch (e4) {}
            try { writeSnapshot(); } catch (e2) {}
          }

          /** Collect `light`/`sensor`/… domains from a list of entity ids. */
          function domainsFromIds(ids) {
            var domains = {};
            if (!ids) return domains;
            for (var i = 0; i < ids.length; i++) {
              var id = ids[i];
              if (typeof id !== 'string') continue;
              var dot = id.indexOf('.');
              if (dot > 0) domains[id.slice(0, dot)] = 1;
            }
            return domains;
          }

          /**
           * Expand discovered domains against the live hass.states table.
           * Returns null when states are unavailable or the expansion would be almost
           * the full firehose (no win over keepFull).
           */
          function expandDomainsFromHass(domains) {
            try {
              var ha = document.querySelector('home-assistant');
              var states = ha && ha.hass && ha.hass.states;
              if (!states) return null;
              var keys = Object.keys(states);
              if (!keys.length) return null;
              var out = [];
              for (var i = 0; i < keys.length; i++) {
                var id = keys[i];
                var dot = id.indexOf('.');
                if (dot > 0 && domains[id.slice(0, dot)]) out.push(id);
              }
              // Less than half of all entities: worth the retarget. Otherwise keepFull.
              if (!out.length || out.length * 2 >= keys.length) return null;
              return out;
            } catch (e) {
              return null;
            }
          }

          /**
           * Swap subscribe_entities filter without killing the WebSocket.
           * unsubscribe → subscribeMessage({ entity_ids }) on the same connection.
           */
          window.__avaRestoreFullEntities = function() {
            var trim = window.__avaEntityTrim;
            if (!trim || !trim._live) return;
            retargetEntitySubscribe(null).then(function(ok) {
              if (ok) {
                trim._mode = 'full';
                trim.pendingIds = null;
                setTrimStatus('page-full');
              }
            });
          };

          var adoptTried = false;
          /**
           * Legacy engines (no document-start script) inject this boot after HA already
           * issued its subscribe_entities, so the live subscription was never captured
           * and park / page-live trim have nothing to retarget. Bounded recovery: gate
           * reconnect on an already-resolved promise and suspend — HA reconnects
           * immediately and re-subscribes through the wrapper, which captures _live.
           * At most once per page; the one dropped WS burst happens while entering
           * dormancy, never in front of the user.
           */
          function adoptLiveSubscription() {
            if (adoptTried) return false;
            adoptTried = true;
            var conn = null;
            try {
              var ha = document.querySelector('home-assistant');
              conn = ha && ha.hass && ha.hass.connection;
            } catch (e) {}
            if (!conn) return false;
            try { wrapConn(conn); } catch (e) {}
            if (typeof conn.suspendReconnectUntil !== 'function' ||
                typeof conn.suspend !== 'function') return false;
            try {
              conn.suspendReconnectUntil(Promise.resolve());
              conn.suspend();
              setTrimStatus('adopt-reconnect');
              return true;
            } catch (e) {
              return false;
            }
          }

          /**
           * Parked tier: retarget the existing subscription onto a sentinel entity that
           * never exists. Entity traffic and callbacks drop to zero while the WebSocket
           * stays authenticated — waking costs one subscribeMessage instead of a full
           * reconnect + re-auth + whole-state resync, which is what made uncovering the
           * screensaver look like a blank / half-drawn dashboard.
           */
          window.__avaSetEntitiesParked = function(on) {
            var trim = window.__avaEntityTrim;
            if (!trim) return 'park-no-sub';
            if (!on) trim._parkAfterAdopt = false;
            if (!trim._live) {
              // Legacy boot: nothing captured to retarget. Recover the subscription
              // once, then finish the park from the wrapper's _live capture.
              if (on && adoptLiveSubscription()) {
                trim._parkAfterAdopt = true;
                return 'park-adopting';
              }
              return 'park-no-sub';
            }
            if (on) {
              if (trim._parked) return 'already-parked';
              trim._preParkMode = trim._mode;
              trim._preParkIds = Array.isArray(trim.pendingIds)
                ? trim.pendingIds.slice()
                : null;
              // Set before the await so a racing page-live retarget cannot unpark us.
              trim._parked = true;
              retargetEntitySubscribe(['$PARK_SENTINEL']).then(function(ok) {
                if (ok) {
                  trim._mode = 'parked';
                  setTrimStatus('parked');
                } else {
                  trim._parked = false;
                  setTrimStatus('park-failed');
                }
              });
              return 'parking';
            }
            if (!trim._parked) return 'not-parked';
            trim._parked = false;
            var ids = trim._preParkIds;
            var restoreMode = trim._preParkMode;
            var wantFiltered = (restoreMode === 'page' || restoreMode === 'domain') &&
              ids && ids.length;
            retargetEntitySubscribe(wantFiltered ? ids : null).then(function(ok) {
              if (ok) {
                trim._mode = wantFiltered ? restoreMode : 'full';
                trim.pendingIds = wantFiltered ? ids : null;
                setTrimStatus('unparked:' + trim._mode);
              } else {
                setTrimStatus('unpark-failed');
              }
              trim._preParkIds = null;
              trim._preParkMode = null;
            });
            return 'unparking';
          };

          async function retargetEntitySubscribe(entityIdsOrNull) {
            var trim = window.__avaEntityTrim;
            var live = trim && trim._live;
            if (!live || typeof live.orig !== 'function') return false;
            if (live._retargeting) return false;
            live._retargeting = true;
            var gate = window.__avaScrollGate;
            // Initial payload after resub must not be coalesced/deferred.
            if (gate) {
              gate.bypass = true;
              gate._release();
            }
            try {
              var oldUnsub = live.unsub;
              live.unsub = null;
              if (typeof oldUnsub === 'function') {
                try { await Promise.resolve(oldUnsub()); } catch (e) {}
              }
              var msg = { type: 'subscribe_entities' };
              if (entityIdsOrNull && entityIdsOrNull.length) {
                msg.entity_ids = entityIdsOrNull.slice();
              }
              // Bypass our wrapper's maybeTrim by calling orig directly.
              live.unsub = await live.orig(live.callback, msg, live.options);
              return true;
            } catch (e) {
              try {
                live.unsub = await live.orig(
                  live.callback, { type: 'subscribe_entities' }, live.options
                );
              } catch (e2) {}
              return false;
            } finally {
              live._retargeting = false;
              if (gate) {
                // Let the first compressed burst land, then re-enable defer.
                setTimeout(function() { gate.bypass = false; }, 300);
              }
            }
          }

          async function applyPageLiveSubscribe(conn) {
            var trim = window.__avaEntityTrim;
            if (!trim || trim.enabled === false) return;
            // Parked wins over page-live: unparking is what restores the subscription.
            if (trim._parked) {
              setTrimStatus('parked-skip');
              return;
            }
            // Legacy boot: no captured subscription to retarget. One adopt-reconnect;
            // the re-subscribe re-enters warmup and this retarget runs again after it.
            if (!trim._live && adoptLiveSubscription()) {
              setTrimStatus('adopt-wait');
              return;
            }
            // Never retarget mid-fling — unsub/resub hitch feels like scroll stutter.
            if (window.__avaScrollGate && window.__avaScrollGate.scrolling) {
              schedulePageLive(conn);
              return;
            }
            var cls = classifyPath();
            // Non-Lovelace / dashboard editor: keep (or restore) full firehose.
            if (cls.kind === 'ignore' || cls.kind === 'config-lovelace') {
              if (trim._mode !== 'full') {
                var okFull = await retargetEntitySubscribe(null);
                if (okFull) {
                  trim._mode = 'full';
                  trim.pendingIds = null;
                  trim._pathKey = null;
                  setTrimStatus(cls.kind === 'config-lovelace' ? 'page-full-config' : 'page-full');
                }
              }
              return;
            }
            var collected = null;
            try {
              collected = await collectCurrentPageEntityIds(conn);
            } catch (e) {
              setTrimStatus('page-collect-error');
              return;
            }
            var ids = (collected && collected.ids) || [];
            var fromConfig = (collected && collected.fromConfig) || [];
            var opaqueReason = (collected && collected.opaqueReason) || null;
            var minIds = trim.minIds || $TRIM_MIN_IDS;
            try {
              trim._lastFromConfig = fromConfig;
              trim._lastFromDom = (collected && collected.fromDom) || [];
            } catch (e) {}

            async function keepFull(reason) {
              if (trim._mode !== 'full') {
                var back = await retargetEntitySubscribe(null);
                if (back) {
                  trim._mode = 'full';
                  trim.pendingIds = null;
                  trim._pathKey = null;
                }
              }
              setTrimStatus('page-keep-full:' + reason);
            }

            // Opaque / indirect cards (UIX Foundry, auto-entities, templates…):
            // hard-trim by id is unsafe, but domains of the ids we *did* find are still
            // a safe middle gear — sensor/light stay live, automation/camera/update drop.
            // Strategy / truncated scans with zero discovery still keepFull.
            if (opaqueReason) {
              var opaqueDomains = domainsFromIds(ids);
              var expanded = expandDomainsFromHass(opaqueDomains);
              if (expanded && expanded.length >= minIds) {
                var pathKeyO = '';
                try {
                  pathKeyO = (location.pathname || '') + (location.hash || '');
                } catch (e) {}
                if (trim._mode === 'domain' && sameIdSet(trim.pendingIds, expanded)) {
                  setTrimStatus('domain-live:' + expanded.length + ':' + opaqueReason);
                  return;
                }
                var okDom = await retargetEntitySubscribe(expanded);
                if (okDom) {
                  trim.pendingIds = expanded;
                  trim.warmedUp = true;
                  trim._mode = 'domain';
                  trim._pathKey = pathKeyO;
                  setTrimStatus('domain-live:' + expanded.length + ':' + opaqueReason);
                  return;
                }
              }
              await keepFull(opaqueReason);
              return;
            }
            // DOM-only lists miss mushroom/button-card/etc. → service works, UI stale.
            // Only page-live trim when Lovelace view config yielded entity ids.
            if (!fromConfig.length) {
              await keepFull('no-config');
              return;
            }
            if (!ids.length || ids.length < minIds) {
              await keepFull(String(ids.length));
              return;
            }
            var pathKey = '';
            try { pathKey = (location.pathname || '') + (location.hash || ''); } catch (e) {}
            // Same view: only grow the allowlist (lazy cards appear after first paint).
            if (trim._mode === 'page' && trim._pathKey === pathKey &&
                Array.isArray(trim.pendingIds) && trim.pendingIds.length) {
              var grown = {};
              var g;
              for (g = 0; g < trim.pendingIds.length; g++) grown[trim.pendingIds[g]] = 1;
              for (g = 0; g < ids.length; g++) grown[ids[g]] = 1;
              ids = Object.keys(grown);
            }
            if (trim._mode === 'page' && sameIdSet(trim.pendingIds, ids)) {
              setTrimStatus('page-live:' + ids.length);
              return;
            }
            var ok = await retargetEntitySubscribe(ids);
            if (ok) {
              trim.pendingIds = ids;
              trim.warmedUp = true;
              trim._mode = 'page';
              trim._pathKey = pathKey;
              try { trim.lastOpaque = null; } catch (e) {}
              setTrimStatus('page-live:' + ids.length);
            } else {
              setTrimStatus('page-retarget-error');
            }
          }

          // Diagnostics: opaque rule table + scanner (Web Console).
          try {
            syncOpaqueDiag();
            window.__avaFindOpaqueReason = findOpaqueReason;
            window.__avaSetUserOpaqueTypes = setUserOpaqueTypes;
            window.__avaCollectCurrentPageEntityIds = collectCurrentPageEntityIds;
            window.__avaWriteTrimSnapshot = writeSnapshot;
            // Kotlin may have pushed types before boot finished installing.
            if (Object.prototype.hasOwnProperty.call(window, '__avaPendingOpaqueTypes')) {
              try { setUserOpaqueTypes(window.__avaPendingOpaqueTypes); } catch (e2) {}
              try { delete window.__avaPendingOpaqueTypes; } catch (e2) {}
            }
          } catch (e) {}

          function schedulePageLive(conn) {
            var trim = window.__avaEntityTrim;
            if (!trim) return;
            if (trim._retargetTimer) clearTimeout(trim._retargetTimer);
            trim._retargetTimer = setTimeout(function() {
              applyPageLiveSubscribe(conn);
            }, $TRIM_RETARGET_DEBOUNCE_MS);
          }

          function ensurePageLiveWatch(conn) {
            var trim = window.__avaEntityTrim;
            if (!trim || trim._watchInstalled) return;
            trim._watchInstalled = true;
            var onNav = function() {
              // New view mounts fresh cards: re-verify the walk and re-arm chunk CSS.
              try {
                if (typeof window.__avaResetScrollerHooks === 'function') {
                  window.__avaResetScrollerHooks();
                }
              } catch (e) {}
              schedulePageLive(conn);
            };
            try {
              window.addEventListener('location-changed', onNav);
              window.addEventListener('popstate', onNav);
              window.addEventListener('hashchange', onNav);
            } catch (e) {}
          }

          window.__avaArmEntityTrim = function() {
            var trim = window.__avaEntityTrim;
            if (!trim || trim.enabled === false) return;
            var live = trim._live;
            var conn = (live && live.conn) || null;
            if (!conn) {
              try {
                var ha = document.querySelector('home-assistant');
                conn = ha && ha.hass && ha.hass.connection;
              } catch (e) {}
            }
            if (!conn) return;
            trim.warmedUp = false;
            trim._armScheduled = false;
            scheduleWarmupArm(conn);
          };

          function scheduleWarmupArm(conn) {
            var trim = window.__avaEntityTrim;
            if (!trim || trim.enabled === false || trim._armScheduled) return;
            trim._armScheduled = true;
            ensurePageLiveWatch(conn);
            var tries = 0;
            var poll = setInterval(function() {
              tries++;
              var nStates = 0;
              var ui = null;
              try {
                var ha = document.querySelector('home-assistant');
                if (ha && ha.hass && ha.hass.states) {
                  nStates = Object.keys(ha.hass.states).length;
                }
                // home-assistant-main exists before any card mounts — waiting on
                // it made page-live retarget fire mid first paint.
                ui = document.querySelector(
                  'hui-root, ha-panel-lovelace'
                );
              } catch (e) {}
              if ((nStates > 0 && ui) || tries >= $TRIM_WARMUP_MAX_TRIES) {
                clearInterval(poll);
                setTimeout(function() {
                  trim.warmedUp = true;
                  applyPageLiveSubscribe(conn);
                }, $TRIM_WARMUP_SETTLE_MS);
              }
            }, $TRIM_WARMUP_POLL_MS);
          }

          async function maybeTrim(conn, message) {
            var trim = window.__avaEntityTrim;
            if (!trim || trim.enabled === false) {
              if (trim) trim.lastStatus = 'disabled';
              return;
            }
            var cls = classifyPath();
            if (cls.kind === 'ignore' || cls.kind === 'config-lovelace') {
              trim.lastStatus = 'skip-panel';
              return;
            }
            // Always let the first subscribe through unfiltered so cards can mount;
            // page-live retarget runs after settle via unsubscribe+subscribe.
            if (!trim.warmedUp || trim._mode !== 'page' ||
                !Array.isArray(trim.pendingIds) || !trim.pendingIds.length) {
              trim.lastStatus = 'warmup-skip';
              scheduleWarmupArm(conn);
              return;
            }
            message.entity_ids = trim.pendingIds.slice();
            trim.lastStatus = 'trimmed:' + message.entity_ids.length;
          }

          function wrapConn(conn) {
            if (!conn || typeof conn.subscribeMessage !== 'function') return false;
            if (conn.__avaSubWrapped) return true;
            conn.__avaSubWrapped = true;
            var orig = conn.subscribeMessage.bind(conn);
            conn.subscribeMessage = async function(callback, message, options) {
              if (message && message.type === 'subscribe_entities') {
                var trim = window.__avaEntityTrim || {};
                // Retarget calls live.orig directly (skips this wrapper).
                var needsTrim = !message.entity_ids ||
                  (Array.isArray(message.entity_ids) && message.entity_ids.length === 0);
                if (needsTrim) {
                  try { await maybeTrim(conn, message); } catch (e) {
                    setTrimStatus('trim-error');
                  }
                }
                var cb = wrapCallback(callback);
                var unsub = await orig(cb, message, options);
                trim._live = {
                  conn: conn,
                  orig: orig,
                  callback: cb,
                  options: options,
                  unsub: unsub,
                  _retargeting: false
                };
                window.__avaEntityTrim = trim;
                // Adopt-reconnect finished: the park requested while _live was
                // missing can now retarget for real.
                if (trim._parkAfterAdopt) {
                  trim._parkAfterAdopt = false;
                  setTimeout(function() {
                    try { window.__avaSetEntitiesParked(true); } catch (e) {}
                  }, 0);
                }
                if (needsTrim && trim.enabled !== false) scheduleWarmupArm(conn);
                return unsub;
              }
              return orig(callback, message, options);
            };
            if (window.__avaWsSteward) {
              window.__avaWsSteward._subPatched = true;
            }
            setTrimStatus('wrapped');
            ensurePageLiveWatch(conn);
            return true;
          }

          function resolveConn(pair) {
            return (pair && pair.conn) || pair || null;
          }

          function attachProm(prom, why) {
            if (!prom || typeof prom.then !== 'function') return;
            prom.then(function(pair) {
              try {
                var conn = resolveConn(pair);
                if (wrapConn(conn)) {
                  // Wrap only. Never kick-resub / suspend here — reconnect was
                  // restoring HA's saved light theme and flipping Ava via the listener.
                  setTrimStatus('wrapped:' + why);
                } else {
                  setTrimStatus('wrap-failed:' + why);
                }
              } catch (e) {
                setTrimStatus('attach-error');
              }
            });
          }

          // Strongest hook: intercept assignment of window.hassConnection so our
          // .then(wrap) is registered BEFORE core.ts registers subscribeEntities.
          try {
            var _hc = window.hassConnection;
            Object.defineProperty(window, 'hassConnection', {
              configurable: true,
              enumerable: true,
              get: function() { return _hc; },
              set: function(prom) {
                _hc = prom;
                setTrimStatus('hc-assigned');
                attachProm(prom, 'setter');
              }
            });
            setTrimStatus('hc-intercept');
            if (_hc) attachProm(_hc, 'preexisting');
          } catch (e) {
            setTrimStatus('hc-intercept-fail');
          }

          var prevReady = window.hassConnectionReady;
          window.hassConnectionReady = function(prom) {
            setTrimStatus('ready-hook');
            if (typeof prevReady === 'function') {
              try { prevReady(prom); } catch (e) {}
            }
            attachProm(prom, 'ready');
          };

          // Poll backup: HA element may expose connection after first paint.
          var polls = 0;
          var pollId = setInterval(function() {
            polls++;
            try {
              var ha = document.querySelector('home-assistant');
              var c = ha && ha.hass && ha.hass.connection;
              if (c && wrapConn(c)) {
                clearInterval(pollId);
              }
            } catch (e) {}
            if (polls >= 80) clearInterval(pollId);
          }, 50);
        })();
    """.trimIndent()

    private const val STEWARD_INSTALL = """
        if (!window.__avaWsSteward) {
            window.__avaWsSteward = {
                enabled: true,
                /** Off until Kotlin setStream(true). Must not default on: boot races HA. */
                stream: false,
                suspended: false,
                parked: false,
                _resolve: null,
                lite: false,
                _liteMs: $LITE_FLUSH_MS,
                _liteTimer: null,
                _subPatched: false,
                _conn: function() {
                    try {
                        var ha = document.querySelector('home-assistant');
                        if (!ha || !ha.hass) return null;
                        return ha.hass.connection || null;
                    } catch (e) { return null; }
                },
                _ensureSubPatch: function() {
                    if (this._subPatched) return true;
                    var conn = this._conn();
                    if (conn && conn.__avaSubWrapped) {
                        this._subPatched = true;
                        return true;
                    }
                    if (!conn || typeof conn.subscribeMessage !== 'function') return false;
                    // Late fallback (no document-start): scroll/lite wrap only.
                    var orig = conn.subscribeMessage.bind(conn);
                    conn.__avaSubWrapped = true;
                    conn.subscribeMessage = async function(callback, message, options) {
                        if (message && message.type === 'subscribe_entities' &&
                            typeof callback === 'function') {
                            var wrapped = function(event) {
                                if (typeof window.__avaNoteEntityEvent === 'function') {
                                    window.__avaNoteEntityEvent(event);
                                }
                                var gate = window.__avaScrollGate;
                                if (gate && gate.shouldDefer()) {
                                    gate.enqueue(callback, event);
                                    return;
                                }
                                callback(event);
                            };
                            return orig(wrapped, message, options);
                        }
                        return orig(callback, message, options);
                    };
                    this._subPatched = true;
                    return true;
                },
                _liteFlush: function() {
                    var gate = window.__avaScrollGate;
                    if (gate) gate.flush();
                },
                /**
                 * @param on
                 * @param flushMs batching window; deeper tiers pass a slower cadence so
                 *   entity churn costs a few coalesced applies per minute, not per frame.
                 * @param chunk callbacks applied per animation frame while draining.
                 */
                setLite: function(on, flushMs, chunk) {
                    if (on && this.enabled === false) return 'steward-disabled';
                    if (on && this.stream === false) return 'stream-off';
                    if (on) {
                        if (!this._ensureSubPatch()) return 'no-ha';
                        var ms = flushMs > 0 ? flushMs : $LITE_FLUSH_MS;
                        if (chunk > 0 && window.__avaScrollGate) {
                            window.__avaScrollGate.flushChunk = chunk;
                        }
                        // Already lite at a different cadence: retime, do not double-arm.
                        if (this.lite && this._liteMs === ms) return 'already-lite';
                        if (this._liteTimer) {
                            clearInterval(this._liteTimer);
                            this._liteTimer = null;
                        }
                        this.lite = true;
                        this._liteMs = ms;
                        var self = this;
                        this._liteTimer = setInterval(function() {
                            // Do not flush mid-gesture; scroll-end handler will —
                            // unless the batch is old enough that waiting looks frozen.
                            var g = window.__avaScrollGate;
                            var due = g && typeof g.overdue === 'function' &&
                                g.overdue($SCROLL_GATE_MAX_DEFER_MS);
                            if (g && g.scrolling && !due) return;
                            self._liteFlush();
                        }, ms);
                        return 'lite-on:' + ms;
                    }
                    if (!this.lite) return 'lite-already-off';
                    this.lite = false;
                    if (this._liteTimer) {
                        clearInterval(this._liteTimer);
                        this._liteTimer = null;
                    }
                    this._liteFlush();
                    return 'lite-off';
                },
                /**
                 * Middle dormancy gear: zero entity traffic without dropping the socket.
                 * Preferred over suspend() for everything short of real memory pressure.
                 */
                /**
                 * Visibility spoof. HA's official dormancy machinery (immediate
                 * reconnect gating, then after its own 5-min timers window.stop() +
                 * connection.suspend() and a FULL panel DOM detach) is driven purely
                 * by document.hidden — which a covered-but-attached WebView never
                 * reports. Forcing hidden lets HA run its own suspend path instead
                 * of us re-implementing it. Off must force visible, not passthrough:
                 * onPause / overlay resume often still reads native hidden, which
                 * would leave HA on the path we just woke.
                 */
                setPageHidden: function(on) {
                    try {
                        if (!this._visInstalled) {
                            var self = this;
                            var dh = null, ds = null;
                            try {
                                dh = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
                                ds = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                            } catch (e) {}
                            if (!dh || !dh.get) return 'vis-unsupported';
                            this._visForced = null;
                            Object.defineProperty(document, 'hidden', {
                                configurable: true,
                                get: function() {
                                    return self._visForced === null
                                        ? dh.get.call(document) : self._visForced;
                                }
                            });
                            if (ds && ds.get) {
                                Object.defineProperty(document, 'visibilityState', {
                                    configurable: true,
                                    get: function() {
                                        if (self._visForced === null) return ds.get.call(document);
                                        return self._visForced ? 'hidden' : 'visible';
                                    }
                                });
                            }
                            this._visInstalled = true;
                        }
                        this._bindVisLatch();
                        var want = !!on;
                        if (this._visForced === want) return 'vis-nochange';
                        this._visForced = want;
                        try { document.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
                        if (!on) {
                            // Wake HA fully: _onHidden armed a once-only window focus
                            // listener, and the Page Lifecycle resume path re-checks
                            // visibility (re-attaches a detached panel).
                            try { document.dispatchEvent(new Event('resume')); } catch (e) {}
                            try { window.dispatchEvent(new Event('focus')); } catch (e) {}
                        }
                        return on ? 'vis-hidden' : 'vis-restored';
                    } catch (e) {
                        return 'vis-error';
                    }
                },
                /** Hide spoofs hidden; show holds visible. Engine onPause/onResume must not win. */
                _bindVisLatch: function() {
                    if (this._visLatchBound) return;
                    this._visLatchBound = true;
                    var self = this;
                    var punch = function() {
                        if (self._visForced !== false) return;
                        if (self._visLatchQuiet) return;
                        self._visLatchQuiet = true;
                        try { document.dispatchEvent(new Event('resume')); } catch (e) {}
                        try { window.dispatchEvent(new Event('focus')); } catch (e) {}
                        self._visLatchQuiet = false;
                    };
                    try { document.addEventListener('visibilitychange', punch, true); } catch (e) {}
                    try { document.addEventListener('freeze', punch, true); } catch (e) {}
                    try { window.addEventListener('pageshow', punch, true); } catch (e) {}
                },
                setParked: function(on) {
                    // Unpark must always run: setEnabled(false) flips the flag before
                    // its cleanup calls, so guarding `off` here would strand the page
                    // parked (and visibility-spoofed) after a steward disable.
                    if (on && this.enabled === false) return 'steward-disabled';
                    if (on && this.stream === false) return 'stream-off';
                    // Parked ⟺ the page believes it is hidden, so HA's own dormancy
                    // (reconnect gate now, suspend + DOM detach on its timers) runs
                    // alongside the zero-traffic park below.
                    var vis = this.setPageHidden(!!on);
                    if (typeof window.__avaSetEntitiesParked !== 'function') {
                        return 'park-unsupported|' + vis;
                    }
                    this.parked = !!on;
                    return window.__avaSetEntitiesParked(!!on) + '|' + vis;
                },
                /** Off-screen cards skip layout/paint/raster; data keeps flowing. */
                setChunkedRendering: function(on) {
                    if (typeof window.__avaSetChunkedRendering !== 'function') {
                        return 'chunk-unsupported';
                    }
                    return window.__avaSetChunkedRendering(!!on);
                },
                setTrim: function(on) {
                    window.__avaEntityTrim = window.__avaEntityTrim || {};
                    window.__avaEntityTrim.enabled = !!on;
                    try {
                        if (window.__avaStewardConsole &&
                            typeof window.__avaStewardConsole.setEnabled === 'function') {
                            window.__avaStewardConsole.setEnabled(!!on);
                        }
                    } catch (e) {}
                    if (!on && typeof window.__avaRestoreFullEntities === 'function') {
                        try { window.__avaRestoreFullEntities(); } catch (e2) {}
                        return 'trim-off';
                    }
                    if (!on) return 'trim-off';
                    // Live enable: bootJs exposes arm() to retarget an existing subscribe.
                    try {
                        if (typeof window.__avaArmEntityTrim === 'function') {
                            window.__avaArmEntityTrim();
                        }
                    } catch (e3) {}
                    return 'trim-on';
                },
                setUserOpaqueTypes: function(types) {
                    // bootJs owns the table and exports window.__avaSetUserOpaqueTypes.
                    // This object is installed later — never call the local bootJs name.
                    if (typeof window.__avaSetUserOpaqueTypes === 'function') {
                        return window.__avaSetUserOpaqueTypes(types);
                    }
                    window.__avaPendingOpaqueTypes = types;
                    return 'opaque-types:pending';
                },
                /**
                 * Pause CSS animations/transitions (dormant path). Does not touch Lit
                 * or pauseTimers — screensaver WebView must keep running.
                 */
                setFreezeAnimations: function(on) {
                    try {
                        var root = document.documentElement;
                        var id = 'ava-freeze-anim-style';
                        if (on) {
                            if (!document.getElementById(id)) {
                                var s = document.createElement('style');
                                s.id = id;
                                s.textContent =
                                    'html.ava-freeze-anim, html.ava-freeze-anim * {' +
                                    'animation-play-state:paused!important;' +
                                    'transition:none!important;}';
                                (document.head || root).appendChild(s);
                            }
                            root.classList.add('ava-freeze-anim');
                            this._freezeAnim = true;
                            return 'freeze-on';
                        }
                        root.classList.remove('ava-freeze-anim');
                        this._freezeAnim = false;
                        return 'freeze-off';
                    } catch (e) {
                        return 'freeze-error';
                    }
                },
                /**
                 * Bounded shadow-DOM walk collecting media elements. HA renders every
                 * card inside nested shadow roots, so a light-DOM querySelectorAll
                 * finds nothing; MJPEG camera streams are plain <img> tags that keep
                 * a socket + decoder alive for as long as src is set.
                 */
                _collectMedia: function() {
                    var media = [];
                    var imgs = [];
                    var budget = 6000;
                    function visit(node) {
                        if (budget <= 0 || !node) return;
                        budget--;
                        try {
                            var tag = node.tagName;
                            if (tag === 'VIDEO' || tag === 'AUDIO') {
                                media.push(node);
                            } else if (tag === 'IMG') {
                                var src = node.currentSrc || node.src || '';
                                if (src.indexOf('/api/camera_proxy_stream/') !== -1 ||
                                    src.indexOf('mjpeg') !== -1) {
                                    imgs.push(node);
                                }
                            }
                        } catch (e) {}
                        try { if (node.shadowRoot) visit(node.shadowRoot); } catch (e) {}
                        try {
                            var kids = node.children;
                            if (kids) {
                                for (var i = 0; i < kids.length && budget > 0; i++) {
                                    visit(kids[i]);
                                }
                            }
                        } catch (e) {}
                    }
                    try { visit(document.body || document.documentElement); } catch (e) {}
                    return { media: media, imgs: imgs };
                },
                /**
                 * Pause media elements while dormant. Remembers which ones were
                 * playing so uncover can best-effort resume (no pauseTimers).
                 * MJPEG <img> streams get a blank src (page is covered/hidden, the
                 * broken-image frame is never visible) and restore on resume.
                 */
                setPauseMedia: function(on) {
                    try {
                        var found = this._collectMedia();
                        if (on) {
                            this._pausedMedia = this._pausedMedia || [];
                            for (var i = 0; i < found.media.length; i++) {
                                var el = found.media[i];
                                try {
                                    if (!el.paused) {
                                        this._pausedMedia.push(el);
                                        el.pause();
                                    }
                                } catch (e2) {}
                            }
                            this._blankedImgs = this._blankedImgs || [];
                            for (var k = 0; k < found.imgs.length; k++) {
                                var img = found.imgs[k];
                                try {
                                    if (!img.__avaMjpegSrc) {
                                        img.__avaMjpegSrc = img.src;
                                        img.src =
                                            'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';
                                        this._blankedImgs.push(img);
                                    }
                                } catch (e4) {}
                            }
                            this._pauseMedia = true;
                            return 'media-paused:' + (this._pausedMedia.length || 0) +
                                '+mjpeg:' + (this._blankedImgs.length || 0);
                        }
                        var prev = this._pausedMedia || [];
                        for (var j = 0; j < prev.length; j++) {
                            try { prev[j].play(); } catch (e3) {}
                        }
                        var blanked = this._blankedImgs || [];
                        for (var m = 0; m < blanked.length; m++) {
                            try {
                                if (blanked[m].__avaMjpegSrc) {
                                    blanked[m].src = blanked[m].__avaMjpegSrc;
                                    blanked[m].__avaMjpegSrc = null;
                                }
                            } catch (e5) {}
                        }
                        this._pausedMedia = [];
                        this._blankedImgs = [];
                        this._pauseMedia = false;
                        return 'media-resumed:' + prev.length + '+mjpeg:' + blanked.length;
                    } catch (e) {
                        return 'media-error';
                    }
                },
                /**
                 * Close a forgotten more-info dialog. HA's history charts inside the
                 * dialog keep subscriptions and leak while it stays open (frontend
                 * issue #25888); a covered wall panel can hold one open for days.
                 * Dispatching hass-more-info with a null entityId is the supported
                 * close path (same technique browser_mod uses).
                 */
                closeDialogs: function() {
                    try {
                        var ha = document.querySelector('home-assistant');
                        if (!ha) return 'no-ha';
                        ha.dispatchEvent(new CustomEvent('hass-more-info', {
                            detail: { entityId: null },
                            bubbles: true,
                            composed: true
                        }));
                        return 'dialogs-closed';
                    } catch (e) {
                        return 'dialog-error';
                    }
                },
                /**
                 * Entity-stream scheduling sub-switch. Off = no scroll deferral, lite,
                 * or park; chunked rendering / dormant quiet stay independently gated.
                 */
                setStream: function(on) {
                    this.stream = !!on;
                    if (!on) {
                        try { this.setLite(false); } catch (e) {}
                        try { this.setParked(false); } catch (e) {}
                        if (window.__avaScrollGate) {
                            window.__avaScrollGate.disabled = true;
                            try { window.__avaScrollGate._release(); } catch (e) {}
                        }
                        return 'stream-off';
                    }
                    if (this.enabled !== false && window.__avaScrollGate) {
                        window.__avaScrollGate.disabled = false;
                        // HTML onPageFinished is not Lovelace-ready. Keep entity
                        // delivery live through the first-paint layout scrolls.
                        try { window.__avaScrollGate.armFirstPaint($FIRST_PAINT_GRACE_MS); } catch (e) {}
                    }
                    this._ensureSubPatch();
                    return 'stream-on';
                },
                setEnabled: function(on) {
                    this.enabled = !!on;
                    if (!on) {
                        try { this.setLite(false); } catch (e) {}
                        // Steward off always clears trim; trim is opt-in when on.
                        try { this.setTrim(false); } catch (e) {}
                        try { this.setFreezeAnimations(false); } catch (e) {}
                        try { this.setPauseMedia(false); } catch (e) {}
                        try { this.setParked(false); } catch (e) {}
                        try { this.setChunkedRendering(false); } catch (e) {}
                        try { this.setStream(false); } catch (e) {}
                        if (window.__avaScrollGate) {
                            window.__avaScrollGate.disabled = true;
                            try { window.__avaScrollGate._release(); } catch (e) {}
                        }
                        return 'steward-off';
                    }
                    // Stream sub-switch owns scroll-gate; Kotlin re-applies it next.
                    if (window.__avaScrollGate) {
                        window.__avaScrollGate.disabled = this.stream === false;
                    }
                    // Do not auto-enable entity trim — Kotlin applies trim separately.
                    this._ensureSubPatch();
                    return 'steward-on';
                },
                trimStatus: function() {
                    var t = window.__avaEntityTrim;
                    if (!t) return 'no-trim';
                    var parts = [String(t.lastStatus || '')];
                    if (t.lastOpaque) parts.push('opaque=' + t.lastOpaque);
                    if (t._mode) parts.push('mode=' + t._mode);
                    if (t.enabled === false) parts.push('trim-off');
                    return parts.join('|');
                },
                suspend: function() {
                    if (this.enabled === false) return 'steward-disabled';
                    if (this.stream === false) return 'stream-off';
                    if (this.suspended) return 'already-suspended';
                    var conn = this._conn();
                    if (!conn) return 'no-ha';
                    if (typeof conn.suspendReconnectUntil !== 'function' ||
                        typeof conn.suspend !== 'function') return 'unsupported';
                    this._liteFlush();
                    this._ensureSubPatch();
                    var self = this;
                    var suspendGate = new Promise(function(resolve) { self._resolve = resolve; });
                    try {
                        // Reconnect gate FIRST, then the official Page Lifecycle
                        // freeze event: HA's handler runs window.stop() (aborts all
                        // in-flight loads incl. MJPEG camera streams) and its own
                        // connection.suspend() with proper resubscribe bookkeeping.
                        // The manual suspend below is the fallback for cores whose
                        // frontend predates the freeze listener; double-suspend is a
                        // no-op in home-assistant-js-websocket.
                        conn.suspendReconnectUntil(suspendGate);
                        try { document.dispatchEvent(new Event('freeze')); } catch (e) {}
                        conn.suspend();
                        this.suspended = true;
                        return 'suspended';
                    } catch (e) {
                        this._resolve = null;
                        return 'error';
                    }
                },
                resume: function() {
                    if (this.enabled === false) return 'steward-disabled';
                    this._ensureSubPatch();
                    if (!this.suspended) return 'not-suspended';
                    this.suspended = false;
                    var resolve = this._resolve;
                    this._resolve = null;
                    if (resolve) { try { resolve(); } catch (e) {} }
                    // Official resume path: HA re-checks visibility, resolves its own
                    // reconnect gate and re-attaches a detached panel.
                    try { document.dispatchEvent(new Event('resume')); } catch (e) {}
                    try { window.dispatchEvent(new Event('focus')); } catch (e) {}
                    return 'resumed';
                }
            };
        }
    """

    val suspendJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.suspend();
        })();
    """.trimIndent()

    val resumeJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.resume();
        })();
    """.trimIndent()

    /** @param flushMs batching window for this tier; @param chunk applies per frame. */
    fun liteOnJs(flushMs: Int, chunk: Int): String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setLite(true, $flushMs, $chunk);
        })();
    """.trimIndent()

    /** Default cadence, used when re-asserting lite on a freshly loaded page. */
    val liteOnJs: String = liteOnJs(LITE_FLUSH_MS, SCROLL_GATE_FLUSH_CHUNK)

    val parkOnJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setParked(true);
        })();
    """.trimIndent()

    val parkOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setParked(false);
        })();
    """.trimIndent()

    val streamOnJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setStream(true);
        })();
    """.trimIndent()

    val streamOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setStream(false);
        })();
    """.trimIndent()

    val chunkedRenderingOnJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setChunkedRendering(true);
        })();
    """.trimIndent()

    val chunkedRenderingOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setChunkedRendering(false);
        })();
    """.trimIndent()

    val liteOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setLite(false);
        })();
    """.trimIndent()

    val freezeAnimationsOnJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setFreezeAnimations(true);
        })();
    """.trimIndent()

    val freezeAnimationsOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setFreezeAnimations(false);
        })();
    """.trimIndent()

    val pauseMediaOnJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setPauseMedia(true);
        })();
    """.trimIndent()

    val pauseMediaOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setPauseMedia(false);
        })();
    """.trimIndent()

    /** One-shot: close a lingering more-info dialog (leak source); nothing to undo. */
    val closeDialogsJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.closeDialogs();
        })();
    """.trimIndent()

    val trimOnJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setTrim(true);
        })();
    """.trimIndent()

    val trimOffJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setTrim(false);
        })();
    """.trimIndent()

    val enableJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setEnabled(true);
        })();
    """.trimIndent()

    val disableJs: String = """
        (function() {
            $STEWARD_INSTALL
            return window.__avaWsSteward.setEnabled(false);
        })();
    """.trimIndent()

    /**
     * Push opaque-type table.
     * `null` → restore built-ins; empty list → clear type passthrough; else replace.
     */
    fun setUserOpaqueTypesJs(types: List<String>?): String {
        val arg = if (types == null) {
            "null"
        } else {
            val arr = org.json.JSONArray()
            for (t in types) {
                val s = t.trim().lowercase()
                if (s.isNotEmpty()) arr.put(s)
            }
            arr.toString()
        }
        return """
            (function() {
                $STEWARD_INSTALL
                return window.__avaWsSteward.setUserOpaqueTypes($arg);
            })();
        """.trimIndent()
    }

    val installJs: String = """
        (function() {
            $STEWARD_INSTALL
            window.__avaWsSteward._ensureSubPatch();
            return 'ok';
        })();
    """.trimIndent()

    fun installOnWebView(webView: WebView) {
        // Best-effort: isFeatureSupported() only reflects the support-library
        // boundary, not whether this WebView build honors it — never the sole
        // channel, and never allowed to crash setup.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, bootJs, setOf("*"))
            }
        }
        // [installEarlyOnPage] (commit) and [ensureInstalledOnPage] (finish)
        // re-deliver per navigation; bootJs self-guards on __avaWsBoot.
    }

    /**
     * Early boot, called from commit: the best remaining chance to beat HA
     * `core.js` to the `hassConnection` hook when document-start delivery
     * didn't run. Unconditional — bootJs self-guards, and gating on
     * isFeatureSupported() left engines with a lying boundary bootless.
     */
    fun installEarlyOnPage(webView: WebView) {
        webView.evaluateJavascript(bootJs, null)
    }

    fun ensureInstalledOnPage(webView: WebView) {
        webView.evaluateJavascript(bootJs, null)
        webView.evaluateJavascript(installJs, null)
    }
}
