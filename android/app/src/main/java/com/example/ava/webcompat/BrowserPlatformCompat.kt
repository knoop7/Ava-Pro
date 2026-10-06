package com.example.ava.webcompat

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Cross-cutting WebView platform fixes for Home Assistant Lovelace dashboards.
 *
 * Shadow-DOM retargeting: many HA cards (including WebRTC `ui: true`) read `event.target.icon`
 * on delegated clicks. When the tap lands on an inner node, `icon` is undefined. We retarget
 * `event.target` to the custom-element host in capture phase — before card handlers run — without
 * duplicating per-card action logic.
 *
 * Legacy DOM polyfills: HA's legacy (ES5) bundle still calls a few APIs its own
 * Chrome >= 59 floor does not have. Observed on Chromium 83: `element.getAnimations()`
 * (84+) throws from `frontend_es5/app.*.js` on every view transition, which both spams
 * errors and skips HA's animation cleanup. An empty-array stub is semantically safe
 * ("no running animations") and a no-op on engines that already have the API.
 * Also carries runtime-API polyfills for what a scan of the installed custom cards
 * actually calls (structuredClone 98+, Promise.any 85+, replaceChildren 86+,
 * reportError 100+, plus replaceAll/at/Object.hasOwn as cheap insurance) — the oxc
 * transpiler lowers syntax, not APIs. A floor-69 pack (globalThis 71+,
 * queueMicrotask 71+, Object.fromEntries 73+, String.matchAll 73+,
 * Promise.allSettled 76+, an attachInternals 77+ stub) keeps WebView-69-class
 * devices viable: UIX alone calls globalThis 10x, and HA's Web Awesome form
 * controls die in their constructor without attachInternals.
 * Intl floor-69 shims: the es5 bundle calls Intl.RelativeTimeFormat (71+,
 * 37 chunks, unguarded — every relative timestamp), Intl.ListFormat (72+, 20)
 * and Intl.Locale (74+, 28); zh/en shape-matched shims cover HA's exact call
 * sites. Intl.NumberFormat style:"unit" (77+, 46 uses) throws RangeError on
 * older ICU ("unit" is an invalid VALUE of a known option, unlike
 * notation/timeStyle which are unknown options and silently ignored) — a
 * probe-gated wrapper strips the unit options and formats the bare number.
 * Array.prototype.findLast is deliberately NOT polyfilled: HA's
 * index.html uses `"findLast" in Array.prototype` to choose the latest-vs-es5
 * build, and faking it would serve this engine an unparseable bundle.
 *
 * hui-image anti-flicker: HA camera-still cards blank out whenever a refresh cannot
 * keep up. Verified in frontend 20260826.1 (`frontend_es5/56076.*.js`, property names
 * are not mangled): `_updateCameraImageSrcAtInterval()` force-calls `_onImageError()`
 * when the previous frame is still loading at the next interval, and a missing entity
 * or failed signed-URL fetch takes the same path — all of them swap the card to an
 * empty `#brokenImage` div until a later frame lands (periodic blinking on slow
 * devices/networks). Patching `_onImageError` to hold the last good frame removes
 * every blank-flash path with one hook; a camera that never produced a frame still
 * shows the genuine broken state.
 *
 * hui-calendar-card anti-flicker: verified in frontend 20260826.1 sources. The card
 * subscribes to the ENTIRE entity registry; every registry push (any integration
 * reload, device rename — nothing to do with calendars) sets `_eventsLoaded=false`,
 * which renders an opaque loading overlay over the card and tears down + resubscribes
 * every calendar event subscription. It only ever reads `options.calendar` (color) of
 * its own entities from the registry, so when that slice is unchanged the patch drops
 * the property from Lit's changedProperties — template output stays identical and the
 * subscriptions stay live. Second gear: the backend pushes `calendar/event/subscribe`
 * updates on every calendar entity update, and each push — identical payload or not —
 * costs a FullCalendar removeAllEventSources + re-add (a visible blink on slow
 * engines). Identical pushes are skipped by an order-insensitive payload
 * fingerprint, and when a push does slip through but produces identical content,
 * the previous `_events` array reference is restored so ha-full-calendar's
 * property check short-circuits. Third gear: ha-full-calendar re-fires
 * `view-changed` with the SAME date window (dialog-close callbacks, midnight
 * ticks) and the stock handler unconditionally blanks the card and tears down
 * every subscription — same-window events are skipped since the live
 * subscriptions already cover the range.
 *
 * Selector compat: HA's ES5 build transpiles *syntax* but not *selector strings*.
 * Observed on Chromium 83: `matches(':focus-visible')` (86+) and
 * `querySelector(':is(ha-radio-option):not([disabled])')` (88+) throw uncaught
 * SyntaxErrors from HA's own chunks on every interaction. The shim retries with a
 * degraded selector (`:focus-visible`→`:focus`, single-arg `:is()/:where()`
 * unwrapped) and otherwise reports "no match" instead of throwing — matching how
 * these code paths behave on the engines HA actually tests. Probe-gated: modern
 * engines take the fast path untouched.
 *
 * JSON.parse tracer (legacy engines only): repeated `Unexpected token u in JSON`
 * rejections from `frontend_es5/core.*.js` have no actionable stack in release
 * logs. The tracer logs the failing input and call stack (rate-limited) and
 * rethrows, so device logs identify the producer instead of just the symptom.
 *
 * CSS compat rewriter: modern HA components (Material 3 `md-*`, new card themes)
 * round corners with *logical* properties — `border-start-start-radius` & co need
 * Chromium 89, `inset:`/`margin-inline:`-style box shorthands need 87, and rules
 * whose selector uses `:is()`/`:where()` need 88. Older engines drop the unknown
 * declaration (or the whole rule) at parse time, so corners render square and
 * overlays lose their placement — and because parsing already discarded them,
 * nothing can be recovered from the CSSOM afterwards. The only viable fix is to
 * rewrite the stylesheet TEXT before it reaches the parser: we hook
 * `CSSStyleSheet.replaceSync/replace/insertRule` (the constructable-stylesheet
 * path every Lit component uses — HA core and most custom cards) plus
 * `HTMLStyleElement.textContent`, mapping logical→physical (LTR) and expanding
 * the box shorthands. Values are split on TOP-LEVEL spaces (paren-aware), so
 * var()/calc() values expand too — HA 2026 spaces everything with
 * `var(--ha-space-N)`/`var(--wa-space-*)` tokens, and the es5 bundle carries
 * 371 logical shorthands whose value is a var()/calc() (88 of them
 * `padding-inline:var(--ha-icon-button-padding)`: dropping it collapsed every
 * icon button to bare-icon size). Logical border shorthands
 * (`border-inline: 1px solid …`, 87+) duplicate their whole value per
 * physical side instead of splitting. Probe-gated via `CSS.supports`, so modern engines return
 * immediately with nothing patched.
 *
 * Selector repair (measured in the es5 bundle of frontend 20260826.1): one
 * unparseable member invalidates the WHOLE selector list, discarding every
 * declaration with it. Multi-arg `:is()` appears in 1094 rules across 210 chunks
 * and `:has()` in 759 — this is why buttons/titles lost their rounded corners.
 * `:is()/:where()` are expanded into the exact selector cross-product; the list
 * is then split on top-level commas and each member is kept iff a
 * fragment.querySelector probe parses it, so `:has()` members degrade
 * individually instead of killing the rule. Level-4 `:not(<list>)` — including
 * `:not(:is(a,b))`/`:not(:-webkit-any(a,b))`, the shape Web Awesome uses for
 * :lang()-based RTL emulation on every wa-button corner-radius rule — is
 * rewritten via De Morgan to the equivalent level-3 chain `:not(a):not(b)`
 * (NEVER expanded as a disjunction, which would invert the semantics). `:focus-visible` in
 * stylesheet text degrades to `:focus` (the JS-API shim already did this for
 * matches/querySelector). HA 2026 also ships Web Awesome (`wa-*`) components
 * whose entire base styles — border-radius included — sit inside
 * `@layer wa-component { … }` blocks (Chromium 99+; dropped whole by older
 * parsers, leaving unstyled square buttons): `@layer` wrappers are unwrapped
 * in source order, which matches the pre-layer cascade.
 *
 * Property-level repairs (all counts from the es5 bundle of 20260826.1):
 * individual transform properties `translate:`/`rotate:`/`scale:` need 104 and
 * appear 660× — wa-switch positions its thumb purely with `translate:`, so on
 * old engines the knob sits centered in BOTH states; they are synthesized into
 * a `transform:` appended to the block (spec order translate·rotate·scale,
 * existing transform last). Unprefixed `appearance:` needs 84 (78×) — without
 * `-webkit-appearance:none` native control chrome bleeds through custom
 * switches/sliders; declarations are duplicated with the prefix. Unprefixed
 * `mask-*` needs 120 (10×) — duplicated as `-webkit-mask-*`. `overflow: clip`
 * (90+) degrades to `hidden`. `aspect-ratio: 1` (88+, 128×) is recovered for
 * the square case when exactly one axis is given a non-percentage size.
 * Single-side logical insets (`inset-inline-start` & co, 87+) map to their
 * physical sides (LTR). For the WebView-69 floor: `clamp()` needs 79 (813×) —
 * an unsupporting engine drops the whole declaration, so it is rewritten to
 * its middle (preferred) argument, which is what a supporting engine computes
 * while the bounds don't bite. Dynamic viewport units need 108 (svh 827×,
 * dvh 140×): dialogs cap themselves with
 * `max-height:var(--dialog-max-height,100dvh)` — var() defers validation past
 * parse time, so this WINS the cascade then computes to nothing, max-height
 * ends up none and popups overflow the screen. In an embedded WebView the
 * small/dynamic/large viewports coincide, so sv-, dv- and lv-units map to the
 * classic units with zero loss (vi/vb to their LTR physical axis). vh tracks the layout
 * viewport, so the user-facing page-scale slider (setInitialScale) keeps
 * working unchanged.
 *
 * Flex gap (spacing): `gap` in a *flex* container needs Chromium 84 — on 83 the
 * property parses (grid has had it since 66) but flex layout ignores it, so HA's
 * settings lists (`ha-md-list{flex-direction:column;gap:8px}`), list-item rows
 * (`gap:16px`) and every `gap:var(--ha-space-N)` collapse to zero spacing. The
 * rewriter emulates it: blocks pairing a flex marker with a gap get the value as
 * inheritable `--ava-gap-row/col` custom properties plus an appended
 * `sel > :not(:first-child) { margin-… }` sibling rule (half-gap on all sides for
 * wrapped chip rows). Inheritance makes md-list-item's `gap:inherit` resolve to
 * the host's value. Detection is a layout probe — `CSS.supports('gap', …)` lies
 * on 83 because the grid property parses fine.
 */
