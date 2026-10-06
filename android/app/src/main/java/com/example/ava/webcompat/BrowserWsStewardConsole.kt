package com.example.ava.webcompat

/**
 * In-page Home Assistant console for the WebView Health Steward.
 *
 * Lovelace 2026.8 (`hui-root`, hass-frontend-20260826.1):
 * - Wide: icon button before the + (`ui.panel.lovelace.menu.add`).
 * - Narrow: first `ha-dropdown-item` inside `#dashboardmenu`, above
 *   「添加至 Home Assistant」 / "Add to Home Assistant".
 *
 * The sheet shows health first (CPU, DOM nodes, heap, pressure),
 * then the subscribe list. Frame time stays on the spark only.
 */
object BrowserWsStewardConsole {

    val removeJs: String = """
        (function() {
          try {
            if (window.__avaStewardConsole) window.__avaStewardConsole.setEnabled(false);
          } catch (e) {}
          return 'console-off';
        })();
    """.trimIndent()

    val installJs: String = """
        (function() {
          var ICON = 'M13.13 22.19L11.5 18.36C13.07 17.78 14.54 17 15.9 16.09L13.13 22.19M5.64 12.5L1.81 10.87L7.91 8.1C7 9.46 6.22 10.93 5.64 12.5M19.22 4C19.5 4 19.75 4 19.96 4.05C20.13 5.44 19.94 8.3 16.66 11.58C14.96 13.29 12.93 14.6 10.65 15.47L8.5 13.37C9.42 11.06 10.73 9.03 12.42 7.34C15.18 4.58 17.64 4 19.22 4M19.22 2C17.24 2 14.24 2.69 11 5.93C8.81 8.12 7.5 10.53 6.65 12.64C6.37 13.39 6.56 14.21 7.11 14.77L9.24 16.89C9.62 17.27 10.13 17.5 10.66 17.5C10.89 17.5 11.13 17.44 11.36 17.35C13.5 16.53 15.88 15.19 18.07 13C23.73 7.34 21.61 2.39 21.61 2.39S20.7 2 19.22 2M14.54 9.46C13.76 8.68 13.76 7.41 14.54 6.63S16.59 5.85 17.37 6.63C18.14 7.41 18.15 8.68 17.37 9.46C16.59 10.24 15.32 10.24 14.54 9.46M8.88 16.53L7.47 15.12L8.88 16.53M6.24 22L9.88 18.36C9.54 18.27 9.21 18.12 8.91 17.91L4.83 22H6.24M2 22H3.41L8.18 17.24L6.76 15.83L2 20.59V22M2 19.17L6.09 15.09C5.88 14.79 5.73 14.47 5.64 14.12L2 17.76V19.17Z';
          var CLOSE = 'M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12L19,6.41Z';
          var PLUS = 'M19,13H13V19';
          var HDR = 'ava-steward-hdr';
          var MENU = 'ava-steward-menu';
          var NAV = 'ava-steward-nav';
          var DLG = 'ava-steward-dlg';
          var CSS = 'ava-steward-console-css';
          var MAX_ROWS = 80;

          var STR = {
            en: {
              btn: 'WebView',
              title: 'WebView Health Steward',
              countPage: '{n} / {t} subscribed',
              countAll: '{n} subscribed',
              empty: 'No entities on this view yet.',
              more: '+{n} more',
              copy: 'Copy list',
              copied: 'Copied',
              reanalyze: 'Re-analyze',
              close: 'Close',
              health: 'Health',
              refresh: 'Refresh',
              cpu: 'CPU',
              nodes: 'Nodes',
              heap: 'Heap',
              pressure: 'Pressure',
              cpuPct: '{n}%',
              hz: '{n} Hz',
              msFrame: '{n} ms',
              heapMb: '{n} MB',
              fpsNow: '{n} fps · {m} ms',
              hzCap: '{n} Hz · capped',
              pressureOk: 'Normal',
              pressureWatch: 'Elevated',
              pressureHigh: 'Strained',
              pressureCrit: 'Critical',
              engChromium: 'Android System WebView',
              engGecko: 'GeckoView'
            },
            zh: {
              btn: 'WebView',
              title: 'WebView 健康管家',
              countPage: '订阅 {n} / {t}',
              countAll: '订阅 {n}',
              empty: '当前视图尚未发现实体。',
              more: '另有 {n} 个',
              copy: '复制列表',
              copied: '已复制',
              reanalyze: '重新分析',
              close: '关闭',
              health: '健康侦测',
              refresh: '刷新率',
              cpu: 'CPU',
              nodes: '节点',
              heap: '堆',
              pressure: '健康',
              cpuPct: '{n}%',
              hz: '{n} Hz',
              msFrame: '{n} ms',
              heapMb: '{n} MB',
              fpsNow: '{n} fps · {m} ms',
              hzCap: '{n} Hz · 已封顶',
              pressureOk: '正常',
              pressureWatch: '偏高',
              pressureHigh: '紧张',
              pressureCrit: '过载',
              engChromium: 'Android System WebView',
              engGecko: 'GeckoView'
            }
          };

          function hassObj() {
            try {
              var ha = document.querySelector('home-assistant');
              return ha && ha.hass ? ha.hass : null;
            } catch (e) { return null; }
          }

          function loc() {
            var lang = '';
            try {
              var h = hassObj();
              lang = (h && (h.language || (h.locale && h.locale.language))) || '';
            } catch (e) {}
            return String(lang).toLowerCase().indexOf('zh') === 0 ? STR.zh : STR.en;
          }

          function t(key, n, tot) {
            var s = loc()[key] || key;
            if (n != null) s = s.replace('{n}', String(n));
            if (tot != null) s = s.replace('{t}', String(tot)).replace('{m}', String(tot));
            return s;
          }

          function liveMode(trim) {
            if (!trim || trim.enabled === false) return 'off';
            if (trim._parked || trim._mode === 'parked') return 'parked';
            if (trim._mode === 'page' || trim._mode === 'domain') return trim._mode;
            if (Array.isArray(trim.pendingIds) && trim.pendingIds.length) {
              return trim._mode === 'domain' ? 'domain' : 'page';
            }
            var st = String(trim.lastStatus || '');
            if (st.indexOf('page-keep-full') === 0 || st.indexOf('page-full') === 0 ||
                st.indexOf('skip-panel') === 0) {
              return 'full';
            }
            if (!trim.warmedUp || st === 'warmup-skip' || st === 'boot' ||
                st.indexOf('wrapped') === 0 || st.indexOf('hc-') === 0 ||
                st === 'ready-hook' || st === 'adopt-wait') {
              return 'warm';
            }
            return 'full';
          }

          function hostReport() {
            return window.__avaHost || {};
          }

          function buildSnapshot(collected) {
            var trim = window.__avaEntityTrim || {};
            var hass = hassObj();
            var total = 0;
            var connected = false;
            try {
              if (hass && hass.states) total = Object.keys(hass.states).length;
              connected = !!(hass && hass.connection && hass.connected !== false);
            } catch (e) {}
            var mode = liveMode(trim);
            var ids = [];
            if (mode === 'page' || mode === 'domain') {
              ids = Array.isArray(trim.pendingIds) ? trim.pendingIds.slice() : [];
            }
            var fromConfig = (collected && collected.fromConfig) ||
              trim._lastFromConfig || (trim.snapshot && trim.snapshot.fromConfig) || [];
            var fromDom = (collected && collected.fromDom) ||
              trim._lastFromDom || (trim.snapshot && trim.snapshot.fromDom) || [];
            if (!ids.length && collected && collected.ids) ids = collected.ids.slice();
            var watching = (mode === 'page' || mode === 'domain')
              ? (Array.isArray(trim.pendingIds) && trim.pendingIds.length
                ? trim.pendingIds.length
                : ids.length)
              : (mode === 'parked' ? 0 : total);
            var snap = {
              mode: mode,
              ids: ids,
              fromConfig: fromConfig,
              fromDom: fromDom,
              watching: watching,
              total: total,
              connected: connected,
              host: hostReport()
            };
            trim.snapshot = snap;
            return snap;
          }

          function countLine(snap) {
            if (!snap) return t('countAll', 0);
            var n = snap.watching || 0;
            var tot = snap.total || 0;
            if (snap.mode === 'page' || snap.mode === 'domain' || snap.mode === 'parked') {
              return t('countPage', n, tot);
            }
            if (snap.mode === 'warm') {
              var w = (snap.fromConfig && snap.fromConfig.length) ||
                (snap.ids && snap.ids.length) || n;
              return tot ? t('countPage', w, tot) : t('countAll', w);
            }
            return t('countAll', tot || n);
          }

          function findId(id) {
            var el = document.getElementById(id);
            if (el) return el;
            var root = pierce('hui-root');
            if (root && root.shadowRoot) {
              el = root.shadowRoot.getElementById(id);
              if (el) return el;
            }
            var side = pierce('ha-sidebar');
            if (side && side.shadowRoot) {
              el = side.shadowRoot.getElementById(id);
              if (el) return el;
            }
            return null;
          }

          function pierce(sel) {
            var ha = document.querySelector('home-assistant');
            if (!ha) return null;
            var roots = [ha];
            if (ha.shadowRoot) roots.push(ha.shadowRoot);
            var main = ha.shadowRoot && ha.shadowRoot.querySelector('home-assistant-main');
            if (main) {
              roots.push(main);
              if (main.shadowRoot) roots.push(main.shadowRoot);
            }
            var i, r, hit;
            for (i = 0; i < roots.length; i++) {
              try {
                hit = roots[i].querySelector(sel);
                if (hit) return hit;
              } catch (e) {}
            }
            for (i = 0; i < roots.length; i++) {
              r = roots[i];
              if (!r || !r.querySelectorAll) continue;
              var all = r.querySelectorAll('*');
              for (var j = 0; j < all.length && j < 400; j++) {
                try {
                  if (all[j].shadowRoot) {
                    hit = all[j].shadowRoot.querySelector(sel);
                    if (hit) return hit;
                  }
                } catch (e2) {}
              }
            }
            try { return document.querySelector(sel); } catch (e3) { return null; }
          }

          function ensureCss() {
            if (document.getElementById(CSS)) return;
            var s = document.createElement('style');
            s.id = CSS;
            s.textContent = [
              '#' + DLG + '{position:fixed;inset:0;z-index:10001;display:flex;',
              'align-items:center;justify-content:center;padding:12px;',
              'font-family:var(--ha-font-family-body, Roboto, Noto Sans, sans-serif);}',
              '#' + DLG + ' .ava-sc-back{position:absolute;inset:0;',
              'background:rgba(0,0,0,.48);}',
              '#' + DLG + ' .ava-sc-sheet{position:relative;z-index:1;width:min(480px,100%);',
              'max-height:min(88vh,640px);height:auto;display:flex;flex-direction:column;',
              'background:var(--ha-dialog-surface-background,var(--md-sys-color-surface,',
              'var(--card-background-color,#fff)));',
              'color:var(--primary-text-color);',
              'border-radius:var(--ha-dialog-border-radius,24px);',
              'box-shadow:var(--ha-card-box-shadow,0 8px 28px rgba(0,0,0,.28));',
              'border:1px solid var(--divider-color,transparent);overflow:hidden;}',
              '#' + DLG + '.ava-sc-fit-compact{padding:0;align-items:flex-end;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-sheet{width:100%;',
              'max-height:min(92vh,100%);border-radius:20px 20px 0 0;}',
              '#' + DLG + '.ava-sc-fit-short{padding:0;align-items:stretch;}',
              '#' + DLG + '.ava-sc-fit-short .ava-sc-sheet{width:100%;max-height:100%;',
              'height:100%;border-radius:0;}',
              '#' + DLG + '.ava-sc-fit-roomy{padding:24px;}',
              '#' + DLG + '.ava-sc-fit-roomy .ava-sc-sheet{width:min(500px,92vw);',
              'max-height:min(80vh,640px);}',
              '#' + DLG + ' .ava-sc-x{position:absolute;top:8px;right:8px;z-index:3;',
              'width:48px;height:48px;margin:0;padding:0;border:0;',
              'background:transparent;color:var(--primary-text-color);',
              'display:inline-flex;align-items:center;justify-content:center;',
              'border-radius:50%;cursor:pointer;}',
              '#' + DLG + ' .ava-sc-x:active{background:rgba(127,127,127,.16);}',
              '#' + DLG + ' .ava-sc-x ha-svg-icon,#' + DLG + ' .ava-sc-x .ava-sc-xico{',
              'width:24px;height:24px;display:block;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-x{top:4px;right:4px;}',
              '#' + DLG + ' .ava-sc-head{padding:20px 64px 12px 29px;flex-shrink:0;',
              'border-bottom:1px solid var(--divider-color);}',
              '#' + DLG + ' .ava-sc-title-row{display:flex;align-items:center;gap:10px;}',
              '#' + DLG + ' .ava-sc-title{font-size:1.28rem;font-weight:600;line-height:1.3;}',
              '#' + DLG + ' .ava-sc-dot{width:9px;height:9px;border-radius:50%;flex-shrink:0;',
              'background:var(--success-color,#43a047);}',
              '#' + DLG + ' .ava-sc-dot.off{background:var(--error-color,#db4437);}',
              '#' + DLG + ' .ava-sc-sub{margin-top:4px;font-size:1.05rem;',
              'color:var(--secondary-text-color);line-height:1.4;}',
              '#' + DLG + ' .ava-sc-scroll{position:relative;flex:1 1 auto;min-height:0;',
              'overflow:hidden;display:flex;flex-direction:column;}',
              '#' + DLG + ' .ava-sc-body{position:relative;overflow:auto;flex:1 1 auto;',
              'min-height:0;padding:20px 28px 24px 33px;',
              '-webkit-overflow-scrolling:touch;overscroll-behavior:contain;touch-action:pan-y;',
              'scrollbar-width:none;-ms-overflow-style:none;',
              '-webkit-mask-repeat:no-repeat;mask-repeat:no-repeat;',
              '-webkit-mask-size:100% 100%;mask-size:100% 100%;}',
              '#' + DLG + ' .ava-sc-body::-webkit-scrollbar{width:0;height:0;}',
              '#' + DLG + ' .ava-sc-health{margin-top:0;display:grid;',
              'grid-template-columns:minmax(0,1fr) minmax(0,1fr);',
              'grid-template-rows:auto auto;',
              'gap:0;border:1px solid var(--divider-color);border-radius:16px;overflow:hidden;}',
              '#' + DLG + ' .ava-sc-hv{display:flex;flex-direction:column;justify-content:center;',
              'padding:18px 20px;min-width:0;min-height:0;',
              'border-top:1px solid var(--divider-color);}',
              '#' + DLG + ' .ava-sc-hv:nth-child(-n+2){border-top:0;}',
              '#' + DLG + ' .ava-sc-hv:nth-child(even){border-left:1px solid var(--divider-color);}',
              '#' + DLG + ' .ava-sc-hk{font-size:1.15rem;color:var(--secondary-text-color);}',
              '#' + DLG + ' .ava-sc-hw{margin-top:8px;font-size:1.55rem;font-weight:600;',
              'font-variant-numeric:tabular-nums;letter-spacing:-.02em;}',
              '#' + DLG + ' .ava-sc-chart{margin-top:16px;padding:16px 18px 14px;',
              'border:1px solid var(--divider-color);border-radius:16px;flex-shrink:0;}',
              '#' + DLG + ' .ava-sc-chart-top{display:flex;justify-content:space-between;',
              'align-items:baseline;gap:10px;}',
              '#' + DLG + ' .ava-sc-chart-k{font-size:1.15rem;color:var(--secondary-text-color);}',
              '#' + DLG + ' .ava-sc-chart-v{font-size:1.35rem;font-weight:650;',
              'font-variant-numeric:tabular-nums;}',
              '#' + DLG + ' .ava-sc-spark{display:block;width:100%;height:72px;margin-top:6px;',
              'flex-shrink:0;}',
              '#' + DLG + ' .ava-sc-allow{margin-top:22px;}',
              '#' + DLG + ' .ava-sc-domain{margin:16px 0 2px;font-size:1.12rem;',
              'color:var(--secondary-text-color);}',
              '#' + DLG + ' .ava-sc-row{display:flex;align-items:baseline;gap:12px;',
              'padding:14px 0;border-top:1px solid var(--divider-color);}',
              '#' + DLG + ' .ava-sc-name{flex:1;min-width:0;}',
              '#' + DLG + ' .ava-sc-fn{font-size:1.18rem;overflow:hidden;',
              'text-overflow:ellipsis;white-space:nowrap;}',
              '#' + DLG + ' .ava-sc-id{margin-top:3px;color:var(--secondary-text-color);',
              'font-family:var(--ha-font-family-code,monospace);font-size:.95rem;}',
              '#' + DLG + ' .ava-sc-st{color:var(--secondary-text-color);flex-shrink:0;',
              'max-width:28%;font-size:1.12rem;overflow:hidden;',
              'text-overflow:ellipsis;white-space:nowrap;}',
              '#' + DLG + ' .ava-sc-empty{margin-top:8px;font-size:1.12rem;',
              'color:var(--secondary-text-color);}',
              '#' + DLG + ' .ava-sc-count{margin-right:auto;min-width:0;padding:0 12px 0 0;',
              'font-size:1.12rem;color:var(--secondary-text-color);',
              'font-variant-numeric:tabular-nums;white-space:nowrap;}',
              '#' + DLG + ' .ava-sc-foot{display:flex;align-items:center;',
              'justify-content:flex-end;gap:4px;',
              'padding:8px 16px 12px 28px;flex-shrink:0;',
              'border-top:1px solid var(--divider-color);}',
              '#' + DLG + ' .ava-sc-btn{appearance:none;border:0;background:transparent;',
              'color:var(--primary-color);font:inherit;font-weight:500;padding:10px 14px;',
              'border-radius:var(--ha-border-radius-md,8px);cursor:pointer;}',
              '#' + DLG + ' .ava-sc-btn.filled{background:var(--primary-color);',
              'color:var(--text-primary-color,#fff);}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-head{padding:18px 56px 14px 25px;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-body{padding:18px 22px 22px 27px;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-title{font-size:1.22rem;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-sub{font-size:1.02rem;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-hk{font-size:1.15rem;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-hw{font-size:1.5rem;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-chart-k{font-size:1.15rem;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-chart-v{font-size:1.3rem;}',
              '#' + DLG + '.ava-sc-fit-compact .ava-sc-foot{padding-left:27px;}',
              '#' + DLG + '.ava-sc-fit-land{padding:10px 14px;align-items:center;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-sheet{width:min(560px,90vw);',
              'max-height:min(84vh,420px);height:auto;border-radius:20px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-head{padding:12px 48px 10px 23px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-title{font-size:1.2rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-sub{margin-top:2px;font-size:1rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-x{top:2px;right:2px;width:40px;height:40px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-body{padding:16px 20px 16px 25px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-hv{min-height:0;padding:16px 18px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-hk{font-size:1.15rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-hw{margin-top:6px;font-size:1.5rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-chart{margin-top:12px;padding:14px 16px 12px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-chart-k{font-size:1.15rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-chart-v{font-size:1.3rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-spark{height:40px;margin-top:4px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-allow{margin-top:12px;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-domain{margin:10px 0 2px;font-size:1.08rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-row{padding:10px 0;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-fn{font-size:1.15rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-id{font-size:.94rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-st{font-size:1.08rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-count{font-size:1.08rem;}',
              '#' + DLG + '.ava-sc-fit-land .ava-sc-foot{padding:6px 12px 8px 25px;}',
              '#' + DLG + '.ava-sc-fit-land.ava-sc-fit-short{padding:8px 12px;}',
              '#' + DLG + '.ava-sc-fit-land.ava-sc-fit-short .ava-sc-sheet{',
              'width:min(560px,92vw);max-height:92vh;}',
              '#' + DLG + '.ava-sc-fit-land.ava-sc-fit-short .ava-sc-spark{height:36px;}',
              '#' + HDR + '.ava-sc-iconbtn{width:48px;height:48px;margin:0;padding:0;',
              'border:0;background:transparent;color:var(--primary-text-color);',
              'display:inline-flex;align-items:center;justify-content:center;',
              'cursor:pointer;border-radius:50%;flex-shrink:0;}',
              '#' + HDR + '.ava-sc-iconbtn ha-svg-icon,#' + HDR + ' .ava-sc-ico{',
              'width:24px;height:24px;display:block;transform:translateY(1px);}',
              '#' + HDR + ' .ava-sc-badge,#' + NAV + ' .ava-sc-badge{position:absolute;',
              'top:4px;right:4px;min-width:14px;height:14px;padding:0 3px;',
              'border-radius:8px;background:var(--accent-color,var(--primary-color));',
              'color:var(--text-accent-color,var(--text-primary-color,#fff));',
              'font-size:9px;line-height:14px;text-align:center;pointer-events:none;}',
              '#' + HDR + '{position:relative;}',
            ].join('');
            (document.head || document.documentElement).appendChild(s);
          }

          async function hydrateSnapshot() {
            var conn = null;
            try { conn = hassObj() && hassObj().connection; } catch (e) {}
            var collected = null;
            if (typeof window.__avaCollectCurrentPageEntityIds === 'function') {
              try { collected = await window.__avaCollectCurrentPageEntityIds(conn); } catch (e2) {}
            }
            var trim = window.__avaEntityTrim || {};
            if (collected) {
              trim._lastFromConfig = collected.fromConfig || [];
              trim._lastFromDom = collected.fromDom || [];
            }
            if (typeof window.__avaWriteTrimSnapshot === 'function') {
              try {
                window.__avaWriteTrimSnapshot(collected ? {
                  ids: (trim.pendingIds && trim.pendingIds.length)
                    ? trim.pendingIds
                    : (collected.ids || []),
                  fromConfig: collected.fromConfig,
                  fromDom: collected.fromDom,
                  opaque: collected.opaqueReason,
                  mode: liveMode(trim)
                } : null);
              } catch (e3) {}
            }
            return buildSnapshot(collected);
          }

          function friendly(id) {
            try {
              var st = hassObj() && hassObj().states && hassObj().states[id];
              if (st && st.attributes && st.attributes.friendly_name) {
                return st.attributes.friendly_name;
              }
            } catch (e) {}
            return id;
          }

          function entityState(id) {
            try {
              var st = hassObj() && hassObj().states && hassObj().states[id];
              return st ? String(st.state) : '';
            } catch (e) { return ''; }
          }

          function displayIds(snap) {
            if (snap.mode === 'page' || snap.mode === 'domain') {
              if (snap.ids && snap.ids.length) return snap.ids.slice();
            }
            var merged = {};
            var a = snap.fromConfig || [];
            var b = snap.fromDom || [];
            var i;
            for (i = 0; i < a.length; i++) merged[a[i]] = 1;
            for (i = 0; i < b.length; i++) merged[b[i]] = 1;
            if (snap.ids) {
              for (i = 0; i < snap.ids.length; i++) merged[snap.ids[i]] = 1;
            }
            return Object.keys(merged);
          }

          function groupByDomain(ids) {
            var g = {};
            for (var i = 0; i < ids.length; i++) {
              var id = ids[i];
              var dot = id.indexOf('.');
              var d = dot > 0 ? id.slice(0, dot) : id;
              if (!g[d]) g[d] = [];
              g[d].push(id);
            }
            var keys = Object.keys(g).sort();
            var out = [];
            for (var k = 0; k < keys.length; k++) out.push({ domain: keys[k], ids: g[keys[k]].sort() });
            return out;
          }

          function svgIcon(path, cls) {
            if (customElements.get && customElements.get('ha-svg-icon')) {
              var ic = document.createElement('ha-svg-icon');
              ic.path = path;
              return ic;
            }
            var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('class', cls || 'ava-sc-xico');
            svg.setAttribute('viewBox', '0 0 24 24');
            var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            p.setAttribute('d', path);
            p.setAttribute('fill', 'currentColor');
            svg.appendChild(p);
            return svg;
          }

          function makeCloseBtn() {
            var xb = document.createElement('button');
            xb.type = 'button';
            xb.className = 'ava-sc-x';
            xb.setAttribute('aria-label', t('close'));
            xb.appendChild(svgIcon(CLOSE, 'ava-sc-xico'));
            xb.addEventListener('click', closeDlg);
            return xb;
          }

          function makeBtn(text, filled) {
            if (customElements.get && customElements.get('ha-button')) {
              var hb = document.createElement('ha-button');
              hb.textContent = text;
              hb.appearance = filled ? 'filled' : 'plain';
              hb.size = 'small';
              return hb;
            }
            var b = document.createElement('button');
            b.className = 'ava-sc-btn' + (filled ? ' filled' : '');
            b.textContent = text;
            return b;
          }

          function el(tag, cls, text) {
            var n = document.createElement(tag);
            if (cls) n.className = cls;
            if (text != null) n.textContent = text;
            return n;
          }

          function chromeVerFromUa(ua) {
            var m = /(?:Chrome|Chromium|Edg|CriOS)\/([0-9.]+)/.exec(ua || '');
            return m ? m[1] : '';
          }

          function pageUserAgent() {
            try { return navigator.userAgent || ''; } catch (e) { return ''; }
          }

          function identityLine(snap) {
            var h = snap.host || {};
            if (h.engine === 'gecko') {
              return t('engGecko') + (h.gecko ? ' ' + h.gecko : '');
            }
            var ua = h.ua || pageUserAgent();
            var ver = h.ver || chromeVerFromUa(ua) || (h.major ? String(h.major) : '');
            return t('engChromium') + (ver ? ' ' + ver : '');
          }

          function setText(root, sel, text) {
            var n = root.querySelector(sel);
            if (n) n.textContent = text;
          }

          var frameWatch = {
            on: false,
            raf: 0,
            last: 0,
            acc: 0,
            accN: 0,
            lastPush: 0,
            ema: 0,
            live: 0,
            series: []
          };

          function isLand() {
            var dlg = document.getElementById(DLG);
            return !!(dlg && dlg.classList.contains('ava-sc-fit-land'));
          }

          function sampleMs() {
            var hz = hostHz();
            var frames = Math.max(6, Math.round(hz / 8));
            return Math.round(1000 * frames / hz);
          }

          function sparkLen() {
            return isLand() ? 24 : 32;
          }

          function sparkGeom() {
            var dlg = document.getElementById(DLG);
            var land = !!(dlg && dlg.classList.contains('ava-sc-fit-land'));
            var short = !!(dlg && dlg.classList.contains('ava-sc-fit-short'));
            return {
              w: 320,
              h: land ? (short ? 36 : 40) : 72,
              pad: land ? 4 : 6
            };
          }

          function hostHz() {
            var h = hostReport();
            var hz = h && h.hz ? Number(h.hz) : 0;
            return hz > 0 ? hz : 60;
          }

          function heapMb() {
            try {
              var mem = performance.memory;
              if (mem && mem.usedJSHeapSize) return mem.usedJSHeapSize / 1048576;
            } catch (e) {}
            return 0;
          }

          function cpuValue(h) {
            return typeof h.appCpu === 'number' ? t('cpuPct', h.appCpu.toFixed(1)) : '—';
          }

          function nodeCount() {
            var n = 0;
            try { n += document.getElementsByTagName('*').length; } catch (e) {}
            try {
              var ha = document.querySelector('home-assistant');
              if (ha && ha.shadowRoot) n += ha.shadowRoot.querySelectorAll('*').length;
            } catch (e2) {}
            try {
              var root = pierce('hui-root');
              if (root && root.shadowRoot) n += root.shadowRoot.querySelectorAll('*').length;
            } catch (e3) {}
            return n;
          }

          function nodesValue() {
            var n = nodeCount();
            return n > 0 ? String(n) : '—';
          }

          function heapValue() {
            var mb = heapMb();
            return mb > 0 ? t('heapMb', mb.toFixed(1)) : '—';
          }

          function pressureValue(h) {
            var p = String(h.pressure || 'GREEN').toUpperCase();
            if (p.indexOf('RED') === 0) return t('pressureCrit');
            if (p.indexOf('ORANGE') === 0) return t('pressureHigh');
            if (p.indexOf('YELLOW') === 0) return t('pressureWatch');
            return t('pressureOk');
          }

          function sparkPath(values, min, max, w, h, pad) {
            if (!values.length) return '';
            var span = max - min || 1;
            return values.map(function(v, i) {
              var x = pad + (i / Math.max(1, values.length - 1)) * (w - pad * 2);
              var y = pad + (1 - (v - min) / span) * (h - pad * 2);
              return (i ? 'L' : 'M') + x.toFixed(1) + ',' + y.toFixed(1);
            }).join(' ');
          }

          function paintSpark() {
            var dlg = document.getElementById(DLG);
            if (!dlg) return;
            var series = frameWatch.series;
            var last = series.length ? series[series.length - 1] : 0;
            var fps = last > 0 ? Math.round(1000 / last) : 0;
            var live = t('fpsNow', fps || '—', last ? last.toFixed(1) : '—');
            setText(dlg, '[data-h=fps]', live);
            var host = hostReport();
            setText(dlg, '[data-h=cpu]', cpuValue(host));
            setText(dlg, '[data-h=nodes]', nodesValue());
            setText(dlg, '[data-h=heap]', heapValue());
            var svg = dlg.querySelector('.ava-sc-spark');
            var line = dlg.querySelector('.ava-sc-spark-line');
            var fill = dlg.querySelector('.ava-sc-spark-fill');
            var bud = dlg.querySelector('.ava-sc-spark-budget');
            if (!line || !fill) return;
            var geom = sparkGeom();
            var w = geom.w, h = geom.h, pad = geom.pad;
            if (svg) svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
            var budget = 1000 / hostHz();
            var max = Math.max(budget * 2, 33);
            var d = sparkPath(series, 0, max, w, h, pad);
            line.setAttribute('d', d);
            fill.setAttribute('d', d
              ? d + ' L' + (w - pad) + ',' + (h - pad) + ' L' + pad + ',' + (h - pad) + ' Z'
              : '');
            if (bud) {
              var y = pad + (1 - (budget / max)) * (h - pad * 2);
              bud.setAttribute('d', 'M' + pad + ',' + y.toFixed(1) + ' L' + (w - pad) + ',' + y.toFixed(1));
            }
          }

          function stopFrameWatch() {
            frameWatch.on = false;
            if (frameWatch.raf) {
              try { cancelAnimationFrame(frameWatch.raf); } catch (e) {}
            }
            frameWatch.raf = 0;
            frameWatch.last = 0;
            frameWatch.acc = 0;
            frameWatch.accN = 0;
            frameWatch.ema = 0;
            frameWatch.live = 0;
          }

          function startFrameWatch() {
            if (frameWatch.on) return;
            frameWatch.on = true;
            frameWatch.last = 0;
            frameWatch.acc = 0;
            frameWatch.accN = 0;
            frameWatch.lastPush = 0;
            frameWatch.ema = 0;
            var tick = function(ts) {
              if (!frameWatch.on || !document.getElementById(DLG)) {
                stopFrameWatch();
                return;
              }
              if (document.hidden) {
                frameWatch.last = 0;
                frameWatch.raf = requestAnimationFrame(tick);
                return;
              }
              if (frameWatch.last) {
                var dt = ts - frameWatch.last;
                var cap = 3000 / hostHz();
                if (dt > 0 && dt < 200) {
                  frameWatch.acc += dt > cap ? cap : dt;
                  frameWatch.accN += 1;
                }
              }
              frameWatch.last = ts;
              if (!frameWatch.lastPush) frameWatch.lastPush = ts;
              if (ts - frameWatch.lastPush >= sampleMs() && frameWatch.accN) {
                var raw = frameWatch.acc / frameWatch.accN;
                frameWatch.live = raw;
                frameWatch.ema = frameWatch.ema
                  ? frameWatch.ema + 0.32 * (raw - frameWatch.ema) : raw;
                frameWatch.series.push(frameWatch.ema);
                var keep = sparkLen();
                while (frameWatch.series.length > keep) frameWatch.series.shift();
                frameWatch.acc = 0;
                frameWatch.accN = 0;
                frameWatch.lastPush = ts;
                paintSpark();
              }
              frameWatch.raf = requestAnimationFrame(tick);
            };
            frameWatch.raf = requestAnimationFrame(tick);
          }

          function fillHero(root, snap) {
            var dot = root.querySelector('.ava-sc-dot');
            if (dot) dot.className = 'ava-sc-dot' + (snap.connected ? '' : ' off');
            var h = snap.host || {};
            setText(root, '[data-h=cpu]', cpuValue(h));
            setText(root, '[data-h=nodes]', nodesValue());
            setText(root, '[data-h=heap]', heapValue());
            setText(root, '[data-h=pressure]', pressureValue(h));
            paintSpark();
          }

          function fillList(elList, snap) {
            while (elList.firstChild) elList.removeChild(elList.firstChild);
            var ids = displayIds(snap);
            if (!ids.length) {
              elList.appendChild(el('div', 'ava-sc-empty', t('empty')));
              return;
            }
            var groups = groupByDomain(ids);
            var shown = 0;
            var hidden = 0;
            for (var g = 0; g < groups.length; g++) {
              var group = groups[g];
              elList.appendChild(el('div', 'ava-sc-domain', group.domain + ' · ' + group.ids.length));
              for (var r = 0; r < group.ids.length; r++) {
                if (shown >= MAX_ROWS) { hidden += group.ids.length - r; break; }
                var id = group.ids[r];
                var row = el('div', 'ava-sc-row');
                var name = el('div', 'ava-sc-name');
                name.appendChild(el('div', 'ava-sc-fn', friendly(id)));
                name.appendChild(el('div', 'ava-sc-id', id));
                row.appendChild(name);
                row.appendChild(el('div', 'ava-sc-st', entityState(id)));
                elList.appendChild(row);
                shown++;
              }
              if (shown >= MAX_ROWS) {
                hidden += groups.slice(g + 1).reduce(function(n, x) { return n + x.ids.length; }, 0);
                break;
              }
            }
            if (hidden > 0) elList.appendChild(el('div', 'ava-sc-empty', t('more', hidden)));
          }

          function dissolveMask(showTop, showBot) {
            var h = 36;
            if (!showTop && !showBot) return 'none';
            if (showTop && showBot) {
              return 'linear-gradient(to bottom,transparent,#000 ' + h +
                'px,#000 calc(100% - ' + h + 'px),transparent)';
            }
            if (showTop) {
              return 'linear-gradient(to bottom,transparent,#000 ' + h + 'px,#000 100%)';
            }
            return 'linear-gradient(to bottom,#000 0,#000 calc(100% - ' + h + 'px),transparent)';
          }

          function paintDissolve(scroll) {
            var node = scroll && scroll.querySelector
              ? scroll.querySelector('.ava-sc-body') : scroll;
            if (!node) return;
            var y = node.scrollTop;
            var max = node.scrollHeight - node.clientHeight;
            var mask = dissolveMask(y > 2, max > 2 && y < max - 2);
            node.style.webkitMaskImage = mask;
            node.style.maskImage = mask;
          }

          function bindDissolve(scroll) {
            var node = scroll.querySelector ? scroll.querySelector('.ava-sc-body') : scroll;
            if (!node) return;
            if (node.__avaScFade) {
              paintDissolve(scroll);
              return;
            }
            node.__avaScFade = true;
            var onScroll = function() { paintDissolve(scroll); };
            node.addEventListener('scroll', onScroll, { passive: true });
            node.addEventListener('touchstart', function(ev) { ev.stopPropagation(); }, { passive: true });
            node.addEventListener('touchmove', function(ev) { ev.stopPropagation(); }, { passive: true });
            node.addEventListener('wheel', function(ev) { ev.stopPropagation(); }, { passive: true });
            if (typeof ResizeObserver !== 'undefined') {
              var ro = new ResizeObserver(onScroll);
              ro.observe(node);
            }
            paintDissolve(scroll);
            setTimeout(onScroll, 40);
          }

          function allowKey(snap) {
            return [snap.mode, snap.watching, snap.total, (snap.ids || []).join(',')].join('|');
          }

          function paintLive(snap) {
            var dlg = document.getElementById(DLG);
            if (!dlg) return;
            fitScreen(dlg);
            setText(dlg, '.ava-sc-sub', identityLine(snap));
            fillHero(dlg, snap);
            var list = dlg.querySelector('.ava-sc-allow');
            if (list && state.lastAllow !== allowKey(snap)) {
              fillList(list, snap);
              state.lastAllow = allowKey(snap);
            }
            setText(dlg, '.ava-sc-count', countLine(snap));
            var scroll = dlg.querySelector('.ava-sc-scroll');
            if (scroll) bindDissolve(scroll);
            paintCountBadges(snap);
          }

          function buildSheetBody() {
            var body = el('div', 'ava-sc-body');
            var health = el('div', 'ava-sc-health');
            var keys = [
              ['cpu', t('cpu')],
              ['nodes', t('nodes')],
              ['heap', t('heap')],
              ['pressure', t('pressure')]
            ];
            for (var i = 0; i < keys.length; i++) {
              var cell = el('div', 'ava-sc-hv');
              cell.appendChild(el('div', 'ava-sc-hk', keys[i][1]));
              var val = el('div', 'ava-sc-hw', '—');
              val.setAttribute('data-h', keys[i][0]);
              cell.appendChild(val);
              health.appendChild(cell);
            }
            body.appendChild(health);
            var chart = el('div', 'ava-sc-chart');
            var top = el('div', 'ava-sc-chart-top');
            top.appendChild(el('div', 'ava-sc-chart-k', t('refresh')));
            var live = el('div', 'ava-sc-chart-v', '—');
            live.setAttribute('data-h', 'fps');
            top.appendChild(live);
            chart.appendChild(top);
            var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('class', 'ava-sc-spark');
            svg.setAttribute('viewBox', '0 0 320 72');
            svg.setAttribute('preserveAspectRatio', 'none');
            svg.setAttribute('aria-hidden', 'true');
            var ns = 'http://www.w3.org/2000/svg';
            var fill = document.createElementNS(ns, 'path');
            fill.setAttribute('class', 'ava-sc-spark-fill');
            fill.setAttribute('fill', 'var(--primary-color)');
            fill.setAttribute('fill-opacity', '0.16');
            var bud = document.createElementNS(ns, 'path');
            bud.setAttribute('class', 'ava-sc-spark-budget');
            bud.setAttribute('fill', 'none');
            bud.setAttribute('stroke', 'var(--success-color,#43a047)');
            bud.setAttribute('stroke-width', '1.2');
            bud.setAttribute('stroke-dasharray', '4 4');
            bud.setAttribute('opacity', '0.7');
            var line = document.createElementNS(ns, 'path');
            line.setAttribute('class', 'ava-sc-spark-line');
            line.setAttribute('fill', 'none');
            line.setAttribute('stroke', 'var(--primary-color)');
            line.setAttribute('stroke-width', '2.1');
            line.setAttribute('stroke-linecap', 'round');
            line.setAttribute('stroke-linejoin', 'round');
            svg.appendChild(fill);
            svg.appendChild(bud);
            svg.appendChild(line);
            chart.appendChild(svg);
            body.appendChild(chart);
            body.appendChild(el('div', 'ava-sc-allow'));
            return body;
          }

          function fitScreen(root) {
            if (!root) root = document.getElementById(DLG);
            if (!root) return;
            var w = window.innerWidth || 360;
            var h = window.innerHeight || 640;
            root.classList.remove('ava-sc-fit-compact', 'ava-sc-fit-roomy',
              'ava-sc-fit-short', 'ava-sc-fit-land');
            if (w > h) root.classList.add('ava-sc-fit-land');
            if (h < 520) root.classList.add('ava-sc-fit-short');
            if (w < 440 || (h < 580 && w <= h)) root.classList.add('ava-sc-fit-compact');
            else if (w >= 720 && h >= 700) root.classList.add('ava-sc-fit-roomy');
          }

          function bindFit(root) {
            if (!root || root.__avaFit) return;
            root.__avaFit = true;
            var onFit = function() { fitScreen(root); paintSpark(); };
            window.addEventListener('resize', onFit);
            root.__avaFitOff = function() {
              window.removeEventListener('resize', onFit);
            };
          }

          function closeDlg() {
            var d = document.getElementById(DLG);
            if (d && d.__avaFitOff) {
              try { d.__avaFitOff(); } catch (e) {}
            }
            if (d && d.parentNode) d.parentNode.removeChild(d);
            document.removeEventListener('keydown', onEsc, true);
            stopFrameWatch();
            state.lastAllow = '';
          }

          function onEsc(ev) {
            if (ev.key === 'Escape' || ev.keyCode === 27) closeDlg();
          }

          function copyList(snap) {
            var ids = displayIds(snap);
            var text = ids.join('\n');
            try {
              if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text);
                return;
              }
            } catch (e) {}
            try {
              var ta = document.createElement('textarea');
              ta.value = text;
              document.body.appendChild(ta);
              ta.select();
              document.execCommand('copy');
              document.body.removeChild(ta);
            } catch (e2) {}
          }

          async function reanalyze() {
            if (typeof window.__avaArmEntityTrim === 'function') {
              try { window.__avaArmEntityTrim(); } catch (e) {}
            }
            var snap = await hydrateSnapshot();
            state.lastAllow = '';
            paintLive(snap);
          }

          function openDlg() {
            closeDlg();
            ensureCss();
            var root = document.createElement('div');
            root.id = DLG;
            root.setAttribute('role', 'dialog');
            root.setAttribute('aria-modal', 'true');
            root.setAttribute('aria-label', t('title'));
            var back = document.createElement('div');
            back.className = 'ava-sc-back';
            back.addEventListener('click', closeDlg);
            var sheet = document.createElement('div');
            sheet.className = 'ava-sc-sheet';
            var head = document.createElement('div');
            head.className = 'ava-sc-head';
            var titles = document.createElement('div');
            titles.className = 'ava-sc-titles';
            var row = el('div', 'ava-sc-title-row');
            row.appendChild(el('div', 'ava-sc-title', t('title')));
            row.appendChild(el('span', 'ava-sc-dot'));
            titles.appendChild(row);
            titles.appendChild(el('div', 'ava-sc-sub', ''));
            head.appendChild(titles);
            var xb = makeCloseBtn();
            var scroll = el('div', 'ava-sc-scroll');
            scroll.appendChild(buildSheetBody());
            var foot = el('div', 'ava-sc-foot');
            foot.appendChild(el('div', 'ava-sc-count', ''));
            var copy = makeBtn(t('copy'), false);
            copy.addEventListener('click', function() {
              copyList(buildSnapshot(null));
              copy.textContent = t('copied');
              setTimeout(function() { copy.textContent = t('copy'); }, 1200);
            });
            var refresh = makeBtn(t('reanalyze'), true);
            refresh.addEventListener('click', function() { reanalyze(); });
            foot.appendChild(copy);
            foot.appendChild(refresh);
            sheet.appendChild(xb);
            sheet.appendChild(head);
            sheet.appendChild(scroll);
            sheet.appendChild(foot);
            root.appendChild(back);
            root.appendChild(sheet);
            (document.body || document.documentElement).appendChild(root);
            fitScreen(root);
            bindFit(root);
            document.addEventListener('keydown', onEsc, true);
            try { xb.focus(); } catch (e) {}
            paintLive(buildSnapshot(null));
            startFrameWatch();
            hydrateSnapshot().then(function(snap) {
              if (!document.getElementById(DLG)) return;
              state.lastAllow = '';
              paintLive(snap);
            });
          }

          function badgeText(snap) {
            if (!snap) return '';
            if (snap.mode === 'page' || snap.mode === 'domain') {
              return String(snap.watching || 0);
            }
            return '';
          }

          function menuIconWrap(host) {
            var wrap = host.querySelector('.ava-sc-ico-wrap');
            if (wrap) return wrap;
            wrap = document.createElement('span');
            wrap.className = 'ava-sc-ico-wrap';
            var icon = host.querySelector('ha-svg-icon');
            if (icon) {
              wrap.setAttribute('slot', icon.getAttribute('slot') || 'icon');
              icon.removeAttribute('slot');
              if (icon.parentNode) icon.parentNode.insertBefore(wrap, icon);
              wrap.appendChild(icon);
            } else {
              wrap.setAttribute('slot', 'icon');
              host.insertBefore(wrap, host.firstChild);
            }
            return wrap;
          }

          function setBadge(host, snap) {
            if (!host) return;
            var txt = badgeText(snap);
            if (host.id === MENU) {
              var old = host.querySelector('[slot="details"]');
              if (old && old.parentNode) old.parentNode.removeChild(old);
              var wrap = menuIconWrap(host);
              var b = wrap.querySelector('.ava-sc-badge');
              if (!txt) { if (b && b.parentNode) b.parentNode.removeChild(b); return; }
              if (!b) {
                b = document.createElement('span');
                b.className = 'ava-sc-badge';
                wrap.appendChild(b);
              }
              b.textContent = txt;
              return;
            }
            var pill = host.querySelector('.ava-sc-badge');
            if (!txt) { if (pill && pill.parentNode) pill.parentNode.removeChild(pill); return; }
            if (!pill) {
              pill = document.createElement('span');
              pill.className = 'ava-sc-badge';
              host.appendChild(pill);
            }
            pill.textContent = txt;
          }

          function paintCountBadges(snap) {
            snap = snap || buildSnapshot(null);
            setBadge(findId(HDR), snap);
            setBadge(findId(MENU), snap);
          }

          function paintBadges() {
            if (document.getElementById(DLG)) paintLive(buildSnapshot(null));
            else paintCountBadges();
          }

          function overflowIsOpen(drop) {
            if (!drop) return false;
            try { if (drop.open === true) return true; } catch (e) {}
            try {
              var pop = drop.popup;
              if (pop && (pop.active || pop.open)) return true;
            } catch (e2) {}
            return drop.hasAttribute('open');
          }

          function hideOverflow(from) {
            var drop = from;
            try {
              if (from && from.closest) {
                drop = from.closest('ha-dropdown') || from.closest('ha-button-menu') || from;
              }
            } catch (e) {}
            if (!drop || !drop.localName) {
              var root = pierce('hui-root');
              var sr = root && root.shadowRoot;
              var menu = sr && sr.querySelector('#dashboardmenu');
              drop = menu && (menu.closest('ha-dropdown') || menu.closest('ha-button-menu'));
            }
            if (!drop) return;
            try { if (typeof drop.hideMenu === 'function') drop.hideMenu(); } catch (e2) {}
            try { if (typeof drop.hide === 'function') drop.hide(); } catch (e3) {}
            try { if (typeof drop.close === 'function') drop.close(); } catch (e4) {}
            try { drop.open = false; } catch (e5) {}
          }

          var opening = false;
          function onOpen(ev) {
            if (opening) return;
            opening = true;
            if (ev) {
              ev.preventDefault();
              ev.stopPropagation();
            }
            hideOverflow(ev && (ev.currentTarget || ev.target));
            openDlg();
            setTimeout(function() { opening = false; }, 400);
          }

          function makeHeaderBtn() {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.id = HDR;
            btn.className = 'ava-sc-iconbtn';
            btn.setAttribute('slot', 'actionItems');
            btn.setAttribute('aria-label', t('btn'));
            if (customElements.get && customElements.get('ha-svg-icon')) {
              var ic = document.createElement('ha-svg-icon');
              ic.path = ICON;
              btn.appendChild(ic);
            } else {
              var svg = document.createElement('span');
              svg.className = 'ava-sc-ico';
              svg.textContent = '☰';
              btn.appendChild(svg);
            }
            btn.addEventListener('click', onOpen);
            return btn;
          }

          function findPlusHost(box) {
            var buttons = box.querySelectorAll('ha-icon-button');
            for (var i = 0; i < buttons.length; i++) {
              var p = buttons[i].path || '';
              if (p.indexOf(PLUS) === 0) {
                return buttons[i].closest('ha-dropdown') || buttons[i];
              }
            }
            var add = box.querySelector('#add-view');
            if (add) return add;
            return null;
          }

          function overflowHost(box) {
            var menu = box && box.querySelector('#dashboardmenu');
            if (!menu) return null;
            return menu.closest('ha-dropdown') || menu.closest('ha-button-menu') || null;
          }

          function headerIsNarrow(root, box) {
            try {
              if (root && typeof root.narrow === 'boolean') return !!root.narrow;
            } catch (e) {}
            return !findPlusHost(box) && !!box.querySelector('#dashboardmenu');
          }

          function findAddOverflowItem(host) {
            var items = host.querySelectorAll('ha-dropdown-item, ha-list-item, mwc-list-item');
            for (var i = 0; i < items.length; i++) {
              var n = items[i];
              if (n.id === MENU) continue;
              var val = n.value || n.getAttribute('value') || '';
              if (val === 'ui.panel.lovelace.menu.add') return n;
              var txt = String(n.textContent || '').replace(/\s+/g, '');
              if (txt.indexOf('添加至HomeAssistant') >= 0 ||
                  txt.indexOf('AddtoHomeAssistant') >= 0) return n;
            }
            for (var j = 0; j < items.length; j++) {
              if (items[j].id !== MENU) return items[j];
            }
            return null;
          }

          function bindOverflowSelect(host) {
            if (!host || host.__avaScSel) return;
            host.__avaScSel = true;
            host.addEventListener('wa-select', function(ev) {
              var it = ev.detail && ev.detail.item;
              if (it && it.id === MENU) {
                ev.stopImmediatePropagation();
                hideOverflow(host);
                onOpen(ev);
              }
            }, true);
          }

          function makeMenuItem() {
            var item;
            var wrap = document.createElement('span');
            wrap.className = 'ava-sc-ico-wrap';
            if (customElements.get && customElements.get('ha-svg-icon')) {
              var ic = document.createElement('ha-svg-icon');
              ic.path = ICON;
              wrap.appendChild(ic);
            }
            if (customElements.get && customElements.get('ha-dropdown-item')) {
              item = document.createElement('ha-dropdown-item');
              item.value = 'ava.webview.steward';
              wrap.setAttribute('slot', 'icon');
              item.appendChild(wrap);
              item.appendChild(document.createTextNode(t('title')));
            } else if (customElements.get && customElements.get('ha-list-item')) {
              item = document.createElement('ha-list-item');
              item.setAttribute('graphic', 'icon');
              wrap.setAttribute('slot', 'graphic');
              item.appendChild(wrap);
              item.appendChild(document.createTextNode(t('title')));
            } else {
              item = document.createElement('button');
              item.type = 'button';
              item.appendChild(wrap);
              item.appendChild(document.createTextNode(t('title')));
            }
            item.id = MENU;
            item.setAttribute('aria-label', t('title'));
            item.addEventListener('click', onOpen);
            return item;
          }

          function shadowChromeCss() {
            return [
              '#' + HDR + '.ava-sc-iconbtn{width:48px;height:48px;margin:0;padding:0;',
              'border:0;background:transparent;color:var(--primary-text-color);',
              'display:inline-flex;align-items:center;justify-content:center;',
              'cursor:pointer;border-radius:50%;flex-shrink:0;position:relative;}',
              '#' + HDR + '.ava-sc-iconbtn ha-svg-icon,#' + HDR + ' .ava-sc-ico{',
              'width:24px;height:24px;display:block;transform:translateY(1px);}',
              '#' + HDR + ' .ava-sc-badge,#' + NAV + ' .ava-sc-badge,#' + MENU + ' .ava-sc-badge{',
              'position:absolute;min-width:14px;height:14px;padding:0 3px;',
              'border-radius:8px;background:var(--accent-color,var(--primary-color));',
              'color:var(--text-accent-color,var(--text-primary-color,#fff));',
              'font-size:9px;line-height:14px;text-align:center;pointer-events:none;}',
              '#' + HDR + ' .ava-sc-badge,#' + NAV + ' .ava-sc-badge{top:4px;right:4px;}',
              '#' + MENU + '{overflow:visible;}',
              '#' + MENU + ' .ava-sc-ico-wrap{position:relative;display:inline-flex;',
              'width:24px;height:24px;overflow:visible;transform:translateY(1px);}',
              '#' + MENU + ' .ava-sc-ico-wrap ha-svg-icon{width:24px;height:24px;display:block;}',
              '#' + MENU + ' .ava-sc-badge{top:-5px;right:-6px;}',
              '#' + NAV + '{position:relative;}'
            ].join('');
          }

          function ensureShadowCss(sr, id) {
            if (!sr) return;
            var sid = id || (CSS + '-sh');
            if (sr.getElementById && sr.getElementById(sid)) return;
            var s = document.createElement('style');
            s.id = sid;
            s.textContent = shadowChromeCss();
            sr.appendChild(s);
          }

          function injectHeader() {
            var root = pierce('hui-root');
            var sr = root && root.shadowRoot;
            if (!sr) return false;
            ensureShadowCss(sr, CSS + '-hdr');
            var box = sr.querySelector('.action-items');
            if (!box) return false;
            var drop = overflowHost(box);
            if (headerIsNarrow(root, box) && drop) {
              var oldBtn = sr.getElementById(HDR);
              if (oldBtn && oldBtn.parentNode) oldBtn.parentNode.removeChild(oldBtn);
              bindOverflowSelect(drop);
              var existing = drop.querySelector('#' + MENU) || sr.getElementById(MENU);
              if (existing) {
                if (!overflowIsOpen(drop)) {
                  var add = findAddOverflowItem(drop);
                  if (add && existing.nextElementSibling !== add) drop.insertBefore(existing, add);
                }
                setBadge(existing, buildSnapshot(null));
                return true;
              }
              if (overflowIsOpen(drop)) return true;
              var item = makeMenuItem();
              var before = findAddOverflowItem(drop);
              if (before) drop.insertBefore(item, before);
              else drop.appendChild(item);
              setBadge(item, buildSnapshot(null));
              return true;
            }
            var menuItem = (drop && drop.querySelector('#' + MENU)) || sr.getElementById(MENU);
            if (menuItem && menuItem.parentNode) menuItem.parentNode.removeChild(menuItem);
            if (sr.getElementById(HDR)) return true;
            var btn = makeHeaderBtn();
            var host = findPlusHost(box);
            if (!host) {
              var menu = box.querySelector('#dashboardmenu');
              host = menu ? (menu.closest('ha-dropdown') || menu.closest('ha-button-menu') || menu) : null;
            }
            if (host && host.parentNode === box) box.insertBefore(btn, host);
            else box.insertBefore(btn, box.firstChild);
            setBadge(btn, buildSnapshot(null));
            return true;
          }

          function stripNav() {
            var nav = findId(NAV);
            if (nav && nav.parentNode) nav.parentNode.removeChild(nav);
          }

          var state = window.__avaStewardConsole;
          if (!state) {
            state = {
              enabled: false,
              timer: null,
              observer: null,
              onNav: null,
              lastAllow: '',
              setEnabled: function(on) {
                this.enabled = !!on;
                if (on) arm();
                else disarm();
                return on ? 'console-on' : 'console-off';
              },
              onSnapshot: function() { paintBadges(); },
              onHost: function() { paintBadges(); },
              open: openDlg
            };
            window.__avaStewardConsole = state;
          } else {
            state.onSnapshot = function() { paintBadges(); };
            state.onHost = function() { paintBadges(); };
            state.open = openDlg;
          }

          function watch(root) {
            if (!root || typeof MutationObserver === 'undefined') return;
            state.watched = state.watched || [];
            for (var i = 0; i < state.watched.length; i++) {
              if (state.watched[i].root === root) return;
            }
            var scheduled = false;
            var obs = new MutationObserver(function() {
              if (scheduled) return;
              scheduled = true;
              var run = function() { scheduled = false; inject(); };
              if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
              else setTimeout(run, 16);
            });
            obs.observe(root, { childList: true, subtree: true });
            state.watched.push({ root: root, obs: obs });
          }

          function inject() {
            if (!state.enabled) return;
            ensureCss();
            try { injectHeader(); } catch (e) {}
            try { stripNav(); } catch (e2) {}
            try {
              var root = pierce('hui-root');
              if (root && root.shadowRoot) watch(root.shadowRoot);
            } catch (e3) {}
          }

          function arm() {
            inject();
            if (!state.observer) {
              var ha = document.querySelector('home-assistant');
              if (ha && ha.shadowRoot && typeof MutationObserver !== 'undefined') {
                var scheduled = false;
                state.observer = new MutationObserver(function() {
                  if (scheduled) return;
                  scheduled = true;
                  var run = function() { scheduled = false; inject(); };
                  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
                  else setTimeout(run, 16);
                });
                state.observer.observe(ha.shadowRoot, { childList: true, subtree: true });
              }
            }
            if (!state.onNav) {
              state.onNav = function() { setTimeout(inject, 80); };
              window.addEventListener('location-changed', state.onNav);
            }
            if (!state.timer) {
              var n = 0;
              state.timer = setInterval(function() {
                inject();
                n++;
                if (n > 240) {
                  clearInterval(state.timer);
                  state.timer = null;
                }
              }, 250);
            }
          }

          function disarm() {
            closeDlg();
            if (state.timer) { clearInterval(state.timer); state.timer = null; }
            if (state.observer) { state.observer.disconnect(); state.observer = null; }
            if (state.watched) {
              for (var w = 0; w < state.watched.length; w++) {
                try { state.watched[w].obs.disconnect(); } catch (e) {}
              }
              state.watched = [];
            }
            if (state.onNav) {
              window.removeEventListener('location-changed', state.onNav);
              state.onNav = null;
            }
            var hdr = findId(HDR);
            if (hdr && hdr.parentNode) hdr.parentNode.removeChild(hdr);
            var menu = findId(MENU);
            if (menu && menu.parentNode) menu.parentNode.removeChild(menu);
            var nav = findId(NAV);
            if (nav && nav.parentNode) nav.parentNode.removeChild(nav);
            var css = document.getElementById(CSS);
            if (css && css.parentNode) css.parentNode.removeChild(css);
          }

          var want = true;
          try {
            if (window.__avaEntityTrim && window.__avaEntityTrim.enabled === false) want = false;
          } catch (e) {}
          state.setEnabled(want);
          return want ? 'console-on' : 'console-ready';
        })();
    """.trimIndent()
}