object BrowserPlatformCompat {
    private const val LEGACY_DOM_POLYFILL_JS = """
        (function() {
          try {
            var empty = function() { return []; };
            if (window.Element && !Element.prototype.getAnimations) {
              Element.prototype.getAnimations = empty;
            }
            if (window.Document && !Document.prototype.getAnimations) {
              Document.prototype.getAnimations = empty;
            }
          } catch (err) {}
          // Runtime APIs newer than the frozen engine, observed in installed custom
          // cards (Bubble-Card: structuredClone, card-mod: Promise.any, HACrack:
          // replaceChildren, tailwindcss-template-card: reportError). The oxc
          // transpiler lowers *syntax* only — a missing API still throws at runtime.
          //
          // NEVER polyfill Array.prototype.findLast here: HA's index.html picks the
          // latest-vs-es5 build with `"findLast" in Array.prototype` — faking it
          // makes HA serve the modern bundle this engine cannot parse at all.
          try {
            if (!window.structuredClone) {
              window.structuredClone = function(v) {
                // JSON round-trip: no functions/cycles/Dates — fine for the
                // config/state objects cards actually clone.
                return v === undefined ? v : JSON.parse(JSON.stringify(v));
              };
            }
            if (window.Promise && !Promise.any) {
              Promise.any = function(list) {
                return new Promise(function(resolve, reject) {
                  var arr = Array.prototype.slice.call(list);
                  var pending = arr.length;
                  var errs = new Array(arr.length);
                  if (!pending) { reject(new Error('All promises were rejected')); return; }
                  for (var i = 0; i < arr.length; i++) {
                    (function(idx) {
                      Promise.resolve(arr[idx]).then(resolve, function(e) {
                        errs[idx] = e;
                        if (--pending === 0) {
                          var agg = new Error('All promises were rejected');
                          agg.errors = errs;
                          reject(agg);
                        }
                      });
                    })(i);
                  }
                });
              };
            }
            var addReplaceChildren = function(proto) {
              if (!proto || proto.replaceChildren) return;
              proto.replaceChildren = function() {
                while (this.lastChild) this.removeChild(this.lastChild);
                for (var i = 0; i < arguments.length; i++) {
                  var n = arguments[i];
                  this.appendChild(typeof n === 'string' ? document.createTextNode(n) : n);
                }
              };
            };
            addReplaceChildren(window.Element && Element.prototype);
            addReplaceChildren(window.Document && Document.prototype);
            addReplaceChildren(window.DocumentFragment && DocumentFragment.prototype);
            if (!window.reportError) {
              window.reportError = function(e) { try { console.error(e); } catch (e2) {} };
            }
            if (!String.prototype.replaceAll) {
              String.prototype.replaceAll = function(search, repl) {
                if (search instanceof RegExp) {
                  return String.prototype.replace.call(this, search, repl);
                }
                if (typeof repl === 'function') {
                  var s = String(this), out = '', from = 0, at;
                  if (search === '') return String.prototype.replace.call(s, /(?:)/g, repl);
                  while ((at = s.indexOf(search, from)) !== -1) {
                    out += s.slice(from, at) + repl(search, at, s);
                    from = at + String(search).length;
                  }
                  return out + s.slice(from);
                }
                return this.split(search).join(repl);
              };
            }
            if (!Array.prototype.at) {
              var atImpl = function(n) {
                n = n | 0;
                if (n < 0) n += this.length;
                return (n < 0 || n >= this.length) ? undefined : this[n];
              };
              Array.prototype.at = atImpl;
              if (!String.prototype.at) String.prototype.at = atImpl;
            }
            if (window.Object && !Object.hasOwn) {
              Object.hasOwn = function(o, k) {
                return Object.prototype.hasOwnProperty.call(o, k);
              };
            }
            // Floor-69 pack: engines below 71-76 miss these; all are no-ops from
            // Chromium 76 up. UIX (uix.js) calls globalThis 10x.
            if (typeof globalThis === 'undefined') {
              window.globalThis = window;
            }
            if (!window.queueMicrotask) {
              window.queueMicrotask = function(fn) {
                Promise.resolve().then(fn);
              };
            }
            if (window.Object && !Object.fromEntries) {
              Object.fromEntries = function(entries) {
                var obj = {};
                var arr = Array.from(entries);
                for (var i = 0; i < arr.length; i++) obj[arr[i][0]] = arr[i][1];
                return obj;
              };
            }
            if (!String.prototype.matchAll) {
              // Returns an array — iterable the same way the real iterator is
              // for for-of / spread / Array.from, which is all callers do.
              String.prototype.matchAll = function(re) {
                if (re && re.global === false) {
                  throw new TypeError('matchAll must be called with a global RegExp');
                }
                var rx = new RegExp(re.source, re.flags);
                var s = String(this), out = [], m;
                while ((m = rx.exec(s)) !== null) {
                  out.push(m);
                  if (m.index === rx.lastIndex) rx.lastIndex++;
                }
                return out;
              };
            }
            if (window.Promise && !Promise.allSettled) {
              Promise.allSettled = function(list) {
                var arr = Array.prototype.slice.call(list);
                return Promise.all(arr.map(function(p) {
                  return Promise.resolve(p).then(
                    function(v) { return { status: 'fulfilled', value: v }; },
                    function(e) { return { status: 'rejected', reason: e }; }
                  );
                }));
              };
            }
            // attachInternals needs 77 (102 uses in the es5 bundle — Web Awesome
            // form controls call it in their constructor). Without the stub the
            // call throws and the whole component dies; with it the control
            // renders and works, minus native form association/validation.
            if (window.HTMLElement && HTMLElement.prototype &&
                !HTMLElement.prototype.attachInternals) {
              HTMLElement.prototype.attachInternals = function() {
                var noop = function() {};
                return {
                  setFormValue: noop,
                  setValidity: noop,
                  checkValidity: function() { return true; },
                  reportValidity: function() { return true; },
                  states: {
                    add: noop, 'delete': noop, clear: noop,
                    has: function() { return false; }
                  },
                  form: null,
                  labels: [],
                  willValidate: false,
                  validity: {},
                  validationMessage: '',
                  shadowRoot: null
                };
              };
            }

            // ---- Intl floor-69 shims ----------------------------------------
            // The es5 bundle of 20260826.1 calls these UNGUARDED — measured:
            // Intl.RelativeTimeFormat (71+) in 37 chunks (every "5 minutes ago"
            // throws without it), Intl.ListFormat (72+) in 20, Intl.Locale (74+)
            // in 28. HA dropped its @formatjs polyfills, assuming evergreen
            // browsers. Shims are shape-matched to HA's exact call sites:
            // RTF is only .format(value, unit)/.formatToParts, ListFormat only
            // .format(list), Locale's maximize() calls all sit in try/catch and
            // only compare toString() (weekInfo is probed with `in`, so its
            // absence degrades gracefully).
            if (window.Intl && !Intl.RelativeTimeFormat) {
              (function() {
                var EN = {
                  year: ['year', 'years'], quarter: ['quarter', 'quarters'],
                  month: ['month', 'months'], week: ['week', 'weeks'],
                  day: ['day', 'days'], hour: ['hour', 'hours'],
                  minute: ['minute', 'minutes'], second: ['second', 'seconds']
                };
                var ZH = {
                  year: '年', quarter: '个季度', month: '个月', week: '周',
                  day: '天', hour: '小时', minute: '分钟', second: '秒钟'
                };
                var RTF = function(locales, opts) {
                  var tag = typeof locales === 'string' ? locales
                    : (locales && locales[0]) || 'en';
                  this._locale = tag;
                  this._zh = /^zh/i.test(tag);
                };
                RTF.prototype.format = function(value, unit) {
                  var u = String(unit).replace(/s$/, '');
                  var n = Math.abs(Number(value));
                  if (this._zh) {
                    var zu = ZH[u] || u;
                    if (n === 0) return '现在';
                    return value < 0 ? n + ' ' + zu + '前' : n + ' ' + zu + '后';
                  }
                  var forms = EN[u] || [u, u + 's'];
                  var w = n === 1 ? forms[0] : forms[1];
                  if (n === 0) return 'now';
                  return value < 0 ? n + ' ' + w + ' ago' : 'in ' + n + ' ' + w;
                };
                RTF.prototype.formatToParts = function(value, unit) {
                  return [{ type: 'literal', value: this.format(value, unit) }];
                };
                RTF.prototype.resolvedOptions = function() {
                  return { locale: this._locale, numeric: 'always', style: 'long' };
                };
                RTF.supportedLocalesOf = function() { return []; };
                Intl.RelativeTimeFormat = RTF;
              })();
            }
            if (window.Intl && !Intl.ListFormat) {
              (function() {
                var LF = function(locales, opts) {
                  var tag = typeof locales === 'string' ? locales
                    : (locales && locales[0]) || 'en';
                  this._zh = /^zh/i.test(tag);
                  this._or = !!(opts && opts.type === 'disjunction');
                };
                LF.prototype.format = function(list) {
                  var a = Array.prototype.slice.call(list || []);
                  if (a.length === 0) return '';
                  if (a.length === 1) return String(a[0]);
                  if (this._zh) return a.join(this._or ? '或' : '、');
                  var conj = this._or ? ' or ' : ' and ';
                  return a.slice(0, a.length - 1).join(', ') + conj + a[a.length - 1];
                };
                LF.prototype.formatToParts = function(list) {
                  return [{ type: 'literal', value: this.format(list) }];
                };
                LF.supportedLocalesOf = function() { return []; };
                Intl.ListFormat = LF;
              })();
            }
            if (window.Intl && !Intl.Locale) {
              (function() {
                var Loc = function(tag) {
                  var t = String(tag).replace(/_/g, '-').split('-');
                  this.language = (t[0] || 'und').toLowerCase();
                  var i = 1;
                  this.script = undefined;
                  if (t[i] && t[i].length === 4 && /^[A-Za-z]+$/.test(t[i])) {
                    this.script = t[i].charAt(0).toUpperCase() + t[i].slice(1).toLowerCase();
                    i++;
                  }
                  this.region = undefined;
                  if (t[i] && (/^[A-Za-z]{2}$/.test(t[i]) || /^[0-9]{3}$/.test(t[i]))) {
                    this.region = t[i].toUpperCase();
                  }
                  var parts = [this.language];
                  if (this.script) parts.push(this.script);
                  if (this.region) parts.push(this.region);
                  this.baseName = parts.join('-');
                };
                Loc.prototype.toString = function() { return this.baseName; };
                // No likely-subtags data: identity maximize scores locale
                // matching neutrally; HA wraps every call in try/catch anyway.
                Loc.prototype.maximize = function() { return this; };
                Loc.prototype.minimize = function() { return new Intl.Locale(this.language); };
                Intl.Locale = Loc;
              })();
            }
            // NumberFormat style:"unit" needs 77 (46 uses) — "unit" is an
            // INVALID VALUE for a known option on older ICU, so unlike
            // notation/timeStyle (unknown options, silently ignored) it THROWS
            // a RangeError. Strip the unit options and format the bare number.
            if (window.Intl && Intl.NumberFormat) {
              (function() {
                try {
                  new Intl.NumberFormat('en', { style: 'unit', unit: 'meter' });
                  return;
                } catch (probeErr) {}
                var Native = Intl.NumberFormat;
                var Wrapped = function(locales, opts) {
                  if (opts && opts.style === 'unit') {
                    var clean = {};
                    for (var k in opts) {
                      if (k !== 'style' && k !== 'unit' && k !== 'unitDisplay') clean[k] = opts[k];
                    }
                    return new Native(locales, clean);
                  }
                  return opts === undefined ? new Native(locales) : new Native(locales, opts);
                };
                Wrapped.prototype = Native.prototype;
                Wrapped.supportedLocalesOf = function() {
                  return Native.supportedLocalesOf.apply(Native, arguments);
                };
                Intl.NumberFormat = Wrapped;
              })();
            }
          } catch (err) {}
        })();
    """

    private const val SHADOW_DOM_CLICK_RETARGET_JS = """
        (function() {
          if (window.__avaShadowClickRetarget) return;
          window.__avaShadowClickRetarget = true;
          document.addEventListener('click', function(event) {
            try {
              if (!event || !event.composedPath) return;
              var path = event.composedPath();
              var target = event.target;
              if (!target || !target.getRootNode) return;
              for (var i = 0; i < path.length; i++) {
                var el = path[i];
                if (!el || el === target || !el.tagName) continue;
                if (el.tagName.indexOf('-') === -1) continue;
                if (typeof el.icon !== 'string') continue;
                var root = target.getRootNode();
                if (root === el.shadowRoot || (el.shadowRoot && el.shadowRoot.contains(target))) {
                  try {
                    Object.defineProperty(event, 'target', { value: el, configurable: true });
                  } catch (err) {}
                  break;
                }
              }
            } catch (err) {}
          }, true);
        })();
    """

    private const val HUI_IMAGE_ANTI_FLICKER_JS = """
        (function() {
          if (window.__avaHuiImagePatch) return;
          window.__avaHuiImagePatch = true;
          function patch(cls) {
            try {
              var p = cls && cls.prototype;
              if (!p || p.__avaAntiFlicker) return;
              p.__avaAntiFlicker = true;
              var origError = p._onImageError;
              var origLoad = p._onImageLoad;
              if (typeof origError !== 'function' || typeof origLoad !== 'function') return;
              p._onImageLoad = function() {
                this.__avaHadFrame = true;
                return origLoad.apply(this, arguments);
              };
              p._onImageError = function() {
                // Once a good frame exists, hold it instead of flashing the empty
                // broken-image placeholder (slow refresh, WS hiccup, signed-URL miss).
                if (this.__avaHadFrame) {
                  this._loadState = 2;
                  return;
                }
                return origError.apply(this, arguments);
              };
            } catch (err) {}
          }
          try {
            if (!window.customElements || !customElements.whenDefined) return;
            var existing = customElements.get && customElements.get('hui-image');
            if (existing) {
              patch(existing);
            } else {
              customElements.whenDefined('hui-image').then(function() {
                patch(customElements.get('hui-image'));
              }).catch(function() {});
            }
          } catch (err) {}
        })();
    """

    private const val CALENDAR_CARD_ANTI_FLICKER_JS = """
        (function() {
          if (window.__avaCalCardPatch) return;
          window.__avaCalCardPatch = true;
          // Only the configured calendar entities' registry *options.calendar* (color)
          // feed the card — fingerprint just that slice.
          function regFp(list, entities) {
            var out = [];
            for (var i = 0; i < entities.length; i++) {
              var id = entities[i];
              if (id && typeof id === 'object') id = id.entity;
              var opt = 'x';
              for (var j = 0; j < list.length; j++) {
                if (list[j] && list[j].entity_id === id) {
                  var o = list[j].options && list[j].options.calendar;
                  opt = o ? JSON.stringify(o) : 'n';
                  break;
                }
              }
              out.push(opt);
            }
            return out.join('|');
          }
          function patch(cls) {
            try {
              var p = cls && cls.prototype;
              if (!p || p.__avaCalPatched) return;
              var origWill = p.willUpdate;
              var origHandle = p._handleCalendarUpdate;
              var origView = p._handleViewChanged;
              if (typeof origWill !== 'function' || typeof origHandle !== 'function') return;
              p.__avaCalPatched = true;
              p.willUpdate = function(changed) {
                // Registry pushes arrive for ANY entity anywhere; the stock card
                // blanks itself behind the loading overlay and resubscribes every
                // time. If the slice it actually reads is unchanged, hide the prop
                // change — template output stays identical, Lit diffs to zero DOM
                // mutations, and updated() keeps the live subscriptions.
                try {
                  if (changed && changed.has && changed.has('_entityRegistry')) {
                    var prev = changed.get('_entityRegistry');
                    var cfg = this._config;
                    if (prev && this._entityRegistry && cfg && cfg.entities &&
                        regFp(prev, cfg.entities) ===
                          regFp(this._entityRegistry, cfg.entities)) {
                      changed.delete('_entityRegistry');
                    }
                  }
                } catch (e) {}
                return origWill.apply(this, arguments);
              };
              p._handleCalendarUpdate = function(calendar, update) {
                // Backend pushes on every calendar entity update, identical payload
                // or not, and each one costs removeAllEventSources + re-add in
                // FullCalendar. Skip pushes whose payload matches the last one for
                // this calendar (initial loads pass: _eventsLoaded is still false).
                // Fingerprint is order-insensitive — the server does not guarantee
                // event ordering between pushes.
                try {
                  if (update && update.events && calendar && calendar.entity_id) {
                    var list = [];
                    for (var i = 0; i < update.events.length; i++) {
                      list.push(JSON.stringify(update.events[i]));
                    }
                    list.sort();
                    var fp = list.join('\u0001');
                    var store = this.__avaCalFp || (this.__avaCalFp = {});
                    if (this._eventsLoaded && store[calendar.entity_id] === fp) return;
                    store[calendar.entity_id] = fp;
                  }
                } catch (e) {}
                // Second gate: the stock handler always builds a NEW _events array
                // (filter + concat), so ha-full-calendar sees a changed `events`
                // property and redraws even when the content is identical. Restore
                // the previous array reference when nothing actually changed — the
                // child's property check then short-circuits and FullCalendar is
                // never touched.
                var before = this._events;
                var r = origHandle.apply(this, arguments);
                try {
                  var cur = this._events;
                  if (before && cur && cur !== before && cur.length === before.length &&
                      JSON.stringify(cur) === JSON.stringify(before)) {
                    this._events = before;
                  }
                } catch (e2) {}
                return r;
              };
              if (typeof origView === 'function') {
                p._handleViewChanged = function(ev) {
                  // ha-full-calendar re-fires view-changed with the SAME window on
                  // dialog-close callbacks and midnight ticks; the stock handler
                  // unconditionally blanks the card (_eventsLoaded=false → opaque
                  // overlay) and tears down every subscription. Same window = the
                  // live subscriptions already cover it; any real change arrives as
                  // a push. Only a genuinely different range needs the full path.
                  try {
                    var d = ev && ev.detail;
                    if (d && d.start && d.end && this._startDate && this._endDate &&
                        +this._startDate === +d.start && +this._endDate === +d.end) {
                      return;
                    }
                  } catch (e) {}
                  return origView.apply(this, arguments);
                };
              }
              try { console.warn('[Ava] hui-calendar-card anti-flicker patch attached'); } catch (e) {}
            } catch (e) {}
          }
          try {
            if (!window.customElements || !customElements.whenDefined) return;
            var existing = customElements.get && customElements.get('hui-calendar-card');
            if (existing) {
              patch(existing);
            } else {
              customElements.whenDefined('hui-calendar-card').then(function() {
                patch(customElements.get('hui-calendar-card'));
              }).catch(function() {});
            }
          } catch (e) {}
        })();
    """

    private const val SELECTOR_COMPAT_JS = """
        (function() {
          if (window.__avaSelectorCompat) return;
          window.__avaSelectorCompat = true;
          var needFV = false, needIs = false;
          try { document.documentElement.matches(':focus-visible'); } catch (e) { needFV = true; }
          try { document.querySelector(':is(a)'); } catch (e) { needIs = true; }
          if (!needFV && !needIs) return;
          var EMPTY_LIST;
          try { EMPTY_LIST = document.createDocumentFragment().querySelectorAll('a'); } catch (e) {}
          var cache = {};
          function degrade(sel) {
            if (typeof sel !== 'string') return sel;
            if (Object.prototype.hasOwnProperty.call(cache, sel)) return cache[sel];
            var out = sel;
            try {
              if (needFV) out = out.replace(/:focus-visible/g, ':focus');
              if (needIs) {
                var prev;
                do {
                  prev = out;
                  out = out.replace(/:(?:is|where)\(([^(),]+)\)/g, function(m, inner) { return inner; });
                } while (out !== prev);
              }
            } catch (e) { out = sel; }
            cache[sel] = out;
            return out;
          }
          function wrap(proto, name, failValue) {
            if (!proto) return;
            var orig = proto[name];
            if (typeof orig !== 'function') return;
            proto[name] = function(sel) {
              try {
                return orig.apply(this, arguments);
              } catch (e) {
                var alt = degrade(sel);
                if (alt !== sel) {
                  try { return orig.call(this, alt); } catch (e2) {}
                }
                // Unsupported selector = "matches nothing", the behaviour HA's
                // code paths get on the engines it actually tests against.
                return failValue;
              }
            };
          }
          var ep = window.Element && Element.prototype;
          wrap(ep, 'matches', false);
          wrap(ep, 'webkitMatchesSelector', false);
          wrap(ep, 'closest', null);
          var protos = [ep,
            window.Document && Document.prototype,
            window.DocumentFragment && DocumentFragment.prototype];
          for (var i = 0; i < protos.length; i++) {
            wrap(protos[i], 'querySelector', null);
            wrap(protos[i], 'querySelectorAll', EMPTY_LIST);
          }
          try {
            console.warn('[Ava] selector compat shim active (focus-visible=' + needFV + ', is()=' + needIs + ')');
          } catch (e) {}
        })();
    """

    private const val CSS_COMPAT_JS = """
        (function() {
          if (window.__avaCssCompat) return;
          window.__avaCssCompat = true;
          var needRadius = true, needBox = true, needIs = false, needFlexGap = false;
          var needHas = false, needLayer = false, needFV = false;
          var needXform = true, needAspect = true, needAppearance = true;
          var needMask = true, needOClip = true, needClamp = true, needVUnit = true;
          try {
            if (window.CSS && CSS.supports) {
              needVUnit = !CSS.supports('height', '100dvh');
              needClamp = !CSS.supports('width', 'clamp(1px, 2px, 3px)');
              needRadius = !CSS.supports('border-start-start-radius', '1px');
              needBox = !CSS.supports('inset', '0px');
              needXform = !CSS.supports('translate', '0px');
              needAspect = !CSS.supports('aspect-ratio', '1');
              needAppearance = !CSS.supports('appearance', 'none');
              needMask = !CSS.supports('mask-image', 'none');
              needOClip = !CSS.supports('overflow', 'clip');
            }
          } catch (e) {}
          // Probe the CSS PARSER, never document.querySelector/matches: the
          // selector-compat shim (installed just before this script) wraps
          // those to swallow unsupported-selector throws, which made these
          // probes report ":is() supported" on Chromium 83 and silently
          // disabled the whole :not(:is(...)) De Morgan rewrite — the exact
          // rules carrying wa-button corner radii and header logo margins.
          // A dropped rule in a real <style> element is shim-proof.
          function selectorParses(sel) {
            try {
              var st = document.createElement('style');
              st.textContent = sel + '{color:inherit}';
              (document.head || document.documentElement).appendChild(st);
              var n = 0;
              try {
                n = st.sheet && st.sheet.cssRules ? st.sheet.cssRules.length : 0;
              } catch (e) {}
              if (st.parentNode) st.parentNode.removeChild(st);
              return n === 1;
            } catch (e) { return false; }
          }
          needIs = !selectorParses(':is(a)');
          needHas = !selectorParses(':has(a)');
          needFV = !selectorParses(':focus-visible');
          // @layer needs 99; a parse probe is the only reliable check.
          try {
            var probeSheet = new CSSStyleSheet();
            probeSheet.insertRule('@layer ava {}', 0);
          } catch (e) { needLayer = true; }
          // `CSS.supports('gap', ...)` lies here: the property parses (grid has had it
          // since 66) but flex layout ignores it until 84. A layout probe
          // (measure scrollHeight of a flex column) gave DIFFERENT answers per
          // load: this script races the parser via two delivery channels
          // (<head> splice vs commit-time eval), and measuring before <body>
          // exists returns garbage. WeakRef shipped in the same Chromium 84 as
          // flex gap — a version proxy that needs no DOM, no layout, and reads
          // the same at any point of the document lifecycle. (The polyfill
          // pack deliberately never defines WeakRef.)
          try {
            needFlexGap = typeof WeakRef === 'undefined';
          } catch (e) {}
          if (!needRadius && !needBox && !needIs && !needFlexGap &&
              !needHas && !needLayer && !needFV && !needXform && !needAspect &&
              !needAppearance && !needMask && !needOClip && !needClamp &&
              !needVUnit) return;

          // Legacy engine confirmed. HA's service worker serves index.html
          // straight from CacheStorage, which bypasses shouldInterceptRequest
          // — the <head> splice (our only true document-start channel here)
          // never sees the document, and the SW-cached boot burst executes
          // before the commit-time eval can install the hooks above. A wall
          // tablet gains nothing from SW offline caching: unregister it, drop
          // its caches, and block re-registration so the splice is
          // deterministic from the next load onward.
          try {
            if (typeof navigator !== 'undefined' && navigator.serviceWorker) {
              try {
                navigator.serviceWorker.register = function() {
                  return Promise.reject(new Error('[Ava] SW disabled on legacy engine'));
                };
              } catch (e) {}
              if (navigator.serviceWorker.getRegistrations) {
                navigator.serviceWorker.getRegistrations().then(function(rs) {
                  for (var i = 0; i < rs.length; i++) {
                    try { rs[i].unregister(); } catch (e) {}
                  }
                }).catch(function() {});
              }
            }
            if (window.caches && caches.keys) {
              caches.keys().then(function(ks) {
                for (var i = 0; i < ks.length; i++) {
                  try { caches['delete'](ks[i]); } catch (e) {}
                }
              }).catch(function() {});
            }
          } catch (e) {}

          // LTR mappings; the app UI is LTR-only on these legacy devices.
          var RADIUS = [
            ['border-start-start-radius', 'border-top-left-radius'],
            ['border-start-end-radius', 'border-top-right-radius'],
            ['border-end-start-radius', 'border-bottom-left-radius'],
            ['border-end-end-radius', 'border-bottom-right-radius']
          ];
          var BOX = {
            'inset': ['top', 'right', 'bottom', 'left'],
            'inset-inline': ['left', 'right'],
            'inset-block': ['top', 'bottom'],
            'inset-inline-start': ['left'],
            'inset-inline-end': ['right'],
            'inset-block-start': ['top'],
            'inset-block-end': ['bottom'],
            'margin-inline': ['margin-left', 'margin-right'],
            'margin-block': ['margin-top', 'margin-bottom'],
            'padding-inline': ['padding-left', 'padding-right'],
            'padding-block': ['padding-top', 'padding-bottom']
          };
          // Logical border shorthands (87+) carry a whole `<width> <style> <color>`
          // value that applies to EACH physical side — duplicated, never split.
          var DUP = {
            'border-inline': ['border-left', 'border-right'],
            'border-block': ['border-top', 'border-bottom'],
            'border-inline-start': ['border-left'],
            'border-inline-end': ['border-right'],
            'border-block-start': ['border-top'],
            'border-block-end': ['border-bottom']
          };
          var BOX_RE = /([;{\s])(inset(?:-(?:inline|block)(?:-(?:start|end))?)?|(?:margin|padding)-(?:inline|block)|border-(?:inline|block)(?:-(?:start|end))?)\s*:\s*([^;}!]+?)\s*(!important)?\s*(?=[;}])/g;

          function expandBox(css) {
            return css.replace(BOX_RE, function(m, pre, prop, val, imp) {
              var suffix = imp ? ' !important' : '';
              var out = [], i;
              var dup = DUP[prop];
              if (dup) {
                for (i = 0; i < dup.length; i++) out.push(dup[i] + ':' + val + suffix);
                return pre + out.join(';');
              }
              var sides = BOX[prop];
              if (!sides) return m;
              // Single-side longhands map 1:1 — safe even for calc()/var() values.
              if (sides.length === 1) return pre + sides[0] + ':' + val + suffix;
              // Top-level space split: calc()/var() keep their internal spaces, so
              // `padding-inline:var(--ha-icon-button-padding)` (88x in the es5
              // bundle — THE reason icon buttons collapsed to bare-icon size) and
              // two-var values like `margin-block:var(--a) var(--b)` both expand.
              // A single var() holding a multi-value list would produce invalid
              // longhands — same net effect as the dropped declaration, no worse.
              var parts = splitSpace(val);
              if (!parts.length || parts.length > sides.length) return m;
              var vals;
              if (sides.length === 4) {
                var a = parts[0], b = parts[1] || a, c = parts[2] || a, d = parts[3] || parts[1] || a;
                vals = [a, b, c, d];
              } else {
                vals = [parts[0], parts[1] || parts[0]];
              }
              for (i = 0; i < sides.length; i++) out.push(sides[i] + ':' + vals[i] + suffix);
              return pre + out.join(';');
            });
          }

          // @layer blocks (99+) are dropped WHOLE by older parsers — HA 2026 ships
          // Web Awesome (wa-*) components whose entire base styles, rounded corners
          // included, live inside `@layer wa-component { … }`. Unwrapping the layer
          // keeps the rules in source order, which is exactly the pre-layer cascade.
          function unwrapLayers(css) {
            css = css.replace(/@layer[^{};]*;/g, '');
            for (var guard = 0; guard < 200; guard++) {
              var idx = css.indexOf('@layer');
              if (idx === -1) break;
              var open = css.indexOf('{', idx);
              if (open === -1) break;
              var depth = 1, i = open + 1;
              while (i < css.length && depth > 0) {
                var c = css.charAt(i);
                if (c === '{') depth++;
                else if (c === '}') depth--;
                i++;
              }
              if (depth !== 0) break;
              css = css.slice(0, idx) + css.slice(open + 1, i - 1) + css.slice(i);
            }
            return css;
          }

          // ---- Selector repair ----------------------------------------------
          // One unparseable member (`:has()`, multi-arg `:is()`, level-4 `:not()`)
          // invalidates the ENTIRE selector list on old parsers, taking every
          // declaration with it — the actual cause of square buttons/titles: the
          // es5 bundle carries 1000+ rules with multi-arg `:is()`. Strategy:
          // split the list on top-level commas, expand `:is()/:where()` into the
          // exact cross-product, then keep each member iff the engine can parse
          // it (probe: querySelector on a detached fragment throws only on parse
          // errors). Unfixable members are dropped; the rule survives for the rest.
          var probeFrag = document.createDocumentFragment();
          function selectorValid(s) {
            try { probeFrag.querySelector(s); return true; } catch (e) { return false; }
          }
          function splitTop(s) {
            var parts = [], depth = 0, cur = '';
            for (var i = 0; i < s.length; i++) {
              var c = s.charAt(i);
              if (c === '(' || c === '[') depth++;
              else if (c === ')' || c === ']') depth--;
              if (c === ',' && depth === 0) { parts.push(cur); cur = ''; }
              else cur += c;
            }
            parts.push(cur);
            return parts;
          }
          // Level-4 `:not(<list>)` (88+) — including the `:not(:is(a,b))` /
          // `:not(:-webkit-any(a,b))` forms Web Awesome ships for its :lang()-based
          // RTL emulation (the wa-button corner-radius rules — why "add device"/
          // "add integration"-class buttons rendered square) — rewritten to the
          // exact-equivalent level-3 chain `:not(a):not(b)` (De Morgan), which
          // legacy engines parse. Expanding the inner list as a disjunction would
          // be semantically WRONG (`:not(a or b)` != `:not(a) or :not(b)`), so
          // this runs before expandIsWhere and expandIsWhere skips :not() bodies.
          function stripListWrapper(inner) {
            var w = inner.match(/^:(?:is|where|-webkit-any|-moz-any|any)\(/);
            if (!w || inner.charAt(inner.length - 1) !== ')') return inner;
            var body = inner.slice(w[0].length, -1);
            var depth = 0;
            for (var i = 0; i < body.length; i++) {
              var c = body.charAt(i);
              if (c === '(') depth++;
              else if (c === ')') { depth--; if (depth < 0) return inner; }
            }
            return depth === 0 ? body : inner;
          }
          function deMorganNot(sel) {
            var pos = 0;
            for (var guard = 0; guard < 64; guard++) {
              var m = sel.indexOf(':not(', pos);
              if (m === -1) break;
              var depth = 1, j = m + 5;
              while (j < sel.length && depth > 0) {
                var c = sel.charAt(j);
                if (c === '(') depth++;
                else if (c === ')') depth--;
                j++;
              }
              if (depth !== 0) return sel;
              var raw = sel.slice(m + 5, j - 1).trim();
              var inner = stripListWrapper(raw);
              var items = splitTop(inner);
              if (items.length > 1) {
                var chain = '';
                for (var k = 0; k < items.length; k++) {
                  var it = items[k].trim();
                  if (it) chain += ':not(' + it + ')';
                }
                sel = sel.slice(0, m) + chain + sel.slice(j);
                pos = m + chain.length;
              } else if (inner !== raw) {
                sel = sel.slice(0, m) + ':not(' + inner + ')' + sel.slice(j);
                pos = m + 6 + inner.length;
              } else {
                pos = j;
              }
            }
            return sel;
          }
          function insideNot(s, idx) {
            var stack = [], i;
            for (i = 0; i < idx; i++) {
              var c = s.charAt(i);
              if (c === '(') stack.push(s.slice(Math.max(0, i - 4), i) === ':not');
              else if (c === ')') stack.pop();
            }
            for (i = 0; i < stack.length; i++) if (stack[i]) return true;
            return false;
          }
          function findExpandable(cur) {
            var re = /:(?:is|where)\(/g, m;
            while ((m = re.exec(cur)) !== null) {
              if (!insideNot(cur, m.index)) return m.index;
            }
            return -1;
          }
          function expandIsWhere(sel) {
            var res = [sel];
            for (var i = 0, guard = 0; i < res.length && guard < 200; i++, guard++) {
              var cur = res[i];
              var m = findExpandable(cur);
              if (m === -1) continue;
              var open = cur.indexOf('(', m);
              var depth = 1, j = open + 1;
              while (j < cur.length && depth > 0) {
                var c = cur.charAt(j);
                if (c === '(') depth++;
                else if (c === ')') depth--;
                j++;
              }
              if (depth !== 0) return null;
              var prefix = cur.slice(0, m), suffix = cur.slice(j);
              var parts = splitTop(cur.slice(open + 1, j - 1));
              var expanded = [];
              for (var k = 0; k < parts.length; k++) {
                var p = parts[k].trim();
                if (p) expanded.push(prefix + p + suffix);
              }
              if (!expanded.length || res.length - 1 + expanded.length > 64) return null;
              Array.prototype.splice.apply(res, [i, 1].concat(expanded));
              i--;
            }
            return res;
          }
          var selCache = {};
          function fixSelector(sel) {
            if (Object.prototype.hasOwnProperty.call(selCache, sel)) return selCache[sel];
            var members = splitTop(sel);
            var keep = [];
            for (var i = 0; i < members.length; i++) {
              var mem = members[i].trim();
              if (!mem) continue;
              if (selectorValid(mem)) { keep.push(mem); continue; }
              if (mem.indexOf(':not(') !== -1) {
                var dm = deMorganNot(mem);
                if (dm !== mem) {
                  if (selectorValid(dm)) { keep.push(dm); continue; }
                  mem = dm; // expansion may still rescue the conjunctive form
                }
              }
              var exp = expandIsWhere(mem);
              if (!exp) continue;
              for (var j = 0; j < exp.length; j++) {
                if (selectorValid(exp[j])) keep.push(exp[j]);
              }
            }
            // All members unparseable: keep the rule syntactically alive with a
            // never-matching (but valid) selector so sibling rules are unaffected.
            var out = keep.length ? keep.join(',') : '[ava-dropped-rule]';
            selCache[sel] = out;
            return out;
          }
          function rewriteSelectors(css) {
            var out = '', last = 0;
            for (var i = 0; i < css.length; i++) {
              var c = css.charAt(i);
              if (c === '{') {
                var header = css.slice(last, i);
                var t = header.trim();
                // :dir() needs 120, ::part() 73 — on engines that lack them, one
                // such member kills the whole selector list at parse time; the
                // probe pass drops just that member (no-op where they're native).
                if (t && t.charAt(0) !== '@' &&
                    (t.indexOf(':is(') !== -1 || t.indexOf(':where(') !== -1 ||
                     t.indexOf(':has(') !== -1 || t.indexOf(':not(') !== -1 ||
                     t.indexOf(':dir(') !== -1 || t.indexOf('::part(') !== -1)) {
                  header = fixSelector(t);
                }
                out += header + '{';
                last = i + 1;
              } else if (c === '}') {
                out += css.slice(last, i) + '}';
                last = i + 1;
              }
            }
            return out + css.slice(last);
          }

          // ---- Property-level repairs -----------------------------------------
          function readLastDecl(body, prop) {
            var re = new RegExp('(?:^|[;\\s{])' + prop + '\\s*:\\s*([^;}]+)', 'g');
            var m, val = null;
            while ((m = re.exec(body)) !== null) val = m[1].trim();
            return val;
          }
          function splitSpace(s) {
            var parts = [], depth = 0, cur = '';
            for (var i = 0; i < s.length; i++) {
              var c = s.charAt(i);
              if (c === '(') depth++;
              else if (c === ')') depth--;
              if ((c === ' ' || c === '\t' || c === '\n' || c === '\r') && depth === 0) {
                if (cur) { parts.push(cur); cur = ''; }
              } else cur += c;
            }
            if (cur) parts.push(cur);
            return parts;
          }

          // Individual transform properties (104+): wa-switch positions its thumb
          // with `translate: calc((var(--width) - var(--height)) / -2)` — dropped
          // on old engines, the knob just sits centered in both states. Synthesize
          // a `transform:` from translate/rotate/scale in spec order (they apply
          // before any author transform), appended last so it wins in the block.
          function xformFn(kind, val) {
            if (!val || val === 'none') return null;
            var p = splitSpace(val);
            if (kind === 'translate') {
              if (p.length === 1) return 'translate(' + p[0] + ')';
              if (p.length === 2) return 'translate(' + p[0] + ',' + p[1] + ')';
              if (p.length === 3) return 'translate3d(' + p.join(',') + ')';
            } else if (kind === 'rotate') {
              if (p.length === 1) return 'rotate(' + p[0] + ')';
              if (p.length === 2 && 'xyz'.indexOf(p[0]) !== -1) {
                return 'rotate' + p[0].toUpperCase() + '(' + p[1] + ')';
              }
              if (p.length === 4) return 'rotate3d(' + p.join(',') + ')';
            } else {
              if (p.length === 1) return 'scale(' + p[0] + ')';
              if (p.length === 2) return 'scale(' + p[0] + ',' + p[1] + ')';
              if (p.length === 3) return 'scale3d(' + p.join(',') + ')';
            }
            return null;
          }
          function emulateTransformProps(css) {
            if (!/[;{\s](?:translate|rotate|scale)\s*:/.test(css)) return css;
            return css.replace(/([^{}]+)\{([^{}]*)\}/g, function(m, sel, body) {
              if (!/(?:^|[;\s])(?:translate|rotate|scale)\s*:/.test(body)) return m;
              // Marker keeps fix() idempotent: composing the synthesized
              // transform onto itself would double every offset on re-runs
              // (style sweeps re-feed already-fixed text).
              if (body.indexOf('--ava-xf') !== -1) return m;
              var parts = [];
              var t = xformFn('translate', readLastDecl(body, 'translate'));
              var r = xformFn('rotate', readLastDecl(body, 'rotate'));
              var s = xformFn('scale', readLastDecl(body, 'scale'));
              if (t) parts.push(t);
              if (r) parts.push(r);
              if (s) parts.push(s);
              if (!parts.length) return m;
              var old = readLastDecl(body, 'transform');
              if (old && old !== 'none') parts.push(old);
              return sel + '{' + body + ';--ava-xf:1;transform:' + parts.join(' ') + '}';
            });
          }

          // aspect-ratio (88+): recover the common square case (round thumbs,
          // avatars, icon boxes) when exactly one axis is specified. Percentages
          // resolve against different axes, so those are left alone.
          function fixAspectSquare(css) {
            if (css.indexOf('aspect-ratio') === -1) return css;
            return css.replace(/([^{}]+)\{([^{}]*)\}/g, function(m, sel, body) {
              var ar = readLastDecl(body, 'aspect-ratio');
              if (!ar) return m;
              ar = ar.replace(/\s+/g, '');
              if (ar !== '1' && ar !== '1/1' && ar !== '1/1;') return m;
              var w = readLastDecl(body, 'width');
              var h = readLastDecl(body, 'height');
              var add = null;
              if (w && !h) add = 'height:' + w;
              else if (h && !w) add = 'width:' + h;
              if (!add || add.indexOf('%') !== -1 || add.indexOf('auto') !== -1) return m;
              return sel + '{' + body + ';' + add + '}';
            });
          }

          // Unprefixed appearance needs 84 — without -webkit-appearance:none the
          // native checkbox/radio/switch chrome bleeds through custom controls.
          // Skips declarations already preceded by their own -webkit- copy so
          // re-running fix() over fixed text cannot grow it (loop guard for
          // the style-sweep observer).
          function dupAppearance(css) {
            return css.replace(/([;{\s])appearance\s*:\s*([^;}]+)/g, function(m, pre, val, off) {
              val = val.trim();
              var dup = '-webkit-appearance:' + val;
              if (css.slice(Math.max(0, off - dup.length), off) === dup) return m;
              return pre + dup + ';appearance:' + val;
            });
          }

          // Unprefixed mask longhands need 120; icon masking silently vanishes.
          // Same re-run guard: skip when our -webkit- duplicate already follows.
          function dupMask(css) {
            return css.replace(
              /([;{\s])(mask(?:-image|-size|-position|-repeat)?)\s*:\s*([^;}]+)/g,
              function(m, pre, prop, val, off) {
                val = val.trim();
                var dup = ';-webkit-' + prop + ':' + val;
                var end = off + m.length;
                if (css.slice(end, end + dup.length) === dup) return m;
                return pre + prop + ':' + val + dup;
              }
            );
          }

          // Flex-gap emulation: a declaration block that pairs a flex marker with a
          // gap is rewritten to inheritable custom properties, and a sibling-margin
          // rule is appended right after the block (so it stays inside any @media
          // scope). Custom properties make `gap:inherit` (md-list-item) resolve to
          // the outer rule's value for free. Grid blocks are skipped — grid gap has
          // worked since Chromium 66.
          function readGapValue(body, prop) {
            var re = new RegExp('(^|[;\\s])' + prop + '\\s*:\\s*([^;]+)');
            var m = re.exec(body);
            if (!m) return null;
            return m[2].replace(/!important/g, '').replace(/\s+/g, ' ').trim();
          }

          function emulateFlexGap(css) {
            if (css.indexOf('gap:') === -1) return css;
            return css.replace(/([^{}]+)\{([^{}]*)\}/g, function(m, sel, body) {
              if (body.indexOf('gap:') === -1) return m;
              if (body.indexOf('--ava-gap-') !== -1) return m; // already emulated
              if (/display\s*:\s*(inline-)?grid/.test(body)) return m;
              if (!/display\s*:\s*(inline-)?flex/.test(body) &&
                  !/flex-direction\s*:/.test(body) &&
                  !/flex-wrap\s*:/.test(body)) return m;
              var rowVal = null, colVal = null, inheritish = false;
              var gap = readGapValue(body, 'gap');
              if (gap !== null) {
                if (/^(inherit|initial|unset|normal)/.test(gap)) {
                  inheritish = true;
                } else if (gap.indexOf('(') !== -1) {
                  // var()/calc() may hold spaces — treat as one value for both axes.
                  rowVal = gap; colVal = gap;
                } else {
                  var parts = gap.split(' ');
                  rowVal = parts[0]; colVal = parts[1] || parts[0];
                }
              }
              var rg = readGapValue(body, 'row-gap');
              if (rg !== null) rowVal = rg;
              var cg = readGapValue(body, 'column-gap');
              if (cg !== null) colVal = cg;
              if (!inheritish && rowVal === null && colVal === null) return m;
              var add = '';
              if (rowVal !== null) add += '--ava-gap-row:' + rowVal + ';';
              if (colVal !== null) add += '--ava-gap-col:' + colVal + ';';
              var column = /flex-direction\s*:\s*column/.test(body);
              var wrap = /flex-wrap\s*:\s*wrap/.test(body);
              var extra = '';
              var list = sel.split(',');
              for (var i = 0; i < list.length; i++) {
                var p = list[i].replace(/\s+/g, ' ').trim();
                if (!p) continue;
                if (wrap) {
                  // Wrapped rows (chip bars): half-gap on every side of every item
                  // approximates both axes without touching the container's margin.
                  extra += p + '>*{margin:calc(var(--ava-gap-row,0px)/2) calc(var(--ava-gap-col,0px)/2)}';
                } else if (column) {
                  extra += p + '>:not(:first-child){margin-top:var(--ava-gap-row,0px)}';
                } else {
                  extra += p + '>:not(:first-child){margin-left:var(--ava-gap-col,0px)}';
                }
              }
              return sel + '{' + body + ';' + add + '}' + extra;
            });
          }

          // clamp() needs 79 (813 uses in the es5 bundle); an unsupporting engine
          // drops the whole declaration and the element falls back to inherited/
          // initial sizing. The middle argument is the preferred value — exactly
          // what a supporting engine computes while the bounds don't bite — so it
          // is the best static stand-in. Paren-aware scan (calc()/var() inside
          // arguments), non-3-arg or word-prefixed matches copied verbatim.
          function clampOnePass(css) {
            var idx = css.indexOf('clamp(');
            if (idx === -1) return css;
            var out = '', pos = 0;
            while (idx !== -1) {
              var before = idx > 0 ? css.charAt(idx - 1) : '';
              var depth = 1, i = idx + 6, args = [], last = i;
              for (; i < css.length && depth > 0; i++) {
                var ch = css.charAt(i);
                if (ch === '(') depth++;
                else if (ch === ')') { depth--; if (depth === 0) break; }
                else if (ch === ',' && depth === 1) { args.push(css.slice(last, i)); last = i + 1; }
              }
              if (depth !== 0) break; // unbalanced input — leave the rest as-is
              args.push(css.slice(last, i));
              out += css.slice(pos, idx);
              if (/[A-Za-z0-9_-]/.test(before) || args.length !== 3) {
                out += css.slice(idx, i + 1);
              } else {
                out += args[1].trim();
              }
              pos = i + 1;
              idx = css.indexOf('clamp(', pos);
            }
            return out + css.slice(pos);
          }
          function fixClamp(css) {
            // Re-run for clamp() nested inside a kept argument; bounded, since
            // each pass strictly removes at least one clamp( occurrence.
            for (var guard = 0; guard < 4; guard++) {
              var next = clampOnePass(css);
              if (next === css) return css;
              css = next;
            }
            return css;
          }

          // Dynamic viewport units need 108 (this bundle: svh 827x, dvh 140x).
          // The killer shape is `max-height:var(--dialog-max-height,100dvh)`:
          // var() defers validation past parse time, so the declaration WINS the
          // cascade over any plain-vh fallback the author wrote, then computes to
          // garbage at used-value time — max-height becomes none and the dialog
          // overflows the screen. Inside an embedded WebView there is no browser
          // chrome, so small/dynamic/large viewports are all identical: mapping
          // sv*/dv*/lv* to the classic v* unit is a zero-loss rewrite. Digit/dot/
          // paren must precede so custom-property NAMES (--my-svh) stay untouched.
          var V_UNIT_RE = /([\d.)])(?:d|s|l)v(h|w|min|max|i|b)\b/gi;
          function fixViewportUnits(css) {
            return css.replace(V_UNIT_RE, function(m, pre, axis) {
              var a = axis.toLowerCase();
              // vi/vb are 108+ themselves — map to their LTR physical axis.
              if (a === 'i') a = 'w';
              else if (a === 'b') a = 'h';
              return pre + 'v' + a;
            });
          }

          function fix(text) {
            if (typeof text !== 'string' || !text) return text;
            try {
              var out = text;
              if (needLayer && out.indexOf('@layer') !== -1) out = unwrapLayers(out);
              if (needFV && out.indexOf(':focus-visible') !== -1) {
                out = out.split(':focus-visible').join(':focus');
              }
              if (needRadius && out.indexOf('-radius') !== -1) {
                for (var i = 0; i < RADIUS.length; i++) {
                  if (out.indexOf(RADIUS[i][0]) !== -1) out = out.split(RADIUS[i][0]).join(RADIUS[i][1]);
                }
              }
              if (needBox) out = expandBox(out);
              if ((needIs || needHas) &&
                  (out.indexOf(':is(') !== -1 || out.indexOf(':where(') !== -1 ||
                   out.indexOf(':has(') !== -1 || out.indexOf(':not(') !== -1)) {
                out = rewriteSelectors(out);
              }
              if (needXform) out = emulateTransformProps(out);
              if (needAspect) out = fixAspectSquare(out);
              if (needAppearance && out.indexOf('appearance') !== -1) out = dupAppearance(out);
              if (needMask && out.indexOf('mask') !== -1) out = dupMask(out);
              if (needOClip && out.indexOf('clip') !== -1) {
                out = out.replace(/(overflow(?:-[xy])?\s*:\s*)clip/g, function(m, pre) {
                  return pre + 'hidden';
                });
              }
              if (needClamp && out.indexOf('clamp(') !== -1) out = fixClamp(out);
              if (needVUnit && /[dsl]v[hwmib]/i.test(out)) out = fixViewportUnits(out);
              if (needFlexGap) out = emulateFlexGap(out);
              // Version-proof pins: HA keeps moving these declarations between
              // logical shorthands (padding-inline:16px 8px) and :lang() RTL
              // guard rules from release to release. When a sheet matches a
              // known component signature, append explicit LTR physical copies
              // at the end — last position wins the cascade at equal
              // specificity, independent of what the rewrites above salvaged.
              if (out.indexOf('/*ava-pin*/') === -1) {
                var pins = '';
                if (out.indexOf('.header img{width:40px;height:40px') !== -1) {
                  pins += '.header{padding-left:16px;padding-right:8px}' +
                          '.header img{margin-right:16px}';
                }
                if (out.indexOf('--_button-start-start-radius') !== -1 &&
                    out.indexOf('.button') !== -1) {
                  pins += '.button{' +
                    'border-top-left-radius:var(--_button-start-start-radius,var(--wa-form-control-border-radius));' +
                    'border-top-right-radius:var(--_button-start-end-radius,var(--wa-form-control-border-radius));' +
                    'border-bottom-right-radius:var(--_button-end-end-radius,var(--wa-form-control-border-radius));' +
                    'border-bottom-left-radius:var(--_button-end-start-radius,var(--wa-form-control-border-radius))}';
                }
                if (pins) out += '/*ava-pin*/' + pins;
              }
              return out;
            } catch (e) { return text; }
          }

          try {
            var sp = window.CSSStyleSheet && CSSStyleSheet.prototype;
            if (sp && typeof sp.replaceSync === 'function') {
              var origSync = sp.replaceSync;
              sp.replaceSync = function(text) { return origSync.call(this, fix(text)); };
            }
            if (sp && typeof sp.replace === 'function') {
              var origAsync = sp.replace;
              sp.replace = function(text) { return origAsync.call(this, fix(text)); };
            }
            if (sp && typeof sp.insertRule === 'function') {
              var origInsert = sp.insertRule;
              sp.insertRule = function(rule, index) { return origInsert.call(this, fix(rule), index || 0); };
            }
          } catch (e) {}

          try {
            var desc = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
            if (desc && desc.set && window.HTMLStyleElement) {
              Object.defineProperty(HTMLStyleElement.prototype, 'textContent', {
                configurable: true,
                enumerable: desc.enumerable,
                get: desc.get,
                set: function(v) { desc.set.call(this, fix(v)); }
              });
            }
          } catch (e) {}

          try {
            var dp = window.CSSStyleDeclaration && CSSStyleDeclaration.prototype;
            if (needRadius && dp && typeof dp.setProperty === 'function') {
              var radiusMap = {};
              for (var r = 0; r < RADIUS.length; r++) radiusMap[RADIUS[r][0]] = RADIUS[r][1];
              var origSet = dp.setProperty;
              dp.setProperty = function(prop, value, priority) {
                return origSet.call(this, radiusMap[prop] || prop, value, priority);
              };
            }
          } catch (e) {}

          // Light-DOM <style> tags never pass the textContent setter: the HTML
          // parser fills them with text nodes directly, and innerHTML-built
          // fragments do the same. Sweep now (covers late installs where styles
          // already exist), again at DOMContentLoaded (document-start installs
          // run before the parser reaches them), and watch for dynamic ones.
          // fix() is idempotent, so re-sweeping already-fixed tags is a no-op.
          function sweepStyles() {
            try {
              var styles = document.querySelectorAll('style');
              for (var s = 0; s < styles.length; s++) {
                var t = styles[s].textContent;
                var f = fix(t);
                if (f !== t) styles[s].textContent = f;
              }
            } catch (e) {}
          }
          sweepStyles();
          try {
            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', sweepStyles);
            }
            if (window.MutationObserver) {
              new MutationObserver(function(muts) {
                for (var i = 0; i < muts.length; i++) {
                  var m = muts[i];
                  if (m.target && m.target.nodeName === 'STYLE') { sweepStyles(); return; }
                  var added = m.addedNodes || [];
                  for (var j = 0; j < added.length; j++) {
                    var n = added[j];
                    if (n.nodeName === 'STYLE' ||
                        (n.querySelector && n.querySelector('style'))) {
                      sweepStyles();
                      return;
                    }
                  }
                }
              }).observe(document.documentElement, { childList: true, subtree: true });
            }
          } catch (e) {}

          try {
            console.warn('[Ava] CSS compat rewriter active (radius=' + needRadius +
              ', boxShorthand=' + needBox + ', is()=' + needIs + ', has()=' + needHas +
              ', layer=' + needLayer + ', focusVisible=' + needFV + ', flexGap=' + needFlexGap +
              ', xform=' + needXform + ', aspect=' + needAspect + ', appearance=' + needAppearance +
              ', mask=' + needMask + ', overflowClip=' + needOClip + ', clamp=' + needClamp +
              ', vUnits=' + needVUnit + ')');
          } catch (e) {}
        })();
    """

    private const val JSON_PARSE_TRACER_JS = """
        (function() {
          if (window.__avaJsonTrace) return;
          window.__avaJsonTrace = true;
          // Legacy engines only — modern ones keep a pristine JSON.parse.
          try { document.querySelector(':is(a)'); return; } catch (e) {}
          var orig = JSON.parse;
          var logged = 0, windowStart = 0;
          JSON.parse = function(text) {
            try {
              return orig.apply(JSON, arguments);
            } catch (err) {
              try {
                var now = Date.now();
                if (now - windowStart > 60000) { windowStart = now; logged = 0; }
                if (logged < 3) {
                  logged++;
                  var head = (text === undefined) ? '<undefined>'
                    : (text === null) ? '<null>'
                    : String(text).slice(0, 60);
                  console.warn('[Ava] JSON.parse failed on: ' + head + '\n' + (new Error().stack || 'no stack'));
                }
              } catch (e2) {}
              throw err;
            }
          };
        })();
    """

    /**
     * Nav-bar (HA header) guard — direct shadow-DOM surgery, technique lifted from
     * the HACrack userscript's `syncDockTopAppBars`, which is the proven way to
     * restyle HA chrome across versions: pierce
     * `home-assistant → home-assistant-main`, deep-walk EVERY shadow root under it
     * collecting fixed/sticky `.header` / `.top-app-bar` / `app-header` bars, tag
     * each with a marker class, and drop an id-keyed `<style>` into the shadow
     * root that OWNS the element (idempotent — reused by id; `!important` so it
     * outranks the component's own sheet). Re-sync on `location-changed` /
     * `popstate` plus a slow interval, because HA rebuilds headers on navigation
     * and the marker/style vanish with them. Only the bars are touched — never
     * the view/card containers, so card margins stay the user's own.
     *
     * Two repairs ride on the marker class:
     *  - Horizontal: nudge HA header icons away from Ava's edge handle only
     *    on the sidebar's side (left handle → leftmost icons; right handle →
     *    rightmost icons). Never both. The bar stays full-bleed so landscape
     *    `--app-safe-area-inset-right` cannot shrink the header paint; the
     *    handle-side control uses max(12px, that real inset).
     *  - Top: the overlay window starts at y=0 (LAYOUT_NO_LIMITS) and, as a
     *    non-focused overlay, can't hide the status bar a launcher behind keeps
     *    visible — the bar draws over HA's header while `env(safe-area-inset-top)`
     *    reads 0 inside the page. Native watches the real insets and calls
     *    `window.__avaSetTopInset(px)`. The value feeds HA's official
     *    `--app-safe-area-inset-top` slot (+ `--safe-area-inset-top` !important
     *    for older frontends) — frontends that consume it shift header AND
     *    content together. The walker then measures each bar: if its computed
     *    top padding/margin ignored the variable (frontend too old), a fallback
     *    marker pushes the bar down via `margin-top: var(--ava-top-inset)`.
     */
    private val STATUS_BAR_INSET_BRIDGE_JS = """
        (function() {
          if (window.__avaSetTopInset) return;
          var EDGE = 12;
          var STYLE_ID = 'ava-nav-inset-style';
          var MARK = 'ava-nav-inset';
          var TOP_MARK = 'ava-nav-top-fallback';
          var START = 'ava-nav-edge-start';
          var END = 'ava-nav-edge-end';
          var sidebarEdge = 'left';
          var topInset = 0;
          var timer = null;

          function startRule() {
            if (sidebarEdge === 'right' || sidebarEdge === 'none') {
              return '.' + START + '{' +
                'margin-left:var(--app-safe-area-inset-left,0px) !important;' +
              '}';
            }
            return '.' + START + '{' +
              'margin-left:' + EDGE + 'px !important;' +
              'margin-left:max(env(safe-area-inset-left,0px),' + EDGE + 'px) !important;' +
            '}';
          }

          function endRule() {
            if (sidebarEdge === 'right') {
              return '.' + END + '{' +
                'margin-right:' + EDGE + 'px !important;' +
                'margin-right:max(var(--app-safe-area-inset-right,0px),' + EDGE + 'px) !important;' +
              '}';
            }
            return '.' + END + '{' +
              'margin-right:var(--app-safe-area-inset-right,0px) !important;' +
            '}';
          }

          function styleCss() {
            return '.' + MARK + '{' +
                'box-sizing:border-box !important;' +
                'left:0 !important;' +
                'right:0 !important;' +
                'width:100% !important;' +
                'max-width:none !important;' +
                'padding-left:0 !important;' +
                'padding-right:0 !important;' +
                'margin-left:0 !important;' +
                'margin-right:0 !important;' +
              '}' +
              '.' + MARK + ' .toolbar,.' + MARK +
                ' .mdc-top-app-bar__row,.' + MARK + ' .top-app-bar__row{' +
                'padding-left:0 !important;' +
                'padding-right:0 !important;' +
                'margin-left:0 !important;' +
                'margin-right:0 !important;' +
              '}' +
              startRule() +
              endRule() +
              '.' + TOP_MARK + '{margin-top:var(--ava-top-inset,0px) !important;}';
          }

          function innerCss() {
            return '.toolbar,.mdc-top-app-bar__row,.top-app-bar__row{' +
                'padding-left:0 !important;' +
                'padding-right:0 !important;' +
                'margin-left:0 !important;' +
                'margin-right:0 !important;' +
              '}' +
              startRule() +
              endRule();
          }

          function ensureStyle(rootNode, css) {
            if (!rootNode || !rootNode.getElementById) return;
            var text = css || styleCss();
            var s = rootNode.getElementById(STYLE_ID);
            if (!s) {
              s = document.createElement('style');
              s.id = STYLE_ID;
              rootNode.appendChild(s);
            }
            if (s.textContent !== text) s.textContent = text;
          }

          function isIconHost(el) {
            if (!el || el.nodeType !== 1) return false;
            var tag = (el.tagName || '').toLowerCase();
            if (tag === 'ha-menu-button' || tag === 'ha-icon-button' ||
                tag === 'ha-button-menu' || tag === 'ha-assist-chip') return true;
            var cl = el.classList;
            return !!(cl && (cl.contains('action-items') || cl.contains('mdc-icon-button')));
          }

          function clearEdges(root) {
            if (!root || !root.querySelectorAll) return;
            var old = root.querySelectorAll('.' + START + ',.' + END);
            for (var i = 0; i < old.length; i++) {
              old[i].classList.remove(START);
              old[i].classList.remove(END);
            }
          }

          function firstLastIcons(root) {
            if (!root) return { first: null, last: null };
            var startSec = root.querySelector &&
              root.querySelector('.mdc-top-app-bar__section--align-start');
            var endSec = root.querySelector &&
              root.querySelector('.mdc-top-app-bar__section--align-end');
            if (startSec || endSec) return { first: startSec, last: endSec };
            var scan = root;
            var tb = root.querySelector && root.querySelector('.toolbar');
            if (tb) scan = tb;
            var first = null, last = null;
            var kids = scan.children;
            if (kids) {
              for (var i = 0; i < kids.length; i++) {
                if (!isIconHost(kids[i])) continue;
                if (!first) first = kids[i];
                last = kids[i];
              }
            }
            return { first: first, last: last };
          }

          function markEdges(bar) {
            clearEdges(bar);
            if (bar.shadowRoot) clearEdges(bar.shadowRoot);
            var pair = firstLastIcons(bar);
            if (!pair.first && !pair.last && bar.shadowRoot) {
              pair = firstLastIcons(bar.shadowRoot);
            }
            if (pair.first) pair.first.classList.add(START);
            if (pair.last) pair.last.classList.add(END);
            ensureStyle(bar.getRootNode(), styleCss());
            if (bar.shadowRoot) ensureStyle(bar.shadowRoot, innerCss());
            var owner = bar.getRootNode();
            var extra = function(node) {
              if (!node || !node.getRootNode) return;
              var rn = node.getRootNode();
              if (rn === owner || rn === bar.shadowRoot) return;
              ensureStyle(rn, innerCss());
            };
            extra(pair.first);
            extra(pair.last);
          }

          function apply() {
            var found = 0;
            var seen = 0;
            try {
              var home = document.querySelector('home-assistant');
              var main = home && home.shadowRoot &&
                home.shadowRoot.querySelector('home-assistant-main');
              var msr = main && main.shadowRoot;
              if (!msr) return 0;
              var visit = function(root, depth) {
                if (!root || depth > 12 || seen > 5000) return;
                var bars = root.querySelectorAll(
                  '.header, .top-app-bar, app-header, .mdc-top-app-bar');
                for (var i = 0; i < bars.length; i++) {
                  var el = bars[i];
                  var cs;
                  try { cs = getComputedStyle(el); } catch (e) { continue; }
                  if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                  found++;
                  el.classList.add(MARK);
                  markEdges(el);
                  if (topInset > 0) {
                    var hasMark = el.classList.contains(TOP_MARK);
                    var pt = parseInt(cs.paddingTop, 10) || 0;
                    var top = parseInt(cs.top, 10) || 0;
                    var mt = parseInt(cs.marginTop, 10) || 0;
                    // Official safe-area path moved it (possibly later than our
                    // first pass) → drop the fallback so shifts never stack.
                    // With the mark on, computed margin IS the fallback, so it
                    // only counts as "official" while the mark is off.
                    var official = pt >= topInset || top >= topInset ||
                      (!hasMark && mt >= topInset);
                    if (official) {
                      el.classList.remove(TOP_MARK);
                    } else if (!hasMark) {
                      el.classList.add(TOP_MARK);
                    }
                  } else {
                    el.classList.remove(TOP_MARK);
                  }
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                  if (++seen > 5000) return;
                  if (all[j].shadowRoot) visit(all[j].shadowRoot, depth + 1);
                }
              };
              visit(msr, 0);
            } catch (e) {}
            return found;
          }

          var retriesLeft = 10;
          function applySoon(delayMs) {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function() {
              timer = null;
              var n = apply();
              // hui-root mounts late on cold start; keep knocking until it lands.
              if (n === 0 && retriesLeft > 0) {
                retriesLeft--;
                applySoon(1500);
              }
            }, delayMs);
          }

          function setSide(style, side, px) {
            if (px > 0) {
              var v = px + 'px';
              style.setProperty('--app-safe-area-inset-' + side, v);
              style.setProperty('--safe-area-inset-' + side, v, 'important');
            } else {
              style.removeProperty('--app-safe-area-inset-' + side);
              style.removeProperty('--safe-area-inset-' + side);
            }
          }

          // Official-companion-app parity (InsetsUtil.applyInsets): all four
          // sides carry the REAL system-bar/cutout overlap in CSS px. The
          // frontend positions its header, FABs (add integration & co) and
          // dialogs from these variables — sides that are 0 stay 0, so card
          // margins never move on bar-less edges.
          window.__avaSetInsets = function(top, right, bottom, left) {
            try {
              var root = document.documentElement;
              if (!root || !root.style) return;
              top = top | 0; right = right | 0; bottom = bottom | 0; left = left | 0;
              topInset = top;
              var s = root.style;
              if (top > 0) s.setProperty('--ava-top-inset', top + 'px');
              else s.removeProperty('--ava-top-inset');
              setSide(s, 'top', top);
              setSide(s, 'right', right);
              setSide(s, 'bottom', bottom);
              setSide(s, 'left', left);
              retriesLeft = 10;
              applySoon(50);
            } catch (e) {}
          };
          window.__avaSetTopInset = function(px) {
            window.__avaSetInsets(px, 0, 0, 0);
          };
          window.__avaSetSidebarEdge = function(side) {
            var next = (side === 'right') ? 'right' : (side === 'none') ? 'none' : 'left';
            if (next === sidebarEdge) return;
            sidebarEdge = next;
            retriesLeft = 10;
            applySoon(50);
          };

          // HA swaps headers on navigation; re-tag after the new panel mounts.
          window.addEventListener('location-changed', function() { applySoon(500); });
          window.addEventListener('popstate', function() { applySoon(500); });
          // Slow safety net for panels that rebuild without a history event.
          setInterval(function() {
            if (document.hidden) return;
            apply();
          }, 8000);
          applySoon(1500);
        })();
    """

    fun installOnWebView(webView: WebView) {
        // Best-effort only: isFeatureSupported() reflects the support-library
        // boundary interface, NOT whether this WebView build actually executes
        // registered scripts — never let this be the sole delivery channel, and
        // never let a lying boundary crash setup. Redundant delivery is safe:
        // every script self-guards on a window.__ava* flag.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                for (script in pageCompatScripts()) {
                    WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                }
            }
        }
        // Legacy engines additionally get the compat pack spliced into <head>
        // by [BrowserLegacyTranspiler.interceptMainDocument], and
        // [ensureInstalledOnPage] runs per navigation as the last net.
    }

    /**
     * Engine-agnostic page scripts for surfaces injected through other channels
     * (Gecko delivers these via its document-start content-script bridge).
     */
    fun pageCompatScripts(): List<String> =
        listOf(
            LEGACY_DOM_POLYFILL_JS,
            SHADOW_DOM_CLICK_RETARGET_JS,
            HUI_IMAGE_ANTI_FLICKER_JS,
            CALENDAR_CARD_ANTI_FLICKER_JS,
            SELECTOR_COMPAT_JS,
            CSS_COMPAT_JS,
            JSON_PARSE_TRACER_JS,
            STATUS_BAR_INSET_BRIDGE_JS,
        )

    /**
     * Per-navigation install, called at commit + finish. Deliberately
     * UNCONDITIONAL: gating this on isFeatureSupported(DOCUMENT_START_SCRIPT)
     * turned every channel off at once when the boundary claimed support the
     * WebView build didn't honor — the scripts then never ran through ANY
     * path. Re-evaluating is harmless (window.__ava* guards) and costs
     * milliseconds; silently running nothing cost days of broken styling.
     */
    fun ensureInstalledOnPage(webView: WebView) {
        for (script in pageCompatScripts()) {
            webView.evaluateJavascript(script, null)
        }
    }
}
